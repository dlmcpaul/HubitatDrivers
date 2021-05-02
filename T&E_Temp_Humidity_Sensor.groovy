/**
 *  T&E ZigBee Temperature Humidity Sensor
 *  Version 1.2
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *
 * Based on various other drivers including Xiaomi Aqara Mijia Sensors and Switches, Nue Temp Humidity Sensor and others
 * Uses Zigbee documentation where available
 *
 * TODO
 * - Configuration and timings of reporting needs work (Each device has different defaults and seems to respond differently to configure commands)
 * - Understand and implement handling of the catchall events not yet recognised
 * - parse is multi-threaded which makes the queueing mechanism a problem and the various syncronisation options don't seem to work
 * 
 */

/* Some notes
   - All these devices are known as Sleepy End Devices
   - Sleepy devices only seem to accept commands on initialisation (usually from joining) or when sending a report (and then only for a limited time [how many ms?])
   - So to do anything outside the above constraints means waiting for the device to report in and then sending the request.
   - It looks like it is up to the driver to hold commands until a report arrives
   - This driver has a MVP implementation that basically works but more effort is is needed around multi-threading (No idea why I cannot create a real static semaphore)
   - Accuracy of the sensors is all over the place even within the same manufacturer

This driver now handles 3 manufacturer and models.  Each one responds a little different despite being the same profile and returning the same values (temp/humidity)

=====================================================
Device     | Default Reporting | Identify Implemented
=====================================================
T&H        | Ok                | No
-----------------------------------------------------
Aqara      | Ok                | No
-----------------------------------------------------
Sonoff     | Too Fast          | Yes
=====================================================

*/

metadata {
	definition (name: "T&E Temp Humidity Sensor", namespace: "hzindustries", author: "David McPaul") {
		capability "TemperatureMeasurement"
		capability "RelativeHumidityMeasurement"
		capability "Battery"
		capability "PresenceSensor"
		capability "Configuration"
		capability "Refresh"
		
		attribute "voltage", "number"

		command "reconfigure"
		command "refreshAll"
		command "resetToDefaults"
		command "identify"
		
		// Home Automation Profile = 0104 (clusterId 0000 = base info, clusterId 0001 = battery (perc, volt), clusterId 0003 = identify, clusterId 0402 = temperature, clusterId 0403 = pressure, clusterId 0405 = humidity)
		fingerprint profileId: "0104", deviceId: "", inClusters: "0000,0001,0003,0402,0405", outClusters:"0003,0402,0405", manufacturer:"TUYATEC-Bfq2i2Sy", model:"RH3052", deviceJoinName: "T&H Temp Humidity Sensor"
		fingerprint profileId: "0104", deviceId: "", inClusters: "0000,0003,FFFF,0402,0403,0405", outClusters: "0000,0004,FFFF", manufacturer: "LUMI", model: "lumi.weather", deviceJoinName: "Aqara Temp Humidity Pressure Sensor"
		fingerprint profileId: "0104", deviceId: "", inClusters: "0000,0001,0003,0402,0405", outClusters: "0003", manufacturer: "eWeLink", model: "TH01", deviceJoinName: "Sonoff Temp Humidity Sensor"
	}
	preferences {
		input name: "checkHealth", type: "bool", title: "Enable Health Check", description: "Track the health of the device by checking when the device reports and if no report occurs within the time defined then report as not present.  Devices that fail to report will likely need to be rediscovered", defaultValue: true
		input name: "healthTimeout", type: "enum", title: "Health Timeout", description: "The number of hours before a device is considered 'not present'.", defaultValue: "12", options: ["1","2","3","4","6","12","24"]
		input name: "temperatureOffset", type: "number", title: "Temperature Offset", description: "This setting compensates for an inaccurate temperature sensor. For example, set to -7 if the temperature is 7 degress too warm.", defaultValue: "0"
		input name: "humidityOffset", type: "number", title: "Humidity Offset", description: "This setting compensates for an inaccurate humidity sensor. For example, set to -7 if the humidity is 7% too high.", defaultValue: "0"
	}
}

// Mutex to allow for single threading
import groovy.transform.Field
@Field static java.util.concurrent.Semaphore mutex = new java.util.concurrent.Semaphore(1)

// Callbacks

// Parse can be handling multiple event simultaneously (need to single thread the queue mechanism)
def parse(String description) {

	deviceReported()
	
	def descMap = zigbee.parseDescriptionAsMap(description)
	def label = "Unknown"
	
	if (descMap.cluster != null || descMap.clusterId != null) {
		def lookup = descMap.cluster != null ? descMap.cluster : descMap.clusterId
		def cluster = zigbee.clusterLookup(lookup)
		label = cluster == null ? "Lookup failed for cluster ${lookup}" : cluster.clusterLabel == null ? "Unknown" : cluster.clusterLabel
	}
	
	if (description?.startsWith("read attr")) {
		if (descMap.cluster == "0000" && descMap.attrId == "0001") {
			log.info "${device.displayName} Application Version ${descMap.value}"
		} else if (descMap.cluster == "0000" && descMap.attrId == "0004") {
			log.info "${device.displayName} Manufacturer Name ${descMap.value}"
		} else if (descMap.cluster == "0000" && descMap.attrId == "0005") {
			log.info "${device.displayName} Model ID ${descMap.value}"
		} else if (descMap.cluster == "0000" && descMap.attrId == "0006") {
			log.info "${device.displayName} Date Code ${descMap.value}"
		} else if (descMap.cluster == "0000" && descMap.attrId == "FF01" && descMap.value.size() > 20) {
			//log.debug "${device.displayName} Xiaomi data ${descMap.raw[22..27]}"
			if (descMap.raw[22..23] == "21") {
				batteryVoltageEvent(Integer.parseInt(descMap.raw[26..27] + descMap.raw[24..25], 16) / 100)
			}
		} else if (descMap.cluster == "0001" && descMap.attrId == "0020") {
			batteryVoltageEvent(Integer.parseInt(descMap.value, 16))
		} else if (descMap.cluster == "0001" && descMap.attrId == "0021") {
			batteryPercentageEvent(Integer.parseInt(descMap.value, 16))
		} else if (descMap.cluster == "0402" && descMap.attrId == "0000") {
			temperatureEvent(hexStrToSignedInt(descMap.value))
		} else if (descMap.cluster == "0403" && descMap.attrId == "0000") {
			pressureEvent(Integer.parseInt(descMap.value, 16))
		} else if (descMap.cluster == "0405" && descMap.attrId == "0000") {
			humidityEvent(Integer.parseInt(descMap.value, 16))
		} else {
			log.error "${device.displayName} unknown cluster and attribute ${descMap} with data size ${descMap.value.size()}"
		}
	} else if (description?.startsWith("catchall")) {
		if (descMap.clusterId == "0001" && descMap.command == "07") {	// How to differentiate configure responses here
			logConfigureResponse(descMap.clusterId, "battery percentage or voltage", descMap.data[0])
		} else if (descMap.clusterId == "0003" && descMap.command == "0B") {
			if (descMap.data[0] == "00") {
				log.info "${device.displayName} Identify Successfull ${descMap.data}"
			} else if (descMap.data[0] == "40") {
				log.info "${device.displayName} Trigger Effect Successfull ${descMap.data}"
			} else {
				log.info "${device.displayName} Unknown Response from Identify cluster ${descMap.data}"
			}
		} else if (descMap.clusterId == "0006" && descMap.command == "07") {
			logConfigureResponse(descMap.clusterId, "", descMap.data[0])
		} else if (descMap.clusterId == "0013") {
			log.info "${device.displayName} cluster ${descMap.clusterId} device announce? ${descMap.data}"
		} else if (descMap.clusterId == "0402" && descMap.command == "07") {
			logConfigureResponse(descMap.clusterId, "temperature", descMap.data[0])
		} else if (descMap.clusterId == "0405" && descMap.command == "07") {
			logConfigureResponse(descMap.clusterId, "humidity", descMap.data[0])
		} else if (descMap.clusterId == "8021" && descMap.command == "00") {
			log.info "${device.displayName} Bind Successfull with sequence ${descMap.data[0]}"
		} else {
			log.warn "${device.displayName} unsupported catchall with map ${descMap}"
		}
	} else {
		log.error "${device.displayName} unsupported description ${description} with map ${descMap}"
	}

	return [:]
}

void installed() {
	log.debug "${device.displayName} installed() called"
	device.updateSetting("checkHealth",[value:"true",type:"bool"])
	device.updateSetting("healthTimeout",[value:"12",type:"enum"])
	device.updateSetting("temperatureOffset",[value:"0",type:"number"])
	device.updateSetting("humidityOffset",[value:"0",type:"number"])
	state.clear()
	state.queuedCommand = "none"
}

void uninstalled() {
	log.debug "${device.displayName} uninstalled() called"
	state.clear()
	unschedule()
}

// Called when preferences saved
void updated() {
	log.debug "${device.displayName} updated() called"
	state.clear()
	state.queuedCommand = "none"
	resetHealthCheck()
}

def refresh() {
	log.debug "${device.displayName} refresh() requested"
	state.clear()
	state.queuedCommand = "refreshAll"
	return getRefreshCmds()
}

// This only seems to work when called as part of device join.
def configure() {
	log.debug "${device.displayName} configure() requested"
	state.clear()
	state.queuedCommand = "none"
	resetHealthCheck()
	return getConfigureCmds()
}

// Internal Functions

void resetHealthCheck() {
	unschedule()
	if (checkHealth != false) {
		startHealthCheck()
	}
}

void logConfigureResponse(cluster, attribute, code) {
	if (code == "00") {
		log.info "${device.displayName} cluster ${cluster} successful configure reporting response for ${attribute}"
	} else if (code == "86") {
		log.error "${device.displayName} cluster ${cluster} UNSUPPORTED_ATTRIBUTE passed to configure ${attribute}"
	} else if (code == "8D") {
		log.error "${device.displayName} cluster ${cluster} INVALID_DATA_TYPE passed to configure ${attribute}"
	}
}

void deviceReported() {
	try {
		// synchronize this method
		mutex.acquire()
		
		sendDelayedCmds()
		if (checkHealth != false) {	// Null or True
			unschedule(reportHealthCheckFail)
			if (device.currentValue("presence") != "present") {
				present()
			}
			startHealthCheck()
		}
	} catch (InterruptedException e) {
		e.printStackTrace();
	} finally {
		mutex.release()
	}
}

void sendZigbeeCommands(List<String> cmds) {
	log.debug "${device.displayName} sendZigbeeCommands received : ${cmds}"
	sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

void sendZigbeeCommands(List<String> cmds, Long delay) {
	sendZigbeeCommands(delayBetween(cmds, delay))
}

void resetToDefaults() {
	state.queuedCommand = "resetToDefaults"
	log.info "${device.displayName} queued command resetToDefaults"
}

void refreshAll() {
	state.queuedCommand = "refreshAll"
	log.info "${device.displayName} queued command refreshAll"
}

void reconfigure() {
	state.queuedCommand = "reconfigure"
	log.info "${device.displayName} queued command reconfigure"
}

void identify() {
	// Try sending immediately then queue it
	sendZigbeeCommands(getIdentifyCmds(), 500)
	state.queuedCommand = "identify"
	log.info "${device.displayName} queued command identify"
}

List<String> getRefreshCmds() {
	List<String> cmds = []

	cmds += zigbee.readAttribute(0x0000, [0x0001, 0x0004, 0x0005, 0x0006])  // App Version, Manufacturer Name, Model ID, Date Code
	cmds += zigbee.readAttribute(0x0001, [0x0020, 0x0021])                  // Battery Voltage & Battery % remaining
	cmds += zigbee.readAttribute(0x0402, 0x0000)                            // Temperature
	cmds += zigbee.readAttribute(0x0405, 0x0000)                            // Humidity
	
	return cmds
}

List<String> getConfigureCmds() {
	List<String> cmds = []

	//List configureReporting(Integer clusterId, Integer attributeId, Integer dataType, Integer minReportTime, Integer maxReportTime, Integer reportableChange = null, Map additionalParams=[:], int delay = STANDARD_DELAY_INT)
	cmds += zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, 0, 3600, 100, [:], 500)  // Configure temperature - Report once per hour 
	cmds += zigbee.configureReporting(0x0405, 0x0000, DataType.UINT16, 0, 3600, 100, [:], 500) // Configure Humidity - Report once per hour
	cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 0, 21600, 1, [:], 500)   // Configure Battery Voltage - Report once per 6hrs or if a change of 100mV detected
	cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 0, 21600, 1, [:], 500)   // Configure Battery % - Report once per 6hrs or if a change of 1% detected

	return cmds
}

List<String> getResetToDefaultsCmds() {
	List<String> cmds = []

	cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 0, 0xFFFF, null, [:], 500)   // Reset Battery Voltage reporting to default
	cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 0, 0xFFFF, null, [:], 500)   // Reset Battery % reporting to default
	cmds += zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, 0, 0xFFFF, null, [:], 500)	// Reset temperature reporting to default (looks to be 1/2 hr reporting)
	cmds += zigbee.configureReporting(0x0405, 0x0000, DataType.UINT16, 0, 0xFFFF, null, [:], 500)   // Reset Humidity reporting to default (looks to be 1/2 hr reporting)

	//cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}"
	//cmds += "he cr 0x${device.deviceNetworkId} 0x01 0x0001 0x0020 0x20 0xFFFF 0x0000 {0000}"
	//cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}"
	//cmds += "he cr 0x${device.deviceNetworkId} 0x01 0x0001 0x0021 0x20 0xFFFF 0x0000 {0000}"
	//cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0402 {${device.zigbeeId}} {}"
	//cmds += "he cr 0x${device.deviceNetworkId} 0x01 0x0402 0x0000 0x29 0xFFFF 0x0000 {0000}"
	//cmds += "zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0405 {${device.zigbeeId}} {}"
	//cmds += "he cr 0x${device.deviceNetworkId} 0x01 0x0405 0x0000 0x21 0xFFFF 0x0000 {0000}"

	return cmds
}

def intTo16bitUnsignedHex(value) {
	def hexStr = zigbee.convertToHexString(value.toInteger(),4)
	return new String(hexStr.substring(2, 4) + hexStr.substring(0, 2))
}

def intTo8bitUnsignedHex(value) {
	return zigbee.convertToHexString(value.toInteger(), 2)
}

private getEFFECT_BLINK() { 0 }
private getEFFECT_BREATHE() { 1 }
private getEFFECT_OK() { 2 }
private getEFFECT_CHANNEL_CHANGE() { 11 }
private getEFFECT_FINISH() { 254 }
private getEFFECT_STOP() { 255 }

private getIDENTIFY_CMD_IDENTIFY() { 0x00 }
private getIDENTIFY_CMD_QUERY() { 0x01 }
private getIDENTIFY_CMD_TRIGGER() { 0x40 }

List<String> getIdentifyCmds() {

	List<String> cmds = []
	// Identify for 60 seconds
	cmds += "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0003 ${IDENTIFY_CMD_IDENTIFY} { 0x${intTo16bitUnsignedHex(60)} }"
	// Trigger Effect
	//cmds += "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x0003 ${IDENTIFY_CMD_TRIGGER} { 0x${intTo8bitUnsignedHex(EFFECT_BREATHE)} 0x${intTo8bitUnsignedHex(0)} }"

	return cmds;
}

void sendDelayedCmds() {

	if (state.queuedCommand != null && state.queuedCommand != "none") {
		log.debug "${device.displayName} sending delayed command ${state.queuedCommand}"
		if (state.queuedCommand == "resetToDefaults") {
			sendZigbeeCommands(getResetToDefaultsCmds())
		} else if (state.queuedCommand == "refreshAll") {
			sendZigbeeCommands(getRefreshCmds(), 500)
		} else if (state.queuedCommand == "reconfigure") {
			sendZigbeeCommands(getConfigureCmds())
		} else if (state.queuedCommand == "identify") {
			sendZigbeeCommands(getIdentifyCmds(), 500)
		}
	}
	state.clear()
	state.queuedCommand = "none"
}

def startHealthCheck() {
	if (healthTimeout == null) healthTimeout = "12"
	def timeoutAsHours = healthTimeout.toInteger() * 60 * 60
	runIn(timeoutAsHours, "reportHealthCheckFail")
}

def reportHealthCheckFail() {
	notPresent()
}

// Events generated

def temperatureEvent(rawValue) {
	// rawValue represents the temperature in degrees Celsius as follows: 
	// Value = 100 x temperature in degrees Celsius. Where -273.15°C <= temperature <= 327.67 ºC, corresponding to a Value in the range 0x954d to 0x7fff. 
	// The maximum resolution this format allows is 0.01 ºC. 
	// A Value of 0x8000 indicates that the temperature measurement is invalid
	
	if (rawValue != 32768) {
		BigDecimal offset = temperatureOffset ? new BigDecimal(temperatureOffset).setScale(2, BigDecimal.ROUND_HALF_UP) : 0
		BigDecimal temp = new BigDecimal(rawValue).setScale(2, BigDecimal.ROUND_HALF_UP) / 100

		// Apply offset and convert to F if location scale set to F
		temp = (location.temperatureScale == "F") ? ((temp * 1.8) + 32) + offset : temp + offset
	
		sendEvent("name": "temperature", "value": temp, "unit": "\u00B0" + location.temperatureScale)
		log.info "${device.displayName} temperature changed to ${temp}\u00B0 ${location.temperatureScale} calculated from raw value ${rawValue} and offset ${offset}"
	} else {
		log.error "${device.displayName} temperature read failed"
	}
}

def humidityEvent(rawValue) {
	// Value represents the relative humidity in % as follows: 
	// Value = 100 x Relative humidity Where 0% <= Relative humidity <= 100%, corresponding to a value in the range 0 to 0x2710.
	// The maximum resolution this format allows is 0.01%.
	// A value of 0xffff indicates that the measurement is invalid.
	
	if (rawValue != 65535 && rawValue <= 10000) {
		BigDecimal offset = humidityOffset ? new BigDecimal(humidityOffset).setScale(2, BigDecimal.ROUND_HALF_UP) : 0
		BigDecimal humidity = new BigDecimal(rawValue).setScale(2, BigDecimal.ROUND_HALF_UP) / 100 + offset
		sendEvent("name": "humidity", "value": humidity, "unit": "%")
		log.info "${device.displayName} humidity changed to ${humidity}% calculated from raw value ${rawValue} and offset ${offset}"
	} else {
		log.error "${device.displayName} humidity read failed"
	}
}

def pressureEvent(rawValue) {
	// Value represents the pressure in kPa as follows: 
	// Value = 10 x Pressure where -3276.7 kPa <= Pressure <= 3276.7 kPa, corresponding to a value in the range 0x8001 to 0x7fff.
	// A Valueof 0x8000 indicates that the pressure measurement is invalid.
	if (rawValue != 32768) {
		Integer pressure = rawValue	// Divide by 10 for kPa or leave for hPa
		sendEvent("name": "pressure", "value": pressure, "unit": "hPa")
		log.info "${device.displayName} pressure changed to ${pressure} hPa"
	} else {
		log.error "${device.displayName} pressure read failed"
	}
}

def batteryVoltageEvent(rawValue) {
	// The BatteryVoltage attribute is 8 bits in length and specifies the current actual (measured) battery voltage, in units of 100mV
	BigDecimal batteryVolts = new BigDecimal(rawValue).setScale(2, BigDecimal.ROUND_HALF_UP) / 10

	if (batteryVolts > 0){
		sendEvent("name": "voltage", "value": batteryVolts, "unit": "volts")
		log.info "${device.displayName} voltage changed to ${batteryVolts}V"
	}
	
	if (device.currentValue("battery") == null) {
		// Guess at percentage remaining
		def minVolts = 20
		def maxVolts = 30
		def pct = (((rawValue - minVolts) / (maxVolts - minVolts)) * 100).toInteger()
		def batteryValue = Math.min(100, pct)
		sendEvent("name": "battery", "value": batteryValue, "unit": "%")
		log.info "${device.displayName} battery % remaining changed to ${batteryValue}%"
	}
}

def batteryPercentageEvent(rawValue) {
	// The BatteryPercentageRemaining attribute specifies the remaining battery life as a half integer percentage of the full battery capacity
	// (e.g., 34.5%, 45%, 68.5%, 90%) with a range between zero and 100%, with 0x00 = 0%, 0x64 = 50%, and 0xC8 = 100%
	// A value of 0xff indicates that the measurement is invalid.
	if (rawValue != 255) {
		Float pct = rawValue / 2
		def batteryValue = Math.min(100, pct)
	
		sendEvent("name": "battery", "value": batteryValue, "unit": "%")
		log.info "${device.displayName} battery % remaining changed to ${batteryValue}%"
	} else {
		log.error "${device.displayName} battery % remaining read failed"
	}
}

// Not sure if using the precense sensor is good for this?
def present() {
	sendEvent("name": "presence", "value":  "present", isStateChange: true)
	log.info "${device.displayName} contact changed to present"
}

def notPresent() {
	sendEvent("name": "presence", "value":  "not present", isStateChange: true)
	log.warn "${device.displayName} contact changed to not present"
}

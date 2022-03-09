/**
 *  T&H ZigBee Temperature Humidity Sensor
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
 * 
 */

/* Some notes
   - All these devices are known as Sleepy End Devices
   - Sleepy devices only seem to accept commands on initialisation (usually from joining) or when sending a report (and then only for a limited time [how many ms?])
   - So to do anything outside the above constraints means waiting for the device to report in and then sending the request.
   - It looks like it is up to the driver to hold commands until a report arrives
   - Accuracy of the sensors is all over the place even within the same manufacturer

This driver now handles 3 manufacturer and models.  Each one responds a little different despite being the same profile and returning the same values (temp/humidity)

=====================================================================================================================
Device     | Default Reporting | Identify Implemented | Cluster 0000 Reporting                           | Battery
=====================================================================================================================
T&H        | Ok                | No                   | Model Id, Manufacturer Name, Version, Date Code  | 3V (CR2032)
---------------------------------------------------------------------------------------------------------------------
Aqara      | Ok                | No                   | No Reporting                                     | 3.2V
---------------------------------------------------------------------------------------------------------------------
Sonoff     | Too Fast          | Yes                  | Version                                          | 3.2V
=====================================================================================================================

*/

metadata {
	definition (name: "T&H Temp Humidity Sensor", namespace: "hzindustries", author: "David McPaul") {
		capability "TemperatureMeasurement"
		capability "RelativeHumidityMeasurement"
		capability "PressureMeasurement"
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

public static String version()	  {  return "v1.3.1"  }

import groovy.transform.Field
import java.util.concurrent.*

// Field annotation makes these variables global to the class
@Field static java.util.concurrent.Semaphore mutex = new java.util.concurrent.Semaphore(1)
@Field static def queueMap = [:]

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
	
	//log.info "${device.displayName} ${device.getDataValue("manufacturer")} ${device.getDataValue("model")}"
	
	if (description?.startsWith("read attr")) {
		if (descMap.cluster == "0000" && descMap.attrId == "0001") {
			log.info "${device.displayName} Application Version ${descMap.value}"
			state.version = descMap.value
		} else if (descMap.cluster == "0000" && descMap.attrId == "0004") {
			log.info "${device.displayName} Manufacturer Name ${descMap.value}"
			state.manufacturerName = descMap.value
            identifyDevice()
		} else if (descMap.cluster == "0000" && descMap.attrId == "0005") {
			log.info "${device.displayName} Model ID ${descMap.value}"
			state.modelId = descMap.value
		} else if (descMap.cluster == "0000" && descMap.attrId == "0006") {
			log.info "${device.displayName} Date Code ${descMap.value}"
		} else if (descMap.cluster == "0000" && descMap.attrId == "FF01" && descMap.value.size() > 20) {
			log.debug "${device.displayName} Xiaomi data ${descMap.raw[22..27]}"
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
		if (descMap.clusterId == "0013") {
			log.info "${device.displayName} cluster ${descMap.clusterId} device announce? ${descMap.data}"
		} else if (descMap.clusterId == "8021") {
			log.info "${device.displayName} Bind Successfull with sequence ${descMap.data[0]} and command ${descMap.command}"
		} else if (descMap.command == "01") {    // Default response to a command - For us it indicates unsupported request
			if (descMap.clusterId == "0403") {
				log.info "${device.displayName} Pressure Refresh Ignored ${descMap.data}"
			}
		} else if (descMap.command == "07") {  // Configuration responses
			if (descMap.clusterId == "0001") {
				logConfigureResponse(descMap.clusterId, "battery percentage or voltage", descMap.data[0])  // How to differentiate configure responses here for cluster 0001
			} else if (descMap.clusterId == "0006") {
				logConfigureResponse(descMap.clusterId, "", descMap.data[0])
			} else if (descMap.clusterId == "0402") {
				log.info "${device.displayName} Temperature configuration Successfull ${descMap.data}"
			} else if (descMap.clusterId == "0403") {
				log.info "${device.displayName} Pressure configuration Successfull ${descMap.data}"
			} else if (descMap.clusterId == "0405") {
				log.info "${device.displayName} Humidity configuration Successfull ${descMap.data}"
			}
		} else if (descMap.command == "0B") {  // Default responses
			if (descMap.clusterId == "0003") {
				if (descMap.data[0] == "00") {
					log.info "${device.displayName} Identify Successfull ${descMap.data}"
				} else if (descMap.data[0] == "40") {
					log.info "${device.displayName} Trigger Effect Successfull ${descMap.data}"
				} else {
					log.info "${device.displayName} Unknown Response from Identify cluster ${descMap.data}"
				}
			} else if (descMap.clusterId == "0402") {
				log.info "${device.displayName} Temperature configuration Successfull ${descMap.data}"
			} else if (descMap.clusterId == "0403") {
				log.info "${device.displayName} Pressure configuration Successfull ${descMap.data}"
			} else if (descMap.clusterId == "0405") {
				log.info "${device.displayName} Humidity configuration Successfull ${descMap.data}"
			}
		} else {
			log.warn "${device.displayName} unsupported catchall with map ${descMap}"
		}
	} else {
		log.error "${device.displayName} unsupported description ${description} with map ${descMap}"
	}

	return [:]
}

void identifyDevice() {
    log.info "${device.displayName} trying to identify device using manufacturer ${device.getDataValue("manufacturer")} & model ${device.getDataValue("model")}"
    
    if ((device.getDataValue("manufacturer") == "eWeLink") && (device.getDataValue("model") == "TH01")) {
        state.manufacturerName = "Sonoff"
        state.deviceName = "Sonoff Temperature & Humidity Sensor"
    } else if ((device.getDataValue("manufacturer") == "TUYATEC-Bfq2i2Sy") && (device.getDataValue("model") == "RH3052")) {
        state.manufacturerName = "TUYATEC"
        state.deviceName = "T&H Temperature & Humidity Sensor"
    } else if ((device.getDataValue("manufacturer") == "LUMI") && (device.getDataValue("model") == "lumi.weather")) {
        state.manufacturerName = "Xiaomi"
        state.deviceName = "Aqara Smart Temperature & Humidity Sensor"
    } else {
        state.manufacturerName = device.getDataValue("manufacturer")
        state.deviceName = device.getDataValue("model")
    }
}

void installed() {
	log.info "${device.displayName} installed() called"
	state.clear()
	device.updateSetting("checkHealth",[value:"true",type:"bool"])
	device.updateSetting("healthTimeout",[value:"12",type:"enum"])
	device.updateSetting("temperatureOffset",[value:"0",type:"number"])
	device.updateSetting("humidityOffset",[value:"0",type:"number"])
	updateDataValue("calcBattery", "true")	// Calculate Battery Perc until an Battery Perc event is sent
    identifyDevice()
}

void uninstalled() {
	log.info "${device.displayName} uninstalled() called"
	state.clear()
	unschedule()
}

// Called when preferences saved
void updated() {
	log.info "${device.displayName} updated() called"
	state.clear()
    identifyDevice()
	resetHealthCheck()
}

def refresh() {
	log.info "${device.displayName} refresh() requested"
	state.clear()
    identifyDevice()
	refreshAll()
	return getRefreshCmds()
}

// This only seems to work when called as part of device join.
def configure() {
	log.info "${device.displayName} configure() requested"
	state.clear()
	updateDataValue("calcBattery", "true")	// Calculate Battery Perc until an Battery Perc event is sent
	resetHealthCheck()
    identifyDevice()

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
	state.clear()
	updateDataValue("calcBattery", "true")	// Calculate Battery Perc until an Battery Perc event is sent
	addToQueue("resetToDefaults")
}

void refreshAll() {
	addToQueue("refreshAll")
}

void reconfigure() {
	state.clear()
	updateDataValue("calcBattery", "true")	// Calculate Battery Perc until an Battery Perc event is sent
	addToQueue("reconfigure")
}

void identify() {
	// Try sending immediately then queue it
	sendZigbeeCommands(getIdentifyCmds(), 500)
	addToQueue("identify")
	log.info "${device.displayName} queued command identify"
}

List<String> getRefreshCmds() {
	List<String> cmds = []

	cmds += zigbee.readAttribute(0x0000, 0x0004)  // Manufacturer Name
	cmds += zigbee.readAttribute(0x0000, 0x0005)  // Model ID
	cmds += zigbee.readAttribute(0x0000, 0x0001)  // App Version
	cmds += zigbee.readAttribute(0x0000, 0x0006)  // Date Code
	cmds += zigbee.readAttribute(0x0000, 0xFF01)  // Xiaomi Voltage
	cmds += zigbee.readAttribute(0x0001, 0x0020)  // Battery Voltage
	cmds += zigbee.readAttribute(0x0001, 0x0021)  // Battery % remaining
	cmds += zigbee.readAttribute(0x0402, 0x0000)  // Temperature
	cmds += zigbee.readAttribute(0x0403, 0x0000)  // Pressure
	cmds += zigbee.readAttribute(0x0405, 0x0000)  // Humidity
	
	return cmds
}

List<String> getConfigureCmds() {
	List<String> cmds = []

	//List configureReporting(Integer clusterId, Integer attributeId, Integer dataType, Integer minReportTime, Integer maxReportTime, Integer reportableChange = null, Map additionalParams=[:], int delay = STANDARD_DELAY_INT)
	cmds += zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, 300, 3600, 100, [:], 500)  // Configure temperature - Report once per hour 
	cmds += zigbee.configureReporting(0x0403, 0x0000, DataType.INT16, 300, 3600, 100, [:], 500)  // Configure Pressure - Report once per hour
	cmds += zigbee.configureReporting(0x0405, 0x0000, DataType.INT16, 300, 3600, 100, [:], 500)  // Configure Humidity - Report once per hour
	// Xiaomi does not report battery using 0x0001
	if (state.manufacturerName == "Xiaomi") {
		cmds += zigbee.configureReporting(0x0000, 0xFF01, DataType.UINT8, 0, 21600, 1, [:], 500)   // Configure Voltage - Report once per 6hrs or if a change of 100mV detected
	} else {
		cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 0, 21600, 1, [:], 500)   // Configure Voltage - Report once per 6hrs or if a change of 100mV detected
		cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 0, 21600, 1, [:], 500)   // Configure Battery % - Report once per 6hrs or if a change of 1% detected
	}

	return cmds
}

List<String> getResetToDefaultsCmds() {
	List<String> cmds = []

    if (state.manufacturerName == "Xiaomi") {
        cmds += zigbee.configureReporting(0x0000, 0xFF01, DataType.UINT8, 0, 0xFFFF, null, [:], 500)	// Reset Battery Voltage reporting to default
    } else {
        cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 0, 0xFFFF, null, [:], 500)	// Reset Battery Voltage reporting to default
	    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 0, 0xFFFF, null, [:], 500)	// Reset Battery % reporting to default
    }
	cmds += zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, 0, 0xFFFF, null, [:], 500)	// Reset Temperature reporting to default (looks to be 1/2 hr reporting)
	cmds += zigbee.configureReporting(0x0403, 0x0000, DataType.INT16, 0, 0xFFFF, null, [:], 500)	// Reset Pressure reporting to default (looks to be 1/2 hr reporting)
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

void addToQueue(String command) {
	queueMap.put(device.displayName, command)
	log.info "${device.displayName} queued command " + queueMap.get(device.displayName)
}

String removeFromQueue() {
	String command = queueMap.get(device.displayName)
	if (command != null) {
		log.info "${device.displayName} reading command " + command
		queueMap.put(device.displayName, null)
	}
	return command
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

	String command = removeFromQueue()
	if (command != null) {
		log.debug "${device.displayName} sending delayed command ${command}"
		if (command == "resetToDefaults") {
			sendZigbeeCommands(getResetToDefaultsCmds())
		} else if (command == "refreshAll") {
			sendZigbeeCommands(getRefreshCmds(), 500)
		} else if (command == "reconfigure") {
			sendZigbeeCommands(getConfigureCmds())
		} else if (command == "identify") {
			sendZigbeeCommands(getIdentifyCmds(), 500)
		}
	}
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
		log.info "${device.displayName} pressure changed to ${pressure} hPa calculated from raw value ${rawValue}"
	} else {
		log.error "${device.displayName} pressure read failed"
	}
}

def batteryVoltageEvent(rawValue) {
	// The BatteryVoltage attribute is 8 bits in length and specifies the current actual (measured) battery voltage, in units of 100mV
	BigDecimal batteryVolts = new BigDecimal(rawValue).setScale(2, BigDecimal.ROUND_HALF_UP) / 10

	if (batteryVolts > 0){
		sendEvent("name": "voltage", "value": batteryVolts, "unit": "volts")
		log.info "${device.displayName} voltage changed to ${batteryVolts}V calculated from raw value ${rawValue}"
	
		if (getDataValue("calcBattery") == null || getDataValue("calcBattery") == "true") {
			updateDataValue("calcBattery", "true")	// We will calculate until a battery perc event occurs
			// Guess at percentage remaining
			// Battery percantage is not a linear relationship to voltage
			// Should try to do this as a table with more ranges
			def batteryValue = 100.0
            if (state.manufacturerName == "TUYATEC") {
                // Battery used is a 3V Battery
    			if (rawValue < 20.01) {
	    			batteryValue = 0.0
		    	} else if (rawValue < 24.01) {
			    	batteryValue = 10.0
			    } else if (rawValue < 25.01) {
			    	batteryValue = 20.0
			    } else if (rawValue < 26.01) {
			    	batteryValue = 30.0
			    } else if (rawValue < 27.01) {
				    batteryValue = 40.0
			    } else if (rawValue < 27.51) {
				    batteryValue = 50.0
    			} else if (rawValue < 28.01) {
	    			batteryValue = 60.0
		    	} else if (rawValue < 28.51) {
			    	batteryValue = 75.0
    			} else if (rawValue < 29.01) {
	    			batteryValue = 95.0
		    	} else if (rawValue <= 29.99) {
			    	batteryValue = 99.0
			    }
            } else {
                // Battery used is a 3.2V Battery
    			if (rawValue < 20.01) {
	    			batteryValue = 0.0
		    	} else if (rawValue < 24.01) {
			    	batteryValue = 10.0
			    } else if (rawValue < 25.01) {
			    	batteryValue = 20.0
			    } else if (rawValue < 26.01) {
			    	batteryValue = 30.0
			    } else if (rawValue < 27.01) {
				    batteryValue = 40.0
			    } else if (rawValue < 27.51) {
				    batteryValue = 50.0
    			} else if (rawValue < 28.01) {
	    			batteryValue = 60.0
		    	} else if (rawValue < 28.51) {
			    	batteryValue = 70.0
    			} else if (rawValue < 29.01) {
	    			batteryValue = 80.0
		    	} else if (rawValue < 29.51) {
			    	batteryValue = 90.0
    			} else if (rawValue < 30.01) {
	    			batteryValue = 92.0
		    	} else if (rawValue < 30.51) {
			    	batteryValue = 95.0
    			} else if (rawValue < 31.01) {
	    			batteryValue = 97.0
		    	} else if (rawValue < 31.51) {
			    	batteryValue = 99.0
			    }
            }
			sendEvent("name": "battery", "value": batteryValue, "unit": "%")
			log.info "${device.displayName} battery % remaining changed to ${batteryValue}% calculated from voltage ${batteryVolts}"
		}
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
		log.info "${device.displayName} battery % remaining changed to ${batteryValue}% calculated from raw value ${rawValue}"
		updateDataValue("calcBattery", "false")	// Battery events are generated so no need to calc
	} else {
		log.error "${device.displayName} battery % remaining read failed"
	}
}

// Not sure if using the presence sensor is good for this?
def present() {
	sendEvent("name": "presence", "value":  "present", isStateChange: true)
	log.info "${device.displayName} contact changed to present"
}

def notPresent() {
	sendEvent("name": "presence", "value":  "not present", isStateChange: true)
	log.warn "${device.displayName} contact changed to not present"
}

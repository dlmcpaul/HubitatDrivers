/**
 *  T&E ZigBee Temperature Humidity Sensor
 *  Version 1.0
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
 *
 * TODO
 * - Configuration and timings of reporting needs work
 * - Understand and implement handling of catchall events
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

		command "codeTest"
		command "resetToDefaults"
		
		// Home Automation Profile = 0104
		fingerprint profileId: "0104", deviceId: "", inClusters:"0000,0001,0003,0402,0405", outClusters:"0003,0402,0405", manufacturer:"TUYATEC-Bfq2i2Sy", model:"RH3052", deviceJoinName: "T&H Zigbee Temp Humidity Sensor"
	}
	preferences {
		input name: "presenceDetect", type: "bool", title: "Enable Presence Detection", description: "This will keep track of when the device last reported and will change state if no data received within the Presence Timeout. If it does lose presence try pushing the reset button on the device if available.", defaultValue: true
		input name: "presenceHours", type: "enum", title: "Presence Timeout", description: "The number of hours before a device is considered 'not present'.<br>Note: Some of these devices only update their battery every 6 hours.", defaultValue: "12", options: ["1","2","3","4","6","12","24"]
		input name: "temperatureOffset", type: "number", title: "Temperature Offset", description: "This setting compensates for an inaccurate temperature sensor. For example, set to -7 if the temperature is 7 degress too warm.", defaultValue: "0"
		input name: "humidityOffset", type: "number", title: "Humidity Offset", description: "This setting compensates for an inaccurate humidity sensor. For example, set to -7 if the humidity is 7% too high.", defaultValue: "0"
	}
}

// Callacks

def parse(String description) {

	if (presenceDetect != false) {    // Null or True
		unschedule(presenceTracker)
		if (device.currentValue("presence") != "present") {
			present()
		}
		presenceStart()
	}
	def descMap = zigbee.parseDescriptionAsMap(description)
	if (descMap.cluster != null || descMap.clusterId != null) {
		def lookup = descMap.cluster != null ? descMap.cluster : descMap.clusterId
		def cluster = zigbee.clusterLookup(lookup)
		log.debug "${device.displayName} cluster ${cluster.clusterLabel} from message ${lookup}"
	}
	
	if (description?.startsWith("read attr")) {
		if (descMap.cluster == "0000" && descMap.attrId == "0001") {
			log.info "${device.displayName} Application Version ${descMap.value}"
		} else if (descMap.cluster == "0000" && descMap.attrId == "0004") {
			log.info "${device.displayName} Manufacturer Name ${descMap.value}"
		} else if (descMap.cluster == "0000" && descMap.attrId == "0005") {
			log.info "${device.displayName} Model ID ${descMap.value}"
		} else if (descMap.cluster == "0001" && descMap.attrId == "0020") {
			batteryVoltageEvent(Integer.parseInt(descMap.value, 16))
			return [:]
		} else if (descMap.cluster == "0001" && descMap.attrId == "0021") {
			batteryPercentageEvent(Integer.parseInt(descMap.value, 16))
			return [:]
		} else if (descMap.cluster == "0402" && descMap.attrId == "0000") {
			temperatureEvent(hexStrToSignedInt(descMap.value))
			return [:]
		} else if (descMap.cluster == "0403" && descMap.attrId == "0000") {
			pressureEvent(Integer.parseInt(descMap.value, 16))
			return [:]
		} else if (descMap.cluster == "0405" && descMap.attrId == "0000") {
			humidityEvent(Integer.parseInt(descMap.value, 16))
			return [:]
		} else if (descMap.cluster == "0000" && descMap.attrId == "FF01" && descMap.value[4..5] == "21") {
			log.info "${device.displayName} Xiaomi special data packet for Battery Voltage " + Integer.parseInt(mydescMap.value[8..9] + mydescMap.value[6..7], 16)
			batteryVoltageEvent(Integer.parseInt(mydescMap.value[8..9] + mydescMap.value[6..7], 16))
			return [:]
		} else if (descMap.cluster == "0000" && descMap.attrId == "FF01" && descMap.value[10..13] == "0328") {
			log.info "${device.displayName} Xiaomi special data packet for Internal Temperature " + descMap.value[14..15]
			return [:]
		} else {
			log.error "${device.displayName} unknown cluster and attribute ${descMap} with data size ${descMap.value.size()}"
		}
	} else if (description?.startsWith("catchall")) {
		if (descMap.clusterId == "0001" && descMap.command == 07) {
			if (descMap.data[0] == "00") {
				log.info "${device.displayName} cluster ${descMap.cluster} successful configure reporting response for battery percentage"
			} else if (descMap.data[0] == "86") {
				log.error "${device.displayName} cluster ${descMap.cluster} UNSUPPORTED_ATTRIBUTE passed to configure battery percentage"
			} else if (descMap.data[0] == "8D") {
				log.error "${device.displayName} cluster ${descMap.cluster} INVALID_DATA_TYPE passed to configure battery percentage"
			}
		} else if (descMap.clusterId == "0006" && descMap.command == 07) {
			log.info "${device.displayName} cluster ${descMap.clusterId} configure reporting response for ?"
		} else if (descMap.clusterId == "0013") {
			log.info "${device.displayName} cluster ${descMap.clusterId} device announce? ${descMap.data}"
		} else if (descMap.clusterId == "0402" && descMap.command == 07) {
			log.info "${device.displayName} cluster ${descMap.clusterId} configure reporting response for temperature"
		} else if (descMap.clusterId == "0405" && descMap.command == 07) {
			log.info "${device.displayName} cluster ${descMap.clusterId} configure reporting response for humidity"
		} else if (descMap.clusterId == "8021" && descMap.command == 07) {
			log.info "${device.displayName} cluster ${descMap.clusterId} configure reporting response for ?"
		}
		log.warn "${device.displayName} unsupported catchall ${descMap}"
	} else {
		log.error "${device.displayName} unsupported description ${description}"
	}
	return [:]
}

void installed() {
	log.debug "${device.displayName} installed() called"
	presenceDetect = true
	presenceHours = 12
	temperatureOffset = 0
	humidityOffset = 0
}

void uninstalled() {
	log.debug "${device.displayName} uninstalled() called"
}

void updated() {
	log.debug "${device.displayName} updated() called"
	unschedule(presenceTracker)
	if (presenceDetect != false) presenceStart()
}

def refresh() {
	log.debug "${device.displayName} refresh() requested"

	List<String> cmds = []

	cmds += zigbee.readAttribute(0x0000, 0x0005)    // Device ID
	cmds += zigbee.readAttribute(0x0001, 0x0020)    // Battery Voltage
	cmds += zigbee.readAttribute(0x0001, 0x0021)    // Battery % remaining
	cmds += zigbee.readAttribute(0x0402, 0x0000)    // Temperature
	cmds += zigbee.readAttribute(0x0405, 0x0000)    // Humidity

	return cmds
}

def configure() {
	log.debug "${device.displayName} configure() requested"

	List<String> cmds = []

	unschedule()
	state.clear()

	if (presenceDetect != false) presenceStart()

	//List configureReporting(Integer clusterId, Integer attributeId, Integer dataType, Integer minReportTime, Integer maxReportTime, Integer reportableChange = null, Map additionalParams=[:], int delay = STANDARD_DELAY_INT)
	cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 300, 21600, 0x01)   // Configure Battery Voltage - Report at least once per 6hrs or every 5 mins if a change of 100mV detected
	cmds += zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, 900, 3600, 0x10)    // Configure temperature - Report at least once per hour or every 15 mins if a change of 0.1C detected
	cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 300, 21600, 0x01)   // Configure Battery % - Report at least once per 6hrs or every 5 mins if a change of 1% detected
	cmds += zigbee.configureReporting(0x0405, 0x0000, DataType.UINT16, 600, 3600, 0x01)   // Configure Humidity - Report at least once per hour or every 10 mins if a change of 0.1% detected

	sendZigbeeCommands(cmds)    // Send directly instead of relying on return.  Not sure any better
	return [:]
}

// Internal Functions

void sendZigbeeCommands(List<String> cmds) {
	log.debug "${device.displayName} sendZigbeeCommands received : ${cmds}"
	sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

void sendZigbeeCommands(List<String> cmds, Long delay) {
	sendZigbeeCommands(delayBetween(cmds, delay))
}

void resetToDefaults() {
	sendZigbeeCommands(["zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}", "he cr 0x${device.deviceNetworkId} 0x01 0x0001 0x0020 0x20 0xFFFF 0x0000 {0000}"], 500)
	sendZigbeeCommands(["zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0001 {${device.zigbeeId}} {}", "he cr 0x${device.deviceNetworkId} 0x01 0x0001 0x0021 0x20 0xFFFF 0x0000 {0000}"], 500)
	sendZigbeeCommands(["zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0402 {${device.zigbeeId}} {}", "he cr 0x${device.deviceNetworkId} 0x01 0x0402 0x0000 0x29 0xFFFF 0x0000 {0000}"], 500)
	sendZigbeeCommands(["zdo bind 0x${device.deviceNetworkId} 0x01 0x01 0x0405 {${device.zigbeeId}} {}", "he cr 0x${device.deviceNetworkId} 0x01 0x0405 0x0000 0x21 0xFFFF 0x0000 {0000}"], 500)
}

// Testing Code
void codeTest() {
	//log.info "${device.displayName}  My temperature config output    " + zigbee.configureReporting(0x0402, 0x0000, DataType.INT16, 900, 3600, 0x0100)
	//sendZigbeeCommands(zigbee.temperatureConfig(900, 3600))   // Configure temperature - Report at least once per hour or every 15 mins if a change of ?C detected
	sendZigbeeCommands(zigbee.readAttribute(0x0000, 0x0004))
	sendZigbeeCommands(zigbee.readAttribute(0x0000, 0x0005))
}

def presenceStart() {
	if (presenceHours == null) presenceHours = "12"
	def scheduleHours = presenceHours.toInteger() * 60 * 60
	runIn(scheduleHours, "presenceTracker")
}

def presenceTracker() {
	notPresent()
	log.warn "${device.displayName} detected as not present"
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
		log.info "${device.displayName} temperature changed to ${temp}\u00B0 ${location.temperatureScale}"
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
		log.info "${device.displayName} humidity changed to ${humidity}%"
	} else {
		log.error "${device.displayName} humidity read failed"
	}
}

def pressureEvent(rawValue) {
	// Value represents the pressure in kPa as follows: 
	// Value = 10 x Pressure where -3276.7 kPa <= Pressure <= 3276.7 kPa, corresponding to a value in the range 0x8001 to 0x7fff.
	// A Valueof 0x8000 indicates that the pressure measurement is invalid.
	if (rawValue != 32768) {
		Integer pressure = rawValue    // Divide by 10 for kPa or leave for hPa
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

def present() {
	sendEvent("name": "presence", "value":  "present", isStateChange: true)
	log.info "${device.displayName} contact changed to present"
}

def notPresent() {
	sendEvent("name": "presence", "value":  "not present", isStateChange: true)
	log.info "${device.displayName} contact changed to not present"
}

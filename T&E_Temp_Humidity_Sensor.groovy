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
 * - Implement Humidity offset
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
    
    if (description?.startsWith("read attr")) {
        def descMap = zigbee.parseDescriptionAsMap(description)
        
        if (descMap.cluster == "0001" && descMap.attrId == "0020") {
			batteryVoltageEvent(Integer.parseInt(descMap.value, 16))
		} else if (descMap.cluster == "0001" && descMap.attrId == "0021") {
            batteryPercentageEvent(Integer.parseInt(descMap.value, 16))
		} else if (descMap.cluster == "0402" && descMap.attrId == "0000") {
            temperatureEvent(hexStrToSignedInt(descMap.value))
		} else if (descMap.cluster == "0405" && descMap.attrId == "0000") {
			humidityEvent(Integer.parseInt(descMap.value, 16))
		} else if (descMap.cluster == "0000" && descMap.attrId == "0001") {
            log.info "${device.displayName} ApplicationVersion ${descMap.value}"
        } else {
            log.error "${device.displayName} unknown cluster and attribute ${descMap}"
        }
    } else if (description?.startsWith("catchall")) {
        def descMap = zigbee.parseDescriptionAsMap(description)
        if (descMap.cluster != null) {
            def cluster = zigbee.clusterLookup(descMap.cluster)
            log.info "${device.displayName} cluster ${cluster.clusterLabel} reported"
        }
        if (descMap.cluster == "0001") {
            // 
        } else if (descMap.cluster == "0006") {
            // Device On Off?
            if (descMap.command == "01") {
                log.info "${device.displayName} turned on"
            }
        } else if (descMap.cluster == "8021") {
        }
        log.warn "${device.displayName} unsupported catchall ${descMap}"
    } else {
        log.error "${device.displayName} unsupported description ${description}"
    }
    
    if (presenceDetect != false) {
		unschedule(presenceTracker)
		if (device.currentValue("presence") != "present") {
            present()
		}
		presenceStart()
	}
}

void installed() {
	log.debug "${device.displayName} installed() called"
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

    List<String> cmd = []

	cmd += zigbee.onOffRefresh()

	cmd += zigbee.readAttribute(0x0001, 0x0020)    // Battery Voltage
	cmd += zigbee.readAttribute(0x0001, 0x0021)    // Battery % remaining
	cmd += zigbee.readAttribute(0x0402, 0x0000)    // Temperature
	cmd += zigbee.readAttribute(0x0405, 0x0000)    // Humidity

    log.info "${device.displayName} refresh() sending commands ${cmd}"

	return cmd
}

def configure() {
	log.debug "${device.displayName} configure() requested"

    Integer zDelay = 100
	List<String> cmd = []

	unschedule()
	state.clear()

	if (presenceDetect != false) presenceStart()

	cmd = []

    cmd += zigbee.onOffConfig()          // Predefined configureReporting for OnOff (0x0006, 0x0000)
    cmd += zigbee.batteryConfig()        // Predefined configureReporting for batteries (0x0001, 0x0020)
    cmd += zigbee.temperatureConfig()    // Predefined configureReporting for temperature (0x0402, 0x0000)
    
    //List configureReporting(Integer clusterId, Integer attributeId, Integer dataType, Integer minReportTime, Integer maxReportTime, Integer reportableChange = null, Map additionalParams=[:], int delay = STANDARD_DELAY_INT)
	cmd += zigbee.configureReporting(0x0001, 0x0021, 0x20, 30, 21600, 1)   // Configure Battery %
	cmd += zigbee.configureReporting(0x0405, 0x0000, 0x20, 30, 3600, 1)    // Configure Humidity

    log.info "${device.displayName} configure() sending commands ${cmd}"

	return cmd
}

// Internal Functions

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
        Float temp = rawValue / 100
        Float offset = temperatureOffset ? temperatureOffset : 0

        // Apply offset and convert to F if location scale set to F
        temp = (location.temperatureScale == "F") ? ((temp * 1.8) + 32) + offset : temp + offset
        temp = temp.round(1)
    
    	sendEvent("name": "temperature", "value": temp, "unit": "\u00B0" + location.temperatureScale)
        log.debug "${device.displayName} temperature changed to ${temp}\u00B0 ${location.temperatureScale}"
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
        Float humidity = rawValue / 100
	    sendEvent("name": "humidity", "value": humidity, "unit": "%")
        log.debug "${device.displayName} humidity changed to ${humidity}%"
    } else {
        log.error "${device.displayName} humidity read failed"
    }
}

def batteryVoltageEvent(rawValue) {
    // The BatteryVoltage attribute is 8 bits in length and specifies the current actual (measured) battery voltage, in units of 100mV
	def batteryVolts = (rawValue / 10).setScale(2, BigDecimal.ROUND_HALF_UP)

    if (batteryValue > 0){
		sendEvent("name": "voltage", "value": batteryVolts, "unit": "volts")
        log.debug "${device.displayName} voltage changed to ${batteryVolts}V"
	}
    
    if (device.currentValue("battery") == null) {
        // Guess at percentage remaining
    	def minVolts = 20
	    def maxVolts = 30
	    def pct = (((rawValue - minVolts) / (maxVolts - minVolts)) * 100).toInteger()
	    def batteryValue = Math.min(100, pct)
    	sendEvent("name": "battery", "value": batteryValue, "unit": "%")
        log.debug "${device.displayName} battery % remaining changed to ${batteryValue}%"
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
        log.debug "${device.displayName} battery % remaining changed to ${batteryValue}%"
    } else {
        log.error "${device.displayName} battery % remaining read failed"
    }
}

def present() {
	sendEvent("name": "presence", "value":  "present", isStateChange: true)
    log.debug "${device.displayName} contact changed to present"
}

def notPresent() {
	sendEvent("name": "presence", "value":  "not present", isStateChange: true)
    log.debug "${device.displayName} contact changed to not present"
}

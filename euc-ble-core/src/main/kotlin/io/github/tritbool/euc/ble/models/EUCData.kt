package io.github.tritbool.euc.ble.models

/**
 * Represents real-time telemetry data from an Electric Unicycle.
 *
 * Contains all decoded data fields from BLE characteristic notifications,
 * including speed, voltage, current, temperature, battery information, and
 * various device-specific settings and status flags.
 *
 * @param frameType Internal identifier for the frame format
 * @param speed Current speed in km/h
 * @param voltage Battery voltage in volts
 * @param current Current draw in amps (positive = discharge, negative = charge)
 * @param temperature Temperature in degrees Celsius
 * @param batteryLevel Battery level percentage (0-100)
 * @param distance Current trip distance in kilometers
 * @param power Current power consumption in watts
 * @param pwm PWM duty cycle percentage (0-100), null if unavailable
 * @param timestamp Timestamp in milliseconds
 * @param rawData Raw byte data from the BLE characteristic
 * @param manufacturer Manufacturer name
 * @param model Device model name
 * @param serialNumber Device serial number, null if unavailable
 * @param firmwareVersion Firmware version, null if unavailable
 * @param isCharging Current charging status
 * @param rideTime Total ride time in seconds
 * @param cellVoltages Individual cell voltages, null if unavailable
 * @param motorTemperature Motor temperature in degrees Celsius, null if unavailable
 * @param totalDistance Total distance traveled in kilometers, null if unavailable
 * @param pedalsMode Pedals mode value (legacy-mapped, typically 0..2)
 * @param alarmMode Encoded alarm mode value (firmware-dependent, usually 0..3)
 * @param rollAngleMode Encoded roll-angle mode value (firmware-dependent, usually 0..3)
 * @param usesMiles Whether the device uses miles instead of km/h
 * @param autoPowerOffMinutes Auto power-off time in minutes, null if unavailable
 * @param tiltBackSpeed Tilt-back speed threshold, null if unavailable
 * @param ledMode LED mode setting, null if unavailable
 * @param lightMode Light mode setting, null if unavailable
 * @param alertFlags Alert flags bitmap, null if unavailable
 * @param wheelAlarm Wheel alarm status, null if unavailable
 * @param topSpeed Session top speed in km/h, null if unavailable
 * @param fanStatus Fan on/off status, null if unavailable
 * @param chargingStatus Charging status byte, null if unavailable
 * @param temperature2 Secondary temperature (e.g., motor/board), null if unavailable
 * @param cpuLoad CPU load percentage, null if unavailable
 * @param speedLimit Configured speed limit in km/h, null if unavailable
 * @param alarm1Speed Alarm 1 speed threshold, null if unavailable
 * @param alarm2Speed Alarm 2 speed threshold, null if unavailable
 * @param alarm3Speed Alarm 3 speed threshold, null if unavailable
 * @param wheelMaxSpeed Maximum speed setting, null if unavailable
 * @param wheelDistance Trip distance reported by wheel, null if unavailable
 * @param angle Pitch/tilt angle in degrees, null if unavailable
 */
data class EUCData(
    internal val frameType: String = "BASE",
    val speed: Double,              // km/h
    val voltage: Double,            // volts
    val current: Double,            // amps
    val temperature: Double,        // degrees Celsius
    val batteryLevel: Int,         // percentage 0-100
    val distance: Double,           // kilometers
    val power: Double,              // watts
    val pwm: Double? = null,          // PWM duty cycle percentage (0-100), null if unavailable
    val timestamp: Long,            // milliseconds
    val rawData: ByteArray,         // raw byte data
    val manufacturer: String,       // manufacturer name
    val model: String,              // device model
    val serialNumber: String? = null,      // device serial
    val firmwareVersion: String? = null,   // firmware version
    val isCharging: Boolean,        // charging status
    val rideTime: Long,             // ride time in seconds
    val cellVoltages: List<Double>? = null, // individual cell voltages
    val motorTemperature: Double? = null,   // motor temperature in degrees Celsius (null if not available)
    val totalDistance: Double? = null,
    val pedalsMode: Int? = null,       // mode value exposed by protocol (legacy-mapped, typically 0..2)
    val alarmMode: Int? = null,        // encoded alarm mode value (firmware-dependent, usually 0..3)
    val rollAngleMode: Int? = null,    // encoded roll-angle mode value (firmware-dependent, usually 0..3)
    val usesMiles: Boolean? = null,
    val autoPowerOffMinutes: Int? = null,
    val tiltBackSpeed: Int? = null,
    val ledMode: Int? = null,
    val lightMode: Int? = null,
    val alertFlags: Int? = null,
    val wheelAlarm: Boolean? = null,
    val topSpeed: Double? = null,           // session top speed in km/h
    val fanStatus: Int? = null,             // fan on/off status
    val chargingStatus: Int? = null,        // charging status byte
    val temperature2: Double? = null,       // secondary temperature (e.g. motor/board)
    val cpuLoad: Int? = null,               // CPU load percentage
    val speedLimit: Double? = null,         // configured speed limit in km/h
    val alarm1Speed: Int? = null,           // alarm 1 speed threshold
    val alarm2Speed: Int? = null,           // alarm 2 speed threshold
    val alarm3Speed: Int? = null,           // alarm 3 speed threshold
    val wheelMaxSpeed: Int? = null,         // max speed setting
    val wheelDistance: Double? = null,       // trip distance (wheel-reported)
    val angle: Double? = null                // pitch/tilt angle in degrees (null if unavailable)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EUCData

        if (timestamp != other.timestamp) return false
        if (manufacturer != other.manufacturer) return false
        if (model != other.model) return false

        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + manufacturer.hashCode()
        result = 31 * result + model.hashCode()
        return result
    }
}

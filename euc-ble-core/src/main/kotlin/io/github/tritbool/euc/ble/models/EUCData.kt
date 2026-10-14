package io.github.tritbool.euc.ble.models

/**
 * Represents real-time telemetry data from an Electric Unicycle.
 *
 * Physical measurements use metric units regardless of the wheel's display preference:
 * speed in km/h, distance in km, voltage in V, current in A, temperature in °C, power in W,
 * time durations in seconds, angles in degrees, torque in N·m, and tire pressure in kPa.
 * [usesMiles] reports a wheel setting only; it does not change the units of these values.
 *
 * Some protocol settings are encoded integers whose meaning or mode mapping varies by
 * manufacturer/firmware. Those fields are identified as protocol-specific below rather than
 * assigning a possibly misleading shared interpretation. Unsupported or unavailable optional
 * measurements are `null`.
 *
 * @param frameType Internal protocol frame identifier.
 * @param speed Current signed speed in km/h.
 * @param voltage Battery-pack voltage in V.
 * @param current Battery current in A; sign convention can depend on the protocol.
 * @param temperature Primary wheel temperature in °C; the physical sensor represented varies by protocol.
 * @param batteryLevel Estimated or reported state of charge, in percent (0..100).
 * @param distance Current trip distance in km where reported. Legacy Ninebot telemetry only supplies
 * an odometer, which is exposed in both [distance] and [totalDistance].
 * @param power Battery power in W, reported or derived from voltage and current.
 * @param pwm PWM duty cycle in percent where available.
 * @param timestamp Unix epoch timestamp in milliseconds.
 * @param rawData Raw BLE frame bytes used to produce this value.
 * @param manufacturer Manufacturer name.
 * @param model Device model name.
 * @param serialNumber Device serial number, if available.
 * @param firmwareVersion Device firmware version, if available.
 * @param isCharging Normalized charging state.
 * @param rideTime Elapsed ride/session time in seconds; may be estimated when the wheel does not report it.
 * @param cellVoltages Individual battery-cell voltages in V.
 * @param motorTemperature Motor temperature in °C.
 * @param totalDistance Lifetime/odometer distance in km.
 * @param pedalsMode Protocol-specific pedals-mode code; mapping is not uniform across manufacturers.
 * @param alarmMode Protocol-specific alarm-mode code.
 * @param rollAngleMode Protocol-specific roll-angle-mode code.
 * @param usesMiles True when the wheel reports imperial display mode, false for metric, or null when unknown.
 * Metric units are still used for decoded physical measurements.
 * @param autoPowerOffMinutes Configured auto-power-off delay in minutes.
 * @param tiltBackSpeed Configured tilt-back speed threshold in km/h where its unit is defined by the protocol;
 * otherwise the protocol's encoded integer is preserved.
 * @param ledMode Protocol-specific LED mode code.
 * @param lightMode Protocol-specific front-light mode code (for example, Begode 0=off, 1=on, 2=strobe).
 * @param alertFlags Protocol-specific alert bit field.
 * @param wheelAlarm Normalized wheel alarm state, if available.
 * @param topSpeed Session top speed in km/h.
 * @param fanStatus Protocol-specific fan status code; interpretation can vary.
 * @param chargingStatus Protocol-specific charging status code; use [isCharging] for the normalized state.
 * @param mosfetTemperature MOSFET temperature in °C.
 * @param boardTemperature Controller/board temperature in °C.
 * @param imuTemperature IMU temperature in °C.
 * @param tirePressureKpa Tire pressure in kPa.
 * @param cpuLoad CPU load in percent where available.
 * @param speedLimit Speed limit in km/h. KingSong reports the configured max-speed/tilt-back
 * setting here (also in [wheelMaxSpeed]); InMotion and Ninebot may report a runtime limit.
 * @param alarm1Speed Alarm 1 speed threshold in km/h.
 * @param alarm2Speed Alarm 2 speed threshold in km/h.
 * @param alarm3Speed Alarm 3 speed threshold in km/h.
 * @param wheelMaxSpeed Configured maximum speed in km/h.
 * @param wheelDistance KingSong-reported trip distance in km; null until that value is received.
 * @param angle Pitch angle in degrees.
 * @param phaseCurrent Motor phase current in A.
 * @param roll Roll angle in degrees.
 * @param torque Estimated torque in N·m.
 * @param mode Protocol-reported ride state/name; values can vary by manufacturer.
 * @param totalRideTimeSeconds Cumulative lifetime ride time in seconds.
 * @param totalPowerOnTimeSeconds Cumulative lifetime powered-on time in seconds.
 * @param dynamicSpeedLimit KingSong F6 runtime speed ceiling in km/h, independent of the
 * configured [speedLimit]. Null until received; the reason for a reduction is not reported here.
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
    val mosfetTemperature: Double? = null,  // MOSFET temperature in degrees Celsius
    val boardTemperature: Double? = null,   // controller/board temperature in degrees Celsius
    val imuTemperature: Double? = null,     // IMU temperature in degrees Celsius
    val tirePressureKpa: Double? = null,
    val cpuLoad: Int? = null,               // CPU load percentage
    val speedLimit: Double? = null,         // speed limit; see protocol-specific semantics above
    val alarm1Speed: Int? = null,           // alarm 1 speed threshold
    val alarm2Speed: Int? = null,           // alarm 2 speed threshold
    val alarm3Speed: Int? = null,           // alarm 3 speed threshold
    val wheelMaxSpeed: Int? = null,         // max speed setting
    val wheelDistance: Double? = null,       // trip distance (wheel-reported)
    val angle: Double? = null,                // pitch/tilt angle in degrees (null if unavailable)
    val phaseCurrent: Double? = null,   // Phase current in amps (motor-side), null if unavailable
    val roll: Double? = null,            // Roll angle in degrees, null if unavailable
    val torque: Double? = null,           // Torque estimate (Nm), null if unavailable
    val mode: String? = null,            // Ride mode/state string (e.g. "active", "idle"), null if unavailable
    val totalRideTimeSeconds: Long? = null,    // Cumulative ride time in seconds (from TotalStats), null if unavailable
    val totalPowerOnTimeSeconds: Long? = null, // Cumulative power-on time in seconds (from TotalStats), null if unavailable
    val dynamicSpeedLimit: Double? = null     // KingSong runtime speed ceiling in km/h
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

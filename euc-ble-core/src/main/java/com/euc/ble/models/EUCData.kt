package com.euc.ble.models

/**
 * Represents real-time data from an Electric Unicycle
 */
data class EUCData(
    val speed: Double,              // km/h
    val voltage: Double,            // volts
    val current: Double,            // amps
    val temperature: Double,        // degrees Celsius
    val batteryLevel: Int,         // percentage 0-100
    val distance: Double,           // kilometers
    val power: Double,              // watts
    val timestamp: Long,            // milliseconds
    val rawData: ByteArray,         // raw byte data
    val manufacturer: String,       // manufacturer name
    val model: String,              // device model
    val serialNumber: String?=null,      // device serial
    val firmwareVersion: String?=null,   // firmware version
    val isCharging: Boolean,        // charging status
    val rideTime: Long,             // ride time in seconds
    val cellVoltages: List<Double>?=null, // individual cell voltages
    val motorTemperature: Double?=null,   // motor temperature in degrees Celsius (null if not available)
    val totalDistance: Double?=null,
    val pedalsMode: Int?=null,       // mode value exposed by protocol (legacy-mapped, typically 0..2)
    val alarmMode: Int?=null,        // encoded alarm mode value (firmware-dependent, usually 0..3)
    val rollAngleMode: Int?=null,    // encoded roll-angle mode value (firmware-dependent, usually 0..3)
    val usesMiles: Boolean?=null,
    val autoPowerOffMinutes: Int?=null,
    val tiltBackSpeed: Int?=null,
    val ledMode: Int?=null,
    val lightMode: Int?=null,
    val alertFlags: Int?=null,
    val wheelAlarm: Boolean?=null
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

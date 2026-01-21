package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import java.util.UUID
import kotlin.math.absoluteValue

/**
 * Veteran EUC Protocol Implementation
 * Supports Veteran series high-power electric unicycles (Sherman, Lynx, etc.)
 * Features advanced BMS data and comprehensive telemetry
 */
class VeteranProtocol : EUCProtocol {

    override val manufacturer: String = "Veteran"
    override val supportedModels: List<String> = listOf(
        "Sherman", "Sherman S", "Sherman Max", "Lynx", "Titan", "Veteran V1", "Veteran V2"
    )

    // Veteran uses standard BLE UUIDs
    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.VETERAN_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.VETERAN_READ_CHARACTERISTIC)
    override fun getWriteCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.VETERAN_WRITE_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        // Veteran devices typically have manufacturer ID 0x0056
        // and specific naming patterns
        return device.manufacturerId == BLEConstants.MANUFACTURER_VETERAN ||
               device.name.contains("Veteran", ignoreCase = true) ||
               device.name.contains("Sherman", ignoreCase = true) ||
               device.name.contains("Lynx", ignoreCase = true) ||
               device.name.contains("Titan", ignoreCase = true)
    }

    override fun decode(data: ByteArray): EUCData? {
        if (data.size < 50) {
            return null // Minimum packet size for Veteran (larger due to BMS data)
        }

        try {
            // Veteran protocol has comprehensive data structure
            // Byte 0-1: 0x55 0xAA (header)
            // Byte 2: Frame type
            // Byte 3: Data length
            // Byte 4-5: Voltage (little-endian, 0.1V units)
            // Byte 6-7: Speed (little-endian, 0.1 km/h units)
            // Byte 8-11: Distance (little-endian, meters)
            // Byte 12-15: Total distance (little-endian, meters)
            // Byte 16-17: Phase current (little-endian, 0.1A units)
            // Byte 18-19: Temperature (little-endian, 0.1°C units)
            // Byte 20-21: Auto-off time (seconds)
            // Byte 22-23: Charge mode
            // Byte 24-25: Speed alert (0.1 km/h units)
            // Byte 26-27: Speed tiltback (0.1 km/h units)
            // Byte 28-29: Version info
            // Byte 30-31: Pedals mode
            // Byte 32-33: Pitch angle (0.1 degrees)
            // Byte 34-35: PWM value
            // Byte 36: Battery percentage
            // Byte 37: Status flags
            // Byte 38-49: BMS data (cell voltages, temperatures)
            // Byte 50: Checksum
            
            val header1 = ByteUtils.getUnsignedByte(data, 0)
            val header2 = ByteUtils.getUnsignedByte(data, 1)
            
            if (header1 != 0x55 || header2 != 0xAA) {
                return null // Invalid header
            }

            val frameType = ByteUtils.getUnsignedByte(data, 2)
            
            // Only process data frames (type 0x01 for real-time data)
            if (frameType != 0x01) {
                return null
            }

            val voltage = ByteUtils.getUnsignedShortLE(data, 4) / 10.0
            val speed = ByteUtils.getUnsignedShortLE(data, 6) / 10.0
            val distance = ByteUtils.getUnsignedIntLE(data, 8).toDouble() / 1000.0 // Convert to km
            val totalDistance = ByteUtils.getUnsignedIntLE(data, 12).toDouble() / 1000.0 // Convert to km
            val current = ByteUtils.getUnsignedShortLE(data, 16) / 10.0
            val temperature = ByteUtils.getUnsignedShortLE(data, 18) / 10.0
            val batteryLevel = ByteUtils.getUnsignedByte(data, 36).toInt()
            val power = voltage * current

            // Parse status flags
            val statusByte = ByteUtils.getUnsignedByte(data, 37)
            val isCharging = (statusByte and 0x01) != 0
            val hasAlarm = (statusByte and 0x02) != 0
            val isLocked = (statusByte and 0x04) != 0

            // Parse BMS data (cell voltages and temperatures)
            val cellVoltages = mutableListOf<Double>()
            val bmsDataAvailable = data.size > 49
            
            if (bmsDataAvailable) {
                // Parse cell voltages (bytes 38-49 contain BMS data)
                // This is a simplified parsing - actual Veteran protocol has more complex BMS structure
                val bmsPacketType = ByteUtils.getUnsignedByte(data, 38)
                
                // For packet type 1 (cell voltages 1-15)
                if (bmsPacketType == 1 && data.size > 50) {
                    for (i in 0 until 15) {
                        val cellIndex = 39 + i * 2
                        if (cellIndex + 1 < data.size) {
                            val cellVoltage = ByteUtils.getUnsignedShortLE(data, cellIndex) / 1000.0
                            cellVoltages.add(cellVoltage)
                        }
                    }
                }
                // For packet type 2 (cell voltages 16-30)
                else if (bmsPacketType == 2 && data.size > 50) {
                    for (i in 0 until 15) {
                        val cellIndex = 39 + i * 2
                        if (cellIndex + 1 < data.size) {
                            val cellVoltage = ByteUtils.getUnsignedShortLE(data, cellIndex) / 1000.0
                            cellVoltages.add(cellVoltage)
                        }
                    }
                }
                // For packet type 3 (cell voltages 31-45 and temperatures)
                else if (bmsPacketType == 3 && data.size > 50) {
                    for (i in 0 until 12) {
                        val cellIndex = 39 + i * 2
                        if (cellIndex + 1 < data.size) {
                            val cellVoltage = ByteUtils.getUnsignedShortLE(data, cellIndex) / 1000.0
                            cellVoltages.add(cellVoltage)
                        }
                    }
                    // Parse temperatures
                    val temp1 = ByteUtils.getUnsignedShortLE(data, 47) / 100.0
                    val temp2 = ByteUtils.getUnsignedShortLE(data, 49) / 100.0
                    // Note: Veteran has multiple temperature sensors
                }
            }

            // Parse motor temperature (if available in extended data)
            val motorTemperature = if (data.size > 52) {
                ByteUtils.getUnsignedShortLE(data, 52) / 10.0
            } else {
                null
            }

            // Verify checksum
            val calculatedChecksum = ByteUtils.calculateChecksum(data, 0, data.size - 1)
            val receivedChecksum = data[data.size - 1]
            if (calculatedChecksum != receivedChecksum) {
                return null // Checksum mismatch
            }

            return EUCData(
                speed = speed,
                voltage = voltage,
                current = current,
                temperature = temperature,
                batteryLevel = batteryLevel,
                distance = distance,
                power = power,
                timestamp = System.currentTimeMillis(),
                rawData = data,
                manufacturer = manufacturer,
                model = "Unknown Veteran", // Would be detected during connection
                serialNumber = null,
                firmwareVersion = null,
                isCharging = isCharging,
                rideTime = 0, // Would be calculated over time
                cellVoltages = if (cellVoltages.isNotEmpty()) cellVoltages else null,
                motorTemperature = motorTemperature
            )

        } catch (e: Exception) {
            // Log decoding error
            return null
        }
    }

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        // Veteran command format
        return when (commandType) {
            CommandType.LIGHT_ON -> {
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x01, 0x01, 0x01, 0x00)
            }
            CommandType.LIGHT_OFF -> {
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x01, 0x01, 0x00, 0x00)
            }
            CommandType.BEEP -> {
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x02, 0x01, 0x00)
            }
            CommandType.POWER_OFF -> {
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x03, 0x01, 0x00)
            }
            CommandType.LIGHT_BRIGHTNESS -> {
                if (value is Int && value in 0..100) {
                    val brightness = (value * 255 / 100).toByte()
                    byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x04, brightness, 0x00)
                } else {
                    byteArrayOf() // Invalid value
                }
            }
            CommandType.REQUEST_SERIAL -> {
                // Request serial number
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x10, 0x01, 0x00)
            }
            CommandType.REQUEST_FIRMWARE -> {
                // Request firmware version
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x11, 0x01, 0x00)
            }
            CommandType.SET_SPEED_LIMIT -> {
                if (value is Double && value in 10.0..50.0) {
                    val speedLimit = (value * 10).toInt()
                    byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x20, (speedLimit and 0xFF).toByte(), ((speedLimit shr 8) and 0xFF).toByte())
                } else {
                    byteArrayOf() // Invalid value
                }
            }
            CommandType.SET_ALARM_SPEED -> {
                if (value is Double && value in 10.0..50.0) {
                    val alarmSpeed = (value * 10).toInt()
                    byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x21, (alarmSpeed and 0xFF).toByte(), ((alarmSpeed shr 8) and 0xFF).toByte())
                } else {
                    byteArrayOf() // Invalid value
                }
            }
            else -> {
                byteArrayOf() // Unsupported command
            }
        }
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        // Device is ready if we have valid data and it's not in an error state
        return data.speed >= 0 && data.voltage > 60.0 && data.temperature < 80.0
    }

    // Helper function to parse Veteran version info
    private fun parseVersionInfo(versionData: Int): String {
        val major = versionData / 1000
        val minor = (versionData % 1000) / 100
        val patch = versionData % 100
        return "$major.$minor.$patch"
    }
}
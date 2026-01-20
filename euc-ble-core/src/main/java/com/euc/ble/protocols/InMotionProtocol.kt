package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import java.util.UUID

/**
 * InMotion EUC Protocol Implementation
 * Supports InMotion series electric unicycles (V8, V10, V11, etc.)
 * Uses CAN bus protocol with complex message structure
 */
class InMotionProtocol : EUCProtocol {

    override val manufacturer: String = "InMotion"
    override val supportedModels: List<String> = listOf(
        "V8", "V8F", "V10", "V10F", "V10F+", "V11", "V12", "V12 Pro", "V12 HT"
    )

    // InMotion uses standard BLE UUIDs
    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.INMOTION_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.INMOTION_READ_CHARACTERISTIC)
    override fun getWriteCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.INMOTION_WRITE_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        // InMotion devices typically have manufacturer ID 0x0049
        // and specific naming patterns
        return device.manufacturerId == BLEConstants.MANUFACTURER_INMOTION ||
               device.name.contains("InMotion", ignoreCase = true) ||
               device.name.contains("V8", ignoreCase = true) ||
               device.name.contains("V10", ignoreCase = true) ||
               device.name.contains("V11", ignoreCase = true) ||
               device.name.contains("V12", ignoreCase = true)
    }

    override fun decode(data: ByteArray): EUCData? {
        if (data.size < 20) {
            return null // Minimum packet size for InMotion
        }

        try {
            // InMotion uses CAN bus protocol with complex message structure
            // The protocol involves parsing different CAN message types
            
            // For now, implement basic parsing - this would be enhanced with full CAN message parsing
            // Byte 0-1: Header
            // Byte 2: Message type
            // Byte 3: Data length
            // Byte 4-5: Voltage (little-endian, 0.1V units)
            // Byte 6-7: Speed (little-endian, 0.1 km/h units)
            // Byte 8-11: Distance (little-endian, meters)
            // Byte 12-13: Current (little-endian, 0.1A units)
            // Byte 14-15: Temperature (little-endian, 0.1°C units)
            // Byte 16: Battery percentage
            // Byte 17: Status flags
            
            val messageType = ByteUtils.getUnsignedByte(data, 2)
            
            // Only process data messages (type 0x01 for fast data)
            if (messageType != 0x01) {
                return null
            }

            val voltage = ByteUtils.getUnsignedShortLE(data, 4) / 10.0
            val speed = ByteUtils.getUnsignedShortLE(data, 6) / 10.0
            val distance = ByteUtils.getUnsignedIntLE(data, 8).toDouble() / 1000.0 // Convert to km
            val current = ByteUtils.getUnsignedShortLE(data, 12) / 10.0
            val temperature = ByteUtils.getUnsignedShortLE(data, 14) / 10.0
            val batteryLevel = ByteUtils.getUnsignedByte(data, 16).toInt()
            val power = voltage * current

            // Parse status flags
            val statusByte = ByteUtils.getUnsignedByte(data, 17)
            val isCharging = (statusByte and 0x01) != 0
            val hasAlarm = (statusByte and 0x02) != 0

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
                model = "Unknown InMotion", // Would be detected during connection
                serialNumber = null,
                firmwareVersion = null,
                isCharging = isCharging,
                rideTime = 0, // Would be calculated over time
                cellVoltages = null, // InMotion doesn't typically send cell voltages in basic data
                motorTemperature = null
            )

        } catch (e: Exception) {
            // Log decoding error
            return null
        }
    }

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        // InMotion command format uses CAN messages
        // This is a simplified version - full implementation would use proper CAN message structure
        return when (commandType) {
            CommandType.LIGHT_ON -> {
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x01, 0x01, 0x01)
            }
            CommandType.LIGHT_OFF -> {
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x01, 0x01, 0x00)
            }
            CommandType.BEEP -> {
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x02, 0x01)
            }
            CommandType.POWER_OFF -> {
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x03, 0x01)
            }
            CommandType.LIGHT_BRIGHTNESS -> {
                if (value is Int && value in 0..100) {
                    val brightness = (value * 255 / 100).toByte()
                    byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x04, brightness)
                } else {
                    byteArrayOf() // Invalid value
                }
            }
            CommandType.REQUEST_SERIAL -> {
                // Request serial number
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x10, 0x01)
            }
            CommandType.REQUEST_FIRMWARE -> {
                // Request firmware version
                byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x11, 0x01)
            }
            else -> {
                byteArrayOf() // Unsupported command
            }
        }
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        // Device is ready if we have valid data and it's not in an error state
        return data.speed >= 0 && data.voltage > 50.0 && data.temperature < 70.0
    }

    // Helper class for CAN message parsing (simplified)
    private class CANMessage {
        companion object {
            fun verify(buffer: ByteArray): CANMessage? {
                // Simplified CAN message verification
                if (buffer.size < 8) return null
                
                // Check for valid CAN message structure
                // This would be enhanced with proper CAN ID and data validation
                return CANMessage()
            }
        }
    }
}
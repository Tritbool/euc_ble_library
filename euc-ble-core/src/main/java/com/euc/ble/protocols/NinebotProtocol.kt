package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import java.util.UUID

/**
 * Ninebot EUC Protocol Implementation
 * Supports Ninebot series electric unicycles (S2, Mini, E+, C+, etc.)
 * Uses standard BLE protocol with keep-alive mechanism
 */
class NinebotProtocol : EUCProtocol {

    override val manufacturer: String = "Ninebot"
    override val supportedModels: List<String> = listOf(
        "Ninebot One S2", "Ninebot Mini", "Ninebot E+", "Ninebot C+", "Ninebot P", "Ninebot A"
    )

    // Ninebot uses standard BLE UUIDs
    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.NINEBOT_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.NINEBOT_READ_CHARACTERISTIC)
    override fun getWriteCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.NINEBOT_WRITE_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        // Ninebot devices typically have manufacturer ID 0x004E (same as Kingsong)
        // and specific naming patterns
        return device.name.contains("Ninebot", ignoreCase = true) ||
               device.name.contains("S2", ignoreCase = true) ||
               device.name.contains("Mini", ignoreCase = true) ||
               device.name.contains("E+", ignoreCase = true) ||
               device.name.contains("C+", ignoreCase = true)
    }

    override fun decode(data: ByteArray): EUCData? {
        if (data.size < 20) {
            return null // Minimum packet size for Ninebot
        }

        try {
            // Ninebot protocol structure
            // Byte 0: Start marker (0x55)
            // Byte 1: Frame length
            // Byte 2: Command/Response type
            // Byte 3: Sequence number
            // Byte 4-5: Voltage (little-endian, 0.1V units)
            // Byte 6-7: Speed (little-endian, 0.1 km/h units)
            // Byte 8-11: Distance (little-endian, meters)
            // Byte 12-13: Current (little-endian, 0.1A units)
            // Byte 14-15: Temperature (little-endian, 0.1°C units)
            // Byte 16: Battery percentage
            // Byte 17: Status flags
            // Byte 18: Checksum
            
            val startMarker = ByteUtils.getUnsignedByte(data, 0)
            if (startMarker != 0x55) {
                return null // Invalid start marker
            }

            val frameLength = ByteUtils.getUnsignedByte(data, 1)
            if (frameLength != data.size) {
                return null // Length mismatch
            }

            val messageType = ByteUtils.getUnsignedByte(data, 2)
            
            // Only process data messages (type 0x01 for real-time data)
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
            val isLocked = (statusByte and 0x04) != 0

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
                model = "Unknown Ninebot", // Would be detected during connection
                serialNumber = null,
                firmwareVersion = null,
                isCharging = isCharging,
                rideTime = 0, // Would be calculated over time
                cellVoltages = null, // Ninebot doesn't typically send cell voltages
                motorTemperature = null
            )

        } catch (e: Exception) {
            // Log decoding error
            return null
        }
    }

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        // Ninebot command format
        return when (commandType) {
            CommandType.LIGHT_ON -> {
                byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x01, 0x01, 0x01, 0x00, 0x00)
            }
            CommandType.LIGHT_OFF -> {
                byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x01, 0x01, 0x00, 0x00, 0x00)
            }
            CommandType.BEEP -> {
                byteArrayOf(0x55.toByte(), 0x04.toByte(), 0x02, 0x01, 0x00, 0x00)
            }
            CommandType.POWER_OFF -> {
                byteArrayOf(0x55.toByte(), 0x04.toByte(), 0x03, 0x01, 0x00, 0x00)
            }
            CommandType.LIGHT_BRIGHTNESS -> {
                if (value is Int && value in 0..100) {
                    val brightness = (value * 255 / 100).toByte()
                    byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x04, brightness, 0x00, 0x00, 0x00)
                } else {
                    byteArrayOf() // Invalid value
                }
            }
            CommandType.REQUEST_SERIAL -> {
                // Request serial number
                byteArrayOf(0x55.toByte(), 0x04.toByte(), 0x10, 0x01, 0x00, 0x00)
            }
            CommandType.REQUEST_FIRMWARE -> {
                // Request firmware version
                byteArrayOf(0x55.toByte(), 0x04.toByte(), 0x11, 0x01, 0x00, 0x00)
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
}
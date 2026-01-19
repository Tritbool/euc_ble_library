package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import java.util.UUID

/**
 * Gotway EUC Protocol Implementation
 * Supports Gotway series electric unicycles
 */
class GotwayProtocol : EUCProtocol {
    
    override val manufacturer: String = "Gotway"
    override val supportedModels: List<String> = listOf(
        "MSuper", "MSX", "MSX Pro", "Mten3", "Mten4", "MTen5",
        "Nikola", "Nikola Plus", "Tesla", "Monster", "Monster Pro",
        "Begode", "Begode RS", "Begode Master", "Begode Hero"
    )
    
    // Gotway uses standard BLE UUIDs
    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.GOTWAY_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.GOTWAY_READ_CHARACTERISTIC)
    
    override fun canHandle(device: EUCDevice): Boolean {
        // Gotway devices typically have manufacturer ID 0x0047
        // and specific naming patterns
        return device.manufacturerId == BLEConstants.MANUFACTURER_GOTWAY ||
               device.name.contains("Gotway", ignoreCase = true) ||
               device.name.contains("Begode", ignoreCase = true) ||
               device.name.contains("Mten", ignoreCase = true) ||
               device.name.contains("MSX", ignoreCase = true) ||
               device.name.contains("Nikola", ignoreCase = true)
    }
    
    override fun decode(data: ByteArray): EUCData? {
        if (data.size < 18) {
            return null // Minimum packet size for Gotway
        }
        
        try {
            // Gotway data format varies by model, but common structure:
            // Byte 0: Packet type
            // Byte 1-2: Voltage (little-endian, 0.1V units)
            // Byte 3-4: Speed (little-endian, 0.1 km/h units)
            // Byte 5-8: Distance (little-endian, meters)
            // Byte 9-10: Current (little-endian, 0.1A units)
            // Byte 11-12: Temperature (little-endian, 0.1°C units)
            // Byte 13: Battery percentage
            // Byte 14: Status flags
            // Byte 15-17: Additional data
            
            val packetType = ByteUtils.getUnsignedByte(data, 0)
            
            // Only process data packets (type 0x01 or 0x02)
            if (packetType != 0x01 && packetType != 0x02) {
                return null
            }
            
            val voltage = ByteUtils.getUnsignedShortLE(data, 1) / 10.0
            val speed = ByteUtils.getUnsignedShortLE(data, 3) / 10.0
            val distance = ByteUtils.getUnsignedIntLE(data, 5).toDouble() / 1000.0 // Convert to km
            val current = ByteUtils.getUnsignedShortLE(data, 9) / 10.0
            val temperature = ByteUtils.getUnsignedShortLE(data, 11) / 10.0
            val batteryLevel = ByteUtils.getUnsignedByte(data, 13).toInt()
            val power = voltage * current
            
            // Parse status flags
            val statusByte = ByteUtils.getUnsignedByte(data, 14)
            val isCharging = (statusByte and 0x01) != 0
            val hasAlarm = (statusByte and 0x02) != 0
            
            // Check for motor temperature in extended data (bytes 15-17)
            // Some Gotway/Begode models send motor temperature in byte 15
            val motorTemperature = if (data.size > 15) {
                // Byte 15 contains motor temperature (0.1°C units)
                ByteUtils.getUnsignedByte(data, 15) / 10.0
            } else {
                null
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
                model = "Unknown Gotway", // Would be detected during connection
                serialNumber = null,
                firmwareVersion = null,
                isCharging = isCharging,
                rideTime = 0, // Would be calculated over time
                cellVoltages = null, // Gotway doesn't typically send cell voltages
                motorTemperature = motorTemperature // Add motor temperature
            )
            
        } catch (e: Exception) {
            // Log decoding error
            return null
        }
    }
    
    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        // Gotway command format varies by model
        return when (commandType) {
            CommandType.LIGHT_ON -> {
                byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x01)
            }
            CommandType.LIGHT_OFF -> {
                byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x00)
            }
            CommandType.BEEP -> {
                byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x02, 0x01)
            }
            CommandType.POWER_OFF -> {
                byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x03, 0x01)
            }
            CommandType.LIGHT_BRIGHTNESS -> {
                if (value is Int && value in 0..100) {
                    val brightness = (value * 255 / 100).toByte()
                    byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x04, brightness)
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
        return data.speed >= 0 && data.voltage > 45.0 && data.temperature < 75.0
    }
}
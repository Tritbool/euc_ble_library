package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import java.util.UUID

/**
 * Kingsong EUC Protocol Implementation
 * Supports Kingsong series electric unicycles
 */
class KingsongProtocol : EUCProtocol {
    
    override val manufacturer: String = "KingSong"
    override val supportedModels: List<String> = listOf(
        "KS-14D", "KS-16", "KS-16S", "KS-16X", "KS-18L", "KS-18XL",
        "KS-19", "KS-S18", "KS-S19", "KS-S20", "KS-S22", "KS-F22"
    )
    
    // Kingsong uses standard BLE UUIDs
    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.KINGSONG_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.KINGSONG_READ_CHARACTERISTIC)
    
    override fun canHandle(device: EUCDevice): Boolean {
        // Kingsong devices typically have manufacturer ID 0x004E
        // and specific service UUIDs
        return device.manufacturerId == BLEConstants.MANUFACTURER_KINGSONG ||
               device.name.startsWith("KS-", ignoreCase = true) ||
               device.name.contains("KingSong", ignoreCase = true)
    }
    
    override fun decode(data: ByteArray): EUCData? {
        if (data.size < 20) {
            return null // Minimum packet size for Kingsong
        }
        
        // Kingsong packets start with 0xAA 0x55
        if (!ByteUtils.startsWith(data, byteArrayOf(0xAA.toByte(), 0x55.toByte()))) {
            return null
        }
        
        try {
            // Parse Kingsong data format
            // Byte 0-1: Header (0xAA 0x55)
            // Byte 2-3: Voltage (little-endian, 0.1V units)
            // Byte 4-5: Speed (little-endian, 0.1 km/h units)
            // Byte 6-9: Distance (little-endian, meters)
            // Byte 10-11: Current (little-endian, 0.1A units)
            // Byte 12-13: Temperature (little-endian, 0.1°C units)
            // Byte 14: Status flags
            // Byte 15: Battery percentage
            // Byte 16-19: Various status data
            
            val voltage = ByteUtils.getUnsignedShortLE(data, 2) / 10.0
            val speed = ByteUtils.getUnsignedShortLE(data, 4) / 10.0
            val distance = ByteUtils.getUnsignedIntLE(data, 6).toDouble() / 1000.0 // Convert to km
            val current = ByteUtils.getUnsignedShortLE(data, 10) / 10.0
            val temperature = ByteUtils.getUnsignedShortLE(data, 12) / 10.0
            val batteryLevel = ByteUtils.getUnsignedByte(data, 15).toInt()
            val power = voltage * current
            
            // Parse status flags
            val statusByte = ByteUtils.getUnsignedByte(data, 14)
            val isCharging = (statusByte and 0x01) != 0
            
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
                model = "Unknown Kingsong", // Would be detected during connection
                serialNumber = null,
                firmwareVersion = null,
                isCharging = isCharging,
                rideTime = 0, // Would be calculated over time
                cellVoltages = null, // Kingsong doesn't typically send cell voltages
                motorTemperature = null

            )
            
        } catch (e: Exception) {
            // Log decoding error
            return null
        }
    }
    
    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        // Kingsong command format: [0xAA, 0x55, command, data...]
        return when (commandType) {
            CommandType.LIGHT_ON -> {
                byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01, 0x01)
            }
            CommandType.LIGHT_OFF -> {
                byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01, 0x00)
            }
            CommandType.BEEP -> {
                byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x02, 0x01)
            }
            CommandType.POWER_OFF -> {
                byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x03, 0x01)
            }
            CommandType.LIGHT_BRIGHTNESS -> {
                if (value is Int && value in 0..100) {
                    val brightness = (value * 255 / 100).toByte()
                    byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x04, brightness)
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
        return data.speed >= 0 && data.voltage > 40.0 && data.temperature < 80.0
    }
}
package com.euc.ble.protocols

import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Base interface for EUC manufacturer protocols
 */
interface EUCProtocol {
    
    /**
     * Manufacturer name
     */
    val manufacturer: String
    
    /**
     * List of supported models
     */
    val supportedModels: List<String>

    val dataFlow: Flow<EUCData>

    /**
     * Check if this protocol can handle the given device
     */
    fun canHandle(device: EUCDevice): Boolean
    
    /**
     * Decode raw BLE data into EUCData
     */
    fun decode(data: ByteArray): EUCData?
    
    /**
     * Get the UUID for the data characteristic
     */
    fun getDataCharacteristicUUID(): UUID
    
    /**
     * Get the UUID for the service
     */
    fun getServiceUUID(): UUID
    
    /**
     * Create a command for the EUC
     */
    fun createCommand(commandType: CommandType, value: Any): ByteArray
    
    /**
     * Get the UUID for the write characteristic (if different from data characteristic)
     */
    fun getWriteCharacteristicUUID(): UUID = getDataCharacteristicUUID()
    
    /**
     * Check if the device is ready for operation
     */
    fun isDeviceReady(data: EUCData): Boolean
}

/**
 * Standard command types for EUCs
 */
enum class CommandType {
    LIGHT_ON,
    LIGHT_OFF,
    LIGHT_BRIGHTNESS,
    SPEAKER_VOLUME,
    BEEP,
    POWER_OFF,
    LOCK,
    UNLOCK,
    SET_SPEED_LIMIT,
    SET_ALARM_SPEED,
    CALIBRATE,
    REQUEST_SERIAL,
    REQUEST_FIRMWARE,
    REQUEST_BATTERY_INFO,
    CUSTOM
}
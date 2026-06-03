package com.euc.ble.protocols

import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.Closeable
import java.util.UUID

/**
 * Base interface for EUC manufacturer protocols
 */
interface EUCProtocol : Closeable {

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
     * Flow that emits every raw BLE characteristic notification received by this protocol,
     * as a defensive copy of the original byte array. Collectors can use this to write raw
     * logs or perform any custom processing on the unmodified BLE data.
     *
     * The default implementation emits nothing; concrete protocols override this to provide
     * a live stream of incoming bytes.
     */
    val rawFrameFlow: Flow<ByteArray> get() = emptyFlow()

    override fun close() {
    }

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
     * Explicit command support matrix for this protocol.
     * Commands outside this set are considered unsupported by design.
     */
    val supportedCommandTypes: Set<CommandType>
        get() = emptySet()

    /**
     * API-level command support check (used by framework and clients).
     */
    fun getCommandSupport(commandType: CommandType): CommandSupport {
        return if (supportedCommandTypes.contains(commandType)) {
            CommandSupport.SUPPORTED
        } else {
            CommandSupport.UNSUPPORTED
        }
    }

    /**
     * Optional polling/query plan consumed by BLEManager orchestration.
     */
    fun getPollingPlan(): ProtocolPollingPlan = ProtocolPollingPlan.disabled()

    /**
     * Optional query/response matcher used by BLEManager observability and retry loop.
     */
    fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean = false

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
    SET_LIGHT_MODE,
    LIGHT_BRIGHTNESS,
    SPEAKER_VOLUME,
    BEEP,
    POWER_OFF,
    LOCK,
    UNLOCK,
    SET_PEDALS_MODE,
    SET_LED_MODE,
    SET_SPEED_LIMIT,
    SET_ALARM_SPEED,
    CALIBRATE,
    REQUEST_SERIAL,
    REQUEST_FIRMWARE,
    REQUEST_BATTERY_INFO,
    RESET_TRIP,
    CUSTOM
}

enum class CommandSupport {
    SUPPORTED,
    UNSUPPORTED
}

data class ProtocolQuerySpec(
    val id: String,
    val commandType: CommandType,
    val value: Any = Unit,
    val initialDelayMs: Long = 0L,
    val intervalMs: Long = 0L,
    val responseTimeoutMs: Long = 1500L,
    val maxRetries: Int = 2,
    val retryBackoffMs: Long = 500L
)

data class ProtocolPollingPlan(
    val enabled: Boolean,
    val startupQueries: List<ProtocolQuerySpec> = emptyList(),
    val periodicQueries: List<ProtocolQuerySpec> = emptyList()
) {
    companion object {
        fun disabled() = ProtocolPollingPlan(enabled = false)
    }
}

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.EUCDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.Closeable
import java.util.UUID

/**
 * Specifies a single GATT service requirement within a [GattSignature].
 *
 * A service spec matches when:
 * - The service UUID is present in the discovered GATT services.
 * - All [requiredCharacteristicUUIDs] are present as characteristics of that service.
 * - None of the [excludedCharacteristicUUIDs] are present as characteristics of that service.
 *
 * @param uuid The service UUID that must be present.
 * @param requiredCharacteristicUUIDs Characteristic UUIDs that must ALL be present in the service.
 * @param excludedCharacteristicUUIDs Characteristic UUIDs that must NOT be present in the service.
 */
data class GattServiceSpec(
    val uuid: UUID,
    val requiredCharacteristicUUIDs: Set<UUID> = emptySet(),
    val excludedCharacteristicUUIDs: Set<UUID> = emptySet()
)

/**
 * A GATT signature is a list of [GattServiceSpec] entries that all must match for a protocol to
 * be identified by GATT fingerprinting. All specs use AND semantics (every spec must hold).
 *
 * A protocol can declare multiple alternative signatures (OR semantics between signatures):
 * the protocol matches if at least one signature matches.
 */
typealias GattSignature = List<GattServiceSpec>

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

    /**
     * Flow of commands or raw bytes that the protocol needs to write to the device automatically.
     */
    val writeFlow: Flow<ByteArray> get() = emptyFlow()


    /**
     * Check if this protocol can handle the given device
     */
    fun canHandle(device: EUCDevice): Boolean

    /**
     * Decode raw BLE data into EUCData
     */
    fun decode(data: ByteArray): EUCData?

    /**
     * Returns GATT service signatures that uniquely identify this protocol.
     *
     * This is the highest-confidence identification method and takes priority over name-based
     * and frame-based detection. It is evaluated immediately after [onServicesDiscovered] using
     * the full GATT service+characteristic profile of the connected device, which is analogous
     * to what WheelLog.Android does in its `detectWheel` method.
     *
     * Each entry in the returned list is an alternative [GattSignature] (OR semantics). A
     * [GattSignature] is a list of [GattServiceSpec] that must ALL match (AND semantics).
     *
     * The default returns an empty list, meaning this protocol will not be identified by GATT
     * fingerprinting and will rely on name-based or frame-based detection instead. Protocols
     * that expose a distinctive GATT service profile should override this to improve reliability.
     */
    fun getGattSignatures(): List<GattSignature> = emptyList()

    /**
     * Optional fast header check: returns true if [chunk] appears to contain frames belonging
     * to this protocol, based on magic-byte patterns alone.  The default returns false (opt-out).
     * Protocols override this to enable frame-header-based routing without full decoding.
     *
     * Note: header-based routing is a hint only.  [canHandle] (device-name / manufacturer-ID)
     * remains the authoritative gate for protocol selection at connection time.
     */
    fun looksLikeMyFrames(chunk: ByteArray): Boolean = false

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

    /**
     * Get BMS (Battery Management System) data for this protocol.
     * Returns null if this protocol does not support BMS data extraction.
     * Protocols that support BMS data should override this method to return
     * a list of BMSData objects representing the current state of all battery packs.
     */
    fun getBMSData(): List<BMSData>? = null
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
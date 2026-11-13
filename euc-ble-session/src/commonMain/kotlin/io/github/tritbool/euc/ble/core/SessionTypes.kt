package io.github.tritbool.euc.ble.core

import io.github.tritbool.euc.ble.exceptions.BLEException
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.CommandType

/**
 * Scan-related callbacks exposed by platform connectors.
 *
 * Threading contract:
 * - These callbacks are invoked from background execution contexts.
 * - They are not guaranteed to run on the Android main thread.
 *
 * Implications for callers:
 * - Do not update Android views directly from these methods unless you explicitly
 *   switch to the main thread first.
 * - Prefer forwarding these events to a `Flow`, `StateFlow`, `LiveData`, or another
 *   application-level state container consumed by the UI layer.
 */
interface ScanCallback {
    fun onScanStarted() {/*empty body to avoid multiple reimplementing*/}
    fun onDeviceDiscovered(device: EUCDevice) {/*empty body to avoid multiple reimplementing*/}
    fun onScanCompleted(devices: List<EUCDevice>) {/*empty body to avoid multiple reimplementing*/}
}

/**
 * Callback receiving decoded [EUCData] frames.
 *
 * Threading contract:
 * - Invoked from the manager's background coroutine scope.
 * - Not guaranteed to run on the Android main thread.
 *
 * Recommended usage:
 * - Perform parsing, aggregation, logging, persistence, or domain processing directly.
 * - For UI updates, switch explicitly to the main thread or publish the value through
 *   a UI-observed state container.
 */
interface DataCallback {
    fun onDataReceived(data: EUCData)
}

/**
 * Callback receiving errors produced by the BLE manager.
 *
 * Threading contract:
 * - Invoked from background execution contexts.
 * - Not guaranteed to run on the Android main thread.
 *
 * Recommended usage:
 * - Handle logging, metrics, and state transitions directly.
 * - Switch explicitly to the main thread before displaying Android UI such as
 *   dialogs, snackbars, or toasts.
 */
interface ErrorCallback {
    fun onError(error: BLEException)
}

enum class ProtocolSelectionMode {
    AUTO,
    AUTO_WITH_MANUAL_FALLBACK,
    FORCED
}

enum class ProtocolSelectionReason {
    AUTO_GATT_FINGERPRINT,
    AUTO_DEVICE_NAME,
    MANUAL_FALLBACK,
    FORCED,
    AUTO_TELEMETRY
}

data class ProtocolSelection(
    val protocolId: String,
    val manufacturer: String,
    val protocolClassName: String,
    val reason: ProtocolSelectionReason
)

enum class QueryTracePhase {
    SENT, RESPONSE_MATCHED, TIMEOUT, RETRY_SCHEDULED, UNSUPPORTED, FAILED
}

data class QueryTraceEvent(
    val timestampMs: Long,
    val protocol: String,
    val queryId: String,
    val commandType: CommandType,
    val phase: QueryTracePhase,
    val attempt: Int,
    val latencyMs: Long? = null,
    val message: String? = null
)

package io.github.tritbool.euc.ble

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import io.github.tritbool.euc.ble.core.AndroidLogger
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.BLEManager
import io.github.tritbool.euc.ble.core.ConnectionCallback
import io.github.tritbool.euc.ble.core.DataCallback
import io.github.tritbool.euc.ble.core.ErrorCallback
import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.core.ProtocolSelectionMode
import io.github.tritbool.euc.ble.core.QueryTraceEvent
import io.github.tritbool.euc.ble.core.TrafficEvent
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.ExtremeBullProtocol
import io.github.tritbool.euc.ble.protocols.GotwayProtocol
import io.github.tritbool.euc.ble.protocols.InMotionProtocol
import io.github.tritbool.euc.ble.protocols.KingsongProtocol
import io.github.tritbool.euc.ble.protocols.LeaperkimProtocol
import io.github.tritbool.euc.ble.protocols.NinebotProtocol
import io.github.tritbool.euc.ble.protocols.NinebotZProtocol
import io.github.tritbool.euc.ble.protocols.NosfetProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Main public entry point for EUC BLE discovery, connection and telemetry.
 *
 * Protocol registration is fully managed internally; client applications should not register
 * brand-specific protocols, but may inspect and manually select from the registered protocols
 * using [getRegisteredProtocols].
 */
class EucBleClient(
    context: Context,
    logger: Logger = AndroidLogger(),
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    internal val bleManager = BLEManager(context, logger, coroutineScope)

    init {
        registerBuiltInProtocols(coroutineScope)
    }

    fun initialize() {
        bleManager.initialize()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        bleManager.startScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        bleManager.stopScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: EUCDevice) {
        bleManager.connect(device)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        bleManager.disconnect()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendCommand(commandType: CommandType, value: Any = Unit) {
        bleManager.sendCommand(commandType, value)
    }

    fun getCommandSupport(commandType: CommandType): CommandSupport {
        return bleManager.getCommandSupport(commandType)
    }

    /** Latest wheel-confirmed security state. Null when unavailable; collect for client PIN UI. */
    val securityStateFlow: StateFlow<WheelSecurityState?> = bleManager.securityStateFlow

    /** Submit a four-ASCII-digit KingSong PIN. No persistent storage or reconnect replay. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun authenticate(pin: String) {
        require(pin.length == 4 && pin.all { it in '0'..'9' }) {
            "PIN must contain exactly four ASCII digits"
        }
        sendCommand(CommandType.AUTHENTICATE, pin)
    }

    /** Explicit request only; confirmation arrives via [securityStateFlow]. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun lock() = sendCommand(CommandType.LOCK)

    /** Experimental KingSong unlock request; verify on the specific wheel/firmware. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun unlock() = sendCommand(CommandType.UNLOCK)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestLockState() = sendCommand(CommandType.REQUEST_LOCK_STATE)

    fun getConnectionState(): BLEConstants.ConnectionState = bleManager.getConnectionState()

    fun getConnectedDevice(): EUCDevice? = bleManager.getConnectedDevice()

    /**
     * Returns the latest BMS snapshot of the connected wheel (cell voltages, temperatures,
     * pack voltage/current, charging status), or `null` when the wheel does not expose BMS data.
     *
     * Poll it as often as needed; what is done with the snapshot (display, logging, ...) is
     * entirely up to the client application.
     */
    fun getBMSData(): List<BMSData>? = bleManager.getBMSData()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setProtocolSelectionMode(mode: ProtocolSelectionMode) {
        bleManager.setProtocolSelectionMode(mode)
    }

    fun getProtocolSelectionMode(): ProtocolSelectionMode = bleManager.getProtocolSelectionMode()

    /**
     * Returns all registered protocols. Callers can use this list to freely choose a protocol
     * for manual selection via [selectProtocol] or [forceProtocol].
     */
    fun getRegisteredProtocols(): List<EUCProtocol> = bleManager.getRegisteredProtocols()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun selectProtocol(protocol: EUCProtocol): Boolean {
        return bleManager.selectProtocol(protocol)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun forceProtocol(protocol: EUCProtocol): Boolean {
        return bleManager.forceProtocol(protocol)
    }

    fun clearForcedProtocol() {
        bleManager.clearForcedProtocol()
    }

    fun setConnectionCallback(callback: ConnectionCallback) {
        bleManager.setConnectionCallback(callback)
    }

    fun setDataCallback(callback: DataCallback) {
        bleManager.setDataCallback(callback)
    }

    fun setErrorCallback(callback: ErrorCallback) {
        bleManager.setErrorCallback(callback)
    }

    /** Bidirectional BLE capture; see [BLEManager.trafficFlow] for delivery and buffering semantics. */
    val trafficFlow: SharedFlow<TrafficEvent> = bleManager.trafficFlow

    /** RX-only view of [trafficFlow]. Bytes are shared, read-only notification chunks. */
    val rawFrameFlow: Flow<ByteArray> = bleManager.rawFrameFlow
    val queryTraceFlow: SharedFlow<QueryTraceEvent> = bleManager.queryTraceFlow

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun cleanup() {
        bleManager.cleanup()
    }

    private fun registerBuiltInProtocols(scope: CoroutineScope) {
        bleManager.registerProtocol(KingsongProtocol(scope = scope))
        bleManager.registerProtocol(GotwayProtocol(scope = scope))
        bleManager.registerProtocol(ExtremeBullProtocol(scope = scope))
        bleManager.registerProtocol(InMotionProtocol())
        bleManager.registerProtocol(NinebotZProtocol())
        bleManager.registerProtocol(NinebotProtocol())
        bleManager.registerProtocol(NosfetProtocol(scope = scope))
        bleManager.registerProtocol(LeaperkimProtocol(scope = scope))
    }
}

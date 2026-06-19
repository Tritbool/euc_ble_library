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
import io.github.tritbool.euc.ble.core.QueryTraceEvent
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.CommandType
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
import kotlinx.coroutines.flow.SharedFlow

/**
 * Main public entry point for EUC BLE discovery, connection and telemetry.
 *
 * Protocol registration is fully managed internally; client applications should not select
 * or register brand-specific protocols.
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

    fun getConnectionState(): BLEConstants.ConnectionState = bleManager.getConnectionState()

    fun getConnectedDevice(): EUCDevice? = bleManager.getConnectedDevice()

    fun setConnectionCallback(callback: ConnectionCallback) {
        bleManager.setConnectionCallback(callback)
    }

    fun setDataCallback(callback: DataCallback) {
        bleManager.setDataCallback(callback)
    }

    fun setErrorCallback(callback: ErrorCallback) {
        bleManager.setErrorCallback(callback)
    }

    val rawFrameFlow: SharedFlow<ByteArray> = bleManager.rawFrameFlow
    val queryTraceFlow: SharedFlow<QueryTraceEvent> = bleManager.queryTraceFlow

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun cleanup() {
        bleManager.cleanup()
    }

    private fun registerBuiltInProtocols(scope: CoroutineScope) {
        bleManager.registerProtocol(KingsongProtocol(scope =scope))
        bleManager.registerProtocol(GotwayProtocol(scope=scope))
        bleManager.registerProtocol(InMotionProtocol())
        bleManager.registerProtocol(NinebotZProtocol())
        bleManager.registerProtocol(NinebotProtocol())
        bleManager.registerProtocol(NosfetProtocol(scope=scope))
        bleManager.registerProtocol(LeaperkimProtocol(scope=scope))
    }
}

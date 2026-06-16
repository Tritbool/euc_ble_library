package com.euc.ble

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.euc.ble.core.AndroidLogger
import com.euc.ble.core.BLEConstants
import com.euc.ble.core.BLEManager
import com.euc.ble.core.ConnectionCallback
import com.euc.ble.core.DataCallback
import com.euc.ble.core.ErrorCallback
import com.euc.ble.core.Logger
import com.euc.ble.core.QueryTraceEvent
import com.euc.ble.models.EUCDevice
import com.euc.ble.protocols.CommandSupport
import com.euc.ble.protocols.CommandType
import com.euc.ble.protocols.GotwayProtocol
import com.euc.ble.protocols.InMotionProtocol
import com.euc.ble.protocols.KingsongProtocol
import com.euc.ble.protocols.LeaperkimProtocol
import com.euc.ble.protocols.NinebotProtocol
import com.euc.ble.protocols.NinebotZProtocol
import com.euc.ble.protocols.NosfetProtocol
import kotlinx.coroutines.flow.SharedFlow

/**
 * Main public entry point for EUC BLE discovery, connection and telemetry.
 *
 * Protocol registration is fully managed internally; client applications should not select
 * or register brand-specific protocols.
 */
class EucBleClient(
    context: Context,
    logger: Logger = AndroidLogger()
) {
    internal val bleManager = BLEManager(context, logger)

    init {
        registerBuiltInProtocols()
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

    private fun registerBuiltInProtocols() {
        bleManager.registerProtocol(KingsongProtocol())
        bleManager.registerProtocol(GotwayProtocol())
        bleManager.registerProtocol(InMotionProtocol())
        bleManager.registerProtocol(NinebotZProtocol())
        bleManager.registerProtocol(NinebotProtocol())
        bleManager.registerProtocol(NosfetProtocol())
        bleManager.registerProtocol(LeaperkimProtocol())
    }
}

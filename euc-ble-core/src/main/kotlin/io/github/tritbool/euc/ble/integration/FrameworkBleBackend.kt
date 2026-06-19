package io.github.tritbool.euc.ble.integration

import android.Manifest
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.BLEManager
import io.github.tritbool.euc.ble.core.DataCallback
import io.github.tritbool.euc.ble.core.ErrorCallback
import io.github.tritbool.euc.ble.core.ListenerConnectionCallback
import io.github.tritbool.euc.ble.exceptions.BLEException
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID

internal class FrameworkBleBackend(
    private val bleManager: BLEManager,
    private val ownsScope: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : BleBackend {

    override val type: BleBackendType = BleBackendType.FRAMEWORK
    private var listener: BleBackendListener? = null
    private var rawFrameJob: Job? = null
    private var isInitialized: Boolean = false

    @Synchronized
    override fun initialize() {
        bleManager.initialize()
        installCallbacks()
        installRawFrameForwarder()
        isInitialized = true
    }

    override fun registerProtocol(protocol: EUCProtocol) {
        bleManager.registerProtocol(protocol)
    }

    @Synchronized
    override fun setListener(listener: BleBackendListener?) {
        this.listener = listener
        if (isInitialized) {
            installRawFrameForwarder()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun startScan() {
        bleManager.startScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun stopScan() {
        bleManager.stopScan()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun connect(device: EUCDevice) {
        bleManager.connect(device)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun disconnect() {
        bleManager.disconnect()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun sendCommand(command: ByteArray, characteristicUuid: UUID) {
        bleManager.sendCommand(command, characteristicUuid)
    }

    override fun getConnectionState(): BLEConstants.ConnectionState = bleManager.getConnectionState()

    override fun getConnectedDevice(): EUCDevice? = bleManager.getConnectedDevice()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun cleanup() {
        rawFrameJob?.cancel()
        rawFrameJob = null
        if (ownsScope) {
            scope.cancel()
        }
        bleManager.cleanup()
    }

    private fun installCallbacks() {
        bleManager.setConnectionCallback(ListenerConnectionCallback(listener, bleManager) )

        bleManager.setDataCallback(object : DataCallback {
            override fun onDataReceived(data: io.github.tritbool.euc.ble.models.EUCData) {
                listener?.onEvent(BleBackendEvent.DataReceived(data))
            }
        })

        bleManager.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) {
                listener?.onEvent(BleBackendEvent.Error(error))
            }
        })
    }

    @Synchronized
    private fun installRawFrameForwarder() {
        rawFrameJob?.cancel()
        rawFrameJob = bleManager.rawFrameFlow
            .onEach { payload ->
                listener?.onEvent(BleBackendEvent.RawFrameReceived(payload))
            }
            .launchIn(scope)
    }
}

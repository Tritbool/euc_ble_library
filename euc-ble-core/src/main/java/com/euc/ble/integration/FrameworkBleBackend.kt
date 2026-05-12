package com.euc.ble.integration

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.BLEManager
import com.euc.ble.core.ConnectionCallback
import com.euc.ble.core.DataCallback
import com.euc.ble.core.ErrorCallback
import com.euc.ble.exceptions.BLEException
import com.euc.ble.models.EUCDevice
import com.euc.ble.protocols.EUCProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID

class FrameworkBleBackend(
    private val bleManager: BLEManager,
    private val ownsScope: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) : BleBackend {

    override val type: BleBackendType = BleBackendType.FRAMEWORK
    private var listener: BleBackendListener? = null
    private var rawFrameJob: Job? = null

    override fun initialize() {
        bleManager.initialize()
        installCallbacks()
        installRawFrameForwarder()
    }

    override fun registerProtocol(protocol: EUCProtocol) {
        bleManager.registerProtocol(protocol)
    }

    override fun setListener(listener: BleBackendListener?) {
        this.listener = listener
    }

    override fun startScan() {
        bleManager.startScan()
    }

    override fun stopScan() {
        bleManager.stopScan()
    }

    override fun connect(device: EUCDevice) {
        bleManager.connect(device)
    }

    override fun disconnect() {
        bleManager.disconnect()
    }

    override fun sendCommand(command: ByteArray, characteristicUuid: UUID) {
        bleManager.sendCommand(command, characteristicUuid)
    }

    override fun getConnectionState(): BLEConstants.ConnectionState = bleManager.getConnectionState()

    override fun getConnectedDevice(): EUCDevice? = bleManager.getConnectedDevice()

    override fun cleanup() {
        rawFrameJob?.cancel()
        rawFrameJob = null
        bleManager.cleanup()
        if (ownsScope) {
            scope.cancel()
        }
    }

    private fun installCallbacks() {
        bleManager.setConnectionCallback(object : ConnectionCallback() {
            override fun onScanStarted() {
                listener?.onEvent(BleBackendEvent.ScanStarted)
            }

            override fun onDeviceDiscovered(device: EUCDevice) {
                listener?.onEvent(BleBackendEvent.DeviceDiscovered(device))
            }

            override fun onScanCompleted(devices: List<EUCDevice>) {
                listener?.onEvent(BleBackendEvent.ScanCompleted(devices))
            }

            override fun onConnected() {
                listener?.onEvent(BleBackendEvent.Connected(bleManager.getConnectedDevice()))
            }

            override fun onDisconnected() {
                listener?.onEvent(BleBackendEvent.Disconnected)
            }

            override fun onConnectionFailed(error: BLEException) {
                listener?.onEvent(BleBackendEvent.Error(error))
            }

            override fun onServicesDiscovered(services: List<android.bluetooth.BluetoothGattService>) {
                listener?.onEvent(BleBackendEvent.ServicesDiscovered(services.map { it.uuid }))
            }

            override fun onMtuChanged(mtu: Int) {
                listener?.onEvent(BleBackendEvent.MtuChanged(mtu))
            }
        })

        bleManager.setDataCallback(object : DataCallback {
            override fun onDataReceived(data: com.euc.ble.models.EUCData) {
                listener?.onEvent(BleBackendEvent.DataReceived(data))
            }
        })

        bleManager.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) {
                listener?.onEvent(BleBackendEvent.Error(error))
            }
        })
    }

    private fun installRawFrameForwarder() {
        rawFrameJob?.cancel()
        rawFrameJob = bleManager.rawFrameFlow
            .onEach { payload ->
                listener?.onEvent(BleBackendEvent.RawFrameReceived(payload.clone()))
            }
            .launchIn(scope)
    }
}

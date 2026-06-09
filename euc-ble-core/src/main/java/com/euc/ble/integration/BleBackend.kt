package com.euc.ble.integration

import com.euc.ble.core.BLEConstants
import com.euc.ble.exceptions.BLEException
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import com.euc.ble.protocols.EUCProtocol
import java.util.UUID

internal enum class BleBackendType {
    LEGACY,
    FRAMEWORK
}

sealed interface BleBackendEvent {
    data object ScanStarted : BleBackendEvent
    data class DeviceDiscovered(val device: EUCDevice) : BleBackendEvent
    data class ScanCompleted(val devices: List<EUCDevice>) : BleBackendEvent
    data class Connected(val device: EUCDevice?) : BleBackendEvent
    data object Disconnected : BleBackendEvent
    data class ServicesDiscovered(val serviceUuids: List<UUID>) : BleBackendEvent
    data class MtuChanged(val mtu: Int) : BleBackendEvent
    data class DataReceived(val data: EUCData) : BleBackendEvent
    class RawFrameReceived(payload: ByteArray) : BleBackendEvent {
        // Defensive copy keeps event payload immutable for consumers.
        val payload: ByteArray = payload.clone()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawFrameReceived) return false
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = payload.contentHashCode()
    }
    data class Error(val error: BLEException) : BleBackendEvent
}

fun interface BleBackendListener {
    fun onEvent(event: BleBackendEvent)
}

internal interface BleBackend {
    val type: BleBackendType

    fun initialize()
    fun registerProtocol(protocol: EUCProtocol)
    fun setListener(listener: BleBackendListener?)
    fun startScan()
    fun stopScan()
    fun connect(device: EUCDevice)
    fun disconnect()
    fun sendCommand(command: ByteArray, characteristicUuid: UUID)
    fun getConnectionState(): BLEConstants.ConnectionState
    fun getConnectedDevice(): EUCDevice?
    fun cleanup()
}

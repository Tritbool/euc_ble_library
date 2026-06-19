package io.github.tritbool.euc.ble.integration

import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.exceptions.BLEException
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import java.util.UUID

/**
 * Types of BLE backend implementations available.
 */
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
    /**
     * Event emitted when a raw BLE frame is received.
     * The payload is a defensive copy to ensure immutability for consumers.
     */
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
    /**
     * Event emitted when an error occurs in the BLE backend.
     */
    data class Error(val error: BLEException) : BleBackendEvent
}

/**
 * Listener interface for receiving BLE backend events.
 */
fun interface BleBackendListener {
    /**
     * Called when a BLE backend event occurs.
     *
     * @param event The event that occurred
     */
    fun onEvent(event: BleBackendEvent)
}

/**
 * Internal interface for BLE backend implementations.
 *
 * Backends handle the actual BLE operations (scanning, connecting, data transfer)
 * and forward events to registered listeners.
 */
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

package io.github.tritbool.euc.ble.integration

import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import java.util.UUID

/**
 * Contract expected from a WheelLog legacy BLE implementation.
 * This allows wrapping legacy code without changing app business/UI layers.
 */
internal interface LegacyBleEngine {
    fun initialize()
    fun setListener(listener: BleBackendListener?)
    fun startScan()
    fun stopScan()
    fun connect(device: EUCDevice)
    fun disconnect()
    fun sendCommand(command: ByteArray, characteristicUuid: UUID)
    fun getConnectionState(): BLEConstants.ConnectionState
    fun getConnectedDevice(): EUCDevice?
    fun cleanup()

    /**
     * Optional in legacy implementations. No-op is acceptable.
     */
    fun registerProtocol(protocol: EUCProtocol) {}
}

/**
 * BLE backend that wraps a legacy [LegacyBleEngine] implementation.
 *
 * This adapter allows legacy WheelLog BLE code to be used through the modern [BleBackend] interface.
 *
 * @param legacyEngine The legacy BLE engine to wrap
 */
internal class LegacyBleBackend(
    private val legacyEngine: LegacyBleEngine
) : BleBackend {
    override val type: BleBackendType = BleBackendType.LEGACY

    override fun initialize() {
        legacyEngine.initialize()
    }

    override fun registerProtocol(protocol: EUCProtocol) {
        legacyEngine.registerProtocol(protocol)
    }

    override fun setListener(listener: BleBackendListener?) {
        legacyEngine.setListener(listener)
    }

    override fun startScan() {
        legacyEngine.startScan()
    }

    override fun stopScan() {
        legacyEngine.stopScan()
    }

    override fun connect(device: EUCDevice) {
        legacyEngine.connect(device)
    }

    override fun disconnect() {
        legacyEngine.disconnect()
    }

    override fun sendCommand(command: ByteArray, characteristicUuid: UUID) {
        legacyEngine.sendCommand(command, characteristicUuid)
    }

    override fun getConnectionState(): BLEConstants.ConnectionState = legacyEngine.getConnectionState()

    override fun getConnectedDevice(): EUCDevice? = legacyEngine.getConnectedDevice()

    override fun cleanup() {
        legacyEngine.cleanup()
    }
}

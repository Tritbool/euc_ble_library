package com.euc.ble.integration

import com.euc.ble.core.BLEConstants
import com.euc.ble.models.EUCDevice
import com.euc.ble.protocols.EUCProtocol
import java.util.UUID

internal class SwitchableBleBackend(
    private val frameworkBackend: BleBackend,
    private val legacyBackend: BleBackend,
    initialType: BleBackendType = BleBackendType.FRAMEWORK
) : BleBackend {

    override val type: BleBackendType
        get() = activeBackend.type

    private var listener: BleBackendListener? = null
    private var activeBackend: BleBackend = resolve(initialType)

    @Synchronized
    fun switchTo(type: BleBackendType, cleanupPrevious: Boolean = false) {
        if (activeBackend.type == type) return
        val previous = activeBackend
        activeBackend = resolve(type)
        activeBackend.setListener(listener)
        if (cleanupPrevious) {
            previous.cleanup()
        } else {
            previous.disconnect()
            previous.stopScan()
        }
    }

    override fun initialize() {
        frameworkBackend.initialize()
        legacyBackend.initialize()
    }

    override fun registerProtocol(protocol: EUCProtocol) {
        frameworkBackend.registerProtocol(protocol)
        legacyBackend.registerProtocol(protocol)
    }

    @Synchronized
    override fun setListener(listener: BleBackendListener?) {
        this.listener = listener
        activeBackend.setListener(listener)
    }

    @Synchronized
    override fun startScan() {
        activeBackend.startScan()
    }

    @Synchronized
    override fun stopScan() {
        activeBackend.stopScan()
    }

    @Synchronized
    override fun connect(device: EUCDevice) {
        activeBackend.connect(device)
    }

    @Synchronized
    override fun disconnect() {
        activeBackend.disconnect()
    }

    @Synchronized
    override fun sendCommand(command: ByteArray, characteristicUuid: UUID) {
        activeBackend.sendCommand(command, characteristicUuid)
    }

    @Synchronized
    override fun getConnectionState(): BLEConstants.ConnectionState = activeBackend.getConnectionState()

    @Synchronized
    override fun getConnectedDevice(): EUCDevice? = activeBackend.getConnectedDevice()

    override fun cleanup() {
        frameworkBackend.cleanup()
        legacyBackend.cleanup()
    }

    private fun resolve(type: BleBackendType): BleBackend {
        return when (type) {
            BleBackendType.FRAMEWORK -> frameworkBackend
            BleBackendType.LEGACY -> legacyBackend
        }
    }
}

package io.github.tritbool.euc.ble.core

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import io.github.tritbool.euc.ble.exceptions.BLEException
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class BLEManagerProtocolSelectionTest {
    private lateinit var manager: BLEManager
    private lateinit var callback: RecordingConnectionCallback

    @BeforeEach
    fun setUp() {
        manager = BLEManager(mock<Context>(), NoOpLogger())
        callback = RecordingConnectionCallback()
        manager.setConnectionCallback(callback)
    }

    @Test
    fun autoWithManualFallbackRequestsManualSelectionWhenNoFingerprintMatches() {
        val alpha = AlphaProtocol(uuid("00000000-0000-0000-0000-0000000000A1"))
        val beta = BetaProtocol(uuid("00000000-0000-0000-0000-0000000000B1"))
        register(alpha, beta)

        val gatt = createGattWithCharacteristics(alpha.getDataCharacteristicUUID(), beta.getDataCharacteristicUUID())
        attachSession(device("Unknown wheel", 0), gatt)

        manager.setProtocolSelectionMode(ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNull(manager.currentProtocol)
        assertEquals(listOf(alpha, beta), callback.requiredSelections.single())
        assertTrue(callback.selectedProtocols.isEmpty())
    }

    @Test
    fun manualSelectionActivatesChosenProtocol() {
        val alpha = AlphaProtocol(uuid("00000000-0000-0000-0000-0000000000A2"))
        val beta = BetaProtocol(uuid("00000000-0000-0000-0000-0000000000B2"))
        register(alpha, beta)

        val gatt = createGattWithCharacteristics(alpha.getDataCharacteristicUUID(), beta.getDataCharacteristicUUID())
        attachSession(device("Unknown wheel", 0), gatt)

        manager.setProtocolSelectionMode(ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNull(manager.currentProtocol)

        assertTrue(manager.selectProtocol(beta))
        assertEquals(beta, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.MANUAL_FALLBACK, callback.selectedProtocols.single().reason)
        assertEquals("BetaProtocol", callback.selectedProtocols.single().protocolId)
    }

    @Test
    fun forcedProtocolOverridesAutoSelection() {
        val alpha = AlphaProtocol(uuid("00000000-0000-0000-0000-0000000000A3"))
        val beta = BetaProtocol(uuid("00000000-0000-0000-0000-0000000000B3"))
        register(alpha, beta)

        val gatt = createGattWithCharacteristics(alpha.getDataCharacteristicUUID(), beta.getDataCharacteristicUUID())
        attachSession(device("Unknown wheel", 0), gatt)

        assertTrue(manager.forceProtocol(beta))
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertEquals(beta, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.FORCED, callback.selectedProtocols.single().reason)
        assertTrue(callback.requiredSelections.isEmpty())
    }

    @Test
    fun autoModeDoesNotFireSelectionCallbackWhenNoFingerprintMatches() {
        val alpha = AlphaProtocol(uuid("00000000-0000-0000-0000-0000000000A4"))
        val beta = BetaProtocol(uuid("00000000-0000-0000-0000-0000000000B4"))
        register(alpha, beta)

        val gatt = createGattWithCharacteristics(alpha.getDataCharacteristicUUID(), beta.getDataCharacteristicUUID())
        attachSession(device("Unknown wheel", 0), gatt)

        // AUTO mode (default): no fingerprint match → no protocol selected, no callback
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNull(manager.currentProtocol)
        assertTrue(callback.requiredSelections.isEmpty())
    }

    @Test
    fun manualSelectionRejectsProtocolWhoseCharacteristicIsUnavailable() {
        val alpha = AlphaProtocol(uuid("00000000-0000-0000-0000-0000000000A5"))
        val beta = BetaProtocol(uuid("00000000-0000-0000-0000-0000000000B5"))
        register(alpha, beta)

        val gatt = createGattWithCharacteristics(alpha.getDataCharacteristicUUID())
        attachSession(device("Unknown wheel", 0), gatt)

        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        val errors = mutableListOf<BLEException>()
        manager.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) {
                errors += error
            }
        })

        assertFalse(manager.selectProtocol(beta))
        assertTrue(errors.single().message!!.contains("No connected device available for manual protocol selection", ignoreCase = true))
    }

    @Test
    fun getRegisteredProtocolsReturnsAllRegisteredProtocols() {
        val alpha = AlphaProtocol(uuid("00000000-0000-0000-0000-0000000000A6"))
        val beta = BetaProtocol(uuid("00000000-0000-0000-0000-0000000000B6"))
        register(alpha, beta)
        assertEquals(listOf(alpha, beta), manager.getRegisteredProtocols())
    }

    private fun register(vararg protocols: EUCProtocol) {
        protocols.forEach(manager::registerProtocol)
    }

    private fun attachSession(device: EUCDevice, gatt: BluetoothGatt) {
        setPrivateField("currentDevice", device)
        setPrivateField("bluetoothGatt", gatt)
    }

    private fun createGattWithCharacteristics(vararg characteristicUuids: UUID): BluetoothGatt {
        val cccdUuid = UUID.fromString(BLEConstants.CCCD_DESCRIPTOR)
        val characteristics = characteristicUuids.map { uuid ->
            mock<BluetoothGattCharacteristic>().also { characteristic ->
                whenever(characteristic.uuid).thenReturn(uuid)
                whenever(characteristic.getDescriptor(cccdUuid)).thenReturn(mock<BluetoothGattDescriptor>())
            }
        }
        val service = mock<BluetoothGattService>()
        whenever(service.uuid).thenReturn(uuid("00000000-0000-0000-0000-00000000F000"))
        whenever(service.characteristics).thenReturn(characteristics)
        characteristicUuids.forEachIndexed { i, uuid ->
            whenever(service.getCharacteristic(uuid)).thenReturn(characteristics[i])
        }

        return mock<BluetoothGatt>().also { gatt ->
            whenever(gatt.services).thenReturn(listOf(service))
        }
    }

    private fun device(name: String, manufacturerId: Int): EUCDevice {
        return EUCDevice(
            name = name,
            address = "AA:BB:CC:DD:EE:FF",
            manufacturerId = manufacturerId,
            manufacturerData = null,
            rssi = -45
        )
    }

    private fun setPrivateField(name: String, value: Any?) {
        val field = BLEManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(manager, value)
    }

    private fun uuid(value: String): UUID = UUID.fromString(value)

    private class RecordingConnectionCallback : ConnectionCallback() {
        val requiredSelections = mutableListOf<List<EUCProtocol>>()
        val selectedProtocols = mutableListOf<ProtocolSelection>()

        override fun onProtocolSelectionRequired(protocols: List<EUCProtocol>) {
            requiredSelections += protocols
        }

        override fun onProtocolSelected(selection: ProtocolSelection) {
            selectedProtocols += selection
        }
    }

    private abstract class TestProtocol(
        private val dataCharacteristicUuid: UUID
    ) : EUCProtocol {
        override val manufacturer: String = "Test"
        override val dataFlow: Flow<EUCData> = emptyFlow()
        override fun decode(data: ByteArray): EUCData? = null
        override fun getDataCharacteristicUUID(): UUID = dataCharacteristicUuid
        override fun getServiceUUID(): UUID = UUID.fromString("00000000-0000-0000-0000-00000000F000")
        override fun createCommand(commandType: CommandType, value: Any): ByteArray = byteArrayOf(0x01)
        override fun isDeviceReady(data: EUCData): Boolean = true
        override fun close() = Unit
    }

    private class AlphaProtocol(dataCharacteristicUuid: UUID) : TestProtocol(dataCharacteristicUuid)
    private class BetaProtocol(dataCharacteristicUuid: UUID) : TestProtocol(dataCharacteristicUuid)
}

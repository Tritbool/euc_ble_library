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
    fun autoWithManualFallbackRequestsManualSelectionWhenMetadataHasNoMatch() {
        val alpha = AlphaProtocol("KingSong", listOf("MODEL-A"), uuid("00000000-0000-0000-0000-0000000000A1"))
        val beta = BetaProtocol("InMotion", listOf("MODEL-B"), uuid("00000000-0000-0000-0000-0000000000B1"))
        register(alpha, beta)

        val gatt = createGattWithCharacteristics(alpha.getDataCharacteristicUUID(), beta.getDataCharacteristicUUID())
        attachSession(device("Unknown wheel", 0), gatt)

        manager.setProtocolSelectionMode(ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNull(manager.currentProtocol)
        assertEquals(listOf("AlphaProtocol", "BetaProtocol"), callback.requiredSelections.single().map { it.id })
        assertTrue(callback.selectedProtocols.isEmpty())
    }

    @Test
    fun ambiguousCandidatesFollowManualSelectionPath() {
        val alpha = AlphaProtocol("KingSong", listOf("MODEL-A"), uuid("00000000-0000-0000-0000-0000000000A2"))
        val beta = BetaProtocol("KingSong", listOf("MODEL-B"), uuid("00000000-0000-0000-0000-0000000000B2"))
        register(alpha, beta)

        val gatt = createGattWithCharacteristics(alpha.getDataCharacteristicUUID(), beta.getDataCharacteristicUUID())
        attachSession(device("Unknown wheel", BLEConstants.MANUFACTURER_KINGSONG), gatt)

        manager.setProtocolSelectionMode(ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNull(manager.currentProtocol)
        assertEquals(listOf("AlphaProtocol", "BetaProtocol"), callback.requiredSelections.single().map { it.id })

        assertTrue(manager.selectProtocol("BetaProtocol"))
        assertEquals(beta, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.MANUAL_FALLBACK, callback.selectedProtocols.single().reason)
        assertEquals("BetaProtocol", callback.selectedProtocols.single().protocolId)
    }

    @Test
    fun forcedProtocolOverridesAutoSelection() {
        val alpha = AlphaProtocol("KingSong", listOf("MODEL-A"), uuid("00000000-0000-0000-0000-0000000000A3"))
        val beta = BetaProtocol("KingSong", listOf("MODEL-B"), uuid("00000000-0000-0000-0000-0000000000B3"))
        register(alpha, beta)

        val gatt = createGattWithCharacteristics(alpha.getDataCharacteristicUUID(), beta.getDataCharacteristicUUID())
        attachSession(device("MODEL-A", BLEConstants.MANUFACTURER_KINGSONG), gatt)

        assertTrue(manager.forceProtocol("BetaProtocol"))
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertEquals(beta, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.FORCED, callback.selectedProtocols.single().reason)
        assertTrue(callback.requiredSelections.isEmpty())
    }

    @Test
    fun defaultAutoModeRemainsBackwardCompatible() {
        val alpha = AlphaProtocol("KingSong", listOf("KS-S22"), uuid("00000000-0000-0000-0000-0000000000A4"))
        val beta = BetaProtocol("InMotion", listOf("V11"), uuid("00000000-0000-0000-0000-0000000000B4"))
        register(alpha, beta)

        val gatt = createGattWithCharacteristics(alpha.getDataCharacteristicUUID(), beta.getDataCharacteristicUUID())
        attachSession(device("KS-S22", BLEConstants.MANUFACTURER_KINGSONG), gatt)

        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertEquals(alpha, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.AUTO_METADATA, callback.selectedProtocols.single().reason)
        assertTrue(callback.requiredSelections.isEmpty())
    }

    @Test
    fun manualSelectionRejectsUnavailableProtocolForCurrentSession() {
        val alpha = AlphaProtocol("KingSong", listOf("KS-S22"), uuid("00000000-0000-0000-0000-0000000000A5"))
        val beta = BetaProtocol("InMotion", listOf("V11"), uuid("00000000-0000-0000-0000-0000000000B5"))
        register(alpha, beta)

        val gatt = createGattWithCharacteristics(alpha.getDataCharacteristicUUID())
        attachSession(device("KS-S22", BLEConstants.MANUFACTURER_KINGSONG), gatt)

        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        val errors = mutableListOf<BLEException>()
        manager.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) {
                errors += error
            }
        })

        assertFalse(manager.selectProtocol("BetaProtocol"))
        assertTrue(errors.single().message!!.contains("not available"))
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
        whenever(service.characteristics).thenReturn(characteristics)

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
        val requiredSelections = mutableListOf<List<ProtocolCandidate>>()
        val selectedProtocols = mutableListOf<ProtocolSelection>()

        override fun onProtocolSelectionRequired(candidates: List<ProtocolCandidate>) {
            requiredSelections += candidates
        }

        override fun onProtocolSelected(selection: ProtocolSelection) {
            selectedProtocols += selection
        }
    }

    private abstract class TestProtocol(
        override val manufacturer: String,
        override val supportedModels: List<String>,
        private val dataCharacteristicUuid: UUID
    ) : EUCProtocol {
        override val dataFlow: Flow<EUCData> = emptyFlow()

        override fun canHandle(device: EUCDevice): Boolean = false

        override fun decode(data: ByteArray): EUCData? = null

        override fun getDataCharacteristicUUID(): UUID = dataCharacteristicUuid

        override fun getServiceUUID(): UUID = UUID.fromString("00000000-0000-0000-0000-00000000F000")

        override fun createCommand(commandType: CommandType, value: Any): ByteArray = byteArrayOf(0x01)

        override fun isDeviceReady(data: EUCData): Boolean = true

        override fun close() = Unit
    }

    private class AlphaProtocol(
        manufacturer: String,
        supportedModels: List<String>,
        dataCharacteristicUuid: UUID
    ) : TestProtocol(manufacturer, supportedModels, dataCharacteristicUuid)

    private class BetaProtocol(
        manufacturer: String,
        supportedModels: List<String>,
        dataCharacteristicUuid: UUID
    ) : TestProtocol(manufacturer, supportedModels, dataCharacteristicUuid)
}

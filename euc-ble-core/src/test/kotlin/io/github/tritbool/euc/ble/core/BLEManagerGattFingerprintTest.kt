package io.github.tritbool.euc.ble.core

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.GattServiceSpec
import io.github.tritbool.euc.ble.protocols.GattSignature
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Tests for GATT service fingerprint-based protocol selection.
 *
 * Unit tests inject a custom [signaturesProvider] into [BLEManager.selectByGattFingerprint]
 * so fingerprints can be defined per-test without depending on [EucFingerprintDatabase].
 * Integration tests use [BLEManager.onServicesDiscovered] with the real DB via a stub class
 * named to match the database key ("KingsongProtocol").
 */
class BLEManagerGattFingerprintTest {

    private lateinit var manager: BLEManager
    private lateinit var callback: RecordingConnectionCallback

    @BeforeEach
    fun setUp() {
        manager = BLEManager(mock<Context>(), NoOpLogger())
        callback = RecordingConnectionCallback()
        manager.setConnectionCallback(callback)
    }

    // ──────────────────────── selectByGattFingerprint unit tests ────────────────────────

    @Test
    fun `returns null when no protocols have signatures`() {
        val proto = SimpleProtocol(uuid("AA000000-0000-0000-0000-000000000001"))
        manager.registerProtocol(proto)
        val gatt = gattWithServices(service(uuid("0000ffe0-0000-1000-8000-00805f9b34fb")))
        val emptyProvider: (String) -> List<GattSignature> = { emptyList() }
        assertNull(manager.selectByGattFingerprint(gatt.services, emptyProvider))
    }

    @Test
    fun `returns null when signature does not match any discovered service`() {
        val proto = SimpleProtocol(uuid("AA000000-0000-0000-0000-000000000002"))
        manager.registerProtocol(proto)
        val gatt = gattWithServices(service(uuid("0000ffe0-0000-1000-8000-00805f9b34fb")))
        val provider = providerFor(proto, GattServiceSpec(uuid = uuid("DEADBEEF-0000-0000-0000-000000000001")))
        assertNull(manager.selectByGattFingerprint(gatt.services, provider))
    }

    @Test
    fun `returns single matched protocol when exactly one signature matches`() {
        val kingSongLikeUuid = uuid("0000fff0-0000-1000-8000-00805f9b34fb")
        val exclusiveCharUuid = uuid("0000fff2-0000-1000-8000-00805f9b34fb")
        val proto = SimpleProtocol(uuid("AA000000-0000-0000-0000-000000000003"))
        manager.registerProtocol(proto)
        val provider = providerFor(proto,
            GattServiceSpec(uuid = kingSongLikeUuid, requiredCharacteristicUUIDs = setOf(exclusiveCharUuid))
        )
        val gatt = gattWithServices(service(kingSongLikeUuid, exclusiveCharUuid))
        assertEquals(proto, manager.selectByGattFingerprint(gatt.services, provider))
    }

    @Test
    fun `returns null when required characteristic is absent`() {
        val serviceUuid = uuid("0000fff0-0000-1000-8000-00805f9b34fb")
        val requiredCharUuid = uuid("0000fff2-0000-1000-8000-00805f9b34fb")
        val proto = SimpleProtocol(uuid("AA000000-0000-0000-0000-000000000004"))
        manager.registerProtocol(proto)
        val provider = providerFor(proto,
            GattServiceSpec(uuid = serviceUuid, requiredCharacteristicUUIDs = setOf(requiredCharUuid))
        )
        val gatt = gattWithServices(service(serviceUuid, uuid("0000fff1-0000-1000-8000-00805f9b34fb")))
        assertNull(manager.selectByGattFingerprint(gatt.services, provider))
    }

    @Test
    fun `returns null when excluded characteristic is present`() {
        val serviceUuid = uuid("00001800-0000-1000-8000-00805f9b34fb")
        val excludedCharUuid = uuid("00002aa6-0000-1000-8000-00805f9b34fb")
        val proto = SimpleProtocol(uuid("AA000000-0000-0000-0000-000000000005"))
        manager.registerProtocol(proto)
        val provider = providerFor(proto,
            GattServiceSpec(uuid = serviceUuid, excludedCharacteristicUUIDs = setOf(excludedCharUuid))
        )
        val gatt = gattWithServices(service(serviceUuid, excludedCharUuid))
        assertNull(manager.selectByGattFingerprint(gatt.services, provider))
    }

    @Test
    fun `matches when excluded characteristic is absent`() {
        val nordicUartUuid = uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val genericAccessUuid = uuid("00001800-0000-1000-8000-00805f9b34fb")
        val excludedCharUuid = uuid("00002aa6-0000-1000-8000-00805f9b34fb")
        val proto = SimpleProtocol(uuid("AA000000-0000-0000-0000-000000000006"))
        manager.registerProtocol(proto)
        val signature: GattSignature = listOf(
            GattServiceSpec(uuid = nordicUartUuid),
            GattServiceSpec(uuid = genericAccessUuid, excludedCharacteristicUUIDs = setOf(excludedCharUuid))
        )
        val provider: (String) -> List<GattSignature> = { listOf(signature) }
        val gatt = gattWithServices(
            service(nordicUartUuid, uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e")),
            service(genericAccessUuid, uuid("00002a00-0000-1000-8000-00805f9b34fb"))
        )
        assertEquals(proto, manager.selectByGattFingerprint(gatt.services, provider))
    }

    @Test
    fun `returns null when multiple protocols match the same GATT profile (ambiguous)`() {
        val sharedServiceUuid = uuid("DEADBEEF-0000-0000-0000-000000000000")
        val alpha = SimpleProtocol(uuid("AA000000-0000-0000-0000-000000000007"))
        val beta = SimpleProtocol(uuid("BB000000-0000-0000-0000-000000000007"))
        manager.registerProtocol(alpha)
        manager.registerProtocol(beta)
        val provider: (String) -> List<GattSignature> = {
            listOf(listOf(GattServiceSpec(uuid = sharedServiceUuid)))
        }
        val gatt = gattWithServices(service(sharedServiceUuid))
        assertNull(manager.selectByGattFingerprint(gatt.services, provider))
    }

    @Test
    fun `matches alternative signature when first signature does not match`() {
        val sig1ServiceUuid = uuid("AAAAAAAA-0000-0000-0000-000000000001")
        val sig2ServiceUuid = uuid("BBBBBBBB-0000-0000-0000-000000000001")
        val proto = SimpleProtocol(uuid("AA000000-0000-0000-0000-000000000008"))
        manager.registerProtocol(proto)
        val provider: (String) -> List<GattSignature> = {
            listOf(
                listOf(GattServiceSpec(uuid = sig1ServiceUuid)),
                listOf(GattServiceSpec(uuid = sig2ServiceUuid))
            )
        }
        val gatt = gattWithServices(service(sig2ServiceUuid))
        assertEquals(proto, manager.selectByGattFingerprint(gatt.services, provider))
    }

    // ──────────────────── InMotion V2 vs NinebotZ disambiguation ────────────────────

    @Test
    fun `InMotion V2 vs NinebotZ - matches InMotion V2 when 00002aa6 is present in 00001800`() {
        val nordicUart = uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val genericAccess = uuid("00001800-0000-1000-8000-00805f9b34fb")
        val centralAddressResolution = uuid("00002aa6-0000-1000-8000-00805f9b34fb")

        val inMotionV2 = SimpleProtocol(uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"), "InMotionV2")
        val ninebotZ = SimpleProtocol(uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"), "NinebotZ")
        manager.registerProtocol(inMotionV2)
        manager.registerProtocol(ninebotZ)

        val inMotionSignature: GattSignature = listOf(
            GattServiceSpec(uuid = nordicUart),
            GattServiceSpec(uuid = genericAccess, requiredCharacteristicUUIDs = setOf(centralAddressResolution))
        )
        val ninebotZSignature: GattSignature = listOf(
            GattServiceSpec(uuid = nordicUart),
            GattServiceSpec(uuid = genericAccess, excludedCharacteristicUUIDs = setOf(centralAddressResolution))
        )
        val provider: (String) -> List<GattSignature> = { name ->
            when (name) {
                "InMotionV2" -> listOf(inMotionSignature)
                "NinebotZ" -> listOf(ninebotZSignature)
                else -> emptyList()
            }
        }

        val gatt = gattWithServices(
            service(nordicUart, uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e")),
            service(genericAccess, uuid("00002a00-0000-1000-8000-00805f9b34fb"), centralAddressResolution)
        )
        assertEquals(inMotionV2, manager.selectByGattFingerprint(gatt.services, provider))
    }

    @Test
    fun `InMotion V2 vs NinebotZ - matches NinebotZ when 00002aa6 is absent from 00001800`() {
        val nordicUart = uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val genericAccess = uuid("00001800-0000-1000-8000-00805f9b34fb")
        val centralAddressResolution = uuid("00002aa6-0000-1000-8000-00805f9b34fb")

        val inMotionV2 = SimpleProtocol(uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"), "InMotionV2")
        val ninebotZ = SimpleProtocol(uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"), "NinebotZ")
        manager.registerProtocol(inMotionV2)
        manager.registerProtocol(ninebotZ)

        val inMotionSignature: GattSignature = listOf(
            GattServiceSpec(uuid = nordicUart),
            GattServiceSpec(uuid = genericAccess, requiredCharacteristicUUIDs = setOf(centralAddressResolution))
        )
        val ninebotZSignature: GattSignature = listOf(
            GattServiceSpec(uuid = nordicUart),
            GattServiceSpec(uuid = genericAccess, excludedCharacteristicUUIDs = setOf(centralAddressResolution))
        )
        val provider: (String) -> List<GattSignature> = { name ->
            when (name) {
                "InMotionV2" -> listOf(inMotionSignature)
                "NinebotZ" -> listOf(ninebotZSignature)
                else -> emptyList()
            }
        }

        val gatt = gattWithServices(
            service(nordicUart, uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e")),
            service(genericAccess, uuid("00002a00-0000-1000-8000-00805f9b34fb"))
        )
        assertEquals(ninebotZ, manager.selectByGattFingerprint(gatt.services, provider))
    }

    // ──────────────────── onServicesDiscovered integration tests ────────────────────

    @Test
    fun `onServicesDiscovered selects KingSong protocol by real GATT fingerprint`() {
        val kingSongServiceUuid = uuid("0000fff0-0000-1000-8000-00805f9b34fb")
        val kingSongExclusiveChar = uuid("0000fff2-0000-1000-8000-00805f9b34fb")
        val dataCharUuid = uuid("0000fff1-0000-1000-8000-00805f9b34fb")
        // Stub named "KingsongProtocol" matches the real DB entry
        val proto = KingsongProtocol(dataCharUuid)
        manager.registerProtocol(proto)

        val cccdUuid = UUID.fromString(BLEConstants.CCCD_DESCRIPTOR)
        val dataChar = mock<BluetoothGattCharacteristic>().also {
            whenever(it.uuid).thenReturn(dataCharUuid)
            whenever(it.getDescriptor(cccdUuid)).thenReturn(mock<BluetoothGattDescriptor>())
        }
        val exclusiveChar = mock<BluetoothGattCharacteristic>().also {
            whenever(it.uuid).thenReturn(kingSongExclusiveChar)
        }
        val exclusiveSvc = mock<BluetoothGattService>().also {
            whenever(it.uuid).thenReturn(kingSongServiceUuid)
            whenever(it.characteristics).thenReturn(listOf(exclusiveChar, dataChar))
            whenever(it.getCharacteristic(dataCharUuid)).thenReturn(dataChar)
        }
        val gatt = mock<BluetoothGatt>().also {
            whenever(it.services).thenReturn(listOf(exclusiveSvc))
        }
        setPrivateField("currentDevice", device("Unknown EUC", 0))
        setPrivateField("bluetoothGatt", gatt)

        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNotNull(manager.currentProtocol)
        assertEquals(proto, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.AUTO_GATT_FINGERPRINT, callback.selectedProtocols.single().reason)
    }

    @Test
    fun `onServicesDiscovered does not select protocol when no fingerprint matches`() {
        val dataCharUuid = uuid("AA000000-0000-0000-0000-000000000001")
        val proto = SimpleProtocol(dataCharUuid)
        manager.registerProtocol(proto)

        val cccdUuid = UUID.fromString(BLEConstants.CCCD_DESCRIPTOR)
        val dataChar = mock<BluetoothGattCharacteristic>().also {
            whenever(it.uuid).thenReturn(dataCharUuid)
            whenever(it.getDescriptor(cccdUuid)).thenReturn(mock<BluetoothGattDescriptor>())
        }
        val ffe0Svc = mock<BluetoothGattService>().also {
            whenever(it.uuid).thenReturn(uuid("0000ffe0-0000-1000-8000-00805f9b34fb"))
            whenever(it.characteristics).thenReturn(listOf(dataChar))
            whenever(it.getCharacteristic(dataCharUuid)).thenReturn(dataChar)
        }
        val gatt = mock<BluetoothGatt>().also {
            whenever(it.services).thenReturn(listOf(ffe0Svc))
        }
        setPrivateField("currentDevice", device("Unknown EUC", 0))
        setPrivateField("bluetoothGatt", gatt)

        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNull(manager.currentProtocol)
    }

    // ──────────────────────────────── Helpers ────────────────────────────────

    private fun device(name: String, manufacturerId: Int) = EUCDevice(
        name = name,
        address = "AA:BB:CC:DD:EE:FF",
        manufacturerId = manufacturerId,
        manufacturerData = null,
        rssi = -50
    )

    private fun uuid(value: String): UUID = UUID.fromString(value)

    private fun service(serviceUuid: UUID, vararg charUuids: UUID): BluetoothGattService {
        val characteristics = charUuids.map { charUuid ->
            mock<BluetoothGattCharacteristic>().also { char ->
                whenever(char.uuid).thenReturn(charUuid)
            }
        }
        return mock<BluetoothGattService>().also { svc ->
            whenever(svc.uuid).thenReturn(serviceUuid)
            whenever(svc.characteristics).thenReturn(characteristics)
        }
    }

    private fun gattWithServices(vararg services: BluetoothGattService): BluetoothGatt {
        return mock<BluetoothGatt>().also { gatt ->
            whenever(gatt.services).thenReturn(services.toList())
        }
    }

    private fun setPrivateField(name: String, value: Any?) {
        val field = BLEManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(manager, value)
    }

    private fun providerFor(proto: EUCProtocol, vararg specs: GattServiceSpec): (String) -> List<GattSignature> {
        val sig: GattSignature = specs.toList()
        return { name -> if (name == proto.javaClass.simpleName) listOf(sig) else emptyList() }
    }

    private class RecordingConnectionCallback : ConnectionCallback() {
        val selectedProtocols = mutableListOf<ProtocolSelection>()
        override fun onProtocolSelected(selection: ProtocolSelection) {
            selectedProtocols += selection
        }
    }

    /** Generic test-only protocol with no EucFingerprintDatabase entry. */
    private class SimpleProtocol(
        private val dataCharUuid: UUID,
        override val manufacturer: String = "Test"
    ) : EUCProtocol {
        override val dataFlow: Flow<EUCData> = emptyFlow()
        override fun decode(data: ByteArray): EUCData? = null
        override fun getDataCharacteristicUUID(): UUID = dataCharUuid
        override fun getServiceUUID(): UUID = uuid("00000000-0000-0000-0000-000000000000")
        override fun createCommand(commandType: CommandType, value: Any): ByteArray = byteArrayOf()
        override fun isDeviceReady(data: EUCData): Boolean = true
        override fun close() = Unit
        private fun uuid(v: String) = UUID.fromString(v)
    }

    /**
     * Stub whose simple class name "KingsongProtocol" matches the real EucFingerprintDatabase
     * entry, so integration tests use the real fingerprint without importing the actual protocol.
     */
    private class KingsongProtocol(private val dataCharUuid: UUID) : EUCProtocol {
        override val manufacturer: String = "KingSong"
        override val dataFlow: Flow<EUCData> = emptyFlow()
        override fun decode(data: ByteArray): EUCData? = null
        override fun getDataCharacteristicUUID(): UUID = dataCharUuid
        override fun getServiceUUID(): UUID = uuid("0000fff0-0000-1000-8000-00805f9b34fb")
        override fun createCommand(commandType: CommandType, value: Any): ByteArray = byteArrayOf()
        override fun isDeviceReady(data: EUCData): Boolean = true
        override fun close() = Unit
        private fun uuid(v: String) = UUID.fromString(v)
    }
}

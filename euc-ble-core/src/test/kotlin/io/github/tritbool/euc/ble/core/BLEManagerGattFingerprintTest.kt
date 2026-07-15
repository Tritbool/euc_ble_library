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
 * Covers the [BLEManager.selectByGattFingerprint] logic and its integration inside
 * [BLEManager.onServicesDiscovered], which mirrors WheelLog.Android's service-fingerprint
 * detection approach.
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
        val proto = NoSignatureProtocol(uuid("AA000000-0000-0000-0000-000000000001"))
        manager.registerProtocol(proto)
        val gatt = gattWithServices(service(uuid("0000ffe0-0000-1000-8000-00805f9b34fb")))
        assertNull(manager.selectByGattFingerprint(gatt.services))
    }

    @Test
    fun `returns null when signature does not match any discovered service`() {
        val proto = FingerprintProtocol(
            dataCharUuid = uuid("AA000000-0000-0000-0000-000000000002"),
            signatures = listOf(
                listOf(GattServiceSpec(uuid = uuid("DEADBEEF-0000-0000-0000-000000000001")))
            )
        )
        manager.registerProtocol(proto)
        val gatt = gattWithServices(service(uuid("0000ffe0-0000-1000-8000-00805f9b34fb")))
        assertNull(manager.selectByGattFingerprint(gatt.services))
    }

    @Test
    fun `returns single matched protocol when exactly one signature matches`() {
        val kingSongLikeUuid = uuid("0000fff0-0000-1000-8000-00805f9b34fb")
        val exclusiveCharUuid = uuid("0000fff2-0000-1000-8000-00805f9b34fb")
        val proto = FingerprintProtocol(
            dataCharUuid = uuid("AA000000-0000-0000-0000-000000000003"),
            signatures = listOf(
                listOf(
                    GattServiceSpec(
                        uuid = kingSongLikeUuid,
                        requiredCharacteristicUUIDs = setOf(exclusiveCharUuid)
                    )
                )
            )
        )
        manager.registerProtocol(proto)
        val gatt = gattWithServices(
            service(kingSongLikeUuid, exclusiveCharUuid)
        )
        assertEquals(proto, manager.selectByGattFingerprint(gatt.services))
    }

    @Test
    fun `returns null when required characteristic is absent`() {
        val serviceUuid = uuid("0000fff0-0000-1000-8000-00805f9b34fb")
        val requiredCharUuid = uuid("0000fff2-0000-1000-8000-00805f9b34fb")
        val proto = FingerprintProtocol(
            dataCharUuid = uuid("AA000000-0000-0000-0000-000000000004"),
            signatures = listOf(
                listOf(GattServiceSpec(uuid = serviceUuid, requiredCharacteristicUUIDs = setOf(requiredCharUuid)))
            )
        )
        manager.registerProtocol(proto)
        // Service exists but without the required characteristic
        val gatt = gattWithServices(service(serviceUuid, uuid("0000fff1-0000-1000-8000-00805f9b34fb")))
        assertNull(manager.selectByGattFingerprint(gatt.services))
    }

    @Test
    fun `returns null when excluded characteristic is present`() {
        val serviceUuid = uuid("00001800-0000-1000-8000-00805f9b34fb")
        val excludedCharUuid = uuid("00002aa6-0000-1000-8000-00805f9b34fb")
        val proto = FingerprintProtocol(
            dataCharUuid = uuid("AA000000-0000-0000-0000-000000000005"),
            signatures = listOf(
                listOf(GattServiceSpec(uuid = serviceUuid, excludedCharacteristicUUIDs = setOf(excludedCharUuid)))
            )
        )
        manager.registerProtocol(proto)
        // Service present with the excluded characteristic → should NOT match
        val gatt = gattWithServices(service(serviceUuid, excludedCharUuid))
        assertNull(manager.selectByGattFingerprint(gatt.services))
    }

    @Test
    fun `matches when excluded characteristic is absent`() {
        val nordicUartUuid = uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val genericAccessUuid = uuid("00001800-0000-1000-8000-00805f9b34fb")
        val excludedCharUuid = uuid("00002aa6-0000-1000-8000-00805f9b34fb")
        val proto = FingerprintProtocol(
            dataCharUuid = uuid("AA000000-0000-0000-0000-000000000006"),
            signatures = listOf(
                listOf(
                    GattServiceSpec(uuid = nordicUartUuid),
                    GattServiceSpec(uuid = genericAccessUuid, excludedCharacteristicUUIDs = setOf(excludedCharUuid))
                )
            )
        )
        manager.registerProtocol(proto)
        // 00001800 is present but without 00002aa6 → should match
        val gatt = gattWithServices(
            service(nordicUartUuid, uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e")),
            service(genericAccessUuid, uuid("00002a00-0000-1000-8000-00805f9b34fb"))
        )
        assertEquals(proto, manager.selectByGattFingerprint(gatt.services))
    }

    @Test
    fun `returns null when multiple protocols match (ambiguous)`() {
        val sharedServiceUuid = uuid("SHARED00-0000-0000-0000-000000000000")
        val alpha = FingerprintProtocol(
            dataCharUuid = uuid("AA000000-0000-0000-0000-000000000007"),
            signatures = listOf(listOf(GattServiceSpec(uuid = sharedServiceUuid)))
        )
        val beta = FingerprintProtocol(
            dataCharUuid = uuid("BB000000-0000-0000-0000-000000000007"),
            signatures = listOf(listOf(GattServiceSpec(uuid = sharedServiceUuid)))
        )
        manager.registerProtocol(alpha)
        manager.registerProtocol(beta)
        val gatt = gattWithServices(service(sharedServiceUuid))
        assertNull(manager.selectByGattFingerprint(gatt.services))
    }

    @Test
    fun `matches alternative signature when first signature does not match`() {
        val sig1ServiceUuid = uuid("AAAAAAAA-0000-0000-0000-000000000001")
        val sig2ServiceUuid = uuid("BBBBBBBB-0000-0000-0000-000000000001")
        val proto = FingerprintProtocol(
            dataCharUuid = uuid("AA000000-0000-0000-0000-000000000008"),
            signatures = listOf(
                listOf(GattServiceSpec(uuid = sig1ServiceUuid)),  // not present in device
                listOf(GattServiceSpec(uuid = sig2ServiceUuid))   // present in device
            )
        )
        manager.registerProtocol(proto)
        // Only sig2ServiceUuid present
        val gatt = gattWithServices(service(sig2ServiceUuid))
        assertEquals(proto, manager.selectByGattFingerprint(gatt.services))
    }

    // ──────────────────── InMotion V2 vs NinebotZ disambiguation ────────────────────

    @Test
    fun `InMotion V2 vs NinebotZ: matches InMotion V2 when 00002aa6 is present in 00001800`() {
        val nordicUart = uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val genericAccess = uuid("00001800-0000-1000-8000-00805f9b34fb")
        val centralAddressResolution = uuid("00002aa6-0000-1000-8000-00805f9b34fb")

        // InMotion V2: requires Nordic UART + 00002aa6 in 00001800
        val inMotionV2 = FingerprintProtocol(
            dataCharUuid = uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            signatures = listOf(
                listOf(
                    GattServiceSpec(uuid = nordicUart),
                    GattServiceSpec(uuid = genericAccess, requiredCharacteristicUUIDs = setOf(centralAddressResolution))
                )
            )
        )
        // NinebotZ: requires Nordic UART + 00002aa6 NOT in 00001800
        val ninebotZ = FingerprintProtocol(
            dataCharUuid = uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            signatures = listOf(
                listOf(
                    GattServiceSpec(uuid = nordicUart),
                    GattServiceSpec(uuid = genericAccess, excludedCharacteristicUUIDs = setOf(centralAddressResolution))
                )
            )
        )

        manager.registerProtocol(inMotionV2)
        manager.registerProtocol(ninebotZ)

        // Device has 00002aa6 → InMotion V2 should match, NinebotZ should not
        val gatt = gattWithServices(
            service(nordicUart, uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e")),
            service(genericAccess, uuid("00002a00-0000-1000-8000-00805f9b34fb"), centralAddressResolution)
        )

        assertEquals(inMotionV2, manager.selectByGattFingerprint(gatt.services))
    }

    @Test
    fun `InMotion V2 vs NinebotZ: matches NinebotZ when 00002aa6 is absent from 00001800`() {
        val nordicUart = uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val genericAccess = uuid("00001800-0000-1000-8000-00805f9b34fb")
        val centralAddressResolution = uuid("00002aa6-0000-1000-8000-00805f9b34fb")

        val inMotionV2 = FingerprintProtocol(
            dataCharUuid = uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            signatures = listOf(
                listOf(
                    GattServiceSpec(uuid = nordicUart),
                    GattServiceSpec(uuid = genericAccess, requiredCharacteristicUUIDs = setOf(centralAddressResolution))
                )
            )
        )
        val ninebotZ = FingerprintProtocol(
            dataCharUuid = uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            signatures = listOf(
                listOf(
                    GattServiceSpec(uuid = nordicUart),
                    GattServiceSpec(uuid = genericAccess, excludedCharacteristicUUIDs = setOf(centralAddressResolution))
                )
            )
        )

        manager.registerProtocol(inMotionV2)
        manager.registerProtocol(ninebotZ)

        // Device does NOT have 00002aa6 → NinebotZ should match, InMotion V2 should not
        val gatt = gattWithServices(
            service(nordicUart, uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e")),
            service(genericAccess, uuid("00002a00-0000-1000-8000-00805f9b34fb"))  // no 00002aa6
        )

        assertEquals(ninebotZ, manager.selectByGattFingerprint(gatt.services))
    }

    // ──────────────────── onServicesDiscovered integration test ────────────────────

    @Test
    fun `onServicesDiscovered selects protocol by GATT fingerprint with AUTO_GATT_FINGERPRINT reason`() {
        val exclusiveServiceUuid = uuid("CAFECAFE-0000-0000-0000-000000000001")
        val dataCharUuid = uuid("CAFECAFE-0000-0000-0000-000000000002")
        val proto = FingerprintProtocol(
            dataCharUuid = dataCharUuid,
            signatures = listOf(listOf(GattServiceSpec(uuid = exclusiveServiceUuid)))
        )
        manager.registerProtocol(proto)

        val cccdUuid = UUID.fromString(BLEConstants.CCCD_DESCRIPTOR)
        val dataChar = mock<BluetoothGattCharacteristic>().also {
            whenever(it.uuid).thenReturn(dataCharUuid)
            whenever(it.getDescriptor(cccdUuid)).thenReturn(mock<BluetoothGattDescriptor>())
        }
        val exclusiveSvc = mock<BluetoothGattService>().also {
            whenever(it.uuid).thenReturn(exclusiveServiceUuid)
            whenever(it.characteristics).thenReturn(listOf(dataChar))
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
    fun `onServicesDiscovered falls back to name-based scoring when no fingerprint matches`() {
        val dataCharUuid = uuid("0000ffe1-0000-1000-8000-00805f9b34fb")
        // Protocol with a signature that does NOT match the device
        val proto = FingerprintProtocol(
            dataCharUuid = dataCharUuid,
            signatures = listOf(listOf(GattServiceSpec(uuid = uuid("DEADBEEF-0000-0000-0000-000000000001"))))
        )
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

        // No fingerprint match and no name match → protocol should remain unselected
        assertNull(manager.currentProtocol)
    }

    // ──────────────────────────────── Helpers ────────────────────────────────

    private fun register(vararg protocols: EUCProtocol) {
        protocols.forEach(manager::registerProtocol)
    }

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

    private class RecordingConnectionCallback : ConnectionCallback() {
        val selectedProtocols = mutableListOf<ProtocolSelection>()
        override fun onProtocolSelected(selection: ProtocolSelection) {
            selectedProtocols += selection
        }
    }

    /** Protocol with no GATT signatures — acts as baseline for fallback tests. */
    private class NoSignatureProtocol(private val dataCharUuid: UUID) : EUCProtocol {
        override val manufacturer: String = "NoSignature"
        override val supportedModels: List<String> = emptyList()
        override val dataFlow: Flow<EUCData> = emptyFlow()
        override fun canHandle(device: EUCDevice): Boolean = false
        override fun decode(data: ByteArray): EUCData? = null
        override fun getDataCharacteristicUUID(): UUID = dataCharUuid
        override fun getServiceUUID(): UUID = uuid("00000000-0000-0000-0000-000000000000")
        override fun createCommand(commandType: CommandType, value: Any): ByteArray = byteArrayOf()
        override fun isDeviceReady(data: EUCData): Boolean = true
        override fun close() = Unit
        private fun uuid(v: String) = UUID.fromString(v)
    }

    /** Protocol whose GATT signatures can be configured per test. */
    private class FingerprintProtocol(
        private val dataCharUuid: UUID,
        private val signatures: List<GattSignature>
    ) : EUCProtocol {
        override val manufacturer: String = "Test"
        override val supportedModels: List<String> = emptyList()
        override val dataFlow: Flow<EUCData> = emptyFlow()
        override fun canHandle(device: EUCDevice): Boolean = false
        override fun decode(data: ByteArray): EUCData? = null
        override fun getDataCharacteristicUUID(): UUID = dataCharUuid
        override fun getServiceUUID(): UUID = uuid("00000000-0000-0000-0000-0000F0000000")
        override fun createCommand(commandType: CommandType, value: Any): ByteArray = byteArrayOf()
        override fun isDeviceReady(data: EUCData): Boolean = true
        override fun close() = Unit
        override fun getGattSignatures(): List<GattSignature> = signatures
        private fun uuid(v: String) = UUID.fromString(v)
    }
}

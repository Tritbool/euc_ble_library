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
import io.github.tritbool.euc.ble.protocols.ExtremeBullProtocol
import io.github.tritbool.euc.ble.protocols.LeaperkimProtocol
import io.github.tritbool.euc.ble.protocols.NosfetProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Tests for device-name based protocol selection in [BLEManager].
 *
 * Covers:
 * - [ExtremeBullProtocol.matchesDeviceName] on the real ExtremeBull protocol.
 * - [BLEManager.selectByDeviceName] unit tests with simple stubs.
 * - [BLEManager.selectSubclassByDeviceName] unit tests with an inheritance hierarchy.
 * - [BLEManager.onServicesDiscovered] integration tests:
 *   - GATT fingerprint match refined by device name (GotwayProtocol → ExtremeBullSubStub).
 *   - Device-name-only selection when no fingerprint matches (Nosfet).
 */
class BLEManagerDeviceNameSelectionTest {

    private lateinit var manager: BLEManager
    private lateinit var callback: RecordingConnectionCallback
    private val protocolsToClose = mutableListOf<EUCProtocol>()

    @BeforeEach
    fun setUp() {
        manager = BLEManager(mock<Context>(), NoOpLogger())
        callback = RecordingConnectionCallback()
        manager.setConnectionCallback(callback)
    }

    @AfterEach
    fun tearDown() {
        protocolsToClose.forEach { it.close() }
        protocolsToClose.clear()
    }

    // ──────────────── ExtremeBullProtocol.matchesDeviceName ────────────────

    @Test
    fun `ExtremeBullProtocol matchesDeviceName recognizes extreme and bull keywords`() {
        val proto = ExtremeBullProtocol().also(protocolsToClose::add)
        assertTrue(proto.matchesDeviceName("Extreme Bull Master"))
        assertTrue(proto.matchesDeviceName("EXTREME BULL"))
        assertTrue(proto.matchesDeviceName("extreme bull apex"))
        assertTrue(proto.matchesDeviceName("extreme rider"))
        assertTrue(proto.matchesDeviceName("Raging Bull"))
    }

    @Test
    fun `ExtremeBullProtocol matchesDeviceName returns false for unrelated names`() {
        val proto = ExtremeBullProtocol().also(protocolsToClose::add)
        assertFalse(proto.matchesDeviceName("Gotway King"))
        assertFalse(proto.matchesDeviceName("Leaperkim Sherman"))
        assertFalse(proto.matchesDeviceName("KingSong S22"))
        assertFalse(proto.matchesDeviceName("Unknown EUC"))
        assertFalse(proto.matchesDeviceName(""))
    }

    // ──────────────── selectByDeviceName unit tests ────────────────

    @Test
    fun `selectByDeviceName returns single matching protocol`() {
        val base = BaseStub()
        val sub = NosfetSubStub()
        manager.registerProtocol(base)
        manager.registerProtocol(sub)
        assertEquals(sub, manager.selectByDeviceName("Nosfet Apex"))
    }

    @Test
    fun `selectByDeviceName returns null when no protocol matches`() {
        val base = BaseStub()
        val sub = NosfetSubStub()
        manager.registerProtocol(base)
        manager.registerProtocol(sub)
        assertNull(manager.selectByDeviceName("Veteran Sherman"))
        assertNull(manager.selectByDeviceName("Unknown EUC"))
    }

    @Test
    fun `selectByDeviceName returns null when multiple protocols match (ambiguous)`() {
        val alpha = MatchAllStub("Alpha")
        val beta = MatchAllStub("Beta")
        manager.registerProtocol(alpha)
        manager.registerProtocol(beta)
        assertNull(manager.selectByDeviceName("anything"))
    }

    // ──────────────── selectSubclassByDeviceName unit tests ────────────────

    @Test
    fun `selectSubclassByDeviceName returns subclass when device name matches`() {
        val base = BaseStub()
        val sub = ExtremeBullSubStub()
        manager.registerProtocol(base)
        manager.registerProtocol(sub)
        assertEquals(sub, manager.selectSubclassByDeviceName(base, "Extreme Bull Monster"))
    }

    @Test
    fun `selectSubclassByDeviceName returns null when device name does not match any subclass`() {
        val base = BaseStub()
        val sub = ExtremeBullSubStub()
        manager.registerProtocol(base)
        manager.registerProtocol(sub)
        assertNull(manager.selectSubclassByDeviceName(base, "KingSong S22"))
    }

    @Test
    fun `selectSubclassByDeviceName returns null when matching protocol is not a subclass of base`() {
        val base = BaseStub()
        val unrelated = MatchAllStub("Unrelated")
        manager.registerProtocol(base)
        manager.registerProtocol(unrelated)
        // unrelated matches every name but is not a subclass of base
        assertNull(manager.selectSubclassByDeviceName(base, "anything"))
    }

    @Test
    fun `selectSubclassByDeviceName does not match base protocol itself`() {
        val base = BaseStub()
        manager.registerProtocol(base)
        // same class as baseProtocol is excluded by the strict-subclass check
        assertNull(manager.selectSubclassByDeviceName(base, "extreme bull"))
    }

    // ── onServicesDiscovered: GATT fingerprint + device name → subclass override ──

    @Test
    fun `onServicesDiscovered selects ExtremeBull subclass when Gotway fingerprint and device name match`() {
        // GotwayProtocol (inner stub, simpleName matches DB key "GotwayProtocol")
        val gotwayProto = GotwayProtocol()
        val extremeBullProto = ExtremeBullSubStub2()
        manager.registerProtocol(gotwayProto)
        manager.registerProtocol(extremeBullProto)

        // GATT: Gotway OTA fingerprint service + shared data/write characteristic
        val gatt = buildGatt(
            listOf(
                service(uuid("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0")),
                serviceWithChar(uuid("0000ffe0-0000-1000-8000-00805f9b34fb"), dataCharUuid)
            )
        )
        attachSession(device("Extreme Bull Master", 0), gatt)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNotNull(manager.currentProtocol)
        assertEquals(extremeBullProto, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.AUTO_DEVICE_NAME, callback.selectedProtocols.single().reason)
    }

    @Test
    fun `onServicesDiscovered falls back to Gotway when fingerprint matches but device name is unrelated`() {
        val gotwayProto = GotwayProtocol()
        val extremeBullProto = ExtremeBullSubStub2()
        manager.registerProtocol(gotwayProto)
        manager.registerProtocol(extremeBullProto)

        val gatt = buildGatt(
            listOf(
                service(uuid("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0")),
                serviceWithChar(uuid("0000ffe0-0000-1000-8000-00805f9b34fb"), dataCharUuid)
            )
        )
        attachSession(device("Gotway King Unknown", 0), gatt)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNotNull(manager.currentProtocol)
        assertEquals(gotwayProto, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.AUTO_GATT_FINGERPRINT, callback.selectedProtocols.single().reason)
    }

    // ── onServicesDiscovered: device-name-only path (no fingerprint match) ──

    @Test
    fun `onServicesDiscovered selects Nosfet by device name when no fingerprint matches`() {
        val leaperkim = LeaperkimProtocol().also(protocolsToClose::add)
        val nosfet = NosfetProtocol().also(protocolsToClose::add)
        manager.registerProtocol(leaperkim)
        manager.registerProtocol(nosfet)

        // GATT has no Leaperkim/Nosfet fingerprint — only the shared characteristic
        val leaperkimCharUuid = uuid("0000ffe1-0000-1000-8000-00805f9b34fb")
        val gatt = buildGatt(
            listOf(serviceWithChar(uuid("0000ffe0-0000-1000-8000-00805f9b34fb"), leaperkimCharUuid))
        )
        attachSession(device("Nosfet Apex Pro", 0), gatt)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNotNull(manager.currentProtocol)
        assertEquals(nosfet, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.AUTO_DEVICE_NAME, callback.selectedProtocols.single().reason)
    }

    @Test
    fun `onServicesDiscovered requires manual selection when neither fingerprint nor device name matches`() {
        val leaperkim = LeaperkimProtocol().also(protocolsToClose::add)
        val nosfet = NosfetProtocol().also(protocolsToClose::add)
        manager.registerProtocol(leaperkim)
        manager.registerProtocol(nosfet)

        val leaperkimCharUuid = uuid("0000ffe1-0000-1000-8000-00805f9b34fb")
        val gatt = buildGatt(
            listOf(serviceWithChar(uuid("0000ffe0-0000-1000-8000-00805f9b34fb"), leaperkimCharUuid))
        )
        attachSession(device("Veteran Sherman", 0), gatt)
        manager.setProtocolSelectionMode(ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNull(manager.currentProtocol)
        assertTrue(callback.selectedProtocols.isEmpty())
        assertTrue(callback.requiredSelections.isNotEmpty())
    }

    // ─────────────────────────── Helpers ───────────────────────────────

    private val dataCharUuid: UUID = uuid("0000ffe1-0000-1000-8000-00805f9b34fb")

    private fun uuid(v: String): UUID = UUID.fromString(v)

    private fun device(name: String, manufacturerId: Int) = EUCDevice(
        name = name,
        address = "AA:BB:CC:DD:EE:FF",
        manufacturerId = manufacturerId,
        manufacturerData = null,
        rssi = -50
    )

    private fun service(serviceUuid: UUID): BluetoothGattService {
        return mock<BluetoothGattService>().also { svc ->
            whenever(svc.uuid).thenReturn(serviceUuid)
            whenever(svc.characteristics).thenReturn(emptyList())
        }
    }

    private fun serviceWithChar(serviceUuid: UUID, charUuid: UUID): BluetoothGattService {
        val cccdUuid = UUID.fromString(BLEConstants.CCCD_DESCRIPTOR)
        val char = mock<BluetoothGattCharacteristic>().also { c ->
            whenever(c.uuid).thenReturn(charUuid)
            whenever(c.getDescriptor(cccdUuid)).thenReturn(mock<BluetoothGattDescriptor>())
        }
        return mock<BluetoothGattService>().also { svc ->
            whenever(svc.uuid).thenReturn(serviceUuid)
            whenever(svc.characteristics).thenReturn(listOf(char))
            whenever(svc.getCharacteristic(charUuid)).thenReturn(char)
        }
    }

    private fun buildGatt(services: List<BluetoothGattService>): BluetoothGatt {
        return mock<BluetoothGatt>().also { gatt ->
            whenever(gatt.services).thenReturn(services)
        }
    }

    private fun attachSession(device: EUCDevice, gatt: BluetoothGatt) {
        setPrivateField("currentDevice", device)
        setPrivateField("bluetoothGatt", gatt)
    }

    private fun setPrivateField(name: String, value: Any?) {
        val field = BLEManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(manager, value)
    }

    // ─────────────────────── Recording callback ───────────────────────────

    private class RecordingConnectionCallback : ConnectionCallback() {
        val selectedProtocols = mutableListOf<ProtocolSelection>()
        val requiredSelections = mutableListOf<List<EUCProtocol>>()

        override fun onProtocolSelected(selection: ProtocolSelection) {
            selectedProtocols += selection
        }

        override fun onProtocolSelectionRequired(protocols: List<EUCProtocol>) {
            requiredSelections += protocols
        }
    }

    // ─────────────────── Generic stubs for unit tests ────────────────────

    /** Base protocol — never matches by device name (default impl). */
    private open class BaseStub : EUCProtocol {
        override val manufacturer = "Base"
        override val dataFlow: Flow<EUCData> = emptyFlow()
        override fun decode(data: ByteArray) = null
        override fun getDataCharacteristicUUID() = UUID.fromString("AA000000-0000-0000-0000-000000000001")
        override fun getServiceUUID() = UUID.fromString("AA000000-0000-0000-0000-000000000000")
        override fun createCommand(commandType: CommandType, value: Any) = byteArrayOf()
        override fun isDeviceReady(data: EUCData) = true
        override fun close() = Unit
    }

    /** Strict subclass of [BaseStub] that matches "extreme" / "bull" device names. */
    private class ExtremeBullSubStub : BaseStub() {
        override val manufacturer = "ExtremeBull"
        override fun matchesDeviceName(deviceName: String): Boolean {
            val lower = deviceName.lowercase()
            return lower.contains("extreme") || lower.contains("bull")
        }
    }

    /** Stub that matches Nosfet-related names as a subclass of [BaseStub]. */
    private class NosfetSubStub : BaseStub() {
        override val manufacturer = "Nosfet"
        override fun matchesDeviceName(deviceName: String): Boolean {
            val lower = deviceName.lowercase()
            return lower.contains("nosfet") || lower.contains("apex") ||
                   lower.contains("aero") || lower.contains("aeon")
        }
    }

    /** Protocol that matches every device name — used to test ambiguous-match behaviour. */
    private class MatchAllStub(override val manufacturer: String) : EUCProtocol {
        override val dataFlow: Flow<EUCData> = emptyFlow()
        override fun decode(data: ByteArray) = null
        override fun getDataCharacteristicUUID() = UUID.fromString("BB000000-0000-0000-0000-000000000001")
        override fun getServiceUUID() = UUID.fromString("BB000000-0000-0000-0000-000000000000")
        override fun createCommand(commandType: CommandType, value: Any) = byteArrayOf()
        override fun isDeviceReady(data: EUCData) = true
        override fun matchesDeviceName(deviceName: String) = true
        override fun close() = Unit
    }

    // ──── Integration stubs whose simpleName matches EucFingerprintDatabase keys ────

    /**
     * Stub whose simple class name "GotwayProtocol" matches the real [EucFingerprintDatabase]
     * entry, enabling integration tests to use the real Gotway GATT fingerprint without
     * importing the full production GotwayProtocol.
     */
    private open class GotwayProtocol(
        private val charUuid: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    ) : EUCProtocol {
        override val manufacturer = "Gotway"
        override val dataFlow: Flow<EUCData> = emptyFlow()
        override fun decode(data: ByteArray) = null
        override fun getDataCharacteristicUUID() = charUuid
        override fun getServiceUUID() = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        override fun createCommand(commandType: CommandType, value: Any) = byteArrayOf()
        override fun isDeviceReady(data: EUCData) = true
        override fun close() = Unit
    }

    /**
     * Strict subclass of the inner [GotwayProtocol] stub that matches "extreme"/"bull" device
     * names. Used in [onServicesDiscovered] integration tests to verify that
     * [BLEManager.selectSubclassByDeviceName] is invoked and produces the more-specific protocol.
     */
    private class ExtremeBullSubStub2(
        charUuid: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    ) : GotwayProtocol(charUuid) {
        override val manufacturer = "ExtremeBull"
        override fun matchesDeviceName(deviceName: String): Boolean {
            val lower = deviceName.lowercase()
            return lower.contains("extreme") || lower.contains("bull")
        }
    }
}

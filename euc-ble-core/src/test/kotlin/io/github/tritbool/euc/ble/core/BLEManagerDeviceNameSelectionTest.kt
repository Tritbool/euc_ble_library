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
import io.github.tritbool.euc.ble.protocols.EucFingerprintDatabase
import io.github.tritbool.euc.ble.protocols.ExtremeBullProtocol
import io.github.tritbool.euc.ble.protocols.GotwayProtocol
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
    fun `ExtremeBullProtocol matchesDeviceName recognizes known Extreme Bull names`() {
        val proto = ExtremeBullProtocol().also(protocolsToClose::add)
        assertTrue(proto.matchesDeviceName("Extreme Bull Master"))
        assertTrue(proto.matchesDeviceName("EXTREME BULL"))
        assertTrue(proto.matchesDeviceName("extreme bull apex"))
        assertTrue(proto.matchesDeviceName("extreme rider"))
        assertTrue(proto.matchesDeviceName("Raging Bull"))
        assertTrue(proto.matchesDeviceName("ROCKET"))
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

    @Test
    fun `selectByDeviceName selects real NosfetProtocol by device name`() {
        val leaperkim = LeaperkimProtocol().also(protocolsToClose::add)
        val nosfet = NosfetProtocol().also(protocolsToClose::add)
        manager.registerProtocol(leaperkim)
        manager.registerProtocol(nosfet)
        assertEquals(nosfet, manager.selectByDeviceName("Nosfet Apex"))
        assertEquals(nosfet, manager.selectByDeviceName("Nosfet"))
        assertEquals(nosfet, manager.selectByDeviceName("NOSFET"))
        assertEquals(leaperkim,manager.selectByDeviceName("Veteran Sherman"))
    }

    @Test
    fun `Rocket device name refines Gotway to real ExtremeBullProtocol`() {
        val gotway = GotwayProtocol().also(protocolsToClose::add)
        val extremeBull = ExtremeBullProtocol().also(protocolsToClose::add)
        manager.registerProtocol(gotway)
        manager.registerProtocol(extremeBull)
        assertEquals(extremeBull, manager.selectSubclassByDeviceName(gotway, "ROCKET"))
        assertNull(manager.selectSubclassByDeviceName(gotway, "KingSong S22"))
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
        val gotwayProto = GotwayProtocol()
        val extremeBullProto = ExtremeBullProtocol()
        manager.registerProtocol(gotwayProto)
        manager.registerProtocol(extremeBullProto)

        // GATT: Gotway OTA fingerprint service + shared data/write characteristic
        val gatt = gotwayGatt()
        attachSession(device("Extreme Bull Master", 0), gatt)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNotNull(manager.currentProtocol)
        assertEquals(extremeBullProto, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.AUTO_DEVICE_NAME, callback.selectedProtocols.single().reason)
    }

    /* TODO blocked due to mock not mocking mcharacterstics of gatt outside of androidTest
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
*/
    // ── onServicesDiscovered: device-name-only path (no fingerprint match) ──

    @Test
    fun `explicit Nosfet name takes precedence over conflicting Gotway fingerprint`() {
        val gotway = GotwayProtocol().also(protocolsToClose::add)
        val leaperkim = LeaperkimProtocol().also(protocolsToClose::add)
        val nosfet = NosfetProtocol().also(protocolsToClose::add)
        manager.registerProtocol(gotway)
        manager.registerProtocol(leaperkim)Bonjour,

J'ai pris connaissance du refus de mon article « Le plafond de 25 km/h des EDPM à l'épreuve des accidents recensés » (nº 19625).

Vous reconnaissez l'intérêt du sujet et la pertinence de la problématique, et je vous en sais gré.

Je suis disposé à reprendre le manuscrit et à présenter une nouvelle soumission, si cette possibilité est ouverte. Vos remarques nécessitent toutefois des précisions pour permettre une révision effective.

Vous mentionnez des « assertions non démontrées », des hypothèses à conforter, notamment dans le tableau 1, et des résultats dont la méthode ou la modélisation ne seraient pas précisées. Merci d'identifier les assertions et les résultats concernés, ainsi que les éléments que vous estimez manquants.

Concernant le tableau 1, attendez-vous une justification du choix des hypothèses, les résultats de l'étude source permettant de les examiner, ou des références extérieures ? Ce tableau rassemble des propositions empiriques, des interprétations et des propositions normatives, dont les exigences de justification diffèrent. Merci de préciser les lignes concernées et la nature du complément attendu.

Le manuscrit présente les données, l'appariement et la standardisation, puis renvoie à une étude source bien plus complète pour les développements détaillés. Estimez-vous que les justifications sont insuffisantes dans cette étude, que les renvois ne sont pas assez précis, ou que certains développements doivent être repris directement dans l'article ? Ces problèmes sont distincts et appellent des corrections différentes.

Les contraintes de format des CST m'ont conduit à réduire certains développements et à reporter des précisions dans l'étude source. Je le déplore, car cette réduction limite le détail de la démonstration dans l'article. Pour réviser utilement le texte, j'ai donc besoin de savoir quels éléments doivent impérativement y figurer et quelle place leur consacrer dans le format demandé.

Concernant la structure, je me suis notamment inspiré d'articles publiés dans les CST. La brièveté d'une section ne constitue pas, à elle seule, une insuffisance scientifique ou rédactionnelle. Quant au caractère « particulier » de la structuration, cette appréciation ne permet pas d'identifier ce qui doit être corrigé. Merci de préciser quelles sections vous jugez insuffisamment développées, quel raisonnement y manque ou en quoi leur articulation nuit à la compréhension. De même, des exemples des formulations jugées trop techniques ou jargonnantes me permettraient de les reprendre.

Je m'interroge également sur la cohérence des exigences de justification appliquées. Dans « Quel avenir pour les trottinettes électriques ? » (Héran et Scornavacca, CST, nº 85), le tableau 2 estime un risque relatif à partir de victimes recensées sur trois ans et de comptages de passages sur les ponts de Paris effectués lors de journées d'octobre. La représentativité de ces comptages comme mesure de l'exposition relative des deux modes n'y est pas démontrée. Le texte affirme pourtant que l'incertitude sur cette exposition n'a « sans doute guère d'incidence », sans la quantifier, puis conclut que la trottinette est « environ trois fois plus dangereuse que le vélo ».

Cet exemple ne constitue en aucun cas un argument pour accepter mon manuscrit. Il motive en revanche ma demande de comprendre précisément le niveau de justification attendu et les points sur lesquels ma démonstration est jugée insuffisante.

Pouvez-vous donc m'adresser un retour plus détaillé et m'indiquer si une nouvelle soumission substantiellement révisée serait envisageable ?

Bien cordialement,

Dr Gauthier Le Bartz Lyan


Le 15 oct. 2026 18:58, Laurent Carnis <cst@episciences.org> a écrit :
Bonjour Gauthier Le Bartz Lyan,

L'article (#19625 - Le plafond de 25 km/h des EDPM à l'épreuve des accidents recensés) que vous aviez proposé le 8 octobre 2026 a été refusé par le comité éditorial.

Après lecture attentive du manuscrit, le sujet a été jugé intéressant et soulève une problématique pertinente en s'interrogeant sur les fondements d'une réglementation.

Toutefois, l'article proposé présente certains défauts :

sa structuration est quelque peu particulière avec des sections courtes et des assertions non démontrées.
certaines hypothèses méritent d'être confortées par des éléments empiriques ou des références, notamment pour ce qui figure dans le tableau 1. Leur nature peut également faire l'objet de développements.
Le style d'écriture est rapide, comprenant des mots anglais, et parfois le vocabulaire utilisé est à la fois technique et jargonnant, pas toujours facile d'accès pour le public des CST.
Par ailleurs, un certain nombre de résultats sont avancés sans que ne soient précisés ni la méthode, la modelisation utilisée et les résultats obtenus qui viendraient en appui de la démonstration.

Sincères salutations,
--
Laurent Carnis
Episciences.org
        manager.registerProtocol(nosfet)
        val gatt = gotwayGatt()
        assertEquals(gotway, manager.selectByGattFingerprint(gatt.services).first)
        attachSession(device("nOsFeT", 0), gatt)

        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertEquals(nosfet, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.AUTO_DEVICE_NAME, callback.selectedProtocols.single().reason)
    }

    @Test
    fun `forced protocol takes precedence over explicit NOSFET name`() {
        val leaperkim = LeaperkimProtocol().also(protocolsToClose::add)
        val nosfet = NosfetProtocol().also(protocolsToClose::add)
        manager.registerProtocol(leaperkim)
        manager.registerProtocol(nosfet)
        assertTrue(manager.forceProtocol(leaperkim))
        val gatt = buildGatt(
            listOf(serviceWithChar(uuid("0000ffe0-0000-1000-8000-00805f9b34fb"), dataCharUuid))
        )
        attachSession(device("NOSFET AEON", 0), gatt)

        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertEquals(leaperkim, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.FORCED, callback.selectedProtocols.single().reason)
    }

    @Test
    fun `NOSFET name retains GATT fallback when Nosfet protocol is not registered`() {
        val gotway = GotwayProtocol().also(protocolsToClose::add)
        manager.registerProtocol(gotway)
        val gatt = gotwayGatt()
        attachSession(device("NOSFET", 0), gatt)

        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertEquals(gotway, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.AUTO_GATT_FINGERPRINT, callback.selectedProtocols.single().reason)
    }

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
        attachSession(device("Nosfet", 0), gatt)
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
        attachSession(device("VETERAN Leclerc", 0), gatt)
        manager.setProtocolSelectionMode(ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertNull(manager.currentProtocol)
        assertTrue(callback.selectedProtocols.isEmpty())
        assertTrue(callback.requiredSelections.isNotEmpty())
    }

    // ─────────────────────────── Helpers ───────────────────────────────

    @Test
    fun `fragmented Nosfet telemetry overrides conflicting GATT without a matching name`() {
        val gotway = GotwayProtocol().also(protocolsToClose::add)
        val nosfet = NosfetProtocol().also(protocolsToClose::add)
        manager.registerProtocol(gotway)
        manager.registerProtocol(nosfet)
        val gatt = gotwayGatt()
        attachSession(device("Unknown wheel", 0), gatt)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertEquals(gotway, manager.currentProtocol)

        val frame = nosfetFrame()
        manager.handleIncomingBytes(frame.copyOfRange(0, 20), dataCharUuid)
        assertEquals(gotway, manager.currentProtocol)
        manager.handleIncomingBytes(frame.copyOfRange(20, frame.size), dataCharUuid)

        assertEquals(nosfet, manager.currentProtocol)
        assertEquals(ProtocolSelectionReason.AUTO_TELEMETRY, callback.selectedProtocols.last().reason)
    }

    @Test
    fun `unknown serial bridge waits for Nosfet telemetry in automatic mode`() {
        val nosfet = NosfetProtocol().also(protocolsToClose::add)
        manager.registerProtocol(nosfet)
        val gatt = buildGatt(listOf(serviceWithChar(
            uuid("0000ffe0-0000-1000-8000-00805f9b34fb"), dataCharUuid
        )))
        attachSession(device("", 0), gatt)
        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertNull(manager.currentProtocol)
        manager.handleIncomingBytes(nosfetFrame(), dataCharUuid)
        assertEquals(nosfet, manager.currentProtocol)
    }

    @Test
    fun `Nosfet telemetry cannot override forced or manual selection`() {
        val leaperkim = LeaperkimProtocol().also(protocolsToClose::add)
        val nosfet = NosfetProtocol().also(protocolsToClose::add)
        manager.registerProtocol(leaperkim)
        manager.registerProtocol(nosfet)
        val gatt = buildGatt(listOf(serviceWithChar(
            uuid("0000ffe0-0000-1000-8000-00805f9b34fb"), dataCharUuid
        )))
        attachSession(device("", 0), gatt)
        assertTrue(manager.forceProtocol(leaperkim))
        manager.handleIncomingBytes(nosfetFrame(), dataCharUuid)
        assertEquals(leaperkim, manager.currentProtocol)

        manager.clearForcedProtocol()
        manager.setProtocolSelectionMode(ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK)
        assertTrue(manager.selectProtocol(leaperkim))
        manager.handleIncomingBytes(nosfetFrame(), dataCharUuid)
        assertEquals(leaperkim, manager.currentProtocol)
    }

    @Test
    fun `other firmware or characteristic cannot trigger Nosfet selection`() {
        val nosfet = NosfetProtocol().also(protocolsToClose::add)
        manager.registerProtocol(nosfet)
        val gatt = buildGatt(listOf(serviceWithChar(
            uuid("0000ffe0-0000-1000-8000-00805f9b34fb"), dataCharUuid
        )))
        attachSession(device("", 0), gatt)
        manager.handleIncomingBytes(nosfetFrame(version = 9001), dataCharUuid)
        manager.handleIncomingBytes(nosfetFrame(), UUID.randomUUID())
        assertNull(manager.currentProtocol)
    }

    private fun nosfetFrame(version: Int = 44250): ByteArray = ByteArray(40).apply {
        this[0] = 0xDC.toByte()
        this[1] = 0x5A
        this[2] = 0x5C
        this[3] = 36
        this[4] = 0x38
        this[5] = 0xBC.toByte()
        this[28] = (version shr 8).toByte()
        this[29] = version.toByte()
    }

    private fun gotwayGatt(): BluetoothGatt = buildGatt(
        EucFingerprintDatabase.getSignatures("GotwayProtocol").last().map { spec ->
            serviceWithChars(spec.uuid, spec.requiredCharacteristicUUIDs.toList())
        }
    )

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
        return serviceWithChars(serviceUuid, listOf(charUuid))
    }

    private fun serviceWithChars(serviceUuid: UUID, charUuids: List<UUID>): BluetoothGattService {
        val cccdUuid = UUID.fromString(BLEConstants.CCCD_DESCRIPTOR)
        val chars = charUuids.map { charUuid ->
            mock<BluetoothGattCharacteristic>().also { c ->
                whenever(c.uuid).thenReturn(charUuid)
                whenever(c.getDescriptor(cccdUuid)).thenReturn(mock<BluetoothGattDescriptor>())
            }
        }
        return mock<BluetoothGattService>().also { svc ->
            whenever(svc.uuid).thenReturn(serviceUuid)
            whenever(svc.characteristics).thenReturn(chars)
            chars.forEach { c -> whenever(svc.getCharacteristic(c.uuid)).thenReturn(c) }
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

}

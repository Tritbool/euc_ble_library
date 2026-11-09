package io.github.tritbool.euc.ble.replay

import io.github.tritbool.euc.ble.core.*
import io.github.tritbool.euc.ble.models.PasswordOperationStatus
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.protocols.*
import io.github.tritbool.euc.ble.session.PollingEngine
import io.github.tritbool.euc.ble.session.ProtocolSelector
import kotlin.test.*

class PortableReplayTest {
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 100_000L
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }
    private fun engines(): List<SyncEucProtocol> = listOf(
        GotwaySyncProtocol(clock), ExtremeBullSyncProtocol(clock),
        LeaperkimSyncProtocol(clock), NosfetSyncProtocol(clock),
        KingsongSyncProtocol(clock, EucRandom { 0 }),
        NinebotSyncProtocol(clock), NinebotZSyncProtocol(clock), InMotionSyncProtocol(clock)
    )

    @Test
    fun goldenCommandsMatchOnEveryTarget() {
        val engines = engines().associateBy { it.protocolId }
        assertEquals(engines.keys, commandFixtures.map { it.substringBefore('\t') }.toSet())
        for (row in commandFixtures) {
            val columns = row.split('\t')
            assertEquals(5, columns.size, row)
            val (protocol, command, kind, value, expected) = columns
            val input = when (kind) {
                "unit" -> Unit
                "hex" -> ByteUtils.hexToBytes(value)
                else -> error("Unknown fixture value kind: $kind")
            }
            val engine = engines.getValue(protocol)
            assertContentEquals(ByteUtils.hexToBytes(expected),
                engine.createCommand(CommandType.valueOf(command), input), row)
            engine.resetSession()
            assertNull(engine.pollOutput())
        }
    }

    @Test
    fun acceptanceStartsPasswordTimerAndResetInvalidatesIt() {
        val engine = KingsongSyncProtocol(clock, EucRandom { 0 })
        val payload = engine.createCommand(CommandType.SET_PASSWORD, "0012")
        assertNull(engine.pollOutput())
        engine.onCommandSubmitted(payload)
        val timer = assertIs<ProtocolOutput.StartTimer>(engine.pollOutput())
        assertEquals(3000L, timer.delayMs)
        engine.onTimer(timer.id)
        val event = engine.drainOutputs().filterIsInstance<ProtocolOutput.SecurityEvent>().single().event
        assertEquals(PasswordOperationStatus.TIMEOUT_UNCONFIRMED,
            assertIs<WheelSecurityEvent.PasswordOperationResult>(event).status)
        engine.resetSession()
        engine.onTimer(timer.id)
        assertNull(engine.pollOutput())
    }

    @Test
    fun inMotionV1EncodingEscapesCanIdAndPreservesChecksum() {
        val engine = InMotionSyncProtocol(clock)
        engine.setDialect(1)
        assertContentEquals(
            ByteUtils.hexToBytes("AAAA1601A5550FB200000011000000080500004B5555"),
            engine.createCommand(CommandType.BEEP, Unit)
        )
    }

    @Test
    fun fragmentedInMotionAuthenticationProducesIdenticalWrite() {
        val identity = ByteUtils.hexToBytes(
            "aaaa1662210286010001413134323139313136303031383341340000000000" +
                "020c010121320008010100090222260004071900080223100004010a00" +
                "0201240e00000001000101250e000000010001012f06000601010000" +
                "0045bd57a9210100220000230000ea"
        )
        for (split in 1 until identity.size) {
            val engine = InMotionSyncProtocol(clock)
            engine.onNotification(identity.copyOfRange(0, split))
            assertNull(engine.pollOutput(), "split=$split")
            engine.onNotification(identity.copyOfRange(split, identity.size))
            val write = engine.drainOutputs().filterIsInstance<ProtocolOutput.Write>().single()
            assertContentEquals(ByteUtils.hexToBytes("aaaa13040200000217"), write.payload)
            engine.resetSession()
            assertNull(engine.pollOutput())
        }
    }

    @Test
    fun selectionUsesExplicitIdentityWithoutRuntimeReflection() {
        val protocols = engines()
        val selector = ProtocolSelector(protocols, NoOpLogger())
        val base = protocols.single { it.protocolId == "GotwayProtocol" }
        assertEquals("ExtremeBullProtocol", selector.subclass(base, "Extreme Bull")?.protocolId)
        assertNull(selector.subclass(base, "Gotway"))
        val service = EucUuid.parse(BLEConstants.GOTWAY_SERVICE_UUID)
        val selected = selector.byFingerprint(listOf(ProtocolSelector.Service(service, emptySet()))) {
            if (it == base.protocolId) listOf(listOf(GattServiceSpec(service))) else emptyList()
        }
        assertSame(base, selected.first)
    }

    @Test
    fun pollingUsesSuppliedTimeAndIgnoresUnacceptedResponses() {
        val engine = PollingEngine("Replay")
        val plan = ProtocolPollingPlan(true,
            startupQueries = listOf(ProtocolQuerySpec("serial", CommandType.REQUEST_SERIAL)))
        engine.start(plan, 0)
        engine.tick(plan, 0)
        val query = assertIs<PollingEngine.Output.Query>(engine.pollOutput())
        engine.onResponse(10_000) { true }
        assertNull(engine.pollOutput())
        engine.onAccepted(query.token, 10_000)
        assertEquals(QueryTracePhase.SENT,
            assertIs<PollingEngine.Output.Trace>(engine.pollOutput()).event.phase)
        engine.onResponse(10_123) { true }
        assertEquals(123L, assertIs<PollingEngine.Output.Trace>(engine.pollOutput()).event.latencyMs)
    }

    @Test
    fun portableUtilitiesPreserveSignedValuesAndCrc() {
        assertEquals(0xCBF43926L, EucCrc32.calculate("123456789".encodeToByteArray()))
        for (value in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            assertEquals(value, ByteUtils.getSignedIntLE(ByteUtils.intToBytesLE(value), 0))
            assertEquals(value, ByteUtils.getSignedIntBE(ByteUtils.intToBytesBE(value), 0))
        }
        assertEquals(EucUuid.parse("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            EucUuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        assertFailsWith<IllegalArgumentException> { EucUuid.parse("not-a-uuid") }
    }
}

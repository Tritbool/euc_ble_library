package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.QueryTracePhase
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucDateTime
import io.github.tritbool.euc.ble.core.EucUuid
import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.core.ProtocolSelectionMode
import io.github.tritbool.euc.ble.core.ProtocolSelectionReason
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.ExtremeBullSyncProtocol
import io.github.tritbool.euc.ble.protocols.GattServiceSpec
import io.github.tritbool.euc.ble.protocols.GotwaySyncProtocol
import io.github.tritbool.euc.ble.protocols.KingsongSyncProtocol
import io.github.tritbool.euc.ble.protocols.LeaperkimSyncProtocol
import io.github.tritbool.euc.ble.protocols.NinebotSyncProtocol
import io.github.tritbool.euc.ble.protocols.NinebotZSyncProtocol
import io.github.tritbool.euc.ble.protocols.InMotionSyncProtocol
import io.github.tritbool.euc.ble.protocols.NosfetSyncProtocol
import io.github.tritbool.euc.ble.protocols.ProtocolPollingPlan
import io.github.tritbool.euc.ble.protocols.ProtocolQuerySpec
import io.github.tritbool.euc.ble.protocols.SyncEucProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class SessionPolicyTest {
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 0L
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }

    @Test
    fun pollingDeadlineBeginsOnTransportAcceptance() {
        val engine = PollingEngine("Replay")
        val plan = ProtocolPollingPlan(
            enabled = true,
            startupQueries = listOf(
                ProtocolQuerySpec("serial", CommandType.REQUEST_SERIAL, responseTimeoutMs = 200)
            )
        )
        engine.start(plan, 0)
        engine.tick(plan, 0)
        val query = assertIs<PollingEngine.Output.Query>(engine.pollOutput())

        engine.onResponse(500) { true }
        assertNull(engine.pollOutput())
        engine.onAccepted(query.token, 500)
        assertEquals(QueryTracePhase.SENT,
            assertIs<PollingEngine.Output.Trace>(engine.pollOutput()).event.phase)

        engine.tick(plan, 699)
        assertNull(engine.pollOutput())
        engine.tick(plan, 700)
        assertEquals(QueryTracePhase.TIMEOUT,
            assertIs<PollingEngine.Output.Trace>(engine.pollOutput()).event.phase)
    }

    @Test
    fun reconnectBackoffIsPureAndBounded() {
        val policy = ReconnectPolicy(baseDelayMs = 100, maxDelayMs = 350, jitterMaxMs = 0)

        assertEquals(100L, policy.delayForRetry(0, 4, io.github.tritbool.euc.ble.core.EucRandom { 0 }))
        assertEquals(200L, policy.delayForRetry(1, 4, io.github.tritbool.euc.ble.core.EucRandom { 0 }))
        assertEquals(350L, policy.delayForRetry(2, 4, io.github.tritbool.euc.ble.core.EucRandom { 0 }))
        assertNull(policy.delayForRetry(4, 4, io.github.tritbool.euc.ble.core.EucRandom { 0 }))
    }

    @Test
    fun pollingOutputQueueRejectsOverflowExplicitly() {
        val engine = PollingEngine("Replay", outputCapacity = 1)
        val plan = ProtocolPollingPlan(true,
            startupQueries = listOf(ProtocolQuerySpec("serial", CommandType.REQUEST_SERIAL)))
        engine.start(plan, 0)
        engine.tick(plan, 0)
        val query = assertIs<PollingEngine.Output.Query>(engine.pollOutput())
        engine.onAccepted(query.token, 0)

        assertFailsWith<IllegalStateException> {
            engine.onResponse(1) { true }
        }
    }

    @Test
    fun selectorUsesExplicitProtocolIdentityAndRejectsAmbiguousNames() {
        val protocols: List<SyncEucProtocol> = listOf(
            GotwaySyncProtocol(clock), ExtremeBullSyncProtocol(clock),
            LeaperkimSyncProtocol(clock), NosfetSyncProtocol(clock),
            KingsongSyncProtocol(clock, io.github.tritbool.euc.ble.core.EucRandom { 0 }),
            NinebotSyncProtocol(clock), NinebotZSyncProtocol(clock), InMotionSyncProtocol(clock)
        )
        val selector = ProtocolSelector(protocols, NoOpLogger())
        val gotway = protocols.single { it.protocolId == "GotwayProtocol" }

        assertEquals("ExtremeBullProtocol",
            selector.subclass(gotway, "Extreme Bull")?.protocolId)
        assertNull(selector.subclass(gotway, "Gotway"))
        val service = EucUuid.parse(BLEConstants.GOTWAY_SERVICE_UUID)
        val match = selector.byFingerprint(
            listOf(ProtocolSelector.Service(service, emptySet()))
        ) { id ->
            if (id == gotway.protocolId) listOf(listOf(GattServiceSpec(service))) else emptyList()
        }
        assertEquals(gotway.protocolId, match.first?.protocolId)
    }

    @Test
    fun selectionPrecedenceIsForcedThenExplicitNosfetThenFingerprint() {
        val gotway = GotwaySyncProtocol(clock)
        val nosfet = NosfetSyncProtocol(clock)
        val selector = ProtocolSelector(listOf(gotway, nosfet), NoOpLogger())
        val service = EucUuid.parse(BLEConstants.GOTWAY_SERVICE_UUID)
        val snapshots = listOf(ProtocolSelector.Service(service, emptySet()))
        val signatures = { id: String ->
            if (id == gotway.protocolId) listOf(listOf(GattServiceSpec(service))) else emptyList()
        }
        val explicit = selector.select("nOsFeT", snapshots, ProtocolSelectionMode.AUTO, null, signatures)
        assertEquals(nosfet, explicit.protocol)
        assertEquals(ProtocolSelectionReason.AUTO_DEVICE_NAME, explicit.reason)
        assertEquals(gotway, selector.select("Nosfet", snapshots, ProtocolSelectionMode.FORCED,
            gotway, signatures).protocol)
        assertEquals(gotway, selector.select("Unknown", snapshots, ProtocolSelectionMode.AUTO,
            null, signatures).protocol)
    }
}

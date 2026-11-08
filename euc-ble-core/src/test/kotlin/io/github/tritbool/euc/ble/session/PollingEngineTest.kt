package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.QueryTracePhase
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.ProtocolPollingPlan
import io.github.tritbool.euc.ble.protocols.ProtocolQuerySpec
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PollingEngineTest {
    private val spec = ProtocolQuerySpec("q", CommandType.REQUEST_SERIAL, responseTimeoutMs = 200,
        maxRetries = 1, retryBackoffMs = 50)
    private val plan = ProtocolPollingPlan(true, startupQueries = listOf(spec))
    private fun PollingEngine.drain(): List<PollingEngine.Output> = buildList {
        while (true) add(pollOutput() ?: break)
    }

    @Test
    fun `queue delay never consumes response timeout and SENT starts at acceptance`() {
        val engine = PollingEngine("Test")
        engine.start(plan, 0)
        engine.tick(plan, 0)
        val query = engine.pollOutput() as PollingEngine.Output.Query
        engine.tick(plan, 10_000)
        assertNull(engine.pollOutput())
        engine.onResponse(10_000) { true }
        assertNull(engine.pollOutput())
        engine.onAccepted(query.token, 10_000)
        assertEquals(QueryTracePhase.SENT, (engine.pollOutput() as PollingEngine.Output.Trace).event.phase)
        engine.tick(plan, 10_199)
        assertNull(engine.pollOutput())
        engine.onResponse(10_199) { true }
        val trace = (engine.pollOutput() as PollingEngine.Output.Trace).event
        assertEquals(QueryTracePhase.RESPONSE_MATCHED, trace.phase)
        assertEquals(199L, trace.latencyMs)
    }

    @Test
    fun `retries respect deadlines backoff and attempt limit`() {
        val engine = PollingEngine("Test")
        engine.start(plan, 0)
        engine.tick(plan, 0)
        val query = engine.pollOutput() as PollingEngine.Output.Query
        engine.onAccepted(query.token, 0)
        engine.drain()
        engine.tick(plan, 200)
        assertEquals(listOf(QueryTracePhase.TIMEOUT, QueryTracePhase.RETRY_SCHEDULED),
            engine.drain().filterIsInstance<PollingEngine.Output.Trace>().map { it.event.phase })
        engine.tick(plan, 249)
        assertNull(engine.pollOutput())
        engine.tick(plan, 250)
        val retry = engine.pollOutput() as PollingEngine.Output.Query
        assertEquals(2, retry.attempt)
        assertNotEquals(query.token, retry.token)
        engine.onAccepted(retry.token, 250)
        engine.drain()
        engine.tick(plan, 450)
        assertEquals(listOf(QueryTracePhase.TIMEOUT, QueryTracePhase.FAILED),
            engine.drain().filterIsInstance<PollingEngine.Output.Trace>().map { it.event.phase })
        engine.tick(plan, 100_000)
        assertNull(engine.pollOutput())
    }

    @Test
    fun `startup sequentially completes before periodic queries run`() {
        val engine = PollingEngine("Test")
        val full = ProtocolPollingPlan(true, listOf(spec, spec.copy(id = "second")),
            listOf(spec.copy(id = "periodic", intervalMs = 1000)))
        engine.start(full, 0)
        engine.tick(full, 0)
        val first = engine.pollOutput() as PollingEngine.Output.Query
        assertNull(engine.pollOutput())
        engine.onFailed(first.token, 0, unsupported = true)
        engine.drain()
        engine.tick(full, 0)
        val second = engine.pollOutput() as PollingEngine.Output.Query
        assertEquals("second", second.spec.id)
        engine.onAccepted(second.token, 0)
        engine.onResponse(1) { true }
        engine.drain()
        engine.tick(full, 1)
        assertEquals("periodic", (engine.pollOutput() as PollingEngine.Output.Query).spec.id)
    }

    @Test
    fun `one shot periodic queries stay completed and removed writes become invalid`() {
        val engine = PollingEngine("Test")
        val full = ProtocolPollingPlan(true, periodicQueries = listOf(spec.copy(intervalMs = 0)))
        engine.start(full, 0)
        engine.tick(full, 0)
        val first = engine.pollOutput() as PollingEngine.Output.Query
        engine.onAccepted(first.token, 0)
        engine.onResponse(1) { true }
        engine.drain()
        engine.tick(full, 1000)
        assertNull(engine.pollOutput())
        engine.tick(ProtocolPollingPlan.disabled(), 1001)
        engine.tick(full, 1002)
        val next = engine.pollOutput() as PollingEngine.Output.Query
        engine.reset()
        assertFalse(engine.isQueued(next.token))
        engine.onAccepted(next.token, 1003)
        assertNull(engine.pollOutput())
    }

    @Test
    fun `startup delay is measured after the preceding startup query`() {
        val engine = PollingEngine("Test")
        val full = plan.copy(startupQueries = listOf(spec, spec.copy(id = "second", initialDelayMs = 50)))
        engine.start(full, 0)
        engine.tick(full, 0)
        val first = engine.pollOutput() as PollingEngine.Output.Query
        engine.onAccepted(first.token, 1000)
        engine.onResponse(1010) { true }
        engine.drain()
        engine.tick(full, 1010)
        assertNull(engine.pollOutput())
        engine.tick(full, 1059)
        assertNull(engine.pollOutput())
        engine.tick(full, 1060)
        assertEquals("second", (engine.pollOutput() as PollingEngine.Output.Query).spec.id)
        engine.tick(ProtocolPollingPlan.disabled(), 1061)
        assertFalse(engine.isQueued(first.token))
        engine.tick(full, 1062)
        assertNull(engine.pollOutput())
    }
}

package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.core.QueryTraceEvent
import io.github.tritbool.euc.ble.core.QueryTracePhase
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.GotwayProtocol
import io.github.tritbool.euc.ble.protocols.ProtocolPollingPlan
import io.github.tritbool.euc.ble.protocols.ProtocolQuerySpec
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDriversTest {
    private val query = ProtocolQuerySpec("q", CommandType.BEEP, responseTimeoutMs = 200, maxRetries = 0)
    private fun protocol(encode: () -> ByteArray = { byteArrayOf(1) }): EUCProtocol =
        object : EUCProtocol by GotwayProtocol(logger = NoOpLogger()) {
            override fun getPollingPlan() = ProtocolPollingPlan(true, startupQueries = listOf(query))
            override fun getCommandSupport(commandType: CommandType) = CommandSupport.SUPPORTED
            override fun createCommand(commandType: CommandType, value: Any) = encode()
            override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray) = true
        }

    @Test
    fun `polling waits for transport acceptance and invalidates callbacks on stop`() = runTest {
        val traces = mutableListOf<QueryTraceEvent>()
        var valid: () -> Boolean = { false }
        var accept: () -> Unit = {}
        val session = PollingSession(protocol(), backgroundScope, { testScheduler.currentTime },
            traces::add, { _, v, a, _ -> valid = v; accept = a }, { throw it })
        session.start()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(valid())
        assertTrue(traces.isEmpty())
        accept()
        assertEquals(QueryTracePhase.SENT, traces.single().phase)
        advanceTimeBy(100)
        session.onNotification(byteArrayOf(1))
        assertEquals(100L, traces.last().latencyMs)
        session.stop()
        assertFalse(valid())
        accept()
        assertEquals(2, traces.size)
        assertTrue(backgroundScope.isActive)
    }

    @Test
    fun `polling encoding errors terminate polling and are reported`() = runTest {
        val errors = mutableListOf<Exception>()
        val session = PollingSession(protocol { error("bad encoding") }, backgroundScope,
            { testScheduler.currentTime }, {}, { _, _, _, _ -> fail("Must not enqueue") }, errors::add)
        session.start()
        runCurrent()
        assertEquals("bad encoding", errors.single().message)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, errors.size)
        assertTrue(backgroundScope.isActive)
    }

    @Test
    fun `write session retries stack rejection but never repeats accepted payloads`() = runTest {
        val session = WriteSession(backgroundScope) { throw it }
        val calls = mutableListOf<Int>()
        val snapshots = mutableListOf<ByteArray>()
        var firstAttempts = 0
        val original = byteArrayOf(1)
        session.enqueue(original, { true }, {
            snapshots += it
            calls += it[0].toInt()
            ++firstAttempts == 2
        }, { true }, {}, { fail("Write failed") })
        original.fill(0)
        session.enqueue(byteArrayOf(2), { true }, {
            snapshots += it
            calls += it[0].toInt()
            true
        }, { true }, {}, { fail("Write failed") })
        runCurrent()
        assertEquals(listOf(1), calls)
        advanceTimeBy(30)
        runCurrent()
        assertEquals(listOf(1, 1), calls)
        session.onAcknowledgment()
        runCurrent()
        assertEquals(listOf(1, 1, 2), calls)
        advanceTimeBy(300)
        runCurrent()
        assertTrue(snapshots.all { it.all { byte -> byte == 0.toByte() } })
        assertEquals(listOf(1, 1, 2), calls)
    }

    @Test
    fun `write reset wipes active bytes skips queued writes and keeps caller scope`() = runTest {
        val session = WriteSession(backgroundScope) { throw it }
        var snapshot = byteArrayOf()
        session.enqueue(byteArrayOf(9), { true }, { snapshot = it; true },
            { true }, {}, { fail("Write failed") })
        session.enqueue(byteArrayOf(8), { true }, { throw AssertionError("Stale write submitted") },
            { true }, {}, { fail("Write failed") })
        runCurrent()
        session.reset()
        runCurrent()
        assertArrayEquals(byteArrayOf(0), snapshot)
        assertTrue(backgroundScope.isActive)
        var accepted = false
        session.enqueue(byteArrayOf(7), { true }, { true }, { true }, { accepted = true }, {})
        runCurrent()
        assertTrue(accepted)
    }

    @Test
    fun `nonretryable transport failure reports once`() = runTest {
        val session = WriteSession(backgroundScope) { throw it }
        var attempts = 0
        var failures = 0
        session.enqueue(byteArrayOf(1), { true }, { attempts++; false }, { false }, {},
            { assertEquals(1, it); failures++ })
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, failures)
    }

    @Test
    fun `stack rejection exhausts exactly five attempts and exceptions are reported`() = runTest {
        val errors = mutableListOf<Exception>()
        val session = WriteSession(backgroundScope, errors::add)
        var attempts = 0
        var failures = 0
        session.enqueue(byteArrayOf(1), { true }, { attempts++; false }, { true }, {},
            { assertEquals(5, it); failures++ })
        runCurrent()
        advanceTimeBy(301)
        runCurrent()
        assertEquals(5, attempts)
        assertEquals(1, failures)
        session.enqueue(byteArrayOf(2), { true }, { error("transport failure") }, { true }, {}, {})
        runCurrent()
        assertEquals("transport failure", errors.single().message)
    }
}

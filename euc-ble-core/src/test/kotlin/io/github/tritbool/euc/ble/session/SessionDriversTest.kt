package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.core.QueryTraceEvent
import io.github.tritbool.euc.ble.core.QueryTracePhase
import io.github.tritbool.euc.ble.core.BLEConstants.ConnectionState
import io.github.tritbool.euc.ble.core.EucRandom
import io.github.tritbool.euc.ble.core.EucUuid
import io.github.tritbool.euc.ble.protocols.ProtocolWriteType
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.GotwayProtocol
import io.github.tritbool.euc.ble.protocols.ProtocolPollingPlan
import io.github.tritbool.euc.ble.protocols.ProtocolQuerySpec
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        val session = WriteSession(backgroundScope, now = { testScheduler.currentTime }) { throw it }
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
        val session = WriteSession(backgroundScope, now = { testScheduler.currentTime }) { throw it }
        val generation = session.generation
        var snapshot = byteArrayOf()
        session.enqueue(byteArrayOf(9), { true }, { snapshot = it; true },
            { true }, {}, { fail("Write failed") })
        session.enqueue(byteArrayOf(8), { true }, { throw AssertionError("Stale write submitted") },
            { true }, {}, { fail("Write failed") })
        runCurrent()
        session.reset()
        runCurrent()
        assertEquals(generation + 1, session.generation)
        assertArrayEquals(byteArrayOf(0), snapshot)
        assertTrue(backgroundScope.isActive)
        var accepted = false
        session.enqueue(byteArrayOf(7), { true }, { true }, { true }, { accepted = true }, {})
        runCurrent()
        assertTrue(accepted)
    }

    @Test
    fun `nonretryable transport failure reports once`() = runTest {
        val session = WriteSession(backgroundScope, now = { testScheduler.currentTime }) { throw it }
        var attempts = 0
        var failures = 0
        session.enqueue(byteArrayOf(1), { true }, { attempts++; false }, { false }, {},
            { assertEquals(1, it); failures++ })
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(1, failures)
    }

    @Test
    fun `write queue rejects overflow and wipes rejected payload`() = runTest {
        val session = WriteSession(backgroundScope, maxQueuedWrites = 1, now = { testScheduler.currentTime }) { throw it }
        val first = session.enqueue(byteArrayOf(1), { true }, { true }, { true }, {}, {})
        val rejectedBytes = byteArrayOf(2)
        val second = session.enqueue(rejectedBytes, { true }, { fail("Overflow write submitted") },
            { true }, {}, {})

        assertEquals(WriteEnqueueResult.ENQUEUED, first)
        assertEquals(WriteEnqueueResult.QUEUE_FULL, second)
        assertArrayEquals(byteArrayOf(2), rejectedBytes)
    }

    @Test
    fun `stack rejection exhausts exactly five attempts and exceptions are reported`() = runTest {
        val errors = mutableListOf<Exception>()
        val session = WriteSession(backgroundScope, now = { testScheduler.currentTime }, onError = errors::add)
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

    @Test
    fun `connection driver consumes the transport port and times out without cancelling caller`() = runTest {
        var connections = 0
        var closes = 0
        val transport = object : BleTransport {
            override fun connect(address: String): Boolean { connections++; return true }
            override fun disconnect() { closes++ }
            override fun discoverServices() = true
            override fun hasCharacteristic(uuid: EucUuid) = true
            override fun subscribe(uuid: EucUuid) = true
            override fun write(uuid: EucUuid, bytes: ByteArray, preferredType: ProtocolWriteType) = true
        }
        val states = mutableListOf<ConnectionState>()
        val errors = mutableListOf<String>()
        val driver = ConnectionSessionDriver(transport, backgroundScope, EucRandom { 0 },
            { testScheduler.currentTime }, states::add, { message, terminal ->
                assertTrue(terminal)
                errors += message
            })
        driver.configure(50, true, 3)
        driver.connect("wheel")
        runCurrent()
        advanceTimeBy(49)
        runCurrent()
        assertTrue(errors.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, connections)
        assertEquals(1, closes)
        assertEquals(listOf(ConnectionState.CONNECTING, ConnectionState.DISCONNECTED), states)
        assertEquals(listOf("Connection timeout"), errors)
        assertTrue(backgroundScope.isActive)
    }

    @Test
    fun `invalidated writes are discarded without duplicate failure callbacks`() = runTest {
        var valid = true
        val session = WriteSession(backgroundScope, now = { testScheduler.currentTime }) { throw it }
        session.enqueue(byteArrayOf(1), { valid }, { valid = false; false }, { true }, {},
            { fail("Already invalidated command must not report another failure") })
        runCurrent()
        var accepted = false
        session.enqueue(byteArrayOf(2), { true }, { true }, { true }, { accepted = true }, {})
        runCurrent()
        assertTrue(accepted)
    }

    @Test
    fun `transport callbacks run outside queue lock and reset cannot wipe submitted bytes`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        try {
            for (scope in listOf(backgroundScope,
                CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))) {
                val session = WriteSession(scope, now = { testScheduler.currentTime }) { throw it }
                session.enqueue(byteArrayOf(9), { true }, { bytes ->
                    executor.submit { session.reset() }.get(2, TimeUnit.SECONDS)
                    assertArrayEquals(byteArrayOf(9), bytes)
                    true
                }, { true }, { fail("Stale submission must not be accepted by a new generation") }, {})
                runCurrent()
                var accepted = false
                session.enqueue(byteArrayOf(1), { true }, { true }, { true }, { accepted = true }, {})
                runCurrent()
                assertTrue(accepted)
                session.reset()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelled session rejects writes rather than accepting work without a worker`() {
        val job = Job().apply { cancel() }
        val session = WriteSession(CoroutineScope(job)) { throw it }
        assertEquals(WriteEnqueueResult.SESSION_CLOSED, session.enqueue(byteArrayOf(1), { true },
            { fail("Closed session must not submit") }, { true }, {}, {}))
    }

    @Test
    fun `reentrant connection callbacks cannot apply stale failure to replacement connection`() = runTest {
        val addresses = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val transport = object : BleTransport {
            override fun connect(address: String): Boolean { addresses += address; return true }
            override fun disconnect() {}
            override fun discoverServices() = true
            override fun hasCharacteristic(uuid: EucUuid) = true
            override fun subscribe(uuid: EucUuid) = true
            override fun write(uuid: EucUuid, bytes: ByteArray, preferredType: ProtocolWriteType) = true
        }
        lateinit var driver: ConnectionSessionDriver
        driver = ConnectionSessionDriver(transport, backgroundScope, EucRandom { 0 },
            { testScheduler.currentTime }, { state ->
                if (state == ConnectionState.DISCONNECTED) driver.connect("replacement")
            }, { message, _ -> errors += message })
        driver.configure(50, true, 0)
        driver.connect("wheel")
        driver.onConnected()
        driver.onDisconnected()
        assertEquals(listOf("wheel", "replacement"), addresses)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `discovery rejection closes old connection before error callback can replace it`() = runTest {
        val addresses = mutableListOf<String>()
        val states = mutableListOf<ConnectionState>()
        val transport = object : BleTransport {
            override fun connect(address: String): Boolean { addresses += address; return true }
            override fun disconnect() {}
            override fun discoverServices() = false
            override fun hasCharacteristic(uuid: EucUuid) = true
            override fun subscribe(uuid: EucUuid) = true
            override fun write(uuid: EucUuid, bytes: ByteArray, preferredType: ProtocolWriteType) = true
        }
        lateinit var driver: ConnectionSessionDriver
        driver = ConnectionSessionDriver(transport, backgroundScope, EucRandom { 0 },
            { testScheduler.currentTime }, states::add, { _, _ -> driver.connect("replacement") })
        driver.connect("wheel")
        driver.onConnected()
        assertEquals(listOf("wheel", "replacement"), addresses)
        assertEquals(ConnectionState.CONNECTING, states.last())
    }
}

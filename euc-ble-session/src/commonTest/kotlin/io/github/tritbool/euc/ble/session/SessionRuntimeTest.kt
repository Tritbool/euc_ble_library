package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.*
import io.github.tritbool.euc.ble.protocols.*
import kotlin.test.*

class SessionRuntimeTest {
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 0L
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }

    @Test
    fun writeQueueOwnsBackoffAcceptanceDeadlineAndGeneration() {
        val queue = WriteQueue<String>(1)
        val bytes = byteArrayOf(1)
        assertEquals(WriteEnqueueResult.ENQUEUED, queue.enqueue(bytes, "first"))
        bytes.fill(0)
        assertEquals(WriteEnqueueResult.QUEUE_FULL, queue.enqueue(byteArrayOf(2), "overflow"))
        val first = assertNotNull(queue.next(0))
        assertContentEquals(byteArrayOf(1), first.bytes)
        assertFalse(queue.onSubmitted(first.token, false, true, 0))
        assertNull(queue.next(29))
        assertSame(first, queue.next(30))
        assertEquals(2, first.attempt)
        queue.onSubmitted(first.token, true, true, 30)
        queue.enqueue(byteArrayOf(3), "second")
        assertNull(queue.next(329))
        assertEquals("second", queue.next(330)?.attachment)
        assertContentEquals(byteArrayOf(0), first.bytes)
        val generation = queue.generation
        queue.reset()
        assertEquals(generation + 1, queue.generation)
        assertNull(queue.next(10_000))
        assertFalse(queue.hasPending)
    }

    @Test
    fun acceptedWritesNeverRetryAndStaleResultsCannotMutateResetQueue() {
        val queue = WriteQueue<Unit>()
        queue.enqueue(byteArrayOf(1), Unit)
        val stale = assertNotNull(queue.next(0))
        queue.reset()
        queue.enqueue(byteArrayOf(2), Unit)
        val current = assertNotNull(queue.next(1))
        assertFalse(queue.onSubmitted(stale.token, true, true, 1))
        queue.onAcknowledgment()
        queue.onSubmitted(current.token, true, true, 1)
        assertFalse(queue.hasPending)
        assertContentEquals(byteArrayOf(0), current.bytes)
    }

    @Test
    fun rejectionExhaustsExactlyFiveAttemptsWithoutRealTime() {
        val queue = WriteQueue<Unit>()
        queue.enqueue(byteArrayOf(1), Unit)
        var now = 0L
        repeat(5) { index ->
            val write = assertNotNull(queue.next(now))
            assertEquals(index + 1, write.attempt)
            assertEquals(index == 4, queue.onSubmitted(write.token, false, true, now))
            now += 30L * (index + 1)
        }
        assertFalse(queue.hasPending)
    }

    @Test
    fun protocolTimersAreSessionStateAndResetDropsDelayedWrites() {
        val engine = GotwaySyncProtocol(clock)
        val session = ProtocolSession(engine)
        engine.createCommand(CommandType.SET_LIGHT_MODE, CommandValue.Integer(1))
        session.afterInput(50)
        assertEquals(150L, session.nextDeadline)
        session.advance(149)
        assertNull(session.pollOutput())
        session.advance(150)
        assertContentEquals("b".encodeToByteArray(),
            assertIs<ProtocolOutput.Write>(session.pollOutput()).payload)
        assertEquals(0, session.armedTimerCount)
        engine.createCommand(CommandType.SET_LIGHT_MODE, CommandValue.Integer(0))
        session.afterInput(200)
        session.reset(201)
        session.advance(10_000)
        assertNull(session.pollOutput())
        assertNull(session.nextDeadline)
    }

    @Test
    fun timerCapacityIsExplicit() {
        val engine = GotwaySyncProtocol(clock)
        val session = ProtocolSession(engine, timerCapacity = 1)
        engine.createCommand(CommandType.SET_LIGHT_MODE, CommandValue.Integer(0))
        session.afterInput(0)
        engine.createCommand(CommandType.SET_LIGHT_MODE, CommandValue.Integer(1))
        assertFailsWith<IllegalStateException> { session.afterInput(0) }
    }
}

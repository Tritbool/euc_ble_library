package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.core.EucRandom
import io.github.tritbool.euc.ble.core.SecureEucRandom
import io.github.tritbool.euc.ble.core.SystemEucClock
import io.github.tritbool.euc.ble.models.PasswordOperationStatus
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncTimerProtocolsTest {
    @Test
    fun `Gotway timer actions retain all delayed command writes in order`() {
        val engine = GotwaySyncProtocol(SystemEucClock)
        assertArrayEquals("b".encodeToByteArray(), engine.createCommand(CommandType.SET_SPEED_LIMIT, 42))
        val timers = engine.drainOutputs().filterIsInstance<ProtocolOutput.StartTimer>()
        assertEquals(listOf(100L, 200L, 300L, 400L, 500L, 600L), timers.map { it.delayMs })
        assertEquals(6, timers.map { it.id }.distinct().size)
        val writes = timers.map { timer ->
            engine.onTimer(timer.id)
            (engine.pollOutput() as ProtocolOutput.Write).payload.decodeToString()
        }
        assertEquals(listOf("W", "Y", "4", "2", "b", "b"), writes)
        engine.onTimer(timers.first().id)
        assertNull(engine.pollOutput())
    }

    @Test
    fun `Gotway reset discards pending timer writes`() {
        val engine = GotwaySyncProtocol(SystemEucClock)
        engine.createCommand(CommandType.LIGHT_ON, Unit)
        val timer = engine.drainOutputs().filterIsInstance<ProtocolOutput.StartTimer>().single()
        engine.resetSession()
        engine.onTimer(timer.id)
        assertNull(engine.pollOutput())
    }

    @Test
    fun `KingSong password timeout starts on acceptance and remains unconfirmed`() {
        val engine = KingsongSyncProtocol(SystemEucClock, EucRandom { 0 })
        val payload = engine.createCommand(CommandType.SET_PASSWORD, "0012")
        assertTrue(engine.drainOutputs().isEmpty())
        engine.onCommandSubmitted(payload)
        val timer = engine.pollOutput() as ProtocolOutput.StartTimer
        assertEquals(KingsongProtocol.PASSWORD_OPERATION_TIMEOUT_MS, timer.delayMs)
        assertFalse(engine.canSendCommand(payload))
        engine.onTimer(timer.id)
        val outputs = engine.drainOutputs()
        val result = outputs.filterIsInstance<ProtocolOutput.SecurityEvent>().single().event
            as WheelSecurityEvent.PasswordOperationResult
        assertEquals(PasswordOperationStatus.TIMEOUT_UNCONFIRMED, result.status)
        assertTrue(engine.canSendCommand(payload))
    }

    @Test
    fun `KingSong confirmation cancels timer and emits no timeout after reset`() {
        val engine = KingsongSyncProtocol(SystemEucClock, SecureEucRandom())
        val payload = engine.createCommand(CommandType.SET_PASSWORD, "0012")
        engine.onCommandSubmitted(payload)
        val timer = engine.pollOutput() as ProtocolOutput.StartTimer
        engine.onNotification(ByteUtils.hexToBytes("AA55303031320100000000000000000043145A5A"))
        val outputs = engine.drainOutputs()
        assertEquals(timer.id, outputs.filterIsInstance<ProtocolOutput.CancelTimer>().single().id)
        val result = outputs.filterIsInstance<ProtocolOutput.SecurityEvent>().single().event
            as WheelSecurityEvent.PasswordOperationResult
        assertEquals(PasswordOperationStatus.CONFIRMED, result.status)
        engine.resetSession()
        engine.onTimer(timer.id)
        assertNull(engine.pollOutput())
    }

    @Test
    fun `KingSong unlock uses injected nonce and received challenge`() {
        val engine = KingsongSyncProtocol(SystemEucClock, EucRandom { 0 })
        engine.onNotification(ByteUtils.hexToBytes("AA5501003034343030300000000000005F145A5A"))
        engine.drainOutputs()
        val command = engine.createCommand(CommandType.UNLOCK, Unit)
        assertArrayEquals("408020".encodeToByteArray(), command.copyOfRange(10, 16))
        engine.resetSession()
        assertTrue(engine.createCommand(CommandType.UNLOCK, Unit).isEmpty())
    }
}

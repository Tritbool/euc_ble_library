package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucDateTime
import io.github.tritbool.euc.ble.core.SecureEucRandom
import io.github.tritbool.euc.ble.core.SystemEucClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProtocolOutboxTest {

    @Test
    fun `outbox is FIFO and drains completely`() {
        val outbox = ProtocolOutbox()
        outbox.push(ProtocolOutput.StartTimer("a", 1))
        outbox.push(ProtocolOutput.CancelTimer("a"))
        assertEquals(2, outbox.size)
        assertEquals(ProtocolOutput.StartTimer("a", 1), outbox.poll())
        assertEquals(ProtocolOutput.CancelTimer("a"), outbox.poll())
        assertNull(outbox.poll())
    }

    @Test
    fun `write outputs compare by content and print as hex`() {
        val write = ProtocolOutput.Write(byteArrayOf(0x0A, 0xFF.toByte()))
        assertEquals(ProtocolOutput.Write(byteArrayOf(0x0A, 0xFF.toByte())), write)
        assertEquals(ProtocolOutput.Write(byteArrayOf(0x0A, 0xFF.toByte())).hashCode(), write.hashCode())
        assertNotEquals(ProtocolOutput.Write(byteArrayOf(0x0A)), write)
        assertEquals("Write(0A FF)", write.toString())
    }

    @Test
    fun `JVM platform defaults are sane`() {
        val now = SystemEucClock.localDateTime()
        assertTrue(now.month in 1..12 && now.dayOfMonth in 1..31 && now.hour in 0..23)
        assertTrue(SystemEucClock.currentTimeMillis() > 0)
        val random = SecureEucRandom()
        repeat(100) { assertTrue(random.nextInt(10) in 0..9) }
        assertEquals(EucDateTime(2026, 1, 2, 3, 4, 5).minute, 4)
    }
}

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NinebotSyncProtocolTest {
    private class Clock(var now: Long = 1_000L) : EucClock {
        override fun currentTimeMillis() = now
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }

    private fun legacyFrame() = byteArrayOf(
        0x55, 0x18, 0x01, 0x01, 0xD0.toByte(), 0x1A, 0xFE.toByte(), 0x00,
        0x40, 0x42, 0x0F, 0x00, 0x2C, 0x01, 0xA6.toByte(), 0x09, 0x48, 0x01
    )

    private fun wheelLogFrame(type: Int, payload: ByteArray) =
        byteArrayOf(0x5A, 0xA5.toByte(), payload.size.toByte(), 0x14, 0x3E, 0x04, type.toByte()) +
            payload + byteArrayOf(0, 0)

    @Test
    fun `sync engines produce telemetry using injected time and clear outputs on reset`() {
        val clock = Clock()
        for (engine in listOf(NinebotSyncProtocol(clock), NinebotZSyncProtocol(clock))) {
            clock.now = 1_000L
            engine.onNotification(legacyFrame())
            val first = (engine.pollOutput() as ProtocolOutput.Telemetry).data
            assertEquals(1_000L, first.timestamp)
            assertEquals(0L, first.rideTime)
            clock.now = 4_000L
            engine.onNotification(legacyFrame())
            val second = (engine.pollOutput() as ProtocolOutput.Telemetry).data
            assertEquals(3L, second.rideTime)
            engine.onNotification(legacyFrame())
            engine.resetSession()
            assertNull(engine.pollOutput())
            engine.onNotification(legacyFrame())
            assertEquals(0L, (engine.pollOutput() as ProtocolOutput.Telemetry).data.rideTime)
        }
    }

    @Test
    fun `fragmented and coalesced frames preserve output order`() {
        val engine = NinebotSyncProtocol(Clock())
        val payload = ByteArray(32).apply {
            this[8] = 90
            this[10] = 10
            this[22] = 200.toByte()
            this[24] = 0x7C
            this[25] = 0x15
        }
        val first = wheelLogFrame(0xB0, payload)
        payload[10] = 20
        val second = wheelLogFrame(0xB0, payload)
        engine.onNotification(first.copyOfRange(0, 5))
        assertNull(engine.pollOutput())
        engine.onNotification(first.copyOfRange(5, first.size) + second)
        assertEquals(1.0, (engine.pollOutput() as ProtocolOutput.Telemetry).data.speed)
        assertEquals(2.0, (engine.pollOutput() as ProtocolOutput.Telemetry).data.speed)
        assertNull(engine.pollOutput())
    }

    @Test
    fun `reset drops partial frames and settings in both engines`() {
        val engines = listOf(NinebotSyncProtocol(Clock()), NinebotZSyncProtocol(Clock()))
        val frame = wheelLogFrame(0x74, byteArrayOf(0x98.toByte(), 0x08))
        for (engine in engines) {
            engine.onNotification(frame)
            engine.onNotification(frame.copyOfRange(0, 5))
            engine.resetSession()
            engine.onNotification(frame.copyOfRange(5, frame.size))
            val snapshot = when (engine) {
                is NinebotSyncProtocol -> engine.getZSettingsSnapshot()
                is NinebotZSyncProtocol -> engine.getZSettingsSnapshot()
                else -> error("unexpected engine")
            }
            assertNull(snapshot.speedLimitKmh)
            assertTrue(engine.drainOutputs().isEmpty())
        }
    }
}

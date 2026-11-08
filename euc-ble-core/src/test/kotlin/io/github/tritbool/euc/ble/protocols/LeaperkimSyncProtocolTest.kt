package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeaperkimSyncProtocolTest {
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 50_000L
        override fun localDateTime() = EucDateTime(2026, 1, 17, 15, 10, 9)
    }

    private fun frame(version: Int, speed: Int): ByteArray = ByteArray(40).apply {
        this[0] = 0xDC.toByte()
        this[1] = 0x5A
        this[2] = 0x5C
        this[3] = 36
        this[4] = 0x30
        this[5] = 0xED.toByte()
        this[6] = (speed shr 8).toByte()
        this[7] = speed.toByte()
        this[28] = (version shr 8).toByte()
        this[29] = version.toByte()
    }

    @Test
    fun `fragmented notifications and coalesced frames retain order`() {
        for (engine in listOf(LeaperkimSyncProtocol(clock), NosfetSyncProtocol(clock))) {
            val first = frame(43001, 390)
            val second = frame(43001, 100)
            engine.onNotification(first.copyOfRange(0, 5))
            assertNull(engine.pollOutput())
            engine.onNotification(first.copyOfRange(5, first.size) + second)
            val a = (engine.pollOutput() as ProtocolOutput.Telemetry).data
            val b = (engine.pollOutput() as ProtocolOutput.Telemetry).data
            assertEquals(39.0, a.speed)
            assertEquals(10.0, b.speed)
            assertEquals(50_000L, a.timestamp)
            assertNull(engine.pollOutput())
            engine.onNotification(first)
            engine.resetSession()
            assertNull(engine.pollOutput())
        }
    }

    @Test
    fun `lock command uses injected local time`() {
        val engine = LeaperkimSyncProtocol(clock)
        val command = engine.createCommand(CommandType.LOCK, Unit)
        assertArrayEquals(byteArrayOf(17, 15, 10, 9), command.copyOfRange(9, 13))
    }

    @Test
    fun `closing facade does not cancel caller scope`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            LeaperkimProtocol(scope).close()
            NosfetProtocol(scope).close()
            assertTrue(scope.isActive)
        } finally {
            scope.cancel()
        }

        @Test
        fun `legacy subclass hooks still affect telemetry`() = runTest {
            val protocol = object : LeaperkimProtocol(backgroundScope, clock) {
                override val manufacturer = "Custom"
                override fun decodeSpeed(speedRaw: Short) = 12.0
                override fun modelByMajorVersion(version: Int) = "Custom model"
            }
            try {
                protocol.decode(frame(43001, 390))
                val data = protocol.dataFlow.first()
                assertEquals("Custom", data.manufacturer)
                assertEquals("Custom model", data.model)
                assertEquals(12.0, data.speed)
            } finally {
                protocol.close()
            }
        }
    }
}

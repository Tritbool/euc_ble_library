package io.github.tritbool.euc.ble.session

import app.cash.turbine.test
import io.github.tritbool.euc.ble.models.AuthenticationState
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.CommandValue
import io.github.tritbool.euc.ble.protocols.ProtocolOutbox
import io.github.tritbool.euc.ble.protocols.ProtocolOutput
import io.github.tritbool.euc.ble.protocols.SyncEucProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.github.tritbool.euc.ble.core.EucUuid as UUID
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolSessionDriverTest {

    /**
     * Notification opcodes (first byte):
     * 1 = telemetry with speed = data[1]; 2 = auto-reply write + arm "timeout" (1000 ms);
     * 3 = cancel "timeout"; 4 = security update; 5 = two telemetry frames.
     */
    private class FakeSyncProtocol : SyncEucProtocol {
        val outbox = ProtocolOutbox()
        val timerFires = mutableListOf<String>()
        val submitted = mutableListOf<ByteArray>()
        var resets = 0
        private val inFlight = AtomicInteger()
        val concurrentEntries = AtomicInteger()
        private var security: WheelSecurityState? = null

        override val manufacturer = "Fake"
        override fun getDataCharacteristicUUID(): UUID = UUID.parse("00000000-0000-0000-0000-000000000001")
        override fun getServiceUUID(): UUID = UUID.parse("00000000-0000-0000-0000-000000000002")
        override fun isDeviceReady(data: EUCData) = true
        override fun getSecurityState(): WheelSecurityState? = security
        override fun createCommand(commandType: CommandType, value: CommandValue): ByteArray {
            outbox.push(ProtocolOutput.StartTimer("cmd", 50))
            return byteArrayOf(0x42)
        }

        override fun onCommandSubmitted(payload: ByteArray) {
            submitted += payload
        }

        override fun onNotification(data: ByteArray) {
            if (inFlight.incrementAndGet() > 1) concurrentEntries.incrementAndGet()
            try {
                when (data[0].toInt()) {
                    1 -> outbox.push(ProtocolOutput.Telemetry(telemetry(data[1].toDouble())))
                    2 -> {
                        outbox.push(ProtocolOutput.Write(byteArrayOf(0x0A, 0x0B)))
                        outbox.push(ProtocolOutput.StartTimer("timeout", 1000))
                    }
                    3 -> outbox.push(ProtocolOutput.CancelTimer("timeout"))
                    4 -> {
                        security = WheelSecurityState(authentication = AuthenticationState.AUTHENTICATED)
                        outbox.push(ProtocolOutput.SecurityStateChanged(security!!))
                        outbox.push(
                            ProtocolOutput.SecurityEvent(
                                WheelSecurityEvent.AuthenticationStatus(AuthenticationState.AUTHENTICATED)
                            )
                        )
                    }
                    5 -> {
                        outbox.push(ProtocolOutput.Telemetry(telemetry(1.0)))
                        outbox.push(ProtocolOutput.Telemetry(telemetry(2.0)))
                    }
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun onTimer(id: String) {
            timerFires += id
            outbox.push(ProtocolOutput.Telemetry(telemetry(99.0)))
        }

        override fun resetSession() {
            resets++
            security = null
            outbox.clear()
        }

        override fun pollOutput(): ProtocolOutput? = outbox.poll()
    }

    private fun TestScope.adapter(protocol: SyncEucProtocol) =
        ProtocolSessionDriver(protocol, backgroundScope, now = { testScheduler.currentTime })

    @Test
    fun `decode returns telemetry synchronously and emits it on dataFlow`() = runTest {
        val adapter = adapter(FakeSyncProtocol())
        adapter.dataFlow.test {
            val decoded = adapter.decode(byteArrayOf(1, 25))
            assertEquals(25.0, decoded?.speed)
            assertSame(decoded, awaitItem())
        }
    }

    @Test
    fun `decode returns the last telemetry and emits all of them in order`() = runTest {
        val adapter = adapter(FakeSyncProtocol())
        adapter.dataFlow.test {
            assertEquals(2.0, adapter.decode(byteArrayOf(5))?.speed)
            assertEquals(1.0, awaitItem().speed)
            assertEquals(2.0, awaitItem().speed)
        }
    }

    @Test
    fun `bounded telemetry flow drops oldest when no collector is ready`() = runTest {
        val adapter = adapter(FakeSyncProtocol())
        repeat(257) { adapter.decode(byteArrayOf(1, it.toByte())) }

        assertEquals(1.0, adapter.dataFlow.first().speed)
    }

    @Test
    fun `bounded compatibility write flow rejects overflow explicitly`() = runTest {
        val adapter = adapter(FakeSyncProtocol())
        repeat(256) { adapter.decode(byteArrayOf(2)) }

        val error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            adapter.decode(byteArrayOf(2))
        }
        assertTrue(error.message!!.contains("control write was rejected"))
    }

    @Test
    fun `decode without telemetry returns null and raw frames are copied`() = runTest {
        val adapter = adapter(FakeSyncProtocol())
        adapter.rawFrameFlow.test {
            val input = byteArrayOf(9, 9)
            assertNull(adapter.decode(input))
            val raw = awaitItem()
            assertArrayEquals(input, raw)
            input[0] = 0
            assertEquals(9, raw[0].toInt())
        }
    }

    @Test
    fun `writes are delivered on writeFlow`() = runTest {
        val adapter = adapter(FakeSyncProtocol())
        adapter.decode(byteArrayOf(2))
        adapter.writeFlow.test {
            assertArrayEquals(byteArrayOf(0x0A, 0x0B), awaitItem())
        }
    }

    @Test
    fun `timers fire after their delay and their outputs are dispatched`() = runTest {
        val protocol = FakeSyncProtocol()
        val adapter = adapter(protocol)
        adapter.decode(byteArrayOf(2))
        assertEquals(1, adapter.armedTimerCount)

        advanceTimeBy(999)
        runCurrent()
        assertTrue(protocol.timerFires.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("timeout"), protocol.timerFires)
        assertEquals(0, adapter.armedTimerCount)
        adapter.dataFlow.test { assertEquals(99.0, awaitItem().speed) }
    }

    @Test
    fun `re-arming a timer replaces it`() = runTest {
        val protocol = FakeSyncProtocol()
        val adapter = adapter(protocol)
        adapter.decode(byteArrayOf(2))
        advanceTimeBy(600)
        adapter.decode(byteArrayOf(2))
        advanceTimeBy(600)
        runCurrent()
        assertTrue(protocol.timerFires.isEmpty())
        advanceTimeBy(401)
        runCurrent()
        assertEquals(listOf("timeout"), protocol.timerFires)
    }

    @Test
    fun `cancel output disarms the timer`() = runTest {
        val protocol = FakeSyncProtocol()
        val adapter = adapter(protocol)
        adapter.decode(byteArrayOf(2))
        adapter.decode(byteArrayOf(3))
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(protocol.timerFires.isEmpty())
        assertEquals(0, adapter.armedTimerCount)
    }

    @Test
    fun `outputs queued by createCommand are dispatched`() = runTest {
        val protocol = FakeSyncProtocol()
        val adapter = adapter(protocol)
        assertArrayEquals(byteArrayOf(0x42), adapter.createCommand(CommandType.BEEP, Unit))
        assertEquals(1, adapter.armedTimerCount)
        advanceTimeBy(51)
        runCurrent()
        assertEquals(listOf("cmd"), protocol.timerFires)
    }

    @Test
    fun `resetSession disarms timers and drops pending writes`() = runTest {
        val protocol = FakeSyncProtocol()
        val adapter = adapter(protocol)
        adapter.decode(byteArrayOf(2))
        adapter.resetSession()
        assertEquals(1, protocol.resets)
        assertEquals(0, adapter.armedTimerCount)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(protocol.timerFires.isEmpty())
        adapter.writeFlow.test { expectNoEvents() }
    }

    @Test
    fun `resetSession drops pending telemetry from previous connection`() = runTest {
        val adapter = adapter(FakeSyncProtocol())
        adapter.decode(byteArrayOf(1, 25))
        adapter.resetSession()
        adapter.dataFlow.test {
            expectNoEvents()
            adapter.decode(byteArrayOf(1, 10))
            assertEquals(10.0, awaitItem().speed)
        }
    }

    @Test
    fun `security outputs update state and events`() = runTest {
        val adapter = adapter(FakeSyncProtocol())
        assertNull(adapter.securityState.value)
        adapter.securityEventFlow.test {
            adapter.decode(byteArrayOf(4))
            assertEquals(
                WheelSecurityEvent.AuthenticationStatus(AuthenticationState.AUTHENTICATED),
                awaitItem()
            )
        }
        assertEquals(AuthenticationState.AUTHENTICATED, adapter.securityState.value?.authentication)
        adapter.securityStateFlow.test {
            assertEquals(AuthenticationState.AUTHENTICATED, awaitItem().authentication)
        }
        adapter.resetSession()
        assertNull(adapter.securityState.value)
    }

    @Test
    fun `spec calls are forwarded`() = runTest {
        val protocol = FakeSyncProtocol()
        val adapter = adapter(protocol)
        assertEquals("Fake", adapter.manufacturer)
        val dataUuid = UUID.parse("00000000-0000-0000-0000-000000000001")
        assertEquals(dataUuid, adapter.getDataCharacteristicUUID())
        assertEquals(dataUuid, adapter.getWriteCharacteristicUUID())
        assertEquals(listOf(dataUuid), adapter.getCandidateDataCharacteristicUUIDs())
        assertEquals(UUID.parse("00000000-0000-0000-0000-000000000002"), adapter.getServiceUUID())
        adapter.onCommandSubmitted(byteArrayOf(7))
        assertArrayEquals(byteArrayOf(7), protocol.submitted.single())
        assertFalse(adapter.getPollingPlan().enabled)
    }

    @Test
    fun `close never cancels a caller-provided scope`() {
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val adapter = ProtocolSessionDriver(FakeSyncProtocol(), callerScope)
            adapter.decode(byteArrayOf(2))
            adapter.close()
            assertEquals(0, adapter.armedTimerCount)
            assertTrue(callerScope.isActive)
        } finally {
            callerScope.cancel()
        }
    }

    @Test
    fun `concurrent callers are serialized`() = runBlocking {
        val protocol = FakeSyncProtocol()
        val adapter = ProtocolSessionDriver(protocol)
        try {
            (1..8).map {
                async(Dispatchers.Default) { repeat(2_000) { adapter.decode(byteArrayOf(1, 1)) } }
            }.awaitAll()
            assertEquals(0, protocol.concurrentEntries.get())
        } finally {
            adapter.close()
        }
    }

    private companion object {
        fun telemetry(speed: Double) = EUCData(
            speed = speed, voltage = 0.0, current = 0.0, temperature = 0.0, batteryLevel = 0,
            distance = 0.0, power = 0.0, timestamp = 0L, rawData = byteArrayOf(),
            manufacturer = "Fake", model = "Fake", isCharging = false, rideTime = 0L,
        )
    }
}

package com.euc.ble.protocols

import app.cash.turbine.test
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import com.euc.ble.test.JUnit4AssertionsCompat.assertNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

class KingsongProtocolAsyncTest {

    private lateinit var protocol: KingsongProtocol

    @BeforeEach
    fun setUp() {
        protocol = KingsongProtocol()
    }


    @AfterEach
    fun tearDown() {
        if (this::protocol.isInitialized) {
            protocol.close()
        }
    }

    private suspend fun collectN(
        flow: kotlinx.coroutines.flow.Flow<EUCData>,
        n: Int,
        timeoutMs: Long = 1000L
    ): List<EUCData> {
        val out = CopyOnWriteArrayList<EUCData>()
        val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            flow.collect { out.add(it) }
        }
        try {
            withTimeout(timeoutMs) {
                while (out.size < n) kotlinx.coroutines.delay(100)
            }
        } catch (e: Exception) {
            print("TIMEOUT !, $e")
            // timeout
        } finally {
            job.cancel()
        }
        return out.toList()
    }

    @Test
    fun testSingleCompleteFrameEmitsEucData() = runTest {
        tearDown()
        protocol = KingsongProtocol(scope = backgroundScope)

        val hex = "AA55E52F8B060D000A9A1500E40C02E0A9145A5A"
        val payload = ByteUtils.hexToBytes(hex)

        protocol.dataFlow.test {
            protocol.decode(payload)

            val d = awaitItem()
            assertEquals("KingSong", d.manufacturer)
            assertEquals(122.61, d.voltage, 0.01)
            assertEquals(16.75, d.speed, 0.01)
            assertTrue(d.distance > 0.0)
            assertEquals(0.21, d.current, 0.01)
            assertEquals(33.0, d.temperature, 0.01)
            assertFalse(d.isCharging)
            assertEquals(100, d.batteryLevel)
            assertNull(d.cellVoltages)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testFragmentedFrameAcrossTwoDecodeCalls() = runTest {
        tearDown()
        protocol = KingsongProtocol(scope = backgroundScope)

        val hexA = "AA55E52F8B060D000A9A"
        val hexB = "1500E40C02E0A9145A5A"
        val a = ByteUtils.hexToBytes(hexA)
        val b = ByteUtils.hexToBytes(hexB)

        protocol.dataFlow.test {
            protocol.decode(a)
            protocol.decode(b)

            val d = awaitItem()
            assertEquals(122.61, d.voltage, 0.01)
            assertEquals(16.75, d.speed, 0.01)
            assertTrue(d.distance > 0.0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testTwoFramesInOnePayload() = runTest {
        tearDown()
        protocol = KingsongProtocol(scope = backgroundScope)

        val hex =
            "AA55E52F8B060D000A9A1500E40C02E0A9145A5A" + "AA55E52F8B060D000A9A1500E40C02E0A9145A5A"
        val payload = ByteUtils.hexToBytes(hex)

        protocol.dataFlow.test {
            protocol.decode(payload)

            val first = awaitItem()
            val second = awaitItem()

            assertEquals(122.61, first.voltage, 0.01)
            assertEquals(122.61, second.voltage, 0.01)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testLeadingNoiseAndResync() = runTest {
        tearDown()
        protocol = KingsongProtocol(scope = backgroundScope)

        val hex = "00FFAA55" + "E52F8B060D000A9A1500E40C02E0A9145A5A"
        val payload = ByteUtils.hexToBytes(hex)

        protocol.dataFlow.test {
            protocol.decode(payload)

            val d = awaitItem()
            assertEquals(122.61, d.voltage, 0.01)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testTruncatedThenCompletedFrame() = runTest {
        tearDown()
        protocol = KingsongProtocol(scope = backgroundScope)

        val part1 = ByteUtils.hexToBytes("AA55E52F8B060D00") // too short
        val part2 = ByteUtils.hexToBytes("0A9A1500E40C02E0A9145A5A")

        protocol.dataFlow.test {
            protocol.decode(part1)
            expectNoEvents()

            protocol.decode(part2)

            val d = awaitItem()
            assertEquals(122.61, d.voltage, 0.01)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testHeaderVariant55AA() = runTest {
        tearDown()
        protocol = KingsongProtocol(scope = backgroundScope)

        val hex = "55AAE52F8B060D000A9A1500E40C02E0A9145A5A"
        val payload = ByteUtils.hexToBytes(hex)

        protocol.dataFlow.test {
            protocol.decode(payload)

            val d = awaitItem()
            assertEquals(122.61, d.voltage, 0.01)

            cancelAndIgnoreRemainingEvents()
        }
    }
}

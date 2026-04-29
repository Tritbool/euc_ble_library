package com.euc.ble.protocols

import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class KingsongProtocolAsyncTest {

    private suspend fun collectN(flow: kotlinx.coroutines.flow.Flow<EUCData>, n: Int, timeoutMs: Long = 1000L): List<EUCData> {
        val out = CopyOnWriteArrayList<EUCData>()
        val job = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            flow.collect { out.add(it) }
        }
        try {
            withTimeout(timeoutMs) {
                while (out.size < n) kotlinx.coroutines.delay(10)
            }
        } catch (e: Exception) {
            // timeout
        } finally {
            job.cancel()
        }
        return out.toList()
    }

    @Test
    fun testSingleCompleteFrameEmitsEucData() = runBlocking {
        val protocol = KingsongProtocol()
        val hex = "AA55E52F8B060D000A9A1500E40C02E0A9145A5A"
        val payload = ByteUtils.hexToBytes(hex)

        val collector = async { collectN(protocol.dataFlow, 1, 1000L) }
        protocol.decode(payload)

        val items = withTimeout(1000L) { collector.await() }
        assertEquals(1, items.size)
        val d = items[0]
        assertEquals("KingSong", d.manufacturer)
        assertEquals(122.61, d.voltage, 0.01)
        assertEquals(16.75, d.speed, 0.01)
        assertEquals(2584346637.0, d.distance, 0.1)
        assertEquals(0.21, d.current, 0.01)
        assertEquals(33.0, d.temperature, 0.01)
        assertFalse(d.isCharging)
        assertEquals(0, d.batteryLevel)
        assertNull(d.cellVoltages)
    }

    @Test
    fun testFragmentedFrameAcrossTwoDecodeCalls() = runBlocking {
        val protocol = KingsongProtocol()
        val hexA = "AA55E52F8B060D000A9A"
        val hexB = "1500E40C02E0A9145A5A"
        val a = ByteUtils.hexToBytes(hexA)
        val b = ByteUtils.hexToBytes(hexB)

        val collector = async { collectN(protocol.dataFlow, 1, 1000L) }
        protocol.decode(a)
        protocol.decode(b)

        val items = withTimeout(1000L) { collector.await() }
        assertEquals(1, items.size)
        val d = items[0]
        assertEquals(122.61, d.voltage, 0.01)
        assertEquals(16.75, d.speed, 0.01)
        assertEquals(2584346637.0, d.distance, 0.1)
    }

    @Test
    fun testTwoFramesInOnePayload() = runBlocking {
        val protocol = KingsongProtocol()
        val hex = "AA55E52F8B060D000A9A1500E40C02E0A9145A5A" + "AA55E52F8B060D000A9A1500E40C02E0A9145A5A"
        val payload = ByteUtils.hexToBytes(hex)

        val collector = async { collectN(protocol.dataFlow, 2, 1000L) }
        protocol.decode(payload)

        val items = withTimeout(1000L) { collector.await() }
        assertEquals(2, items.size)
        assertEquals(122.61, items[0].voltage, 0.01)
        assertEquals(122.61, items[1].voltage, 0.01)
    }

    @Test
    fun testLeadingNoiseAndResync() = runBlocking {
        val protocol = KingsongProtocol()
        val hex = "00FFAA55" + "E52F8B060D000A9A1500E40C02E0A9145A5A"
        val payload = ByteUtils.hexToBytes(hex)

        val collector = async { collectN(protocol.dataFlow, 1, 1000L) }
        protocol.decode(payload)

        val items = withTimeout(1000L) { collector.await() }
        assertEquals(1, items.size)
        val d = items[0]
        assertEquals(122.61, d.voltage, 0.01)
    }

    @Test
    fun testTruncatedThenCompletedFrame() = runBlocking {
        val protocol = KingsongProtocol()
        val part1 = ByteUtils.hexToBytes("AA55E52F8B060D00") // too short
        val part2 = ByteUtils.hexToBytes("0A9A1500E40C02E0A9145A5A")

        val collector = async { collectN(protocol.dataFlow, 1, 1000L) }
        protocol.decode(part1)
        // ensure no immediate emission
        val none = withTimeoutOrNull(200L) { collector.await() }
        // collector may time out; we expect no emission yet
        // Now send rest
        protocol.decode(part2)
        val items = withTimeout(1000L) { collector.await() }
        assertEquals(1, items.size)
        assertEquals(122.61, items[0].voltage, 0.01)
    }

    @Test
    fun testHeaderVariant55AA() = runBlocking {
        val protocol = KingsongProtocol()
        val hex = "55AAE52F8B060D000A9A1500E40C02E0A9145A5A"
        val payload = ByteUtils.hexToBytes(hex)

        val collector = async { collectN(protocol.dataFlow, 1, 1000L) }
        protocol.decode(payload)

        val items = withTimeout(1000L) { collector.await() }
        assertEquals(1, items.size)
        assertEquals(122.61, items[0].voltage, 0.01)
    }
}

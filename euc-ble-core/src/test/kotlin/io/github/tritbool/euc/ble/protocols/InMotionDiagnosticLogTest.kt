package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucCrc32
import io.github.tritbool.euc.ble.core.EucDateTime
import io.github.tritbool.euc.ble.models.DiagnosticLogFailure
import io.github.tritbool.euc.ble.models.DiagnosticLogState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.zip.GZIPInputStream

class InMotionDiagnosticLogTest {
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 100_000L
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }

    private val v9Identity = ByteUtils.hexToBytes(
        "aaaa1662210286010001413134323139313136303031383341340000000000" +
            "020c010121320008010100090222260004071900080223100004010a00" +
            "0201240e00000001000101250e000000010001012f06000601010000" +
            "0045bd57a9210100220000230000ea"
    )
    private val openRequest = hex("aaaa13100221048100323a2f6c6f672e62696e00ad")
    private val closeRequest = hex("aaaa13050221048400b5")
    private val timerId = "inmotion.diagnostic-log"

    private fun hex(value: String) = ByteUtils.hexToBytes(value)
    private fun key(bytes: ByteArray) = ByteUtils.bytesToHex(bytes, "").lowercase()

    /** Reads use the sequence number; other file operations (byte 7 in both directions) are unique. */
    private fun exchangeKey(frame: ByteArray): String {
        if (frame[2] != 0x53.toByte()) return "op:" + (frame[7].toInt() and 0xFF)
        // The app sends 127 as `80 7F` while the wheel echoes `7F`: compare decoded values.
        var index = 3
        var sequence = 0
        while (true) {
            val b = frame[index++].toInt() and 0xFF
            sequence = (sequence shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) return "seq:$sequence"
        }
    }

    private fun v9Engine() = InMotionSyncProtocol(clock).also {
        it.onNotification(v9Identity)
        it.drainOutputs()
    }

    private fun InMotionSyncProtocol.requestLog() =
        createCommand(CommandType.REQUEST_DIAGNOSTIC_LOG, CommandValue.None)

    private fun List<ProtocolOutput>.writes() = filterIsInstance<ProtocolOutput.Write>().map { it.payload }

    /** Wheel-side frame: `AA AA flag [seq] len 21 payload checksum`, escaped like the wheel does. */
    private fun wheelFrame(flag: Int, sequence: ByteArray, payload: ByteArray): ByteArray {
        val body = byteArrayOf(flag.toByte()) + sequence + (payload.size + 1).toByte() + 0x21.toByte() + payload
        val checksum = body.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }.toByte()
        val out = mutableListOf(0xAA.toByte(), 0xAA.toByte())
        for (b in body + checksum) {
            if (b == 0xAA.toByte() || b == 0xA5.toByte()) out += 0xA5.toByte()
            out += b
        }
        return out.toByteArray()
    }

    private fun le32(value: Long) = ByteArray(4) { i -> (value ushr (8 * i)).toByte() }

    private fun openResponse(size: Long, status: Int = 0) =
        wheelFrame(0x13, byteArrayOf(), byteArrayOf(0x02, 0x84.toByte(), 0x81.toByte(), 0, status.toByte()) + le32(size) + le32(0))

    private fun readResponse(sequence: Int, offset: Long, data: ByteArray) =
        wheelFrame(0x53, byteArrayOf(sequence.toByte()), byteArrayOf(0x02, 0x84.toByte(), 0x02, 0, 0) + le32(offset) + data)

    private fun verifyResponse(size: Long, crc: Long) =
        wheelFrame(0x13, byteArrayOf(), byteArrayOf(0x02, 0x84.toByte(), 0x03, 0, 0) + le32(size) + le32(size) + le32(crc))

    @Test
    fun `replays the complete V9 diagnostic download byte for byte`() {
        val lines = GZIPInputStream(
            javaClass.getResourceAsStream("/diagnostics/inmotion_v9_diagnostic_log.txt.gz")!!
        ).bufferedReader().readLines().filter { it.isNotBlank() }
        val expectedWrites = mutableListOf<String>()
        val responses = mutableMapOf<String, ByteArray>()
        var lastTx: String? = null
        for (line in lines) {
            val (direction, value) = line.split(' ')
            if (direction == "TX") {
                // The official app retries identical requests; the engine never needed to.
                if (value != lastTx) expectedWrites += value
                lastTx = value
            } else {
                // Late duplicate answers to retries exist, so pair by sequence/operation.
                responses.putIfAbsent(exchangeKey(hex(value)), hex(value))
            }
        }

        val engine = v9Engine()
        val pending = ArrayDeque(listOf(engine.requestLog()))
        val written = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val request = pending.removeFirst()
            written += key(request)
            engine.onCommandSubmitted(request)
            responses[exchangeKey(request)]?.let(engine::onNotification)
            pending.addAll(engine.drainOutputs().writes())
        }

        assertEquals(expectedWrites, written)
        val transfer = engine.getDiagnosticLogTransfer()!!
        assertEquals(DiagnosticLogState.COMPLETED, transfer.state)
        assertEquals("2:/log.bin", transfer.filePath)
        assertEquals(524_288, transfer.totalBytes)
        assertEquals(524_288, transfer.receivedBytes)
        assertEquals(0xD762A529L, transfer.crc32)
        assertEquals(1.0, transfer.progress)
        val data = transfer.copyData()!!
        assertEquals(0xD762A529L, EucCrc32.calculate(data))
        data[0] = (data[0] + 1).toByte()
        assertEquals(0xD762A529L, EucCrc32.calculate(engine.getDiagnosticLogTransfer()!!.copyData()!!))
    }

    @Test
    fun `fragmented escaped chunks complete and stale chunks are ignored`() {
        val engine = v9Engine()
        assertArrayEquals(openRequest, engine.requestLog())
        assertTrue(engine.requestLog().isEmpty(), "a second request must not restart an active transfer")
        engine.drainOutputs()

        engine.onNotification(openResponse(6))
        assertArrayEquals(hex("aaaa53000a022104020000000000067a"), engine.drainOutputs().writes().single())
        assertEquals(DiagnosticLogState.READING, engine.getDiagnosticLogTransfer()!!.state)

        engine.onNotification(readResponse(0, 4, byteArrayOf(1)))
        assertTrue(engine.drainOutputs().writes().isEmpty())
        assertEquals(0, engine.getDiagnosticLogTransfer()!!.receivedBytes)

        val data = byteArrayOf(0xAA.toByte(), 0xA5.toByte(), 0x55, 0xAA.toByte(), 0x00, 0xA5.toByte())
        readResponse(0, 0, data).toList().chunked(3).forEach { engine.onNotification(it.toByteArray()) }
        val verify = engine.drainOutputs().writes().single()
        assertArrayEquals(hex("aaaa130902210403000600000038"), verify)

        engine.onNotification(verifyResponse(6, EucCrc32.calculate(data)))
        assertArrayEquals(closeRequest, engine.drainOutputs().writes().single())
        val transfer = engine.getDiagnosticLogTransfer()!!
        assertEquals(DiagnosticLogState.COMPLETED, transfer.state)
        assertArrayEquals(data, transfer.copyData())
    }

    @Test
    fun `CRC mismatch fails without exposing data`() {
        val engine = v9Engine()
        engine.requestLog()
        engine.onNotification(openResponse(2))
        engine.onNotification(readResponse(0, 0, byteArrayOf(1, 2)))
        engine.drainOutputs()
        engine.onNotification(verifyResponse(2, 0x12345678))

        assertArrayEquals(closeRequest, engine.drainOutputs().writes().single())
        val transfer = engine.getDiagnosticLogTransfer()!!
        assertEquals(DiagnosticLogState.FAILED, transfer.state)
        assertEquals(DiagnosticLogFailure.CRC_MISMATCH, transfer.failure)
        assertNull(transfer.copyData())
    }

    @Test
    fun `unanswered requests are retried then time out`() {
        val engine = v9Engine()
        val request = engine.requestLog()
        assertEquals(
            listOf(ProtocolOutput.StartTimer(timerId, 5_000L)),
            engine.drainOutputs().filterIsInstance<ProtocolOutput.StartTimer>()
        )
        engine.onCommandSubmitted(request)
        assertEquals(listOf(ProtocolOutput.StartTimer(timerId, 3_000L)), engine.drainOutputs())

        repeat(5) {
            engine.onTimer(timerId)
            assertArrayEquals(request, engine.drainOutputs().writes().single())
        }
        engine.onTimer(timerId)

        assertArrayEquals(closeRequest, engine.drainOutputs().writes().single())
        val transfer = engine.getDiagnosticLogTransfer()!!
        assertEquals(DiagnosticLogFailure.TIMEOUT, transfer.failure)
        assertTrue(transfer.state.isTerminal)
    }

    @Test
    fun `rejected open is retried once before failing`() {
        val engine = v9Engine()
        engine.requestLog()
        engine.drainOutputs()
        engine.onNotification(openResponse(0, status = 3))
        assertEquals(listOf(key(closeRequest), key(openRequest)), engine.drainOutputs().writes().map(::key))
        engine.onNotification(openResponse(0, status = 3))
        assertEquals(listOf(key(closeRequest)), engine.drainOutputs().writes().map(::key))
        assertEquals(DiagnosticLogFailure.OPEN_REJECTED, engine.getDiagnosticLogTransfer()!!.failure)
    }

    @Test
    fun `cancel, wheel abort and session reset end the transfer`() {
        val engine = v9Engine()
        engine.requestLog()
        assertArrayEquals(closeRequest, engine.createCommand(CommandType.CANCEL_DIAGNOSTIC_LOG, CommandValue.None))
        assertEquals(DiagnosticLogState.CANCELLED, engine.getDiagnosticLogTransfer()!!.state)
        assertTrue(engine.createCommand(CommandType.CANCEL_DIAGNOSTIC_LOG, CommandValue.None).isEmpty())

        engine.requestLog()
        engine.onNotification(openResponse(1024))
        engine.drainOutputs()
        // Unsolicited notice seen after the interrupted P6 download.
        engine.onNotification(hex("aaaa13052100838400" + "30"))
        assertTrue(engine.drainOutputs().writes().isEmpty())
        assertEquals(DiagnosticLogFailure.WHEEL_ABORTED, engine.getDiagnosticLogTransfer()!!.failure)

        engine.requestLog()
        engine.resetSession()
        assertEquals(DiagnosticLogFailure.SESSION_RESET, engine.getDiagnosticLogTransfer()!!.failure)
        assertNull(engine.pollOutput())
    }

    @Test
    fun `download is only offered to identified P6 and V9 wheels`() {
        val engine = InMotionSyncProtocol(clock)
        assertTrue(engine.requestLog().isEmpty())
        assertNull(engine.getDiagnosticLogTransfer())
        assertTrue(CommandType.REQUEST_DIAGNOSTIC_LOG in engine.supportedCommandTypes)
    }
}

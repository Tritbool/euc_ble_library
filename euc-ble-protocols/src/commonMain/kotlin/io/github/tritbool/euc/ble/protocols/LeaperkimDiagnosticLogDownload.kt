/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 0.1.3 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucCrc32
import io.github.tritbool.euc.ble.models.DiagnosticLogFailure
import io.github.tritbool.euc.ble.models.DiagnosticLogState
import io.github.tritbool.euc.ble.models.DiagnosticLogTransfer

/**
 * Download of the LeaperKim black-box event log through the regular `DC 5A 5C` telemetry stream.
 *
 * The wheel carries no side channel for its log: the read command arms a cursor and the
 * scheduler swaps the frame extension (byte 46 and beyond) for log pages while the main telemetry
 * body keeps updating. Wire formats are byte-identical to the official LeaperKim app
 * (`BtManager.CMD_READ_LOG` / `CMD_READ_LOG_NEW`):
 *
 * - start / stop: a `LkAp` frame and its `LdAp` companion, `0x14`-class, value `0x01` to arm
 *   the cursor (reset to the first record), `0xFF` to park it again
 *   (`4c 6b 41 70 14 01 80 80 80 80 80 80 80 80 80 01 + CRC32 BE`,
 *   `4c 64 41 70 14 01 00 80 80 80 80 80 80 80 80 01 + CRC32 BE`).
 * - page 33 (current): one record per frame; the wheel signals the end once with the cursor
 *   parked at index 4095.
 * - page 32 (legacy): up to three records per frame, no text, end at index 255.
 *
 * Long frames carry the trailing CRC32 the stream parser already validated, so every record
 * is received CRC-verified; the wheel reports no whole-file checksum.
 */
internal class LeaperkimDiagnosticLogDownload(private val outbox: ProtocolOutbox) {
    private var state: DiagnosticLogState? = null
    private var failure: DiagnosticLogFailure? = null
    private val recordsByIndex = mutableMapOf<Int, LogRecord>()
    private var receivedBytes = 0

    val isActive: Boolean get() = state?.isTerminal == false

    fun snapshot(): DiagnosticLogTransfer? = state?.let {
        DiagnosticLogTransfer(
            it, FILE_PATH, null, receivedBytes, failure, null,
            if (it == DiagnosticLogState.COMPLETED) renderRecords().encodeToByteArray() else null
        )
    }

    /** Returns the start request, or an empty array while a transfer is already active. */
    fun start(): ByteArray {
        if (isActive) return byteArrayOf()
        state = DiagnosticLogState.OPENING
        failure = null
        recordsByIndex.clear()
        receivedBytes = 0
        outbox.push(ProtocolOutput.DebugLog(TAG, "Arming the event-log cursor"))
        outbox.push(ProtocolOutput.StartTimer(TIMER_ID, NO_ANSWER_TIMEOUT_MS))
        return startRequest()
    }

    /** Returns the stop request, or an empty array when no transfer is active. */
    fun cancel(): ByteArray {
        if (!isActive) return byteArrayOf()
        state = DiagnosticLogState.CANCELLED
        release()
        outbox.push(ProtocolOutput.DebugLog(TAG, "Cancelled after ${recordsByIndex.size} records"))
        return stopRequest()
    }

    /** Feeds one complete, CRC-validated frame while a transfer is active. */
    fun onFrame(frame: ByteArray) {
        if (!isActive) return
        val page33 = parsePage33Record(frame)
        if (page33 != null) consume(page33) else page32Records(frame).forEach(::consume)
    }

    /** Returns `true` when [id] belongs to this transfer. */
    fun onTimer(id: String): Boolean {
        if (id != TIMER_ID || !isActive) return false
        if (recordsByIndex.isEmpty()) {
            fail(DiagnosticLogFailure.TIMEOUT)
        } else {
            // The wheel does not always send the end marker; silence after records is the end.
            complete()
        }
        return true
    }

    fun reset() {
        if (isActive) {
            state = DiagnosticLogState.FAILED
            failure = DiagnosticLogFailure.SESSION_RESET
            outbox.push(ProtocolOutput.CancelTimer(TIMER_ID))
        }
        recordsByIndex.clear()
        receivedBytes = 0
    }

    private fun consume(record: LogRecord) {
        outbox.push(ProtocolOutput.CancelTimer(TIMER_ID))
        outbox.push(ProtocolOutput.StartTimer(TIMER_ID, END_OF_LOG_TIMEOUT_MS))
        if (state == DiagnosticLogState.OPENING) state = DiagnosticLogState.READING
        if (record.isEndMarker) {
            complete()
            return
        }
        if (recordsByIndex.put(record.index, record) == null) {
            receivedBytes += record.wireBytes
        }
    }

    private fun complete() {
        state = DiagnosticLogState.COMPLETED
        outbox.push(ProtocolOutput.CancelTimer(TIMER_ID))
        outbox.push(ProtocolOutput.DebugLog(TAG, "Collected ${recordsByIndex.size} records"))
        outbox.push(ProtocolOutput.Write(stopRequest()))
    }

    private fun fail(reason: DiagnosticLogFailure) {
        state = DiagnosticLogState.FAILED
        failure = reason
        outbox.push(ProtocolOutput.CancelTimer(TIMER_ID))
        outbox.push(ProtocolOutput.DebugLog(TAG, "Download failed: $reason"))
        outbox.push(ProtocolOutput.Write(stopRequest()))
    }

    private fun release() {
        outbox.push(ProtocolOutput.CancelTimer(TIMER_ID))
    }

    private fun renderRecords(): String = buildString {
        recordsByIndex.keys.sorted().forEach { key ->
            val record = recordsByIndex.getValue(key)
            append('#').append(record.index)
            record.totalCount?.let { append('/').append(it) }
            append(" time=").append(record.unixSeconds ?: record.timeBytes.joinToString("."))
            append(" code=").append(record.code)
            append(" values=").append(record.values.joinToString(",", "[", "]"))
            if (record.text.isNotEmpty()) append(" text=").append(record.text)
            append('\n')
        }
    }

    internal companion object {
        const val FILE_PATH = "leaperkim://event-log"
        const val TIMER_ID = "leaperkim.diagnostic-log"
        const val NO_ANSWER_TIMEOUT_MS = 6_000L
        const val END_OF_LOG_TIMEOUT_MS = 3_000L
        private const val LOG_PAGE_ID = 33
        private const val LOG_PAGE_32_ID = 32
        private const val PAGE_ID_OFFSET = 46
        private const val END_INDEX_PAGE33 = 4095
        private const val END_INDEX_PAGE32 = 255
        private const val TAG = "LeaperkimDiagnosticLog"

        /**
         * Value frames for the log cursor, both spellings the official app sends through
         * `sendBytesDataCombine`: the legacy `LkAp` frame and the modern `LdAp` companion,
         * each with its own CRC32-BE trailer.
         */
        fun startRequest(): ByteArray = logRequest(0x01)
        fun stopRequest(): ByteArray = logRequest(0xFF.toByte())

        private fun logRequest(value: Byte): ByteArray =
            vendorFrame(LKAP, byteArrayOf(0x01) + PLACEHOLDERS_9, value) +
                vendorFrame(LDAP, byteArrayOf(0x01, 0x00) + PLACEHOLDERS_8, value)

        private val PLACEHOLDERS_9 = ByteArray(9) { 0x80.toByte() }
        private val PLACEHOLDERS_8 = ByteArray(8) { 0x80.toByte() }

        /**
         * Log pages ride the standard vendor frame layout: `[magic 4][len 1][payload 11]
         * [CRC32 BE 4]`, total length 20 (`0x14`), CRC over everything before the trailer.
         */
        private fun vendorFrame(magic: ByteArray, payloadHead: ByteArray, value: Byte): ByteArray {
            val out = ByteArray(20)
            magic.copyInto(out, 0)
            out[4] = 0x14
            payloadHead.copyInto(out, 5)
            out[5 + payloadHead.size] = value
            val crc = EucCrc32.calculate(out, 0, 16).toInt()
            out[16] = ((crc ushr 24) and 0xFF).toByte()
            out[17] = ((crc ushr 16) and 0xFF).toByte()
            out[18] = ((crc ushr 8) and 0xFF).toByte()
            out[19] = (crc and 0xFF).toByte()
            return out
        }

        private val LKAP = byteArrayOf(0x4C, 0x6B, 0x41, 0x70)
        private val LDAP = byteArrayOf(0x4C, 0x64, 0x41, 0x70)

        /** Page 33: one record per frame, guarded the way the wheel's own packer guards it. */
        internal fun parsePage33Record(frame: ByteArray): LogRecord? {
            if (frame.size <= PAGE_ID_OFFSET) return null
            if (frame[PAGE_ID_OFFSET].toInt() and 0xFF != LOG_PAGE_ID) return null
            if (frame.size - 4 <= 56) return null
            fun byte(index: Int): Int = frame[index].toInt() and 0xFF

            val totalCount = byte(47) * 16 + byte(48) / 16
            val index = (byte(48) % 16) * 256 + byte(49)
            val timeBytes = listOf(byte(50), byte(51), byte(52), byte(53))
            val code = (byte(54) shl 8) or byte(55)
            val declaredCount = byte(56)

            val values = ArrayList<Long>(declaredCount.coerceAtMost(8))
            for (i in 0 until declaredCount.coerceAtMost(8)) {
                val at = 57 + i * 4
                if (at + 3 >= frame.size) break
                var value = (byte(at).toLong() shl 24) or (byte(at + 1).toLong() shl 16) or
                    (byte(at + 2).toLong() shl 8) or byte(at + 3).toLong()
                if (value > 2147483648L) value -= 4294967296L
                values.add(value)
            }

            val builder = StringBuilder()
            var at = 57 + values.size * 4
            while (at < frame.size) {
                val raw = byte(at)
                if (raw == 0) break
                // The event names are ASCII; other bytes are kept as escapes instead of
                // silently mangled, mirroring the official app's debug screen.
                if (raw in 0x20..0x7E) builder.append(raw.toChar()) else {
                    builder.append("\\x").append(raw.toString(16).uppercase().padStart(2, '0'))
                }
                at++
            }

            return LogRecord(
                index, totalCount, timeBytes, code, values, builder.toString(),
                isEndMarker = index == END_INDEX_PAGE33,
                wireBytes = frame.size - 4
            )
        }

        /** Page 32 (legacy): up to three records per frame, no total count and no text. */
        internal fun page32Records(frame: ByteArray): List<LogRecord> {
            if (frame.size <= PAGE_ID_OFFSET) return emptyList()
            if (frame[PAGE_ID_OFFSET].toInt() and 0xFF != LOG_PAGE_32_ID) return emptyList()
            if (frame.size - 4 <= 80) return emptyList()
            fun byte(index: Int): Int = frame[index].toInt() and 0xFF

            val first = byte(47)
            return (0 until 3).mapNotNull { slot ->
                val index = first + slot
                if (slot > 0 && index > END_INDEX_PAGE32) return@mapNotNull null
                val base = 48 + slot * 11
                LogRecord(
                    index = index,
                    totalCount = null,
                    timeBytes = listOf(byte(base + 2), byte(base + 3), byte(base + 4), byte(base + 5)),
                    code = (byte(base) shl 8) or byte(base + 1),
                    values = (base + 6..base + 10).map { frame[it].toLong() },
                    text = "",
                    isEndMarker = index == END_INDEX_PAGE32,
                    wireBytes = 11
                )
            }
        }
    }
}

internal data class LogRecord(
    val index: Int,
    val totalCount: Int?,
    val timeBytes: List<Int>,
    val code: Int,
    val values: List<Long>,
    val text: String,
    val isEndMarker: Boolean,
    val wireBytes: Int,
) {
    val unixSeconds: Long?
        get() = timeBytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
            .takeIf { it != 0L }
}

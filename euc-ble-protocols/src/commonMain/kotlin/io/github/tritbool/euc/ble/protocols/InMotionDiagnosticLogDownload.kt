/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 1.1.0 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucCrc32
import io.github.tritbool.euc.ble.models.DiagnosticLogFailure
import io.github.tritbool.euc.ble.models.DiagnosticLogState
import io.github.tritbool.euc.ble.models.DiagnosticLogTransfer

/**
 * Synchronous download of the InMotion diagnostic log through the main-MCU file service
 * (address 0x21, service 0x04) used by the official app. Wire format verified byte-for-byte
 * against a complete V9 download and an interrupted P6 download:
 *
 * - open:   `13 <len> 02 21 04 81 00 <ASCII path> 00`
 *   → `13 <len> 21 02 84 81 00 <status> <size LE32> …`
 * - read:   `53 <seq> <len> 02 21 04 02 00 <offset LE32> <count>`
 *   → `53 <seq> <len> 21 02 84 02 00 <status> <offset LE32> <data>` (144-byte chunks)
 * - verify: `13 <len> 02 21 04 03 00 <size LE32>`
 *   → `13 <len> 21 02 84 03 00 <status> <size LE32> <size LE32> <CRC-32 LE32>`
 * - close:  `13 <len> 02 21 04 84 00` → `13 <len> 21 02 84 84 00 <status>`
 *
 * Flag bit 0x40 adds a big-endian 7-bit sequence number (continuation bit 0x80) before the
 * length byte. Every byte after `AA AA`, including the checksum, uses the 0xA5 escape.
 * One request is in flight at a time; timeouts and retries use protocol timers.
 */
internal class InMotionDiagnosticLogDownload(private val outbox: ProtocolOutbox) {
    private var state: DiagnosticLogState? = null
    private var failure: DiagnosticLogFailure? = null
    private var buffer: ByteArray? = null
    private var totalBytes: Int? = null
    private var receivedBytes = 0
    private var sequence = 0
    private var crc32: Long? = null
    private var pendingRequest: ByteArray? = null
    private var pendingOperation = 0
    private var pendingCount = 0
    private var attempts = 0
    private var reopened = false

    val isActive: Boolean get() = state?.isTerminal == false

    fun snapshot(): DiagnosticLogTransfer? = state?.let {
        DiagnosticLogTransfer(it, FILE_PATH, totalBytes, receivedBytes, failure, crc32,
            if (it == DiagnosticLogState.COMPLETED) buffer else null)
    }

    /** Returns the open request, or an empty array while a transfer is already active. */
    fun start(): ByteArray {
        if (isActive) return byteArrayOf()
        state = DiagnosticLogState.OPENING
        failure = null
        buffer = null
        totalBytes = null
        receivedBytes = 0
        sequence = 0
        crc32 = null
        reopened = false
        outbox.push(ProtocolOutput.DebugLog(TAG, "Opening $FILE_PATH"))
        return request(OP_OPEN, openRequest(), write = false)
    }

    /** Returns the close request, or an empty array when no transfer is active. */
    fun cancel(): ByteArray {
        if (!isActive) return byteArrayOf()
        state = DiagnosticLogState.CANCELLED
        release()
        return closeRequest()
    }

    fun onSubmitted(payload: ByteArray) {
        val request = pendingRequest ?: return
        if (isActive && request.contentEquals(payload)) {
            outbox.push(ProtocolOutput.StartTimer(TIMER_ID, responseTimeoutMs(pendingOperation)))
        }
    }

    /** Returns `true` when [id] belongs to this transfer. */
    fun onTimer(id: String): Boolean {
        if (id != TIMER_ID) return false
        val request = pendingRequest
        if (!isActive || request == null) return true
        if (attempts < MAX_RETRIES) {
            attempts++
            outbox.push(ProtocolOutput.DebugLog(TAG, "Retrying operation $pendingOperation (attempt $attempts)"))
            outbox.push(ProtocolOutput.Write(request.copyOf()))
            outbox.push(ProtocolOutput.StartTimer(TIMER_ID, SUBMISSION_TIMEOUT_MS))
        } else {
            fail(DiagnosticLogFailure.TIMEOUT, sendClose = true)
        }
        return true
    }

    /** Handles a file-service frame (`command` = 0x21); returns `true` when it was one. */
    fun onResponse(command: Int, payload: ByteArray): Boolean {
        if (command != MCU_ADDRESS || payload.size < 3) return false
        if (payload.u8(0) == 0x00 && payload.u8(1) == WHEEL_NOTICE && payload.u8(2) == OP_CLOSE) {
            // Observed after an interrupted P6 download: the wheel closed the file session.
            if (isActive) fail(DiagnosticLogFailure.WHEEL_ABORTED, sendClose = false)
            return true
        }
        if (payload.size < 5 || payload.u8(0) != ROUTING_QUERY || payload.u8(1) != FILE_SERVICE_RESPONSE) {
            return false
        }
        val operation = payload.u8(2)
        // Stale, duplicated (after a retry) or close acknowledgements need no action.
        if (!isActive || operation != pendingOperation) return true
        val status = payload.u8(4)
        when (operation) {
            OP_OPEN -> onOpened(status, payload)
            OP_READ -> onChunk(status, payload)
            OP_VERIFY -> onVerified(status, payload)
        }
        return true
    }

    fun reset() {
        if (isActive) {
            state = DiagnosticLogState.FAILED
            failure = DiagnosticLogFailure.SESSION_RESET
            buffer = null
        }
        pendingRequest = null
    }

    private fun onOpened(status: Int, payload: ByteArray) {
        if (status != 0) {
            // The official app closes and reopens once when the wheel rejects an open.
            if (reopened) return fail(DiagnosticLogFailure.OPEN_REJECTED, sendClose = true)
            reopened = true
            outbox.push(ProtocolOutput.Write(closeRequest()))
            request(OP_OPEN, openRequest())
            return
        }
        val size = payload.le32(5) ?: return
        if (size <= 0 || size > MAX_FILE_BYTES) return fail(DiagnosticLogFailure.INVALID_SIZE, sendClose = true)
        totalBytes = size.toInt()
        buffer = ByteArray(size.toInt())
        state = DiagnosticLogState.READING
        requestNextChunk()
    }

    private fun onChunk(status: Int, payload: ByteArray) {
        val data = buffer ?: return
        if (status != 0 || payload.le32(5) != receivedBytes.toLong()) return
        val length = payload.size - CHUNK_HEADER_SIZE
        if (length <= 0 || length > pendingCount) return
        payload.copyInto(data, receivedBytes, CHUNK_HEADER_SIZE, payload.size)
        receivedBytes += length
        sequence++
        if (receivedBytes < data.size) return requestNextChunk()
        state = DiagnosticLogState.VERIFYING
        request(OP_VERIFY, fileRequest(OP_VERIFY, le32Bytes(data.size)))
    }

    private fun onVerified(status: Int, payload: ByteArray) {
        val data = buffer ?: return
        // V9 answers `<size LE32> <size LE32> <CRC-32 LE32>`; the CRC is the authoritative check.
        if (status != 0) return
        val reported = payload.le32(13) ?: return
        crc32 = reported
        if (reported != EucCrc32.calculate(data)) return fail(DiagnosticLogFailure.CRC_MISMATCH, sendClose = true)
        state = DiagnosticLogState.COMPLETED
        pendingRequest = null
        outbox.push(ProtocolOutput.CancelTimer(TIMER_ID))
        outbox.push(ProtocolOutput.DebugLog(TAG, "Downloaded ${data.size} bytes, CRC-32 verified"))
        outbox.push(ProtocolOutput.Write(closeRequest()))
    }

    private fun requestNextChunk() {
        val total = totalBytes ?: return
        pendingCount = minOf(READ_REQUEST_BYTES, total - receivedBytes)
        request(OP_READ, readRequest(sequence, receivedBytes, pendingCount))
    }

    private fun request(operation: Int, frame: ByteArray, write: Boolean = true): ByteArray {
        pendingOperation = operation
        pendingRequest = frame
        attempts = 0
        if (write) outbox.push(ProtocolOutput.Write(frame.copyOf()))
        // Covers queueing until GATT acceptance re-arms the shorter response timeout.
        outbox.push(ProtocolOutput.StartTimer(TIMER_ID, SUBMISSION_TIMEOUT_MS))
        return frame.copyOf()
    }

    private fun fail(reason: DiagnosticLogFailure, sendClose: Boolean) {
        state = DiagnosticLogState.FAILED
        failure = reason
        release()
        outbox.push(ProtocolOutput.DebugLog(TAG, "Download failed: $reason after $receivedBytes bytes"))
        if (sendClose) outbox.push(ProtocolOutput.Write(closeRequest()))
    }

    private fun release() {
        buffer = null
        pendingRequest = null
        outbox.push(ProtocolOutput.CancelTimer(TIMER_ID))
    }

    internal companion object {
        const val FILE_PATH = "2:/log.bin"
        const val TIMER_ID = "inmotion.diagnostic-log"
        const val OPEN_RESPONSE_TIMEOUT_MS = 3_000L
        const val RESPONSE_TIMEOUT_MS = 1_000L
        const val SUBMISSION_TIMEOUT_MS = 5_000L
        const val MAX_RETRIES = 5
        const val MAX_FILE_BYTES = 16L * 1024 * 1024

        const val FILE_FLAG = 0x13
        const val SEQUENCE_FLAG_BIT = 0x40
        const val SEQUENCED_FILE_FLAG = FILE_FLAG or SEQUENCE_FLAG_BIT
        private const val HEADER = 0xAA
        private const val ESCAPE = 0xA5
        private const val COMMAND_MAIN_INFO = 0x02
        private const val MCU_ADDRESS = 0x21
        private const val FILE_SERVICE = 0x04
        private const val FILE_SERVICE_RESPONSE = FILE_SERVICE or 0x80
        private const val ROUTING_QUERY = 0x02
        private const val WHEEL_NOTICE = 0x83
        private const val FILE_HANDLE = 0x00
        private const val OP_OPEN = 0x81
        private const val OP_READ = 0x02
        private const val OP_VERIFY = 0x03
        private const val OP_CLOSE = 0x84
        // The app asks for 0xB0 bytes; the wheels answer with 144-byte chunks.
        private const val READ_REQUEST_BYTES = 0xB0
        private const val CHUNK_HEADER_SIZE = 9
        private const val TAG = "InMotionDiagnosticLog"

        private fun responseTimeoutMs(operation: Int): Long =
            if (operation == OP_OPEN) OPEN_RESPONSE_TIMEOUT_MS else RESPONSE_TIMEOUT_MS

        fun openRequest(): ByteArray = fileRequest(OP_OPEN, FILE_PATH.encodeToByteArray() + 0)

        fun readRequest(sequence: Int, offset: Int, count: Int): ByteArray =
            fileRequest(OP_READ, le32Bytes(offset) + count.toByte(), sequence)

        fun closeRequest(): ByteArray = fileRequest(OP_CLOSE, byteArrayOf())

        private fun fileRequest(operation: Int, args: ByteArray, sequence: Int? = null): ByteArray =
            encodeFrame(
                byteArrayOf(MCU_ADDRESS.toByte(), FILE_SERVICE.toByte(), operation.toByte(), FILE_HANDLE.toByte()) + args,
                sequence
            )

        private fun encodeFrame(data: ByteArray, sequence: Int?): ByteArray {
            val length = data.size + 1
            require(length <= 0xFF) { "InMotion frame too long" }
            val body = ArrayList<Byte>(data.size + 8)
            body += (if (sequence == null) FILE_FLAG else SEQUENCED_FILE_FLAG).toByte()
            if (sequence != null) body += encodeSequence(sequence).toList()
            body += length.toByte()
            body += COMMAND_MAIN_INFO.toByte()
            body += data.toList()
            val checksum = body.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }
            val out = ArrayList<Byte>(body.size * 2 + 4)
            out += HEADER.toByte()
            out += HEADER.toByte()
            for (b in body + checksum.toByte()) {
                val value = b.toInt() and 0xFF
                if (value == HEADER || value == ESCAPE) out += ESCAPE.toByte()
                out += b
            }
            return out.toByteArray()
        }

        /** 127 is sent as `80 7F` by the official app; smaller values use one byte. */
        fun encodeSequence(sequence: Int): ByteArray {
            require(sequence >= 0)
            if (sequence < 0x7F) return byteArrayOf(sequence.toByte())
            val groups = ArrayList<Int>()
            var remaining = sequence
            while (remaining > 0) {
                groups.add(0, remaining and 0x7F)
                remaining = remaining ushr 7
            }
            if (groups.size == 1) groups.add(0, 0)
            return ByteArray(groups.size) { i ->
                (if (i < groups.lastIndex) groups[i] or 0x80 else groups[i]).toByte()
            }
        }

        private fun le32Bytes(value: Int): ByteArray =
            ByteArray(4) { i -> (value ushr (8 * i)).toByte() }

        private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

        private fun ByteArray.le32(index: Int): Long? {
            if (index < 0 || index + 4 > size) return null
            var value = 0L
            for (i in 3 downTo 0) value = (value shl 8) or u8(index + i).toLong()
            return value
        }
    }
}

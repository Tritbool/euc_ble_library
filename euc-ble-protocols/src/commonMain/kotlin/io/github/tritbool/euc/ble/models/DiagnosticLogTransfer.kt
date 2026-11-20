/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 1.1.0 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.models

/** Lifecycle of a wheel diagnostic-log download. */
enum class DiagnosticLogState {
    /** Open request sent; waiting for the wheel to report the file size. */
    OPENING,

    /** Reading file chunks. */
    READING,

    /** Every byte received; waiting for the wheel-reported CRC-32. */
    VERIFYING,

    /** Every byte received and the wheel-reported CRC-32 matched. */
    COMPLETED,

    /** The transfer stopped; see [DiagnosticLogTransfer.failure]. No partial data is exposed. */
    FAILED,

    /** The client cancelled the transfer. */
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/** Why a diagnostic-log download failed. */
enum class DiagnosticLogFailure {
    /** The wheel refused to open the log file. */
    OPEN_REJECTED,

    /** The wheel reported an empty or implausibly large file. */
    INVALID_SIZE,

    /** A request stayed unanswered after every retry. */
    TIMEOUT,

    /** The received bytes do not match the wheel-reported CRC-32. */
    CRC_MISMATCH,

    /** The wheel closed the file session during the transfer. */
    WHEEL_ABORTED,

    /** The protocol session was reset (disconnect, reconnect, close) during the transfer. */
    SESSION_RESET
}

/**
 * Snapshot of a wheel diagnostic-log download. The library only transfers the opaque binary
 * file; storing, sharing or decoding it belongs to the client application.
 */
class DiagnosticLogTransfer internal constructor(
    val state: DiagnosticLogState,
    /** Wheel-side file path, e.g. `2:/log.bin`. */
    val filePath: String,
    /** File size reported by the wheel, or `null` before the file is opened. */
    val totalBytes: Int?,
    val receivedBytes: Int,
    val failure: DiagnosticLogFailure? = null,
    /** CRC-32 (IEEE) reported by the wheel once every byte is received. */
    val crc32: Long? = null,
    // Shared without copying: the owner never mutates a completed buffer.
    private val data: ByteArray? = null,
) {
    /** Fraction in `0.0..1.0`, or `null` while the file size is unknown. */
    val progress: Double?
        get() = totalBytes?.takeIf { it > 0 }?.let { receivedBytes.toDouble() / it }

    /** Returns a copy of the verified file, or `null` unless [state] is [DiagnosticLogState.COMPLETED]. */
    fun copyData(): ByteArray? = if (state == DiagnosticLogState.COMPLETED) data?.copyOf() else null

    override fun equals(other: Any?): Boolean =
        other is DiagnosticLogTransfer && state == other.state && filePath == other.filePath &&
            totalBytes == other.totalBytes && receivedBytes == other.receivedBytes &&
            failure == other.failure && crc32 == other.crc32 &&
            (data === other.data || (data != null && other.data != null && data.contentEquals(other.data)))

    override fun hashCode(): Int {
        var result = state.hashCode()
        result = 31 * result + filePath.hashCode()
        result = 31 * result + (totalBytes ?: 0)
        result = 31 * result + receivedBytes
        result = 31 * result + (failure?.hashCode() ?: 0)
        result = 31 * result + (crc32?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "DiagnosticLogTransfer(state=$state, filePath=$filePath, receivedBytes=$receivedBytes, " +
            "totalBytes=$totalBytes, failure=$failure, crc32=${crc32?.toString(16)})"
}

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.models.WheelSecurityState

/**
 * Output produced by a [SyncEucProtocol]: either an event for the application or an action the
 * session layer must perform. Protocols never perform I/O or schedule work themselves.
 */
sealed class ProtocolOutput {
    /** Diagnostic message for the session's logger. */
    data class DebugLog(val tag: String, val message: String) : ProtocolOutput()
    /** Decoded telemetry. */
    data class Telemetry(val data: EUCData) : ProtocolOutput()

    /** Notification-confirmed security state changed (or was re-confirmed). */
    data class SecurityStateChanged(val state: WheelSecurityState) : ProtocolOutput()

    /** Individual security notification or operation result. */
    data class SecurityEvent(val event: WheelSecurityEvent) : ProtocolOutput()

    /** Bytes the session must write to the protocol's write characteristic. */
    class Write(val payload: ByteArray) : ProtocolOutput() {
        override fun equals(other: Any?): Boolean =
            other is Write && payload.contentEquals(other.payload)

        override fun hashCode(): Int = payload.contentHashCode()

        override fun toString(): String = "Write(${payload.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }})"
    }

    /**
     * Arms timer [id] to fire [SyncEucProtocol.onTimer] after [delayMs]. Re-arming an already
     * armed id replaces it.
     */
    data class StartTimer(val id: String, val delayMs: Long) : ProtocolOutput()

    /** Disarms timer [id]; ignored when it is not armed. */
    data class CancelTimer(val id: String) : ProtocolOutput()
}

/**
 * FIFO queue of [ProtocolOutput] owned by a [SyncEucProtocol]. The default capacity is 256;
 * overflow throws instead of dropping protocol outputs. Not thread-safe: the session layer
 * serializes all calls into a protocol.
 */
class ProtocolOutbox(private val capacity: Int = DEFAULT_CAPACITY) {
    private val queue = ArrayDeque<ProtocolOutput>()

    init {
        require(capacity > 0) { "Protocol output capacity must be positive" }
    }

    val size: Int get() = queue.size

    fun push(output: ProtocolOutput) {
        check(queue.size < capacity) {
            "Protocol output queue overflow (capacity=$capacity)"
        }
        queue.addLast(output)
    }

    fun poll(): ProtocolOutput? = queue.removeFirstOrNull()

    fun clear() {
        queue.clear()
    }

    private companion object {
        const val DEFAULT_CAPACITY = 256
    }
}

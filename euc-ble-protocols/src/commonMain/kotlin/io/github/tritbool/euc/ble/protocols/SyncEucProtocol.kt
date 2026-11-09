package io.github.tritbool.euc.ble.protocols

/**
 * Synchronous, I/O-free protocol state machine ("sans-IO").
 *
 * Inputs are plain method calls: [onNotification], [onTimer], [EucProtocolSpec.onCommandSubmitted],
 * [EucProtocolSpec.createCommand] and [EucProtocolSpec.resetSession]. Every resulting telemetry,
 * security update, automatic write or timer request is queued and retrieved with [pollOutput].
 *
 * Implementations must not use coroutines, threads, locks, timers or wall-clock reads other than
 * an injected [io.github.tritbool.euc.ble.core.EucClock]. They are not required to be thread-safe:
 * the caller/session layer serializes every call and drains outputs before the next input.
 * The contract has no async runtime dependency. A Rust port must still choose allocation,
 * command-value and queue-capacity representations; this Kotlin implementation uses collections.
 *
 * [EucProtocolSpec.resetSession] must also discard queued outputs; the session layer disarms all
 * timers on reset.
 */
interface SyncEucProtocol : EucProtocolSpec {
    /** Feeds one raw BLE notification (any fragmentation) into the state machine. */
    fun onNotification(data: ByteArray)

    /** Called when a timer armed with [ProtocolOutput.StartTimer] expires. */
    fun onTimer(id: String) {}

    /** Returns and removes the next pending output, or `null` when there is none. */
    fun pollOutput(): ProtocolOutput?
}

/** Drains every pending output of this protocol in FIFO order. */
fun SyncEucProtocol.drainOutputs(): List<ProtocolOutput> {
    val out = mutableListOf<ProtocolOutput>()
    while (true) out += pollOutput() ?: return out
}

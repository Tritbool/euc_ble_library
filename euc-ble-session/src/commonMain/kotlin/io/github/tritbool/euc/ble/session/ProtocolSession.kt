package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.protocols.ProtocolOutbox
import io.github.tritbool.euc.ble.protocols.ProtocolOutput
import io.github.tritbool.euc.ble.protocols.SyncEucProtocol

/** Synchronous protocol session; the caller serializes inputs and supplies monotonic time. */
class ProtocolSession(val protocol: SyncEucProtocol, private val timerCapacity: Int = 32) {
    private class Timer(val deadline: Long)
    private val timers = mutableMapOf<String, Timer>()
    private val outputs = ProtocolOutbox()
    val armedTimerCount: Int get() = timers.size
    val nextDeadline: Long? get() = timers.values.minOfOrNull { it.deadline }

    init { require(timerCapacity > 0) }

    fun onNotification(bytes: ByteArray, now: Long) {
        protocol.onNotification(bytes)
        drain(now)
    }

    fun afterInput(now: Long) = drain(now)

    fun advance(now: Long) {
        // Snapshot expired IDs: a callback can cancel or re-arm another expired timer.
        val expired = timers.filterValues { it.deadline <= now }.toList().sortedBy { it.second.deadline }
        for ((id, timer) in expired) {
            if (timers[id] !== timer) continue
            timers.remove(id)
            protocol.onTimer(id)
            drain(now)
        }
    }

    fun reset(now: Long) {
        timers.clear()
        outputs.clear()
        protocol.resetSession()
        drain(now)
    }

    fun close() { timers.clear() }
    fun pollOutput(): ProtocolOutput? = outputs.poll()

    private fun drain(now: Long) {
        while (true) {
            when (val output = protocol.pollOutput() ?: return) {
                is ProtocolOutput.StartTimer -> {
                    check(output.id in timers || timers.size < timerCapacity) {
                        "Protocol timer capacity exceeded ($timerCapacity)"
                    }
                    timers[output.id] = Timer(now + output.delayMs.coerceAtLeast(0))
                }
                is ProtocolOutput.CancelTimer -> timers.remove(output.id)
                else -> outputs.push(output)
            }
        }
    }
}

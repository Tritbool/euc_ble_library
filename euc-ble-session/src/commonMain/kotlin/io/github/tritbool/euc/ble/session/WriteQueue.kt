package io.github.tritbool.euc.ble.session

enum class WriteEnqueueResult { ENQUEUED, QUEUE_FULL, SESSION_CLOSED }

/** Bounded, synchronous write policy. The connector supplies time, submissions and acknowledgments. */
class WriteQueue<T>(private val capacity: Int = 64) {
    class Submission<T> internal constructor(
        val token: Long,
        val bytes: ByteArray,
        val attachment: T,
        var attempt: Int = 0
    )

    private enum class Phase { READY, SUBMITTING, BACKOFF, ACKNOWLEDGMENT }
    private val pending = ArrayDeque<Submission<T>>()
    private var active: Submission<T>? = null
    private var phase = Phase.READY
    private var due = 0L
    private var earlyAcknowledgment = false
    private var nextToken = 0L
    var generation: Long = 0
        private set
    val hasPending: Boolean get() = active != null || pending.isNotEmpty()
    fun isCurrent(token: Long): Boolean = active?.token == token

    init { require(capacity > 0) { "Write queue capacity must be positive" } }

    fun enqueue(bytes: ByteArray, attachment: T): WriteEnqueueResult {
        if (pending.size >= capacity) return WriteEnqueueResult.QUEUE_FULL
        pending.addLast(Submission(++nextToken, bytes.copyOf(), attachment))
        return WriteEnqueueResult.ENQUEUED
    }

    /** Returns each attempt once; accepted writes are never retried, even without an acknowledgment. */
    fun next(now: Long): Submission<T>? {
        if (phase == Phase.ACKNOWLEDGMENT && now >= due) finish()
        if (phase == Phase.BACKOFF && now < due) return null
        if (phase == Phase.SUBMITTING || phase == Phase.ACKNOWLEDGMENT) return null
        val write = active ?: pending.removeFirstOrNull()?.also { active = it } ?: return null
        phase = Phase.SUBMITTING
        earlyAcknowledgment = false
        write.attempt++
        return write
    }

    /** Returns true only for an exhausted/retry-forbidden rejection of the current attempt. */
    fun onSubmitted(token: Long, accepted: Boolean, retryable: Boolean, now: Long): Boolean {
        val write = active?.takeIf { it.token == token && phase == Phase.SUBMITTING } ?: return false
        if (accepted) {
            phase = Phase.ACKNOWLEDGMENT
            due = now + 300
            if (earlyAcknowledgment) finish()
        } else if (retryable && write.attempt < 5) {
            phase = Phase.BACKOFF
            due = now + 30L * write.attempt
        } else {
            finish()
            return true
        }
        return false
    }

    fun onAcknowledgment() {
        when (phase) {
            Phase.SUBMITTING -> earlyAcknowledgment = true
            Phase.ACKNOWLEDGMENT -> finish()
            else -> Unit
        }
    }

    fun discard(token: Long) {
        if (active?.token == token) finish()
    }

    fun reset() {
        generation++
        finish()
        while (pending.isNotEmpty()) pending.removeFirst().bytes.fill(0)
    }

    fun delayUntilNext(now: Long): Long =
        if (phase == Phase.BACKOFF || phase == Phase.ACKNOWLEDGMENT) (due - now).coerceAtLeast(0) else 0

    private fun finish() {
        active?.bytes?.fill(0)
        active = null
        phase = Phase.READY
        earlyAcknowledgment = false
    }
}

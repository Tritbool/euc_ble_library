package io.github.tritbool.euc.ble.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Serializes transport writes, owns write generations, and retries only stack rejections. */
class WriteSession(
    private val scope: CoroutineScope,
    maxQueuedWrites: Int = DEFAULT_MAX_QUEUED_WRITES,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onError: (Exception) -> Unit
) {
    private class Write(
        val valid: () -> Boolean, val submit: (ByteArray) -> Boolean,
        val retryable: (ByteArray) -> Boolean, val accepted: (ByteArray) -> Unit,
        val failed: (Int) -> Unit
    )

    private val lock = Any()
    private val queue = WriteQueue<Write>(maxQueuedWrites)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    val generation: Long
        get() = synchronized(lock) { queue.generation }

    init {
        require(maxQueuedWrites > 0) { "Write queue capacity must be positive" }
    }

    fun enqueue(
        bytes: ByteArray,
        valid: () -> Boolean,
        submit: (ByteArray) -> Boolean,
        retryable: (ByteArray) -> Boolean,
        accepted: (ByteArray) -> Unit,
        failed: (Int) -> Unit
    ): WriteEnqueueResult {
        val write = Write(valid, submit, retryable, accepted, failed)
        var start: Job? = null
        val result = synchronized(lock) {
            if (!scope.isActive) return WriteEnqueueResult.SESSION_CLOSED
            val result = queue.enqueue(bytes, write)
            if (result != WriteEnqueueResult.ENQUEUED) return result
            if (worker == null || worker?.isCompleted == true) {
                worker = scope.launch(start = CoroutineStart.LAZY) {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val attempt = synchronized(lock) {
                            if (!queue.hasPending) {
                                worker = null
                                return@launch
                            }
                            queue.next(now())?.let { it to it.bytes.copyOf() }
                        }
                        attempt?.let { (submission, bytes) -> perform(submission, bytes) }
                        val wait = synchronized(lock) { queue.delayUntilNext(now()) }
                        if (wait > 0) withTimeoutOrNull(wait) { wake.receive() }
                    }
                }.also { start = it }
            }
            WriteEnqueueResult.ENQUEUED
        }
        wake.trySend(Unit)
        start?.start()
        return result
    }

    fun onAcknowledgment() {
        synchronized(lock) { queue.onAcknowledgment() }
        wake.trySend(Unit)
    }

    /** Clears only this session's jobs and byte snapshots, not the caller's scope. */
    fun reset() = synchronized(lock) {
        worker?.cancel()
        worker = null
        queue.reset()
        while (wake.tryReceive().isSuccess) Unit
    }

    private fun perform(submission: WriteQueue.Submission<Write>, bytes: ByteArray) {
        val write = submission.attachment
        try {
            if (!write.valid() || !synchronized(lock) { queue.isCurrent(submission.token) }) {
                synchronized(lock) { queue.discard(submission.token) }
                return
            }
            val accepted = write.submit(bytes)
            if (!accepted && !write.valid()) {
                synchronized(lock) { queue.discard(submission.token) }
                return
            }
            val retryable = !accepted && write.retryable(bytes)
            val outcome = synchronized(lock) {
                if (!queue.isCurrent(submission.token)) null
                else queue.onSubmitted(submission.token, accepted, retryable, now())
            }
            if (outcome != null) {
                if (accepted) write.accepted(bytes)
                else if (outcome) write.failed(submission.attempt)
            }
        } catch (e: CancellationException) {
            synchronized(lock) { queue.discard(submission.token) }
            throw e
        } catch (e: Exception) {
            synchronized(lock) { queue.discard(submission.token) }
            write.failed(submission.attempt)
            onError(e)
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val DEFAULT_MAX_QUEUED_WRITES = 64
    }
}

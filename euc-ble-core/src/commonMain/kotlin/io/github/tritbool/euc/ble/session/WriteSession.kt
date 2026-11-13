package io.github.tritbool.euc.ble.session

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import io.github.tritbool.euc.ble.core.MonotonicClock

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
    private val now: () -> Long = { MonotonicClock.millis() },
    private val onError: (Exception) -> Unit
) {
    private class Write(
        val valid: () -> Boolean, val submit: (ByteArray) -> Boolean,
        val retryable: (ByteArray) -> Boolean, val accepted: (ByteArray) -> Unit,
        val failed: (Int) -> Unit
    )

    private val lock = Lock()
    private val queue = WriteQueue<Write>(maxQueuedWrites)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    val generation: Long
        get() = lock.withLock { queue.generation }

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
        val result = lock.withLock {
            if (!scope.isActive) return WriteEnqueueResult.SESSION_CLOSED
            val result = queue.enqueue(bytes, write)
            if (result != WriteEnqueueResult.ENQUEUED) return result
            if (worker == null || worker?.isCompleted == true) {
                worker = scope.launch(start = CoroutineStart.LAZY) {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val attempt = lock.withLock {
                            if (!queue.hasPending) {
                                worker = null
                                return@launch
                            }
                            queue.next(now())?.let { it to it.bytes.copyOf() }
                        }
                        attempt?.let { (submission, bytes) -> perform(submission, bytes) }
                        val wait = lock.withLock { queue.delayUntilNext(now()) }
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
        lock.withLock { queue.onAcknowledgment() }
        wake.trySend(Unit)
    }

    /** Clears only this session's jobs and byte snapshots, not the caller's scope. */
    fun reset() = lock.withLock {
        worker?.cancel()
        worker = null
        queue.reset()
        while (wake.tryReceive().isSuccess) Unit
    }

    private fun perform(submission: WriteQueue.Submission<Write>, bytes: ByteArray) {
        val write = submission.attachment
        try {
            if (!write.valid() || !lock.withLock { queue.isCurrent(submission.token) }) {
                lock.withLock { queue.discard(submission.token) }
                return
            }
            val accepted = write.submit(bytes)
            if (!accepted && !write.valid()) {
                lock.withLock { queue.discard(submission.token) }
                return
            }
            val retryable = !accepted && write.retryable(bytes)
            val outcome = lock.withLock {
                if (!queue.isCurrent(submission.token)) null
                else queue.onSubmitted(submission.token, accepted, retryable, now())
            }
            if (outcome != null) {
                if (accepted) write.accepted(bytes)
                else if (outcome) write.failed(submission.attempt)
            }
        } catch (e: CancellationException) {
            lock.withLock { queue.discard(submission.token) }
            throw e
        } catch (e: Exception) {
            lock.withLock { queue.discard(submission.token) }
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

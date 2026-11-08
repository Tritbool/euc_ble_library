package io.github.tritbool.euc.ble.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Serializes transport writes and retries only stack-rejected submissions, never accepted writes. */
class WriteSession(
    private val scope: CoroutineScope,
    private val onError: (Exception) -> Unit
) {
    private class Write(
        val bytes: ByteArray, val valid: () -> Boolean, val submit: (ByteArray) -> Boolean,
        val retryable: (ByteArray) -> Boolean, val accepted: (ByteArray) -> Unit,
        val failed: (Int) -> Unit
    )

    private val lock = Any()
    private val queue = Channel<Write>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var acknowledgment: CompletableDeferred<Unit>? = null

    fun enqueue(
        bytes: ByteArray,
        valid: () -> Boolean,
        submit: (ByteArray) -> Boolean,
        retryable: (ByteArray) -> Boolean,
        accepted: (ByteArray) -> Unit,
        failed: (Int) -> Unit
    ) {
        val write = Write(bytes.copyOf(), valid, submit, retryable, accepted, failed)
        synchronized(lock) {
            check(queue.trySend(write).isSuccess) { "Write session queue unavailable" }
            if (worker?.isActive != true) {
                worker = scope.launch {
                    for (item in queue) {
                        try {
                            perform(item)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            item.failed(0)
                            onError(e)
                        } finally {
                            item.bytes.fill(0)
                        }
                    }
                }
            }
        }
    }

    fun onAcknowledgment() {
        val ack = synchronized(lock) { acknowledgment }
        ack?.complete(Unit)
    }

    /** Clears only this session's jobs and byte snapshots, not the caller's scope. */
    fun reset() = synchronized(lock) {
        worker?.cancel()
        worker = null
        acknowledgment?.cancel()
        acknowledgment = null
        while (true) {
            val write = queue.tryReceive().getOrNull() ?: break
            write.bytes.fill(0)
        }
    }

    private suspend fun perform(write: Write) {
        var attempts = 0
        for (attempt in 1..5) {
            if (!write.valid()) return
            val ack = CompletableDeferred<Unit>()
            synchronized(lock) { acknowledgment = ack }
            attempts = attempt
            try {
                if (write.submit(write.bytes)) {
                    write.accepted(write.bytes)
                    withTimeoutOrNull(300L) { ack.await() }
                    return
                }
            } finally {
                synchronized(lock) {
                    if (acknowledgment === ack) acknowledgment = null
                }
            }
            if (!write.valid()) return
            if (!write.retryable(write.bytes)) break
            if (attempt < 5) delay(30L * attempt)
        }
        write.failed(attempts)
    }
}

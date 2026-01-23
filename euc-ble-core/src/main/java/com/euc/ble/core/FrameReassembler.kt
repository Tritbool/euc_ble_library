package com.euc.ble.core

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FrameReassembler(
    private val frameSize: Int,
    private val frameHeader: ByteArray,
    private val frameFooter: ByteArray
) {

    private val mutex = Mutex()
    private val buffer = mutableListOf<Byte>()
    private val frameFlow = MutableSharedFlow<ByteArray>(replay = 0)

    fun observeFrames(): Flow<ByteArray> = frameFlow

    suspend fun processIncomingBytes(data: ByteArray) {
        mutex.withLock {
            buffer.addAll(data.toList())

            var continueProcessing = true
            while (continueProcessing) {
                val headerIndex = findHeader()
                if (headerIndex == -1) {
                    // No full header found.
                    // To prevent the buffer from growing indefinitely with invalid data,
                    // we'll clear it, but preserve trailing bytes that might be the start of a future header.
                    preservePartialHeader()
                    continueProcessing = false
                } else {
                    // Discard any data before the header.
                    if (headerIndex > 0) {
                        buffer.subList(0, headerIndex).clear()
                    }

                    if (buffer.size < frameSize) {
                        // Not enough data for a full frame. Wait for more.
                        continueProcessing = false
                    } else {
                        // We have enough data for a frame, check the footer.
                        val frameCandidate = buffer.subList(0, frameSize)
                        if (isValidFooter(frameCandidate)) {
                            // Valid frame, emit it.
                            frameFlow.emit(frameCandidate.toByteArray())
                            // Remove the frame from the buffer and loop again to check for more frames.
                            frameCandidate.clear()
                        } else {
                            // Invalid footer. This isn't a valid frame.
                            // Discard the header's first byte to avoid getting stuck, then re-process buffer.
                            buffer.removeAt(0)
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds the index of the first occurrence of the frame header.
     */
    private fun findHeader(): Int {
        if (frameHeader.isEmpty()) return if (buffer.isNotEmpty()) 0 else -1
        outer@ for (i in 0..buffer.size - frameHeader.size) {
            for (j in frameHeader.indices) {
                if (buffer[i + j] != frameHeader[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun isValidFooter(frame: List<Byte>): Boolean {
        if (frame.size != frameSize) return false
        if (frameFooter.isEmpty()) return true
        val footerStart = frameSize - frameFooter.size
        return frame.subList(footerStart, frameSize) == frameFooter.toList()
    }

    /**
     * Preserves trailing bytes that might be the start of a future header.
     * Clears the buffer but keeps any partial header match at the end.
     */
    private fun preservePartialHeader() {
        if (frameHeader.isEmpty() || buffer.isEmpty()) {
            buffer.clear()
            return
        }

        // Check if the end of the buffer contains a partial match of the header
        val maxPartialLength = minOf(frameHeader.size - 1, buffer.size)
        for (partialLen in maxPartialLength downTo 1) {
            val bufferEnd = buffer.takeLast(partialLen)
            val headerStart = frameHeader.take(partialLen).toList()
            if (bufferEnd == headerStart) {
                buffer.clear()
                buffer.addAll(bufferEnd)
                return
            }
        }
        buffer.clear()
    }

    @VisibleForTesting
    fun reset() {
        buffer.clear()
    }
}

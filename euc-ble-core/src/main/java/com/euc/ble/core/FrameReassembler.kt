package com.euc.ble.core

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object FrameReassembler {

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
                    // we'll clear it, but preserve a trailing 0x55 if it exists,
                    // as it might be the start of a future header.
                    val lastByteIs55 = buffer.isNotEmpty() && buffer.last() == 0x55.toByte()
                    buffer.clear()
                    if (lastByteIs55) {
                        buffer.add(0x55.toByte())
                    }
                    continueProcessing = false
                } else {
                    // Discard any data before the header.
                    if (headerIndex > 0) {
                        buffer.subList(0, headerIndex).clear()
                    }

                    if (buffer.size < 24) {
                        // Not enough data for a full frame. Wait for more.
                        continueProcessing = false
                    } else {
                        // We have enough data for a frame, check the footer.
                        val frameCandidate = buffer.subList(0, 24)
                        if (isValidFooter(frameCandidate)) {
                            // Valid frame, emit it.
                            frameFlow.emit(frameCandidate.toByteArray())
                            // Remove the frame from the buffer and loop again to check for more frames.
                            frameCandidate.clear()
                        } else {
                            // Invalid footer. This isn't a valid frame.
                            // Discard the header's first byte (0x55) to avoid getting stuck, then re-process buffer.
                            buffer.removeAt(0)
                        }
                    }
                }
            }
        }
    }

    /**
     * Finds the index of the first occurrence of the frame header (0x55, 0xAA).
     */
    private fun findHeader(): Int {
        for (i in 0 until buffer.size - 1) {
            if (buffer[i] == 0x55.toByte() && buffer[i + 1] == 0xAA.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun isValidFooter(frame: List<Byte>): Boolean {
        return frame.size == 24 &&
                frame.subList(20, 24) == listOf(0x5A.toByte(), 0x5A.toByte(), 0x5A.toByte(), 0x5A.toByte())
    }

    @VisibleForTesting
    fun reset() {
        buffer.clear()
    }
}

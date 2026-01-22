package com.euc.ble.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.annotation.VisibleForTesting
object FrameReassembler {

    private val mutex = Mutex()
    private val buffer = mutableListOf<Byte>()
    private var collecting = false
    private var previousByte: Byte = 0x00

    private val frameFlow = MutableSharedFlow<ByteArray>(replay = 0) // Emits completed frames

    // Expose the shared Flow for complete frames
    fun observeFrames(): Flow<ByteArray> = frameFlow

    // Process incoming bytes asynchronously
    suspend fun processIncomingBytes(data: ByteArray) {
        mutex.withLock {
            var index = 0
            while (index < data.size) {
                val byte = data[index]

                if (collecting) {
                    buffer.add(byte)

                    // Check if the frame is complete
                    if (buffer.size == 24) {
                        if (isValidFooter(buffer)) {
                            frameFlow.emit(buffer.toByteArray()) // Emit the complete frame

                            // Check for a new frame after the current frame
                            if (index + 1 < data.size && data[index + 1] == 0x55.toByte()) {
                                // Keep the trailing 0x55 for the next frame
                                buffer.clear()
                                buffer.add(0x55) // Start rebuilding with 0x55
                            } else {
                                // Clear the buffer if there's no valid start of a frame
                                buffer.clear()
                                collecting = false
                            }
                        } else {
                            // Invalid frame handling: reset buffer
                            resetReassembly()
                        }
                    }
                } else if (byte == 0xAA.toByte() && previousByte == 0x55.toByte()) {
                    // Found frame header: start collecting
                    buffer.clear()
                    buffer.add(0x55.toByte())
                    buffer.add(0xAA.toByte())
                    collecting = true
                }

                previousByte = byte
                index++
            }
        }
    }

    // Reset the state for testing purposes (clears buffer and collecting state)
    @VisibleForTesting
    fun reset() {
        resetReassembly()
    }
    private fun resetReassembly() {
        buffer.clear()
        collecting = false
    }

    private fun isValidFooter(frame: List<Byte>): Boolean {
        return frame.size == 24 &&
                frame.subList(20, 24) == listOf(0x5A.toByte(), 0x5A.toByte(), 0x5A.toByte(), 0x5A.toByte())
    }
}
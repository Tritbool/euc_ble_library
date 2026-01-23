package com.euc.ble.frames

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * FrameReassembler thread-safe, API compatible : processIncomingBytes, reset, frames / observeFrames.
 * Peut être construit avec un FrameParser custom ou via le constructeur compatible legacy.
 */
class FrameReassembler(
    private val parser: FrameParser
) {
    private val lock = Any()
    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: SharedFlow<ByteArray> get() = _frames.asSharedFlow()
    fun observeFrames(): SharedFlow<ByteArray> = frames

    fun reset() {
        synchronized(lock) { parser.reset() }
    }

    fun processIncomingBytes(data: ByteArray) {
        val ready: List<ByteArray>
        synchronized(lock) {
            ready = parser.appendAndExtract(data)
        }
        for (frame in ready) _frames.tryEmit(frame)
    }
}

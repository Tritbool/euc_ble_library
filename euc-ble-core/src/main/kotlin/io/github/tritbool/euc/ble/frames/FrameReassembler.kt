package io.github.tritbool.euc.ble.frames

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class FrameReassembler(
    private val parser: FrameParser
) {
    private val lock = Any()

    private val _frames = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    val frames: Flow<ByteArray> get() = _frames.receiveAsFlow()
    fun observeFrames(): Flow<ByteArray> = frames

    fun reset() {
        synchronized(lock) { parser.reset() }
    }

    fun processIncomingBytes(data: ByteArray) {
        val ready: List<ByteArray>
        synchronized(lock) {
            ready = parser.appendAndExtract(data)
        }
        for (frame in ready) {
            _frames.trySend(frame)
        }
    }
}
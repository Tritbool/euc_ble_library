package io.github.tritbool.euc.ble.frames

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Reassembles incoming byte streams into complete frames using a [FrameParser].
 *
 * This class provides a thread-safe way to process incoming BLE data and emit
 * complete frames through a Flow interface.
 *
 * @param parser The frame parser to use for extracting complete frames from byte streams
 */
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
// kotlin
package com.euc.ble.frames

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FrameReassembler(
    private val frameSize: Int,
    private val header: ByteArray = byteArrayOf(),
    private val footer: ByteArray = byteArrayOf()
) {
    private val lock = Any()
    private val buffer = ArrayList<Byte>()

    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 1,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: SharedFlow<ByteArray> get() = _frames.asSharedFlow()
    fun observeFrames(): SharedFlow<ByteArray> = frames

    fun reset() {
        synchronized(lock) { buffer.clear() }
    }

    fun processIncomingBytes(data: ByteArray) {
        val ready = mutableListOf<ByteArray>()

        synchronized(lock) {
            for (b in data) buffer.add(b)
            extractReadyFramesInto(ready)
        }

        // Émettre hors du verrou, non suspendant
        for (frame in ready) _frames.tryEmit(frame)
    }

    private fun extractReadyFramesInto(out: MutableList<ByteArray>) {
        var idx = findHeaderIndex(0)
        while (idx >= 0) {
            if (buffer.size - idx < frameSize) break
            val end = idx + frameSize
            val candidate = ByteArray(frameSize) { i -> buffer[idx + i] }
            if (matchesFooter(candidate)) {
                out.add(candidate)
                repeat(end) { buffer.removeAt(0) }
                idx = findHeaderIndex(0)
            } else {
                buffer.removeAt(0)
                idx = findHeaderIndex(0)
            }
        }

        if (idx < 0) {
            val keep = maxOf(0, header.size - 1)
            while (buffer.size > keep) buffer.removeAt(0)
        }
    }

    private fun findHeaderIndex(from: Int): Int {
        if (buffer.size < header.size) return -1
        var i = from
        val maxStart = buffer.size - header.size
        while (i <= maxStart) {
            var ok = true
            for (j in header.indices) {
                if (buffer[i + j] != header[j]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    private fun matchesFooter(frame: ByteArray): Boolean {
        if (footer.isEmpty()) return true
        val start = frame.size - footer.size
        if (start < 0) return false
        for (i in footer.indices) {
            if (frame[start + i] != footer[i]) return false
        }
        return true
    }
}

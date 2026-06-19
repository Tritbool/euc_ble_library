package io.github.tritbool.euc.ble.frames

/**
 * Compatible with legacy implementation: looks for an optional header,
 * extracts fixed-size frames and validates an optional footer.
 *
 * @param frameSize The fixed size of each frame in bytes
 * @param header Optional header bytes that must precede each frame
 * @param footer Optional footer bytes that must follow each frame
 */
class FixedSizeFrameParser(
    private val frameSize: Int,
    private val header: ByteArray = byteArrayOf(),
    private val footer: ByteArray = byteArrayOf()
) : FrameParser {
    private val buffer = ArrayList<Byte>()

    override fun appendAndExtract(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        for (b in data) buffer.add(b)
        extractReadyFramesInto(out)
        return out
    }

    override fun reset() {
        buffer.clear()
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
        if (header.isEmpty()) return 0.takeIf { buffer.size >= 0 } ?: -1
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

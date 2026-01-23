package com.euc.ble.frames

/**
 * Parser simple pour trames \"header + length + payload\".
 * lengthSize en octets (1 ou 2). lengthOffset = offset relatif au début de la trame.
 */
class LengthPrefixedFrameParser(
    private val header: ByteArray = byteArrayOf(),
    private val lengthOffset: Int = 0,
    private val lengthSize: Int = 1,
    private val maxFrameSize: Int = 4096
) : FrameParser {
    private val buffer = ArrayList<Byte>()

    override fun appendAndExtract(data: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        for (b in data) buffer.add(b)
        extract(out)
        return out
    }

    override fun reset() {
        buffer.clear()
    }

    private fun extract(out: MutableList<ByteArray>) {
        var idx = findHeaderIndex(0)
        while (idx >= 0) {
            val needForLength = idx + lengthOffset + lengthSize
            if (buffer.size < needForLength) break
            // lire la longueur (little endian)
            var len = 0
            for (i in 0 until lengthSize) {
                len = len or ((buffer[idx + lengthOffset + i].toInt() and 0xFF) shl (8 * i))
            }
            if (len <= 0 || len > maxFrameSize) {
                // corruption: drop header byte and resync
                buffer.removeAt(idx)
                idx = findHeaderIndex(0)
                continue
            }
            val end = idx + len
            if (buffer.size < end) break
            val frame = ByteArray(len) { i -> buffer[idx + i] }
            out.add(frame)
            repeat(end) { buffer.removeAt(0) }
            idx = findHeaderIndex(0)
        }

        if (idx < 0) {
            val keep = maxOf(0, header.size - 1)
            while (buffer.size > keep) buffer.removeAt(0)
        }
    }

    private fun findHeaderIndex(from: Int): Int {
        if (header.isEmpty()) return 0.takeIf { buffer.size > 0 } ?: -1
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
}

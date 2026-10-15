package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import java.util.zip.CRC32

internal class NosfetFrameDetector {
    private val buffer = ArrayList<Byte>()

    @Synchronized
    fun reset() = buffer.clear()

    @Synchronized
    fun detect(data: ByteArray): ByteArray? {
        for ((index, byte) in data.withIndex()) {
            buffer.add(byte)
            if (buffer.size > 259) buffer.removeAt(0)
            // Inspect all possible headers so a corrupt length cannot hide the next frame.
            for (start in 0 until (buffer.size - 3)) {
                if (buffer[start] != 0xDC.toByte() ||
                    buffer[start + 1] != 0x5A.toByte() ||
                    buffer[start + 2] != 0x5C.toByte()
                ) continue
                val len = buffer[start + 3].toInt() and 0xFF
                if (len < 32 || len == 0x5F || buffer.size - start != len + 4) continue
                val frame = buffer.subList(start, buffer.size).toByteArray()
                if (ByteUtils.getUnsignedShortBE(frame, 28) / 1000 !in 42..44) continue
                if (len > 38) {
                    val crc = CRC32().apply { update(frame, 0, len) }.value
                    val expected = ByteUtils.getUnsignedIntBE(frame, len)
                    if (crc != expected) continue
                }
                if (ByteUtils.getUnsignedShortBE(frame, 4) !in 2000..18000 ||
                    ByteUtils.getSignedShortBE(frame, 6) !in -1200..1200 ||
                    ByteUtils.getSignedShortBE(frame, 16) !in -30000..30000 ||
                    ByteUtils.getSignedShortBE(frame, 18) !in -5000..13000
                ) continue
                buffer.clear()
                return frame + data.copyOfRange(index + 1, data.size)
            }
        }
        return null
    }
}

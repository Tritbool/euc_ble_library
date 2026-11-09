package io.github.tritbool.euc.ble.core

/** IEEE CRC-32 (polynomial 0xEDB88320), with the same initial/final XOR as standard CRC32. */
object EucCrc32 {
    fun calculate(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "CRC range is outside the input"
        }
        var crc = -1
        for (index in offset until offset + length) {
            crc = crc xor (data[index].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return (crc.inv().toLong() and 0xFFFFFFFFL)
    }
}

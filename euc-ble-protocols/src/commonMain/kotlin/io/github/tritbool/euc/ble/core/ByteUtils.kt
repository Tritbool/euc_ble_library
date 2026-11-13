package io.github.tritbool.euc.ble.core

import kotlin.experimental.xor

/**
 * Utility class for byte operations commonly used in BLE protocols
 */
object ByteUtils {
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun byteToHex(value: Byte): String {
        val unsigned = value.toInt() and 0xFF
        return buildString(2) {
            append(HEX_DIGITS[unsigned ushr 4])
            append(HEX_DIGITS[unsigned and 0x0F])
        }
    }
    
    /**
     * Convert bytes to hex string
     */
    fun bytesToHex(bytes: ByteArray, separator: String = " "): String {
        return bytes.joinToString(separator) { byteToHex(it) }
    }
    
    /**
     * Convert hex string to byte array
     */
    fun hexToBytes(hexString: String): ByteArray {
        val cleanHexString=hexString.filterNot { it.isWhitespace() }
        val length = cleanHexString.length
        val byteArray = ByteArray(length / 2)
        
        for (i in 0 until length step 2) {
            val byteString = cleanHexString.substring(i, i + 2)
            byteArray[i / 2] = byteString.toInt(16).toByte()
        }
        
        return byteArray
    }
    
    /**
     * Get unsigned byte value (0-255)
     */
    fun getUnsignedByte(data: ByteArray, offset: Int): Int {
        return data[offset].toInt() and 0xFF
    }
    
    /**
     * Get unsigned short (2 bytes) in little-endian format
     */
    fun getUnsignedShortLE(data: ByteArray, offset: Int): Int {
        return (getUnsignedByte(data, offset) or (getUnsignedByte(data, offset + 1) shl 8))
    }
    
    /**
     * Get unsigned short (2 bytes) in big-endian format
     */
    fun getUnsignedShortBE(data: ByteArray, offset: Int): Int {
        return (getUnsignedByte(data, offset) shl 8) or getUnsignedByte(data, offset + 1)
    }
    
    /**
     * Get signed short (2 bytes) in little-endian format
     */
    fun getSignedShortLE(data: ByteArray, offset: Int): Short {
        return getUnsignedShortLE(data, offset).toShort()
    }
    
    /**
     * Get signed short (2 bytes) in big-endian format
     */
    fun getSignedShortBE(data: ByteArray, offset: Int): Short {
        return getUnsignedShortBE(data, offset).toShort()
    }
    
    /**
     * Get unsigned int (4 bytes) in little-endian format
     */
    fun getUnsignedIntLE(data: ByteArray, offset: Int): Long {
        return (getUnsignedShortLE(data, offset).toLong() or 
                (getUnsignedShortLE(data, offset + 2).toLong() shl 16))
    }
    
    /**
     * Get unsigned int (4 bytes) in big-endian format
     */
    fun getUnsignedIntBE(data: ByteArray, offset: Int): Long {
        return (getUnsignedShortBE(data, offset).toLong() shl 16) or 
                getUnsignedShortBE(data, offset + 2).toLong()
    }
    
    /**
     * Get signed int (4 bytes) in little-endian format
     */
    fun getSignedIntLE(data: ByteArray, offset: Int): Int {
        return (getUnsignedByte(data, offset) or
            (getUnsignedByte(data, offset + 1) shl 8) or
            (getUnsignedByte(data, offset + 2) shl 16) or
            (getUnsignedByte(data, offset + 3) shl 24))
    }
    
    /**
     * Get signed int (4 bytes) in big-endian format
     */
    fun getSignedIntBE(data: ByteArray, offset: Int): Int {
        return (getUnsignedByte(data, offset) shl 24) or
            (getUnsignedByte(data, offset + 1) shl 16) or
            (getUnsignedByte(data, offset + 2) shl 8) or
            getUnsignedByte(data, offset + 3)
    }
    
    /**
     * Convert short to byte array in little-endian format
     */
    fun shortToBytesLE(value: Short): ByteArray {
        val unsigned = value.toInt()
        return byteArrayOf(unsigned.toByte(), (unsigned ushr 8).toByte())
    }
    
    /**
     * Convert short to byte array in big-endian format
     */
    fun shortToBytesBE(value: Short): ByteArray {
        val unsigned = value.toInt()
        return byteArrayOf((unsigned ushr 8).toByte(), unsigned.toByte())
    }
    
    /**
     * Convert int to byte array in little-endian format
     */
    fun intToBytesLE(value: Int): ByteArray {
        return byteArrayOf(
            value.toByte(), (value ushr 8).toByte(),
            (value ushr 16).toByte(), (value ushr 24).toByte()
        )
    }
    
    /**
     * Convert int to byte array in big-endian format
     */
    fun intToBytesBE(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte()
        )
    }
    
    /**
     * Calculate checksum for byte array
     */
    fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Byte {
        var checksum: Byte = 0
        for (i in offset until offset + length) {
            checksum = (checksum + data[i]).toByte()
        }
        return checksum
    }
    
    /**
     * Calculate XOR checksum for byte array
     */
    fun calculateXorChecksum(data: ByteArray, offset: Int, length: Int): Byte {
        var checksum: Byte = 0
        for (i in offset until offset + length) {
            checksum = (checksum xor data[i]).toByte()
        }
        return checksum
    }
    
    /**
     * Check if byte array starts with specific pattern
     */
    fun startsWith(data: ByteArray, pattern: ByteArray): Boolean {
        if (data.size < pattern.size) return false
        
        for (i in pattern.indices) {
            if (data[i] != pattern[i]) return false
        }
        
        return true
    }
    
    /**
     * Find the index of a pattern in byte array
     */
    fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        if (data.size < pattern.size) return -1
        
        for (i in 0..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        
        return -1
    }

    // -- Safe access methods (return null if out of bounds) --
    fun tryGetUnsignedByte(data: ByteArray, offset: Int): Int? =
        if (offset in 0 until data.size) data[offset].toInt() and 0xFF else null

    fun tryGetSignedByte(data: ByteArray, offset: Int): Int? =
        tryGetUnsignedByte(data, offset)?.let { raw -> if (raw >= 0x80) raw - 0x100 else raw }

    fun tryGetUnsignedShortLE(data: ByteArray, offset: Int): Int? =
        if (offset + 1 < data.size) getUnsignedShortLE(data, offset) else null

    fun tryGetUnsignedShortBE(data: ByteArray, offset: Int): Int? =
        if (offset + 1 < data.size) getUnsignedShortBE(data, offset) else null

    fun tryGetSignedShortLE(data: ByteArray, offset: Int): Short? =
        if (offset + 1 < data.size) getSignedShortLE(data, offset) else null

    fun tryGetSignedShortBE(data: ByteArray, offset: Int): Short? =
        if (offset + 1 < data.size) getSignedShortBE(data, offset) else null

    fun tryGetUnsignedIntLE(data: ByteArray, offset: Int): Long? =
        if (offset + 3 < data.size) getUnsignedIntLE(data, offset) else null

    fun tryGetUnsignedIntBE(data: ByteArray, offset: Int): Long? =
        if (offset + 3 < data.size) getUnsignedIntBE(data, offset) else null

    fun tryGetSignedIntLE(data: ByteArray, offset: Int): Int? =
        if (offset + 3 < data.size) getSignedIntLE(data, offset) else null

    fun tryGetSignedIntBE(data: ByteArray, offset: Int): Int? =
        if (offset + 3 < data.size) getSignedIntBE(data, offset) else null

}
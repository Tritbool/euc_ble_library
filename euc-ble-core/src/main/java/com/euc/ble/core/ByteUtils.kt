package com.euc.ble.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.experimental.xor

/**
 * Utility class for byte operations commonly used in BLE protocols
 */
object ByteUtils {
    
    /**
     * Convert bytes to hex string
     */
    fun bytesToHex(bytes: ByteArray, separator: String = " "): String {
        return bytes.joinToString(separator) { "%02X".format(it) }
    }
    
    /**
     * Convert hex string to byte array
     */
    fun hexToBytes(hexString: String): ByteArray {
        val length = hexString.length
        val byteArray = ByteArray(length / 2)
        
        for (i in 0 until length step 2) {
            val byteString = hexString.substring(i, i + 2)
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
        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short
    }
    
    /**
     * Get signed short (2 bytes) in big-endian format
     */
    fun getSignedShortBE(data: ByteArray, offset: Int): Short {
        return ByteBuffer.wrap(data, offset, 2).order(ByteOrder.BIG_ENDIAN).short
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
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
    
    /**
     * Get signed int (4 bytes) in big-endian format
     */
    fun getSignedIntBE(data: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    }
    
    /**
     * Convert short to byte array in little-endian format
     */
    fun shortToBytesLE(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }
    
    /**
     * Convert short to byte array in big-endian format
     */
    fun shortToBytesBE(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(value).array()
    }
    
    /**
     * Convert int to byte array in little-endian format
     */
    fun intToBytesLE(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
    
    /**
     * Convert int to byte array in big-endian format
     */
    fun intToBytesBE(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
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
}
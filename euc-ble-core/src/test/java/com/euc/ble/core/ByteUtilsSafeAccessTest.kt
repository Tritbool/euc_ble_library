// kotlin
package com.euc.ble.core

import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertNull
import org.junit.jupiter.api.Test

class ByteUtilsSafeAccessTest {

    @Test
    fun testTryGetUnsignedByte() {
        val b1 = byteArrayOf(0x7F.toByte())
        assertEquals(0x7F, ByteUtils.tryGetUnsignedByte(b1, 0))

        val b2 = byteArrayOf(0xFF.toByte())
        assertEquals(0xFF, ByteUtils.tryGetUnsignedByte(b2, 0))

        val empty = byteArrayOf()
        assertNull(ByteUtils.tryGetUnsignedByte(empty, 0))
        assertNull(ByteUtils.tryGetUnsignedByte(b1, 1))
    }

    @Test
    fun testTryGetSignedByte() {
        val p = byteArrayOf(0x7F.toByte())
        assertEquals(0x7F, ByteUtils.tryGetSignedByte(p, 0))

        val n = byteArrayOf(0xFF.toByte())
        assertEquals(-1, ByteUtils.tryGetSignedByte(n, 0))

        val empty = byteArrayOf()
        assertNull(ByteUtils.tryGetSignedByte(empty, 0))
    }

    @Test
    fun testTryGetUnsignedShortLE_BE() {
        val le = byteArrayOf(0x34.toByte(), 0x12.toByte())
        assertEquals(0x1234, ByteUtils.tryGetUnsignedShortLE(le, 0))

        val be = byteArrayOf(0x12.toByte(), 0x34.toByte())
        assertEquals(0x1234, ByteUtils.tryGetUnsignedShortBE(be, 0))

        val short = byteArrayOf(0x12.toByte())
        assertNull(ByteUtils.tryGetUnsignedShortLE(short, 0))
        assertNull(ByteUtils.tryGetUnsignedShortBE(short, 0))
    }

    @Test
    fun testTryGetSignedShortLE_BE() {
        val posLE = byteArrayOf(0x34.toByte(), 0x12.toByte())
        assertEquals(0x1234.toShort(), ByteUtils.tryGetSignedShortLE(posLE, 0))

        val negLE = byteArrayOf(0x3C.toByte(), 0xFE.toByte()) // 0xFE3C = -452
        assertEquals(0xFE3C.toShort(), ByteUtils.tryGetSignedShortLE(negLE, 0))

        val posBE = byteArrayOf(0x12.toByte(), 0x34.toByte())
        assertEquals(0x1234.toShort(), ByteUtils.tryGetSignedShortBE(posBE, 0))

        val negBE = byteArrayOf(0xFE.toByte(), 0x3C.toByte()) // 0xFE3C = -452
        assertEquals(0xFE3C.toShort(), ByteUtils.tryGetSignedShortBE(negBE, 0))

        val short = byteArrayOf(0x00.toByte())
        assertNull(ByteUtils.tryGetSignedShortLE(short, 0))
        assertNull(ByteUtils.tryGetSignedShortBE(short, 0))
    }

    @Test
    fun testTryGetUnsignedIntLE_BE() {
        val le = byteArrayOf(0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte())
        assertEquals(0x12345678.toLong(), ByteUtils.tryGetUnsignedIntLE(le, 0))

        val be = byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte())
        assertEquals(0x12345678.toLong(), ByteUtils.tryGetUnsignedIntBE(be, 0))

        val short = byteArrayOf(0x00.toByte(), 0x01.toByte(), 0x02.toByte())
        assertNull(ByteUtils.tryGetUnsignedIntLE(short, 0))
        assertNull(ByteUtils.tryGetUnsignedIntBE(short, 0))
    }

    @Test
    fun testTryGetSignedIntLE_BE() {
        val posLE = byteArrayOf(0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte())
        assertEquals(0x12345678, ByteUtils.tryGetSignedIntLE(posLE, 0))

        val negLE = byteArrayOf(0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0xFE.toByte()) // 0xFE345678
        assertEquals(0xFE345678.toInt(), ByteUtils.tryGetSignedIntLE(negLE, 0))

        val posBE = byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte())
        assertEquals(0x12345678, ByteUtils.tryGetSignedIntBE(posBE, 0))

        val negBE = byteArrayOf(0xFE.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte())
        assertEquals(0xFE345678.toInt(), ByteUtils.tryGetSignedIntBE(negBE, 0))

        val short = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte())
        assertNull(ByteUtils.tryGetSignedIntLE(short, 0))
        assertNull(ByteUtils.tryGetSignedIntBE(short, 0))
    }
}

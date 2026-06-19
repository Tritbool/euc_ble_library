package io.github.tritbool.euc.ble.core

import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for ByteUtils - the core byte manipulation utility
 */
class ByteUtilsTest {

    @Test
    fun testBytesToHex() {
        // Test basic conversion
        val bytes = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01.toByte(), 0xFF.toByte())
        val hex = ByteUtils.bytesToHex(bytes)
        assertEquals("AA 55 01 FF", hex)

        // Test with different separator
        val hexNoSpace = ByteUtils.bytesToHex(bytes, "")
        assertEquals("AA5501FF", hexNoSpace)

        // Test empty array
        val emptyHex = ByteUtils.bytesToHex(byteArrayOf())
        assertEquals("", emptyHex)

        // Test single byte
        val singleByte = byteArrayOf(0x0F.toByte())
        val singleHex = ByteUtils.bytesToHex(singleByte)
        assertEquals("0F", singleHex)
    }

    @Test
    fun testHexToBytes() {
        // Test basic conversion
        val hex = "AA5501FF"
        val bytes = ByteUtils.hexToBytes(hex)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01.toByte(), 0xFF.toByte()), bytes)

        // Test with spaces
        val hexWithSpaces = "AA 55 01 FF"
        val bytesWithSpaces = ByteUtils.hexToBytes(hexWithSpaces)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01.toByte(), 0xFF.toByte()), bytesWithSpaces)

        // Test empty string
        val emptyBytes = ByteUtils.hexToBytes("")
        assertArrayEquals(byteArrayOf(), emptyBytes)

        // Test single byte
        val singleByte = ByteUtils.hexToBytes("0F")
        assertArrayEquals(byteArrayOf(0x0F.toByte()), singleByte)
    }

    @Test
    fun testGetUnsignedByte() {
        // Test positive byte
        val positiveByte = byteArrayOf(0x7F.toByte())
        val positiveResult = ByteUtils.getUnsignedByte(positiveByte, 0)
        assertEquals(0x7F, positiveResult)

        // Test negative byte (should be converted to unsigned)
        val negativeByte = byteArrayOf(0xFF.toByte())
        val negativeResult = ByteUtils.getUnsignedByte(negativeByte, 0)
        assertEquals(0xFF, negativeResult)

        // Test zero
        val zeroByte = byteArrayOf(0x00.toByte())
        val zeroResult = ByteUtils.getUnsignedByte(zeroByte, 0)
        assertEquals(0x00, zeroResult)
    }

    @Test
    fun testGetUnsignedShortLE() {
        // Test basic little-endian conversion
        val data = byteArrayOf(0x34.toByte(), 0x12.toByte()) // 0x1234 = 4660
        val result = ByteUtils.getUnsignedShortLE(data, 0)
        assertEquals(0x1234, result)

        // Test with offset
        val dataWithOffset = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x78.toByte(), 0x56.toByte()) // 0x5678 = 22136
        val offsetResult = ByteUtils.getUnsignedShortLE(dataWithOffset, 2)
        assertEquals(0x5678, offsetResult)

        // Test maximum value
        val maxData = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) // 0xFFFF = 65535
        val maxResult = ByteUtils.getUnsignedShortLE(maxData, 0)
        assertEquals(0xFFFF, maxResult)
    }

    @Test
    fun testGetUnsignedShortBE() {
        // Test basic big-endian conversion
        val data = byteArrayOf(0x12.toByte(), 0x34.toByte()) // 0x1234 = 4660
        val result = ByteUtils.getUnsignedShortBE(data, 0)
        assertEquals(0x1234, result)

        // Test with offset
        val dataWithOffset = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x56.toByte(), 0x78.toByte()) // 0x5678 = 22136
        val offsetResult = ByteUtils.getUnsignedShortBE(dataWithOffset, 2)
        assertEquals(0x5678, offsetResult)

        // Test maximum value
        val maxData = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) // 0xFFFF = 65535
        val maxResult = ByteUtils.getUnsignedShortBE(maxData, 0)
        assertEquals(0xFFFF, maxResult)
    }

    @Test
    fun testGetSignedShortLE() {
        // Test positive value
        val positiveData = byteArrayOf(0x34.toByte(), 0x12.toByte()) // 0x1234 = 4660
        val positiveResult = ByteUtils.getSignedShortLE(positiveData, 0)
        assertEquals(0x1234.toShort(), positiveResult)

        // Test negative value (two's complement)
        val negativeData = byteArrayOf(0x3C.toByte(), 0xFE.toByte()) // 0xFE3C = -452
        val negativeResult = ByteUtils.getSignedShortLE(negativeData, 0)
        assertEquals(0xFE3C.toShort(), negativeResult)

        // Test maximum positive
        val maxPositive = byteArrayOf(0xFF.toByte(), 0x7F.toByte()) // 0x7FFF = 32767
        val maxPositiveResult = ByteUtils.getSignedShortLE(maxPositive, 0)
        assertEquals(0x7FFF.toShort(), maxPositiveResult)

        // Test maximum negative
        val maxNegative = byteArrayOf(0x00.toByte(), 0x80.toByte()) // 0x8000 = -32768
        val maxNegativeResult = ByteUtils.getSignedShortLE(maxNegative, 0)
        assertEquals(0x8000.toShort(), maxNegativeResult)
    }

    @Test
    fun testGetSignedShortBE() {
        // Test positive value
        val positiveData = byteArrayOf(0x12.toByte(), 0x34.toByte()) // 0x1234 = 4660
        val positiveResult = ByteUtils.getSignedShortBE(positiveData, 0)
        assertEquals(0x1234.toShort(), positiveResult)

        // Test negative value (two's complement)
        val negativeData = byteArrayOf(0xFE.toByte(), 0x3C.toByte()) // 0xFE3C = -452
        val negativeResult = ByteUtils.getSignedShortBE(negativeData, 0)
        assertEquals(0xFE3C.toShort(), negativeResult)

        // Test maximum positive
        val maxPositive = byteArrayOf(0x7F.toByte(), 0xFF.toByte()) // 0x7FFF = 32767
        val maxPositiveResult = ByteUtils.getSignedShortBE(maxPositive, 0)
        assertEquals(0x7FFF.toShort(), maxPositiveResult)

        // Test maximum negative
        val maxNegative = byteArrayOf(0x80.toByte(), 0x00.toByte()) // 0x8000 = -32768
        val maxNegativeResult = ByteUtils.getSignedShortBE(maxNegative, 0)
        assertEquals(0x8000.toShort(), maxNegativeResult)
    }

    @Test
    fun testGetUnsignedIntLE() {
        // Test basic little-endian conversion
        val data = byteArrayOf(0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte()) // 0x12345678 = 305419896
        val result = ByteUtils.getUnsignedIntLE(data, 0)
        assertEquals(0x12345678.toLong(), result)

        // Test with offset
        val dataWithOffset = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte())
        val offsetResult = ByteUtils.getUnsignedIntLE(dataWithOffset, 2)
        assertEquals(0x12345678.toLong(), offsetResult)

        // Test maximum value
        val maxData = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) // 0xFFFFFFFF = 4294967295
        val maxResult = ByteUtils.getUnsignedIntLE(maxData, 0)
        assertEquals(0xFFFFFFFF, maxResult)
    }

    @Test
    fun testGetUnsignedIntBE() {
        // Test basic big-endian conversion
        val data = byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()) // 0x12345678 = 305419896
        val result = ByteUtils.getUnsignedIntBE(data, 0)
        assertEquals(0x12345678.toLong(), result)

        // Test with offset
        val dataWithOffset = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte())
        val offsetResult = ByteUtils.getUnsignedIntBE(dataWithOffset, 2)
        assertEquals(0x12345678.toLong(), offsetResult)

        // Test maximum value
        val maxData = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) // 0xFFFFFFFF = 4294967295
        val maxResult = ByteUtils.getUnsignedIntBE(maxData, 0)
        assertEquals(0xFFFFFFFF, maxResult)
    }

    @Test
    fun testGetSignedIntLE() {
        // Test positive value
        val positiveData = byteArrayOf(0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte()) // 0x12345678 = 305419896
        val positiveResult = ByteUtils.getSignedIntLE(positiveData, 0)
        assertEquals(0x12345678,positiveResult)

        // Test negative value (two's complement)
        val negativeData = byteArrayOf(0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0xFE.toByte()) // 0xFE345678 = -29189640
        val negativeResult = ByteUtils.getSignedIntLE(negativeData, 0)
        assertEquals(0xFE345678.toInt(), negativeResult)

        // Test maximum positive
        val maxPositive = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F.toByte()) // 0x7FFFFFFF = 2147483647
        val maxPositiveResult = ByteUtils.getSignedIntLE(maxPositive, 0)
        assertEquals(0x7FFFFFFF, maxPositiveResult)

        // Test maximum negative
        val maxNegative = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x80.toByte()) // 0x80000000 = -2147483648
        val maxNegativeResult = ByteUtils.getSignedIntLE(maxNegative, 0)
        assertEquals(0x80000000.toInt(), maxNegativeResult)
    }

    @Test
    fun testGetSignedIntBE() {
        // Test positive value
        val positiveData = byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()) // 0x12345678 = 305419896
        val positiveResult = ByteUtils.getSignedIntBE(positiveData, 0)
        assertEquals(0x12345678, positiveResult)

        // Test negative value (two's complement)
        val negativeData = byteArrayOf(0xFE.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()) // 0xFE345678 = -29189640
        val negativeResult = ByteUtils.getSignedIntBE(negativeData, 0)
        assertEquals(0xFE345678.toInt(), negativeResult)

        // Test maximum positive
        val maxPositive = byteArrayOf(0x7F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) // 0x7FFFFFFF = 2147483647
        val maxPositiveResult = ByteUtils.getSignedIntBE(maxPositive, 0)
        assertEquals(0x7FFFFFFF, maxPositiveResult)

        // Test maximum negative
        val maxNegative = byteArrayOf(0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()) // 0x80000000 = -2147483648
        val maxNegativeResult = ByteUtils.getSignedIntBE(maxNegative, 0)
        assertEquals(0x80000000.toInt(), maxNegativeResult)
    }

    @Test
    fun testShortToBytesLE() {
        // Test positive value
        val positiveShort: Short = 0x1234 // 4660
        val positiveBytes = ByteUtils.shortToBytesLE(positiveShort)
        assertArrayEquals(byteArrayOf(0x34.toByte(), 0x12.toByte()), positiveBytes)

        // Test negative value
        val negativeShort: Short = 0xFE3C.toShort() // -452
        val negativeBytes = ByteUtils.shortToBytesLE(negativeShort)
        assertArrayEquals(byteArrayOf(0x3C.toByte(), 0xFE.toByte()), negativeBytes)

        // Test maximum positive
        val maxPositive: Short = 0x7FFF.toShort() // 32767
        val maxPositiveBytes = ByteUtils.shortToBytesLE(maxPositive)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F.toByte()), maxPositiveBytes)

        // Test maximum negative
        val maxNegative: Short = 0x8000.toShort()// -32768
        val maxNegativeBytes = ByteUtils.shortToBytesLE(maxNegative)
        assertArrayEquals(byteArrayOf(0x00.toByte(), 0x80.toByte()), maxNegativeBytes)
    }

    @Test
    fun testShortToBytesBE() {
        // Test positive value
        val positiveShort: Short = 0x1234 //4660
        val positiveBytes = ByteUtils.shortToBytesBE(positiveShort)
        assertArrayEquals(byteArrayOf(0x12.toByte(), 0x34.toByte()), positiveBytes)

        // Test negative value
        val negativeShort: Short = 0xFE3C.toShort()//-452
        val negativeBytes = ByteUtils.shortToBytesBE(negativeShort)
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0x3C.toByte()), negativeBytes)

        // Test maximum positive
        val maxPositive: Short = 0x7FFF.toShort() //32767
        val maxPositiveBytes = ByteUtils.shortToBytesBE(maxPositive)
        assertArrayEquals(byteArrayOf(0x7F.toByte(), 0xFF.toByte()), maxPositiveBytes)

        // Test maximum negative
        val maxNegative: Short = 0x8000.toShort() //-32768
        val maxNegativeBytes = ByteUtils.shortToBytesBE(maxNegative)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x00.toByte()), maxNegativeBytes)
    }

    @Test
    fun testIntToBytesLE() {
        // Test positive value
        val positiveInt = 0x12345678 // 305419896
        val positiveBytes = ByteUtils.intToBytesLE(positiveInt)
        assertArrayEquals(byteArrayOf(0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0x12.toByte()), positiveBytes)

        // Test negative value
        val negativeInt = 0xFE345678.toInt() // -29189640
        val negativeBytes = ByteUtils.intToBytesLE(negativeInt)
        assertArrayEquals(byteArrayOf(0x78.toByte(), 0x56.toByte(), 0x34.toByte(), 0xFE.toByte()), negativeBytes)

        // Test maximum positive
        val maxPositive = 0x7FFFFFFF // 2147483647
        val maxPositiveBytes = ByteUtils.intToBytesLE(maxPositive)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F.toByte()), maxPositiveBytes)

        // Test maximum negative
        val maxNegative = 0x80000000.toInt() // -2147483648
        val maxNegativeBytes = ByteUtils.intToBytesLE(maxNegative)
        assertArrayEquals(byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x80.toByte()), maxNegativeBytes)
    }

    @Test
    fun testIntToBytesBE() {
        // Test positive value
        val positiveInt = 0x12345678
        val positiveBytes = ByteUtils.intToBytesBE(positiveInt)
        assertArrayEquals(byteArrayOf(0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()), positiveBytes)

        // Test negative value
        val negativeInt = 0xFE345678.toInt()
        val negativeBytes = ByteUtils.intToBytesBE(negativeInt)
        assertArrayEquals(byteArrayOf(0xFE.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()), negativeBytes)

        // Test maximum positive
        val maxPositive = 0x7FFFFFFF
        val maxPositiveBytes = ByteUtils.intToBytesBE(maxPositive)
        assertArrayEquals(byteArrayOf(0x7F.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), maxPositiveBytes)

        // Test maximum negative
        val maxNegative = 0x80000000.toInt()
        val maxNegativeBytes = ByteUtils.intToBytesBE(maxNegative)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()), maxNegativeBytes)
    }

    @Test
    fun testCalculateChecksum() {
        // Test basic checksum
        val data = byteArrayOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte())
        val checksum = ByteUtils.calculateChecksum(data, 0, 4)
        assertEquals(0x0A.toByte(), checksum) // 1+2+3+4 = 10

        // Test with offset
        val dataWithOffset = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x05.toByte(), 0x06.toByte(), 0x07.toByte())
        val offsetChecksum = ByteUtils.calculateChecksum(dataWithOffset, 2, 3)
        assertEquals(0x12.toByte(), offsetChecksum) // 5+6+7 = 18

        // Test empty range
        val emptyChecksum = ByteUtils.calculateChecksum(data, 0, 0)
        assertEquals(0x00.toByte(), emptyChecksum)

        // Test overflow
        val overflowData = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val overflowChecksum = ByteUtils.calculateChecksum(overflowData, 0, 3)
        assertEquals(0xFD.toByte(), overflowChecksum) // 255+255+255 = 765, 765 mod 256 = 253 (0xFD)
    }

    @Test
    fun testCalculateXorChecksum() {
        // Test basic XOR checksum
        val data = byteArrayOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte())
        val checksum = ByteUtils.calculateXorChecksum(data, 0, 4)
        assertEquals(0x04.toByte(), checksum) // 1 XOR 2 XOR 3 XOR 4 = 4

        // Test with offset
        val dataWithOffset = byteArrayOf(0x00.toByte(), 0x00.toByte(), 0x05.toByte(), 0x06.toByte(), 0x07.toByte())
        val offsetChecksum = ByteUtils.calculateXorChecksum(dataWithOffset, 2, 3)
        assertEquals(0x04.toByte(), offsetChecksum) // 5 XOR 6 XOR 7 = 4

        // Test empty range
        val emptyChecksum = ByteUtils.calculateXorChecksum(data, 0, 0)
        assertEquals(0x00.toByte(), emptyChecksum)

        // Test XOR properties
        val xorData = byteArrayOf(0xFF.toByte(), 0xFF.toByte())
        val xorChecksum = ByteUtils.calculateXorChecksum(xorData, 0, 2)
        assertEquals(0x00.toByte(), xorChecksum) // FF XOR FF = 0
    }

    @Test
    fun testStartsWith() {
        // Test positive match
        val data = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01.toByte(), 0x02.toByte())
        val pattern = byteArrayOf(0xAA.toByte(), 0x55.toByte())
        assertTrue(ByteUtils.startsWith(data, pattern))

        // Test negative match
        val wrongPattern = byteArrayOf(0xBB.toByte(), 0xCC.toByte())
        assertFalse(ByteUtils.startsWith(data, wrongPattern))

        // Test partial match
        val partialPattern = byteArrayOf(0xAA.toByte())
        assertTrue(ByteUtils.startsWith(data, partialPattern))

        // Test data shorter than pattern
        val shortData = byteArrayOf(0xAA.toByte())
        assertFalse(ByteUtils.startsWith(shortData, pattern))

        // Test empty pattern (should always return true)
        val emptyPattern = byteArrayOf()
        assertTrue(ByteUtils.startsWith(data, emptyPattern))
    }

    @Test
    fun testFindPattern() {
        // Test pattern found
        val data = byteArrayOf(0x01.toByte(), 0x02.toByte(), 0xAA.toByte(), 0x55.toByte(), 0x03.toByte())
        val pattern = byteArrayOf(0xAA.toByte(), 0x55.toByte())
        val index = ByteUtils.findPattern(data, pattern)
        assertEquals(2, index)

        // Test pattern not found
        val wrongPattern = byteArrayOf(0xBB.toByte(), 0xCC.toByte())
        val notFoundIndex = ByteUtils.findPattern(data, wrongPattern)
        assertEquals(-1, notFoundIndex)

        // Test pattern at beginning
        val startPattern = byteArrayOf(0x01.toByte(), 0x02.toByte())
        val startIndex = ByteUtils.findPattern(data, startPattern)
        assertEquals(0, startIndex)

        // Test pattern at end
        val endData = byteArrayOf(0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0xAA.toByte(), 0x55.toByte())
        val endIndex = ByteUtils.findPattern(endData, pattern)
        assertEquals(3, endIndex)

        // Test data shorter than pattern
        val shortData = byteArrayOf(0xAA.toByte())
        val shortIndex = ByteUtils.findPattern(shortData, pattern)
        assertEquals(-1, shortIndex)

        // Test empty pattern (should return 0)
        val emptyPattern = byteArrayOf()
        val emptyIndex = ByteUtils.findPattern(data, emptyPattern)
        assertEquals(0, emptyIndex)

        // Test multiple occurrences (should return first)
        val multiData = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01.toByte(), 0xAA.toByte(), 0x55.toByte())
        val multiIndex = ByteUtils.findPattern(multiData, pattern)
        assertEquals(0, multiIndex)
    }

    @Test
    fun testRoundTripConversions() {
        // Test short round-trip (LE)
        val originalShort: Short = 12345
        val shortBytes = ByteUtils.shortToBytesLE(originalShort)
        val recoveredShort = ByteUtils.getSignedShortLE(shortBytes, 0)
        assertEquals(originalShort, recoveredShort)

        // Test short round-trip (BE)
        val originalShortBE: Short = 12345
        val shortBytesBE = ByteUtils.shortToBytesBE(originalShortBE)
        val recoveredShortBE = ByteUtils.getSignedShortBE(shortBytesBE, 0)
        assertEquals(originalShortBE, recoveredShortBE)

        // Test int round-trip (LE)
        val originalInt = 123456789
        val intBytes = ByteUtils.intToBytesLE(originalInt)
        val recoveredInt = ByteUtils.getSignedIntLE(intBytes, 0)
        assertEquals(originalInt, recoveredInt)

        // Test int round-trip (BE)
        val originalIntBE = 123456789
        val intBytesBE = ByteUtils.intToBytesBE(originalIntBE)
        val recoveredIntBE = ByteUtils.getSignedIntBE(intBytesBE, 0)
        assertEquals(originalIntBE, recoveredIntBE)

        // Test hex round-trip
        val originalBytes = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01.toByte(), 0xFF.toByte())
        val hexString = ByteUtils.bytesToHex(originalBytes, "")
        val recoveredBytes = ByteUtils.hexToBytes(hexString)
        assertArrayEquals(originalBytes, recoveredBytes)
    }
}

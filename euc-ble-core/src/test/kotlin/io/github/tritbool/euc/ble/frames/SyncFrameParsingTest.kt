package io.github.tritbool.euc.ble.frames

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncFrameParsingTest {

    @Test
    fun `feed returns reassembled frames synchronously across fragments`() {
        val reassembler = FrameReassembler(
            FixedSizeFrameParser(4, header = byteArrayOf(0x55, 0xAA.toByte()))
        )
        assertTrue(reassembler.feed(byteArrayOf(0x00, 0x55)).isEmpty())
        val frames = reassembler.feed(byteArrayOf(0xAA.toByte(), 0x01, 0x02, 0x55, 0xAA.toByte(), 0x03))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(0x55, 0xAA.toByte(), 0x01, 0x02), frames[0])
        val next = reassembler.feed(byteArrayOf(0x04))
        assertArrayEquals(byteArrayOf(0x55, 0xAA.toByte(), 0x03, 0x04), next.single())
    }

    @Test
    fun `reset drops partial frames`() {
        val reassembler = FrameReassembler(FixedSizeFrameParser(3, header = byteArrayOf(0x55)))
        reassembler.feed(byteArrayOf(0x55, 0x01))
        reassembler.reset()
        assertTrue(reassembler.feed(byteArrayOf(0x02)).isEmpty())
    }

    @Test
    fun `fixed-size parser without header extracts consecutive frames`() {
        val parser = FixedSizeFrameParser(3)
        val frames = parser.appendAndExtract(byteArrayOf(1, 2, 3, 4, 5))
        assertArrayEquals(byteArrayOf(1, 2, 3), frames.single())
        val next = parser.appendAndExtract(byteArrayOf(6, 7, 8, 9))
        assertEquals(2, next.size)
        assertArrayEquals(byteArrayOf(4, 5, 6), next[0])
        assertArrayEquals(byteArrayOf(7, 8, 9), next[1])
    }

    @Test
    fun `fixed-size parser without header resynchronises on footer`() {
        val parser = FixedSizeFrameParser(3, footer = byteArrayOf(0x5A))
        val frames = parser.appendAndExtract(byteArrayOf(0x09, 1, 2, 0x5A, 3, 4, 0x5A))
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(1, 2, 0x5A), frames[0])
        assertArrayEquals(byteArrayOf(3, 4, 0x5A), frames[1])
    }

    @Test
    fun `flow output queue reports overflow instead of silently dropping frames`() {
        val reassembler = FrameReassembler(FixedSizeFrameParser(1))

        val error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            reassembler.processIncomingBytes(ByteArray(257) { it.toByte() })
        }

        assertTrue(error.message!!.contains("capacity=256"))
    }
}

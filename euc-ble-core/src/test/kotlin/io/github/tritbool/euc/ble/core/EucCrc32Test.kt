package io.github.tritbool.euc.ble.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EucCrc32Test {
    @Test
    fun `matches standard IEEE CRC32 vectors and ranges`() {
        assertEquals(0xCBF43926L, EucCrc32.calculate("123456789".encodeToByteArray()))
        assertEquals(0L, EucCrc32.calculate(byteArrayOf()))
        assertEquals(0xCBF43926L,
            EucCrc32.calculate(byteArrayOf(0, *"123456789".encodeToByteArray(), 0), 1, 9))
    }

    @Test
    fun `rejects invalid ranges`() {
        assertThrows(IllegalArgumentException::class.java) { EucCrc32.calculate(byteArrayOf(1), -1, 1) }
        assertThrows(IllegalArgumentException::class.java) { EucCrc32.calculate(byteArrayOf(1), 0, 2) }
    }
}

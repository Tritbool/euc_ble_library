package io.github.tritbool.euc.ble.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EucUuidTest {
    @Test
    fun `parses and normalizes canonical UUID strings`() {
        val uuid = EucUuid.parse("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", uuid.value)
        assertEquals(uuid, EucUuid.fromString(uuid.toString()))
    }

    @Test
    fun `rejects malformed or abbreviated UUID strings`() {
        for (invalid in listOf("", "0000ffe0", "00000000-0000-0000-0000-00000000000g",
                "00000000_0000-0000-0000-000000000000")
        ) {
            assertThrows(IllegalArgumentException::class.java) { EucUuid.parse(invalid) }
        }
    }
}

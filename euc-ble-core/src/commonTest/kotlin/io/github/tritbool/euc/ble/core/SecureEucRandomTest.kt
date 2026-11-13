package io.github.tritbool.euc.ble.core

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SecureEucRandomTest {
    @Test
    fun `non power of two bounds reject biased tail values`() {
        val samples = ArrayDeque(listOf(-1, 18))
        val random = SecureEucRandom { uuid(samples.removeFirst()) }

        assertEquals(9, random.nextInt(10))
        assertTrue(samples.isEmpty())
    }

    @Test
    fun `power of two and maximum bounds use only random uuid bits`() {
        val random = SecureEucRandom { uuid(-4) }

        assertEquals(0, random.nextInt(1))
        assertEquals(15, random.nextInt(16))
        assertEquals(Int.MAX_VALUE - 1, random.nextInt(Int.MAX_VALUE))
    }

    @Test
    fun `invalid bounds fail before requesting entropy`() {
        val random = SecureEucRandom { error("Entropy must not be requested") }

        assertFailsWith<IllegalArgumentException> { random.nextInt(0) }
        assertFailsWith<IllegalArgumentException> { random.nextInt(-1) }
    }

    @Test
    fun `default uuid source supplies bounded protocol digits`() {
        val random = SecureEucRandom()

        repeat(100) {
            assertTrue(random.nextInt(10) in 0 until 10)
        }
    }

    private fun uuid(randomBits: Int): Uuid =
        Uuid.fromLongs(randomBits.toLong() shl 32 or 0x4000L, Long.MIN_VALUE)
}

package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.EucRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReconnectPolicyTest {
    @Test
    fun `exponential backoff adds bounded injected jitter and stops at retry limit`() {
        val random = EucRandom { bound -> bound - 1 }
        val policy = ReconnectPolicy(baseDelayMs = 100, maxDelayMs = 500, jitterMaxMs = 20)
        assertEquals(119, policy.delayForRetry(0, 3, random))
        assertEquals(219, policy.delayForRetry(1, 3, random))
        assertEquals(419, policy.delayForRetry(2, 3, random))
        assertNull(policy.delayForRetry(3, 3, random))
    }

    @Test
    fun `negative indexes limits and zero jitter are handled without invalid random bounds`() {
        val random = EucRandom { error("Random should not be called") }
        val policy = ReconnectPolicy(baseDelayMs = 100, maxDelayMs = 150, jitterMaxMs = 0)
        assertNull(policy.delayForRetry(-1, 3, random))
        assertNull(policy.delayForRetry(0, -1, random))
        assertEquals(100, policy.delayForRetry(0, 3, random))
        assertEquals(150, policy.delayForRetry(1, 3, random))
    }
}

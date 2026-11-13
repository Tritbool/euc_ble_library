package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.EucRandom

/** Session reconnect policy; the connector performs connection I/O. */
class ReconnectPolicy(
    private val baseDelayMs: Long = BLEConstants.RECONNECT_BASE_DELAY_MS,
    private val maxDelayMs: Long = BLEConstants.MAX_RECONNECT_DELAY_MS,
    private val jitterMaxMs: Int = BLEConstants.RECONNECT_JITTER_MAX_MS.toInt()
) {
    /** Returns null when [retryIndex] has reached [maxRetries]; the first retry uses index 0. */
    fun delayForRetry(retryIndex: Int, maxRetries: Int, random: EucRandom): Long? {
        if (retryIndex < 0 || retryIndex >= maxRetries.coerceAtLeast(0)) return null
        val multiplier = 1L shl retryIndex.coerceAtMost(30)
        val base = (baseDelayMs * multiplier).coerceAtMost(maxDelayMs)
        val jitter = if (jitterMaxMs > 0) random.nextInt(jitterMaxMs) else 0
        return (base + jitter).coerceAtMost(maxDelayMs)
    }
}

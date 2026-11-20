package io.github.tritbool.euc.ble.session

import kotlin.math.abs

/** Evaluates speed measurements using a session-supplied monotonic clock. */
class SpeedLimitGuard {
    private var speedKmh: Double? = null
    private var measuredAtMillis: Long = 0

    fun update(speedKmh: Double, nowMillis: Long) {
        this.speedKmh = speedKmh
        measuredAtMillis = nowMillis
    }

    fun reset() {
        speedKmh = null
    }

    fun rejection(nowMillis: Long): String? {
        val speed = speedKmh ?: return "No speed measurement is available"
        if (nowMillis < measuredAtMillis || nowMillis - measuredAtMillis > MAX_AGE_MILLIS) {
            return "The speed measurement is stale"
        }
        if (!speed.isFinite()) return "The speed measurement is invalid"
        if (abs(speed) >= MAX_SPEED_KMH) return "The wheel is moving"
        return null
    }

    companion object {
        const val MAX_SPEED_KMH = 2.0
        const val MAX_AGE_MILLIS = 2_000L
    }
}

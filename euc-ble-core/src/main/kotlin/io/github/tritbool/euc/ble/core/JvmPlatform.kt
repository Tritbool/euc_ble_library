package io.github.tritbool.euc.ble.core

import java.security.SecureRandom
import java.util.Calendar

// JVM implementations of the platform abstractions. Kept in a dedicated file so that a future
// KMP split can move them to jvmMain without touching the platform-independent contracts.

/** [EucClock] backed by the JVM system clock and default time zone. */
object SystemEucClock : EucClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun localDateTime(): EucDateTime {
        val now = Calendar.getInstance()
        return EucDateTime(
            year = now.get(Calendar.YEAR),
            month = now.get(Calendar.MONTH) + 1,
            dayOfMonth = now.get(Calendar.DAY_OF_MONTH),
            hour = now.get(Calendar.HOUR_OF_DAY),
            minute = now.get(Calendar.MINUTE),
            second = now.get(Calendar.SECOND),
        )
    }
}

/** [EucRandom] backed by [SecureRandom]. */
class SecureEucRandom(private val random: SecureRandom = SecureRandom()) : EucRandom {
    override fun nextInt(bound: Int): Int = random.nextInt(bound)
}

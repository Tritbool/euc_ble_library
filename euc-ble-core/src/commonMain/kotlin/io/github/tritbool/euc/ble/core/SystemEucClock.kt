package io.github.tritbool.euc.ble.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** [EucClock] backed by the system clock and current local time zone. */
@OptIn(ExperimentalTime::class)
object SystemEucClock : EucClock {
    override fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

    override fun localDateTime(): EucDateTime {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return EucDateTime(
            year = now.year,
            month = now.month.number,
            dayOfMonth = now.day,
            hour = now.hour,
            minute = now.minute,
            second = now.second,
        )
    }
}

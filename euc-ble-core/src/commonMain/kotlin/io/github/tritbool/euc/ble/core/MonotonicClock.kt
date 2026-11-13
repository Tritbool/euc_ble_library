package io.github.tritbool.euc.ble.core

import kotlin.time.TimeSource

internal object MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    fun millis(): Long = origin.elapsedNow().inWholeMilliseconds
}

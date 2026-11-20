/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 0.1.3 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.core

import kotlin.time.TimeSource

internal object MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    fun millis(): Long = origin.elapsedNow().inWholeMilliseconds
}

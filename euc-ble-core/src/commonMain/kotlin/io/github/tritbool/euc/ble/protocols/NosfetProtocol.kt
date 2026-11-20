/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 1.1.0 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.SystemEucClock
import kotlinx.coroutines.CoroutineScope

class NosfetProtocol(scope: CoroutineScope? = null, clock: EucClock = SystemEucClock) :
    LeaperkimProtocol(NosfetSyncProtocol(clock), scope) {
    override val manufacturer: String get() = "Nosfet"
}

/*
 *
 *  Copyright (C) 2026 Gauthier Le Bartz Lyan (Tritbool)
 *  euc_ble_library — version 1.2.0 — licence GPL-3.0
 *  https://github.com/Tritbool/euc_ble_library
 * /
 */

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.SystemEucClock
import kotlinx.coroutines.CoroutineScope

class ExtremeBullProtocol(
    scope: CoroutineScope? = null,
    logger: Logger = NoOpLogger(),
    clock: EucClock = SystemEucClock,
) : GotwayProtocol(ExtremeBullSyncProtocol(clock), scope, logger)
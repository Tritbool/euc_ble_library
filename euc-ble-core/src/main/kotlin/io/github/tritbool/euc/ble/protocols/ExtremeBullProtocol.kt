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
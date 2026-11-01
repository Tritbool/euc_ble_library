package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.core.NoOpLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class ExtremeBullProtocol(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    logger: Logger = NoOpLogger(),
) : GotwayProtocol(scope, logger) {

    override val manufacturer: String = "ExtremeBull"

    override fun matchesDeviceName(deviceName: String): Boolean {
        val lower = deviceName.lowercase()
        return lower.contains("extreme") || lower.contains("bull") ||
                lower.contains("commander") || lower.contains("rocket") ||
                lower.contains("griffin")
    }
}
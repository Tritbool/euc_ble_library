package io.github.tritbool.euc.ble.protocols

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class ExtremeBullProtocol(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) :
    GotwayProtocol(scope) {

    override val manufacturer: String = "ExtremeBull"

    override fun matchesDeviceName(deviceName: String): Boolean {
        val lower = deviceName.lowercase()
        return lower.contains("extreme") || lower.contains("bull") ||
                lower.contains("commander") || lower.contains("rocket") ||
                lower.contains("griffin")
    }
}
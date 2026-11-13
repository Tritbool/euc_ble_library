package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock

class ExtremeBullSyncProtocol(clock: EucClock) : GotwaySyncProtocol(clock) {
    override val manufacturer: String = "ExtremeBull"
    override val protocolId: String = "ExtremeBullProtocol"
    override val parentProtocolId: String = "GotwayProtocol"

    override fun matchesDeviceName(deviceName: String): Boolean {
        val lower = deviceName.lowercase()
        return lower.contains("extreme") || lower.contains("bull") ||
            lower.contains("commander") || lower.contains("rocket") || lower.contains("griffin")
    }
}

package io.github.tritbool.euc.ble.protocols

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt

class NosfetProtocol(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) :
    LeaperkimProtocol(scope) {

    override val manufacturer: String = "Nosfet"

    override fun matchesDeviceName(deviceName: String): Boolean {
        val lower = deviceName.lowercase()
        return lower.contains("nosfet") || lower.contains("apex") ||
               lower.contains("aero") || lower.contains("aeon")
    }

    override fun modelByMajorVersion(version: Int): String {
        return when (version) {
            42 -> "Nosfet Apex"
            43 -> "Nosfet Aero"
            44 -> "Nosfet Aeon"
            else -> "Nosfet"
        }
    }

    override fun extractMajorVersion(versionRaw: Int): Int = versionRaw / 100

    override fun formatVersion(versionRaw: Int): String {
        val major = versionRaw / 100
        val minor = 0
        val patch = versionRaw % 100
        return "%03d.%01d.%02d".format(major, minor, patch)
    }

    override fun estimateBatteryPercent(voltageRaw: Int, majorVersion: Int): Int {
        val battery = when (majorVersion) {
            43 -> ((voltageRaw - 9600) / (12525.0 - 9600.0) * 100.0)
            42, 44 -> ((voltageRaw - 11520) / (15030.0 - 11520.0) * 100.0)
            else -> ((voltageRaw - 9600) / (12525.0 - 9600.0) * 100.0)
        }
        return battery.roundToInt().coerceIn(0, 100)
    }
}

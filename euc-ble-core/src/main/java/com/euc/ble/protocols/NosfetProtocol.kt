package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.models.EUCDevice
import kotlin.math.roundToInt

class NosfetProtocol : LeaperkimProtocol() {

    override val manufacturer: String = "Nosfet"
    override val supportedModels: List<String> = listOf(
        "Nosfet Apex", "Nosfet Aero", "Nosfet Aeon"
    )

    override fun canHandle(device: EUCDevice): Boolean {
        val name = device.name
        return device.manufacturerId == BLEConstants.MANUFACTURER_LEAPERKIM ||
                device.manufacturerId == BLEConstants.MANUFACTURER_VETERAN ||
                name.contains("Nosfet", ignoreCase = true) ||
                name.contains("Apex", ignoreCase = true) ||
                name.contains("Aero", ignoreCase = true) ||
                name.contains("Aeon", ignoreCase = true)
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
        val minor = (versionRaw % 100) / 10
        val patch = versionRaw % 10
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

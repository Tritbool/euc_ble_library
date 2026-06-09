package com.euc.ble.integration

internal object BleBackendFactory {
    fun fromFlag(
        useLegacy: Boolean,
        frameworkBackend: BleBackend,
        legacyBackend: BleBackend
    ): SwitchableBleBackend {
        return SwitchableBleBackend(
            frameworkBackend = frameworkBackend,
            legacyBackend = legacyBackend,
            initialType = if (useLegacy) BleBackendType.LEGACY else BleBackendType.FRAMEWORK
        )
    }
}

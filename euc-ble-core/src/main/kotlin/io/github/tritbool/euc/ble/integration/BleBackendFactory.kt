package io.github.tritbool.euc.ble.integration

/**
 * Factory for creating [SwitchableBleBackend] instances.
 *
 * Provides a convenient way to create a switchable backend based on a configuration flag.
 */
internal object BleBackendFactory {
    /**
     * Creates a new [SwitchableBleBackend] that can switch between framework and legacy implementations.
     *
     * @param useLegacy If true, starts with the legacy backend; otherwise starts with framework backend
     * @param frameworkBackend The framework-based BLE backend
     * @param legacyBackend The legacy BLE backend
     * @return A new switchable backend instance
     */
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

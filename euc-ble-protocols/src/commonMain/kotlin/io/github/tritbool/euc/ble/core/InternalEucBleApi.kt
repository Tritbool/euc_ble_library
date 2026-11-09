package io.github.tritbool.euc.ble.core

/**
 * Marks core declarations that exist for platform connectors (e.g. `euc-ble-android`)
 * and are not part of the stable public API. They may change without notice.
 */
@RequiresOptIn(
    message = "Internal euc-ble API intended for platform connectors only.",
    level = RequiresOptIn.Level.ERROR
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class InternalEucBleApi

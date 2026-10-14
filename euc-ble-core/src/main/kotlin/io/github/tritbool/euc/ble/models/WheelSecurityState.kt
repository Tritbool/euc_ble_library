package io.github.tritbool.euc.ble.models

/** Authentication reported by the wheel, never inferred from a successful BLE write. */
enum class AuthenticationState {
    UNKNOWN,
    REQUIRED,
    AUTHENTICATED
}

/** Lock status reported by the wheel, never inferred from a lock/unlock request. */
enum class WheelLockState {
    UNKNOWN,
    LOCKED,
    UNLOCKED
}

data class WheelSecurityState(
    val authentication: AuthenticationState = AuthenticationState.UNKNOWN,
    val lock: WheelLockState = WheelLockState.UNKNOWN
)

/** A wheel notification, including repeated status reports. Contains no PIN or raw bytes. */
sealed interface WheelSecurityEvent {
    data class AuthenticationStatus(val state: AuthenticationState) : WheelSecurityEvent
    data class LockStatus(val state: WheelLockState) : WheelSecurityEvent
}

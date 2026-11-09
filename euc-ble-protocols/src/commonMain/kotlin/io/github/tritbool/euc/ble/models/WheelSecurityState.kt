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

enum class PasswordOperation {
    SET,
    CHANGE,
    REMOVE
}

enum class PasswordOperationStatus {
    CONFIRMED,
    /** No matching confirmation; this does not mean that the password was incorrect. */
    TIMEOUT_UNCONFIRMED
}

/** A security notification or local password-operation timeout. Contains no PIN or raw bytes. */
sealed interface WheelSecurityEvent {
    data class AuthenticationStatus(val state: AuthenticationState) : WheelSecurityEvent
    data class LockStatus(val state: WheelLockState) : WheelSecurityEvent
    data class PasswordOperationResult(
        val operation: PasswordOperation,
        val status: PasswordOperationStatus
    ) : WheelSecurityEvent
}

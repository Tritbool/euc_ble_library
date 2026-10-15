package io.github.tritbool.euc.ble.protocols

/** Explicit, short-lived KingSong password change input. Never persist this request. */
class PasswordChangeRequest(val oldPassword: String, val newPassword: String) {
    override fun toString(): String = "PasswordChangeRequest(<redacted>)"
}

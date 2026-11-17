package io.github.tritbool.euc.ble.protocols

/** Explicit, short-lived KingSong password change input. Never persist this request. */
class PasswordChangeRequest(val oldPassword: String, val newPassword: String) : CommandRecordValue {
    override fun commandFields(): Map<String, CommandValue> = mapOf(
        "oldPassword" to CommandValue.Text(oldPassword),
        "newPassword" to CommandValue.Text(newPassword),
    )

    override fun toString(): String = "PasswordChangeRequest(<redacted>)"
}

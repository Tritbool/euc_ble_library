/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 0.1.3 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.protocols

/** Explicit, short-lived KingSong password change input. Never persist this request. */
class PasswordChangeRequest(val oldPassword: String, val newPassword: String) : CommandRecordValue {
    override fun commandFields(): Map<String, CommandValue> = mapOf(
        "oldPassword" to CommandValue.Text(oldPassword),
        "newPassword" to CommandValue.Text(newPassword),
    )

    override fun toString(): String = "PasswordChangeRequest(<redacted>)"
}

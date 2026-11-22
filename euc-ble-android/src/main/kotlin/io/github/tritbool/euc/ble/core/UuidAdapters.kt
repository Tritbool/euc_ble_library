/*
 *
 *  Copyright (C) 2026 Gauthier Le Bartz Lyan (Tritbool)
 *  euc_ble_library — version 1.2.0 — licence GPL-3.0
 *  https://github.com/Tritbool/euc_ble_library
 * /
 */

package io.github.tritbool.euc.ble.core

import java.util.UUID

internal fun UUID.toEucUuid(): EucUuid = EucUuid.parse(toString())

internal fun EucUuid.toAndroidUuid(): UUID = UUID.fromString(value)

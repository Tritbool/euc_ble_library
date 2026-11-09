package io.github.tritbool.euc.ble.core

import java.util.UUID

internal fun UUID.toEucUuid(): EucUuid = EucUuid.parse(toString())

internal fun EucUuid.toAndroidUuid(): UUID = UUID.fromString(value)

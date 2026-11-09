package io.github.tritbool.euc.ble.core

/** Canonical UUID value usable by platform-neutral protocol and GATT-fingerprint code. */
@JvmInline
value class EucUuid private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        fun parse(value: String): EucUuid {
            require(value.length == 36 &&
                value[8] == '-' && value[13] == '-' && value[18] == '-' && value[23] == '-'
            ) { "Invalid UUID: $value" }
            for (index in value.indices) {
                if (index == 8 || index == 13 || index == 18 || index == 23) continue
                require(value[index].digitToIntOrNull(16) != null) { "Invalid UUID: $value" }
            }
            return EucUuid(value.lowercase())
        }

        fun fromString(value: String): EucUuid = parse(value)
    }
}

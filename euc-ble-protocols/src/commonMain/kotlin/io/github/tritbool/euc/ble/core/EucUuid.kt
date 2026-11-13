package io.github.tritbool.euc.ble.core

/** Canonical UUID value usable by platform-neutral protocol and GATT-fingerprint code. */
class EucUuid private constructor(
    val mostSignificantBits: ULong,
    val leastSignificantBits: ULong
) {
    val value: String
        get() {
            val hex = mostSignificantBits.toString(16).padStart(16, '0') +
                leastSignificantBits.toString(16).padStart(16, '0')
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
        }

    override fun toString(): String = value
    override fun equals(other: Any?): Boolean =
        other is EucUuid &&
            mostSignificantBits == other.mostSignificantBits &&
            leastSignificantBits == other.leastSignificantBits

    override fun hashCode(): Int =
        31 * mostSignificantBits.hashCode() + leastSignificantBits.hashCode()

    companion object {
        fun parse(value: String): EucUuid {
            require(value.length == 36 &&
                value[8] == '-' && value[13] == '-' && value[18] == '-' && value[23] == '-'
            ) { "Invalid UUID: $value" }
            for (index in value.indices) {
                if (index == 8 || index == 13 || index == 18 || index == 23) continue
                require(value[index].digitToIntOrNull(16) != null) { "Invalid UUID: $value" }
            }
            val hex = value.replace("-", "")
            return EucUuid(
                mostSignificantBits = hex.substring(0, 16).toULong(16),
                leastSignificantBits = hex.substring(16, 32).toULong(16)
            )
        }

        fun fromString(value: String): EucUuid = parse(value)
    }
}

package io.github.tritbool.euc.ble.protocols

/**
 * Typed command/query inputs shared by synchronous engines and session drivers.
 * The command type determines which variant is meaningful. Binary and integer-list values
 * own snapshots: modifying caller input or a returned copy cannot change a queued query.
 */
sealed interface CommandValue {
    data object None : CommandValue
    data class Integer(val value: Int) : CommandValue
    data class Toggle(val value: Boolean) : CommandValue

    /** Text can carry credentials; diagnostic output must not expose it. */
    data class Text(val value: String) : CommandValue {
        override fun toString(): String = "Text(<redacted>)"
    }

    class Bytes(value: ByteArray) : CommandValue {
        private val snapshot = value.copyOf()
        fun copyBytes(): ByteArray = snapshot.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Bytes && snapshot.contentEquals(other.snapshot)
        override fun hashCode(): Int = snapshot.contentHashCode()
        override fun toString(): String = "Bytes(${snapshot.size} bytes)"
    }

    class Integers(value: List<Int>) : CommandValue {
        private val snapshot = value.toList()
        fun copyValues(): List<Int> = snapshot.toList()

        override fun equals(other: Any?): Boolean =
            other is Integers && snapshot == other.snapshot
        override fun hashCode(): Int = snapshot.hashCode()
        override fun toString(): String = "Integers($snapshot)"
    }
}

package io.github.tritbool.euc.ble.protocols

/** Checked boundary for the historical JVM/Android `Any` command API. */
internal fun Any.toCommandValue(): CommandValue = when (this) {
    is CommandValue -> this
    Unit -> CommandValue.None
    is Int -> CommandValue.Integer(this)
    is Boolean -> CommandValue.Toggle(this)
    is String -> CommandValue.Text(this)
    is ByteArray -> CommandValue.Bytes(this)
    is IntArray -> CommandValue.Integers(toList())
    is List<*> -> CommandValue.Integers(map {
        require(it is Int) { "Command integer lists must contain only Int values" }
        it
    })
    else -> throw IllegalArgumentException("Unsupported command value type")
}

internal fun CommandValue.toLegacyValue(): Any = when (this) {
    CommandValue.None -> Unit
    is CommandValue.Integer -> value
    is CommandValue.Toggle -> value
    is CommandValue.Text -> value
    is CommandValue.Bytes -> copyBytes()
    is CommandValue.Integers -> copyValues().toIntArray()
    is PasswordChangeRequest -> this
}

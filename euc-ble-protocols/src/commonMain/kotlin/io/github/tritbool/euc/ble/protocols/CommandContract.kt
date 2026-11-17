package io.github.tritbool.euc.ble.protocols

/** Open input contract: new kinds can supply a validator without changing this interface. */
interface CommandInput {
    val kind: String

    /** A value-free explanation, or null when the input satisfies this contract. */
    fun validationError(value: CommandValue): String?

    data object None : CommandInput {
        override val kind = "none"
        override fun validationError(value: CommandValue) =
            if (value == CommandValue.None) null else "This command takes no value"
    }

    data object Boolean : CommandInput {
        override val kind = "boolean"
        override fun validationError(value: CommandValue) =
            if (value is CommandValue.Toggle) null else "Expected a boolean"
    }

    data class Range(
        val min: Int,
        val max: Int,
        val step: Int,
        val unit: String,
        val scale: Double = 1.0,
    ) : CommandInput {
        override val kind = "range"
        init {
            require(min <= max && step > 0 && scale > 0 && scale.isFinite())
        }
        override fun validationError(value: CommandValue): String? {
            val number = (value as? CommandValue.Integer)?.value ?: return "Expected an integer"
            return if (number in min..max && (number.toLong() - min) % step == 0L) null
            else "Integer is outside the advertised range or step"
        }
    }

    data class Option(val id: String, val value: Int)

    data class Choice(val options: List<Option>) : CommandInput {
        override val kind = "choice"
        init {
            require(options.isNotEmpty())
            require(options.map { it.id }.toSet().size == options.size)
            require(options.map { it.value }.toSet().size == options.size)
        }
        override fun validationError(value: CommandValue): String? =
            if (value is CommandValue.Integer && options.any { it.value == value.value }) null
            else "Expected an advertised option"
    }

    data class Text(
        val minLength: Int,
        val maxLength: Int,
        val pattern: String,
        val sensitive: kotlin.Boolean,
    ) : CommandInput {
        override val kind = "text"
        private val regex = Regex(pattern)
        init { require(minLength >= 0 && maxLength >= minLength) }
        override fun validationError(value: CommandValue): String? {
            val text = (value as? CommandValue.Text)?.value ?: return "Expected text"
            return if (text.length in minLength..maxLength && regex.matches(text)) null
            else "Text does not match the advertised format"
        }
    }

    data class Field(val id: String, val input: CommandInput)

    data class Tuple(val apiType: String, val fields: List<Field>) : CommandInput {
        override val kind = "tuple"
        override fun validationError(value: CommandValue): String? {
            val values = (value as? CommandValue.Integers)?.copyValues()
                ?: return "Expected an integer tuple"
            if (values.size != fields.size) return "Tuple has the wrong number of fields"
            return fields.zip(values).firstNotNullOfOrNull { (field, number) ->
                field.input.validationError(CommandValue.Integer(number))?.let { "${field.id}: $it" }
            }
        }
    }

    data class Record(val apiType: String, val fields: List<Field>) : CommandInput {
        override val kind = "record"
        override fun validationError(value: CommandValue): String? {
            val values = (value as? CommandRecordValue)?.commandFields()
                ?: return "Expected a structured command value"
            if (values.keys != fields.map { it.id }.toSet()) return "Record has unexpected or missing fields"
            return fields.firstNotNullOfOrNull { field ->
                field.input.validationError(values.getValue(field.id))?.let { "${field.id}: $it" }
            }
        }
    }

    data class RawPayload(val description: String, val valueTypes: Set<String>) : CommandInput {
        override val kind = "raw_payload"
        override fun validationError(value: CommandValue): String? = when {
            value is CommandValue.Bytes && "bytes" in valueTypes && value.copyBytes().isNotEmpty() -> null
            value is CommandValue.Text && "text" in valueTypes && value.value.isNotEmpty() -> null
            else -> "Expected a non-empty payload of an advertised type"
        }
    }

    /** The catalogue knows the command, but its input contract is not yet implemented. */
    data class Unsupported(val reason: String) : CommandInput {
        override val kind = "unknown"
        override fun validationError(value: CommandValue) = reason
    }
}

/** Extensible structured values, without platform reflection or a generic untyped map API. */
interface CommandRecordValue : CommandValue {
    fun commandFields(): Map<String, CommandValue>
}

enum class CommandEncoding { AVAILABLE, CONDITIONAL, UNAVAILABLE, UNKNOWN }

data class CommandDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val input: CommandInput,
    val encoding: CommandEncoding,
    val operation: String? = null,
    val hardwareLimits: String = "unknown",
) {
    /** Compatibility convenience, not the identity or an exhaustive command registry. */
    val commandType: CommandType? get() = CommandType.entries.firstOrNull { it.name == id }
}

enum class CommandRejection { UNKNOWN_COMMAND, UNAVAILABLE, INVALID_VALUE, UNSUPPORTED_INPUT, ENCODING_FAILED }

sealed interface CommandEncodingResult {
    /** Owned bytes. Queueing is not wheel acceptance or confirmation. */
    class Encoded(val payload: ByteArray) : CommandEncodingResult {
        override fun toString() = "Encoded(${payload.size} bytes)"
    }
    data class Rejected(val reason: CommandRejection, val message: String) : CommandEncodingResult
}

data class CommandContext(val model: String? = null, val dialect: String? = null)

internal data class CommandProfile(
    val id: String,
    val models: Set<String>,
    val dialects: Set<String>,
    val commands: List<CommandDescriptor>,
) {
    fun matches(context: CommandContext): kotlin.Boolean =
        (models.isEmpty() || context.model in models) &&
            (dialects.isEmpty() || context.dialect in dialects)
}

internal data class ProtocolCommandContract(
    val commands: List<CommandDescriptor>,
    val profiles: List<CommandProfile>,
)

/** Generated at build time from the library-authoritative JSON, with no runtime JSON dependency. */
object CommandCatalogue {
    val protocolIds: Set<String> get() = commandCatalogueData.keys.toSet()

    fun getCommands(protocolId: String, context: CommandContext = CommandContext()): List<CommandDescriptor> {
        val contract = commandCatalogueData[protocolId] ?: return emptyList()
        val matches = contract.profiles.filter { it.matches(context) }
        check(matches.size <= 1) { "Overlapping command profiles for $protocolId" }
        val commands = contract.commands.associateBy { it.id }.toMutableMap()
        matches.singleOrNull()?.commands?.forEach { commands[it.id] = it }
        return commands.values.toList()
    }

    internal fun supportedTypes(commands: List<CommandDescriptor>): Set<CommandType> =
        commands.filter { it.encoding != CommandEncoding.UNAVAILABLE && it.encoding != CommandEncoding.UNKNOWN }
            .mapNotNull { it.commandType }.toSet()
}

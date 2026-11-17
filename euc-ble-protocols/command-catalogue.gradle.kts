import groovy.json.JsonOutput
import groovy.json.JsonSlurper

val generateCommandCatalogue by tasks.registering {
    val catalogue = rootProject.layout.projectDirectory.file("docs/commands/commands.json")
    val output = layout.buildDirectory.dir("generated/commands")
    inputs.file(catalogue)
    outputs.dir(output)
    doLast {
        fun obj(value: Any?): Map<*, *> = requireNotNull(value as? Map<*, *>) { "Expected catalogue object" }
        fun array(value: Any?): List<*> = requireNotNull(value as? List<*>) { "Expected catalogue array" }
        fun text(value: Any?): String = requireNotNull(value as? String) { "Expected catalogue string" }
        fun quote(value: Any?): String = JsonOutput.toJson(text(value)).replace("$", "\\$")
        fun strings(value: Any?, constructor: String = "listOf"): String =
            array(value).joinToString(", ", "$constructor(", ")") { quote(it) }
        fun integer(value: Any?): Int =
            requireNotNull(value as? Number) { "Expected a command integer" }
                .toString().toBigDecimal().intValueExact()

        fun input(value: Any?): String {
            val spec = obj(value)
            return when (val kind = text(spec["kind"])) {
                "none" -> "CommandInput.None"
                "boolean" -> "CommandInput.Boolean"
                "range" -> {
                    val min = integer(spec["min"])
                    val max = integer(spec["max"])
                    val step = integer(spec["step"])
                    require(min <= max && step > 0) { "Invalid command range" }
                    val scale = requireNotNull((spec["scale"] ?: 1.0) as? Number).toDouble()
                    require(scale > 0 && scale.isFinite()) { "Invalid command scale" }
                    "CommandInput.Range($min, $max, $step, ${quote(spec["unit"])}, $scale)"
                }
                "choice" -> {
                    val options = array(spec["options"]).map(::obj)
                    require(options.isNotEmpty() && options.map { it["id"] }.distinct().size == options.size)
                    require(options.map { it["value"] }.distinct().size == options.size)
                    options.joinToString(", ", "CommandInput.Choice(listOf(", "))") {
                        "CommandInput.Option(${quote(it["id"])}, ${integer(it["value"])})"
                    }
                }
                "text" -> "CommandInput.Text(${integer(spec["minLength"])}, ${integer(spec["maxLength"])}, " +
                    "${quote(spec["pattern"])}, ${requireNotNull(spec["sensitive"] as? Boolean)})"
                "tuple", "record" -> {
                    val fields = array(spec["fields"]).map(::obj)
                    require(fields.isNotEmpty() && fields.map { it["id"] }.distinct().size == fields.size)
                    val type = if (kind == "tuple") "Tuple" else "Record"
                    fields.joinToString(", ", "CommandInput.$type(${quote(spec["apiType"])}, listOf(", "))") {
                        "CommandInput.Field(${quote(it["id"])}, ${input(it["input"])})"
                    }
                }
                "raw_payload" -> "CommandInput.RawPayload(${quote(spec["description"])}, ${strings(spec["valueTypes"], "setOf")})"
                "unknown" -> "CommandInput.Unsupported(${quote(spec["reason"])})"
                else -> error("Unsupported command input kind: $kind")
            }
        }

        fun commands(value: Any?): String = obj(value).entries.joinToString(",\n", "listOf(\n", "\n)") { (id, value) ->
            val command = obj(value)
            val encoding = text(command["encoding"]).uppercase()
            require(encoding in setOf("AVAILABLE", "CONDITIONAL", "UNAVAILABLE", "UNKNOWN"))
            "CommandDescriptor(id = ${quote(id)}, name = ${quote(command["name"])}, " +
                "description = ${quote(command["description"])}, input = ${input(command["input"])}, " +
                "encoding = CommandEncoding.$encoding, " +
                "operation = ${command["operation"]?.let(::quote) ?: "null"})"
        }

        val root = obj(JsonSlurper().parse(catalogue.asFile))
        require(root["schemaVersion"] == 1)
        val protocols = obj(root["protocols"])
        val functions = protocols.entries.mapIndexed { index, (id, value) ->
            val protocol = obj(value)
            require(protocol["hardwareLimits"] == "unknown") { "Unsupported hardware-limit representation" }
            val profiles = array(protocol["profiles"]).map(::obj)
            require(profiles.map { it["id"] }.distinct().size == profiles.size)
            for ((position, left) in profiles.withIndex()) {
                for (right in profiles.drop(position + 1)) {
                    val a = obj(left["match"])
                    val b = obj(right["match"])
                    require(listOf("models", "dialects").any {
                        it in a && it in b && array(a[it]).toSet().intersect(array(b[it]).toSet()).isEmpty()
                    }) { "Overlapping profiles for $id" }
                }
            }
            val generatedProfiles = profiles.joinToString(",\n", "listOf(", ")") {
                val match = obj(it["match"])
                require(match.isNotEmpty() && match.keys.all { it in setOf("models", "dialects") })
                "CommandProfile(${quote(it["id"])}, " +
                    "${strings(match["models"] ?: emptyList<String>(), "setOf")}, " +
                    "${strings(match["dialects"] ?: emptyList<String>(), "setOf")}, ${commands(it["commands"])})"
            }
            "private fun protocol$index() = ProtocolCommandContract(${commands(protocol["commands"])}, $generatedProfiles)"
        }
        val entries = protocols.keys.mapIndexed { index, id -> "${quote(id)} to protocol$index()" }
        output.get().file("CommandCatalogueData.kt").asFile.apply {
            parentFile.mkdirs()
            writeText(
                "package io.github.tritbool.euc.ble.protocols\n\n" +
                    "internal val commandCatalogueData = mapOf(\n${entries.joinToString(",\n")}\n)\n\n" +
                    functions.joinToString("\n\n") + "\n"
            )
        }
    }
}

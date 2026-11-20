/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 0.1.3 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucDateTime
import io.github.tritbool.euc.ble.core.EucRandom
import kotlin.test.*

class CommandContractTest {
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 100_000L
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }

    private fun kingsong() = KingsongSyncProtocol(clock, EucRandom { 0 }).also {
        it.onNotification(kingFrame(0xB5).also { frame ->
            frame[4] = 10
            frame[6] = 20
            frame[8] = 30
            frame[10] = 50
        })
        it.onNotification(kingFrame(0x5F).also { frame ->
            frame[2] = 1
            for (index in 4..9) frame[index] = '1'.code.toByte()
        })
        it.drainOutputs()
    }

    private fun inMotion(series: Int? = null, dialect: Int = 2) =
        InMotionSyncProtocol(clock).also {
            it.setDialect(dialect)
            if (series != null) {
                val frame = ByteUtils.hexToBytes("aaaa11088201020c0101010095")
                frame[7] = series.toByte()
                frame[frame.lastIndex] = (frame.last().toInt() xor 12 xor series).toByte()
                it.onNotification(frame)
                it.onNotification(ByteUtils.hexToBytes("aaaa1213028002ef8764a42b432de0f2e999991445637bde"))
                it.onNotification(ByteUtils.hexToBytes("aaaa12040280820117"))
            }
            it.drainOutputs()
        }

    private fun leaperkimTelemetry() = ByteArray(40).apply {
        this[0] = 0xDC.toByte()
        this[1] = 0x5A
        this[2] = 0x5C
        this[3] = 36
        this[4] = 0x30
        this[5] = 0xED.toByte()
        this[24] = 0x00
        this[25] = 20
        this[26] = 0x00
        this[27] = 30
    }

    private fun scenarios(): List<() -> SyncEucProtocol> = listOf(
        { GotwaySyncProtocol(clock) }, { ExtremeBullSyncProtocol(clock) },
        { LeaperkimSyncProtocol(clock).also { it.onNotification(leaperkimTelemetry()); it.drainOutputs() } },
        { NosfetSyncProtocol(clock).also { it.onNotification(leaperkimTelemetry()); it.drainOutputs() } },
        { kingsong() }, { NinebotSyncProtocol(clock) }, { NinebotZSyncProtocol(clock) },
        { InMotionSyncProtocol(clock) }, { inMotion(dialect = 1) },
        { inMotion() }, { inMotion(6) }, { inMotion(12) }, { inMotion(13) },
    )

    private fun prepared(factory: () -> SyncEucProtocol, command: String) = factory().also {
        if (command == "CANCEL_DIAGNOSTIC_LOG") {
            assertIs<CommandEncodingResult.Encoded>(it.encodeCommand("REQUEST_DIAGNOSTIC_LOG"))
            it.drainOutputs()
        }
    }

    @Test
    fun catalogueDomainsEncodeAcrossProtocolsAndProfiles() {
        val factories = scenarios()
        assertEquals(CommandCatalogue.protocolIds, factories.map { it().protocolId }.toSet())
        for (factory in factories) {
            val engine = factory()
            for (descriptor in engine.getCommandDescriptors()) {
                if (descriptor.encoding == CommandEncoding.UNAVAILABLE ||
                    descriptor.encoding == CommandEncoding.UNKNOWN) continue
                val values = validValues(descriptor.input)
                assertTrue(values.isNotEmpty(), "${engine.protocolId}/${descriptor.id} has no domain test")
                for (value in values) {
                    val subject = prepared(factory, descriptor.id)
                    val result = subject.encodeCommand(descriptor.id, value)
                    val encoded = assertIs<CommandEncodingResult.Encoded>(
                        result, "${subject.protocolId}/${descriptor.id}: $result"
                    )
                    assertTrue(encoded.payload.isNotEmpty())
                    assertPreservedFields(subject.protocolId, descriptor.id, value, encoded.payload)
                }
            }
        }
    }

    @Test
    fun invalidDomainsAreRejectedBeforeProtocolSideEffects() {
        for (factory in scenarios()) {
            for (descriptor in factory().getCommandDescriptors()) {
                if (descriptor.encoding == CommandEncoding.UNAVAILABLE ||
                    descriptor.encoding == CommandEncoding.UNKNOWN) continue
                for (value in invalidValues(descriptor.input)) {
                    val engine = prepared(factory, descriptor.id)
                    val result = assertIs<CommandEncodingResult.Rejected>(
                        engine.encodeCommand(descriptor.id, value), "${engine.protocolId}/${descriptor.id}"
                    )
                    assertEquals(CommandRejection.INVALID_VALUE, result.reason)
                    assertNull(engine.pollOutput(), "${engine.protocolId}/${descriptor.id} mutated output state")
                }
            }
        }
    }

    @Test
    fun currentIdentityAndResetControlDiscoveryAndSubmission() {
        val engine = inMotion(12)
        assertIs<CommandEncodingResult.Encoded>(
            engine.encodeCommand("SET_SPEED_LIMIT", CommandValue.Integer(655))
        )
        engine.resetSession()
        assertEquals(CommandRejection.UNAVAILABLE, assertIs<CommandEncodingResult.Rejected>(
            engine.encodeCommand("SET_SPEED_LIMIT", CommandValue.Integer(10))
        ).reason)
        assertEquals(CommandRejection.UNKNOWN_COMMAND, assertIs<CommandEncodingResult.Rejected>(
            engine.encodeCommand("vendor.missing")
        ).reason)
        val legacy = inMotion(dialect = 1)
        assertEquals(CommandRejection.UNKNOWN_COMMAND, assertIs<CommandEncodingResult.Rejected>(
            legacy.encodeCommand("REQUEST_DIAGNOSTIC_LOG")
        ).reason)
        val king = KingsongSyncProtocol(clock, EucRandom { 0 })
        assertEquals(CommandRejection.UNAVAILABLE, assertIs<CommandEncodingResult.Rejected>(
            king.encodeCommand("SET_SPEED_LIMIT", CommandValue.Integer(10))
        ).reason)
        assertNull(king.pollOutput())
        // The raw compatibility API intentionally keeps its previous fallback.
        assertTrue(king.createCommand(CommandType.SET_SPEED_LIMIT, CommandValue.Integer(10)).isNotEmpty())
    }

    @Test
    fun rangeStepsDoNotOverflowAtIntegerExtremes() {
        val range = CommandInput.Range(Int.MIN_VALUE, Int.MAX_VALUE, 3, "code")
        assertNull(range.validationError(CommandValue.Integer(Int.MIN_VALUE)))
        assertNull(range.validationError(CommandValue.Integer(Int.MAX_VALUE)))
        assertNotNull(range.validationError(CommandValue.Integer(0)))
        val stepped = CommandInput.Range(10, 20, 3, "code")
        for (value in 10..20) {
            assertEquals(value !in setOf(10, 13, 16, 19),
                stepped.validationError(CommandValue.Integer(value)) != null)
        }
    }

    @Test
    fun structuredValidationRejectsMissingExtraAndMalformedFieldsWithoutDisclosingSecrets() {
        val input = kingsong().getCommandDescriptors().first { it.id == "CHANGE_PASSWORD" }.input
        for (fields in listOf(
            emptyMap(),
            mapOf("oldPassword" to CommandValue.Text("1234")),
            mapOf("oldPassword" to CommandValue.Text("1234"), "newPassword" to CommandValue.Text("bad!")),
            mapOf("oldPassword" to CommandValue.Text("1234"), "newPassword" to CommandValue.Text("5678"),
                "extra" to CommandValue.None),
        )) {
            val value = object : CommandRecordValue {
                override fun commandFields() = fields
            }
            val error = assertNotNull(input.validationError(value))
            assertFalse(error.contains("1234"))
            assertFalse(error.contains("bad!"))
        }
    }

    @Test
    fun customInputValidatorsWorkAndUnknownKindsFailClosed() {
        var calls = 0
        val customInput = object : CommandInput {
            override val kind = "vendor.positive"
            override fun validationError(value: CommandValue): String? =
                if (value is CommandValue.Integer && value.value > 0) null else "Expected a positive integer"
        }
        var descriptor = CommandDescriptor(
            "vendor.new-command", "New command", "An extension command.", customInput, CommandEncoding.AVAILABLE
        )
        val engine = object : EucProtocolSpec {
            override val manufacturer = "Test"
            override fun getServiceUUID() = GotwaySyncProtocol(clock).getServiceUUID()
            override fun getDataCharacteristicUUID() = getServiceUUID()
            override fun isDeviceReady(data: io.github.tritbool.euc.ble.models.EUCData) = true
            override fun getCommandDescriptors() = listOf(descriptor)
            override fun createCommand(commandType: CommandType, value: CommandValue) =
                error("Open IDs must not fall through to enum encoding")
            override fun createCommand(commandId: String, value: CommandValue): ByteArray {
                calls++
                return byteArrayOf((value as CommandValue.Integer).value.toByte())
            }
        }
        assertNull(descriptor.commandType)
        assertContentEquals(byteArrayOf(7), assertIs<CommandEncodingResult.Encoded>(
            engine.encodeCommand(descriptor.id, CommandValue.Integer(7))
        ).payload)
        assertEquals(CommandRejection.INVALID_VALUE, assertIs<CommandEncodingResult.Rejected>(
            engine.encodeCommand(descriptor.id, CommandValue.Integer(0))
        ).reason)
        descriptor = descriptor.copy(input = CommandInput.Unsupported("Input kind needs library support."))
        assertEquals(CommandRejection.UNSUPPORTED_INPUT, assertIs<CommandEncodingResult.Rejected>(
            engine.encodeCommand(descriptor.id, CommandValue.Integer(7))
        ).reason)
        assertEquals(1, calls)
    }

    private fun validValues(input: CommandInput): List<CommandValue> = when (input) {
        CommandInput.None -> listOf(CommandValue.None)
        CommandInput.Boolean -> listOf(CommandValue.Toggle(false), CommandValue.Toggle(true))
        is CommandInput.Choice -> input.options.map { CommandValue.Integer(it.value) }
        is CommandInput.Range -> {
            val count = (input.max.toLong() - input.min) / input.step + 1
            val numbers = if (count <= 1000) {
                (0 until count.toInt()).map { input.min + it * input.step }
            } else {
                listOf(input.min, input.min + input.step, input.max - input.step, input.max, 0,
                    ((input.min.toLong() + input.max) / 2).toInt())
                    .filter { input.validationError(CommandValue.Integer(it)) == null }.distinct()
            }
            numbers.map { CommandValue.Integer(it) }
        }
        is CommandInput.Text -> listOf("0000", "0012", "9999").map { CommandValue.Text(it) }
        is CommandInput.Tuple -> {
            val base = input.fields.map { (validValues(it.input).first() as CommandValue.Integer).value }
            input.fields.flatMapIndexed { index, field ->
                validValues(field.input).map { value ->
                    CommandValue.Integers(base.toMutableList().also {
                        it[index] = (value as CommandValue.Integer).value
                    })
                }
            }
        }
        is CommandInput.Record -> {
            check(input.apiType == "PasswordChangeRequest") { "Add a typed test value for ${input.apiType}" }
            listOf(PasswordChangeRequest("0012", "0034"), PasswordChangeRequest("9999", "0000"))
        }
        is CommandInput.RawPayload -> buildList {
            if ("bytes" in input.valueTypes) add(CommandValue.Bytes(byteArrayOf(1, 2, 3)))
            if ("text" in input.valueTypes) add(CommandValue.Text("test"))
        }
        else -> error("Add domain tests for input kind ${input.kind}")
    }

    private fun invalidValues(input: CommandInput): List<CommandValue> = when (input) {
        CommandInput.None -> listOf(CommandValue.Integer(1))
        CommandInput.Boolean -> listOf(CommandValue.None, CommandValue.Integer(1))
        is CommandInput.Range -> buildList {
            add(CommandValue.Toggle(true))
            if (input.min > Int.MIN_VALUE) add(CommandValue.Integer(input.min - 1))
            if (input.max < Int.MAX_VALUE) add(CommandValue.Integer(input.max + 1))
        }
        is CommandInput.Choice -> listOf(CommandValue.None, CommandValue.Integer(Int.MIN_VALUE),
            CommandValue.Integer(Int.MAX_VALUE))
        is CommandInput.Text -> listOf("", "123", "12345", "12a4", "\uFF11\uFF12\uFF13\uFF14")
            .map { CommandValue.Text(it) } + CommandValue.Integer(1234)
        is CommandInput.Tuple -> listOf(
            CommandValue.None, CommandValue.Integers(listOf(10, 20)),
            CommandValue.Integers(listOf(10, 20, 30, 40)),
            CommandValue.Integers(listOf(-1, 20, 30)),
            CommandValue.Integers(listOf(10, 101, 30)),
            CommandValue.Integers(listOf(10, 20, 101)),
        )
        is CommandInput.Record -> listOf(
            CommandValue.None, PasswordChangeRequest("123", "1234"), PasswordChangeRequest("1234", "bad!"),
        )
        is CommandInput.RawPayload -> listOf(CommandValue.None, CommandValue.Bytes(byteArrayOf()),
            CommandValue.Text(""), CommandValue.Integer(0)) +
            if ("text" !in input.valueTypes) listOf(CommandValue.Text("test")) else emptyList()
        else -> error("Add rejection tests for input kind ${input.kind}")
    }

    private fun assertPreservedFields(protocol: String, command: String, value: CommandValue, bytes: ByteArray) {
        if (protocol == "KingsongProtocol") {
            when (command) {
                "SET_ALARM_SPEED" -> {
                    val alarms = (value as CommandValue.Integers).copyValues()
                    assertEquals(alarms.toList(), listOf(2, 4, 6).map { bytes[it].toInt() and 255 })
                    assertEquals(50, bytes[8].toInt() and 255)
                }
                "SET_SPEED_LIMIT" -> {
                    assertEquals(listOf(10, 20, 30), listOf(2, 4, 6).map { bytes[it].toInt() and 255 })
                    assertEquals((value as CommandValue.Integer).value, bytes[8].toInt() and 255)
                }
                "SET_CHARGE_LIMIT", "SET_STANDBY_DELAY", "SET_PEDAL_CUTOFF_ANGLE" ->
                    assertEquals((value as CommandValue.Integer).value, ByteUtils.getUnsignedShortLE(bytes, 4))
                "SET_PEDAL_PITCH_TRIM" ->
                    assertEquals((value as CommandValue.Integer).value.toShort().toInt(),
                        ByteUtils.getUnsignedShortLE(bytes, 4).toShort().toInt())
            }
        }
        if (protocol in setOf("LeaperkimProtocol", "NosfetProtocol") && command == "SET_PEDAL_ANGLE") {
            assertEquals((value as CommandValue.Integer).value, bytes[bytes.size - 5].toInt())
        }
    }

    private fun kingFrame(opcode: Int) = ByteArray(20).also {
        it[0] = 0xAA.toByte()
        it[1] = 0x55
        it[16] = opcode.toByte()
        it[17] = 0x14
        it[18] = 0x5A
        it[19] = 0x5A
    }
}

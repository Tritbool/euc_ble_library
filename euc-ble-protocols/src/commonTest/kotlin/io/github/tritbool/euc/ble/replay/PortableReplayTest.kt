package io.github.tritbool.euc.ble.replay

import io.github.tritbool.euc.ble.core.*
import io.github.tritbool.euc.ble.models.PasswordOperationStatus
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.protocols.*
import kotlin.test.*

class PortableReplayTest {
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 100_000L
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }
    private fun engines(): List<SyncEucProtocol> = listOf(
        GotwaySyncProtocol(clock), ExtremeBullSyncProtocol(clock),
        LeaperkimSyncProtocol(clock), NosfetSyncProtocol(clock),
        KingsongSyncProtocol(clock, EucRandom { 0 }),
        NinebotSyncProtocol(clock), NinebotZSyncProtocol(clock), InMotionSyncProtocol(clock)
    )

    @Test
    fun goldenCommandsMatchOnEveryTarget() {
        val engines = engines().associateBy { it.protocolId }
        assertEquals(engines.keys, commandFixtures.map { it.substringBefore('\t') }.toSet())
        for (row in commandFixtures) {
            val columns = row.split('\t')
            assertEquals(5, columns.size, row)
            val (protocol, command, kind, value, expected) = columns
            val input = when (kind) {
                "unit" -> CommandValue.None
                "hex" -> CommandValue.Bytes(ByteUtils.hexToBytes(value))
                "int" -> CommandValue.Integer(value.toInt())
                "bool" -> CommandValue.Toggle(value.toBooleanStrict())
                "ints" -> CommandValue.Integers(value.split(',').map(String::toInt))
                "password-change" -> value.split(',').let { PasswordChangeRequest(it[0], it[1]) }
                else -> error("Unknown fixture value kind: $kind")
            }
            val engine = engines.getValue(protocol)
            assertContentEquals(ByteUtils.hexToBytes(expected),
                engine.createCommand(CommandType.valueOf(command), input), row)
            engine.resetSession()
            assertNull(engine.pollOutput())
        }
    }

    @Test
    fun languageNeutralNotificationVectorsMatchTelemetryAndProtocolWrites() {
        val engines = engines().associateBy { it.protocolId }
        for (row in telemetryFixtures) {
            val (protocolId, chunks, expected) = row.split('\t')
            val engine = engines.getValue(protocolId)
            for (chunk in chunks.split(';')) {
                engine.onNotification(ByteUtils.hexToBytes(chunk))
            }
            val actual = engine.drainOutputs().mapNotNull { output ->
                when (output) {
                    is ProtocolOutput.Write -> "W|${output.payload.hex()}"
                    is ProtocolOutput.Telemetry -> {
                        val data = output.data
                        when (protocolId) {
                            "GotwayProtocol", "ExtremeBullProtocol" ->
                                "T|${data.speed}|${data.voltage}|${data.current}|${data.temperature}|" +
                                    "${data.distance}|${data.pwm}|${data.timestamp}"
                            "LeaperkimProtocol", "NosfetProtocol", "NinebotProtocol", "NinebotZProtocol",
                            "KingsongProtocol" -> "T|${data.speed}|${data.timestamp}"
                            "InMotionProtocol" -> "T|${data.voltage}|${data.timestamp}"
                            else -> error("No telemetry projection for $protocolId")
                        }
                    }
                    else -> null
                }
            }
            val expectedEvents = expected.split(';')
            assertEquals(expectedEvents.size, actual.size, row)
            expectedEvents.zip(actual).forEach { (expectedEvent, actualEvent) ->
                val expectedFields = expectedEvent.split('|')
                val actualFields = actualEvent.split('|')
                assertEquals(expectedFields.size, actualFields.size, row)
                expectedFields.zip(actualFields).forEach { (expectedField, actualField) ->
                    val number = expectedField.toDoubleOrNull()
                    if (number == null) assertEquals(expectedField, actualField, row)
                    else assertEquals(number, actualField.toDouble(), 1e-9, row)
                }
            }
        }
    }

    @Test
    fun acceptanceStartsPasswordTimerAndResetInvalidatesIt() {
        val engine = KingsongSyncProtocol(clock, EucRandom { 0 })
        val payload = engine.createCommand(CommandType.SET_PASSWORD, CommandValue.Text("0012"))
        assertNull(engine.pollOutput())
        engine.onCommandSubmitted(payload)
        val timer = assertIs<ProtocolOutput.StartTimer>(engine.pollOutput())
        assertEquals(3000L, timer.delayMs)
        engine.onTimer(timer.id)
        val event = engine.drainOutputs().filterIsInstance<ProtocolOutput.SecurityEvent>().single().event
        assertEquals(PasswordOperationStatus.TIMEOUT_UNCONFIRMED,
            assertIs<WheelSecurityEvent.PasswordOperationResult>(event).status)
        engine.resetSession()
        engine.onTimer(timer.id)
        assertNull(engine.pollOutput())
    }

    @Test
    fun inMotionV1EncodingEscapesCanIdAndPreservesChecksum() {
        val engine = InMotionSyncProtocol(clock)
        engine.setDialect(1)
        assertContentEquals(
            ByteUtils.hexToBytes("AAAA1601A5550FB200000011000000080500004B5555"),
            engine.createCommand(CommandType.BEEP)
        )
    }

    @Test
    fun fragmentedInMotionAuthenticationProducesIdenticalWrite() {
        val identity = ByteUtils.hexToBytes(
            "aaaa1662210286010001413134323139313136303031383341340000000000" +
                "020c010121320008010100090222260004071900080223100004010a00" +
                "0201240e00000001000101250e000000010001012f06000601010000" +
                "0045bd57a9210100220000230000ea"
        )
        for (split in 1 until identity.size) {
            val engine = InMotionSyncProtocol(clock)
            engine.onNotification(identity.copyOfRange(0, split))
            assertNull(engine.pollOutput(), "split=$split")
            engine.onNotification(identity.copyOfRange(split, identity.size))
            val write = engine.drainOutputs().filterIsInstance<ProtocolOutput.Write>().single()
            assertContentEquals(ByteUtils.hexToBytes("aaaa13040200000217"), write.payload)
            engine.resetSession()
            assertNull(engine.pollOutput())
        }
    }

    @Test
    fun portableUtilitiesPreserveSignedValuesAndCrc() {
        assertEquals(0xCBF43926L, EucCrc32.calculate("123456789".encodeToByteArray()))
        for (value in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) {
            assertEquals(value, ByteUtils.getSignedIntLE(ByteUtils.intToBytesLE(value), 0))
            assertEquals(value, ByteUtils.getSignedIntBE(ByteUtils.intToBytesBE(value), 0))
        }
        assertEquals(EucUuid.parse("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
            EucUuid.parse("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        assertFailsWith<IllegalArgumentException> { EucUuid.parse("not-a-uuid") }
    }

    @Test
    fun uuidStoresAll128BitsAndFormatsCanonicalBytes() {
        val uuid = EucUuid.parse("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF")
        assertEquals(ULong.MAX_VALUE, uuid.mostSignificantBits)
        assertEquals(ULong.MAX_VALUE, uuid.leastSignificantBits)
        assertEquals("ffffffff-ffff-ffff-ffff-ffffffffffff", uuid.value)
        assertEquals(uuid, EucUuid.parse(uuid.value))
    }

    @Test
    fun protocolOutboxFailsExplicitlyAtItsCapacity() {
        val outbox = ProtocolOutbox(capacity = 1)
        outbox.push(ProtocolOutput.DebugLog("test", "first"))

        assertFailsWith<IllegalStateException> {
            outbox.push(ProtocolOutput.DebugLog("test", "overflow"))
        }
        assertEquals(1, outbox.size)
    }

    @Test
    fun typedValuesEncodeSettingsAndPasswordsWithoutSessionRuntime() {
        val kingSong = KingsongSyncProtocol(clock, EucRandom { 0 })
        assertContentEquals(
            ByteUtils.hexToBytes("AA55140100000000000000000000000073145A5A"),
            kingSong.createCommand(CommandType.SET_LIGHT_MODE, CommandValue.Integer(2))
        )
        val alarms = kingSong.createCommand(
            CommandType.SET_ALARM_SPEED, CommandValue.Integers(listOf(10, 20, 30))
        )
        assertContentEquals(ByteUtils.hexToBytes("AA550A0014001E00000000000000000085145A5A"), alarms)
        assertContentEquals(ByteUtils.hexToBytes("AA55303033343030313200000000000041145A5A"),
            kingSong.createCommand(CommandType.CHANGE_PASSWORD, PasswordChangeRequest("0012", "0034")))

        val leaperkim = LeaperkimSyncProtocol(clock)
        assertContentEquals(ByteUtils.hexToBytes("4C6B41700D0180800157ED3BD54C6441700D010080016FF832F9"),
            leaperkim.createCommand(CommandType.SET_HIGH_BEAM, CommandValue.Toggle(true)))
        assertContentEquals("custom".encodeToByteArray(),
            leaperkim.createCommand(CommandType.CUSTOM, CommandValue.Text("custom")))
        assertTrue(kingSong.createCommand(
            CommandType.SET_LIGHT_MODE, CommandValue.Text("wrong kind")
        ).isEmpty())
        assertNull(kingSong.pollOutput())
    }

    @Test
    fun commandValuesOwnSnapshotsAndDoNotExposeCredentialTextInDiagnostics() {
        val bytes = byteArrayOf(1, 2, 3)
        val payload = CommandValue.Bytes(bytes)
        val query = ProtocolQuerySpec("snapshot", CommandType.CUSTOM, payload)
        bytes.fill(0)
        payload.copyBytes().fill(0)
        assertEquals(CommandValue.Bytes(byteArrayOf(1, 2, 3)), query.value)
        assertEquals(payload.hashCode(), query.value.hashCode())

        val engine = InMotionSyncProtocol(clock)
        engine.createCommand(query.commandType, query.value).fill(0)
        assertContentEquals(byteArrayOf(1, 2, 3), engine.createCommand(query.commandType, query.value))
        val source = mutableListOf(10, 20, 30)
        val integers = CommandValue.Integers(source)
        source[0] = 99
        assertEquals(listOf(10, 20, 30), integers.copyValues())
        assertEquals(CommandValue.Integers(listOf(10, 20, 30)), integers)

        val text = CommandValue.Text("0012")
        val change = PasswordChangeRequest("0012", "0034")
        assertFalse(text.toString().contains("0012"))
        assertFalse(change.toString().contains("0012"))
        assertFalse(change.toString().contains("0034"))
    }

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

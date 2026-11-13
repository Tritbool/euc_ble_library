package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucRandom
import io.github.tritbool.euc.ble.core.Logger
import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.core.SystemEucClock
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.session.ProtocolSessionDriver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.github.tritbool.euc.ble.core.EucUuid as UUID

class EUCProtocolContractTest {
    @Test
    fun protocolDefaultsAreSafeForProtocolsWithoutOptionalFeatures() = runTest {
        val protocol = TestProtocol()

        assertEquals(emptyList<ByteArray>(), protocol.rawFrameFlow.toList())
        assertTrue(protocol.supportedCommandTypes.isEmpty())
        assertEquals(CommandSupport.UNSUPPORTED, protocol.getCommandSupport(CommandType.LOCK))
        assertFalse(protocol.matchesQueryResponse(query, byteArrayOf(0x01)))
        assertEquals(ProtocolWriteType.AUTO, protocol.preferredWriteType)
        assertNull(protocol.getBMSData())
    }

    @Test
    fun rawFrameFlowCanExposeProtocolNotifications() = runTest {
        val frames = listOf(byteArrayOf(0x01), byteArrayOf(0x02, 0x03))
        val protocol = TestProtocol(rawFrameFlow = flowOf(*frames.toTypedArray()))

        val received = protocol.rawFrameFlow.toList()

        assertEquals(2, received.size)
        assertTrue(received[0].contentEquals(frames[0]))
        assertTrue(received[1].contentEquals(frames[1]))
    }

    @Test
    fun commandSupportIsDeclaredAndReportedByTheProtocol() {
        val protocol = TestProtocol(supportedCommandTypes = setOf(CommandType.LOCK))

        assertEquals(setOf(CommandType.LOCK), protocol.supportedCommandTypes)
        assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(CommandType.LOCK))
        assertEquals(CommandSupport.UNSUPPORTED, protocol.getCommandSupport(CommandType.UNLOCK))
    }

    @Test
    fun queryResponseMatcherCanRecognizeTheExpectedResponse() {
        val protocol = TestProtocol(responseMatcher = { spec, bytes ->
            spec.id == query.id && bytes.contentEquals(byteArrayOf(0x42))
        })

        assertTrue(protocol.matchesQueryResponse(query, byteArrayOf(0x42)))
        assertFalse(protocol.matchesQueryResponse(query, byteArrayOf(0x00)))
    }

    @Test
    fun preferredWriteTypeCanBeOverridden() {
        val protocol = TestProtocol(preferredWriteType = ProtocolWriteType.NO_RESPONSE)

        assertEquals(ProtocolWriteType.NO_RESPONSE, protocol.preferredWriteType)
    }

    @Test
    fun bmsDataCanBeReturnedWhenAvailable() {
        val bmsData = listOf(
            BMSData(
                bmsIndex = 1,
                voltage = 84.0,
                current = -1.0,
                remainingCapacity = 12000,
                factoryCapacity = 15000,
                cycles = 12,
                temperatures = listOf(25.0),
                cellVoltages = listOf(4.0, 4.1),
                isCharging = true
            )
        )
        val protocol = TestProtocol(bmsData = bmsData)

        assertEquals(bmsData, protocol.getBMSData())
    }

    @Test
    fun legacyValueConversionIsCheckedAndPreservesSupportedKinds() {
        val cases = listOf(
            Unit to CommandValue.None,
            42 to CommandValue.Integer(42),
            true to CommandValue.Toggle(true),
            "0012" to CommandValue.Text("0012"),
            byteArrayOf(1, 2) to CommandValue.Bytes(byteArrayOf(1, 2)),
            intArrayOf(10, 20, 30) to CommandValue.Integers(listOf(10, 20, 30)),
            listOf(10, 20, 30) to CommandValue.Integers(listOf(10, 20, 30))
        )
        for ((legacy, typed) in cases) assertEquals(typed, legacy.toCommandValue())
        val change = PasswordChangeRequest("0012", "0034")
        assertSame(change, change.toCommandValue())
        assertSame(change, change.toLegacyValue())
        assertThrows(IllegalArgumentException::class.java) { 1.5.toCommandValue() }
        assertThrows(IllegalArgumentException::class.java) {
            listOf(10, "credential", 20, 30).toCommandValue()
        }
    }

    @Test
    fun legacyCustomProtocolReceivesUnwrappedTypedQueryValues() {
        val received = mutableListOf<Any>()
        val protocol = TestProtocol(commandEncoder = { received += it; byteArrayOf(1) })
        val values = listOf(
            CommandValue.None, CommandValue.Integer(42), CommandValue.Toggle(true),
            CommandValue.Text("0012"), CommandValue.Bytes(byteArrayOf(1, 2)),
            CommandValue.Integers(listOf(10, 20, 30)), PasswordChangeRequest("0012", "0034")
        )
        values.forEach { protocol.createCommand(CommandType.CUSTOM, it) }
        assertEquals(listOf(Unit, 42, true, "0012"), received.take(4))
        assertArrayEquals(byteArrayOf(1, 2), received[4] as ByteArray)
        assertArrayEquals(intArrayOf(10, 20, 30), received[5] as IntArray)
        assertSame(values.last(), received.last())
        assertArrayEquals(byteArrayOf(1), protocol.createCommand(CommandType.BEEP))
    }

    @Test
    fun builtInFacadeAcceptsLegacyAndTypedValuesAndRejectsMalformedLists() {
        val protocol = KingsongProtocol()
        try {
            assertArrayEquals(protocol.createCommand(CommandType.SET_LIGHT_MODE, 2),
                protocol.createCommand(CommandType.SET_LIGHT_MODE, CommandValue.Integer(2)))
            assertArrayEquals(protocol.createCommand(CommandType.SET_ALARM_SPEED, intArrayOf(10, 20, 30)),
                protocol.createCommand(CommandType.SET_ALARM_SPEED, CommandValue.Integers(listOf(10, 20, 30))))
            assertTrue(protocol.createCommand(
                CommandType.SET_ALARM_SPEED, listOf(10, "ignored before", 20, 30)
            ).isEmpty())
        } finally {
            protocol.close()
        }
    }

    @Test
    fun unrepresentableLegacyInputIsLoggedWithoutExposingItsContents() {
        val warnings = mutableListOf<String>()
        val logger = object : Logger by NoOpLogger() {
            override fun warn(tag: String, message: String) { warnings += message }
        }
        val adapter = ProtocolSessionDriver(
            KingsongSyncProtocol(SystemEucClock, EucRandom { 0 }), logger = logger
        )
        try {
            assertTrue(adapter.createCommand(
                CommandType.CHANGE_PASSWORD, "0012" to "0034"
            ).isEmpty())
            assertEquals(1, warnings.size)
            assertTrue(warnings.single().contains("CHANGE_PASSWORD"))
            assertFalse(warnings.single().contains("0012"))
            assertFalse(warnings.single().contains("0034"))
        } finally {
            adapter.close()
        }
    }

    private val query = ProtocolQuerySpec(id = "serial", commandType = CommandType.REQUEST_SERIAL)

    private class TestProtocol(
        override val rawFrameFlow: Flow<ByteArray> = emptyFlow(),
        override val supportedCommandTypes: Set<CommandType> = emptySet(),
        private val responseMatcher: (ProtocolQuerySpec, ByteArray) -> Boolean = { _, _ -> false },
        override val preferredWriteType: ProtocolWriteType = ProtocolWriteType.AUTO,
        private val bmsData: List<BMSData>? = null,
        private val commandEncoder: (Any) -> ByteArray = { byteArrayOf(0x01) }
    ) : EUCProtocol {
        override val manufacturer: String = "Test"
        override val dataFlow: Flow<EUCData> = emptyFlow()

        override fun decode(data: ByteArray): EUCData? = null

        override fun getDataCharacteristicUUID(): UUID = UUID.fromString("00000000-0000-0000-0000-0000000000E1")

        override fun getServiceUUID(): UUID = UUID.fromString("00000000-0000-0000-0000-0000000000F0")

        override fun createCommand(commandType: CommandType, value: Any): ByteArray = commandEncoder(value)

        override fun isDeviceReady(data: EUCData): Boolean = true

        override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean =
            responseMatcher(query, data)

        override fun getBMSData(): List<BMSData>? = bmsData

        override fun close() = Unit
    }
}

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

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

    private val query = ProtocolQuerySpec(id = "serial", commandType = CommandType.REQUEST_SERIAL)

    private class TestProtocol(
        override val rawFrameFlow: Flow<ByteArray> = emptyFlow(),
        override val supportedCommandTypes: Set<CommandType> = emptySet(),
        private val responseMatcher: (ProtocolQuerySpec, ByteArray) -> Boolean = { _, _ -> false },
        override val preferredWriteType: ProtocolWriteType = ProtocolWriteType.AUTO,
        private val bmsData: List<BMSData>? = null
    ) : EUCProtocol {
        override val manufacturer: String = "Test"
        override val dataFlow: Flow<EUCData> = emptyFlow()

        override fun decode(data: ByteArray): EUCData? = null

        override fun getDataCharacteristicUUID(): UUID = UUID.fromString("00000000-0000-0000-0000-0000000000E1")

        override fun getServiceUUID(): UUID = UUID.fromString("00000000-0000-0000-0000-0000000000F0")

        override fun createCommand(commandType: CommandType, value: Any): ByteArray = byteArrayOf(0x01)

        override fun isDeviceReady(data: EUCData): Boolean = true

        override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean =
            responseMatcher(query, data)

        override fun getBMSData(): List<BMSData>? = bmsData

        override fun close() = Unit
    }
}

package io.github.tritbool.euc.ble.protocols

import app.cash.turbine.test
import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucDateTime
import io.github.tritbool.euc.ble.models.AuthenticationState
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InMotionSyncProtocolTest {
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 100_000L
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }

    private val identity = ByteUtils.hexToBytes(
        "aaaa1662210286010001413134323139313136303031383341340000000000" +
            "020c010121320008010100090222260004071900080223100004010a00" +
            "0201240e00000001000101250e000000010001012f06000601010000" +
            "0045bd57a9210100220000230000ea"
    )
    private val realtime = ByteUtils.hexToBytes(
        "aaaa165f2102870100c91d23020000000018000000fcf28405000000" +
            "00de189c177017ac0dc210ccd3c800c0022803b90006007604000006" +
            "00000015000000b700000078d90100b1338e00506f0800b8bc020065ba1e00" +
            "490030000000000000000000a5a5"
    )

    @Test
    fun `compatibility facade ignores empty notifications`() = runTest {
        val protocol = InMotionProtocol()
        try {
            protocol.rawFrameFlow.test {
                assertNull(protocol.decode(byteArrayOf()))
                expectNoEvents()
            }
        } finally {
            protocol.close()
        }
    }

    @Test
    fun `compatibility facade publishes current security state synchronously`() {
        val protocol = InMotionProtocol()
        try {
            protocol.decode(identity)
            protocol.decode(ByteUtils.hexToBytes("aaaa1213028002ef8764a42b432de0f2e999991445637bde"))
            assertEquals(AuthenticationState.REQUIRED, protocol.securityStateFlow.value.authentication)
            protocol.decode(ByteUtils.hexToBytes("aaaa12040280820117"))
            assertEquals(AuthenticationState.AUTHENTICATED, protocol.securityStateFlow.value.authentication)
            protocol.resetSession()
            assertEquals(AuthenticationState.UNKNOWN, protocol.securityStateFlow.value.authentication)
        } finally {
            protocol.close()
        }
    }

    @Test
    fun `query matching and fragmented decoding share framing without losing telemetry`() {
        val engine = InMotionSyncProtocol(clock)
        engine.onNotification(identity)
        engine.drainOutputs()
        val query = engine.getPollingPlan().periodicQueries.single { it.id == "inmotion.extended-realtime" }
        val first = realtime.copyOfRange(0, 12)
        val second = realtime.copyOfRange(12, realtime.size)
        assertTrue(!engine.matchesQueryResponse(query, first))
        engine.onNotification(first)
        assertTrue(engine.matchesQueryResponse(query, second))
        engine.onNotification(second)
        val telemetry = engine.drainOutputs().filterIsInstance<ProtocolOutput.Telemetry>().single().data
        assertEquals(100_000L, telemetry.timestamp)
        assertEquals("InMotion V9", telemetry.model)
        assertEquals(76.25, telemetry.voltage, 0.01)
    }

    @Test
    fun `authentication outputs preserve write and state ordering and reset clears them`() {
        val engine = InMotionSyncProtocol(clock)
        engine.onNotification(identity)
        val request = engine.drainOutputs().filterIsInstance<ProtocolOutput.Write>().single()
        assertArrayEquals(ByteUtils.hexToBytes("aaaa13040200000217"), request.payload)
        engine.onNotification(ByteUtils.hexToBytes("aaaa1213028002ef8764a42b432de0f2e999991445637bde"))
        val outputs = engine.drainOutputs()
        assertEquals(AuthenticationState.REQUIRED, (outputs[0] as ProtocolOutput.SecurityStateChanged).state.authentication)
        assertArrayEquals(
            ByteUtils.hexToBytes("aaaa131402000082ef8764a42b432de0f2e999991445637bd8"),
            (outputs[1] as ProtocolOutput.Write).payload
        )
        engine.onNotification(ByteUtils.hexToBytes("aaaa12040280820117"))
        assertEquals(
            AuthenticationState.AUTHENTICATED,
            (engine.pollOutput() as ProtocolOutput.SecurityStateChanged).state.authentication
        )
        engine.resetSession()
        assertNull(engine.pollOutput())
        assertEquals(AuthenticationState.UNKNOWN, engine.getSecurityState().authentication)
        assertEquals("InMotion", engine.lastDecodedModel())
    }
}

/*
 *
 *  * Copyright (C) ${YEAR} Gauthier Le Bartz Lyan (Tritbool)
 *  * euc_ble_library — version 0.1.3 — licence GPL-3.0
 *  * https://github.com/Tritbool/euc_ble_library
 *
 */

package io.github.tritbool.euc.ble.protocols

import app.cash.turbine.test
import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucDateTime
import io.github.tritbool.euc.ble.models.AuthenticationState
import io.github.tritbool.euc.ble.models.WheelLockState
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    // Complete detailed-data replies after LOCK, UNLOCK, LOCK, UNLOCK in v9_lock.log.
    private val lockReplies = listOf(
        "aaaa16592102844a1e0f000000000000000000000000000000000014e9b60114e90000000000000000ca18871770174c04c509401f401fa00fa00f983a00000000c3c100c7b0c9c1ceb00464000000000003000000000000000000000067",
        "aaaa16592102840e1e0e000000000000000000000000000000000014e9b60114e90000000000000000c918861770174c04c509401f401f08070807983a00000000c3c100c7b0c9c1ceb0096400000000002000000000000000000000000e",
        "aaaa1659210284491e0e000000000000000000000000000000000014e9b60114e90000000000000000c918861770174c04c509401f401fa00fa00f983a00000000c3c100c7b0c9c1ceb02b64000000000003000000000000000000000048",
        "aaaa16592102840f1e09000000000000000000000000000000000014e9b60114e90000000000000000c918861770174c04c509401f401f08070807983a00000000c3c100c7b0c9c1ceb04864000000000020000000000000000000000049"
    ).map(ByteUtils::hexToBytes)

    @Test
    fun `lock state query is available only for identified V9 and resets with the session`() {
        val engine = InMotionSyncProtocol(clock)
        assertEquals(CommandSupport.UNSUPPORTED, engine.getCommandSupport(CommandType.REQUEST_LOCK_STATE))
        assertTrue(engine.createCommand(CommandType.REQUEST_LOCK_STATE).isEmpty())
        engine.onNotification(identity)
        assertTrue(CommandType.REQUEST_LOCK_STATE in engine.supportedCommandTypes)
        assertArrayEquals(
            ByteUtils.hexToBytes("aaaa160302210432"),
            engine.createCommand(CommandType.REQUEST_LOCK_STATE)
        )
        engine.resetSession()
        assertEquals(CommandSupport.UNSUPPORTED, engine.getCommandSupport(CommandType.REQUEST_LOCK_STATE))
        assertEquals(WheelLockState.UNKNOWN, engine.getSecurityState().lock)
        for (carType in listOf("aaaa11088201020d0101010094", "aaaa110882010206010101009f")) {
            engine.onNotification(ByteUtils.hexToBytes(carType))
            assertEquals(CommandSupport.UNSUPPORTED, engine.getCommandSupport(CommandType.REQUEST_LOCK_STATE))
            assertTrue(engine.createCommand(CommandType.REQUEST_LOCK_STATE).isEmpty())
            engine.onNotification(lockReplies.first())
            assertEquals(WheelLockState.UNKNOWN, engine.getSecurityState().lock)
        }
    }

    @Test
    fun `captured V9 detailed replies confirm both lock cycles without inferring from acknowledgments`() {
        val engine = InMotionSyncProtocol(clock)
        engine.onNotification(identity)
        engine.onNotification(ByteUtils.hexToBytes("aaaa1213028002ef8764a42b432de0f2e999991445637bde"))
        engine.onNotification(ByteUtils.hexToBytes("aaaa12040280820117"))
        engine.drainOutputs()
        var previous = WheelLockState.UNKNOWN
        lockReplies.forEachIndexed { index, reply ->
            val expected = if (index % 2 == 0) WheelLockState.LOCKED else WheelLockState.UNLOCKED
            val command = if (index % 2 == 0) CommandType.LOCK else CommandType.UNLOCK
            engine.onCommandSubmitted(engine.createCommand(command))
            engine.onNotification(ByteUtils.hexToBytes("aaaa16052102e03100e1"))
            assertEquals(previous, engine.getSecurityState().lock)
            assertTrue(engine.drainOutputs().isEmpty())
            engine.onNotification(reply)
            assertEquals(expected, engine.getSecurityState().lock)
            assertEquals(AuthenticationState.AUTHENTICATED, engine.getSecurityState().authentication)
            val outputs = engine.drainOutputs()
            assertEquals(expected, (outputs[0] as ProtocolOutput.SecurityStateChanged).state.lock)
            assertEquals(
                WheelSecurityEvent.LockStatus(expected),
                (outputs[1] as ProtocolOutput.SecurityEvent).event
            )
            previous = expected
        }
        engine.resetSession()
        assertEquals(WheelLockState.UNKNOWN, engine.getSecurityState().lock)
    }

    @Test
    fun `lock decoding rejects unknown models values layouts query echoes and corrupt frames`() {
        val engine = InMotionSyncProtocol(clock)
        engine.onNotification(lockReplies.first())
        assertEquals(WheelLockState.UNKNOWN, engine.getSecurityState().lock)
        engine.onNotification(identity)
        engine.drainOutputs()
        val body = lockReplies.first().copyOfRange(7, 93)
        val invalid = listOf(
            lockReplies.first().copyOf().apply { this[lastIndex] = 0 },
            detailedFrame(body.copyOf(75)),
            detailedFrame(body + 0),
            detailedFrame(body.copyOf().apply { this[74] = 0x23 }),
            detailedFrame(body, response = false)
        )
        for (frame in invalid) {
            engine.onNotification(frame)
            assertEquals(WheelLockState.UNKNOWN, engine.getSecurityState().lock)
            assertTrue(engine.drainOutputs().isEmpty())
        }
        val reply = lockReplies.first()
        engine.onNotification(reply.copyOfRange(0, 40))
        assertEquals(WheelLockState.UNKNOWN, engine.getSecurityState().lock)
        engine.onNotification(reply.copyOfRange(40, reply.size))
        assertEquals(WheelLockState.LOCKED, engine.getSecurityState().lock)
    }

    @Test
    fun `facade exposes repeated confirmed lock events and current state`() = runTest {
        val protocol = InMotionProtocol()
        try {
            protocol.decode(identity)
            protocol.securityEventFlow.test {
                repeat(2) {
                    protocol.decode(lockReplies.first())
                    assertEquals(WheelSecurityEvent.LockStatus(WheelLockState.LOCKED), awaitItem())
                    assertEquals(WheelLockState.LOCKED, protocol.securityStateFlow.value.lock)
                }
                protocol.decode(lockReplies[1])
                assertEquals(WheelSecurityEvent.LockStatus(WheelLockState.UNLOCKED), awaitItem())
                assertEquals(WheelLockState.UNLOCKED, protocol.securityStateFlow.value.lock)
                protocol.resetSession()
                assertEquals(WheelLockState.UNKNOWN, protocol.securityStateFlow.value.lock)
                expectNoEvents()
            }
        } finally {
            protocol.close()
        }
    }

    private fun detailedFrame(body: ByteArray, response: Boolean = true): ByteArray {
        val content = byteArrayOf(0x16, (body.size + 3).toByte(), 0x21, 0x02,
            if (response) 0x84.toByte() else 0x04) + body
        val checksum = content.fold(0) { sum, byte -> sum xor (byte.toInt() and 0xFF) }.toByte()
        val escaped = (content + checksum).flatMap {
            if (it == 0xAA.toByte() || it == 0xA5.toByte()) listOf(0xA5.toByte(), it) else listOf(it)
        }.toByteArray()
        return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + escaped
    }

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

    @Test
    fun `V9 lock commands require a confirmed handshake and are rechecked before sending`() {
        val engine = InMotionSyncProtocol(clock)
        val lock = ByteUtils.hexToBytes("aaaa1605022160310160")
        val unlock = ByteUtils.hexToBytes("aaaa1605022160310061")
        engine.setDialect(2)
        assertTrue(engine.createCommand(CommandType.LOCK).isEmpty())
        assertFalse(engine.canSendCommand(ByteUtils.hexToBytes("aaaa140360310147")))
        engine.onNotification(identity)
        engine.drainOutputs()

        assertTrue(engine.createCommand(CommandType.LOCK).isEmpty())
        assertTrue(engine.createCommand(CommandType.UNLOCK).isEmpty())
        assertFalse(engine.canSendCommand(lock))
        assertFalse(engine.canSendCommand(ByteUtils.hexToBytes("aaaa140360310147")))
        engine.onNotification(ByteUtils.hexToBytes("aaaa12040280820117"))
        assertTrue(engine.createCommand(CommandType.LOCK).isEmpty())

        engine.onNotification(ByteUtils.hexToBytes("aaaa1213028002ef8764a42b432de0f2e999991445637bde"))
        assertTrue(engine.createCommand(CommandType.UNLOCK).isEmpty())
        engine.onNotification(ByteUtils.hexToBytes("aaaa12040280820117"))
        assertArrayEquals(lock, engine.createCommand(CommandType.LOCK))
        assertArrayEquals(unlock, engine.createCommand(CommandType.UNLOCK))
        assertTrue(engine.canSendCommand(lock))

        // A refresh does not revoke a previously confirmed session until the wheel rejects it.
        engine.onNotification(ByteUtils.hexToBytes("aaaa1213028002ef8764a42b432de0f2e999991445637bde"))
        assertTrue(engine.canSendCommand(unlock))
        engine.onNotification(ByteUtils.hexToBytes("aaaa12040280820016"))
        assertEquals(AuthenticationState.REQUIRED, engine.getSecurityState().authentication)
        assertFalse(engine.canSendCommand(unlock))
        assertTrue(engine.createCommand(CommandType.UNLOCK).isEmpty())

        engine.resetSession()
        assertFalse(engine.canSendCommand(lock))
        assertEquals(AuthenticationState.UNKNOWN, engine.getSecurityState().authentication)
    }
}

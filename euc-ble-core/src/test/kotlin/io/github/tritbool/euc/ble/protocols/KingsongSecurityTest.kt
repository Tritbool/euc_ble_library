package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.models.AuthenticationState
import io.github.tritbool.euc.ble.models.WheelLockState
import io.github.tritbool.euc.ble.models.WheelSecurityState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KingsongSecurityTest {
    @Test
    fun pinPreservesLeadingZerosAndUsesExactStandardFrame() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        assertArrayEquals(
            ByteUtils.hexToBytes("AA55303031320000000000000000000044145A5A"),
            protocol.createCommand(CommandType.AUTHENTICATE, "0012")
        )
        for (invalid in listOf("", "123", "12345", " 123", "12 3", "12a3", "１２３４", "١٢٣٤", 1234)) {
            assertTrue(protocol.createCommand(CommandType.AUTHENTICATE, invalid).isEmpty())
        }
        assertEquals(WheelSecurityState(), protocol.getSecurityState())
        assertEquals(ProtocolWriteType.NO_RESPONSE, protocol.preferredWriteType)
    }

    @Test
    fun explicitLockUnlockAndQueryHaveExactPayloadsAndDoNotConfirmState() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        assertArrayEquals(
            ByteUtils.hexToBytes("AA5501000000000000000000000000005D145A5A"),
            protocol.createCommand(CommandType.LOCK, Unit)
        )
        assertArrayEquals(
            ByteUtils.hexToBytes("AA5500000000000000000000000000005D145A5A"),
            protocol.createCommand(CommandType.UNLOCK, Unit)
        )
        assertArrayEquals(
            ByteUtils.hexToBytes("AA5500000000000000000000000000005E145A5A"),
            protocol.createCommand(CommandType.REQUEST_LOCK_STATE, Unit)
        )
        for (command in listOf(CommandType.AUTHENTICATE, CommandType.LOCK, CommandType.UNLOCK, CommandType.REQUEST_LOCK_STATE)) {
            assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(command))
        }
        assertEquals(WheelSecurityState(), protocol.getSecurityState())
        val plan = protocol.getPollingPlan()
        assertTrue((plan.startupQueries + plan.periodicQueries).none {
            it.commandType in setOf(CommandType.LOCK, CommandType.UNLOCK, CommandType.AUTHENTICATE)
        })
        val query = ProtocolQuerySpec("lock", CommandType.REQUEST_LOCK_STATE)
        assertTrue(protocol.matchesQueryResponse(query, notification(0x5F, 1)))
        assertFalse(protocol.matchesQueryResponse(query, notification(0x46, 0)))
    }

    @Test
    fun fragmentedAndConcatenatedNotificationsConfirmIndependentStateWithoutAutomaticWrites() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        val writes = mutableListOf<ByteArray>()
        backgroundScope.launch { protocol.writeFlow.collect { writes.add(it) } }
        runCurrent()
        val required = notification(0x46, 1)
        protocol.decode(byteArrayOf(0x12) + required.copyOfRange(0, 7))
        runCurrent()
        assertEquals(AuthenticationState.UNKNOWN, protocol.getSecurityState().authentication)
        protocol.decode(required.copyOfRange(7, 20) + notification(0x5F, 1))
        runCurrent()
        assertEquals(WheelSecurityState(AuthenticationState.REQUIRED, WheelLockState.LOCKED), protocol.getSecurityState())
        assertTrue(protocol.createCommand(CommandType.LOCK, Unit).isEmpty())
        assertTrue(protocol.createCommand(CommandType.UNLOCK, Unit).isEmpty())
        assertFalse(protocol.canSendCommand(notification(0x5D, 0)))
        assertTrue(protocol.createCommand(CommandType.AUTHENTICATE, "0000").isNotEmpty())
        // A repeated required notification after a PIN means only that authentication is still required.
        protocol.decode(required)
        runCurrent()
        assertEquals(AuthenticationState.REQUIRED, protocol.getSecurityState().authentication)
        protocol.decode(notification(0x46, 0) + notification(0x5F, 0))
        runCurrent()
        assertEquals(WheelSecurityState(AuthenticationState.AUTHENTICATED, WheelLockState.UNLOCKED), protocol.getSecurityState())
        assertTrue(protocol.canSendCommand(notification(0x5D, 1)))
        assertTrue(protocol.createCommand(CommandType.LOCK, Unit).isNotEmpty())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun unknownValuesTruncationAndMalformedSecurityFramesDoNotChangeState() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        protocol.decode(notification(0x46, 2) + notification(0x5F, 255))
        protocol.decode(notification(0x46, 0).also { it[19] = 0 })
        protocol.decode(notification(0x5F, 1).also { it[0] = 0x55; it[1] = 0xAA.toByte() })
        runCurrent()
        assertEquals(WheelSecurityState(), protocol.getSecurityState())
        protocol.decode(notification(0x46, 0).copyOfRange(0, 10))
        runCurrent()
        assertEquals(WheelSecurityState(), protocol.getSecurityState())
    }

    @Test
    fun resetDiscardsQueuedAndPartialFramesAndProtocolRemainsReusable() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        protocol.decode(notification(0x46, 0) + notification(0x5F, 1))
        protocol.resetSession()
        runCurrent()
        assertEquals(WheelSecurityState(), protocol.getSecurityState())
        protocol.decode(notification(0x46, 0).copyOfRange(0, 10))
        runCurrent()
        protocol.resetSession()
        protocol.decode(notification(0x46, 0).copyOfRange(10, 20))
        runCurrent()
        assertEquals(WheelSecurityState(), protocol.getSecurityState())
        protocol.decode(notification(0x46, 1))
        runCurrent()
        assertEquals(AuthenticationState.REQUIRED, protocol.getSecurityState().authentication)
        protocol.resetSession()
        assertEquals(WheelSecurityState(), protocol.securityStateFlow.value)
        protocol.decode(notification(0x46, 0) + notification(0x5F, 0))
        runCurrent()
        assertEquals(WheelSecurityState(AuthenticationState.AUTHENTICATED, WheelLockState.UNLOCKED), protocol.getSecurityState())
    }

    private fun notification(opcode: Int, status: Int) = ByteArray(20).also {
        it[0] = 0xAA.toByte()
        it[1] = 0x55
        it[2] = status.toByte()
        it[16] = opcode.toByte()
        it[17] = 0x14
        it[18] = 0x5A
        it[19] = 0x5A
    }
}

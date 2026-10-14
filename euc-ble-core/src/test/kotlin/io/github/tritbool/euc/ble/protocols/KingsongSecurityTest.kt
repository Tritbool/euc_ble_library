package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.models.AuthenticationState
import io.github.tritbool.euc.ble.models.WheelLockState
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
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
        assertTrue(protocol.createCommand(CommandType.UNLOCK, Unit).isEmpty())
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
    fun unlockEncodesCapturedChallengeAndWaitsForWheelConfirmation() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        // Lock status/query reply from the S18 kslockbtsnoop capture.
        protocol.decode(ByteUtils.hexToBytes("AA5501003034343030300000000000005F145A5A"))
        runCurrent()
        val unlock = protocol.createCommand(CommandType.UNLOCK, Unit)
        assertUnlockToken(unlock, intArrayOf(0, 4, 0))
        assertEquals(WheelLockState.LOCKED, protocol.getSecurityState().lock)

        // Bare acknowledgements carry no challenge and must not overwrite it.
        protocol.decode(notification(0x5F, 1))
        runCurrent()
        assertUnlockToken(protocol.createCommand(CommandType.UNLOCK, Unit), intArrayOf(0, 4, 0))
        protocol.decode(notification(0x5F, 0))
        runCurrent()
        assertEquals(WheelLockState.UNLOCKED, protocol.getSecurityState().lock)
    }

    @Test
    fun latestChallengeIsUsedAndRelockingAndResetDiscardIt() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        protocol.decode(ByteUtils.hexToBytes("AA5501003034343030300000000000005F145A5A"))
        protocol.decode(ByteUtils.hexToBytes("AA5501003830303030300000000000005F145A5A"))
        runCurrent()
        assertUnlockToken(protocol.createCommand(CommandType.UNLOCK, Unit), intArrayOf(8, 0, 0))
        protocol.createCommand(CommandType.LOCK, Unit)
        assertTrue(protocol.createCommand(CommandType.UNLOCK, Unit).isEmpty())
        protocol.decode(ByteUtils.hexToBytes("AA5501003030303030300000000000005F145A5A"))
        runCurrent()
        assertUnlockToken(protocol.createCommand(CommandType.UNLOCK, Unit), intArrayOf(0, 0, 0))
        protocol.resetSession()
        assertTrue(protocol.createCommand(CommandType.UNLOCK, Unit).isEmpty())
    }

    @Test
    fun invalidChallengesCannotEnableUnlockAndAuthenticationStillGatesIt() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        val valid = ByteUtils.hexToBytes("AA5501003034343030300000000000005F145A5A")
        for (offset in 4..9) {
            protocol.decode(valid.clone().also { it[offset] = 0x2F })
            runCurrent()
            assertTrue(protocol.createCommand(CommandType.UNLOCK, Unit).isEmpty())
        }
        protocol.decode(valid.clone().also { it[19] = 0 })
        protocol.decode(valid.clone().also { it[2] = 2 })
        runCurrent()
        assertTrue(protocol.createCommand(CommandType.UNLOCK, Unit).isEmpty())
        protocol.decode(valid + notification(0x46, 1))
        runCurrent()
        assertTrue(protocol.createCommand(CommandType.UNLOCK, Unit).isEmpty())
        protocol.decode(notification(0x46, 0))
        runCurrent()
        assertUnlockToken(protocol.createCommand(CommandType.UNLOCK, Unit), intArrayOf(0, 4, 0))
    }

    @Test
    fun fragmentedAndConcatenatedNotificationsConfirmIndependentStateWithoutAutomaticWrites() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        val writes = mutableListOf<ByteArray>()
        val securityEvents = mutableListOf<WheelSecurityEvent>()
        backgroundScope.launch { protocol.writeFlow.collect { writes.add(it) } }
        backgroundScope.launch { protocol.securityEventFlow.collect { securityEvents.add(it) } }
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
        assertEquals(listOf(
            WheelSecurityEvent.AuthenticationStatus(AuthenticationState.REQUIRED),
            WheelSecurityEvent.LockStatus(WheelLockState.LOCKED),
            WheelSecurityEvent.AuthenticationStatus(AuthenticationState.REQUIRED),
            WheelSecurityEvent.AuthenticationStatus(AuthenticationState.AUTHENTICATED),
            WheelSecurityEvent.LockStatus(WheelLockState.UNLOCKED)
        ), securityEvents)
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

    private fun assertUnlockToken(frame: ByteArray, challenge: IntArray) {
        assertEquals(20, frame.size)
        val envelope = frame.clone().also { it.fill(0, 10, 16) }
        assertArrayEquals(
            ByteUtils.hexToBytes("AA5500000000000000000000000000005D145A5A"),
            envelope
        )
        assertTrue((10..15).all { frame[it].toInt() in 0x30..0x39 })
        val digits = (10..15).map { frame[it].toInt() - 0x30 }
        val a = digits[0]
        val b = digits[2]
        val c = digits[4]
        val sum = digits[1] + digits[3] + digits[5]
        assertEquals((sum + challenge[0] + challenge[1] + challenge[2]) % 10, a)
        assertEquals((sum + a + challenge[1] + challenge[2]) % 10, b)
        assertEquals((sum + a + b + challenge[2]) % 10, c)
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

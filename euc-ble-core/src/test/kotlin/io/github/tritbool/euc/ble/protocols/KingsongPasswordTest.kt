package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.models.PasswordOperation
import io.github.tritbool.euc.ble.models.PasswordOperationStatus
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Minimal sanitized capture-shaped fixtures using disposable example passwords. */
@OptIn(ExperimentalCoroutinesApi::class)
class KingsongPasswordTest {
    @Test
    fun exactSetChangeAndRemoveFramesPreserveZeros() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        assertArrayEquals(
            bytes("AA55303031320000000000000000000041145A5A"),
            protocol.createCommand(CommandType.SET_PASSWORD, "0012")
        )
        assertArrayEquals(
            bytes("AA55363738393030313200000000000041145A5A"),
            protocol.createCommand(CommandType.CHANGE_PASSWORD, PasswordChangeRequest("0012", "6789"))
        )
        assertArrayEquals(
            bytes("AA55303030303030303000000000000041145A5A"),
            protocol.createCommand(CommandType.CHANGE_PASSWORD, PasswordChangeRequest("0000", "0000"))
        )
        assertArrayEquals(
            bytes("AA55303031320000000000000000000042145A5A"),
            protocol.createCommand(CommandType.REMOVE_PASSWORD, "0012")
        )
        assertEquals("PasswordChangeRequest(<redacted>)", PasswordChangeRequest("0012", "6789").toString())
        for (command in commands) {
            assertEquals(CommandSupport.SUPPORTED, protocol.getCommandSupport(command))
        }
        val plan = protocol.getPollingPlan()
        assertTrue((plan.startupQueries + plan.periodicQueries).none { it.commandType in commands })
    }

    @Test
    fun invalidGenericInputsNeverProducePasswordCommands() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        for (invalid in listOf("", "123", "12345", " 123", "12 3", "12a3", "１２３４", "١٢٣٤")) {
            assertTrue(protocol.createCommand(CommandType.SET_PASSWORD, invalid).isEmpty())
            assertTrue(protocol.createCommand(CommandType.REMOVE_PASSWORD, invalid).isEmpty())
            assertTrue(protocol.createCommand(
                CommandType.CHANGE_PASSWORD, PasswordChangeRequest(invalid, "0012")
            ).isEmpty())
            assertTrue(protocol.createCommand(
                CommandType.CHANGE_PASSWORD, PasswordChangeRequest("0012", invalid)
            ).isEmpty())
        }
        for (invalid in listOf(1234, Unit, byteArrayOf(1, 2, 3, 4), "0012" to "6789")) {
            for (command in commands) assertTrue(protocol.createCommand(command, invalid).isEmpty())
        }
        assertTrue(protocol.createCommand(CommandType.CHANGE_PASSWORD, "0012").isEmpty())
        assertTrue(protocol.createCommand(CommandType.SET_PASSWORD, PasswordChangeRequest("0012", "6789")).isEmpty())
    }

    @Test
    fun onlyMatchingSubmittedOperationCanBeConfirmedAndStateIsPreserved() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        val events = mutableListOf<WheelSecurityEvent>()
        val writes = mutableListOf<ByteArray>()
        backgroundScope.launch { protocol.securityEventFlow.collect { events.add(it) } }
        backgroundScope.launch { protocol.writeFlow.collect { writes.add(it) } }
        runCurrent()
        protocol.decode(bytes("AA55010000000000000000000000000046145A5A"))
        protocol.decode(bytes("AA5501000000000000000000000000005F145A5A"))
        runCurrent()
        val state = protocol.getSecurityState()
        events.clear()
        val reply = bytes("AA55303031320100000000000000000043145A5A")
        protocol.decode(reply)
        protocol.createCommand(CommandType.SET_PASSWORD, "0012")
        runCurrent()
        assertTrue(events.isEmpty())
        for ((command, value, operation) in listOf(
            Triple(CommandType.SET_PASSWORD, "0012", PasswordOperation.SET),
            Triple(CommandType.CHANGE_PASSWORD, PasswordChangeRequest("0000", "0012"), PasswordOperation.CHANGE)
        )) {
            val payload = protocol.createCommand(command, value)
            protocol.onCommandSubmitted(payload)
            payload.fill(0) // Correlation owns a defensive copy, not a queue-buffer alias.
            protocol.decode(reply.copyOfRange(0, 8))
            runCurrent()
            assertTrue(events.isEmpty())
            protocol.decode(reply.copyOfRange(8, 20))
            runCurrent()
            assertEquals(listOf(result(operation, PasswordOperationStatus.CONFIRMED)), events)
            assertEquals(state, protocol.getSecurityState())
            events.clear()
            protocol.decode(reply)
            runCurrent()
            assertTrue(events.isEmpty())
        }
        assertTrue(writes.isEmpty())
        advanceTimeBy(KingsongProtocol.PASSWORD_OPERATION_TIMEOUT_MS)
        runCurrent()
        assertTrue(events.isEmpty())
    }

    @Test
    fun markerTrailerTargetHeaderAndOpcodeMustMatch() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        val events = mutableListOf<WheelSecurityEvent>()
        backgroundScope.launch { protocol.securityEventFlow.collect { events.add(it) } }
        runCurrent()
        val payload = protocol.createCommand(CommandType.SET_PASSWORD, "0012")
        protocol.onCommandSubmitted(payload)
        val reply = bytes("AA55303031320100000000000000000043145A5A")
        val invalid = listOf(
            reply.clone().also { it[6] = 0 },
            reply.clone().also { it[6] = 2 },
            reply.clone().also { it[2] = 0x31 },
            reply.clone().also { it[16] = 0x44 },
            reply.clone().also { it[0] = 0x55; it[1] = 0xAA.toByte() }
        ) + (17..19).map { offset -> reply.clone().also { it[offset] = 0 } }
        for (frame in invalid) {
            protocol.decode(frame)
            runCurrent()
            assertTrue(events.isEmpty())
            assertFalse(protocol.canSendCommand(payload))
        }
        protocol.decode(reply)
        runCurrent()
        assertEquals(listOf(result(PasswordOperation.SET, PasswordOperationStatus.CONFIRMED)), events)
    }

    @Test
    fun removalRequiresAllFfAndDoesNotRetainCurrentPasswordAsTarget() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        val events = mutableListOf<WheelSecurityEvent>()
        backgroundScope.launch { protocol.securityEventFlow.collect { events.add(it) } }
        runCurrent()
        val payload = protocol.createCommand(CommandType.REMOVE_PASSWORD, "0012")
        protocol.onCommandSubmitted(payload)
        payload.fill(0)
        protocol.decode(bytes("AA55303031320100000000000000000043145A5A"))
        protocol.decode(bytes("AA55303030300100000000000000000043145A5A"))
        protocol.decode(bytes("AA55FFFFFF000100000000000000000043145A5A"))
        runCurrent()
        assertTrue(events.isEmpty())
        protocol.decode(bytes("AA55FFFFFFFF0100000000000000000043145A5A"))
        runCurrent()
        assertEquals(listOf(result(PasswordOperation.REMOVE, PasswordOperationStatus.CONFIRMED)), events)
    }

    @Test
    fun timeoutStartsOnlyAfterSubmissionAndOverlapCannotReplaceCorrelation() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        val events = mutableListOf<WheelSecurityEvent>()
        backgroundScope.launch { protocol.securityEventFlow.collect { events.add(it) } }
        runCurrent()
        val first = protocol.createCommand(CommandType.SET_PASSWORD, "0012")
        val overlap = protocol.createCommand(CommandType.CHANGE_PASSWORD, PasswordChangeRequest("0012", "6789"))
        advanceTimeBy(KingsongProtocol.PASSWORD_OPERATION_TIMEOUT_MS * 2)
        runCurrent()
        assertTrue(events.isEmpty())
        assertTrue(protocol.canSendCommand(first))
        protocol.onCommandSubmitted(first)
        runCurrent()
        for (command in commands) {
            val value = if (command == CommandType.CHANGE_PASSWORD) PasswordChangeRequest("0012", "6789") else "6789"
            assertFalse(protocol.canSendCommand(protocol.createCommand(command, value)))
        }
        assertTrue(protocol.canSendCommand(protocol.createCommand(CommandType.BEEP, Unit)))
        protocol.onCommandSubmitted(overlap)
        protocol.decode(bytes("AA55363738390100000000000000000043145A5A"))
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(KingsongProtocol.PASSWORD_OPERATION_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(result(PasswordOperation.SET, PasswordOperationStatus.TIMEOUT_UNCONFIRMED)), events)
        assertTrue(protocol.canSendCommand(overlap))
        protocol.decode(bytes("AA55303031320100000000000000000043145A5A"))
        runCurrent()
        assertEquals(1, events.size)
    }

    @Test
    fun missingWrongOldRepliesAreUnconfirmedNotAuthenticationFailures() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        val events = mutableListOf<WheelSecurityEvent>()
        backgroundScope.launch { protocol.securityEventFlow.collect { events.add(it) } }
        runCurrent()
        for ((command, value, operation) in listOf(
            Triple(CommandType.CHANGE_PASSWORD, PasswordChangeRequest("4555", "6789"), PasswordOperation.CHANGE),
            Triple(CommandType.REMOVE_PASSWORD, "4555", PasswordOperation.REMOVE)
        )) {
            val payload = protocol.createCommand(command, value)
            assertFalse(protocol.canRetryCommand(payload))
            protocol.onCommandSubmitted(payload)
            runCurrent()
            advanceTimeBy(KingsongProtocol.PASSWORD_OPERATION_TIMEOUT_MS)
            runCurrent()
            assertEquals(result(operation, PasswordOperationStatus.TIMEOUT_UNCONFIRMED), events.last())
        }
        assertEquals(2, events.size)
        assertTrue(protocol.canRetryCommand(protocol.createCommand(CommandType.BEEP, Unit)))
    }

    @Test
    fun resetAndCloseCancelTimeoutDiscardPartialAndLateReplies() = runTest {
        val protocol = KingsongProtocol(backgroundScope)
        val events = mutableListOf<WheelSecurityEvent>()
        backgroundScope.launch { protocol.securityEventFlow.collect { events.add(it) } }
        runCurrent()
        val payload = protocol.createCommand(CommandType.SET_PASSWORD, "0012")
        val reply = bytes("AA55303031320100000000000000000043145A5A")
        protocol.onCommandSubmitted(payload)
        protocol.decode(reply.copyOfRange(0, 8))
        runCurrent()
        protocol.resetSession()
        protocol.decode(reply.copyOfRange(8, 20) + reply)
        advanceTimeBy(KingsongProtocol.PASSWORD_OPERATION_TIMEOUT_MS)
        runCurrent()
        assertTrue(events.isEmpty())
        assertTrue(protocol.canSendCommand(payload))
        protocol.onCommandSubmitted(payload)
        runCurrent()
        protocol.decode(reply)
        runCurrent()
        assertEquals(listOf(result(PasswordOperation.SET, PasswordOperationStatus.CONFIRMED)), events)
        events.clear()
        protocol.onCommandSubmitted(payload)
        runCurrent()
        protocol.close()
        advanceTimeBy(KingsongProtocol.PASSWORD_OPERATION_TIMEOUT_MS)
        assertTrue(events.isEmpty())
    }

    private fun bytes(hex: String) = ByteUtils.hexToBytes(hex)
    private fun result(operation: PasswordOperation, status: PasswordOperationStatus) =
        WheelSecurityEvent.PasswordOperationResult(operation, status)

    private val commands = setOf(CommandType.SET_PASSWORD, CommandType.CHANGE_PASSWORD, CommandType.REMOVE_PASSWORD)
}

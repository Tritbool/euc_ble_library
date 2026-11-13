package io.github.tritbool.euc.ble.session

import io.github.tritbool.euc.ble.core.BLEConstants.ConnectionState
import io.github.tritbool.euc.ble.core.EucRandom
import io.github.tritbool.euc.ble.core.EucUuid
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.GotwayProtocol
import io.github.tritbool.euc.ble.protocols.ProtocolPollingPlan
import io.github.tritbool.euc.ble.protocols.ProtocolQuerySpec
import io.github.tritbool.euc.ble.protocols.ProtocolWriteType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class PortableRuntimeTest {
    @Test
    fun reentrantCallbacksCanReplaceConnection() = runTest {
        val addresses = mutableListOf<String>()
        val transport = object : BleTransport {
            override fun connect(address: String): Boolean { addresses += address; return true }
            override fun disconnect() {}
            override fun discoverServices() = false
            override fun hasCharacteristic(uuid: EucUuid) = true
            override fun subscribe(uuid: EucUuid) = true
            override fun write(uuid: EucUuid, bytes: ByteArray, preferredType: ProtocolWriteType) = true
        }
        lateinit var driver: ConnectionSessionDriver
        driver = ConnectionSessionDriver(transport, backgroundScope, EucRandom { 0 },
            { testScheduler.currentTime }, {}, { _, _ -> driver.connect("replacement") })
        driver.connect("wheel")
        driver.onConnected()
        assertEquals(listOf("wheel", "replacement"), addresses)
    }

    @Test
    fun pollingErrorCanStopWhileHoldingLock() = runTest {
        val facade = GotwayProtocol(scope = backgroundScope)
        val protocol = object : EUCProtocol by facade {
            override fun getPollingPlan() = ProtocolPollingPlan(true,
                startupQueries = listOf(ProtocolQuerySpec("q", CommandType.BEEP)))
            override fun getCommandSupport(commandType: CommandType) = CommandSupport.SUPPORTED
            override fun createCommand(commandType: CommandType, value: Any): ByteArray =
                error("bad encoding")
        }
        val errors = mutableListOf<Exception>()
        val polling = PollingSession(protocol, backgroundScope, { testScheduler.currentTime },
            {}, { _, _, _, _ -> fail("Must not enqueue") }, errors::add)
        polling.start()
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("bad encoding", errors.single().message)
        assertTrue(backgroundScope.isActive)
        facade.close()
    }

    @Test
    fun acceptedWritesWaitForAcknowledgment() = runTest {
        val session = WriteSession(backgroundScope, now = { testScheduler.currentTime }) { throw it }
        val writes = mutableListOf<Int>()
        for (byte in 1..2) {
            session.enqueue(byteArrayOf(byte.toByte()), { true },
                { writes += it[0].toInt(); true }, { true }, {}, { fail("Write failed") })
        }
        runCurrent()
        assertEquals(listOf(1), writes)
        session.onAcknowledgment()
        runCurrent()
        assertEquals(listOf(1, 2), writes)
        session.reset()
        assertTrue(backgroundScope.isActive)
    }

    @Test
    fun connectionTimerExpiresWithoutCancellingCallerScope() = runTest {
        val states = mutableListOf<ConnectionState>()
        val errors = mutableListOf<String>()
        val transport = object : BleTransport {
            override fun connect(address: String) = true
            override fun disconnect() {}
            override fun discoverServices() = true
            override fun hasCharacteristic(uuid: EucUuid) = true
            override fun subscribe(uuid: EucUuid) = true
            override fun write(uuid: EucUuid, bytes: ByteArray, preferredType: ProtocolWriteType) = true
        }
        val driver = ConnectionSessionDriver(transport, backgroundScope, EucRandom { 0 },
            { testScheduler.currentTime }, states::add, { message, _ -> errors += message })
        driver.configure(50, false, 0)
        driver.connect("wheel")
        runCurrent()
        advanceTimeBy(51)
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, states.last())
        assertEquals(1, errors.size)
        assertTrue(backgroundScope.isActive)
    }
}

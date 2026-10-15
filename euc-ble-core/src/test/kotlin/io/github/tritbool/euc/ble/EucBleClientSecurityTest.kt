package io.github.tritbool.euc.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.core.BLEManager
import io.github.tritbool.euc.ble.core.ErrorCallback
import io.github.tritbool.euc.ble.core.NoOpLogger
import io.github.tritbool.euc.ble.core.TrafficDirection
import io.github.tritbool.euc.ble.core.TrafficEvent
import io.github.tritbool.euc.ble.exceptions.BLEException
import io.github.tritbool.euc.ble.models.AuthenticationState
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.models.WheelLockState
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.models.PasswordOperation
import io.github.tritbool.euc.ble.models.PasswordOperationStatus
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.KingsongProtocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.Mockito.mockingDetails

@OptIn(ExperimentalCoroutinesApi::class)
class EucBleClientSecurityTest {
    @Test
    fun customCallerBufferIsNotWipedWhenProtocolReturnsItDirectly() = runTest {
        val (client, protocol, _) = connectedClient()
        client.bleManager.currentProtocol = object : EUCProtocol by protocol {
            override fun createCommand(commandType: CommandType, value: Any): ByteArray =
                if (commandType == CommandType.CUSTOM) value as ByteArray else protocol.createCommand(commandType, value)
        }
        val traffic = collectTraffic(client)
        val callerBuffer = notification(0x88, 0)
        val expected = callerBuffer.clone()
        client.sendCommand(CommandType.CUSTOM, callerBuffer)
        assertArrayEquals(expected, callerBuffer)
        callerBuffer.fill(0)
        runCurrent()
        assertArrayEquals(expected, traffic.single { it.direction == TrafficDirection.TX }.bytes)
    }

    @Test
    fun passwordApisValidateWithoutExposingInputAndOnlyKingsongSupportsThem() = runTest {
        val (client, _, _) = connectedClient()
        val commands = listOf(CommandType.SET_PASSWORD, CommandType.CHANGE_PASSWORD, CommandType.REMOVE_PASSWORD)
        for (protocol in client.getRegisteredProtocols()) {
            for (command in commands) {
                assertEquals(
                    if (protocol is KingsongProtocol) CommandSupport.SUPPORTED else CommandSupport.UNSUPPORTED,
                    protocol.getCommandSupport(command)
                )
            }
        }
        for (invalid in listOf("", "123", "12345", "12a3", "１２３４", "١٢٣٤")) {
            for (call in listOf<() -> Unit>(
                { client.setPassword(invalid) },
                { client.changePassword(invalid, "0012") },
                { client.changePassword("0012", invalid) },
                { client.removePassword(invalid) }
            )) {
                val error = assertThrows(IllegalArgumentException::class.java) { call() }
                assertEquals("Password must contain exactly four ASCII digits", error.message)
            }
        }
        val errors = mutableListOf<String>()
        client.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) { errors.add(error.message.orEmpty()) }
        })
        client.bleManager.currentProtocol = client.getRegisteredProtocols().first { it.manufacturer == "Gotway" }
        val traffic = collectTraffic(client)
        client.setPassword("0012")
        client.changePassword("0012", "0034")
        client.removePassword("0034")
        runCurrent()
        assertEquals(3, errors.size)
        assertTrue(errors.all { "Unsupported command" in it && "0012" !in it && "0034" !in it })
        assertTrue(traffic.isEmpty())
    }

    @Test
    fun acceptedPasswordWritesForwardResultsWithoutChangingAuthenticationOrLock() = runTest {
        val (client, protocol, _) = connectedClient()
        val events = mutableListOf<WheelSecurityEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.securityEventFlow.collect { events.add(it) }
        }
        protocol.decode(notification(0x46, 1) + notification(0x5F, 1))
        runCurrent()
        val state = client.securityStateFlow.value
        events.clear()
        for ((call, operation, target) in listOf(
            Triple<() -> Unit, PasswordOperation, ByteArray>(
                { client.setPassword("0012") }, PasswordOperation.SET, "0012".toByteArray(Charsets.US_ASCII)
            ),
            Triple<() -> Unit, PasswordOperation, ByteArray>(
                { client.changePassword("0012", "0034") }, PasswordOperation.CHANGE, "0034".toByteArray(Charsets.US_ASCII)
            ),
            Triple<() -> Unit, PasswordOperation, ByteArray>(
                { client.removePassword("0034") }, PasswordOperation.REMOVE, ByteArray(4) { 0xFF.toByte() }
            )
        )) {
            call()
            runCurrent()
            assertTrue(events.isEmpty())
            val reply = notification(0x43, 0).also {
                target.copyInto(it, 2)
                it[6] = 1
            }
            protocol.decode(reply)
            runCurrent()
            assertEquals(listOf(
                WheelSecurityEvent.PasswordOperationResult(operation, PasswordOperationStatus.CONFIRMED)
            ), events)
            assertEquals(state, client.securityStateFlow.value)
            events.clear()
            advanceTimeBy(300)
            runCurrent()
        }
    }

    @Test
    fun passwordTimeoutStartsAtAcceptedQueuedWriteAndOverlapIsNotAnAuthenticationError() = runTest {
        val (client, _, _) = connectedClient()
        val traffic = collectTraffic(client)
        val results = mutableListOf<WheelSecurityEvent>()
        val errors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.securityEventFlow.collect { results.add(it) }
        }
        client.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) { errors.add(error.message.orEmpty()) }
        })
        client.sendCommand(CommandType.BEEP)
        client.setPassword("0012")
        client.changePassword("0012", "0034")
        client.removePassword("0012")
        runCurrent()
        advanceTimeBy(299)
        runCurrent()
        assertTrue(traffic.none { it.direction == TrafficDirection.TX && it.bytes[16] == 0x41.toByte() })
        assertTrue(results.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, errors.size)
        assertTrue(errors.all { it == "Command blocked by protocol security state or pending operation" })
        assertEquals(1, traffic.count {
            it.direction == TrafficDirection.TX && it.bytes[16].toInt() in 0x41..0x42
        })
        advanceTimeBy(2699)
        runCurrent()
        assertTrue(results.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(WheelSecurityEvent.PasswordOperationResult(
            PasswordOperation.SET, PasswordOperationStatus.TIMEOUT_UNCONFIRMED
        )), results)
    }

    @Test
    fun rejectedPasswordWriteIsNeverRetriedAndDoesNotStartConfirmationTimeout() = runTest {
        val (client, _, gatt) = connectedClient()
        @Suppress("DEPRECATION")
        whenever(gatt.writeCharacteristic(any())).thenReturn(false)
        whenever(gatt.writeCharacteristic(any(), any(), any())).thenReturn(1)
        val traffic = collectTraffic(client)
        val results = mutableListOf<WheelSecurityEvent>()
        val errors = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.securityEventFlow.collect { results.add(it) }
        }
        client.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) { errors.add(error.message.orEmpty()) }
        })
        client.changePassword("0012", "0034")
        runCurrent()
        advanceTimeBy(6000)
        runCurrent()
        assertTrue(results.isEmpty())
        assertTrue(traffic.none { it.direction == TrafficDirection.TX })
        assertEquals(1, mockingDetails(gatt).invocations.count { it.method.name == "writeCharacteristic" })
        assertEquals(listOf("BLE stack rejected write after 1 attempts (20 bytes)"), errors)
    }

    @Test
    fun disconnectDropsQueuedPasswordAndCancelsAlreadySubmittedTimeout() = runTest {
        val (client, protocol, _) = connectedClient()
        val traffic = collectTraffic(client)
        val results = mutableListOf<WheelSecurityEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.securityEventFlow.collect { results.add(it) }
        }
        client.setPassword("0012")
        client.removePassword("0012")
        runCurrent()
        client.disconnect()
        attachGatt(client.bleManager, protocol)
        client.bleManager.currentProtocol = protocol
        client.bleManager.startDataFlowCollection(protocol)
        advanceTimeBy(6000)
        runCurrent()
        assertTrue(results.isEmpty())
        assertTrue(traffic.none { it.direction == TrafficDirection.TX && it.bytes[16] == 0x42.toByte() })
        protocol.decode(notification(0x43, 0).also {
            "0012".toByteArray(Charsets.US_ASCII).copyInto(it, 2)
            it[6] = 1
        })
        runCurrent()
        assertTrue(results.isEmpty())
        assertEquals(WheelSecurityState(), client.securityStateFlow.value)
    }

    @Test
    fun publicApiReportsSupportValidatesPinAndPublishesNotificationState() = runTest {
        val (client, protocol, _) = connectedClient()
        assertEquals(CommandSupport.SUPPORTED, client.getCommandSupport(CommandType.AUTHENTICATE))
        assertEquals(CommandSupport.SUPPORTED, client.getCommandSupport(CommandType.LOCK))
        assertEquals(WheelSecurityState(), client.securityStateFlow.value)
        val events = collectTraffic(client)
        val securityEvents = mutableListOf<WheelSecurityEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.securityEventFlow.collect { securityEvents.add(it) }
        }
        val errorMessages = mutableListOf<String>()
        client.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) { errorMessages.add(error.message.orEmpty()) }
        })
        for (pin in listOf("123", "１２３４", "12a3")) {
            assertThrows(IllegalArgumentException::class.java) { client.authenticate(pin) }
        }
        protocol.decode(notification(0x46, 1) + notification(0x5F, 1))
        runCurrent()
        assertEquals(WheelSecurityState(AuthenticationState.REQUIRED, WheelLockState.LOCKED), client.securityStateFlow.value)
        events.clear()
        client.lock()
        client.unlock()
        client.sendCommand(CommandType.CUSTOM, notification(0x5D, 0))
        runCurrent()
        assertTrue(events.none { it.direction == TrafficDirection.TX })
        assertEquals(3, errorMessages.size)
        client.authenticate("0012")
        runCurrent()
        assertEquals(AuthenticationState.REQUIRED, client.securityStateFlow.value?.authentication)
        val auth = events.single { it.direction == TrafficDirection.TX }
        assertEquals(0x44.toByte(), auth.bytes[16])
        assertArrayEquals("0012".toByteArray(Charsets.US_ASCII), auth.bytes.copyOfRange(2, 6))
        assertTrue(errorMessages.none { "0012" in it })
        protocol.decode(notification(0x46, 1))
        runCurrent()
        assertEquals(2, securityEvents.count {
            it == WheelSecurityEvent.AuthenticationStatus(AuthenticationState.REQUIRED)
        })
        assertTrue(client.securityEventFlow.replayCache.isEmpty())
        protocol.decode(notification(0x46, 0))
        runCurrent()
        advanceTimeBy(300)
        client.unlock()
        runCurrent()
        assertEquals(WheelLockState.LOCKED, client.securityStateFlow.value?.lock)
        advanceTimeBy(300)
        client.requestLockState()
        runCurrent()
        assertTrue(events.any { it.direction == TrafficDirection.TX && it.bytes[16] == 0x5E.toByte() })
        protocol.decode(notification(0x5F, 0))
        runCurrent()
        assertEquals(WheelLockState.UNLOCKED, client.securityStateFlow.value?.lock)
    }

    @Test
    fun queueRechecksAuthenticationBeforeWritingAndDoesNotAutomaticallyResumeLock() = runTest {
        val (client, protocol, _) = connectedClient()
        val events = collectTraffic(client)
        client.sendCommand(CommandType.BEEP)
        client.lock()
        runCurrent()
        protocol.decode(notification(0x46, 1))
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(events.none { it.direction == TrafficDirection.TX && it.bytes[16] == 0x5D.toByte() })
        protocol.decode(notification(0x46, 0))
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(events.none { it.direction == TrafficDirection.TX && it.bytes[16] == 0x5D.toByte() })
    }

    @Test
    fun manualDisconnectClearsStateAndDropsQueuedPinBeforeReuse() = runTest {
        val (client, protocol, _) = connectedClient()
        protocol.decode(notification(0x46, 0) + notification(0x5F, 1))
        runCurrent()
        val events = collectTraffic(client)
        client.sendCommand(CommandType.BEEP)
        runCurrent()
        client.authenticate("0012")
        client.disconnect()
        assertNull(client.securityStateFlow.value)
        assertEquals(WheelSecurityState(), protocol.getSecurityState())
        attachGatt(client.bleManager, protocol)
        client.bleManager.currentProtocol = protocol
        client.bleManager.startDataFlowCollection(protocol)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(WheelSecurityState(), client.securityStateFlow.value)
        assertTrue(events.none { it.direction == TrafficDirection.TX && it.bytes[16] == 0x44.toByte() })
    }

    @Test
    fun unexpectedDisconnectAndForcedReselectionStartFreshAndDropLateFrames() = runTest {
        val (client, protocol, gatt) = connectedClient()
        protocol.decode(notification(0x46, 0))
        runCurrent()
        protocol.decode(notification(0x5F, 1))
        client.bleManager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()
        assertNull(client.securityStateFlow.value)
        assertNull(client.bleManager.currentProtocol)
        assertEquals(WheelSecurityState(), protocol.getSecurityState())
        attachGatt(client.bleManager, protocol)
        setField(client.bleManager, "currentDevice",
            EUCDevice(name = "KS", address = "AA:BB:CC:DD:EE:FF", manufacturerId = 0, rssi = -40))
        assertTrue(client.forceProtocol(protocol))
        runCurrent()
        assertEquals(WheelSecurityState(), client.securityStateFlow.value)
        protocol.decode(notification(0x46, 1))
        runCurrent()
        assertEquals(AuthenticationState.REQUIRED, client.securityStateFlow.value?.authentication)
    }

    @Test
    fun changingProtocolClearsConfirmedStateAndQueuedPin() = runTest {
        val (client, protocol, _) = connectedClient()
        val events = collectTraffic(client)
        protocol.decode(notification(0x46, 0))
        runCurrent()
        client.authenticate("0012")
        // Gotway shares FFE1 with KingSong; forcing it tests protocol-switch queue isolation.
        setField(client.bleManager, "currentDevice",
            EUCDevice(name = "Wheel", address = "AA:BB:CC:DD:EE:FF", manufacturerId = 0, rssi = -40))
        assertTrue(client.forceProtocol(client.getRegisteredProtocols().first { it.manufacturer == "Gotway" }))
        runCurrent()
        assertNull(client.securityStateFlow.value)
        assertEquals(CommandSupport.UNSUPPORTED, client.getCommandSupport(CommandType.AUTHENTICATE))
        assertTrue(events.none { it.direction == TrafficDirection.TX && it.bytes.size == 20 && it.bytes[16] == 0x44.toByte() })
    }

    @Test
    fun callbacksFromOldGattCannotChangeNewConnectionSecurityState() = runTest {
        val (client, protocol, oldGatt) = connectedClient()
        val newGatt = attachGatt(client.bleManager, protocol)
        val characteristic = newGatt.services.single().characteristics.single()
        val required = notification(0x46, 1)
        client.bleManager.onCharacteristicChanged(oldGatt, characteristic, required)
        @Suppress("DEPRECATION")
        whenever(characteristic.value).thenReturn(notification(0x5F, 1))
        @Suppress("DEPRECATION")
        client.bleManager.onCharacteristicChanged(oldGatt, characteristic)
        client.bleManager.onConnectionStateChange(oldGatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        runCurrent()
        assertEquals(BLEConstants.ConnectionState.CONNECTED, client.getConnectionState())
        assertEquals(WheelSecurityState(), client.securityStateFlow.value)
        client.bleManager.onCharacteristicChanged(newGatt, characteristic, required)
        runCurrent()
        assertEquals(AuthenticationState.REQUIRED, client.securityStateFlow.value?.authentication)
    }

    private fun TestScope.connectedClient(): Triple<EucBleClient, KingsongProtocol, BluetoothGatt> {
        val client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        val protocol = client.getRegisteredProtocols().filterIsInstance<KingsongProtocol>().single()
        assertNull(client.securityStateFlow.value)
        val gatt = attachGatt(client.bleManager, protocol)
        client.bleManager.currentProtocol = protocol
        client.bleManager.startDataFlowCollection(protocol)
        return Triple(client, protocol, gatt)
    }

    private fun TestScope.collectTraffic(client: EucBleClient): MutableList<TrafficEvent> {
        val events = mutableListOf<TrafficEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.trafficFlow.collect { events.add(it) }
        }
        return events
    }

    private fun attachGatt(manager: BLEManager, protocol: KingsongProtocol): BluetoothGatt {
        val characteristic = mock<BluetoothGattCharacteristic>()
        whenever(characteristic.uuid).thenReturn(protocol.getDataCharacteristicUUID())
        whenever(characteristic.properties).thenReturn(
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        )
        val service = mock<BluetoothGattService>()
        whenever(service.characteristics).thenReturn(listOf(characteristic))
        val gatt = mock<BluetoothGatt>()
        whenever(gatt.services).thenReturn(listOf(service))
        @Suppress("DEPRECATION")
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)
        whenever(gatt.writeCharacteristic(any(), any(), any())).thenReturn(BluetoothStatusCodes.SUCCESS)
        setField(manager, "bluetoothGatt", gatt)
        setField(manager, "connectionState", BLEConstants.ConnectionState.CONNECTED)
        return gatt
    }

    private fun setField(manager: BLEManager, name: String, value: Any) {
        BLEManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
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

package io.github.tritbool.euc.ble.core

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.InMotionProtocol
import io.github.tritbool.euc.ble.protocols.ProtocolPollingPlan
import io.github.tritbool.euc.ble.protocols.EucFingerprintDatabase
import io.github.tritbool.euc.ble.models.EUCDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BLEManagerTrafficTest {
    private val characteristicUuid = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

    @Test
    fun v2GattFingerprintDialectIsReappliedAfterSessionResetBeforePolling() = runTest {
        val manager = manager()
        val protocol = InMotionProtocol(NoOpLogger())
        manager.registerProtocol(protocol)
        val signature = EucFingerprintDatabase.entries
            .first { it.protocolId == "InMotionProtocol" }.gattSignatures
            .first { specs -> specs.any { it.version == 2 } }
        val services = signature.map { spec ->
            val characteristics = spec.requiredCharacteristicUUIDs.map { uuid ->
                mock<BluetoothGattCharacteristic>().also { characteristic ->
                    whenever(characteristic.uuid).thenReturn(uuid)
                }
            }
            mock<BluetoothGattService>().also { service ->
                whenever(service.uuid).thenReturn(spec.uuid)
                whenever(service.characteristics).thenReturn(characteristics)
            }
        }
        val gatt = mock<BluetoothGatt>()
        whenever(gatt.services).thenReturn(services)
        setField(manager, "bluetoothGatt", gatt)
        setField(manager, "connectionState", BLEConstants.ConnectionState.CONNECTED)
        setField(manager, "currentDevice", EUCDevice(
            name = "InMotion P6", address = "AA:BB:CC:DD:EE:FF",
            manufacturerId = 0, manufacturerData = null, rssi = -50
        ))

        manager.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertSame(protocol, manager.currentProtocol)
        assertEquals(UUID.fromString(BLEConstants.INMOTION_V2_WRITE_CHARACTERISTIC),
            protocol.getWriteCharacteristicUUID())
        assertEquals(UUID.fromString(BLEConstants.INMOTION_V2_READ_CHARACTERISTIC),
            protocol.getDataCharacteristicUUID())
        assertTrue(protocol.getPollingPlan().enabled)
    }

    @Test
    fun pollingRefreshesAfterP6IdentificationWithoutRestartingConnection() = runTest {
        val manager = manager()
        val realProtocol = InMotionProtocol(NoOpLogger())
        // Isolate periodic orchestration from handshake retry delays.
        val protocol = object : EUCProtocol by realProtocol {
            override fun getPollingPlan(): ProtocolPollingPlan =
                realProtocol.getPollingPlan().copy(startupQueries = emptyList())
        }
        manager.currentProtocol = protocol
        attachGatt(manager).also { acceptWrites(it.first) }
        val traces = mutableListOf<QueryTraceEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.queryTraceFlow.collect { traces += it }
        }
        BLEManager::class.java.getDeclaredMethod("startPollingOrchestration", EUCProtocol::class.java)
            .apply { isAccessible = true }.invoke(manager, protocol)
        runCurrent()
        assertTrue(traces.none { it.queryId.startsWith("inmotion.p6-pack-") })

        manager.handleIncomingBytes(ByteUtils.hexToBytes("aaaa11058201020d0199"), characteristicUuid)
        advanceTimeBy(3_000)
        runCurrent()

        val sentP6Ids = traces.filter {
            it.phase == QueryTracePhase.SENT && it.queryId.startsWith("inmotion.p6-pack-")
        }.map { it.queryId }.toSet()
        assertEquals(realProtocol.getPollingPlan().periodicQueries
            .filter { it.id.startsWith("inmotion.p6-pack-") }.map { it.id }.toSet(), sentP6Ids)
        assertEquals(6, sentP6Ids.size)
    }

    @Test
    fun completedP6IdentityJobsAreNotRestartedByPollingPlanRefresh() = runTest {
        val manager = manager()
        val realProtocol = InMotionProtocol(NoOpLogger())
        realProtocol.decode(ByteUtils.hexToBytes("aaaa11058201020d0199"))
        val protocol = object : EUCProtocol by realProtocol {
            override fun getPollingPlan(): ProtocolPollingPlan {
                val plan = realProtocol.getPollingPlan()
                return plan.copy(startupQueries = emptyList(), periodicQueries =
                    plan.periodicQueries.filter { it.id.endsWith("-4") }.map {
                        it.copy(initialDelayMs = 0L, responseTimeoutMs = 1L, maxRetries = 0)
                    })
            }
        }
        manager.currentProtocol = protocol
        attachGatt(manager)
        val sentIdentities = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.queryTraceFlow.collect {
                if (it.phase == QueryTracePhase.SENT) sentIdentities += it.queryId
            }
        }
        BLEManager::class.java.getDeclaredMethod("startPollingOrchestration", EUCProtocol::class.java)
            .apply { isAccessible = true }.invoke(manager, protocol)

        advanceTimeBy(125_000)
        runCurrent()

        assertEquals(listOf("inmotion.p6-pack-1-4", "inmotion.p6-pack-2-4"),
            sentIdentities.sorted())
    }

    @Test
    fun rxSnapshotIsCapturedBeforeDecodeAndSharedWithRawView() = runTest {
        val manager = manager()
        val events = collectTraffic(manager)
        val raw = mutableListOf<ByteArray>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.rawFrameFlow.collect { raw.add(it) }
        }
        val bytes = byteArrayOf(0x55, 0x01)
        val protocol = mock<EUCProtocol>()
        whenever(protocol.decode(any())).thenAnswer {
            bytes.fill(0)
            null
        }
        manager.currentProtocol = protocol
        val before = System.currentTimeMillis()

        manager.handleIncomingBytes(bytes, characteristicUuid)

        val event = events.single()
        assertEquals(TrafficDirection.RX, event.direction)
        assertEquals(characteristicUuid, event.characteristicUuid)
        assertEquals(1L, event.sequence)
        assertTrue(event.timestampMs in before..System.currentTimeMillis())
        assertArrayEquals(byteArrayOf(0x55, 0x01), event.bytes)
        assertNotSame(bytes, event.bytes)
        assertSame(event.bytes, raw.single())
    }

    @Test
    fun bothNotificationCallbacksPreserveCharacteristicAndChunkBoundaries() = runTest {
        val manager = manager()
        val events = collectTraffic(manager)
        val gatt = mock<BluetoothGatt>()
        val characteristic = characteristic()
        val modern = byteArrayOf(0x55)
        val legacy = byteArrayOf(0x01, 0x02)
        @Suppress("DEPRECATION")
        whenever(characteristic.value).thenReturn(legacy)

        manager.onCharacteristicChanged(gatt, characteristic, modern)
        @Suppress("DEPRECATION")
        manager.onCharacteristicChanged(gatt, characteristic)
        modern.fill(0)
        legacy.fill(0)

        assertEquals(listOf(1L, 2L), events.map { it.sequence })
        assertTrue(events.all { it.direction == TrafficDirection.RX && it.characteristicUuid == characteristicUuid })
        assertArrayEquals(byteArrayOf(0x55), events[0].bytes)
        assertArrayEquals(byteArrayOf(0x01, 0x02), events[1].bytes)
    }

    @Test
    fun queuedWriteIsCapturedOnlyAfterAcceptanceAndNeverInRawView() = runTest {
        val manager = manager()
        val events = collectTraffic(manager)
        val raw = mutableListOf<ByteArray>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.rawFrameFlow.collect { raw.add(it) }
        }
        val (gatt, characteristic) = attachGatt(manager)
        acceptWrites(gatt)
        val bytes = byteArrayOf(0x51)

        manager.sendCommand(bytes, characteristicUuid)
        bytes.fill(0)
        assertTrue(events.isEmpty())
        val before = System.currentTimeMillis()
        runCurrent()

        val event = events.single()
        assertEquals(TrafficDirection.TX, event.direction)
        assertEquals(characteristicUuid, event.characteristicUuid)
        assertArrayEquals(byteArrayOf(0x51), event.bytes)
        assertTrue(event.timestampMs in before..System.currentTimeMillis())
        assertTrue(raw.isEmpty())

        manager.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_FAILURE)
        runCurrent()
        assertEquals(1, events.size)
    }

    @Test
    fun rejectedAttemptFollowedByAcceptedRetryProducesExactlyOneTx() = runTest {
        val manager = manager()
        val events = collectTraffic(manager)
        val (gatt, _) = attachGatt(manager)
        @Suppress("DEPRECATION")
        whenever(gatt.writeCharacteristic(any())).thenReturn(false, true)
        whenever(gatt.writeCharacteristic(any(), any(), any()))
            .thenReturn(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY, BluetoothStatusCodes.SUCCESS)

        manager.sendCommand(byteArrayOf(0x01), characteristicUuid)
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(30)
        runCurrent()

        assertEquals(listOf(TrafficDirection.TX), events.map { it.direction })
        assertEquals(1L, events.single().sequence)
    }

    @Test
    fun rejectedAndThrowingWritesNeverProduceTxOrConsumeSequenceNumbers() = runTest {
        val manager = manager()
        val events = collectTraffic(manager)
        val (gatt, _) = attachGatt(manager)
        @Suppress("DEPRECATION")
        whenever(gatt.writeCharacteristic(any())).thenReturn(false)
        whenever(gatt.writeCharacteristic(any(), any(), any()))
            .thenReturn(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY)
        manager.sendCommand(byteArrayOf(0x01), characteristicUuid)
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(events.isEmpty())

        @Suppress("DEPRECATION")
        whenever(gatt.writeCharacteristic(any())).thenThrow(SecurityException("Denied"))
        whenever(gatt.writeCharacteristic(any(), any(), any())).thenThrow(SecurityException("Denied"))
        manager.sendCommand(byteArrayOf(0x02), characteristicUuid)
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(events.isEmpty())

        manager.handleIncomingBytes(byteArrayOf(0x03), characteristicUuid)
        assertEquals(1L, events.single().sequence)
    }

    @Test
    fun rxAndTxShareOneSequenceAndTimeoutDoesNotDuplicateTx() = runTest {
        val manager = manager()
        val events = collectTraffic(manager)
        val (gatt, _) = attachGatt(manager)
        acceptWrites(gatt)

        manager.handleIncomingBytes(byteArrayOf(0x01), characteristicUuid)
        manager.sendCommand(byteArrayOf(0x02), characteristicUuid)
        runCurrent()
        manager.handleIncomingBytes(byteArrayOf(0x03), characteristicUuid)
        advanceTimeBy(1000)
        runCurrent()

        assertEquals(listOf(1L, 2L, 3L), events.map { it.sequence })
        assertEquals(listOf(TrafficDirection.RX, TrafficDirection.TX, TrafficDirection.RX), events.map { it.direction })
    }

    @Test
    fun trafficHasNoReplayAndSequenceContinuesWithoutCollectors() = runTest {
        val manager = manager()
        manager.handleIncomingBytes(byteArrayOf(0x01), characteristicUuid)
        assertTrue(manager.trafficFlow.replayCache.isEmpty())
        val events = collectTraffic(manager)
        assertTrue(events.isEmpty())

        manager.handleIncomingBytes(byteArrayOf(0x02), characteristicUuid)

        assertEquals(2L, events.single().sequence)
    }

    @Test
    fun slowCollectorSeesSequenceGapWhenBoundedBufferOverflows() = runTest {
        val manager = manager()
        val events = mutableListOf<TrafficEvent>()
        backgroundScope.launch { manager.trafficFlow.collect { events.add(it) } }
        runCurrent()
        manager.handleIncomingBytes(byteArrayOf(0x01), characteristicUuid)
        runCurrent()

        repeat(BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY + 3) {
            manager.handleIncomingBytes(byteArrayOf(0x02), characteristicUuid)
        }
        runCurrent()

        assertEquals(1L, events.first().sequence)
        assertEquals(5L, events[1].sequence)
        assertEquals(BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY + 1, events.size)
        assertEquals((BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY + 4).toLong(), events.last().sequence)
    }

    @Test
    fun concurrentCapturesRemainInSequenceOrder() = runTest {
        val manager = manager()
        val events = mutableListOf<TrafficEvent>()
        backgroundScope.launch { manager.trafficFlow.collect { events.add(it) } }
        runCurrent()

        withContext(Dispatchers.Default) {
            coroutineScope {
                repeat(8) {
                    launch {
                        repeat(16) { manager.handleIncomingBytes(byteArrayOf(0x01), characteristicUuid) }
                    }
                }
            }
        }
        runCurrent()

        assertEquals((1L..128L).toList(), events.map { it.sequence })
    }

    private fun TestScope.manager() = BLEManager(mock<Context>(), NoOpLogger(), backgroundScope)

    private fun TestScope.collectTraffic(manager: BLEManager): MutableList<TrafficEvent> {
        val events = mutableListOf<TrafficEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.trafficFlow.collect { events.add(it) }
        }
        return events
    }

    private fun characteristic() = mock<BluetoothGattCharacteristic>().also {
        whenever(it.uuid).thenReturn(characteristicUuid)
        whenever(it.properties).thenReturn(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
    }

    private fun attachGatt(manager: BLEManager): Pair<BluetoothGatt, BluetoothGattCharacteristic> {
        val characteristic = characteristic()
        val service = mock<BluetoothGattService>()
        whenever(service.characteristics).thenReturn(listOf(characteristic))
        val gatt = mock<BluetoothGatt>()
        whenever(gatt.services).thenReturn(listOf(service))
        setField(manager, "bluetoothGatt", gatt)
        setField(manager, "connectionState", BLEConstants.ConnectionState.CONNECTED)
        return gatt to characteristic
    }

    private fun acceptWrites(gatt: BluetoothGatt) {
        @Suppress("DEPRECATION")
        whenever(gatt.writeCharacteristic(any())).thenReturn(true)
        whenever(gatt.writeCharacteristic(any(), any(), any())).thenReturn(BluetoothStatusCodes.SUCCESS)
    }

    private fun setField(manager: BLEManager, name: String, value: Any) {
        BLEManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }
}

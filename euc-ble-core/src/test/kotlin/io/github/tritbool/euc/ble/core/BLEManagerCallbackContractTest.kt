package io.github.tritbool.euc.ble.core

import android.bluetooth.BluetoothGattService
import android.content.Context
import io.github.tritbool.euc.ble.exceptions.BLEException
import io.github.tritbool.euc.ble.integration.BleBackendEvent
import io.github.tritbool.euc.ble.integration.BleBackendListener
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

class BLEManagerCallbackContractTest {
    @Test
    fun connectionCallbackDefaultImplementationsAreSafeNoOps() {
        val callback = object : ConnectionCallback() {}
        val device = device()
        val service = mock<BluetoothGattService>()

        callback.onScanStarted()
        callback.onDeviceDiscovered(device)
        callback.onScanCompleted(listOf(device))
        callback.onConnecting()
        callback.onConnected()
        callback.onDisconnected()
        callback.onConnectionFailed(BLEException("connection failed"))
        callback.onServicesDiscovered(listOf(service))
        callback.onMtuChanged(247)
        callback.onProtocolSelectionRequired(emptyList())
        callback.onProtocolSelected(
            ProtocolSelection("protocol", "manufacturer", "Protocol", ProtocolSelectionReason.FORCED)
        )
    }

    @Test
    fun listenerConnectionCallbackForwardsEvents() {
        val manager = BLEManager(mock<Context>(), NoOpLogger())
        val events = mutableListOf<BleBackendEvent>()
        val callback = ListenerConnectionCallback(BleBackendListener(events::add), manager)
        val device = device()
        val service = mock<BluetoothGattService>()
        val serviceUuid = UUID.randomUUID()
        whenever(service.uuid).thenReturn(serviceUuid)
        val protocol = mock<EUCProtocol>()
        val selection = ProtocolSelection(
            "protocol",
            "manufacturer",
            "Protocol",
            ProtocolSelectionReason.AUTO_GATT_FINGERPRINT
        )
        val error = BLEException("connection failed")

        callback.onScanStarted()
        callback.onDeviceDiscovered(device)
        callback.onScanCompleted(listOf(device))
        callback.onConnecting()
        callback.onConnected()
        callback.onDisconnected()
        callback.onConnectionFailed(error)
        callback.onServicesDiscovered(listOf(service))
        callback.onMtuChanged(247)
        callback.onProtocolSelectionRequired(listOf(protocol))
        callback.onProtocolSelected(selection)

        assertEquals(
            listOf(
                BleBackendEvent.ScanStarted,
                BleBackendEvent.DeviceDiscovered(device),
                BleBackendEvent.ScanCompleted(listOf(device)),
                BleBackendEvent.Connecting,
                BleBackendEvent.Connected(null),
                BleBackendEvent.Disconnected,
                BleBackendEvent.Error(error),
                BleBackendEvent.ServicesDiscovered(listOf(serviceUuid)),
                BleBackendEvent.MtuChanged(247),
                BleBackendEvent.ProtocolSelectionRequired(listOf(protocol)),
                BleBackendEvent.ProtocolSelected(selection)
            ),
            events
        )
        assertNull(manager.getConnectedDevice())
    }

    @Test
    fun listenerConnectionCallbackAllowsMissingListener() {
        val callback = ListenerConnectionCallback(null, BLEManager(mock<Context>(), NoOpLogger()))

        callback.onScanStarted()
        callback.onConnected()
        callback.onConnectionFailed(BLEException("connection failed"))
    }

    @Test
    fun queryTraceModelsPreserveFieldsAndExposeAllPhases() {
        val event = QueryTraceEvent(
            timestampMs = 123L,
            protocol = "Test",
            queryId = "serial",
            commandType = io.github.tritbool.euc.ble.protocols.CommandType.REQUEST_SERIAL,
            phase = QueryTracePhase.RESPONSE_MATCHED,
            attempt = 2,
            latencyMs = 45L,
            message = "matched"
        )

        assertEquals(123L, event.timestampMs)
        assertEquals("Test", event.protocol)
        assertEquals("serial", event.queryId)
        assertEquals(io.github.tritbool.euc.ble.protocols.CommandType.REQUEST_SERIAL, event.commandType)
        assertEquals(QueryTracePhase.RESPONSE_MATCHED, event.phase)
        assertEquals(2, event.attempt)
        assertEquals(45L, event.latencyMs)
        assertEquals("matched", event.message)
        assertEquals(
            setOf(
                QueryTracePhase.SENT,
                QueryTracePhase.RESPONSE_MATCHED,
                QueryTracePhase.TIMEOUT,
                QueryTracePhase.RETRY_SCHEDULED,
                QueryTracePhase.UNSUPPORTED,
                QueryTracePhase.FAILED
            ),
            QueryTracePhase.entries.toSet()
        )
        assertEquals(event, event.copy())
    }

    private fun device() = EUCDevice(
        name = "test",
        address = "00:11:22:33:44:55",
        manufacturerId = 1,
        rssi = -50
    )
}

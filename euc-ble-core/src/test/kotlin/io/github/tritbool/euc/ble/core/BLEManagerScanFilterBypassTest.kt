package io.github.tritbool.euc.ble.core

import android.content.Context
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for [BLEManager.shouldForwardDevice].
 *
 * All discovered BLE devices are always forwarded to [ConnectionCallback.onDeviceDiscovered]
 * regardless of whether any registered protocol claims to support them.  Protocol
 * identification is deferred to post-connection negotiation when the first data frames arrive.
 */
class BLEManagerScanFilterBypassTest {

    private lateinit var bleManager: BLEManager
    private lateinit var protocol: EUCProtocol

    private val knownDevice = EUCDevice(
        name = "KS-16X",
        address = "AA:BB:CC:DD:EE:FF",
        manufacturerId = BLEConstants.MANUFACTURER_KINGSONG,
        rssi = -55
    )
    private val unknownDevice = EUCDevice(
        name = "RandomHeadphones",
        address = "11:22:33:44:55:66",
        manufacturerId = 0,
        rssi = -70
    )

    @BeforeEach
    fun setUp() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        bleManager = BLEManager(mock<Context>(), NoOpLogger(), scope)

        protocol = mock<EUCProtocol>()
        whenever(protocol.canHandle(knownDevice)).thenReturn(true)
        whenever(protocol.canHandle(unknownDevice)).thenReturn(false)

        bleManager.registerProtocol(protocol)
    }

    @Test
    fun `all devices are forwarded including protocol-recognised ones`() {
        assertTrue(bleManager.shouldForwardDevice(knownDevice))
    }

    @Test
    fun `all devices are forwarded including unrecognised ones`() {
        assertTrue(bleManager.shouldForwardDevice(unknownDevice))
    }

    @Test
    fun `devices are forwarded even when no protocols are registered`() {
        val emptyManager = BLEManager(
            mock<Context>(), NoOpLogger(),
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        )

        assertTrue(emptyManager.shouldForwardDevice(unknownDevice))
    }
}

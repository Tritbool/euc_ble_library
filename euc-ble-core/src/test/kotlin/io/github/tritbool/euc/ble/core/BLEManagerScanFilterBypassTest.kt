package io.github.tritbool.euc.ble.core

import android.content.Context
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Unit tests for the [BLEManager.shouldForwardDevice] scan-filter-bypass feature.
 *
 * These tests verify that:
 * - By default only protocol-recognised devices are forwarded.
 * - After [BLEManager.setScanFilterBypass] is set to `true`, every device is forwarded.
 * - Restoring the flag to `false` re-enables the filter.
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
    fun `default filter forwards only protocol-recognised devices`() {
        assertTrue(bleManager.shouldForwardDevice(knownDevice))
        assertFalse(bleManager.shouldForwardDevice(unknownDevice))
    }

    @Test
    fun `bypass enabled forwards all devices including unrecognised ones`() {
        bleManager.setScanFilterBypass(true)

        assertTrue(bleManager.shouldForwardDevice(knownDevice))
        assertTrue(bleManager.shouldForwardDevice(unknownDevice))
    }

    @Test
    fun `bypass disabled after being enabled restores normal filtering`() {
        bleManager.setScanFilterBypass(true)
        bleManager.setScanFilterBypass(false)

        assertTrue(bleManager.shouldForwardDevice(knownDevice))
        assertFalse(bleManager.shouldForwardDevice(unknownDevice))
    }

    @Test
    fun `bypass with no registered protocols forwards all devices`() {
        val emptyManager = BLEManager(
            mock<Context>(), NoOpLogger(),
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        )
        emptyManager.setScanFilterBypass(true)

        assertTrue(emptyManager.shouldForwardDevice(unknownDevice))
    }

    @Test
    fun `no bypass with no registered protocols blocks all devices`() {
        val emptyManager = BLEManager(
            mock<Context>(), NoOpLogger(),
            CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        )

        assertFalse(emptyManager.shouldForwardDevice(unknownDevice))
    }
}

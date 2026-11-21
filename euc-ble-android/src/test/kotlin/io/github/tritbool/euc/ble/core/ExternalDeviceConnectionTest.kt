/*
 *
 *  Copyright (C) 2026 Gauthier Le Bartz Lyan (Tritbool)
 *  euc_ble_library — version 1.2.0 — licence GPL-3.0
 *  https://github.com/Tritbool/euc_ble_library
 * /
 */

package io.github.tritbool.euc.ble.core

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothProfile
import android.content.Context
import io.github.tritbool.euc.ble.EucBleClient
import io.github.tritbool.euc.ble.exceptions.BLEException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalDeviceConnectionTest {
    @Test
    fun clientConnectsExternalDeviceWithAdvertisementMetadataWithoutScan() = runTest {
        val context = mock<Context>()
        val device = device("cached name")
        val gatt = mock<BluetoothGatt>()
        whenever(device.connectGatt(eq(context), eq(false), any())).thenReturn(gatt)
        val client = EucBleClient(context, NoOpLogger(), backgroundScope)
        var connecting = 0
        client.setConnectionCallback(object : ConnectionCallback() {
            override fun onConnecting() { connecting++ }
        })

        client.connectExternalDevice(device, "NOSFET", -42)

        verify(device).connectGatt(eq(context), eq(false), any())
        assertEquals(1, connecting)
        assertEquals(BLEConstants.ConnectionState.CONNECTING, client.getConnectionState())
        val connectedDevice = requireNotNull(client.getConnectedDevice())
        assertEquals("NOSFET", connectedDevice.name)
        assertEquals("00:11:22:33:44:55", connectedDevice.address)
        assertEquals(-42, connectedDevice.rssi)
        client.disconnect()
    }

    @Test
    fun externalDeviceUsesCachedNameAndReportsRejectedConnection() = runTest {
        val device = device("wheel")
        val client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        val errors = mutableListOf<BLEException>()
        val failures = mutableListOf<BLEException>()
        var nameWhileConnecting: String? = null
        client.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) { errors += error }
        })
        client.setConnectionCallback(object : ConnectionCallback() {
            override fun onConnecting() { nameWhileConnecting = client.getConnectedDevice()?.name }
            override fun onConnectionFailed(error: BLEException) { failures += error }
        })

        client.connectExternalDevice(device)

        assertEquals("wheel", nameWhileConnecting)
        assertEquals("BLE stack rejected connection", errors.single().message)
        assertEquals(errors.single(), failures.single())
        assertEquals(BLEConstants.ConnectionState.DISCONNECTED, client.getConnectionState())
        assertNull(client.getConnectedDevice())
    }

    @Test
    fun externalDeviceReportsGattSecurityFailure() = runTest {
        val context = mock<Context>()
        val device = device("wheel")
        whenever(device.connectGatt(eq(context), eq(false), any()))
            .thenThrow(SecurityException("permission denied"))
        val client = EucBleClient(context, NoOpLogger(), backgroundScope)
        val failures = mutableListOf<BLEException>()
        client.setConnectionCallback(object : ConnectionCallback() {
            override fun onConnectionFailed(error: BLEException) { failures += error }
        })

        client.connectExternalDevice(device)

        assertEquals("Failed to connect to device: permission denied", failures.single().message)
        assertEquals(BLEConstants.ConnectionState.DISCONNECTED, client.getConnectionState())
        assertNull(client.getConnectedDevice())
    }

    @Test
    fun externalDeviceReportsMissingPermissionBeforeStartingConnection() = runTest {
        val device = mock<BluetoothDevice>()
        whenever(device.name).thenThrow(SecurityException("permission denied"))
        val client = EucBleClient(mock<Context>(), NoOpLogger(), backgroundScope)
        val errors = mutableListOf<BLEException>()
        val failures = mutableListOf<BLEException>()
        client.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) { errors += error }
        })
        client.setConnectionCallback(object : ConnectionCallback() {
            override fun onConnectionFailed(error: BLEException) { failures += error }
        })

        client.connectExternalDevice(device)

        assertEquals("Cannot access external Bluetooth device: permission denied", errors.single().message)
        assertEquals(errors.single(), failures.single())
        assertEquals(BLEConstants.ConnectionState.DISCONNECTED, client.getConnectionState())
        assertNull(client.getConnectedDevice())
        verify(device, never()).connectGatt(any(), eq(false), any())
    }

    @Test
    fun externalDeviceReconnectsAndBusyRequestCannotReplaceItsTransport() = runTest {
        val context = mock<Context>()
        val first = device("first")
        val replacement = device("replacement")
        val gatt = mock<BluetoothGatt>()
        whenever(first.connectGatt(eq(context), eq(false), any())).thenReturn(gatt)
        whenever(gatt.discoverServices()).thenReturn(true)
        val manager = BLEManager(context, NoOpLogger(), backgroundScope, { testScheduler.currentTime }, EucRandom { 0 })
        val errors = mutableListOf<BLEException>()
        manager.setErrorCallback(object : ErrorCallback {
            override fun onError(error: BLEException) { errors += error }
        })

        manager.connectExternalDevice(first, null, -30)
        manager.connectExternalDevice(replacement, "other", -40)
        assertEquals("Already connecting or connected", errors.single().message)
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        verify(gatt).discoverServices()
        manager.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        advanceTimeBy(BLEConstants.RECONNECT_BASE_DELAY_MS + 1)
        runCurrent()

        verify(first, times(2)).connectGatt(eq(context), eq(false), any())
        verify(replacement, never()).connectGatt(eq(context), eq(false), any())
        assertEquals("first", manager.getConnectedDevice()?.name)
        manager.disconnect()
    }

    private fun device(name: String): BluetoothDevice = mock<BluetoothDevice>().also {
        whenever(it.address).thenReturn("00:11:22:33:44:55")
        whenever(it.name).thenReturn(name)
    }
}

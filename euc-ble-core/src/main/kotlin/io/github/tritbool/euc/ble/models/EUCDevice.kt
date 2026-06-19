package io.github.tritbool.euc.ble.models

import android.bluetooth.BluetoothDevice

/**
 * Represents an Electric Unicycle device
 */
data class EUCDevice(
    val bluetoothDevice: BluetoothDevice?=null,
    val name: String,
    val address: String,
    val manufacturerId: Int,
    val manufacturerData: ByteArray?=null,
    val rssi: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EUCDevice

        if (address != other.address) return false

        return true
    }

    override fun hashCode(): Int {
        return address.hashCode()
    }

    fun isSameDevice(other: EUCDevice?): Boolean {
        return other != null && this.address == other.address
    }
}
package io.github.tritbool.euc.ble.models

/**
 * Represents an Electric Unicycle device discovered during BLE scanning.
 *
 * Contains device identification and connection information used for
 * establishing BLE connections and selecting appropriate protocols.
 *
 * @param name The device name (advertised BLE name)
 * @param address The MAC address of the device
 * @param manufacturerId The manufacturer identifier from BLE advertising data
 * @param manufacturerData The manufacturer-specific data from BLE advertising, null if not available
 * @param rssi The received signal strength indicator (dBm)
 * @param timestamp The discovery timestamp in milliseconds, or `0` when unavailable
 */
data class EUCDevice(
    val name: String,
    val address: String,
    val manufacturerId: Int,
    val manufacturerData: ByteArray?=null,
    val rssi: Int,
    val timestamp: Long = 0L
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
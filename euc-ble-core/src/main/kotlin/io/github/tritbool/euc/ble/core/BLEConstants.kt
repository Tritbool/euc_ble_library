package io.github.tritbool.euc.ble.core

import java.util.UUID

object BLEConstants {
    // BLE Service UUIDs
    const val KINGSONG_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val GOTWAY_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val INMOTION_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val INMOTION_V2_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val NINEBOT_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val NINEBOT_Z_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val VETERAN_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val LEAPERKIM_SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"

    // BLE Characteristic UUIDs
    const val KINGSONG_READ_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val GOTWAY_READ_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val INMOTION_READ_CHARACTERISTIC = "0000ffe4-0000-1000-8000-00805f9b34fb"
    const val INMOTION_WRITE_CHARACTERISTIC = "0000ffe9-0000-1000-8000-00805f9b34fb"
    const val NINEBOT_READ_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val NINEBOT_WRITE_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val NINEBOT_Z_READ_CHARACTERISTIC = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    const val NINEBOT_Z_WRITE_CHARACTERISTIC = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val VETERAN_READ_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val VETERAN_WRITE_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val LEAPERKIM_READ_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val LEAPERKIM_WRITE_CHARACTERISTIC = "0000ffe1-0000-1000-8000-00805f9b34fb"

    const val INMOTION_V2_READ_CHARACTERISTIC = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

    const val INMOTION_V2_WRITE_CHARACTERISTIC = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

    // BLE Descriptor UUIDs
    const val CCCD_DESCRIPTOR = "00002902-0000-1000-8000-00805f9b34fb"

    // Connection states
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    // Scan timeout
    const val DEFAULT_SCAN_TIMEOUT_MS = 10000L
    const val DEFAULT_CONNECTION_TIMEOUT_MS = 5000L
    const val DEFAULT_MTU_SIZE = 23
    const val MAX_MTU_SIZE = 517

    // Manufacturer identifiers
    const val MANUFACTURER_KINGSONG = 0x004B
    const val MANUFACTURER_EXTREMEBULL = 0x0045
    const val MANUFACTURER_GOTWAY = 0x0047
    const val MANUFACTURER_INMOTION = 0x0049
    const val MANUFACTURER_NINEBOT = 0x004E
    const val MANUFACTURER_NOSFET = 0x004F
    const val MANUFACTURER_VETERAN = 0x0056

    // Leaperkim (Veteran branding) uses the same manufacturer identifier in WheelLog captures.
    const val MANUFACTURER_LEAPERKIM = MANUFACTURER_VETERAN

    // Helper functions
    fun String.toUUID(): UUID = UUID.fromString(this)

    // Frame header magic bytes for each protocol — used by looksLikeMyFrames() implementations
    // and available as a canonical reference to avoid scattering magic bytes across protocol files.
    val KINGSONG_FRAME_HEADER_1: ByteArray = byteArrayOf(0xAA.toByte(), 0x55.toByte())

    // Note: KINGSONG_FRAME_HEADER_2 and GOTWAY_FRAME_HEADER share the same byte sequence (0x55 0xAA).
    // Header bytes alone cannot disambiguate Kingsong from Gotway; canHandle() (device name /
    // manufacturer ID) and the presence of the Gotway 5A5A5A5A footer remain the primary gates.
    val KINGSONG_FRAME_HEADER_2: ByteArray = byteArrayOf(0x55.toByte(), 0xAA.toByte())
    val GOTWAY_FRAME_HEADER: ByteArray = byteArrayOf(0x55.toByte(), 0xAA.toByte())
    val GOTWAY_FRAME_FOOTER: ByteArray =
        byteArrayOf(0x5A.toByte(), 0x5A.toByte(), 0x5A.toByte(), 0x5A.toByte())
    val INMOTION_FRAME_HEADER: ByteArray = byteArrayOf(0xAA.toByte(), 0xAA.toByte())
    const val NINEBOT_FRAME_FIRST_BYTE: Int = 0x55
    val NINEBOT_WHEELLOG_FRAME_HEADER: ByteArray = byteArrayOf(0x5A.toByte(), 0xA5.toByte())
    val LEAPERKIM_FRAME_HEADER: ByteArray = byteArrayOf(0xDC.toByte(), 0x5A.toByte(), 0x5C.toByte())
}

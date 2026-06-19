package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NinebotZProtocolTest {

    private lateinit var protocol: NinebotZProtocol

    @BeforeEach
    fun setUp() {
        protocol = NinebotZProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }

    @Test
    fun canHandleNinebotZIdentifiersAndNames() {
        val byMetadata = EUCDevice(
            name = "Ninebot Z10",
            address = "A",
            manufacturerId = BLEConstants.MANUFACTURER_NINEBOT,
            rssi = -45
        )
        val bySegwayName = EUCDevice(
            name = "Segway Z8",
            address = "B",
            manufacturerId = BLEConstants.MANUFACTURER_NINEBOT,
            rssi = -70
        )
        val other = EUCDevice(
            name = "KS-16X",
            address = "D",
            manufacturerId = BLEConstants.MANUFACTURER_KINGSONG,
            rssi = -55
        )

        assertTrue(protocol.canHandle(byMetadata))
        assertTrue(protocol.canHandle(bySegwayName))
        assertEquals(false, protocol.canHandle(other))
    }

    @Test
    fun decodeWheelLogStyleFrameProducesTelemetry() {
        val frame = byteArrayOf(
            0x55.toByte(),
            0x18.toByte(),
            0x01.toByte(),
            0x01.toByte(),
            0xD0.toByte(), 0x1A.toByte(), // 68.64V
            0xFE.toByte(), 0x00.toByte(), // 2.54 km/h
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // 1000.0 km
            0x2C.toByte(), 0x01.toByte(), // 3.0A
            0xA6.toByte(), 0x09.toByte(), // 24.7C
            0x48.toByte(), // 72%
            0x01.toByte(), // charging
            0xC8.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // 200s ride time
            0x00.toByte() // checksum placeholder
        )

        val decoded = protocol.decode(frame)
        assertNotNull(decoded)
        assertEquals("Ninebot", decoded?.manufacturer)
        assertEquals(68.64, decoded?.voltage ?: 0.0, 0.01)
        assertEquals(2.54, decoded?.speed ?: 0.0, 0.01)
        assertEquals(72, decoded?.batteryLevel)
        assertEquals(true, decoded?.isCharging)
        assertEquals(200L, decoded?.rideTime)
    }

    @Test
    fun createCommandSupportsCommonControlCommands() {
        val lightOn = protocol.createCommand(CommandType.LIGHT_ON, Unit)
        val lock = protocol.createCommand(CommandType.LOCK, Unit)
        val requestFirmware = protocol.createCommand(CommandType.REQUEST_FIRMWARE, Unit)

        assertTrue(lightOn.isNotEmpty())
        assertTrue(lock.isNotEmpty())
        assertTrue(requestFirmware.isNotEmpty())
    }

    @Test
    fun decodeWheelLogB0UsesLegacyOffsetsAndScale() {
        val payload = ByteArray(32)
        payload[8] = 95.toByte()
        writeSignedShortLE(payload, 10, -123) // 12.3 km/h
        writeIntLE(payload, 14, 123_456) // 123.456 km
        writeShortLE(payload, 22, 235) // 23.5 C
        writeShortLE(payload, 24, 5_600) // 56.0 V
        writeSignedShortLE(payload, 26, -250) // -2.5 A

        val frame = wheelLogFrame(0xB0, payload)
        val decoded = protocol.decode(frame)

        assertNotNull(decoded)
        assertEquals(12.3, decoded?.speed ?: 0.0, 0.001)
        assertEquals(123.456, decoded?.distance ?: 0.0, 0.001)
        assertEquals(56.0, decoded?.voltage ?: 0.0, 0.001)
        assertEquals(-2.5, decoded?.current ?: 0.0, 0.001)
        assertEquals(23.5, decoded?.temperature ?: 0.0, 0.001)
        assertEquals(95, decoded?.batteryLevel)
        assertEquals(123.456, decoded?.totalDistance ?: 0.0, 0.001)
    }

    @Test
    fun decodeWheelLogTelemetryCarriesSerialAndFirmware() {
        protocol.decode(wheelLogFrame(0x10, "N3OTL2047C003".toByteArray()))
        protocol.decode(wheelLogFrame(0x1A, byteArrayOf(0x21, 0x30)))

        val payload = ByteArray(32)
        payload[8] = 90.toByte()
        writeSignedShortLE(payload, 10, 50)
        writeIntLE(payload, 14, 10_000)
        writeShortLE(payload, 22, 200)
        writeShortLE(payload, 24, 5_500)
        writeSignedShortLE(payload, 26, 100)

        val decoded = protocol.decode(wheelLogFrame(0xB0, payload))
        assertNotNull(decoded)
        assertEquals("N3OTL2047C003", decoded?.serialNumber)
        assertEquals("3.2.1", decoded?.firmwareVersion)
    }

    private fun wheelLogFrame(parameter: Int, payload: ByteArray): ByteArray {
        return byteArrayOf(
            0x5A,
            0xA5.toByte(),
            payload.size.toByte(),
            0x14,
            0x3E,
            0x04,
            parameter.toByte()
        ) + payload + byteArrayOf(0x00, 0x00)
    }

    private fun writeShortLE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeSignedShortLE(target: ByteArray, offset: Int, value: Int) {
        writeShortLE(target, offset, value and 0xFFFF)
    }

    private fun writeIntLE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}

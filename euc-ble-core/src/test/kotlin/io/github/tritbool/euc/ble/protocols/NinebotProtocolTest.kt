package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
class NinebotProtocolTest {

    private lateinit var protocol: NinebotProtocol
    @BeforeEach
    fun setUp() {
        protocol = NinebotProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
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

    @Test
    fun decodeNinebotZSettingsFramesCarryForwardIntoTelemetryAndSnapshot() {
        protocol.decode(wheelLogFrame(0x68, "Z-1.0".toByteArray()))
        protocol.decode(wheelLogFrame(0x70, byteArrayOf(0x01, 0x00)))
        protocol.decode(wheelLogFrame(0x72, byteArrayOf(0x01, 0x00)))
        protocol.decode(wheelLogFrame(0x74, byteArrayOf(0x98.toByte(), 0x08))) // 22.00 km/h
        protocol.decode(wheelLogFrame(0x7C, byteArrayOf(0x05, 0x00)))
        protocol.decode(wheelLogFrame(0x7D, byteArrayOf(0x66, 0x08))) // 21.50 km/h
        protocol.decode(wheelLogFrame(0x7E, byteArrayOf(0xFC.toByte(), 0x08))) // 23.00 km/h
        protocol.decode(wheelLogFrame(0x7F, byteArrayOf(0x92.toByte(), 0x09))) // 24.50 km/h
        protocol.decode(wheelLogFrame(0xC6, byteArrayOf(0x03)))
        protocol.decode(wheelLogFrame(0xD2, byteArrayOf(0x2A, 0x00)))
        protocol.decode(wheelLogFrame(0xD3, byteArrayOf(0x05, 0x00)))
        protocol.decode(wheelLogFrame(0xF5, byteArrayOf(0x18, 0x00)))

        val payload = ByteArray(32)
        payload[8] = 88.toByte()
        writeSignedShortLE(payload, 10, 80)
        writeIntLE(payload, 14, 42_000)
        writeShortLE(payload, 22, 215)
        writeShortLE(payload, 24, 5_620)
        writeSignedShortLE(payload, 26, 150)

        val decoded = protocol.decode(wheelLogFrame(0xB0, payload))
        assertNotNull(decoded)
        assertEquals(22.0, decoded?.speedLimit ?: 0.0, 0.001)
        assertEquals(5, decoded?.alertFlags)
        assertEquals(22, decoded?.alarm1Speed)
        assertEquals(23, decoded?.alarm2Speed)
        assertEquals(25, decoded?.alarm3Speed)
        assertEquals(3, decoded?.ledMode)
        assertEquals(1, decoded?.lightMode)
        assertEquals(42, decoded?.pedalsMode)

        val snapshot = protocol.getZSettingsSnapshot()
        assertEquals("Z-1.0", snapshot.bleVersion)
        assertEquals(1, snapshot.lockState)
        assertEquals(true, snapshot.limitedModeEnabled)
        assertEquals(22.0, snapshot.speedLimitKmh ?: 0.0, 0.001)
        assertEquals(5, snapshot.alarmsArmedMask)
        assertEquals(22, snapshot.alarm1SpeedKmh)
        assertEquals(23, snapshot.alarm2SpeedKmh)
        assertEquals(25, snapshot.alarm3SpeedKmh)
        assertEquals(3, snapshot.ledMode)
        assertEquals(5, snapshot.driveFlags)
        assertEquals(true, snapshot.drlEnabled)
        assertEquals(true, snapshot.headlightEnabled)
        assertEquals(3, snapshot.speakerVolumeStep)
    }

    @Test
    fun decodeNinebotZAuthAndBmsFramesExposeSnapshotsAndBmsData() {
        protocol.decode(wheelLogFrame(0x1D, "AUTH-KEY-123".toByteArray()))
        protocol.decode(
            wheelLogFrame(
                0x24,
                byteArrayOf(
                    0xE8.toByte(),
                    0x15,
                    0x83.toByte(),
                    0xFF.toByte(),
                    0x68,
                    0x10,
                    0x63,
                    0x10,
                    0x5C,
                    0x10,
                    0x53,
                    0x10
                )
            )
        )
        protocol.decode(
            wheelLogFrame(
                0x25,
                byteArrayOf(
                    0xD6.toByte(),
                    0x15,
                    0x50,
                    0x00,
                    0x62,
                    0x10,
                    0x61,
                    0x10,
                    0x5E,
                    0x10,
                    0x58,
                    0x10
                )
            )
        )

        val snapshot = protocol.getZSettingsSnapshot()
        assertEquals("AUTH-KEY-123", snapshot.authKeyAscii)
        assertEquals("415554482D4B45592D313233", snapshot.authKeyHex)

        val bmsSnapshots = protocol.getZBmsSnapshots()
        assertEquals(2, bmsSnapshots.size)
        assertEquals(56.08, bmsSnapshots[0].voltage ?: 0.0, 0.001)
        assertEquals(-1.25, bmsSnapshots[0].current ?: 0.0, 0.001)
        assertEquals(4.2, bmsSnapshots[0].cellVoltages?.first() ?: 0.0, 0.001)
        assertFalse(bmsSnapshots[0].rawPayloadHex.isBlank())

        val bmsData = protocol.getBMSData()
        assertNotNull(bmsData)
        assertEquals(2, bmsData?.size)
        assertEquals(1, bmsData?.get(0)?.bmsIndex)
        assertEquals(2, bmsData?.get(1)?.bmsIndex)
        assertEquals(4, bmsData?.get(1)?.cellVoltages?.size)
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

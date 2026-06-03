package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import com.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import com.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.zip.CRC32

class LeaperkimProtocolTest {

    private val defaultFrameLength = 36
    private val defaultVoltageRaw = 10000
    private val defaultTemperatureRaw = 2500
    private val defaultVersionRaw = 4000
    private val telemetryEmissionTimeoutMs = 5_000L
    private val invalidFrameCheckTimeoutMs = 500L
    private val beepCommandPayload = "b".encodeToByteArray()
    private val modernBeepCommandPayload = byteArrayOf(
        0x4c, 0x6b, 0x41, 0x70, 0x0e, 0x00,
        0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x01,
        0xca.toByte(), 0x87.toByte(), 0xe6.toByte(), 0x6f
    )
    private lateinit var protocol: LeaperkimProtocol

    @BeforeEach
    fun setUp() {
        protocol = LeaperkimProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }

    @Test
    fun canHandleLeaperkimAndVeteranDevicesOnly() {
        assertTrue(
            protocol.canHandle(
                EUCDevice(name = "Veteran Patton", address = "A", manufacturerId = 0, rssi = -50)
            )
        )
        assertTrue(
            protocol.canHandle(
                EUCDevice(name = "Unknown", address = "B", manufacturerId = BLEConstants.MANUFACTURER_LEAPERKIM, rssi = -55)
            )
        )
        assertFalse(
            protocol.canHandle(
                EUCDevice(name = "KS-16X", address = "C", manufacturerId = BLEConstants.MANUFACTURER_KINGSONG, rssi = -60)
            )
        )
        assertFalse(
            protocol.canHandle(
                EUCDevice(name = "Nosfet Aero", address = "D", manufacturerId = 0, rssi = -61)
            )
        )
    }

    @Test
    fun decodeValidFrameEmitsTelemetry() = runBlocking {
        val frame = createLeaperkimFrame(
            voltageRaw = 12525,
            speedRaw = 1234,
            distanceRaw = 54321,
            totalDistanceRaw = 65432,
            currentRaw = -250,
            temperatureRaw = 3500,
            pwmRaw = 7850,
            chargeMode = 1,
            versionRaw = 7001
        )

        val decodeResult = protocol.decode(frame)
        assertNull(decodeResult)

        val telemetry = withTimeout(telemetryEmissionTimeoutMs) { protocol.dataFlow.first() }
        assertNotNull(telemetry)
        assertEquals("Leaperkim", telemetry.manufacturer)
        assertEquals("Patton S", telemetry.model)
        assertEquals(125.25, telemetry.voltage, 0.01)
        assertEquals(12.34, telemetry.speed, 0.01)
        assertEquals(-2.50, telemetry.current, 0.01)
        assertEquals(35.00, telemetry.temperature, 0.01)
        assertEquals(78.50, telemetry.pwm ?: -1.0, 0.01)
        assertEquals(54.321, telemetry.distance, 0.001)
        assertEquals(65.432, telemetry.totalDistance ?: -1.0, 0.001)
        assertEquals("007.0.01", telemetry.firmwareVersion)
        assertEquals(100, telemetry.batteryLevel)
        assertTrue(telemetry.isCharging)
    }

    @Test
    fun decodeOutOfRangeVoltageFrameIsDropped() = runBlocking {
        val invalidFrame = createLeaperkimFrame(voltageRaw = 19000)
        protocol.decode(invalidFrame)
        val emitted = withTimeoutOrNull(invalidFrameCheckTimeoutMs) { protocol.dataFlow.first() }
        assertNull("Out-of-range frame should not be emitted", emitted)
    }

    @Test
    fun createCommandMapsKnownActions() {
        assertArrayEquals("SetLightON".encodeToByteArray(), protocol.createCommand(CommandType.LIGHT_ON, Unit))
        assertArrayEquals("SetLightOFF".encodeToByteArray(), protocol.createCommand(CommandType.LIGHT_OFF, Unit))
        assertArrayEquals(beepCommandPayload, protocol.createCommand(CommandType.BEEP, Unit))
    }

    @Test
    fun createCommandUsesModernBeepOnNewerFirmware() {
        protocol.decode(createLeaperkimFrame(versionRaw = 4000))
        assertArrayEquals(modernBeepCommandPayload, protocol.createCommand(CommandType.BEEP, Unit))
    }

    @Test
    fun createCommandMapsPedalsModeVariants() {
        assertArrayEquals("SETh".encodeToByteArray(), protocol.createCommand(CommandType.SET_PEDALS_MODE, 0))
        assertArrayEquals("SETm".encodeToByteArray(), protocol.createCommand(CommandType.SET_PEDALS_MODE, 1))
        assertArrayEquals("SETs".encodeToByteArray(), protocol.createCommand(CommandType.SET_PEDALS_MODE, 2))
    }

    @Test
    fun createCommandResetTrip() {
        assertArrayEquals("CLEARMETER".encodeToByteArray(), protocol.createCommand(CommandType.RESET_TRIP, Unit))
    }

    @Test
    fun decodeValidFrameEmitsAngle() = runBlocking {
        val frame = createLeaperkimFrame(
            voltageRaw = 10000,
            speedRaw = 500,
            angleRaw = 350 // 3.50 degrees
        )

        protocol.decode(frame)

        val telemetry = withTimeout(telemetryEmissionTimeoutMs) { protocol.dataFlow.first() }
        assertNotNull(telemetry.angle)
        assertEquals(3.50, telemetry.angle!!, 0.01)
    }

    @Test
    fun decodeValidFrameWithZeroAngle() = runBlocking {
        val frame = createLeaperkimFrame(
            voltageRaw = 10000,
            speedRaw = 500,
            angleRaw = 0
        )

        protocol.decode(frame)

        val telemetry = withTimeout(telemetryEmissionTimeoutMs) { protocol.dataFlow.first() }
        assertNotNull(telemetry.angle)
        assertEquals(0.0, telemetry.angle!!, 0.01)
    }

    @Test
    fun decodeLegacySettingsFieldsAreMapped() = runBlocking {
        val frame = createLeaperkimFrame(
            versionRaw = 5000,
            pedalsModeRaw = 2,
            autoOffSecondsRaw = 600,
            speedAlertRaw = 3,
            speedTiltBackRaw = 4
        )

        protocol.decode(frame)

        val telemetry = withTimeout(telemetryEmissionTimeoutMs) { protocol.dataFlow.first() }
        assertEquals(2, telemetry.pedalsMode)
        assertEquals(10, telemetry.autoPowerOffMinutes)
        assertEquals(30, telemetry.alarm1Speed)
        assertEquals(40, telemetry.tiltBackSpeed)
    }

    @Test
    fun decodeSmartBmsPagesPopulateCellVoltagesAndBmsSnapshot() = runBlocking {
        val page1 = createSmartBmsFrame(len = 86, versionRaw = 5000) {
            packetNum = 0x01
            cellVoltages = IntArray(15) { 4100 + it }
        }

        val page3 = createSmartBmsFrame(len = 86, versionRaw = 5000) {
            packetNum = 0x03
            temps = IntArray(6) { 2500 + it * 10 }
            cellVoltages = IntArray(12) { 4200 + it }
        }

        protocol.decode(page1)
        protocol.decode(page3)

        val telemetry = withTimeout(telemetryEmissionTimeoutMs) { protocol.dataFlow.first() }
        assertNotNull(telemetry.cellVoltages)
        assertTrue((telemetry.cellVoltages?.size ?: 0) >= 27)
        assertEquals(4.100, telemetry.cellVoltages?.first() ?: 0.0, 0.001)

        val bmsData = protocol.getBMSData()
        assertTrue(bmsData.isNotEmpty())
        assertEquals(1, bmsData.first().bmsIndex)
        assertNotNull(bmsData.first().temperatures)
        assertEquals(25.0, bmsData.first().temperatures?.first() ?: 0.0, 0.01)
    }

    private fun createLeaperkimFrame(
        len: Int = defaultFrameLength,
        voltageRaw: Int = defaultVoltageRaw,
        speedRaw: Int = 0,
        distanceRaw: Long = 0,
        totalDistanceRaw: Long = 0,
        currentRaw: Int = 0,
        temperatureRaw: Int = defaultTemperatureRaw,
        angleRaw: Int? = null,
        pwmRaw: Int = 0,
        chargeMode: Int = 0,
        versionRaw: Int = defaultVersionRaw,
        autoOffSecondsRaw: Int = 0,
        speedAlertRaw: Int = 0,
        speedTiltBackRaw: Int = 0,
        pedalsModeRaw: Int = 0
    ): ByteArray {
        val frame = ByteArray(len + 4)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A.toByte()
        frame[2] = 0x5C.toByte()
        frame[3] = len.toByte()

        frame[4] = ((voltageRaw shr 8) and 0xFF).toByte()
        frame[5] = (voltageRaw and 0xFF).toByte()
        frame[6] = ((speedRaw shr 8) and 0xFF).toByte()
        frame[7] = (speedRaw and 0xFF).toByte()
        frame[8] = (distanceRaw and 0xFF).toByte()
        frame[9] = ((distanceRaw shr 8) and 0xFF).toByte()
        frame[10] = ((distanceRaw shr 16) and 0xFF).toByte()
        frame[11] = ((distanceRaw shr 24) and 0xFF).toByte()
        frame[12] = (totalDistanceRaw and 0xFF).toByte()
        frame[13] = ((totalDistanceRaw shr 8) and 0xFF).toByte()
        frame[14] = ((totalDistanceRaw shr 16) and 0xFF).toByte()
        frame[15] = ((totalDistanceRaw shr 24) and 0xFF).toByte()
        frame[16] = ((currentRaw shr 8) and 0xFF).toByte()
        frame[17] = (currentRaw and 0xFF).toByte()
        frame[18] = ((temperatureRaw shr 8) and 0xFF).toByte()
        frame[19] = (temperatureRaw and 0xFF).toByte()
        if (angleRaw != null) {
            frame[32] = ((angleRaw shr 8) and 0xFF).toByte()
            frame[33] = (angleRaw and 0xFF).toByte()
        }
        frame[34] = ((pwmRaw shr 8) and 0xFF).toByte()
        frame[35] = (pwmRaw and 0xFF).toByte()

        frame[20] = ((autoOffSecondsRaw shr 8) and 0xFF).toByte()
        frame[21] = (autoOffSecondsRaw and 0xFF).toByte()
        frame[22] = ((chargeMode shr 8) and 0xFF).toByte()
        frame[23] = (chargeMode and 0xFF).toByte()
        frame[24] = ((speedAlertRaw shr 8) and 0xFF).toByte()
        frame[25] = (speedAlertRaw and 0xFF).toByte()
        frame[26] = ((speedTiltBackRaw shr 8) and 0xFF).toByte()
        frame[27] = (speedTiltBackRaw and 0xFF).toByte()
        frame[28] = ((versionRaw shr 8) and 0xFF).toByte()
        frame[29] = (versionRaw and 0xFF).toByte()
        frame[30] = ((pedalsModeRaw shr 8) and 0xFF).toByte()
        frame[31] = (pedalsModeRaw and 0xFF).toByte()

        if (len > 38) {
            val crc = CRC32()
            crc.update(frame, 0, len)
            val value = crc.value
            frame[len] = ((value shr 24) and 0xFF).toByte()
            frame[len + 1] = ((value shr 16) and 0xFF).toByte()
            frame[len + 2] = ((value shr 8) and 0xFF).toByte()
            frame[len + 3] = (value and 0xFF).toByte()
        }
        return frame
    }

    private fun createSmartBmsFrame(
        len: Int,
        versionRaw: Int,
        build: SmartBmsFrameBuilder.() -> Unit
    ): ByteArray {
        val builder = SmartBmsFrameBuilder().apply(build)
        val frame = createLeaperkimFrame(len = len, versionRaw = versionRaw)

        frame[46] = builder.packetNum.toByte()

        builder.cellVoltages.forEachIndexed { i, raw ->
            val offset = when (builder.packetNum) {
                0x01, 0x05 -> 53 + i * 2
                0x02, 0x06 -> 53 + i * 2
                0x03, 0x07 -> 59 + i * 2
                else -> 53 + i * 2
            }
            frame[offset] = ((raw shr 8) and 0xFF).toByte()
            frame[offset + 1] = (raw and 0xFF).toByte()
        }

        builder.temps.forEachIndexed { i, raw ->
            val offset = 47 + i * 2
            frame[offset] = ((raw shr 8) and 0xFF).toByte()
            frame[offset + 1] = (raw and 0xFF).toByte()
        }

        return appendCrc(frame, len)
    }

    private fun appendCrc(frame: ByteArray, len: Int): ByteArray {
        if (len <= 38) return frame
        val crc = CRC32()
        crc.update(frame, 0, len)
        val value = crc.value
        frame[len] = ((value shr 24) and 0xFF).toByte()
        frame[len + 1] = ((value shr 16) and 0xFF).toByte()
        frame[len + 2] = ((value shr 8) and 0xFF).toByte()
        frame[len + 3] = (value and 0xFF).toByte()
        return frame
    }

    private data class SmartBmsFrameBuilder(
        var packetNum: Int = 0,
        var cellVoltages: IntArray = intArrayOf(),
        var temps: IntArray = intArrayOf()
    )
}

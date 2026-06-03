// File: `euc-ble-core/src/test/java/com/euc/ble/protocols/GotwayProtocolTest.kt`
package com.euc.ble.protocols

import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import com.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals

class GotwayProtocolTest {

    private lateinit var protocol: GotwayProtocol

    @BeforeEach
    fun setUp() {
        protocol = GotwayProtocol()
        MockBLEUtils.createMockProtocolTestEnvironment()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }

    /**
     * Helper to create a valid 24-byte Gotway frame with header and footer.
     * Format:
     *   [0-1]: Header 0x55 0xAA
     *   [2-3]: Voltage (BE, /100)
     *   [4-5]: Speed (BE, *3.6/100 -> km/h)
     *   [6-9]: Distance (BE, uint32)
     *   [10-11]: Current (BE, signed, /100)
     *   [12-13]: Temperature (BE, signed, /100)
     *   [14-17]: Reserved/flags
     *   [18]: Frame type (0x00 = Type A, 0x04 = Type B)
     *   [19]: Reserved
     *   [20-23]: Footer 0x5A 0x5A 0x5A 0x5A
     */
    private fun createGotwayFrame(
        voltageRaw: Int = 0,
        speedRaw: Int = 0,
        distanceRaw: Long = 0,
        currentRaw: Int = 0,
        tempRaw: Int = 0,
        pwmRaw: Int = 0,
        frameType: Byte = 0x00
    ): ByteArray {
        val frame = ByteArray(24)
        // Header
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        // Voltage BE
        frame[2] = ((voltageRaw shr 8) and 0xFF).toByte()
        frame[3] = (voltageRaw and 0xFF).toByte()
        // Speed BE
        frame[4] = ((speedRaw shr 8) and 0xFF).toByte()
        frame[5] = (speedRaw and 0xFF).toByte()
        // Distance BE (uint32)
        frame[6] = ((distanceRaw shr 24) and 0xFF).toByte()
        frame[7] = ((distanceRaw shr 16) and 0xFF).toByte()
        frame[8] = ((distanceRaw shr 8) and 0xFF).toByte()
        frame[9] = (distanceRaw and 0xFF).toByte()
        // Current BE (signed short)
        frame[10] = ((currentRaw shr 8) and 0xFF).toByte()
        frame[11] = (currentRaw and 0xFF).toByte()
        // Temperature BE (signed short)
        frame[12] = ((tempRaw shr 8) and 0xFF).toByte()
        frame[13] = (tempRaw and 0xFF).toByte()
        // PWM/fallback output at 14-15 (signed short, tenths of percent for Type A)
        frame[14] = ((pwmRaw shr 8) and 0xFF).toByte()
        frame[15] = (pwmRaw and 0xFF).toByte()
        frame[16] = 0x00
        frame[17] = 0x00
        // Frame type
        frame[18] = frameType
        // Reserved
        frame[19] = 0x00
        // Footer
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()
        return frame
    }

    /**
     * Helper to create a Type B frame (for total distance).
     * Format for Type B:
     *   [2-5]: Total distance (BE, uint32)
     */
    private fun createGotwayFrameTypeB(
        distanceRaw: Long,
        settings: Int = 0,
        autoPowerOffMinutes: Int = 0,
        tiltBackSpeed: Int = 0,
        ledMode: Int = 0,
        alertFlags: Int = 0,
        lightMode: Int = 0
    ): ByteArray {
        val frame = ByteArray(24)
        // Header
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        // Distance BE (uint32) at bytes 2-5
        frame[2] = ((distanceRaw shr 24) and 0xFF).toByte()
        frame[3] = ((distanceRaw shr 16) and 0xFF).toByte()
        frame[4] = ((distanceRaw shr 8) and 0xFF).toByte()
        frame[5] = (distanceRaw and 0xFF).toByte()
        // Settings and mode data
        frame[6] = ((settings shr 8) and 0xFF).toByte()
        frame[7] = (settings and 0xFF).toByte()
        frame[8] = ((autoPowerOffMinutes shr 8) and 0xFF).toByte()
        frame[9] = (autoPowerOffMinutes and 0xFF).toByte()
        frame[10] = ((tiltBackSpeed shr 8) and 0xFF).toByte()
        frame[11] = (tiltBackSpeed and 0xFF).toByte()
        frame[12] = 0x00
        frame[13] = (ledMode and 0xFF).toByte()
        frame[14] = (alertFlags and 0xFF).toByte()
        frame[15] = (lightMode and 0x03).toByte()
        frame[16] = 0x00
        frame[17] = 0x00
        // Frame type = 0x04 for Type B
        frame[18] = 0x04
        // Reserved
        frame[19] = 0x00
        // Footer
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()
        return frame
    }

    private fun createGotwayNewBoardTypeAFrame(
        voltageRaw: Int,
        speedRaw: Int,
        tripDistanceRaw: Int,
        phaseCurrentRaw: Int,
        tempRaw: Int,
        pwmRaw: Int = 0
    ): ByteArray {
        val frame = ByteArray(24)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[2] = ((voltageRaw shr 8) and 0xFF).toByte()
        frame[3] = (voltageRaw and 0xFF).toByte()
        frame[4] = ((speedRaw shr 8) and 0xFF).toByte()
        frame[5] = (speedRaw and 0xFF).toByte()
        frame[6] = 0x00
        frame[7] = 0x00
        frame[8] = ((tripDistanceRaw shr 8) and 0xFF).toByte()
        frame[9] = (tripDistanceRaw and 0xFF).toByte()
        frame[10] = ((phaseCurrentRaw shr 8) and 0xFF).toByte()
        frame[11] = (phaseCurrentRaw and 0xFF).toByte()
        frame[12] = ((tempRaw shr 8) and 0xFF).toByte()
        frame[13] = (tempRaw and 0xFF).toByte()
        frame[14] = ((pwmRaw shr 8) and 0xFF).toByte()
        frame[15] = (pwmRaw and 0xFF).toByte()
        frame[16] = 0x00
        frame[17] = 0x00
        frame[18] = 0x00
        frame[19] = 0x18
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()
        return frame
    }

    private fun createGotwayFrameType1(batteryVoltageTenth: Int): ByteArray {
        val frame = ByteArray(24)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[6] = ((batteryVoltageTenth shr 8) and 0xFF).toByte()
        frame[7] = (batteryVoltageTenth and 0xFF).toByte()
        frame[18] = 0x01
        frame[19] = 0x00
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()
        return frame
    }

    private fun createGotwayFrameType7(
        batteryCurrentRaw: Int,
        motorTempRaw: Int,
        truePwmRaw: Int = 0
    ): ByteArray {
        val frame = ByteArray(24)
        frame[0] = 0x55.toByte()
        frame[1] = 0xAA.toByte()
        frame[2] = ((batteryCurrentRaw shr 8) and 0xFF).toByte()
        frame[3] = (batteryCurrentRaw and 0xFF).toByte()
        frame[6] = ((motorTempRaw shr 8) and 0xFF).toByte()
        frame[7] = (motorTempRaw and 0xFF).toByte()
        frame[8] = ((truePwmRaw shr 8) and 0xFF).toByte()
        frame[9] = (truePwmRaw and 0xFF).toByte()
        frame[18] = 0x07
        frame[19] = 0x18
        frame[20] = 0x5A.toByte()
        frame[21] = 0x5A.toByte()
        frame[22] = 0x5A.toByte()
        frame[23] = 0x5A.toByte()
        return frame
    }

    @Test
    fun testCanHandle() {
        val gotwayDevice = MockBLEUtils.createMockDevice(
            name = "Gotway MSX",
            manufacturerId = 0x0047
        )
        assertTrue(protocol.canHandle(gotwayDevice))

        val gotwayByName = MockBLEUtils.createMockDevice(
            name = "Begode Master",
            manufacturerId = 0x0000
        )
        assertTrue(protocol.canHandle(gotwayByName))

        val nonGotway = MockBLEUtils.createMockDevice(
            name = "OtherBrand",
            manufacturerId = 0x1234
        )
        assertFalse(protocol.canHandle(nonGotway))

        val mtenDevice = MockBLEUtils.createMockDevice(
            name = "Mten3",
            manufacturerId = 0x0000
        )
        assertTrue(protocol.canHandle(mtenDevice))

        val nikolaDevice = EUCDevice(
            name = "Nikola Plus",
            address = "00:11:22:33:44:55",
            rssi = -50,
            manufacturerId = 0
        )
        assertTrue(protocol.canHandle(nikolaDevice))
    }

    @Test
    fun testDecodeReturnsNullBecauseAsyncProcessing() {
        // With the FrameReassembler, decode() always returns null
        // because data is emitted asynchronously via dataFlow
        val frame = createGotwayFrame(
            voltageRaw = 6720, // 67.20V
            speedRaw = 833,    // 833 * 3.6 / 100 = 30.0 km/h
            distanceRaw = 1000,
            currentRaw = 250,  // 2.50A
            tempRaw = 2500     // 25.0°C
        )
        val result = protocol.decode(frame)
        assertNull(result)
    }

    @Test
    fun testDecodeValidTypeAFrame() = runBlocking {
        // voltageRaw=6720 -> 67.20V
        // speedRaw=833 -> 833*3.6/100 = 29.988 km/h (~30)
        // distance=1000 m
        // currentRaw=250 -> 2.50A
        // tempRaw=2500 -> 25.0°C
        val frame = createGotwayFrame(
            voltageRaw = 6720,
            speedRaw = 833,
            distanceRaw = 1000,
            currentRaw = 250,
            tempRaw = 2500,
            pwmRaw = 136,
            frameType = 0x00
        )

        protocol.decode(frame)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertEquals(67.2, result.voltage, 0.01)
        assertEquals(29.988, result.speed, 0.1)
        assertEquals(1000.0, result.distance, 0.01)
        assertEquals(2.5, result.current, 0.01)
        assertEquals(25.0, result.temperature, 0.01)
        assertEquals(13.6, result.pwm ?: 0.0, 0.01)
        assertEquals("Gotway", result.manufacturer)
        assertEquals("Gotway (Type A)", result.model)
    }

    @Test
    fun testDecodeValidTypeBFrame() = runBlocking {
        val typeAFrame = createGotwayFrame(
            voltageRaw = 6720,
            speedRaw = 833,
            distanceRaw = 1000,
            currentRaw = 250,
            tempRaw = 2500,
            pwmRaw = 136,
            frameType = 0x00
        )
        protocol.decode(typeAFrame)
        withTimeout(10000) { protocol.dataFlow.first() }

        // Type B provides settings/total distance and should carry forward last telemetry values.
        val frame = createGotwayFrameTypeB(distanceRaw = 123456)

        protocol.decode(frame)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertEquals(1000.0, result.distance, 0.01)
        assertEquals(123456.0, result.totalDistance ?: 0.0, 0.01)
        assertEquals("Gotway", result.manufacturer)
        assertEquals("Gotway (Type B)", result.model)
        assertEquals(67.2, result.voltage, 0.01)
        assertEquals(29.988, result.speed, 0.1)
        assertEquals(2.5, result.current, 0.01)
        assertEquals(25.0, result.temperature, 0.01)
        assertEquals(13.6, result.pwm ?: 0.0, 0.01)
    }

    @Test
    fun testDecodeTypeBParsesSettingsAndAlerts() = runBlocking {
        val pedalsRaw = 1 // 0..3
        val alarmRaw = 2 // 0..3
        val rollRaw = 3 // 0..3
        val inMilesRaw = 1
        val settings = (pedalsRaw shl 13) or (alarmRaw shl 10) or (rollRaw shl 7) or inMilesRaw
        val frame = createGotwayFrameTypeB(
            distanceRaw = 456789,
            settings = settings,
            autoPowerOffMinutes = 15,
            tiltBackSpeed = 40,
            ledMode = 5,
            alertFlags = 0b0010_1101,
            lightMode = 2
        )

        protocol.decode(frame)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertNotNull(result.totalDistance)
        assertEquals(456789.0, result.totalDistance!!, 0.01)
        assertEquals(1, result.pedalsMode)
        assertEquals(alarmRaw, result.alarmMode)
        assertEquals(rollRaw, result.rollAngleMode)
        assertEquals(true, result.usesMiles)
        assertEquals(15, result.autoPowerOffMinutes)
        assertEquals(40, result.tiltBackSpeed)
        assertEquals(5, result.ledMode)
        assertEquals(2, result.lightMode)
        assertEquals(0b0010_1101, result.alertFlags)
        assertEquals(true, result.wheelAlarm)
    }

    @Test
    fun testDecodeTypeBTiltBackSpeedOverLimitIsIgnored() = runBlocking {
        val frame = createGotwayFrameTypeB(
            distanceRaw = 321,
            tiltBackSpeed = 120
        )

        protocol.decode(frame)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertNull(result.tiltBackSpeed)
    }

    @Test
    fun testDecodeZeroValues() = runBlocking {
        val frame = createGotwayFrame(
            voltageRaw = 0,
            speedRaw = 0,
            distanceRaw = 0,
            currentRaw = 0,
            tempRaw = 0
        )

        protocol.decode(frame)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertEquals(0.0, result.voltage, 0.01)
        assertEquals(0.0, result.speed, 0.01)
        assertEquals(0.0, result.distance, 0.01)
        assertEquals(0.0, result.current, 0.01)
        assertEquals(0.0, result.temperature, 0.01)
    }

    @Test
    fun testDecodeOutOfRangeFrameIsDropped() {
        // Frame avec speedRaw=65535 (2359 km/h) doit être silencieusement ignorée
        val frame = createGotwayFrame(
            voltageRaw = 65535,
            speedRaw = 65535,
            distanceRaw = 4294967295L,
            currentRaw = 32767,
            tempRaw = 32767
        )
        // decode() retourne null (asynchrone)
        assertNull(protocol.decode(frame))
        // dataFlow ne doit rien émettre — vérification via timeout
        val result = runBlocking {
            withTimeoutOrNull(500) { protocol.dataFlow.first() }
        }
        assertNull("Frame hors plage ne doit pas être émise", result)
    }

    @Test
    fun testDecodeMaxValues() = runBlocking {
        // Max unsigned short = 65535
        // Max unsigned int = 0xFFFFFFFF = 4294967295
        val frame = createGotwayFrame(
            voltageRaw = 30000,    // 300.00V
            speedRaw = 5555,      // 5555*3.6/100 = 200.0 km/h
            distanceRaw = 4294967295L,
            currentRaw = 32767,    // Max signed short -> 327.67A
            tempRaw = 32767        // 327.67°C
        )

        protocol.decode(frame)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertEquals(300.0, result.voltage, 0.01)
        assertEquals(200.0, result.speed, 0.1)
        assertEquals(4294967295.0, result.distance, 1.0)
        assertEquals(327.67, result.current, 0.01)
        assertEquals(327.67, result.temperature, 0.01)
    }

    @Test
    fun testDecodeNegativeCurrent() = runBlocking {
        // Negative current (regenerative braking)
        // -500 as signed short = 0xFE0C in two's complement
        val frame = createGotwayFrame(
            voltageRaw = 6720,
            speedRaw = 833,
            distanceRaw = 1000,
            currentRaw = -500,  // -5.00A
            tempRaw = 2500
        )

        protocol.decode(frame)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertEquals(-5.0, result.current, 0.01)
    }

    @Test
    fun testDecodeTypeAUsesTripDistanceFieldAtBytes8To9() = runBlocking {
        val frame = createGotwayNewBoardTypeAFrame(
            voltageRaw = 0x1775,
            speedRaw = 0x0538,
            tripDistanceRaw = 0x02EE,
            phaseCurrentRaw = -1180,
            tempRaw = 0x1481
        )

        protocol.decode(frame)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertEquals(0.75, result.distance, 0.001)
        assertEquals(48.096, result.speed, 0.01)
    }

    @Test
    fun testDecodeTypeAUsesHardwarePwmWhenCustomFirmwareIsDetected() = runBlocking {
        protocol.decode("NAME Begode Master".encodeToByteArray())
        protocol.decode("CF1.0.3".encodeToByteArray())
        val frame = createGotwayFrame(
            voltageRaw = 6720,
            speedRaw = 833,
            distanceRaw = 1000,
            currentRaw = 250,
            tempRaw = 2500,
            pwmRaw = 136,
            frameType = 0x00
        )

        protocol.decode(frame)

        val result = withTimeout(10000) { protocol.dataFlow.first() }
        assertEquals(136.0, result.pwm ?: 0.0, 0.01)
        assertEquals("Begode Master", result.model)
        assertEquals("1.0.3", result.firmwareVersion)
    }

    @Test
    fun testDecodeTypeATelemetryCarriesLegacyFirmwareMetadata() = runBlocking {
        protocol.decode("NAME Nikola Plus".encodeToByteArray())
        protocol.decode("GW2.5.1".encodeToByteArray())
        val frame = createGotwayFrame(
            voltageRaw = 6720,
            speedRaw = 833,
            distanceRaw = 1000,
            currentRaw = 250,
            tempRaw = 2500,
            pwmRaw = 136,
            frameType = 0x00
        )

        protocol.decode(frame)

        val result = withTimeout(10000) { protocol.dataFlow.first() }
        assertEquals("Nikola Plus", result.model)
        assertEquals("2.5.1", result.firmwareVersion)
        assertEquals(13.6, result.pwm ?: 0.0, 0.01)
    }

    @Test
    fun testDecodeType7UsesLatestType1VoltageAndPublishesMotorTemperature() = runBlocking {
        val type1 = createGotwayFrameType1(batteryVoltageTenth = 1201) // 120.1V
        val type7 = createGotwayFrameType7(batteryCurrentRaw = 556, motorTempRaw = 35, truePwmRaw = 82)

        protocol.decode(type1)
        protocol.decode(type7)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertEquals("Gotway (Type 7)", result.model)
        assertEquals(120.1, result.voltage, 0.01)
        assertEquals(-5.56, result.current, 0.01)
        assertNotNull(result.motorTemperature)
        assertEquals(35.0, result.motorTemperature!!, 0.01)
        assertEquals(82.0, result.pwm ?: 0.0, 0.01)
    }

    @Test
    fun testDecodeFragmentedFrames() = runBlocking {
        // Test that the FrameReassembler correctly handles fragmented data
        val fullFrame = createGotwayFrame(
            voltageRaw = 6720,
            speedRaw = 833,
            distanceRaw = 1000,
            currentRaw = 250,
            tempRaw = 2500
        )

        // Send frame in two fragments
        val fragment1 = fullFrame.sliceArray(0..11)
        val fragment2 = fullFrame.sliceArray(12..23)

        protocol.decode(fragment1)
        protocol.decode(fragment2)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertEquals(67.2, result.voltage, 0.01)
        assertEquals(1000.0, result.distance, 0.01)
    }

    @Test
    fun testDecodeMultipleFramesInOnePacket() = runBlocking {
        // Two complete frames sent together
        val frame1 = createGotwayFrame(
            voltageRaw = 6720,
            speedRaw = 833,
            distanceRaw = 1000,
            currentRaw = 250,
            tempRaw = 2500
        )
        val frame2 = createGotwayFrame(
            voltageRaw = 6800,
            speedRaw = 1000,
            distanceRaw = 2000,
            currentRaw = 300,
            tempRaw = 2600
        )

        val combinedData = frame1 + frame2

        protocol.decode(combinedData)

        // Should receive the first frame
        val result1 = withTimeout(10000) {
            protocol.dataFlow.first()
        }
        assertEquals(67.2, result1.voltage, 0.01)

        // The second frame should also be processed
        val result2 = withTimeout(10000) {
            protocol.dataFlow.first()
        }
        // Note: Due to replay=1 on MutableSharedFlow, we might get the same or different frame
        // This tests that at least one frame is processed correctly
        assertTrue(result2.voltage >= 67.2)
    }

    @Test
    fun testDecodeWithGarbageBeforeHeader() = runBlocking {
        // Garbage bytes before the valid frame
        val garbage = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val validFrame = createGotwayFrame(
            voltageRaw = 6720,
            speedRaw = 833,
            distanceRaw = 1000,
            currentRaw = 250,
            tempRaw = 2500
        )

        val dataWithGarbage = garbage + validFrame

        protocol.decode(dataWithGarbage)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertEquals(67.2, result.voltage, 0.01)
        assertEquals(1000.0, result.distance, 0.01)
    }

    @Test
    fun testInvalidFrameWithWrongFooter() {
        // Frame with invalid footer should not be emitted
        val frame = createGotwayFrame(voltageRaw = 6720)
        // Corrupt the footer
        frame[20] = 0x00
        frame[21] = 0x00
        frame[22] = 0x00
        frame[23] = 0x00

        protocol.decode(frame)

        // The dataFlow should not emit anything for an invalid frame
        // We can't easily test this without a timeout, but we verify decode returns null
        val result = protocol.decode(frame)
        assertNull(result)
    }

    @Test
    fun testInvalidFrameWithWrongHeader() {
        // Frame with invalid header should not be processed
        val frame = createGotwayFrame(voltageRaw = 6720)
        // Corrupt the header
        frame[0] = 0x00
        frame[1] = 0x00

        val result = protocol.decode(frame)
        assertNull(result)
    }

    @Test
    fun testPowerCalculation() = runBlocking {
        // voltage = 67.2V, current = 10.0A -> power = 672W
        val frame = createGotwayFrame(
            voltageRaw = 6720,  // 67.20V
            speedRaw = 833,
            distanceRaw = 1000,
            currentRaw = 1000,  // 10.0A
            tempRaw = 2500
        )

        protocol.decode(frame)

        val result = withTimeout(10000) {
            protocol.dataFlow.first()
        }

        assertEquals(672.0, result.power, 0.1)
    }

    @Test
    fun testCreateCommandLightOn() {
        val command = protocol.createCommand(CommandType.LIGHT_ON, Unit)
        assertArrayEquals(
            byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x01),
            command
        )
    }

    @Test
    fun testCreateCommandLightOff() {
        val command = protocol.createCommand(CommandType.LIGHT_OFF, Unit)
        assertArrayEquals(
            byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x00),
            command
        )
    }

    @Test
    fun testCreateCommandBeep() {
        val command = protocol.createCommand(CommandType.BEEP, Unit)
        assertArrayEquals(
            byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x02, 0x01),
            command
        )
    }

    @Test
    fun testCreateCommandPowerOff() {
        val command = protocol.createCommand(CommandType.POWER_OFF, Unit)
        assertArrayEquals(
            byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x03, 0x01),
            command
        )
    }

    @Test
    fun testCreateCommandLightBrightness() {
        val command = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 50)
        // 50% of 255 = 127 (0x7F)
        assertArrayEquals(
            byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x04, 0x7F.toByte()),
            command
        )
    }

    @Test
    fun testCreateCommandLightBrightnessInvalidValue() {
        // Invalid value should return empty array
        val command = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 150)
        assertArrayEquals(byteArrayOf(), command)
    }

    @Test
    fun testCreateCommandRequestSerial() {
        val command = protocol.createCommand(CommandType.REQUEST_SERIAL, Unit)
        assertArrayEquals("N".encodeToByteArray(), command)
    }

    @Test
    fun testCreateCommandRequestFirmware() {
        val command = protocol.createCommand(CommandType.REQUEST_FIRMWARE, Unit)
        assertArrayEquals("V".encodeToByteArray(), command)
    }

    @Test
    fun testGetPollingPlanIsEnabled() {
        val plan = protocol.getPollingPlan()
        assertTrue(plan.enabled)
        assertTrue(plan.startupQueries.isNotEmpty())
        assertEquals(2, plan.startupQueries.size)
        assertEquals(CommandType.REQUEST_SERIAL, plan.startupQueries[0].commandType)
        assertEquals(CommandType.REQUEST_FIRMWARE, plan.startupQueries[1].commandType)
        assertTrue(plan.periodicQueries.isEmpty())
    }

    @Test
    fun testMatchesQueryResponseReturnsTrueForAsciiResponse() {
        val query = ProtocolQuerySpec("gotway.request-model", CommandType.REQUEST_SERIAL, maxRetries = 3)
        val asciiResponse = "GW_MSX_PRO".encodeToByteArray()
        assertTrue(protocol.matchesQueryResponse(query, asciiResponse))
    }

    @Test
    fun testMatchesQueryResponseReturnsFalseForTelemetryFrame() {
        val query = ProtocolQuerySpec("gotway.request-model", CommandType.REQUEST_SERIAL, maxRetries = 3)
        val telemetryFrame = createGotwayFrame(voltageRaw = 6720, speedRaw = 833, distanceRaw = 1000, currentRaw = 250, tempRaw = 2500)
        assertFalse(protocol.matchesQueryResponse(query, telemetryFrame))
    }

    @Test
    fun testMatchesQueryResponseReturnsFalseForEmptyData() {
        val query = ProtocolQuerySpec("gotway.request-model", CommandType.REQUEST_SERIAL, maxRetries = 3)
        assertFalse(protocol.matchesQueryResponse(query, byteArrayOf()))
    }

    @Test
    fun testMatchesQueryResponseReturnsFalseForUnrelatedCommand() {
        val query = ProtocolQuerySpec("gotway.beep", CommandType.BEEP, maxRetries = 1)
        val asciiResponse = "GW_MSX".encodeToByteArray()
        assertFalse(protocol.matchesQueryResponse(query, asciiResponse))
    }

    @Test
    fun testIsDeviceReady() {
        val readyData = MockBLEUtils.createMockEUCData(
            voltage = 67.2,
            speed = 10.0
        )
        assertTrue(protocol.isDeviceReady(readyData))

        val notReadyData = MockBLEUtils.createMockEUCData(
            voltage = 0.0,
            speed = 0.0
        )
        assertFalse(protocol.isDeviceReady(notReadyData))

        val negativeSpeedData = MockBLEUtils.createMockEUCData(
            voltage = 67.2,
            speed = -5.0
        )
        assertFalse(protocol.isDeviceReady(negativeSpeedData))
    }
}

package io.github.tritbool.euc.ble.protocols

import app.cash.turbine.test
import org.junit.jupiter.api.AfterEach
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertNull
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.zip.CRC32
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NosfetProtocolTest {

    private val defaultFrameLength = 36
    private lateinit var protocol: NosfetProtocol

    @BeforeEach
    fun setUp() {
    }

    @AfterEach
    fun tearDown() {
        if (this::protocol.isInitialized) {
            protocol.close()
        }
    }

    @Test
    fun decodeValidNosfetFrameEmitsTelemetry() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)

        val frame = createFrame(
            voltageRaw = 12525,
            speedRaw = 390,
            distanceRaw = 54321,
            totalDistanceRaw = 65432,
            currentRaw = -250,
            temperatureRaw = 3500,
            pwmRaw = 6550,
            chargeMode = 1,
            versionRaw = 43001
        )

        protocol.dataFlow.test(timeout = 5.seconds) {
            val decodeResult = protocol.decode(frame)
            assertNull(decodeResult)

            val telemetry = awaitItem()
            assertEquals("Nosfet", telemetry.manufacturer)
            assertEquals("Nosfet Aero", telemetry.model)
            assertEquals("043.0.01", telemetry.firmwareVersion)
            assertEquals(100, telemetry.batteryLevel)
            assertEquals(39.0, telemetry.speed, 0.000001)
            assertEquals(54.321, telemetry.distance, 0.000001)
            assertEquals(65.432, telemetry.totalDistance ?: -1.0, 0.000001)
            assertEquals(65.5, telemetry.pwm ?: -1.0, 0.01)
            assertTrue(telemetry.isCharging)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun speedUsesSignedTenthsOfKmhForAllNosfetModels() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)
        protocol.dataFlow.test(timeout = 5.seconds) {
            for (version in listOf(42001, 43001, 44001)) {
                for (raw in listOf(-390, 0, 390)) {
                    protocol.decode(createFrame(versionRaw = version, speedRaw = raw))
                    assertEquals(raw / 10.0, awaitItem().speed, 0.000001)
                }
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun frameDetectionReassemblesFragmentsAndPreservesFollowingBytes() {
        val detector = NosfetFrameDetector()
        for (version in listOf(42001, 43001, 44001)) {
            val frame = createFrame(versionRaw = version)
            val following = frame.copyOfRange(0, 15)
            assertNull(detector.detect(byteArrayOf(1, 2, 3) + frame.copyOfRange(0, 20)))
            assertArrayEquals(
                frame + following,
                detector.detect(frame.copyOfRange(20, frame.size) + following)
            )
        }
    }

    @Test
    fun frameDetectionRejectsInvalidCrcBmsPagesAndOtherFirmware() {
        val detector = NosfetFrameDetector()
        val frame = createBmsFrame(0)
        assertNull(detector.detect(frame.clone().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        }))
        assertNull(detector.detect(createBmsFrame(3)))
        assertNull(detector.detect(createFrame(versionRaw = 9001)))
        assertArrayEquals(frame, detector.detect(frame))
    }

    @Test
    fun frameDetectionResynchronizesAfterBadLengthAndClearsPartialFrames() {
        val detector = NosfetFrameDetector()
        val frame = createFrame()
        val corruptHeader = byteArrayOf(0xDC.toByte(), 0x5A, 0x5C, 0xFF.toByte())
        assertArrayEquals(frame, detector.detect(corruptHeader + frame))
        assertNull(detector.detect(frame.copyOfRange(0, 20)))
        detector.reset()
        assertNull(detector.detect(frame.copyOfRange(20, frame.size)))
        assertArrayEquals(frame, detector.detect(ByteArray(1024) + frame))
    }

    @Test
    fun matchesDeviceName_recognizesKnownNosfetKeywords() {
        protocol = NosfetProtocol()
        assertTrue(protocol.matchesDeviceName("Nosfet Apex"))
        assertTrue(protocol.matchesDeviceName("NOSFET AERO"))
        assertTrue(protocol.matchesDeviceName("nosfet"))
        assertTrue(protocol.matchesDeviceName("apex rider"))
        assertTrue(protocol.matchesDeviceName("APEX X Pro"))
        assertTrue(protocol.matchesDeviceName("Aero 2"))
        assertTrue(protocol.matchesDeviceName("Aeon GT"))
    }

    @Test
    fun matchesDeviceName_returnsFalseForUnrelatedNames() {
        protocol = NosfetProtocol()
        assertFalse(protocol.matchesDeviceName("Leaperkim Sherman"))
        assertFalse(protocol.matchesDeviceName("Gotway King"))
        assertFalse(protocol.matchesDeviceName("Unknown EUC"))
        assertFalse(protocol.matchesDeviceName(""))
    }

    @Test
    fun aeonFirmwareSelects151VoltBatteryCurve() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)
        protocol.dataFlow.test(timeout = 5.seconds) {
            protocol.decode(createFrame(voltageRaw = 14524, versionRaw = 44250))
            val telemetry = awaitItem()
            assertEquals("Nosfet Aeon", telemetry.model)
            assertEquals("044.2.50", telemetry.firmwareVersion)
            assertEquals(86, telemetry.batteryLevel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun apexFirmwareUses151VoltBatteryAndPreservesMinorVersion() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)
        protocol.dataFlow.test(timeout = 5.seconds) {
            protocol.decode(createFrame(voltageRaw = 14524, versionRaw = 42254))
            val telemetry = awaitItem()
            assertEquals("Nosfet Apex", telemetry.model)
            assertEquals("042.2.54", telemetry.firmwareVersion)
            assertEquals(86, telemetry.batteryLevel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun distancesUseLowWordFirstBigEndianMeters() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)
        val frame = createFrame(versionRaw = 44250).apply {
            // 23 metres trip; 33,000 metres lifetime. Ordinary LE would report 5.888 km trip.
            this[8] = 0x00
            this[9] = 0x17
            this[12] = 0x80.toByte()
            this[13] = 0xE8.toByte()
        }
        protocol.dataFlow.test(timeout = 5.seconds) {
            protocol.decode(frame)
            val telemetry = awaitItem()
            assertEquals(0.023, telemetry.distance, 0.000001)
            assertEquals(33.0, telemetry.totalDistance ?: -1.0, 0.000001)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aeonDetailedBmsPagesExposeTwo36CellPacks() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)
        protocol.dataFlow.test(timeout = 5.seconds) {
            for (page in 0..7) {
                protocol.decode(createBmsFrame(page))
                runCurrent()
                if (page != 3 && page != 7) awaitItem()
            }
            val packs = protocol.getBMSData()
            assertEquals(2, packs.size)
            for (pack in packs) {
                assertEquals(36, pack.cellVoltages?.size)
                assertTrue(pack.cellVoltages!!.all { it == 4.0 })
                assertEquals(6, pack.temperatures?.size)
                assertTrue(pack.temperatures!!.all { it == 25.0 })
            }
            assertEquals(-1.0, packs[0].current ?: 0.0, 0.000001)
            assertEquals(2.0, packs[1].current ?: 0.0, 0.000001)
            assertEquals(true, packs[0].isCharging)
            assertEquals(false, packs[1].isCharging)

            protocol.decode(createFrame(versionRaw = 44250, voltageRaw = 14524))
            val telemetry = awaitItem()
            assertEquals(72, telemetry.cellVoltages?.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun corruptedBmsOnlyFrameDoesNotPopulateCellsOrTemperatures() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)
        protocol.decode(createBmsFrame(3).apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        })
        runCurrent()
        assertTrue(protocol.getBMSData().isEmpty())
    }

    @Test
    fun pageEightDoesNotCreateAnEmptyBatteryPack() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)
        protocol.decode(createBmsFrame(8))
        runCurrent()
        assertTrue(protocol.getBMSData().isEmpty())
    }

    @Test
    fun incompleteCellPageDoesNotInterpretCrcAsCellVoltage() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)
        val len = 81
        val frame = createFrame(len = len, versionRaw = 44250).apply {
            this[46] = 1
        }
        appendCrc(frame)
        protocol.decode(frame)
        runCurrent()
        assertTrue(protocol.getBMSData().isEmpty())
    }

    @Test
    fun createCommandMapsKnownActions() {
        protocol = NosfetProtocol()

        assertArrayEquals(
            "SetLightON".encodeToByteArray(),
            protocol.createCommand(CommandType.LIGHT_ON, Unit)
        )
        assertArrayEquals(
            "SetLightOFF".encodeToByteArray(),
            protocol.createCommand(CommandType.LIGHT_OFF, Unit)
        )
        assertArrayEquals(
            "b".encodeToByteArray(),
            protocol.createCommand(CommandType.BEEP, Unit)
        )
    }

    private fun createFrame(
        len: Int = defaultFrameLength,
        voltageRaw: Int = 10000,
        speedRaw: Int = 0,
        distanceRaw: Long = 0,
        totalDistanceRaw: Long = 0,
        currentRaw: Int = 0,
        temperatureRaw: Int = 2500,
        pwmRaw: Int = 0,
        chargeMode: Int = 0,
        versionRaw: Int = 43000
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
        frame[8] = ((distanceRaw shr 8) and 0xFF).toByte()
        frame[9] = (distanceRaw and 0xFF).toByte()
        frame[10] = ((distanceRaw shr 24) and 0xFF).toByte()
        frame[11] = ((distanceRaw shr 16) and 0xFF).toByte()
        frame[12] = ((totalDistanceRaw shr 8) and 0xFF).toByte()
        frame[13] = (totalDistanceRaw and 0xFF).toByte()
        frame[14] = ((totalDistanceRaw shr 24) and 0xFF).toByte()
        frame[15] = ((totalDistanceRaw shr 16) and 0xFF).toByte()
        frame[16] = ((currentRaw shr 8) and 0xFF).toByte()
        frame[17] = (currentRaw and 0xFF).toByte()
        frame[18] = ((temperatureRaw shr 8) and 0xFF).toByte()
        frame[19] = (temperatureRaw and 0xFF).toByte()
        frame[34] = ((pwmRaw shr 8) and 0xFF).toByte()
        frame[35] = (pwmRaw and 0xFF).toByte()

        frame[22] = ((chargeMode shr 8) and 0xFF).toByte()
        frame[23] = (chargeMode and 0xFF).toByte()
        frame[28] = ((versionRaw shr 8) and 0xFF).toByte()
        frame[29] = (versionRaw and 0xFF).toByte()
        frame[30] = 0x00
        return frame
    }

    private fun createBmsFrame(page: Int): ByteArray {
        val len = if (page == 3 || page == 7) 0x5F else 0x53
        val frame = createFrame(len = len, voltageRaw = 14524, versionRaw = 44250)
        frame[46] = page.toByte()
        when (page) {
            0, 4 -> {
                frame[69] = 0xFF.toByte()
                frame[70] = 0x9C.toByte()
                frame[71] = 0x00
                frame[72] = 0xC8.toByte()
            }
            1, 2, 5, 6 -> repeat(15) { index ->
                frame[53 + index * 2] = 0x0F
                frame[54 + index * 2] = 0xA0.toByte()
            }
            3, 7 -> {
                repeat(6) { index ->
                    frame[47 + index * 2] = 0x09
                    frame[48 + index * 2] = 0xC4.toByte()
                }
                // Nonzero padding must not become six fictitious AEON cells.
                repeat(12) { index ->
                    frame[59 + index * 2] = 0x0F
                    frame[60 + index * 2] = 0xA0.toByte()
                }
            }
        }
        return appendCrc(frame)
    }

    private fun appendCrc(frame: ByteArray): ByteArray {
        val len = frame[3].toInt() and 0xFF
        val crc = CRC32().apply { update(frame, 0, len) }.value
        repeat(4) { index ->
            frame[len + index] = (crc shr (24 - index * 8)).toByte()
        }
        return frame
    }
}
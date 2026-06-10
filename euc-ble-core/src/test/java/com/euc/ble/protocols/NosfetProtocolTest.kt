package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.models.EUCDevice
import app.cash.turbine.test
import org.junit.jupiter.api.AfterEach
import com.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import com.euc.ble.test.JUnit4AssertionsCompat.assertNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class NosfetProtocolTest {

    private val defaultFrameLength = 36
    private lateinit var protocol: NosfetProtocol

    @BeforeEach
    fun setUp() {
    }

    @AfterEach
    fun tearDown() {
        if (::protocol.isInitialized) {
            protocol.close()
        }
    }

    @Test
    fun canHandleNosfetDevicesOnly() {
        protocol = NosfetProtocol()

        assertTrue(
            protocol.canHandle(
                EUCDevice(name = "Nosfet Aero", address = "A", manufacturerId = 0, rssi = -50)
            )
        )
        assertTrue(
            protocol.canHandle(
                EUCDevice(
                    name = "Apex",
                    address = "B",
                    manufacturerId = BLEConstants.MANUFACTURER_LEAPERKIM,
                    rssi = -55
                )
            )
        )
        assertFalse(
            protocol.canHandle(
                EUCDevice(name = "Veteran Patton", address = "C", manufacturerId = 0, rssi = -60)
            )
        )
    }

    @Test
    fun decodeValidNosfetFrameEmitsTelemetry() = runTest {
        protocol = NosfetProtocol(scope = backgroundScope)

        val frame = createFrame(
            voltageRaw = 12525,
            speedRaw = 1234,
            distanceRaw = 54321,
            totalDistanceRaw = 65432,
            currentRaw = -250,
            temperatureRaw = 3500,
            pwmRaw = 6550,
            chargeMode = 1,
            versionRaw = 4301
        )

        protocol.dataFlow.test(timeout = 5.seconds) {
            val decodeResult = protocol.decode(frame)
            assertNull(decodeResult)

            val telemetry = awaitItem()
            assertEquals("Nosfet", telemetry.manufacturer)
            assertEquals("Nosfet Aero", telemetry.model)
            assertEquals("043.0.01", telemetry.firmwareVersion)
            assertEquals(100, telemetry.batteryLevel)
            assertEquals(65.5, telemetry.pwm ?: -1.0, 0.01)
            assertTrue(telemetry.isCharging)

            cancelAndIgnoreRemainingEvents()
        }
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
        versionRaw: Int = 4300
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
        frame[34] = ((pwmRaw shr 8) and 0xFF).toByte()
        frame[35] = (pwmRaw and 0xFF).toByte()

        frame[22] = ((chargeMode shr 8) and 0xFF).toByte()
        frame[23] = (chargeMode and 0xFF).toByte()
        frame[28] = ((versionRaw shr 8) and 0xFF).toByte()
        frame[29] = (versionRaw and 0xFF).toByte()
        frame[30] = 0x00
        return frame
    }
}
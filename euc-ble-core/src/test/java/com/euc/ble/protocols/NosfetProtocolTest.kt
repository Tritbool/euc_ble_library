package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import com.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import com.euc.ble.test.JUnit4AssertionsCompat.assertNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NosfetProtocolTest {

    private val defaultFrameLength = 36
    private lateinit var protocol: NosfetProtocol

    @BeforeEach
    fun setUp() {
        protocol = NosfetProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }

    @Test
    fun canHandleNosfetDevicesOnly() {
        assertTrue(
            protocol.canHandle(
                EUCDevice(name = "Nosfet Aero", address = "A", manufacturerId = 0, rssi = -50)
            )
        )
        assertTrue(
            protocol.canHandle(
                EUCDevice(name = "Apex", address = "B", manufacturerId = BLEConstants.MANUFACTURER_LEAPERKIM, rssi = -55)
            )
        )
        assertFalse(
            protocol.canHandle(
                EUCDevice(name = "Veteran Patton", address = "C", manufacturerId = 0, rssi = -60)
            )
        )
    }

    @Test
    fun decodeValidNosfetFrameEmitsTelemetry() = runBlocking {
        val frame = createFrame(
            voltageRaw = 12525,
            speedRaw = 1234,
            distanceRaw = 54321,
            totalDistanceRaw = 65432,
            currentRaw = -250,
            temperatureRaw = 3500,
            chargeMode = 1,
            versionRaw = 4301
        )

        val decodeResult = protocol.decode(frame)
        assertNull(decodeResult)

        val telemetry = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertEquals("Nosfet", telemetry.manufacturer)
        assertEquals("Nosfet Aero", telemetry.model)
        assertEquals("043.0.01", telemetry.firmwareVersion)
        assertEquals(100, telemetry.batteryLevel)
        assertTrue(telemetry.isCharging)
    }

    @Test
    fun createCommandMapsKnownActions() {
        assertArrayEquals("SetLightON".encodeToByteArray(), protocol.createCommand(CommandType.LIGHT_ON, Unit))
        assertArrayEquals("SetLightOFF".encodeToByteArray(), protocol.createCommand(CommandType.LIGHT_OFF, Unit))
        assertArrayEquals("b".encodeToByteArray(), protocol.createCommand(CommandType.BEEP, Unit))
    }

    private fun createFrame(
        len: Int = defaultFrameLength,
        voltageRaw: Int = 10000,
        speedRaw: Int = 0,
        distanceRaw: Long = 0,
        totalDistanceRaw: Long = 0,
        currentRaw: Int = 0,
        temperatureRaw: Int = 2500,
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

        frame[22] = ((chargeMode shr 8) and 0xFF).toByte()
        frame[23] = (chargeMode and 0xFF).toByte()
        frame[28] = ((versionRaw shr 8) and 0xFF).toByte()
        frame[29] = (versionRaw and 0xFF).toByte()
        frame[30] = 0x00
        return frame
    }
}

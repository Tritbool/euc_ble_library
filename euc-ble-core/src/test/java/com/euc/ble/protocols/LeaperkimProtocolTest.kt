package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LeaperkimProtocolTest {

    private lateinit var protocol: LeaperkimProtocol

    @Before
    fun setUp() {
        protocol = LeaperkimProtocol()
    }

    @After
    fun tearDown() {
        protocol.close()
    }

    @Test
    fun canHandleLeaperkimAndVeteranDevices() {
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
            chargeMode = 1,
            versionRaw = 7001
        )

        val decodeResult = protocol.decode(frame)
        assertNull(decodeResult)

        val telemetry = withTimeout(5_000) { protocol.dataFlow.first() }
        assertNotNull(telemetry)
        assertEquals("Leaperkim", telemetry.manufacturer)
        assertEquals("Patton S", telemetry.model)
        assertEquals(125.25, telemetry.voltage, 0.01)
        assertEquals(12.34, telemetry.speed, 0.01)
        assertEquals(-2.50, telemetry.current, 0.01)
        assertEquals(35.00, telemetry.temperature, 0.01)
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
        val emitted = withTimeoutOrNull(500) { protocol.dataFlow.first() }
        assertNull("Out-of-range frame should not be emitted", emitted)
    }

    @Test
    fun createCommandMapsKnownActions() {
        assertArrayEquals("SetLightON".encodeToByteArray(), protocol.createCommand(CommandType.LIGHT_ON, Unit))
        assertArrayEquals("SetLightOFF".encodeToByteArray(), protocol.createCommand(CommandType.LIGHT_OFF, Unit))
        assertArrayEquals("b".encodeToByteArray(), protocol.createCommand(CommandType.BEEP, Unit))
    }

    private fun createLeaperkimFrame(
        len: Int = 36,
        voltageRaw: Int = 10000,
        speedRaw: Int = 0,
        distanceRaw: Long = 0,
        totalDistanceRaw: Long = 0,
        currentRaw: Int = 0,
        temperatureRaw: Int = 2500,
        chargeMode: Int = 0,
        versionRaw: Int = 4000
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

        // Keep parser-sensitive bytes valid.
        frame[30] = 0x00
        return frame
    }
}

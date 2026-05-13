package com.euc.ble.protocols

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertNull

class KingsongProtocolTest {

    private lateinit var protocol: KingsongProtocol

    @BeforeEach
    fun setUp() {
        protocol = KingsongProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }

    @Test
    fun decodeA9TelemetryWithoutF5HasNoPwm() = runBlocking {
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertEquals("KingSong", data.manufacturer)
        assertNull(data.pwm)
    }

    @Test
    fun decodeF5ThenA9PublishesPwm() = runBlocking {
        protocol.decode(createF5Frame(outputPercentByte = 63))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertEquals(0.63, data.pwm ?: -1.0, 0.0001)
    }

    @Test
    fun createCommandUsesLegacyFrameFormatForCoreActions() {
        assertArrayEquals(
            createLegacyCommand(command = 0x73, payload2 = 0x13, payload3 = 0x01),
            protocol.createCommand(CommandType.LIGHT_ON, Unit)
        )
        assertArrayEquals(
            createLegacyCommand(command = 0x73, payload2 = 0x12, payload3 = 0x01),
            protocol.createCommand(CommandType.LIGHT_OFF, Unit)
        )
        assertArrayEquals(
            createLegacyCommand(command = 0x88),
            protocol.createCommand(CommandType.BEEP, Unit)
        )
        assertArrayEquals(
            createLegacyCommand(command = 0x40),
            protocol.createCommand(CommandType.POWER_OFF, Unit)
        )
    }

    @Test
    fun createCommandSupportsLegacySettingsControls() {
        assertArrayEquals(
            createLegacyCommand(command = 0x87, payload2 = 0x02, payload3 = 0xE0, payload17 = 0x15),
            protocol.createCommand(CommandType.SET_PEDALS_MODE, 2)
        )
        assertArrayEquals(
            createLegacyCommand(command = 0x6C, payload2 = 0x05),
            protocol.createCommand(CommandType.SET_LED_MODE, 5)
        )
        assertArrayEquals(
            createLegacyCommand(command = 0x73, payload2 = 0x14, payload3 = 0x01),
            protocol.createCommand(CommandType.SET_LIGHT_MODE, 2)
        )
    }

    private fun createA9Frame(
        voltageRaw: Int = 8400,
        speedRaw: Int = 1250,
        distanceRaw: Long = 12345,
        currentRaw: Int = 150,
        temperatureRaw: Int = 2500,
        statusByte: Int = 0,
        batteryByte: Int = 80
    ): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = (voltageRaw and 0xFF).toByte()
        frame[3] = ((voltageRaw shr 8) and 0xFF).toByte()
        frame[4] = (speedRaw and 0xFF).toByte()
        frame[5] = ((speedRaw shr 8) and 0xFF).toByte()
        frame[6] = (distanceRaw and 0xFF).toByte()
        frame[7] = ((distanceRaw shr 8) and 0xFF).toByte()
        frame[8] = ((distanceRaw shr 16) and 0xFF).toByte()
        frame[9] = ((distanceRaw shr 24) and 0xFF).toByte()
        frame[10] = (currentRaw and 0xFF).toByte()
        frame[11] = ((currentRaw shr 8) and 0xFF).toByte()
        frame[12] = (temperatureRaw and 0xFF).toByte()
        frame[13] = ((temperatureRaw shr 8) and 0xFF).toByte()
        frame[14] = (statusByte and 0xFF).toByte()
        frame[15] = (batteryByte and 0xFF).toByte()
        frame[16] = 0xA9.toByte()
        frame[17] = 0x00
        frame[18] = 0x00
        frame[19] = 0x00
        return frame
    }

    private fun createF5Frame(outputPercentByte: Int): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[15] = (outputPercentByte and 0xFF).toByte()
        frame[16] = 0xF5.toByte()
        return frame
    }

    private fun createLegacyCommand(
        command: Int,
        payload2: Int = 0x00,
        payload3: Int = 0x00,
        payload17: Int = 0x14
    ): ByteArray {
        val data = byteArrayOf(
            0xAA.toByte(), 0x55.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x14, 0x5A, 0x5A
        )
        data[2] = payload2.toByte()
        data[3] = payload3.toByte()
        data[16] = command.toByte()
        data[17] = payload17.toByte()
        return data
    }
}

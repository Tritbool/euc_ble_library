// File: `euc-ble-core/src/test/java/com/euc/ble/protocols/GotwayProtocolTest.kt`
package com.euc.ble.protocols

import com.euc.ble.models.EUCDevice
import com.euc.ble.models.EUCData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for GotwayProtocol - tests the Gotway/Begode EUC protocol implementation
 */
class GotwayProtocolTest {

    private val protocol = GotwayProtocol()

    @Before
    fun setUp() {
        // Initialize mock BLE environment for testing
        MockBLEUtils.createMockProtocolTestEnvironment()
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
            name = "KingSong S18",
            manufacturerId = 0x004E
        )
        assertFalse(protocol.canHandle(nonGotway))

        val mtenDevice = MockBLEUtils.createMockDevice(
            name = "Mten3",
            manufacturerId = 0x0000
        )
        assertTrue(protocol.canHandle(mtenDevice))

        val nikolaDevice = EUCDevice(
            name = "Nikola Plus",
            address = "00:11:22:33:44:59",
            manufacturerId = 0x0000,
            rssi = -65
        )
        assertTrue(protocol.canHandle(nikolaDevice))
    }

    @Test
    fun testDecodeValidPacket() {
        val data = byteArrayOf(
            0x01.toByte(), // Packet type (legacy)
            0x64.toByte(), 0x01.toByte(), // Voltage (LE) = 0x0164 = 356 -> 35.6 V
            0x2C.toByte(), 0x01.toByte(), // Speed (LE) = 300 -> 30.0
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance (LE) = 1000000 m -> 1000 km
            0xE8.toByte(), 0x03.toByte(), // Current (LE) = 1000 -> 100.0 A
            0x14.toByte(), 0x00.toByte(), // Temperature (LE) = 20 -> 2.0°C
            0x64.toByte(), // Battery (100%)
            0x01.toByte(), // Status (charging)
            0x1E.toByte(), // Motor temperature (30°C)
            0x00.toByte(), 0x00.toByte() // Padding
        )

        val result = protocol.decode(data)

        assertNotNull(result)
        assertEquals(35.6, result?.voltage ?: 0.0, 0.01)
        assertEquals(30.0, result?.speed ?: 0.0, 0.01)
        assertEquals(1000.0, result?.distance ?: 0.0, 0.01)
        assertEquals(100.0, result?.current ?: 0.0, 0.01)
        assertEquals(2.0, result?.temperature ?: 0.0, 0.01)
        assertEquals(100, result?.batteryLevel)
        assertEquals(3560.0, result?.power ?: 0.0, 0.01) // 35.6 V * 100 A
        assertTrue(result?.isCharging == true)
        assertEquals(30.0, result?.motorTemperature ?: 0.0, 0.01)
        assertEquals("Gotway", result?.manufacturer)
    }

    @Test
    fun testDecodeInvalidPacket() {
        val shortData = byteArrayOf(0x01.toByte())
        val shortResult = protocol.decode(shortData)
        assertNull(shortResult)

        val invalidType = byteArrayOf(
            0x99.toByte(), // Invalid packet type
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte(),
            0x1E.toByte(),
            0x00.toByte(), 0x00.toByte()
        )
        val invalidTypeResult = protocol.decode(invalidType)
        assertNull(invalidTypeResult)

        val emptyResult = protocol.decode(byteArrayOf())
        assertNull(emptyResult)
    }

    @Test
    fun testDecodeEdgeCases() {
        val zeroData = byteArrayOf(
            0x01.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x00.toByte(), 0x00.toByte()
        )

        val zeroResult = protocol.decode(zeroData)
        assertNotNull(zeroResult)
        assertEquals(0.0, zeroResult?.voltage ?: Double.MAX_VALUE, 0.01)
        assertEquals(0.0, zeroResult?.speed ?: Double.MAX_VALUE, 0.01)
        assertEquals(0.0, zeroResult?.distance ?: Double.MAX_VALUE, 0.01)
        assertEquals(0.0, zeroResult?.current ?: Double.MAX_VALUE, 0.01)
        assertEquals(0.0, zeroResult?.temperature ?: Double.MAX_VALUE, 0.01)
        assertEquals(0, zeroResult?.batteryLevel)
        assertEquals(0.0, zeroResult?.power ?: Double.MAX_VALUE, 0.01)
        assertFalse(zeroResult?.isCharging == true)
        assertEquals(0.0, zeroResult?.motorTemperature ?: 0.0, 0.01)

        val maxData = byteArrayOf(
            0x01.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0xFF.toByte(),
            0x00.toByte(), 0x00.toByte()
        )

        val maxResult = protocol.decode(maxData)
        assertNotNull(maxResult)
        assertEquals(6553.5, maxResult?.voltage ?: Double.MAX_VALUE, 0.01)
        assertEquals(6553.5, maxResult?.speed ?: Double.MAX_VALUE, 0.01)
        assertEquals(4294967.295, maxResult?.distance ?: Double.MAX_VALUE, 0.01)
        assertEquals(6553.5, maxResult?.current ?: Double.MAX_VALUE, 0.01)
        assertEquals(6553.5, maxResult?.temperature ?: Double.MAX_VALUE, 0.01)
        assertEquals(255, maxResult?.batteryLevel)
        assertTrue(maxResult?.isCharging == true)
        assertEquals(255.0, maxResult?.motorTemperature ?: Double.MAX_VALUE, 0.01)
    }

    @Test
    fun testDecodePacketType02() {
        val dataType02 = byteArrayOf(
            0x02.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x03.toByte(),
            0x1E.toByte(),
            0x00.toByte(), 0x00.toByte()
        )

        val result = protocol.decode(dataType02)

        assertNotNull(result)
        assertEquals(35.6, result?.voltage ?: Double.MAX_VALUE, 0.01)
        assertEquals(30.0, result?.speed ?: Double.MAX_VALUE, 0.01)
        assertEquals(1000.0, result?.distance ?: Double.MAX_VALUE, 0.01)
        assertEquals(100.0, result?.current ?: Double.MAX_VALUE, 0.01)
        assertEquals(2.0, result?.temperature ?: Double.MAX_VALUE, 0.01)
        assertEquals(100, result?.batteryLevel)
        assertTrue(result?.isCharging == true)
        assertEquals(30.0, result?.motorTemperature ?: Double.MAX_VALUE, 0.01)
    }

    @Test
    fun testDecodePacketWithoutMotorTemperature() {
        val dataNoMotor = byteArrayOf(
            0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte()
        )

        val result = protocol.decode(dataNoMotor)

        assertNotNull(result)
        assertEquals(35.6, result?.voltage ?: Double.MAX_VALUE, 0.01)
        assertEquals(30.0, result?.speed ?: Double.MAX_VALUE, 0.01)
        assertEquals(1000.0, result?.distance ?: Double.MAX_VALUE, 0.01)
        assertEquals(100.0, result?.current ?: Double.MAX_VALUE, 0.01)
        assertEquals(2.0, result?.temperature ?: Double.MAX_VALUE, 0.01)
        assertEquals(100, result?.batteryLevel)
        assertTrue(result?.isCharging == true)
        assertNull(result?.motorTemperature)
    }

    @Test
    fun testCreateCommand() {
        val lightOn = protocol.createCommand(CommandType.LIGHT_ON, 0)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x01), lightOn)

        val lightOff = protocol.createCommand(CommandType.LIGHT_OFF, 0)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x00), lightOff)

        val beep = protocol.createCommand(CommandType.BEEP, 0)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x02, 0x01), beep)

        val powerOff = protocol.createCommand(CommandType.POWER_OFF, 0)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x03, 0x01), powerOff)

        val brightness50 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 50)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x04, 0x7F.toByte()), brightness50)

        val brightness100 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 100)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x04, 0xFF.toByte()), brightness100)

        val brightness0 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 0)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x04, 0x00.toByte()), brightness0)

        val invalidBrightness = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 150)
        assertArrayEquals(byteArrayOf(), invalidBrightness)
    }

    @Test
    fun testIsDeviceReady() {
        val readyData = MockBLEUtils.createMockEUCData(
            speed = 5.0,
            voltage = 67.2,
            manufacturer = "Gotway",
            model = "MSX",
            motorTemperature = null
        )
        assertTrue(protocol.isDeviceReady(readyData))

        val lowVoltageData = readyData.copy(voltage = 30.0)
        assertFalse(protocol.isDeviceReady(lowVoltageData))

        val highTempData = readyData.copy(temperature = 80.0)
        assertFalse(protocol.isDeviceReady(highTempData))

        val veryHighTempData = readyData.copy(temperature = 90.0)
        assertFalse(protocol.isDeviceReady(veryHighTempData))

        val negativeSpeedData = readyData.copy(speed = -1.0)
        assertTrue(protocol.isDeviceReady(negativeSpeedData))

        val zeroSpeedData = readyData.copy(speed = 0.0)
        assertTrue(protocol.isDeviceReady(zeroSpeedData))

        val minVoltageData = readyData.copy(voltage = 45.1)
        assertTrue(protocol.isDeviceReady(minVoltageData))

        val maxTempData = readyData.copy(temperature = 74.9)
        assertTrue(protocol.isDeviceReady(maxTempData))
    }

    @Test
    fun testManufacturerAndSupportedModels() {
        assertEquals("Gotway", protocol.manufacturer)
        assertTrue(protocol.supportedModels.isNotEmpty())
        assertTrue(protocol.supportedModels.contains("MSX"))
        assertTrue(protocol.supportedModels.contains("Begode Master"))
        assertTrue(protocol.supportedModels.contains("Mten3"))
        assertTrue(protocol.supportedModels.contains("Nikola Plus"))
    }

    @Test
    fun testServiceUUIDs() {
        val serviceUUID = protocol.getServiceUUID()
        val dataUUID = protocol.getDataCharacteristicUUID()
        assertNotNull(serviceUUID)
        assertNotNull(dataUUID)
        assertNotEquals("00000000-0000-0000-0000-000000000000", serviceUUID.toString())
        assertNotEquals("00000000-0000-0000-0000-000000000000", dataUUID.toString())
    }

    @Test
    fun testStatusFlagsParsing() {
        val chargingData = byteArrayOf(
            0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte(),
            0x00.toByte(),
            0x00.toByte(), 0x00.toByte()
        )
        val chargingResult = protocol.decode(chargingData)
        assertTrue(chargingResult?.isCharging == true)

        val alarmData = byteArrayOf(
            0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x02.toByte(),
            0x00.toByte(),
            0x00.toByte(), 0x00.toByte()
        )
        val alarmResult = protocol.decode(alarmData)
        assertFalse(alarmResult?.isCharging == true)

        val bothFlagsData = byteArrayOf(
            0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x03.toByte(),
            0x00.toByte(),
            0x00.toByte(), 0x00.toByte()
        )
        val bothFlagsResult = protocol.decode(bothFlagsData)
        assertTrue(bothFlagsResult?.isCharging == true)
    }

    @Test
    fun testParseTypeAFrame() {
        // First byte 0x00 triggers parseTypeA in current impl
        val dataA = byteArrayOf(
            0x00.toByte(),
            0x64.toByte(), 0x01.toByte(), // voltage LE (parsed by parseTypeA as implemented)
            0x2C.toByte(), 0x01.toByte(), // speed
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // distance
            0xE8.toByte(), 0x03.toByte(), // current
            0x14.toByte(), 0x00.toByte(), // temp
            0x64.toByte(), // battery
            0x01.toByte(), // status
            0x1E.toByte()  // motor temp
        )

        val resA = protocol.decode(dataA)
        assertNotNull(resA)
        assertEquals("Gotway A", resA?.model)
        assertEquals(35.6, resA?.voltage ?: 0.0, 0.01)
        assertTrue(resA?.isCharging == true)
    }

    @Test
    fun testParseTypeBFrame() {
        // First byte 0x04 triggers parseTypeB in current impl
        val dataB = byteArrayOf(
            0x04.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte(),
            0x1E.toByte()
        )

        val resB = protocol.decode(dataB)
        assertNotNull(resB)
        assertEquals("Gotway B", resB?.model)
        assertEquals(35.6, resB?.voltage ?: 0.0, 0.01)
    }
}
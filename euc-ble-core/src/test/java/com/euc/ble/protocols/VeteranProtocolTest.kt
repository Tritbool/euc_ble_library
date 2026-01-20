package com.euc.ble.protocols

import com.euc.ble.models.EUCDevice
import com.euc.ble.models.EUCData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for VeteranProtocol - tests the Veteran EUC protocol implementation
 */
class VeteranProtocolTest {

    private val protocol = VeteranProtocol()

    @Before
    fun setUp() {
        // Initialize mock BLE environment for testing
        MockBLEUtils.createMockProtocolTestEnvironment()
    }

    @Test
    fun testCanHandle() {
        // Test Veteran device by manufacturer ID
        val veteranDevice = MockBLEUtils.createMockDevice(
            name = "Veteran Sherman",
            manufacturerId = 0x0056
        )
        assertTrue(protocol.canHandle(veteranDevice))

        // Test Veteran device by name pattern
        val veteranByName = MockBLEUtils.createMockDevice(
            name = "Sherman Max",
            manufacturerId = 0x0000
        )
        assertTrue(protocol.canHandle(veteranByName))

        // Test non-Veteran device
        val nonVeteran = MockBLEUtils.createMockDevice(
            name = "KingSong S18",
            manufacturerId = 0x004E
        )
        assertFalse(protocol.canHandle(nonVeteran))

        // Test device with Lynx pattern
        val lynxDevice = MockBLEUtils.createMockDevice(
            name = "Veteran Lynx",
            manufacturerId = 0x0000
        )
        assertTrue(protocol.canHandle(lynxDevice))
    }

    @Test
    fun testDecodeValidPacket() {
        // Create a valid Veteran packet with checksum and BMS data
        // Header: 0x55 0xAA
        // Frame type: 0x01 (data frame)
        // Data length: 0x32 (50 bytes)
        // Voltage: 0x64 0x01 = 360 (36.0V)
        // Speed: 0x2C 0x01 = 300 (30.0 km/h)
        // Distance: 0x40 0x42 0x0F 0x00 = 1000000 meters = 1000 km
        // Total distance: 0x80 0x84 0x1E 0x00 = 2000000 meters = 2000 km
        // Current: 0xE8 0x03 = 1000 (100.0A)
        // Temperature: 0x14 0x00 = 20 (2.0°C)
        // Auto-off time: 0x00 0x00 (0 seconds)
        // Charge mode: 0x00 0x00
        // Speed alert: 0x14 0x00 = 20 (2.0 km/h)
        // Speed tiltback: 0x1E 0x00 = 30 (3.0 km/h)
        // Version info: 0x01 0x00
        // Pedals mode: 0x00 0x00
        // Pitch angle: 0x00 0x00 (0.0 degrees)
        // PWM value: 0x00 0x00
        // Battery: 0x64 (100%)
        // Status: 0x01 (charging)
        // BMS data: Packet type 1 with cell voltages
        // Checksum: calculated
        val data = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(), // Header
            0x01.toByte(), // Frame type (data)
            0x32.toByte(), // Data length (50 bytes)
            0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
            0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance (1000 km)
            0x80.toByte(), 0x84.toByte(), 0x1E.toByte(), 0x00.toByte(), // Total distance (2000 km)
            0xE8.toByte(), 0x03.toByte(), // Current (100.0A)
            0x14.toByte(), 0x00.toByte(), // Temperature (2.0°C)
            0x00.toByte(), 0x00.toByte(), // Auto-off time
            0x00.toByte(), 0x00.toByte(), // Charge mode
            0x14.toByte(), 0x00.toByte(), // Speed alert (2.0 km/h)
            0x1E.toByte(), 0x00.toByte(), // Speed tiltback (3.0 km/h)
            0x01.toByte(), 0x00.toByte(), // Version info
            0x00.toByte(), 0x00.toByte(), // Pedals mode
            0x00.toByte(), 0x00.toByte(), // Pitch angle (0.0 degrees)
            0x00.toByte(), 0x00.toByte(), // PWM value
            0x64.toByte(), // Battery (100%)
            0x01.toByte(), // Status (charging)
            0x01.toByte(), // BMS packet type 1
            0x64.toByte(), 0x01.toByte(), // Cell 1: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 2: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 3: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 4: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 5: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 6: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 7: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 8: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 9: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 10: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 11: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 12: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 13: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 14: 360 (3.60V)
            0x64.toByte(), 0x01.toByte(), // Cell 15: 360 (3.60V)
            0x00.toByte()  // Checksum (will be calculated)
        )

        // Calculate checksum (XOR of all bytes except last)
        var checksum: Byte = 0x00
        for (i in 0 until data.size - 1) {
            checksum = (checksum.toInt() xor data[i].toInt()).toByte()
        }
        data[data.size - 1] = checksum

        val result = protocol.decode(data)

        assertNotNull(result)
        assertEquals(36.0, result?.voltage, 0.01)
        assertEquals(30.0, result?.speed, 0.01)
        assertEquals(1000.0, result?.distance, 0.01)
        assertEquals(100.0, result?.current, 0.01)
        assertEquals(2.0, result?.temperature, 0.01)
        assertEquals(100, result?.batteryLevel)
        assertEquals(3600.0, result?.power, 0.01) // 36.0V * 100.0A
        assertTrue(result?.isCharging == true)
        assertEquals("Veteran", result?.manufacturer)
        
        // Check cell voltages
        assertNotNull(result?.cellVoltages)
        assertEquals(15, result?.cellVoltages?.size)
        assertEquals(3.6, result?.cellVoltages?.get(0), 0.01)
        assertEquals(3.6, result?.cellVoltages?.get(14), 0.01)
    }

    @Test
    fun testDecodeInvalidPacket() {
        // Test packet too short
        val shortData = byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x01.toByte())
        val shortResult = protocol.decode(shortData)
        assertNull(shortResult)

        // Test packet with wrong header
        val wrongHeader = byteArrayOf(
            0xBB.toByte(), 0xCC.toByte(),
            0x01.toByte(),
            0x32.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(),
            0x80.toByte(), 0x84.toByte(), 0x1E.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x1E.toByte(), 0x00.toByte(),
            0x01.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte(),
            0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x00.toByte()
        )
        val wrongHeaderResult = protocol.decode(wrongHeader)
        assertNull(wrongHeaderResult)

        // Test packet with wrong frame type
        val wrongFrameType = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(),
            0x02.toByte(), // Wrong frame type
            0x32.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(),
            0x80.toByte(), 0x84.toByte(), 0x1E.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x1E.toByte(), 0x00.toByte(),
            0x01.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte(),
            0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x00.toByte()
        )
        val wrongFrameTypeResult = protocol.decode(wrongFrameType)
        assertNull(wrongFrameTypeResult)

        // Test packet with wrong checksum
        val wrongChecksum = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(),
            0x01.toByte(),
            0x32.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(),
            0x80.toByte(), 0x84.toByte(), 0x1E.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x1E.toByte(), 0x00.toByte(),
            0x01.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte(),
            0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0xFF.toByte() // Wrong checksum
        )
        val wrongChecksumResult = protocol.decode(wrongChecksum)
        assertNull(wrongChecksumResult)

        // Test empty packet
        val emptyResult = protocol.decode(byteArrayOf())
        assertNull(emptyResult)
    }

    @Test
    fun testDecodeEdgeCases() {
        // Test zero values
        val zeroData = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(), // Header
            0x01.toByte(), // Frame type
            0x32.toByte(), // Data length
            0x00.toByte(), 0x00.toByte(), // Voltage (0.0V)
            0x00.toByte(), 0x00.toByte(), // Speed (0.0 km/h)
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // Distance (0 km)
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // Total distance (0 km)
            0x00.toByte(), 0x00.toByte(), // Current (0.0A)
            0x00.toByte(), 0x00.toByte(), // Temperature (0.0°C)
            0x00.toByte(), 0x00.toByte(), // Auto-off time
            0x00.toByte(), 0x00.toByte(), // Charge mode
            0x00.toByte(), 0x00.toByte(), // Speed alert
            0x00.toByte(), 0x00.toByte(), // Speed tiltback
            0x00.toByte(), 0x00.toByte(), // Version info
            0x00.toByte(), 0x00.toByte(), // Pedals mode
            0x00.toByte(), 0x00.toByte(), // Pitch angle
            0x00.toByte(), 0x00.toByte(), // PWM value
            0x00.toByte(), // Battery (0%)
            0x00.toByte(), // Status (not charging)
            0x01.toByte(), // BMS packet type 1
            0x00.toByte(), 0x00.toByte(), // Cell 1: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 2: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 3: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 4: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 5: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 6: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 7: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 8: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 9: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 10: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 11: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 12: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 13: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 14: 0 (0.0V)
            0x00.toByte(), 0x00.toByte(), // Cell 15: 0 (0.0V)
            0x00.toByte() // Checksum (will be calculated)
        )

        // Calculate checksum for zero data
        var checksum: Byte = 0x00
        for (i in 0 until zeroData.size - 1) {
            checksum = (checksum.toInt() xor zeroData[i].toInt()).toByte()
        }
        zeroData[zeroData.size - 1] = checksum

        val zeroResult = protocol.decode(zeroData)
        assertNotNull(zeroResult)
        assertEquals(0.0, zeroResult?.voltage, 0.01)
        assertEquals(0.0, zeroResult?.speed, 0.01)
        assertEquals(0.0, zeroResult?.distance, 0.01)
        assertEquals(0.0, zeroResult?.current, 0.01)
        assertEquals(0.0, zeroResult?.temperature, 0.01)
        assertEquals(0, zeroResult?.batteryLevel)
        assertEquals(0.0, zeroResult?.power, 0.01)
        assertFalse(zeroResult?.isCharging == true)
        
        // Check cell voltages
        assertNotNull(zeroResult?.cellVoltages)
        assertEquals(15, zeroResult?.cellVoltages?.size)
        assertEquals(0.0, zeroResult?.cellVoltages?.get(0), 0.01)
        assertEquals(0.0, zeroResult?.cellVoltages?.get(14), 0.01)

        // Test maximum values
        val maxData = byteArrayOf(
            0x55.toByte(), 0xAA.toByte(), // Header
            0x01.toByte(), // Frame type
            0x32.toByte(), // Data length
            0xFF.toByte(), 0xFF.toByte(), // Voltage (6553.5V)
            0xFF.toByte(), 0xFF.toByte(), // Speed (6553.5 km/h)
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // Distance (4294967295 km)
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // Total distance (4294967295 km)
            0xFF.toByte(), 0xFF.toByte(), // Current (6553.5A)
            0xFF.toByte(), 0xFF.toByte(), // Temperature (6553.5°C)
            0xFF.toByte(), 0xFF.toByte(), // Auto-off time
            0xFF.toByte(), 0xFF.toByte(), // Charge mode
            0xFF.toByte(), 0xFF.toByte(), // Speed alert
            0xFF.toByte(), 0xFF.toByte(), // Speed tiltback
            0xFF.toByte(), 0xFF.toByte(), // Version info
            0xFF.toByte(), 0xFF.toByte(), // Pedals mode
            0xFF.toByte(), 0xFF.toByte(), // Pitch angle
            0xFF.toByte(), 0xFF.toByte(), // PWM value
            0xFF.toByte(), // Battery (255%)
            0xFF.toByte(), // Status (all flags set)
            0x01.toByte(), // BMS packet type 1
            0xFF.toByte(), 0xFF.toByte(), // Cell 1: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 2: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 3: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 4: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 5: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 6: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 7: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 8: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 9: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 10: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 11: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 12: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 13: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 14: 65535 (65.535V)
            0xFF.toByte(), 0xFF.toByte(), // Cell 15: 65535 (65.535V)
            0x00.toByte() // Checksum (will be calculated)
        )

        // Calculate checksum for max data
        checksum = 0x00
        for (i in 0 until maxData.size - 1) {
            checksum = (checksum.toInt() xor maxData[i].toInt()).toByte()
        }
        maxData[maxData.size - 1] = checksum

        val maxResult = protocol.decode(maxData)
        assertNotNull(maxResult)
        assertEquals(6553.5, maxResult?.voltage, 0.01)
        assertEquals(6553.5, maxResult?.speed, 0.01)
        assertEquals(4294967.295, maxResult?.distance, 0.01)
        assertEquals(6553.5, maxResult?.current, 0.01)
        assertEquals(6553.5, maxResult?.temperature, 0.01)
        assertEquals(255, maxResult?.batteryLevel)
        assertTrue(maxResult?.isCharging == true) // At least one flag is set
        
        // Check cell voltages
        assertNotNull(maxResult?.cellVoltages)
        assertEquals(15, maxResult?.cellVoltages?.size)
        assertEquals(65.535, maxResult?.cellVoltages?.get(0), 0.01)
        assertEquals(65.535, maxResult?.cellVoltages?.get(14), 0.01)
    }

    @Test
    fun testCreateCommand() {
        // Test light on command
        val lightOn = protocol.createCommand(CommandType.LIGHT_ON, 0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x01, 0x01, 0x01, 0x00), lightOn)

        // Test light off command
        val lightOff = protocol.createCommand(CommandType.LIGHT_OFF, 0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x01, 0x01, 0x00, 0x00), lightOff)

        // Test beep command
        val beep = protocol.createCommand(CommandType.BEEP, 0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x02, 0x01, 0x00), beep)

        // Test power off command
        val powerOff = protocol.createCommand(CommandType.POWER_OFF, 0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x03, 0x01, 0x00), powerOff)

        // Test light brightness command
        val brightness50 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 50)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x04, 0x7F.toByte(), 0x00), brightness50)

        val brightness100 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 100)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x04, 0xFF.toByte(), 0x00), brightness100)

        val brightness0 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x04, 0x00.toByte(), 0x00), brightness0)

        // Test invalid brightness (should return empty array)
        val invalidBrightness = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 150)
        assertArrayEquals(byteArrayOf(), invalidBrightness)

        // Test speed limit command
        val speedLimit20 = protocol.createCommand(CommandType.SET_SPEED_LIMIT, 20.0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x20, 0xC8.toByte(), 0x07.toByte()), speedLimit20)

        val speedLimit50 = protocol.createCommand(CommandType.SET_SPEED_LIMIT, 50.0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x20, 0x32.toByte(), 0x12.toByte()), speedLimit50)

        // Test invalid speed limit (should return empty array)
        val invalidSpeedLimit = protocol.createCommand(CommandType.SET_SPEED_LIMIT, 5.0)
        assertArrayEquals(byteArrayOf(), invalidSpeedLimit)

        // Test alarm speed command
        val alarmSpeed20 = protocol.createCommand(CommandType.SET_ALARM_SPEED, 20.0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0xAA.toByte(), 0x21, 0xC8.toByte(), 0x07.toByte()), alarmSpeed20)

        // Test unsupported command
        val unsupported = protocol.createCommand(CommandType.REQUEST_SERIAL, 0)
        assertArrayEquals(byteArrayOf(), unsupported)
    }

    @Test
    fun testIsDeviceReady() {
        // Test ready device
        val readyData = MockBLEUtils.createMockEUCData(
            speed = 5.0,
            voltage = 84.0,
            manufacturer = "Veteran",
            model = "Sherman",
            cellVoltages = listOf(3.6, 3.6, 3.6, 3.6),
            motorTemperature = 30.0
        )
        assertTrue(protocol.isDeviceReady(readyData))

        // Test device with low voltage
        val lowVoltageData = readyData.copy(voltage = 30.0)
        assertFalse(protocol.isDeviceReady(lowVoltageData))

        // Test device with high temperature
        val highTempData = readyData.copy(temperature = 90.0)
        assertFalse(protocol.isDeviceReady(highTempData))

        // Test device with negative speed (should still be ready)
        val negativeSpeedData = readyData.copy(speed = -1.0)
        assertTrue(protocol.isDeviceReady(negativeSpeedData))

        // Test device with zero speed (should be ready)
        val zeroSpeedData = readyData.copy(speed = 0.0)
        assertTrue(protocol.isDeviceReady(zeroSpeedData))
    }

    @Test
    fun testManufacturerAndSupportedModels() {
        assertEquals("Veteran", protocol.manufacturer)
        assertTrue(protocol.supportedModels.isNotEmpty())
        assertTrue(protocol.supportedModels.contains("Sherman"))
        assertTrue(protocol.supportedModels.contains("Sherman Max"))
        assertTrue(protocol.supportedModels.contains("Lynx"))
        assertTrue(protocol.supportedModels.contains("Titan"))
    }

    @Test
    fun testServiceUUIDs() {
        // Test that service UUIDs are not empty
        val serviceUUID = protocol.getServiceUUID()
        val dataUUID = protocol.getDataCharacteristicUUID()
        assertNotNull(serviceUUID)
        assertNotNull(dataUUID)
        assertNotEquals("00000000-0000-0000-0000-000000000000", serviceUUID.toString())
        assertNotEquals("00000000-0000-0000-0000-000000000000", dataUUID.toString())
    }
}
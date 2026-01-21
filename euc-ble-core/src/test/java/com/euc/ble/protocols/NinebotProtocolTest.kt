package com.euc.ble.protocols

import com.euc.ble.models.EUCDevice
import com.euc.ble.models.EUCData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for NinebotProtocol - tests the Ninebot EUC protocol implementation
 */
class NinebotProtocolTest {

    private val protocol = NinebotProtocol()

    @Before
    fun setUp() {
        // Initialize mock BLE environment for testing
        MockBLEUtils.createMockProtocolTestEnvironment()
    }

    @Test
    fun testCanHandle() {
        // Test Ninebot device by name pattern
        val ninebotDevice = MockBLEUtils.createMockDevice(
            name = "Ninebot One S2",
            manufacturerId = 0x004E
        )
        assertTrue(protocol.canHandle(ninebotDevice))

        // Test Ninebot device by S2 pattern
        val s2Device = MockBLEUtils.createMockDevice(
            name = "S2",
            manufacturerId = 0x0000
        )
        assertTrue(protocol.canHandle(s2Device))

        // Test non-Ninebot device
        val nonNinebot = MockBLEUtils.createMockDevice(
            name = "KingSong S18",
            manufacturerId = 0x004E
        )
        assertFalse(protocol.canHandle(nonNinebot))

        // Test device with Mini pattern
        val miniDevice = MockBLEUtils.createMockDevice(
            name = "Ninebot Mini",
            manufacturerId = 0x0000
        )
        assertTrue(protocol.canHandle(miniDevice))
    }

    @Test
    fun testDecodeValidPacket() {
        // Create a valid Ninebot packet with checksum
        // Start marker: 0x55
        // Frame length: 0x18 (24 bytes)
        // Message type: 0x01 (data message)
        // Sequence number: 0x01
        // Voltage: 0x64 0x01 = 360 (36.0V)
        // Speed: 0x2C 0x01 = 300 (30.0 km/h)
        // Distance: 0x40 0x42 0x0F 0x00 = 1000000 meters = 1000 km
        // Current: 0xE8 0x03 = 1000 (100.0A)
        // Temperature: 0x14 0x00 = 20 (2.0°C)
        // Battery: 0x64 (100%)
        // Status: 0x01 (charging)
        // Checksum: calculated
        val data = byteArrayOf(
            0x55.toByte(), // Start marker
            0x18.toByte(), // Frame length (24 bytes)
            0x01.toByte(), // Message type (data)
            0x01.toByte(), // Sequence number
            0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
            0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance (1000 km)
            0xE8.toByte(), 0x03.toByte(), // Current (100.0A)
            0x14.toByte(), 0x00.toByte(), // Temperature (2.0°C)
            0x64.toByte(), // Battery (100%)
            0x01.toByte(), // Status (charging)
            0x00.toByte(), // Additional data
            0x00.toByte(), // Additional data
            0x00.toByte(), // Additional data
            0x00.toByte(), // Additional data
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
        assertEquals(36.0, result?.voltage ?: Double.MAX_VALUE, 0.01)
        assertEquals(30.0, result?.speed ?: Double.MAX_VALUE, 0.01)
        assertEquals(1000.0, result?.distance ?: Double.MAX_VALUE, 0.01)
        assertEquals(100.0, result?.current ?: Double.MAX_VALUE, 0.01)
        assertEquals(2.0, result?.temperature ?: Double.MAX_VALUE, 0.01)
        assertEquals(100, result?.batteryLevel)
        assertEquals(3600.0, result?.power ?: Double.MAX_VALUE, 0.01) // 36.0V * 100.0A
        assertTrue(result?.isCharging == true)
        assertEquals("Ninebot", result?.manufacturer)
    }

    @Test
    fun testDecodeInvalidPacket() {
        // Test packet too short
        val shortData = byteArrayOf(0x55.toByte(), 0x18.toByte(), 0x01.toByte())
        val shortResult = protocol.decode(shortData)
        assertNull(shortResult)

        // Test packet with wrong start marker
        val wrongStart = byteArrayOf(
            0xAA.toByte(), // Wrong start marker
            0x18.toByte(),
            0x01.toByte(),
            0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte()
        )
        val wrongStartResult = protocol.decode(wrongStart)
        assertNull(wrongStartResult)

        // Test packet with wrong message type
        val wrongType = byteArrayOf(
            0x55.toByte(),
            0x18.toByte(),
            0x02.toByte(), // Wrong message type
            0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte()
        )
        val wrongTypeResult = protocol.decode(wrongType)
        assertNull(wrongTypeResult)

        // Test packet with wrong checksum
        val wrongChecksum = byteArrayOf(
            0x55.toByte(),
            0x18.toByte(),
            0x01.toByte(),
            0x01.toByte(),
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
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
            0x55.toByte(), // Start marker
            0x18.toByte(), // Frame length
            0x01.toByte(), // Message type
            0x01.toByte(), // Sequence number
            0x00.toByte(), 0x00.toByte(), // Voltage (0.0V)
            0x00.toByte(), 0x00.toByte(), // Speed (0.0 km/h)
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // Distance (0 km)
            0x00.toByte(), 0x00.toByte(), // Current (0.0A)
            0x00.toByte(), 0x00.toByte(), // Temperature (0.0°C)
            0x00.toByte(), // Battery (0%)
            0x00.toByte(), // Status (not charging)
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte() // Checksum
        )

        // Calculate checksum for zero data
        var checksum: Byte = 0x00
        for (i in 0 until zeroData.size - 1) {
            checksum = (checksum.toInt() xor zeroData[i].toInt()).toByte()
        }
        zeroData[zeroData.size - 1] = checksum

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

        // Test maximum values
        val maxData = byteArrayOf(
            0x55.toByte(), // Start marker
            0x18.toByte(), // Frame length
            0x01.toByte(), // Message type
            0x01.toByte(), // Sequence number
            0xFF.toByte(), 0xFF.toByte(), // Voltage (6553.5V)
            0xFF.toByte(), 0xFF.toByte(), // Speed (6553.5 km/h)
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // Distance (4294967295 km)
            0xFF.toByte(), 0xFF.toByte(), // Current (6553.5A)
            0xFF.toByte(), 0xFF.toByte(), // Temperature (6553.5°C)
            0xFF.toByte(), // Battery (255%)
            0xFF.toByte(), // Status (all flags set)
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
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
        assertEquals(6553.5, maxResult?.voltage ?: Double.MAX_VALUE, 0.01)
        assertEquals(6553.5, maxResult?.speed ?: Double.MAX_VALUE, 0.01)
        assertEquals(4294967.295, maxResult?.distance ?: Double.MAX_VALUE, 0.01)
        assertEquals(6553.5, maxResult?.current ?: Double.MAX_VALUE, 0.01)
        assertEquals(6553.5, maxResult?.temperature ?: Double.MAX_VALUE, 0.01)
        assertEquals(255, maxResult?.batteryLevel)
        assertTrue(maxResult?.isCharging == true) // At least one flag is set
    }

    @Test
    fun testCreateCommand() {
        // Test light on command
        val lightOn = protocol.createCommand(CommandType.LIGHT_ON, 0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x01, 0x01, 0x01, 0x00, 0x00), lightOn)

        // Test light off command
        val lightOff = protocol.createCommand(CommandType.LIGHT_OFF, 0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x01, 0x01, 0x00, 0x00, 0x00), lightOff)

        // Test beep command
        val beep = protocol.createCommand(CommandType.BEEP, 0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0x04.toByte(), 0x02, 0x01, 0x00, 0x00), beep)

        // Test power off command
        val powerOff = protocol.createCommand(CommandType.POWER_OFF, 0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0x04.toByte(), 0x03, 0x01, 0x00, 0x00), powerOff)

        // Test light brightness command
        val brightness50 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 50)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x04, 0x7F.toByte(), 0x00, 0x00, 0x00), brightness50)

        val brightness100 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 100)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x04, 0xFF.toByte(), 0x00, 0x00, 0x00), brightness100)

        val brightness0 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 0)
        assertArrayEquals(byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x04, 0x00.toByte(), 0x00, 0x00, 0x00), brightness0)

        // Test invalid brightness (should return empty array)
        val invalidBrightness = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 150)
        assertArrayEquals(byteArrayOf(), invalidBrightness)

        // Test unsupported command
        // val unsupported = protocol.createCommand(CommandType.SPEED_LIMIT, 0)
        // assertArrayEquals(byteArrayOf(), unsupported)
    }

    @Test
    fun testIsDeviceReady() {
        // Test ready device
        val readyData = MockBLEUtils.createMockEUCData(
            speed = 5.0,
            voltage = 67.2,
            manufacturer = "Ninebot",
            model = "S2"
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
        assertEquals("Ninebot", protocol.manufacturer)
        assertTrue(protocol.supportedModels.isNotEmpty())
        assertTrue(protocol.supportedModels.contains("Ninebot One S2"))
        assertTrue(protocol.supportedModels.contains("Ninebot Mini"))
        assertTrue(protocol.supportedModels.contains("Ninebot E+"))
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
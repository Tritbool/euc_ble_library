package com.euc.ble.protocols

import com.euc.ble.models.EUCDevice
import com.euc.ble.models.EUCData
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for GotwayProtocol - tests the Gotway/Begode EUC protocol implementation
 */
class GotwayProtocolTest {

    private val protocol = GotwayProtocol()

    @Test
    fun testCanHandle() {
        // Test Gotway device by manufacturer ID
        val gotwayDevice = EUCDevice(
            name = "Gotway MSX",
            address = "00:11:22:33:44:55",
            manufacturerId = 0x0047,
            rssi = -50
        )
        assertTrue(protocol.canHandle(gotwayDevice))

        // Test Gotway device by name pattern
        val gotwayByName = EUCDevice(
            name = "Begode Master",
            address = "00:11:22:33:44:56",
            manufacturerId = 0x0000,
            rssi = -60
        )
        assertTrue(protocol.canHandle(gotwayByName))

        // Test non-Gotway device
        val nonGotway = EUCDevice(
            name = "KingSong S18",
            address = "00:11:22:33:44:57",
            manufacturerId = 0x004E,
            rssi = -70
        )
        assertFalse(protocol.canHandle(nonGotway))

        // Test device with Mten pattern
        val mtenDevice = EUCDevice(
            name = "Mten3",
            address = "00:11:22:33:44:58",
            manufacturerId = 0x0000,
            rssi = -55
        )
        assertTrue(protocol.canHandle(mtenDevice))

        // Test device with Nikola pattern
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
        // Create a valid Gotway packet (type 0x01)
        // Byte 0: Packet type (0x01)
        // Byte 1-2: Voltage (little-endian, 0.1V units) = 0x64 0x01 = 360 (36.0V)
        // Byte 3-4: Speed (little-endian, 0.1 km/h units) = 0x2C 0x01 = 300 (30.0 km/h)
        // Byte 5-8: Distance (little-endian, meters) = 0x40 0x42 0x0F 0x00 = 1000000 meters = 1000 km
        // Byte 9-10: Current (little-endian, 0.1A units) = 0xE8 0x03 = 1000 (100.0A)
        // Byte 11-12: Temperature (little-endian, 0.1°C units) = 0x14 0x00 = 20 (2.0°C)
        // Byte 13: Battery percentage = 0x64 (100%)
        // Byte 14: Status flags = 0x01 (charging)
        // Byte 15-17: Additional data (motor temperature)
        val data = byteArrayOf(
            0x01.toByte(), // Packet type
            0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
            0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance (1000 km)
            0xE8.toByte(), 0x03.toByte(), // Current (100.0A)
            0x14.toByte(), 0x00.toByte(), // Temperature (2.0°C)
            0x64.toByte(), // Battery (100%)
            0x01.toByte(), // Status (charging)
            0x1E.toByte(), // Motor temperature (30°C)
            0x00.toByte(), 0x00.toByte() // Padding
        )

        val result = protocol.decode(data)

        assertNotNull(result)
        assertEquals(36.0, result?.voltage?:0.0, 0.01)
        assertEquals(30.0, result?.speed?:0.0, 0.01)
        assertEquals(1000.0, result?.distance?:0.0, 0.01)
        assertEquals(100.0, result?.current?:0.0, 0.01)
        assertEquals(2.0, result?.temperature?:0.0, 0.01)
        assertEquals(100, result?.batteryLevel)
        assertEquals(3600.0, result?.power?:0.0, 0.01) // 36.0V * 100.0A
        assertTrue(result?.isCharging == true)
        assertEquals(30.0, result?.motorTemperature?:0.0, 0.01) // Motor temperature
        assertEquals("Gotway", result?.manufacturer)
    }

    @Test
    fun testDecodeInvalidPacket() {
        // Test packet too short
        val shortData = byteArrayOf(0x01.toByte())
        val shortResult = protocol.decode(shortData)
        assertNull(shortResult)

        // Test packet with invalid type
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

        // Test empty packet
        val emptyResult = protocol.decode(byteArrayOf())
        assertNull(emptyResult)
    }

    @Test
    fun testDecodeEdgeCases() {
        // Test zero values
        val zeroData = byteArrayOf(
            0x01.toByte(), // Packet type
            0x00.toByte(), 0x00.toByte(), // Voltage (0.0V)
            0x00.toByte(), 0x00.toByte(), // Speed (0.0 km/h)
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // Distance (0 km)
            0x00.toByte(), 0x00.toByte(), // Current (0.0A)
            0x00.toByte(), 0x00.toByte(), // Temperature (0.0°C)
            0x00.toByte(), // Battery (0%)
            0x00.toByte(), // Status (not charging)
            0x00.toByte(), // Motor temperature (0°C)
            0x00.toByte(), 0x00.toByte() // Padding
        )

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
        assertEquals(0.0, zeroResult?.motorTemperature, 0.01)

        // Test maximum values
        val maxData = byteArrayOf(
            0x01.toByte(), // Packet type
            0xFF.toByte(), 0xFF.toByte(), // Voltage (6553.5V)
            0xFF.toByte(), 0xFF.toByte(), // Speed (6553.5 km/h)
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // Distance (4294967295 km)
            0xFF.toByte(), 0xFF.toByte(), // Current (6553.5A)
            0xFF.toByte(), 0xFF.toByte(), // Temperature (6553.5°C)
            0xFF.toByte(), // Battery (255%)
            0xFF.toByte(), // Status (all flags set)
            0xFF.toByte(), // Motor temperature (255°C)
            0x00.toByte(), 0x00.toByte() // Padding
        )

        val maxResult = protocol.decode(maxData)
        assertNotNull(maxResult)
        assertEquals(6553.5, maxResult?.voltage, 0.01)
        assertEquals(6553.5, maxResult?.speed, 0.01)
        assertEquals(4294967.295, maxResult?.distance, 0.01)
        assertEquals(6553.5, maxResult?.current, 0.01)
        assertEquals(6553.5, maxResult?.temperature, 0.01)
        assertEquals(255, maxResult?.batteryLevel)
        assertTrue(maxResult?.isCharging == true) // At least one flag is set
        assertEquals(255.0, maxResult?.motorTemperature, 0.01)
    }

    @Test
    fun testDecodePacketType02() {
        // Test packet type 0x02 (alternative data format)
        val dataType02 = byteArrayOf(
            0x02.toByte(), // Packet type 0x02
            0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
            0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance (1000 km)
            0xE8.toByte(), 0x03.toByte(), // Current (100.0A)
            0x14.toByte(), 0x00.toByte(), // Temperature (2.0°C)
            0x64.toByte(), // Battery (100%)
            0x03.toByte(), // Status (charging + alarm)
            0x1E.toByte(), // Motor temperature (30°C)
            0x00.toByte(), 0x00.toByte() // Padding
        )

        val result = protocol.decode(dataType02)

        assertNotNull(result)
        assertEquals(36.0, result?.voltage, 0.01)
        assertEquals(30.0, result?.speed, 0.01)
        assertEquals(1000.0, result?.distance, 0.01)
        assertEquals(100.0, result?.current, 0.01)
        assertEquals(2.0, result?.temperature, 0.01)
        assertEquals(100, result?.batteryLevel)
        assertTrue(result?.isCharging == true)
        assertEquals(30.0, result?.motorTemperature, 0.01)
    }

    @Test
    fun testDecodePacketWithoutMotorTemperature() {
        // Test packet without motor temperature data (shorter packet)
        val dataNoMotor = byteArrayOf(
            0x01.toByte(), // Packet type
            0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
            0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // Distance (1000 km)
            0xE8.toByte(), 0x03.toByte(), // Current (100.0A)
            0x14.toByte(), 0x00.toByte(), // Temperature (2.0°C)
            0x64.toByte(), // Battery (100%)
            0x01.toByte() // Status (charging)
            // No motor temperature data
        )

        val result = protocol.decode(dataNoMotor)

        assertNotNull(result)
        assertEquals(36.0, result?.voltage, 0.01)
        assertEquals(30.0, result?.speed, 0.01)
        assertEquals(1000.0, result?.distance, 0.01)
        assertEquals(100.0, result?.current, 0.01)
        assertEquals(2.0, result?.temperature, 0.01)
        assertEquals(100, result?.batteryLevel)
        assertTrue(result?.isCharging == true)
        assertNull(result?.motorTemperature) // Should be null when no motor temp data
    }

    @Test
    fun testCreateCommand() {
        // Test light on command
        val lightOn = protocol.createCommand(CommandType.LIGHT_ON, 0)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x01), lightOn)

        // Test light off command
        val lightOff = protocol.createCommand(CommandType.LIGHT_OFF, 0)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x00), lightOff)

        // Test beep command
        val beep = protocol.createCommand(CommandType.BEEP, 0)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x02, 0x01), beep)

        // Test power off command
        val powerOff = protocol.createCommand(CommandType.POWER_OFF, 0)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x03, 0x01), powerOff)

        // Test light brightness command
        val brightness50 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 50)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x04, 0x7F.toByte()), brightness50)

        val brightness100 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 100)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x04, 0xFF.toByte()), brightness100)

        val brightness0 = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 0)
        assertArrayEquals(byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x04, 0x00.toByte()), brightness0)

        // Test invalid brightness (should return empty array)
        val invalidBrightness = protocol.createCommand(CommandType.LIGHT_BRIGHTNESS, 150)
        assertArrayEquals(byteArrayOf(), invalidBrightness)

        // (Prolly dumb) Test unsupported command

        //val unsupported = protocol.createCommand(CommandType.SPEED_LIMIT, 0)
        //assertArrayEquals(byteArrayOf(), unsupported)
    }

    @Test
    fun testIsDeviceReady() {
        // Test ready device
        val readyData = EUCData(
            speed = 5.0,
            voltage = 67.2,
            current = 2.5,
            temperature = 25.0,
            batteryLevel = 80,
            distance = 10.5,
            power = 168.0,
            timestamp = System.currentTimeMillis(),
            rawData = byteArrayOf(),
            manufacturer = "Gotway",
            model = "MSX",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = false,
            rideTime = 0,
            cellVoltages = null,
            motorTemperature = null
        )
        assertTrue(protocol.isDeviceReady(readyData))

        // Test device with low voltage
        val lowVoltageData = readyData.copy(voltage = 30.0)
        assertFalse(protocol.isDeviceReady(lowVoltageData))

        // Test device with high temperature
        val highTempData = readyData.copy(temperature = 80.0)
        assertFalse(protocol.isDeviceReady(highTempData))

        // Test device with very high temperature
        val veryHighTempData = readyData.copy(temperature = 90.0)
        assertFalse(protocol.isDeviceReady(veryHighTempData))

        // Test device with negative speed (should still be ready)
        val negativeSpeedData = readyData.copy(speed = -1.0)
        assertTrue(protocol.isDeviceReady(negativeSpeedData))

        // Test device with zero speed (should be ready)
        val zeroSpeedData = readyData.copy(speed = 0.0)
        assertTrue(protocol.isDeviceReady(zeroSpeedData))

        // Test device with minimum acceptable voltage
        val minVoltageData = readyData.copy(voltage = 45.1)
        assertTrue(protocol.isDeviceReady(minVoltageData))

        // Test device with maximum acceptable temperature
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
        // Test that service UUIDs are not empty
        val serviceUUID = protocol.getServiceUUID()
        val dataUUID = protocol.getDataCharacteristicUUID()
        assertNotNull(serviceUUID)
        assertNotNull(dataUUID)
        assertNotEquals("00000000-0000-0000-0000-000000000000", serviceUUID.toString())
        assertNotEquals("00000000-0000-0000-0000-000000000000", dataUUID.toString())
    }

    @Test
    fun testStatusFlagsParsing() {
        // Test different status flag combinations
        val chargingData = byteArrayOf(
            0x01.toByte(), // Packet type
            0x64.toByte(), 0x01.toByte(), // Voltage
            0x00.toByte(), 0x00.toByte(), // Speed
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // Distance
            0x00.toByte(), 0x00.toByte(), // Current
            0x00.toByte(), 0x00.toByte(), // Temperature
            0x64.toByte(), // Battery
            0x01.toByte(), // Status (charging flag only)
            0x00.toByte(), // Motor temperature
            0x00.toByte(), 0x00.toByte() // Padding
        )
        val chargingResult = protocol.decode(chargingData)
        assertTrue(chargingResult?.isCharging == true)

        val alarmData = byteArrayOf(
            0x01.toByte(), // Packet type
            0x64.toByte(), 0x01.toByte(), // Voltage
            0x00.toByte(), 0x00.toByte(), // Speed
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // Distance
            0x00.toByte(), 0x00.toByte(), // Current
            0x00.toByte(), 0x00.toByte(), // Temperature
            0x64.toByte(), // Battery
            0x02.toByte(), // Status (alarm flag only)
            0x00.toByte(), // Motor temperature
            0x00.toByte(), 0x00.toByte() // Padding
        )
        val alarmResult = protocol.decode(alarmData)
        assertFalse(alarmResult?.isCharging == true) // Only alarm flag, not charging

        val bothFlagsData = byteArrayOf(
            0x01.toByte(), // Packet type
            0x64.toByte(), 0x01.toByte(), // Voltage
            0x00.toByte(), 0x00.toByte(), // Speed
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // Distance
            0x00.toByte(), 0x00.toByte(), // Current
            0x00.toByte(), 0x00.toByte(), // Temperature
            0x64.toByte(), // Battery
            0x03.toByte(), // Status (both flags)
            0x00.toByte(), // Motor temperature
            0x00.toByte(), 0x00.toByte() // Padding
        )
        val bothFlagsResult = protocol.decode(bothFlagsData)
        assertTrue(bothFlagsResult?.isCharging == true) // Charging flag is set
    }
}

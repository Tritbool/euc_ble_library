package io.github.tritbool.euc.ble.models

import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertNull
import io.github.tritbool.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for EUCData model
 */
class EUCDataTest {

    @Test
    fun testEUCDataCreation() {
        // Test basic data creation
        val data = EUCData(
            speed = 25.5,
            voltage = 67.2,
            current = 10.5,
            temperature = 28.0,
            batteryLevel = 75,
            distance = 15.3,
            power = 705.6,
            pwm = 47.5,
            timestamp = 1234567890L,
            rawData = byteArrayOf(0x01, 0x02, 0x03),
            manufacturer = "KingSong",
            model = "KS-16X",
            serialNumber = "KS12345678",
            firmwareVersion = "1.2.3",
            isCharging = false,
            rideTime = 1800,
            cellVoltages = listOf(4.1, 4.2, 4.15, 4.18),
            motorTemperature = 35.0,
            pedalsMode = 2,
            alarmMode = 1,
            rollAngleMode = 0,
            usesMiles = false,
            autoPowerOffMinutes = 10,
            tiltBackSpeed = 45,
            ledMode = 3,
            lightMode = 1,
            alertFlags = 0x12,
            wheelAlarm = false,
        )

        assertEquals(25.5, data.speed, 0.01)
        assertEquals(67.2, data.voltage, 0.01)
        assertEquals(10.5, data.current, 0.01)
        assertEquals(28.0, data.temperature, 0.01)
        assertEquals(75, data.batteryLevel)
        assertEquals(15.3, data.distance, 0.01)
        assertEquals(705.6, data.power, 0.01)
        assertEquals(47.5, data.pwm ?: 0.0, 0.01)
        assertEquals(1234567890L, data.timestamp)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), data.rawData)
        assertEquals("KingSong", data.manufacturer)
        assertEquals("KS-16X", data.model)
        assertEquals("KS12345678", data.serialNumber)
        assertEquals("1.2.3", data.firmwareVersion)
        assertFalse(data.isCharging)
        assertEquals(1800L, data.rideTime)
        assertEquals(listOf(4.1, 4.2, 4.15, 4.18), data.cellVoltages)
        //assertEquals(35.0, data.motorTemperature?:0, 0.01)
        assertEquals(2, data.pedalsMode)
        assertEquals(1, data.alarmMode)
        assertEquals(0, data.rollAngleMode)
        assertEquals(false, data.usesMiles)
        assertEquals(10, data.autoPowerOffMinutes)
        assertEquals(45, data.tiltBackSpeed)
        assertEquals(3, data.ledMode)
        assertEquals(1, data.lightMode)
        assertEquals(0x12, data.alertFlags)
        assertEquals(false, data.wheelAlarm)
    }

    @Test
    fun testEUCDataWithNullValues() {
        // Test data with null optional values
        val data = EUCData(
            speed = 0.0,
            voltage = 0.0,
            current = 0.0,
            temperature = 0.0,
            batteryLevel = 0,
            distance = 0.0,
            power = 0.0,
            timestamp = 0L,
            rawData = byteArrayOf(),
            manufacturer = "Gotway",
            model = "MSX",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = false,
            rideTime = 0L,
            cellVoltages = null,
            motorTemperature = null,
        )

        assertEquals(0.0, data.speed, 0.01)
        assertEquals(0.0, data.voltage, 0.01)
        assertEquals(0.0, data.current, 0.01)
        assertEquals(0.0, data.temperature, 0.01)
        assertEquals(0, data.batteryLevel)
        assertEquals(0.0, data.distance, 0.01)
        assertEquals(0.0, data.power, 0.01)
        assertNull(data.pwm)
        assertEquals(0L, data.timestamp)
        assertArrayEquals(byteArrayOf(), data.rawData)
        assertEquals("Gotway", data.manufacturer)
        assertEquals("MSX", data.model)
        assertNull(data.serialNumber)
        assertNull(data.firmwareVersion)
        assertFalse(data.isCharging)
        assertEquals(0L, data.rideTime)
        assertNull(data.cellVoltages)
        assertNull(data.motorTemperature)
        assertNull(data.pedalsMode)
        assertNull(data.alarmMode)
        assertNull(data.rollAngleMode)
        assertNull(data.usesMiles)
        assertNull(data.autoPowerOffMinutes)
        assertNull(data.tiltBackSpeed)
        assertNull(data.ledMode)
        assertNull(data.lightMode)
        assertNull(data.alertFlags)
        assertNull(data.wheelAlarm)
    }

    @Test
    fun testEUCDataEquality() {
        // Test equality based on timestamp, manufacturer, and model
        val data1 = EUCData(
            speed = 25.0,
            voltage = 67.0,
            current = 10.0,
            temperature = 28.0,
            batteryLevel = 75,
            distance = 15.0,
            power = 700.0,
            timestamp = 1234567890L,
            rawData = byteArrayOf(0x01),
            manufacturer = "KingSong",
            model = "KS-16X",
            serialNumber = "KS123",
            firmwareVersion = "1.0",
            isCharging = false,
            rideTime = 1800,
            cellVoltages = listOf(4.1),
            motorTemperature = 35.0,
        )

        val data2 = EUCData(
            speed = 30.0, // Different speed
            voltage = 70.0, // Different voltage
            current = 15.0, // Different current
            temperature = 30.0, // Different temperature
            batteryLevel = 80, // Different battery level
            distance = 20.0, // Different distance
            power = 1000.0, // Different power
            timestamp = 1234567890L, // Same timestamp
            rawData = byteArrayOf(0x02), // Different raw data
            manufacturer = "KingSong", // Same manufacturer
            model = "KS-16X", // Same model
            serialNumber = "KS456", // Different serial
            firmwareVersion = "2.0", // Different firmware
            isCharging = true, // Different charging status
            rideTime = 3600, // Different ride time
            cellVoltages = listOf(4.2), // Different cell voltages
            motorTemperature = 40.0,
            // Different motor temperature
        )

        // Should be equal because timestamp, manufacturer, and model are the same
        assertEquals(data1, data2)
        assertEquals(data1.hashCode(), data2.hashCode())

        // Test inequality with different timestamp
        val data3 = data1.copy(timestamp = 1234567891L)
        assertNotEquals(data1, data3)

        // Test inequality with different manufacturer
        val data4 = data1.copy(manufacturer = "Gotway")
        assertNotEquals(data1, data4)

        // Test inequality with different model
        val data5 = data1.copy(model = "KS-18X")
        assertNotEquals(data1, data5)
    }

    @Test
    fun testEUCDataCopy() {
        // Test copy functionality
        val original = EUCData(
            speed = 25.5,
            voltage = 67.2,
            current = 10.5,
            temperature = 28.0,
            batteryLevel = 75,
            distance = 15.3,
            power = 705.6,
            timestamp = 1234567890L,
            rawData = byteArrayOf(0x01, 0x02),
            manufacturer = "KingSong",
            model = "KS-16X",
            serialNumber = "KS123",
            firmwareVersion = "1.2",
            isCharging = false,
            rideTime = 1800,
            cellVoltages = listOf(4.1, 4.2),
            motorTemperature = 35.0,
        )

        // Test copy with no changes
        val copy = original.copy()
        assertEquals(original, copy)

        // Test copy with changes
        val modifiedCopy = original.copy(
            speed = 30.0,
            batteryLevel = 80,
            isCharging = true
        )

        assertEquals(30.0, modifiedCopy.speed, 0.01)
        assertEquals(80, modifiedCopy.batteryLevel)
        assertTrue(modifiedCopy.isCharging)
        // Other fields should remain the same
        assertEquals(original.voltage, modifiedCopy.voltage, 0.01)
        assertEquals(original.timestamp, modifiedCopy.timestamp)
        assertEquals(original.manufacturer, modifiedCopy.manufacturer)
        assertEquals(original.model, modifiedCopy.model)
    }

    @Test
    fun testEUCDataEdgeCases() {
        // Test with maximum reasonable values
        val maxData = EUCData(
            speed = 100.0, // Very high speed
            voltage = 100.0, // High voltage
            current = 100.0, // High current
            temperature = 100.0, // High temperature
            batteryLevel = 100, // Full battery
            distance = 10000.0, // Long distance
            power = 10000.0, // High power
            timestamp = Long.MAX_VALUE, // Max timestamp
            rawData = ByteArray(100), // Large raw data
            manufacturer = "Test",
            model = "TestModel",
            serialNumber = "A".repeat(100), // Long serial
            firmwareVersion = "V".repeat(50), // Long firmware
            isCharging = true,
            rideTime = Long.MAX_VALUE, // Max ride time
            cellVoltages = List(20) { 4.2 }, // Many cells
            motorTemperature = 100.0,
            // High motor temp
        )

        assertEquals(100.0, maxData.speed, 0.01)
        assertEquals(100.0, maxData.voltage, 0.01)
        assertEquals(100.0, maxData.current, 0.01)
        assertEquals(100.0, maxData.temperature, 0.01)
        assertEquals(100, maxData.batteryLevel)
        assertEquals(10000.0, maxData.distance, 0.01)
        assertEquals(10000.0, maxData.power, 0.01)
        assertEquals(Long.MAX_VALUE, maxData.timestamp)
        assertEquals(100, maxData.rawData.size)
        assertEquals("Test", maxData.manufacturer)
        assertEquals("TestModel", maxData.model)
        assertEquals("A".repeat(100), maxData.serialNumber)
        assertEquals("V".repeat(50), maxData.firmwareVersion)
        assertTrue(maxData.isCharging)
        assertEquals(Long.MAX_VALUE, maxData.rideTime)
        assertEquals(20, maxData.cellVoltages?.size)
        assertEquals(100.0, maxData.motorTemperature?:0.0, 0.01)

        // Test with minimum values
        val minData = EUCData(
            speed = 0.0,
            voltage = 0.0,
            current = 0.0,
            temperature = 0.0,
            batteryLevel = 0,
            distance = 0.0,
            power = 0.0,
            timestamp = 0,
            rawData = byteArrayOf(),
            manufacturer = "",
            model = "",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = false,
            rideTime = 0,
            cellVoltages = emptyList(),
            motorTemperature = null,
        )

        assertEquals(0.0, minData.speed, 0.01)
        assertEquals(0.0, minData.voltage, 0.01)
        assertEquals(0.0, minData.current, 0.01)
        assertEquals(0.0, minData.temperature, 0.01)
        assertEquals(0, minData.batteryLevel)
        assertEquals(0.0, minData.distance, 0.01)
        assertEquals(0.0, minData.power, 0.01)
        assertEquals(0L, minData.timestamp)
        assertEquals(0, minData.rawData.size)
        assertEquals("", minData.manufacturer)
        assertEquals("", minData.model)
        assertNull(minData.serialNumber)
        assertNull(minData.firmwareVersion)
        assertFalse(minData.isCharging)
        assertEquals(0L, minData.rideTime)
        assertEquals(0, minData.cellVoltages?.size)
        assertNull(minData.motorTemperature)
    }

    @Test
    fun testEUCDataToString() {
        // Test that toString() doesn't throw exceptions
        val data = EUCData(
            speed = 25.5,
            voltage = 67.2,
            current = 10.5,
            temperature = 28.0,
            batteryLevel = 75,
            distance = 15.3,
            power = 705.6,
            timestamp = 1234567890L,
            rawData = byteArrayOf(0x01, 0x02),
            manufacturer = "KingSong",
            model = "KS-16X",
            serialNumber = "KS123",
            firmwareVersion = "1.2",
            isCharging = false,
            rideTime = 1800,
            cellVoltages = listOf(4.1, 4.2),
            motorTemperature = 35.0,
        )

        val stringRepresentation = data.toString()
        assertTrue(stringRepresentation.contains("KingSong"))
        assertTrue(stringRepresentation.contains("KS-16X"))
        assertTrue(stringRepresentation.contains("25.5"))
    }

    @Test
    fun testEUCDataWithNegativeValues() {
        // Test with negative values where applicable
        val negativeData = EUCData(
            speed = -5.0, // Negative speed (going backwards)
            voltage = 50.0,
            current = -2.0, // Negative current (regenerative braking)
            temperature = -10.0, // Negative temperature (cold)
            batteryLevel = 50,
            distance = 10.0,
            power = -100.0, // Negative power (regenerative)
            timestamp = 1234567890L,
            rawData = byteArrayOf(),
            manufacturer = "Test",
            model = "TestModel",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = false,
            rideTime = 600,
            cellVoltages = listOf(3.8, 3.9),
            motorTemperature = -5.0,
            // Cold motor
        )

        assertEquals(-5.0, negativeData.speed, 0.01)
        assertEquals(50.0, negativeData.voltage, 0.01)
        assertEquals(-2.0, negativeData.current, 0.01)
        assertEquals(-10.0, negativeData.temperature, 0.01)
        assertEquals(50, negativeData.batteryLevel)
        assertEquals(10.0, negativeData.distance, 0.01)
        assertEquals(-100.0, negativeData.power, 0.01)
        assertEquals(-5.0, negativeData.motorTemperature?:0.0, 0.01)
    }

    @Test
    fun testEUCDataAngleField() {
        // Test angle field with a value
        val dataWithAngle = EUCData(
            speed = 20.0,
            voltage = 67.0,
            current = 5.0,
            temperature = 30.0,
            batteryLevel = 80,
            distance = 5.0,
            power = 335.0,
            timestamp = 1234567890L,
            rawData = byteArrayOf(),
            manufacturer = "InMotion",
            model = "V12",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = false,
            rideTime = 600,
            cellVoltages = null,
            motorTemperature = null,
            angle = 3.5
        )

        assertEquals(3.5, dataWithAngle.angle ?: 0.0, 0.01)

        // Test angle null by default
        val dataNoAngle = EUCData(
            speed = 20.0,
            voltage = 67.0,
            current = 5.0,
            temperature = 30.0,
            batteryLevel = 80,
            distance = 5.0,
            power = 335.0,
            timestamp = 1234567890L,
            rawData = byteArrayOf(),
            manufacturer = "Test",
            model = "TestModel",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = false,
            rideTime = 600,
            cellVoltages = null,
            motorTemperature = null,
        )

        assertNull(dataNoAngle.angle)

        // Test negative angle (tilted backward)
        val dataNegativeAngle = EUCData(
            speed = 20.0,
            voltage = 67.0,
            current = 5.0,
            temperature = 30.0,
            batteryLevel = 80,
            distance = 5.0,
            power = 335.0,
            timestamp = 1234567890L,
            rawData = byteArrayOf(),
            manufacturer = "Test",
            model = "TestModel",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = false,
            rideTime = 600,
            cellVoltages = null,
            motorTemperature = null,
            angle = -2.5
        )

        assertEquals(-2.5, dataNegativeAngle.angle ?: 0.0, 0.01)
    }
}

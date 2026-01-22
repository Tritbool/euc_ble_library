// File: `euc-ble-core/src/test/java/com/euc/ble/protocols/GotwayProtocolTest.kt`
package com.euc.ble.protocols

import com.euc.ble.models.EUCDevice
import com.euc.ble.models.EUCData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GotwayProtocolTest {

    private val protocol = GotwayProtocol()

    @Before
    fun setUp() {
        // Initialise l'environnement mock (présumé fourni dans le projet)
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
            name = "OtherBrand",
            manufacturerId = 0x1234
        )
        assertFalse(protocol.canHandle(nonGotway))

        val mtenDevice = MockBLEUtils.createMockDevice(
            name = "Mten3",
            manufacturerId = 0x0000
        )
        assertTrue(protocol.canHandle(mtenDevice))

        val nikolaDevice = EUCDevice(
            name = "Nikola Plus",
            address = "00:11:22:33:44:55",
            rssi = -50,
            manufacturerId = 0
        )
        assertTrue(protocol.canHandle(nikolaDevice))
    }

    @Test
    fun testDecodeValidPacket_typeA_compact() {
        // Compact Type A (no header) - little endian fields per parseTypeA
        // voltageRaw=356 -> 35.6V ; speedRaw=300 -> 30.0 ; distance=1000 ; currentRaw=1000 -> 100.0
        // tempRaw=20 -> 2.0 ; battery=100 ; status=0x01 (charging) ; motorRaw=30 -> motorTemperature=3.0
        val data = byteArrayOf(
            0x00,
            0x64.toByte(), 0x01.toByte(),       // voltageRaw LE = 356 -> 35.6
            0x2C.toByte(), 0x01.toByte(),       // speedRaw LE = 300 -> 30.0
            0xE8.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(), // distance LE = 1000
            0xE8.toByte(), 0x03.toByte(),       // currentRaw LE = 1000 -> 100.0
            0x14.toByte(), 0x00.toByte(),       // tempRaw LE = 20 -> 2.0
            0x64.toByte(),                       // battery = 100
            0x01.toByte(),                       // status (bit0 -> charging)
            0x1E.toByte()                        // motorRaw = 30 -> motorTemperature = 3.0
        )

        val result = protocol.decode(data)
        assertNotNull(result)
        assertEquals(35.6, result?.voltage ?: Double.MAX_VALUE, 0.01)
        assertEquals(30.0, result?.speed ?: Double.MAX_VALUE, 0.01)
        assertEquals(1000.0, result?.distance ?: Double.MAX_VALUE, 0.01)
        assertEquals(100.0, result?.current ?: Double.MAX_VALUE, 0.01)
        assertEquals(2.0, result?.temperature ?: Double.MAX_VALUE, 0.01)
        assertEquals(100, result?.batteryLevel)
        assertTrue(result?.isCharging == true)
        // motorTemperature = 30 / 10 = 3.0 (implémentation actuelle utilise signed byte /10)
        assertEquals(3.0, result?.motorTemperature ?: Double.MAX_VALUE, 0.01)
        assertEquals("Gotway", result?.manufacturer)
    }

    @Test
    fun testDecodeInvalidPacket() {
        val shortData = byteArrayOf(0x01.toByte())
        val shortResult = protocol.decode(shortData)
        assertNull(shortResult)

        val invalidType = byteArrayOf(0x02.toByte(), 0x00.toByte(), 0x01.toByte())
        val invalidTypeResult = protocol.decode(invalidType)
        // bare 0x02 without header isn't handled by current implementation -> null
        assertNull(invalidTypeResult)

        val emptyResult = protocol.decode(byteArrayOf())
        assertNull(emptyResult)
    }

    @Test
    fun testDecodeEdgeCases_typeA_zero_and_max() {
        // zero-filled type A (compact)
        val zeroData = byteArrayOf(
            0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x00,
            0x00
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

        // max-values type A (compact) - use max for unsigned short / int where applicable
        val maxData = byteArrayOf(
            0x00,
            0xFF.toByte(), 0xFF.toByte(),       // voltageRaw = 65535 -> 6553.5 V (/10)
            0xFF.toByte(), 0xFF.toByte(),       // speedRaw = 65535 -> 6553.5
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // distance = 0xFFFFFFFF
            0xFF.toByte(), 0x7F.toByte(),       // currentRaw signed short = 32767 -> 3276.7 (/10)
            0xFF.toByte(), 0x7F.toByte(),       // tempRaw signed short = 32767 -> 3276.7 (/10)
            0xFF.toByte(),                       // battery = 255 -> coerced to 100
            0x01.toByte(),                       // status (charging)
            0x7F.toByte()                        // motorRaw = 127 -> 12.7
        )
        val maxResult = protocol.decode(maxData)
        assertNotNull(maxResult)
        assertEquals(6553.5, maxResult?.voltage ?: Double.MAX_VALUE, 0.01)
        assertEquals(6553.5, maxResult?.speed ?: Double.MAX_VALUE, 0.01)
        assertEquals(4294967295.0, maxResult?.distance ?: Double.MAX_VALUE, 0.01)
        assertEquals(3276.7, maxResult?.current ?: Double.MAX_VALUE, 0.01)
        assertEquals(3276.7, maxResult?.temperature ?: Double.MAX_VALUE, 0.01)
        assertEquals(100, maxResult?.batteryLevel) // coerced
        assertTrue(maxResult?.isCharging == true)
        assertEquals(12.7, maxResult?.motorTemperature ?: Double.MAX_VALUE, 0.01)
    }

    @Test
    fun testDecodePacketType_legacy_with_header() {
        // Headered legacy frame (big endian fields per parseLegacy)
        // voltageRaw BE = 3560 -> 35.6 (/100)
        // speedRaw BE = 3000 -> 30.0 (/100)
        // distance BE = 1000
        // currentRaw BE = 10000 -> 100.0 (/100)
        // tempRaw BE = 200 -> 2.0 (/100) -> low byte becomes 0xC8 (200) -> battery read at index 13 => 200 -> coerced to 100
        val legacy = ByteArray(20) { 0x00.toByte() }
        legacy[0] = 0x55.toByte()
        legacy[1] = 0xAA.toByte()
        // voltageRaw BE @2..3 = 3560 -> 0x0D E8
        legacy[2] = 0x0D.toByte()
        legacy[3] = 0xE8.toByte()
        // speedRaw BE @4..5 = 3000 -> 0x0B B8
        legacy[4] = 0x0B.toByte()
        legacy[5] = 0xB8.toByte()
        // distance BE @6..9 = 1000 -> 0x00 00 03 E8
        legacy[6] = 0x00.toByte()
        legacy[7] = 0x00.toByte()
        legacy[8] = 0x03.toByte()
        legacy[9] = 0xE8.toByte()
        // current BE @10..11 = 10000 -> 0x27 10
        legacy[10] = 0x27.toByte()
        legacy[11] = 0x10.toByte()
        // temp BE @12..13 = 200 -> 0x00 C8 (battery will read index 13 = 0xC8 -> 200 -> coerced to 100)
        legacy[12] = 0x00.toByte()
        legacy[13] = 0xC8.toByte()
        // status @14 = 0x01 (charging)
        legacy[14] = 0x01.toByte()
        // frameType @18 != 0x00/0x04 -> triggers parseLegacy
        legacy[18] = 0x10.toByte()

        val result = protocol.decode(legacy)
        assertNotNull(result)
        assertEquals(35.6, result?.voltage ?: Double.MAX_VALUE, 0.01)
        assertEquals(30.0, result?.speed ?: Double.MAX_VALUE, 0.01)
        assertEquals(1000.0, result?.distance ?: Double.MAX_VALUE, 0.01)
        assertEquals(100.0, result?.current ?: Double.MAX_VALUE, 0.01)
        assertEquals(2.0, result?.temperature ?: Double.MAX_VALUE, 0.01)
        // battery coerced to 100
        assertEquals(100, result?.batteryLevel)
        assertTrue(result?.isCharging == true)
    }

    @Test
    fun testDecodePacketWithoutMotorTemperature_typeA() {
        // Type A compact but truncated: no byte at index 15 -> motorTemperature should be null
        val dataNoMotor = byteArrayOf(
            0x00,
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0xE8.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte()
            // length = 15 -> index 15 missing
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
    fun testStatusFlagsParsing() {
        // status bit0 => charging
        val charging = byteArrayOf(
            0x00,
            0x64.toByte(), 0x01.toByte(),
            0x2C.toByte(), 0x01.toByte(),
            0xE8.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xE8.toByte(), 0x03.toByte(),
            0x14.toByte(), 0x00.toByte(),
            0x64.toByte(),
            0x01.toByte(),
            0x00.toByte()
        )
        // set status at index 14 to 0x01
        charging[14] = 0x01.toByte()
        val chargingResult = protocol.decode(charging)
        assertTrue(chargingResult?.isCharging == true)

        // no charging flag
        val noCharging = charging.clone()
        noCharging[14] = 0x00.toByte()
        val noChargingResult = protocol.decode(noCharging)
        assertFalse(noChargingResult?.isCharging == true)

        // both flags (whatever second bit means) still charging if bit0 set
        val both = charging.clone()
        both[14] = 0x03.toByte()
        val bothResult = protocol.decode(both)
        assertTrue(bothResult?.isCharging == true)
    }

    @Test
    fun testParseTypeBFrame() {
        // First byte 0x04 triggers parseTypeB
        val dataB = byteArrayOf(
            0x04,
            0x64.toByte(), 0x01.toByte(),       // voltageRaw LE = 356 -> 35.6
            0x2C.toByte(), 0x01.toByte(),       // speedRaw LE = 300 -> 30.0
            0xE8.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(), // distance LE = 1000
            0xE8.toByte(), 0x03.toByte(),       // currentRaw LE = 1000 -> 100.0
            0x14.toByte(), 0x00.toByte(),       // tempRaw LE = 20 -> 2.0
            0x64.toByte(),                       // battery = 100
            0x01.toByte(),                       // status
            0x1E.toByte()                        // motorRaw = 30 -> 3.0
        )

        val resB = protocol.decode(dataB)
        assertNotNull(resB)
        assertEquals("Gotway B", resB?.model)
        assertEquals(35.6, resB?.voltage ?: 0.0, 0.01)
        assertTrue(resB?.isCharging == true)
    }
}

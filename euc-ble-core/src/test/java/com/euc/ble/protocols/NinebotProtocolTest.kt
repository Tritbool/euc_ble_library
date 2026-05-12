package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.models.EUCDevice
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue
import org.junit.jupiter.api.Test
class NinebotProtocolTest {

    @Test
    fun canHandleNinebotIdentifiersAndNames() {
        val protocol = NinebotProtocol()
        val byManufacturer = EUCDevice(name = "Unknown", address = "A", manufacturerId = BLEConstants.MANUFACTURER_NINEBOT, rssi = -45)
        val byName = EUCDevice(name = "Ninebot Z10", address = "B", manufacturerId = 0, rssi = -60)
        val bySegwayName = EUCDevice(name = "Segway Z8", address = "C", manufacturerId = 0, rssi = -70)
        val other = EUCDevice(name = "KS-16X", address = "D", manufacturerId = BLEConstants.MANUFACTURER_KINGSONG, rssi = -55)

        assertTrue(protocol.canHandle(byManufacturer))
        assertTrue(protocol.canHandle(byName))
        assertTrue(protocol.canHandle(bySegwayName))
        assertEquals(false, protocol.canHandle(other))
        protocol.close()
    }

    @Test
    fun decodeWheelLogStyleFrameProducesTelemetry() {
        val protocol = NinebotProtocol()
        val frame = byteArrayOf(
            0x55.toByte(),
            0x18.toByte(),
            0x01.toByte(),
            0x01.toByte(),
            0xD0.toByte(), 0x1A.toByte(), // 68.64V
            0xFE.toByte(), 0x00.toByte(), // 2.54 km/h
            0x40.toByte(), 0x42.toByte(), 0x0F.toByte(), 0x00.toByte(), // 1000.0 km
            0x2C.toByte(), 0x01.toByte(), // 3.0A
            0xA6.toByte(), 0x09.toByte(), // 24.7C
            0x48.toByte(), // 72%
            0x01.toByte(), // charging
            0xC8.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), // 200s ride time
            0x00.toByte() // checksum placeholder
        )

        val decoded = protocol.decode(frame)
        assertNotNull(decoded)
        assertEquals("Ninebot", decoded?.manufacturer)
        assertEquals(68.64, decoded?.voltage ?: 0.0, 0.01)
        assertEquals(2.54, decoded?.speed ?: 0.0, 0.01)
        assertEquals(72, decoded?.batteryLevel)
        assertEquals(true, decoded?.isCharging)
        assertEquals(200L, decoded?.rideTime)
        protocol.close()
    }

    @Test
    fun createCommandSupportsCommonControlCommands() {
        val protocol = NinebotProtocol()
        val lightOn = protocol.createCommand(CommandType.LIGHT_ON, Unit)
        val lock = protocol.createCommand(CommandType.LOCK, Unit)
        val unsupported = protocol.createCommand(CommandType.REQUEST_FIRMWARE, Unit)

        assertTrue(lightOn.isNotEmpty())
        assertTrue(lock.isNotEmpty())
        assertEquals(true, unsupported.isEmpty())
        protocol.close()
    }
}

package com.euc.ble.protocols

import com.euc.ble.core.ByteUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMotionProtocolTest {

    @Test
    fun decodeV9LegacyVectorMatchesExpectedValues() {
        val protocol = InMotionProtocol()

        val packets = listOf(
            "aaaa11088201020c0101010095",
            "aaaa11178202413134323139353041303030343635460000000000fd",
            "aaaa11388206222800040719000802212600080101000902230a0004010a0002012401000102010001012501000102010001012f0500050101000000b8",
            "aaaa142ca0202a000000071900089411a00f9511000058020064641a020a28646428d0071e32010001012501053015009c",
            "aaaa142b900001162617000000c59d4980520367003100cdc9c9c9060000005d0000000000000044000000ca010000cf",
            "aaaa14199191620000c1a216008bc301006ffe000037890200ffffd5fe55",
            "aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f"
        )

        val decoded = packets
            .map { protocol.decode(ByteUtils.hexToBytes(it)) }
            .lastOrNull { it != null }
            ?: fail("Expected realtime telemetry frame to decode")

        assertEquals("InMotion", decoded.manufacturer)
        assertEquals("InMotion V9", decoded.model)
        assertEquals("A1421950A000465F", decoded.serialNumber)
        assertEquals("Main:1.8.38 Drv:7.4.40 BLE:1.4.10", decoded.firmwareVersion)

        assertEquals(0.0, decoded.speed, 0.01)
        assertEquals(77.42, decoded.voltage, 0.01)
        assertEquals(0.12, decoded.current, 0.01)
        assertEquals(29.0, decoded.temperature, 0.01)
        assertEquals(25.0, decoded.motorTemperature ?: -1.0, 0.01)
        assertEquals(58, decoded.batteryLevel)
        assertEquals(0.06, decoded.distance, 0.01)
        assertEquals(252.33, decoded.totalDistance ?: -1.0, 0.01)
    }

    @Test
    fun decodeSkipsBadChecksumAndResyncsOnNextValidFrame() {
        val protocol = InMotionProtocol()

        val valid = ByteUtils.hexToBytes("aaaa11088201020c0101010095")
        val invalid = valid.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }

        // bad checksum should be ignored
        val first = protocol.decode(invalid)
        assertEquals(null, first)

        // then valid packet should be accepted
        val second = protocol.decode(valid)
        assertEquals(null, second) // main-info packet only updates state
    }

    @Test
    fun decodeCanExtractMultipleFramesFromSingleChunk() {
        val protocol = InMotionProtocol()

        val chunk = ByteUtils.hexToBytes(
            "aaaa11088201020c0101010095aaaa11178202413134323139353041303030343635460000000000fd"
        )

        protocol.decode(chunk)

        // Send versions + realtime to verify previous state was retained from concatenated chunk
        protocol.decode(
            ByteUtils.hexToBytes("aaaa11388206222800040719000802212600080101000902230a0004010a0002012401000102010001012501000102010001012f0500050101000000b8")
        )
        val data = protocol.decode(
            ByteUtils.hexToBytes("aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f")
        )

        assertNotNull(data)
        assertEquals("A1421950A000465F", data?.serialNumber)
        assertTrue((data?.firmwareVersion ?: "").contains("Main:1.8.38"))
    }
}

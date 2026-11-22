/*
 *
 *  Copyright (C) 2026 Gauthier Le Bartz Lyan (Tritbool)
 *  euc_ble_library — version 1.2.0 — licence GPL-3.0
 *  https://github.com/Tritbool/euc_ble_library
 * /
 */

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucCrc32
import io.github.tritbool.euc.ble.core.EucDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Reading directions verified against three independent sources that agree:
 * WheelLog's VeteranAdapter, the eucplanet Lynx-S captures and the loeuc firmware readout.
 */
class LeaperkimTelemetryReadbackTest {
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 100_000L
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }

    private fun engine() = LeaperkimSyncProtocol(clock)

    private fun telemetry(alarmRaw: Int? = null, tiltbackRaw: Int? = null): ProtocolOutput.Telemetry? {
        val engine = engine()
        val frame = ByteArray(40)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A
        frame[2] = 0x5C
        frame[3] = 36
        frame[4] = 0x30
        frame[5] = 0xED.toByte()
        alarmRaw?.let {
            frame[24] = (it shr 8).toByte()
            frame[25] = it.toByte()
        }
        tiltbackRaw?.let {
            frame[26] = (it shr 8).toByte()
            frame[27] = it.toByte()
        }
        engine.onNotification(frame)
        return engine.pollOutput() as? ProtocolOutput.Telemetry
    }

    @Test
    fun speedThresholdsAreTenthsOfKilometresPerHour() {
        val alarm20 = telemetry(alarmRaw = 200)?.data
        val tiltback30 = telemetry(tiltbackRaw = 300)?.data
        assertEquals(20, alarm20?.alarm1Speed)
        assertEquals(30, tiltback30?.tiltBackSpeed)
    }

    @Test
    fun zeroAndBeyondCeilingThresholdsStayNull() {
        assertNull(telemetry(alarmRaw = 0)?.data?.alarm1Speed)
        assertNull(telemetry(tiltbackRaw = 0)?.data?.tiltBackSpeed)
        // 200 km/h is the firmware's "disabled" marker territory, not a threshold.
        assertNull(telemetry(alarmRaw = 2000)?.data?.alarm1Speed)
        assertNull(telemetry(tiltbackRaw = 2000)?.data?.tiltBackSpeed)
    }

    @Test
    fun motorTemperatureFromPatchedFirmwareOffsets() {
        val engine = engine()
        engine.onNotification(longFrame { frame ->
            frame[40] = (5500 shr 8).toByte()
            frame[41] = 5500.toByte()
        })
        val data = (engine.pollOutput() as? ProtocolOutput.Telemetry)?.data
        assertEquals(55.0, data?.motorTemperature)
    }

    @Test
    fun stockSentinelNoiseStaysOutOfMotorTemperature() {
        val engine = engine()
        // 0x80 sentinels decode as -326.4 C, outside the firmware's own measuring range.
        engine.onNotification(longFrame { frame ->
            frame[40] = 0x80.toByte()
            frame[41] = 0x80.toByte()
        })
        val data = (engine.pollOutput() as? ProtocolOutput.Telemetry)?.data
        assertNull(data?.motorTemperature)
    }

    @Test
    fun mosfetTemperatureLatchesFromPage0AndStampsEveryFrame() {
        val engine = engine()
        engine.onNotification(longFrame { frame ->
            frame[46] = 0            // page 0: the currents page that carries the MOS word
            frame[59] = (4523 shr 8).toByte()
            frame[60] = 4523.toByte()
        })
        val fromPage0 = (engine.pollOutput() as? ProtocolOutput.Telemetry)?.data
        assertEquals(45.23, fromPage0?.mosfetTemperature)

        val bare = ByteArray(40)
        bare[0] = 0xDC.toByte()
        bare[1] = 0x5A
        bare[2] = 0x5C
        bare[3] = 36
        bare[4] = 0x30
        bare[5] = 0xED.toByte()
        engine.onNotification(bare)
        val fromShortFrame = (engine.pollOutput() as? ProtocolOutput.Telemetry)?.data
        assertEquals(45.23, fromShortFrame?.mosfetTemperature)
    }

    @Test
    fun adcFramesDoNotFeedTheMosfetLatch() {
        val engine = engine()
        engine.onNotification(longFrame(len = 0x5F) { frame ->
            frame[46] = 0
            frame[59] = (4523 shr 8).toByte()
            frame[60] = 4523.toByte()
        })
        // The 0x5F page carries no telemetry, so nothing may be published from it.
        assertNull(engine.pollOutput())
        val bare = ByteArray(40)
        bare[0] = 0xDC.toByte()
        bare[1] = 0x5A
        bare[2] = 0x5C
        bare[3] = 36
        bare[4] = 0x30
        bare[5] = 0xED.toByte()
        engine.onNotification(bare)
        val data = (engine.pollOutput() as? ProtocolOutput.Telemetry)?.data
        assertNull(data?.mosfetTemperature)
    }

    private inline fun longFrame(len: Int = 0x50, build: (ByteArray) -> Unit = { }): ByteArray {
        val frame = ByteArray(len + 4)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A
        frame[2] = 0x5C
        frame[3] = len.toByte()
        frame[4] = 0x30
        frame[5] = 0xED.toByte()
        frame[28] = (5123 shr 8).toByte()
        frame[29] = (5123 and 0xFF).toByte()    // version raw 5123: mVer 5, a smart-BMS Lynx-class wheel
        build(frame)
        val crc = EucCrc32.calculate(frame, 0, len)
        frame[len] = ((crc ushr 24) and 0xFF).toByte()
        frame[len + 1] = ((crc ushr 16) and 0xFF).toByte()
        frame[len + 2] = ((crc ushr 8) and 0xFF).toByte()
        frame[len + 3] = crc.toByte()
        return frame
    }
}

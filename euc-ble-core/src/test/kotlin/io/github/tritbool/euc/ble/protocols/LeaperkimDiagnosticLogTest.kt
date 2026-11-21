/*
 *
 *  Copyright (C) 2026 Gauthier Le Bartz Lyan (Tritbool)
 *  euc_ble_library — version 1.2.0 — licence GPL-3.0
 *  https://github.com/Tritbool/euc_ble_library
 * /
 */

package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.ByteUtils
import io.github.tritbool.euc.ble.core.EucClock
import io.github.tritbool.euc.ble.core.EucCrc32
import io.github.tritbool.euc.ble.core.EucDateTime
import io.github.tritbool.euc.ble.models.DiagnosticLogFailure
import io.github.tritbool.euc.ble.models.DiagnosticLogState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeaperkimDiagnosticLogTest {
    private val timerId = "leaperkim.diagnostic-log"
    private val clock = object : EucClock {
        override fun currentTimeMillis() = 100_000L
        override fun localDateTime() = EucDateTime(2026, 1, 1, 0, 0, 0)
    }

    private fun engine() = LeaperkimSyncProtocol(clock)

    /** Start request: CMD_READ_LOG + CMD_READ_LOG_NEW, each CRC32-wrapped, as the app sends them. */
    @Test
    fun startRequestMatchesCommandIdFrames() {
        val start = engine().createCommand(CommandType.REQUEST_DIAGNOSTIC_LOG, CommandValue.None)
        assertArrayEquals(
            ByteUtils.hexToBytes(
                "4c6b4170140180808080808080808001e53c2970" +
                    "4c6441701401008080808080808080 0157b1e3ec".replace(" ", "")
            ),
            start
        )
    }

    /** Stop request: same frames with the cursor parking value `0xFF`. */
    @Test
    fun stopRequestParksTheCursor() {
        val engine = engine()
        engine.createCommand(CommandType.REQUEST_DIAGNOSTIC_LOG, CommandValue.None)
        val stop = engine.createCommand(CommandType.CANCEL_DIAGNOSTIC_LOG, CommandValue.None)
        assertArrayEquals(
            ByteUtils.hexToBytes(
                "4c6b41701401808080808080808080ffbf39f66b" +
                    "4c6441701401008080808080808080ff0db43cf7"
            ),
            stop
        )
    }

    @Test
    fun page33RecordAndEndMarkerCompleteTheLog() {
        val engine = engine()
        engine.createCommand(CommandType.REQUEST_DIAGNOSTIC_LOG, CommandValue.None)
        assertEquals(DiagnosticLogState.OPENING, engine.getDiagnosticLogTransfer()?.state)

        engine.onNotification(page33RecordFrame())
        assertEquals(DiagnosticLogState.READING, engine.getDiagnosticLogTransfer()?.state)

        engine.onNotification(page33EndMarkerFrame())
        val snapshot = engine.getDiagnosticLogTransfer()
        assertEquals(DiagnosticLogState.COMPLETED, snapshot?.state)
        val dump = snapshot?.copyData()?.decodeToString()
        assertNotNull(dump)
        assertTrue(dump!!.startsWith("#5/10 "), dump)
        assertTrue(dump.contains(" code=33 "), dump)
        assertTrue(dump.contains(" values=[0,-1] text=mTest\n"), dump)
        assertEquals(1, dump.lines().count { it.startsWith("#") })
    }

    @Test
    fun silenceWithoutRecordsFailsWithTimeout() {
        val engine = engine()
        engine.createCommand(CommandType.REQUEST_DIAGNOSTIC_LOG, CommandValue.None)
        engine.onTimer(timerId)
        val snapshot = engine.getDiagnosticLogTransfer()
        assertEquals(DiagnosticLogState.FAILED, snapshot?.state)
        assertEquals(DiagnosticLogFailure.TIMEOUT, snapshot?.failure)
    }

    @Test
    fun silenceAfterRecordsCompletesTheLog() {
        val engine = engine()
        engine.createCommand(CommandType.REQUEST_DIAGNOSTIC_LOG, CommandValue.None)
        engine.onNotification(page33RecordFrame())
        engine.onTimer(timerId)
        assertEquals(DiagnosticLogState.COMPLETED, engine.getDiagnosticLogTransfer()?.state)
    }

    @Test
    fun cancelStopsAnActiveTransfer() {
        val engine = engine()
        engine.createCommand(CommandType.REQUEST_DIAGNOSTIC_LOG, CommandValue.None)
        val cancel = engine.createCommand(CommandType.CANCEL_DIAGNOSTIC_LOG, CommandValue.None)
        assertEquals(DiagnosticLogState.CANCELLED, engine.getDiagnosticLogTransfer()?.state)
        assertTrue(cancel.isNotEmpty())
        assertNull(engine.getDiagnosticLogTransfer()?.copyData())
    }

    @Test
    fun resetSessionMarksAnActiveTransferFailed() {
        val engine = engine()
        engine.createCommand(CommandType.REQUEST_DIAGNOSTIC_LOG, CommandValue.None)
        engine.resetSession()
        assertEquals(DiagnosticLogState.FAILED, engine.getDiagnosticLogTransfer()?.state)
        assertEquals(DiagnosticLogFailure.SESSION_RESET, engine.getDiagnosticLogTransfer()?.failure)
    }

    /** Tiltback speed write: LdAp frame, 21 km/h (Lynx S capture, slider value 0x15). */
    @Test
    fun setSpeedLimitBytesMatchCapture() {
        val bytes = engine().createCommand(CommandType.SET_SPEED_LIMIT, CommandValue.Integer(21))
        assertArrayEquals(
            ByteUtils.hexToBytes("4c644170110102808080808015f77248c6"),
            bytes
        )
    }

    /** Alarm speed write: LkAp frame, 20 km/h (Lynx S capture, slider value 0x14). */
    @Test
    fun setAlarmSpeedBytesMatchCapture() {
        val bytes = engine().createCommand(CommandType.SET_ALARM_SPEED, CommandValue.Integer(20))
        assertArrayEquals(
            ByteUtils.hexToBytes("4c6b4170110180808080808014ed681cfa"),
            bytes
        )
    }

    @Test
    fun speedSettingsRequireAReadbackBeforeWriting() {
        val engine = engine()
        assertNotNull(engine.commandStateError(CommandType.SET_SPEED_LIMIT.name))
        assertNotNull(engine.commandStateError(CommandType.SET_ALARM_SPEED.name))

        engine.onNotification(telemetryFrame(alarm = 200, tiltback = 210))
        assertNull(engine.commandStateError(CommandType.SET_SPEED_LIMIT.name))
        assertNull(engine.commandStateError(CommandType.SET_ALARM_SPEED.name))
    }

    private fun telemetryFrame(alarm: Int, tiltback: Int): ByteArray = ByteArray(40).apply {
        this[0] = 0xDC.toByte()
        this[1] = 0x5A
        this[2] = 0x5C
        this[3] = 36
        this[4] = 0x30
        this[5] = 0xED.toByte()
        this[24] = (alarm shr 8).toByte()
        this[25] = alarm.toByte()
        this[26] = (tiltback shr 8).toByte()
        this[27] = tiltback.toByte()
    }

    /** A realistic page-33 frame: total 10 records, cursor at index 5, one record encoded. */
    private fun page33RecordFrame(): ByteArray = withFrame {
        this[3] = 0x50.toByte()
        this[4] = 0x30
        this[5] = 0xED.toByte()
        this[46] = 33
        this[47] = 0x00
        this[48] = 0xA0.toByte()          // totalCount = 10, high nibble of the index
        this[49] = 5                      // index = (0xA0 % 16) * 256 + 5 = 5
        this[50] = 0x67
        this[51] = 0x45
        this[52] = 0x0A
        this[53] = 0x00                   // timestamp 0x67450A00
        this[54] = 0x00
        this[55] = 0x21                   // code 0x0021
        this[56] = 2                      // two values follow
        this[57] = 0x00
        this[58] = 0x00
        this[59] = 0x00
        this[60] = 0x00                   // value 0
        this[61] = 0xFF.toByte()
        this[62] = 0xFF.toByte()
        this[63] = 0xFF.toByte()
        this[64] = 0xFF.toByte()          // value -1
        "mTest".forEachIndexed { i, c -> this[65 + i] = c.code.toByte() }
        this[70] = 0x00
    }

    private fun page33EndMarkerFrame(): ByteArray = withFrame {
        this[3] = 0x50.toByte()
        this[4] = 0x30
        this[5] = 0xED.toByte()
        this[46] = 33
        this[47] = 0x00
        this[48] = 0xFF.toByte()          // index 4095, total 15: the cursor ran out
        this[49] = 0xFF.toByte()
    }

    /** DC 5A 5C header, `len` byte, CRC32 trailer: the shape `processFrame` accepts. */
    private inline fun withFrame(build: ByteArray.() -> Unit): ByteArray {
        val frame = ByteArray(84)
        frame[0] = 0xDC.toByte()
        frame[1] = 0x5A
        frame[2] = 0x5C
        frame.build()
        val crc = EucCrc32.calculate(frame, 0, 80)
        frame[80] = ((crc ushr 24) and 0xFF).toByte()
        frame[81] = ((crc ushr 16) and 0xFF).toByte()
        frame[82] = ((crc ushr 8) and 0xFF).toByte()
        frame[83] = crc.toByte()
        return frame
    }
}

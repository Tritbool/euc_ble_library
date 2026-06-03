package com.euc.ble.protocols

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.euc.ble.test.JUnit4AssertionsCompat.assertEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertArrayEquals
import com.euc.ble.test.JUnit4AssertionsCompat.assertFalse
import com.euc.ble.test.JUnit4AssertionsCompat.assertNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertNotNull
import com.euc.ble.test.JUnit4AssertionsCompat.assertTrue

class KingsongProtocolTest {

    private lateinit var protocol: KingsongProtocol

    @BeforeEach
    fun setUp() {
        protocol = KingsongProtocol()
    }

    @AfterEach
    fun tearDown() {
        protocol.close()
    }

    @Test
    fun decodeA9TelemetryWithoutF5HasNoPwm() = runBlocking {
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertEquals("KingSong", data.manufacturer)
        assertNull(data.pwm)
    }

    @Test
    fun decodeF5ThenA9PublishesPwm() = runBlocking {
        protocol.decode(createF5Frame(outputPercentByte = 63))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertEquals(0.63, data.pwm ?: -1.0, 0.0001)
    }

    @Test
    fun createCommandUsesLegacyFrameFormatForCoreActions() {
        assertArrayEquals(
            createLegacyCommand(command = 0x73, payload2 = 0x13, payload3 = 0x01),
            protocol.createCommand(CommandType.LIGHT_ON, Unit)
        )
        assertArrayEquals(
            createLegacyCommand(command = 0x73, payload2 = 0x12, payload3 = 0x01),
            protocol.createCommand(CommandType.LIGHT_OFF, Unit)
        )
        assertArrayEquals(
            createLegacyCommand(command = 0x88),
            protocol.createCommand(CommandType.BEEP, Unit)
        )
        assertArrayEquals(
            createLegacyCommand(command = 0x40),
            protocol.createCommand(CommandType.POWER_OFF, Unit)
        )
    }

    @Test
    fun createCommandSupportsLegacySettingsControls() {
        assertArrayEquals(
            createLegacyCommand(command = 0x87, payload2 = 0x02, payload3 = 0xE0, payload17 = 0x15),
            protocol.createCommand(CommandType.SET_PEDALS_MODE, 2)
        )
        assertArrayEquals(
            createLegacyCommand(command = 0x6C, payload2 = 0x05),
            protocol.createCommand(CommandType.SET_LED_MODE, 5)
        )
        assertArrayEquals(
            createLegacyCommand(command = 0x73, payload2 = 0x14, payload3 = 0x01),
            protocol.createCommand(CommandType.SET_LIGHT_MODE, 2)
        )
    }

    private fun createA9Frame(
        voltageRaw: Int = 8400,
        speedRaw: Int = 1250,
        distanceRaw: Long = 12345,
        currentRaw: Int = 150,
        temperatureRaw: Int = 2500,
        statusByte: Int = 0,
        batteryByte: Int = 80
    ): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = (voltageRaw and 0xFF).toByte()
        frame[3] = ((voltageRaw shr 8) and 0xFF).toByte()
        frame[4] = (speedRaw and 0xFF).toByte()
        frame[5] = ((speedRaw shr 8) and 0xFF).toByte()
        frame[6] = (distanceRaw and 0xFF).toByte()
        frame[7] = ((distanceRaw shr 8) and 0xFF).toByte()
        frame[8] = ((distanceRaw shr 16) and 0xFF).toByte()
        frame[9] = ((distanceRaw shr 24) and 0xFF).toByte()
        frame[10] = (currentRaw and 0xFF).toByte()
        frame[11] = ((currentRaw shr 8) and 0xFF).toByte()
        frame[12] = (temperatureRaw and 0xFF).toByte()
        frame[13] = ((temperatureRaw shr 8) and 0xFF).toByte()
        frame[14] = (statusByte and 0xFF).toByte()
        frame[15] = (batteryByte and 0xFF).toByte()
        frame[16] = 0xA9.toByte()
        frame[17] = 0x00
        frame[18] = 0x00
        frame[19] = 0x00
        return frame
    }

    private fun createF5Frame(outputPercentByte: Int, cpuLoadByte: Int = 0): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[14] = (cpuLoadByte and 0xFF).toByte()
        frame[15] = (outputPercentByte and 0xFF).toByte()
        frame[16] = 0xF5.toByte()
        return frame
    }

    private fun createLegacyCommand(
        command: Int,
        payload2: Int = 0x00,
        payload3: Int = 0x00,
        payload17: Int = 0x14
    ): ByteArray {
        val data = byteArrayOf(
            0xAA.toByte(), 0x55.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x14, 0x5A, 0x5A
        )
        data[2] = payload2.toByte()
        data[3] = payload3.toByte()
        data[16] = command.toByte()
        data[17] = payload17.toByte()
        return data
    }

    // --- Tests for frame 0xB9 (distance/fan/temp2) ---

    @Test
    fun decodeB9ThenA9IncludesTopSpeedAndFan() = runBlocking {
        protocol.decode(createB9Frame(
            wheelDistanceRaw = 5000L,
            topSpeedRaw = 3500,
            fanStatus = 1,
            chargingStatus = 0,
            temperature2Raw = 4200
        ))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertEquals(35.0, data.topSpeed ?: -1.0, 0.01)
        assertEquals(1, data.fanStatus)
        assertEquals(0, data.chargingStatus)
        assertEquals(42.0, data.temperature2 ?: -1.0, 0.01)
        assertEquals(5000.0, data.wheelDistance ?: -1.0, 0.01)
    }

    // --- Tests for frame 0xBB (name/model/version) ---

    @Test
    fun decodeBBThenA9IncludesModelAndVersion() = runBlocking {
        protocol.decode(createBBFrame("KS-16X-234"))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertEquals("KS-16X", data.model)
        assertEquals("2.34", data.firmwareVersion)
    }

    // --- Tests for frame 0xB3 (serial number) ---

    @Test
    fun decodeB3ThenA9IncludesSerial() = runBlocking {
        protocol.decode(createB3Frame("KS16X12345678901"))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertNotNull(data.serialNumber)
        assertTrue(data.serialNumber!!.startsWith("KS16X"))
    }

    // --- Tests for frame 0xF6 (speed limit) ---

    @Test
    fun decodeF6ThenA9IncludesSpeedLimit() = runBlocking {
        protocol.decode(createF6Frame(speedLimitRaw = 3000))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertEquals(30.0, data.speedLimit ?: -1.0, 0.01)
    }

    // --- Tests for frame 0xA4 (alarm speeds) ---

    @Test
    fun decodeA4ThenA9IncludesAlarmSpeeds() = runBlocking {
        protocol.decode(createA4Frame(alarm1 = 20, alarm2 = 30, alarm3 = 40, maxSpeed = 50))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertEquals(20, data.alarm1Speed)
        assertEquals(30, data.alarm2Speed)
        assertEquals(40, data.alarm3Speed)
        assertEquals(50, data.wheelMaxSpeed)
    }

    // --- Tests for new commands ---

    @Test
    fun createCommandCalibrate() {
        val cmd = protocol.createCommand(CommandType.CALIBRATE, Unit)
        assertEquals(0x89.toByte(), cmd[16])
    }

    @Test
    fun createCommandRequestSerial() {
        val cmd = protocol.createCommand(CommandType.REQUEST_SERIAL, Unit)
        assertEquals(0x63.toByte(), cmd[16])
    }

    @Test
    fun createCommandRequestFirmware() {
        val cmd = protocol.createCommand(CommandType.REQUEST_FIRMWARE, Unit)
        assertEquals(0x9B.toByte(), cmd[16])
    }

    @Test
    fun createCommandSetAlarmSpeed() {
        val cmd = protocol.createCommand(CommandType.SET_ALARM_SPEED, intArrayOf(20, 30, 40))
        assertEquals(0x85.toByte(), cmd[16])
        assertEquals(20.toByte(), cmd[2])
        assertEquals(30.toByte(), cmd[4])
        assertEquals(40.toByte(), cmd[6])
    }

    @Test
    fun getPollingPlanIsEnabled() {
        val plan = protocol.getPollingPlan()
        assertTrue(plan.enabled)
        assertTrue(plan.startupQueries.isNotEmpty())
    }

    // --- Tests for frame 0xF5 (cpuLoad) ---

    @Test
    fun decodeF5ThenA9IncludesCpuLoad() = runBlocking {
        protocol.decode(createF5Frame(outputPercentByte = 63, cpuLoadByte = 42))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertEquals(42, data.cpuLoad)
        assertEquals(0.63, data.pwm ?: -1.0, 0.0001)
    }

    // --- Tests for BMS frames (0xF1/0xF2) ---

    @Test
    fun decodeBmsPage00SummaryIsStoredInGetBMSData() = runBlocking {
        // Page 0x00: voltage=84.00V, current=-2.50A, remaining=5000mAh, factory=6000mAh, cycles=42
        protocol.decode(createBmsPage00Frame(
            messageType = 0xF1,
            bmsVoltageRaw = 8400,
            bmsCurrentRaw = -250,
            remainingCapacity = 5000,
            factoryCapacity = 6000,
            cycles = 42
        ))
        val bmsDataList = protocol.getBMSData()
        assertTrue(bmsDataList.isNotEmpty())
        val bms = bmsDataList.first()
        assertEquals(1, bms.bmsIndex)
        assertEquals(84.0, bms.voltage ?: -1.0, 0.01)
        assertEquals(-2.5, bms.current ?: -1.0, 0.01)
        assertEquals(5000, bms.remainingCapacity)
        assertEquals(6000, bms.factoryCapacity)
        assertEquals(42, bms.cycles)
    }

    @Test
    fun decodeBmsPage01TemperaturesIsStoredInGetBMSData() = runBlocking {
        // Page 0x01: 7 temperature probes at 0.1°C units
        protocol.decode(createBmsPage01Frame(
            messageType = 0xF1,
            temperatures = intArrayOf(250, 260, 270, 280, 290, 300, 310)
        ))
        val bmsDataList = protocol.getBMSData()
        assertTrue(bmsDataList.isNotEmpty())
        val bms = bmsDataList.first()
        assertNotNull(bms.temperatures)
        assertEquals(7, bms.temperatures!!.size)
        assertEquals(25.0, bms.temperatures!![0], 0.01)
        assertEquals(31.0, bms.temperatures!![6], 0.01)
    }

    @Test
    fun getBMSDataCombinesSummaryTempsAndCells() = runBlocking {
        // Send summary, temperatures, and cell voltages for BMS 1
        protocol.decode(createBmsPage00Frame(messageType = 0xF1, bmsVoltageRaw = 8400, bmsCurrentRaw = 100, remainingCapacity = 4000, factoryCapacity = 5000, cycles = 10))
        protocol.decode(createBmsPage01Frame(messageType = 0xF1, temperatures = intArrayOf(250, 260, 270, 280, 290, 300, 310)))
        protocol.decode(createBmsFrame(messageType = 0xF1, pageNum = 0x02, cellVoltages = intArrayOf(4200, 4150, 4100, 4050, 4000, 3950, 3900)))

        val bmsDataList = protocol.getBMSData()
        assertEquals(1, bmsDataList.size)
        val bms = bmsDataList[0]
        assertEquals(1, bms.bmsIndex)
        assertNotNull(bms.voltage)
        assertNotNull(bms.temperatures)
        assertNotNull(bms.cellVoltages)
        assertEquals(7, bms.cellVoltages!!.size)
        assertEquals(4.2, bms.cellVoltages!![0], 0.001)
    }

    @Test
    fun decodeBmsCellVoltagesPage02() = runBlocking {
        // Page 0x02: first 7 cell voltages for BMS 1 (0xF1)
        protocol.decode(createBmsFrame(messageType = 0xF1, pageNum = 0x02, cellVoltages = intArrayOf(4200, 4150, 4100, 4050, 4000, 3950, 3900)))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertNotNull(data.cellVoltages)
        val cells = data.cellVoltages!!
        assertTrue(cells.isNotEmpty())
        assertEquals(4.2, cells[0], 0.001)
        assertEquals(4.15, cells[1], 0.001)
        assertEquals(3.9, cells[6], 0.001)
    }

    @Test
    fun decodeBmsMultiplePagesAccumulatesCells() = runBlocking {
        // Page 0x02: cells 0-6, Page 0x03: cells 7-13
        protocol.decode(createBmsFrame(messageType = 0xF1, pageNum = 0x02, cellVoltages = intArrayOf(4200, 4150, 4100, 4050, 4000, 3950, 3900)))
        protocol.decode(createBmsFrame(messageType = 0xF1, pageNum = 0x03, cellVoltages = intArrayOf(4180, 4130, 4080, 4030, 3980, 3930, 3880)))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertNotNull(data.cellVoltages)
        val cells = data.cellVoltages!!
        assertTrue(cells.size >= 14)
        assertEquals(4.2, cells[0], 0.001)
        assertEquals(4.18, cells[7], 0.001)
    }

    @Test
    fun decodeBmsF2SeparateFromF1() = runBlocking {
        // BMS 1 and BMS 2 contribute separate cells
        protocol.decode(createBmsFrame(messageType = 0xF1, pageNum = 0x02, cellVoltages = intArrayOf(4200, 4150, 4100, 4050, 4000, 3950, 3900)))
        protocol.decode(createBmsFrame(messageType = 0xF2, pageNum = 0x02, cellVoltages = intArrayOf(4100, 4050, 4000, 3950, 3900, 3850, 3800)))
        protocol.decode(createA9Frame())
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertNotNull(data.cellVoltages)
        val cells = data.cellVoltages!!
        // Should have cells from both BMS packs
        assertTrue(cells.size >= 14)
    }

    // --- Tests for SET_SPEED_LIMIT command ---

    @Test
    fun createCommandSetSpeedLimit() {
        // First feed an A4 frame to set known alarm values
        runBlocking {
            protocol.decode(createA4Frame(alarm1 = 25, alarm2 = 35, alarm3 = 45, maxSpeed = 50))
        }
        val cmd = protocol.createCommand(CommandType.SET_SPEED_LIMIT, 60)
        assertEquals(0x85.toByte(), cmd[16])
        // max speed at offset 8
        assertEquals(60.toByte(), cmd[8])
        // alarm values should be preserved
        assertEquals(25.toByte(), cmd[2])
        assertEquals(35.toByte(), cmd[4])
        assertEquals(45.toByte(), cmd[6])
    }

    @Test
    fun createCommandSetSpeedLimitClampsToRange() {
        val cmd = protocol.createCommand(CommandType.SET_SPEED_LIMIT, 150)
        assertEquals(0x85.toByte(), cmd[16])
        // Should be clamped to 100
        assertEquals(100.toByte(), cmd[8])
    }

    @Test
    fun createCommandSetSpeedLimitReturnsEmptyForNonInt() {
        val cmd = protocol.createCommand(CommandType.SET_SPEED_LIMIT, "invalid")
        assertEquals(0, cmd.size)
    }

    // --- Tests for matchesQueryResponse ---

    @Test
    fun matchesQueryResponseForFirmware() {
        val query = ProtocolQuerySpec("ks.request-name-version", CommandType.REQUEST_FIRMWARE, maxRetries = 3)
        val bbFrame = createBBFrame("KS-16X-234")
        assertTrue(protocol.matchesQueryResponse(query, bbFrame))
    }

    @Test
    fun matchesQueryResponseForSerial() {
        val query = ProtocolQuerySpec("ks.request-serial", CommandType.REQUEST_SERIAL, maxRetries = 3)
        val b3Frame = createB3Frame("KS16X12345678901")
        assertTrue(protocol.matchesQueryResponse(query, b3Frame))
    }

    @Test
    fun matchesQueryResponseForCustomAlarms() {
        val query = ProtocolQuerySpec("ks.request-alarms", CommandType.CUSTOM, maxRetries = 2)
        val a4Frame = createA4Frame(alarm1 = 20, alarm2 = 30, alarm3 = 40, maxSpeed = 50)
        assertTrue(protocol.matchesQueryResponse(query, a4Frame))
    }

    @Test
    fun matchesQueryResponseReturnsFalseForWrongType() {
        val query = ProtocolQuerySpec("ks.request-serial", CommandType.REQUEST_SERIAL, maxRetries = 3)
        val bbFrame = createBBFrame("KS-16X-234") // 0xBB != 0xB3
        assertFalse(protocol.matchesQueryResponse(query, bbFrame))
    }

    @Test
    fun matchesQueryResponseReturnsFalseForShortFrame() {
        val query = ProtocolQuerySpec("ks.request-serial", CommandType.REQUEST_SERIAL, maxRetries = 3)
        val shortFrame = ByteArray(5)
        assertFalse(protocol.matchesQueryResponse(query, shortFrame))
    }

    // --- Tests for isDeviceReady ---

    @Test
    fun isDeviceReadyReturnsTrueForNormalConditions() = runBlocking {
        protocol.decode(createA9Frame(voltageRaw = 8400, temperatureRaw = 3500, batteryByte = 80))
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertTrue(protocol.isDeviceReady(data))
    }

    @Test
    fun isDeviceReadyReturnsFalseForLowBattery() = runBlocking {
        protocol.decode(createA9Frame(voltageRaw = 8400, temperatureRaw = 3500, batteryByte = 3))
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertFalse(protocol.isDeviceReady(data))
    }

    @Test
    fun isDeviceReadyReturnsFalseForHighTemp() = runBlocking {
        // temperature = raw / 100, so 8000 = 80°C > 75°C
        protocol.decode(createA9Frame(voltageRaw = 8400, temperatureRaw = 8000, batteryByte = 80))
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertFalse(protocol.isDeviceReady(data))
    }

    @Test
    fun isDeviceReadyReturnsFalseForLowVoltage() = runBlocking {
        // voltage = raw / 100, so 2500 = 25V < 30V
        protocol.decode(createA9Frame(voltageRaw = 2500, temperatureRaw = 3500, batteryByte = 80))
        val data = withTimeout(5_000L) { protocol.dataFlow.first() }
        assertFalse(protocol.isDeviceReady(data))
    }

    // --- Frame builders ---

    private fun createBmsFrame(messageType: Int, pageNum: Int, cellVoltages: IntArray): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        // Cell voltages: 7 cells, each uint16 LE at offset 2 + i*2
        for (i in cellVoltages.indices) {
            if (i >= 7) break
            val offset = 2 + i * 2
            frame[offset] = (cellVoltages[i] and 0xFF).toByte()
            frame[offset + 1] = ((cellVoltages[i] shr 8) and 0xFF).toByte()
        }
        frame[16] = messageType.toByte()
        frame[17] = pageNum.toByte()
        return frame
    }

    private fun createB9Frame(
        wheelDistanceRaw: Long = 1000L,
        topSpeedRaw: Int = 2000,
        fanStatus: Int = 0,
        chargingStatus: Int = 0,
        temperature2Raw: Int = 3000
    ): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = (wheelDistanceRaw and 0xFF).toByte()
        frame[3] = ((wheelDistanceRaw shr 8) and 0xFF).toByte()
        frame[4] = ((wheelDistanceRaw shr 16) and 0xFF).toByte()
        frame[5] = ((wheelDistanceRaw shr 24) and 0xFF).toByte()
        frame[8] = (topSpeedRaw and 0xFF).toByte()
        frame[9] = ((topSpeedRaw shr 8) and 0xFF).toByte()
        frame[12] = fanStatus.toByte()
        frame[13] = chargingStatus.toByte()
        frame[14] = (temperature2Raw and 0xFF).toByte()
        frame[15] = ((temperature2Raw shr 8) and 0xFF).toByte()
        frame[16] = 0xB9.toByte()
        return frame
    }

    private fun createBBFrame(name: String): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        for (i in nameBytes.indices) {
            if (i >= 14) break
            frame[2 + i] = nameBytes[i]
        }
        frame[16] = 0xBB.toByte()
        return frame
    }

    private fun createB3Frame(serial: String): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        val snBytes = serial.toByteArray(Charsets.US_ASCII)
        // First 14 bytes at offset 2, last 3 bytes at offset 17
        for (i in 0 until minOf(14, snBytes.size)) {
            frame[2 + i] = snBytes[i]
        }
        for (i in 0 until minOf(3, maxOf(0, snBytes.size - 14))) {
            frame[17 + i] = snBytes[14 + i]
        }
        frame[16] = 0xB3.toByte()
        return frame
    }

    private fun createF6Frame(speedLimitRaw: Int): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[2] = (speedLimitRaw and 0xFF).toByte()
        frame[3] = ((speedLimitRaw shr 8) and 0xFF).toByte()
        frame[16] = 0xF6.toByte()
        return frame
    }

    private fun createA4Frame(alarm1: Int, alarm2: Int, alarm3: Int, maxSpeed: Int): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        frame[4] = alarm1.toByte()
        frame[6] = alarm2.toByte()
        frame[8] = alarm3.toByte()
        frame[10] = maxSpeed.toByte()
        frame[16] = 0xA4.toByte()
        return frame
    }

    private fun createBmsPage00Frame(
        messageType: Int,
        bmsVoltageRaw: Int = 0,
        bmsCurrentRaw: Int = 0,
        remainingCapacity: Int = 0,
        factoryCapacity: Int = 0,
        cycles: Int = 0
    ): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        // Voltage LE at offset 2
        frame[2] = (bmsVoltageRaw and 0xFF).toByte()
        frame[3] = ((bmsVoltageRaw shr 8) and 0xFF).toByte()
        // Current LE at offset 4
        frame[4] = (bmsCurrentRaw and 0xFF).toByte()
        frame[5] = ((bmsCurrentRaw shr 8) and 0xFF).toByte()
        // Remaining capacity LE at offset 6
        frame[6] = (remainingCapacity and 0xFF).toByte()
        frame[7] = ((remainingCapacity shr 8) and 0xFF).toByte()
        // Factory capacity LE at offset 8
        frame[8] = (factoryCapacity and 0xFF).toByte()
        frame[9] = ((factoryCapacity shr 8) and 0xFF).toByte()
        // Cycles LE at offset 10
        frame[10] = (cycles and 0xFF).toByte()
        frame[11] = ((cycles shr 8) and 0xFF).toByte()
        frame[16] = messageType.toByte()
        frame[17] = 0x00.toByte() // page 0x00
        return frame
    }

    private fun createBmsPage01Frame(
        messageType: Int,
        temperatures: IntArray
    ): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55.toByte()
        for (i in temperatures.indices) {
            if (i >= 7) break
            val offset = 2 + i * 2
            frame[offset] = (temperatures[i] and 0xFF).toByte()
            frame[offset + 1] = ((temperatures[i] shr 8) and 0xFF).toByte()
        }
        frame[16] = messageType.toByte()
        frame[17] = 0x01.toByte() // page 0x01
        return frame
    }
}

package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.frames.ByteByByteFrameParser
import com.euc.ble.models.BMSData
import com.euc.ble.frames.FrameReassembler
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Improved KingSong protocol: tolerant parsing, header resync, header-aware frame reassembly
 * using ByteByByteFrameParser, safe bounds checks, optional cell voltages parsing, and clamped command generation.
 *
 * Supports frame types: 0xA9 (live telemetry), 0xB9 (distance/fan/temp2), 0xBB (name/model/version),
 * 0xB3 (serial number), 0xF5 (CPU load/PWM), 0xF6 (speed limit), 0xA4/0xB5 (alarm speeds),
 * 0xF1/0xF2 (Smart BMS data).
 */
class KingsongProtocol : EUCProtocol {


    private val header1 = byteArrayOf(0xAA.toByte(), 0x55.toByte())
    private val header2 = byteArrayOf(0x55.toByte(), 0xAA.toByte())
    private val MIN_LENGTH = 20
    // Keep enough replay for short startup races and enough extra capacity for bursty BLE chunks.

    private val unpackBuffer = ArrayList<Byte>()

    // Unpacker: accumule octets et retourne 0..N trames complètes.
    // Règle heuristique : détecte en-têtes (AA 55 ou 55 AA) et extrait la tranche jusqu'au prochain en-tête.
    private val unpacker: (Byte) -> List<ByteArray> = { b: Byte ->
        val out = mutableListOf<ByteArray>()
        unpackBuffer.add(b)

        fun findHeaderIndex(from: Int = 0): Int {
            val bufSize = unpackBuffer.size
            if (bufSize < 2) return -1
            var i = maxOf(from, 0)
            val maxStart = bufSize - 2
            while (i <= maxStart) {
                val a = unpackBuffer[i]
                val c = unpackBuffer[i + 1]
                if ((a == header1[0] && c == header1[1]) || (a == header2[0] && c == header2[1])) return i
                i++
            }
            return -1
        }

        var headerIdx = findHeaderIndex(0)
        while (headerIdx >= 0) {
            // chercher l'en-tête suivant
            val nextHeader = findHeaderIndex(headerIdx + 2)
            if (nextHeader >= 0) {
                // extraire frame [headerIdx, nextHeader)
                val len = nextHeader - headerIdx
                val frame = ByteArray(len) { i -> unpackBuffer[headerIdx + i] }
                out.add(frame)
                // supprimer les octets émis
                repeat(nextHeader) { unpackBuffer.removeAt(0) }
                headerIdx = findHeaderIndex(0)
                continue
            }

            // pas d'en-tête suivant
            // si on a au moins MIN_LENGTH octets après l'en-tête, émettre au moins cela (heuristique de flottement)
            if (unpackBuffer.size >= headerIdx + MIN_LENGTH) {
                // émettre tout le restant comme une trame au lieu d'attendre indéfiniment
                val len = unpackBuffer.size - headerIdx
                val frame = ByteArray(len) { i -> unpackBuffer[headerIdx + i] }
                out.add(frame)
                // supprimer les octets émis
                repeat(headerIdx + len) { unpackBuffer.removeAt(0) }
            } else {
                // pas assez de données pour compléter une trame, attendre plus
                break
            }
            headerIdx = findHeaderIndex(0)
        }

        // si pas d'en-tête, garder au plus 1 octet (préserver possibilité de header fractured)
        if (findHeaderIndex(0) < 0) {
            val keep = 1
            while (unpackBuffer.size > keep) unpackBuffer.removeAt(0)
        }

        out
    }

    override val manufacturer: String = "KingSong"
    override val supportedModels: List<String> = listOf(
        "KS-14D", "KS-16", "KS-16S", "KS-16X", "KS-18L", "KS-18XL",
        "KS-19", "KS-S18", "KS-S19", "KS-S20", "KS-S22", "KS-F22"
    )
    override val supportedCommandTypes: Set<CommandType> = setOf(
        CommandType.LIGHT_ON,
        CommandType.LIGHT_OFF,
        CommandType.SET_LIGHT_MODE,
        CommandType.LIGHT_BRIGHTNESS,
        CommandType.BEEP,
        CommandType.POWER_OFF,
        CommandType.SET_PEDALS_MODE,
        CommandType.SET_LED_MODE,
        CommandType.SET_SPEED_LIMIT,
        CommandType.SET_ALARM_SPEED,
        CommandType.CALIBRATE,
        CommandType.REQUEST_SERIAL,
        CommandType.REQUEST_FIRMWARE
    )

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.KINGSONG_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID =
        UUID.fromString(BLEConstants.KINGSONG_READ_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        return device.manufacturerId == BLEConstants.MANUFACTURER_KINGSONG ||
                device.name.startsWith("KS-", ignoreCase = true) ||
                device.name.contains("KingSong", ignoreCase = true)
    }

    override fun looksLikeMyFrames(chunk: ByteArray): Boolean {
        if (chunk.size < 2) return false
        for (i in 0..chunk.size - 2) {
            val a = chunk[i].toInt() and 0xFF
            val b = chunk[i + 1].toInt() and 0xFF
            if ((a == 0xAA && b == 0x55) || (a == 0x55 && b == 0xAA)) return true
        }
        return false
    }

    private fun ensureRange(data: ByteArray, offset: Int, length: Int): Boolean {
        return offset >= 0 && data.size >= offset + length
    }

    // Internal buffer used by the unpacker

    private val byteParser = ByteByByteFrameParser(unpacker, resetUnpacker = {
        unpackBuffer.clear()
    })
    private val frameReassembler = FrameReassembler(byteParser)

    private val _channel = Channel<EUCData>(capacity = Channel.UNLIMITED)
    override val dataFlow: Flow<EUCData> = _channel.receiveAsFlow()

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rawFrameFlow: Flow<ByteArray> = _rawFrameFlow.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var sessionStartTimestampMs: Long? = null
    private var lastKnownPwm: Double? = null

    // Stateful fields accumulated from non-A9 frames
    private var lastKnownModel: String? = null
    private var lastKnownFirmwareVersion: String? = null
    private var lastKnownSerialNumber: String? = null
    private var lastKnownTopSpeed: Double? = null
    private var lastKnownFanStatus: Int? = null
    private var lastKnownChargingStatus: Int? = null
    private var lastKnownTemperature2: Double? = null
    private var lastKnownCpuLoad: Int? = null
    private var lastKnownSpeedLimit: Double? = null
    private var lastKnownAlarm1Speed: Int? = null
    private var lastKnownAlarm2Speed: Int? = null
    private var lastKnownAlarm3Speed: Int? = null
    private var lastKnownWheelMaxSpeed: Int? = null
    private var lastKnownWheelDistance: Double? = null
    private var lastKnownTotalDistance: Double? = null

    // BMS cell data: bmsIndex (1 or 2) -> array of cell voltages
    private val bmsCellPages: MutableMap<Int, DoubleArray> = mutableMapOf()
    // BMS summary data from page 0x00: bmsIndex -> [voltage, current, remainingCapacity, factoryCapacity, cycles]
    private val bmsSummary: MutableMap<Int, BMSSummary> = mutableMapOf()
    // BMS temperature data from page 0x01: bmsIndex -> list of temperature values in °C
    private val bmsTemperatures: MutableMap<Int, List<Double>> = mutableMapOf()

    private data class BMSSummary(
        val voltage: Double,       // volts
        val current: Double,       // amps
        val remainingCapacity: Int, // mAh
        val factoryCapacity: Int,  // mAh
        val cycles: Int            // charge cycles
    )

    companion object {
        private const val MAX_BMS_CELLS = 30
    }

    init {
        // Start observing frames asynchronously and process them
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            frameReassembler.observeFrames().collect { frame ->
                processFrame(frame)
            }
        }
    }

    private fun parseFrame(data: ByteArray): EUCData? {
        if (data.isEmpty()) return null

        val headerIdx = run {
            for (i in 0..(data.size - 2)) {
                if (data[i] == header1[0] && data[i + 1] == header1[1]) return@run i
                if (data[i] == header2[0] && data[i + 1] == header2[1]) return@run i
            }
            -1
        }
        if (headerIdx < 0) return null

        if (data.size - headerIdx < MIN_LENGTH) {
            return null
        }

        val messageType = ByteUtils.getUnsignedByte(data, headerIdx + 16)
        return when (messageType) {
            0xA9 -> parseTypeA9(data, headerIdx)
            0xB9 -> {
                parseTypeB9(data, headerIdx)
                null
            }
            0xBB -> {
                parseTypeBB(data, headerIdx)
                null
            }
            0xB3 -> {
                parseTypeB3(data, headerIdx)
                null
            }
            0xF5 -> {
                parseTypeF5(data, headerIdx)
                null
            }
            0xF6 -> {
                parseTypeF6(data, headerIdx)
                null
            }
            0xA4, 0xB5 -> {
                parseTypeA4B5(data, headerIdx)
                null
            }
            0xF1, 0xF2 -> {
                parseTypeBMS(data, headerIdx, messageType)
                null
            }
            else -> null
        }
    }

    private fun parseTypeA9(data: ByteArray, base: Int): EUCData? {
        try {
            val voltage = if (ensureRange(data, base + 2, 2))
                ByteUtils.getUnsignedShortLE(data, base + 2) / 100.0 else 0.0

            val speed = if (ensureRange(data, base + 4, 2))
                ByteUtils.getUnsignedShortLE(data, base + 4) / 100.0 else 0.0

            val totalDistRaw = if (ensureRange(data, base + 6, 4))
                ByteUtils.getUnsignedIntLE(data, base + 6).toDouble() else 0.0

            val current = if (ensureRange(data, base + 10, 2))
                ByteUtils.getSignedShortLE(data, base + 10) / 100.0 else 0.0

            val temperature = if (ensureRange(data, base + 12, 2))
                ByteUtils.getSignedShortLE(data, base + 12) / 100.0 else 0.0

            val statusByte = if (ensureRange(data, base + 14, 1))
                ByteUtils.getUnsignedByte(data, base + 14) else 0
            val batteryLevel = if (ensureRange(data, base + 15, 1)) {
                ByteUtils.getUnsignedByte(data, base + 15).coerceIn(0, 100)
            } else {
                estimateBatteryPercent(voltage)
            }

            // === Sanity filters ===
            if (voltage !in 40.0..180.0) return null
            if (speed !in 0.0..80.0) return null
            if (temperature !in -40.0..100.0) return null
            if (current !in -200.0..200.0) return null

            val isCharging = (statusByte and 0x01) != 0

            // Mode detection (legacy: byte 15 == 0xE0 means mode is in byte 14)
            if (ensureRange(data, base + 15, 1) && ByteUtils.getUnsignedByte(data, base + 15) == 0xE0) {
                // pedals mode is in byte 14 — but only when byte 15 is the mode marker
                // This conflicts with battery %; in legacy KS the battery is sent separately.
                // We don't alter battery here, but note it for reference.
            }

            lastKnownTotalDistance = totalDistRaw

            val power = voltage * current
            val now = System.currentTimeMillis()
            val rideTimeSeconds = deriveRideTimeSeconds(now)

            return EUCData(
                speed = speed,
                voltage = voltage,
                current = current,
                temperature = temperature,
                batteryLevel = batteryLevel,
                distance = lastKnownWheelDistance ?: totalDistRaw,
                power = power,
                pwm = lastKnownPwm,
                timestamp = now,
                rawData = data,
                manufacturer = manufacturer,
                model = lastKnownModel ?: "KingSong",
                serialNumber = lastKnownSerialNumber,
                firmwareVersion = lastKnownFirmwareVersion,
                isCharging = isCharging,
                rideTime = rideTimeSeconds,
                cellVoltages = getCombinedCellVoltages(),
                motorTemperature = null,
                totalDistance = lastKnownTotalDistance,
                topSpeed = lastKnownTopSpeed,
                fanStatus = lastKnownFanStatus,
                chargingStatus = lastKnownChargingStatus,
                temperature2 = lastKnownTemperature2,
                cpuLoad = lastKnownCpuLoad,
                speedLimit = lastKnownSpeedLimit,
                alarm1Speed = lastKnownAlarm1Speed,
                alarm2Speed = lastKnownAlarm2Speed,
                alarm3Speed = lastKnownAlarm3Speed,
                wheelMaxSpeed = lastKnownWheelMaxSpeed,
                wheelDistance = lastKnownWheelDistance
            )
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Frame 0xB9: Distance/Time/Fan/ChargingStatus/Temperature2
     * Legacy: wheelDistance(uint32 LE @2), topSpeed(uint16 LE @8), fanStatus(@12),
     *         chargingStatus(@13), temperature2(uint16 LE @14)
     */
    private fun parseTypeB9(data: ByteArray, base: Int) {
        if (ensureRange(data, base + 2, 4)) {
            lastKnownWheelDistance = ByteUtils.getUnsignedIntLE(data, base + 2).toDouble()
        }
        if (ensureRange(data, base + 8, 2)) {
            lastKnownTopSpeed = ByteUtils.getUnsignedShortLE(data, base + 8) / 100.0
        }
        if (ensureRange(data, base + 12, 1)) {
            lastKnownFanStatus = ByteUtils.getUnsignedByte(data, base + 12)
        }
        if (ensureRange(data, base + 13, 1)) {
            lastKnownChargingStatus = ByteUtils.getUnsignedByte(data, base + 13)
        }
        if (ensureRange(data, base + 14, 2)) {
            lastKnownTemperature2 = ByteUtils.getUnsignedShortLE(data, base + 14) / 100.0
        }
    }

    /**
     * Frame 0xBB: Name/Model/Version
     * Bytes 2..15: null-terminated ASCII name string (e.g. "KS-16X-1234")
     * Model is derived from name parts; version from last segment.
     */
    private fun parseTypeBB(data: ByteArray, base: Int) {
        if (!ensureRange(data, base + 2, 14)) return
        var end = 0
        for (i in 0 until 14) {
            if (data[base + 2 + i] == 0.toByte()) break
            end++
        }
        if (end == 0) return
        val name = String(data, base + 2, end).trim()
        val parts = name.split("-")
        if (parts.size >= 2) {
            lastKnownModel = parts.dropLast(1).joinToString("-")
            try {
                val versionInt = parts.last().toInt()
                lastKnownFirmwareVersion = "%.2f".format(versionInt / 100.0)
            } catch (_: NumberFormatException) {
                // version part not numeric, keep model as full name
                lastKnownModel = name
            }
        } else {
            lastKnownModel = name
        }
    }

    /**
     * Frame 0xB3: Serial Number
     * Bytes 2..15 (14 bytes) + bytes 17..19 (3 bytes) = 17-char serial
     */
    private fun parseTypeB3(data: ByteArray, base: Int) {
        if (!ensureRange(data, base + 2, 14)) return
        if (!ensureRange(data, base + 17, 3)) return
        val snBytes = ByteArray(17)
        System.arraycopy(data, base + 2, snBytes, 0, 14)
        System.arraycopy(data, base + 17, snBytes, 14, 3)
        lastKnownSerialNumber = String(snBytes).trimEnd('\u0000').trim()
    }

    private fun parseTypeF5(data: ByteArray, base: Int) {
        if (ensureRange(data, base + 14, 1)) {
            lastKnownCpuLoad = ByteUtils.getUnsignedByte(data, base + 14)
        }
        if (!ensureRange(data, base + 15, 1)) return
        val outputByte = ByteUtils.getUnsignedByte(data, base + 15)
        lastKnownPwm = outputByte / 100.0
    }

    /**
     * Frame 0xF6: Speed limit
     * uint16 LE @2, in 0.01 km/h units
     */
    private fun parseTypeF6(data: ByteArray, base: Int) {
        if (!ensureRange(data, base + 2, 2)) return
        lastKnownSpeedLimit = ByteUtils.getUnsignedShortLE(data, base + 2) / 100.0
    }

    /**
     * Frame 0xA4 / 0xB5: Alarm speeds and max speed
     * alarm1 @4, alarm2 @6, alarm3 @8, maxSpeed @10 (all single bytes)
     */
    private fun parseTypeA4B5(data: ByteArray, base: Int) {
        if (ensureRange(data, base + 4, 1)) {
            lastKnownAlarm1Speed = ByteUtils.getUnsignedByte(data, base + 4)
        }
        if (ensureRange(data, base + 6, 1)) {
            lastKnownAlarm2Speed = ByteUtils.getUnsignedByte(data, base + 6)
        }
        if (ensureRange(data, base + 8, 1)) {
            lastKnownAlarm3Speed = ByteUtils.getUnsignedByte(data, base + 8)
        }
        if (ensureRange(data, base + 10, 1)) {
            lastKnownWheelMaxSpeed = ByteUtils.getUnsignedByte(data, base + 10)
        }
    }

    /**
     * Frame 0xF1/0xF2: Smart BMS data
     * BMS number = messageType - 0xF0 (1 or 2)
     * Page number @17 determines what data is in the payload:
     *   0x00: voltage, current, remaining/factory cap, cycles
     *   0x01: temperatures (6 probes + MOS temp)
     *   0x02-0x06: cell voltages (7 cells per page)
     */
    private fun parseTypeBMS(data: ByteArray, base: Int, messageType: Int) {
        val bmsIndex = messageType - 0xF0
        if (!ensureRange(data, base + 17, 1)) return
        val pageNum = ByteUtils.getUnsignedByte(data, base + 17)

        when (pageNum) {
            0x00 -> {
                // Page 0x00: BMS voltage, current, remaining capacity, factory capacity, cycles
                if (!ensureRange(data, base + 2, 10)) return
                val bmsVoltage = ByteUtils.getUnsignedShortLE(data, base + 2) / 100.0
                val bmsCurrent = ByteUtils.getSignedShortLE(data, base + 4) / 100.0
                val remainingCapacity = ByteUtils.getUnsignedShortLE(data, base + 6)
                val factoryCapacity = ByteUtils.getUnsignedShortLE(data, base + 8)
                val cycles = ByteUtils.getUnsignedShortLE(data, base + 10)
                bmsSummary[bmsIndex] = BMSSummary(
                    voltage = bmsVoltage,
                    current = bmsCurrent,
                    remainingCapacity = remainingCapacity,
                    factoryCapacity = factoryCapacity,
                    cycles = cycles
                )
            }
            0x01 -> {
                // Page 0x01: BMS temperatures (6 probes + MOS temp)
                val temps = mutableListOf<Double>()
                for (i in 0 until 7) {
                    val offset = base + 2 + i * 2
                    if (ensureRange(data, offset, 2)) {
                        val raw = ByteUtils.getSignedShortLE(data, offset)
                        // Temperature is in 0.1°C units
                        temps.add(raw / 10.0)
                    }
                }
                if (temps.isNotEmpty()) {
                    bmsTemperatures[bmsIndex] = temps
                }
            }
            0x02, 0x03, 0x04, 0x05, 0x06 -> {
                val cells = bmsCellPages.getOrPut(bmsIndex) { DoubleArray(MAX_BMS_CELLS) }
                val startCell = (pageNum - 0x02) * 7
                for (i in 0 until 7) {
                    val offset = base + 2 + i * 2
                    if (ensureRange(data, offset, 2)) {
                        val cellIndex = startCell + i
                        if (cellIndex < MAX_BMS_CELLS) {
                            cells[cellIndex] = ByteUtils.getUnsignedShortLE(data, offset) / 1000.0
                        }
                    }
                }
            }
        }
    }

    private fun getCombinedCellVoltages(): List<Double>? {
        if (bmsCellPages.isEmpty()) return null
        val combined = bmsCellPages.values
            .flatMap { it.asList() }
            .filter { it > 0.0 }
        return combined.ifEmpty { null }
    }

    /**
     * Returns the current BMS data snapshots for all detected battery packs.
     * Each entry represents one BMS unit (typically 1 or 2 for dual-battery wheels).
     * Data is accumulated from frame types 0xF1/0xF2 pages 0x00 (summary), 0x01 (temperatures),
     * and 0x02-0x06 (cell voltages).
     */
    fun getBMSData(): List<BMSData> {
        val allIndices = (bmsSummary.keys + bmsTemperatures.keys + bmsCellPages.keys).distinct().sorted()
        return allIndices.map { index ->
            val summary = bmsSummary[index]
            val temps = bmsTemperatures[index]
            val cells = bmsCellPages[index]?.asList()?.filter { it > 0.0 }?.ifEmpty { null }
            BMSData(
                bmsIndex = index,
                voltage = summary?.voltage,
                current = summary?.current,
                remainingCapacity = summary?.remainingCapacity,
                factoryCapacity = summary?.factoryCapacity,
                cycles = summary?.cycles,
                temperatures = temps,
                cellVoltages = cells
            )
        }
    }

    private fun deriveRideTimeSeconds(nowMs: Long): Long {
        val start = sessionStartTimestampMs ?: nowMs.also { sessionStartTimestampMs = it }
        return ((nowMs - start) / 1000L).coerceAtLeast(0L)
    }

    private fun estimateBatteryPercent(voltage: Double): Int {
        return (((voltage - 50.0) / (100.0 - 50.0)) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun processFrame(frame: ByteArray) {
        val parsed = parseFrame(frame)
        parsed?.let { _channel.trySend(it) }

    }

    override fun close() {
        scope.cancel()
        lastKnownPwm = null
        _channel.close()
    }

    override fun decode(data: ByteArray): EUCData? {
        if (data.isEmpty()) return null
        _rawFrameFlow.tryEmit(data.clone())

        // Let the reassembler handle the incoming bytes asynchronously
        runBlocking(Dispatchers.IO) {
            frameReassembler.processIncomingBytes(data)
        }
        // Return null because data is emitted asynchronously via the dataFlow
        return null
    }

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        return when (commandType) {
            CommandType.LIGHT_ON -> buildLegacyCommand(command = 0x73, payload2 = 0x13, payload3 = 0x01)
            CommandType.LIGHT_OFF -> buildLegacyCommand(command = 0x73, payload2 = 0x12, payload3 = 0x01)
            CommandType.SET_LIGHT_MODE -> {
                val mode = (value as? Int)?.coerceIn(0, 2) ?: return byteArrayOf()
                buildLegacyCommand(command = 0x73, payload2 = mode + 0x12, payload3 = 0x01)
            }
            CommandType.BEEP -> buildLegacyCommand(command = 0x88)
            CommandType.POWER_OFF -> buildLegacyCommand(command = 0x40)
            CommandType.SET_PEDALS_MODE -> {
                val pedalsMode = (value as? Int)?.coerceIn(0, 2) ?: return byteArrayOf()
                buildLegacyCommand(command = 0x87, payload2 = pedalsMode, payload3 = 0xE0, payload17 = 0x15)
            }
            CommandType.SET_LED_MODE -> {
                val ledMode = (value as? Int)?.coerceIn(0, 0xFF) ?: return byteArrayOf()
                buildLegacyCommand(command = 0x6C, payload2 = ledMode)
            }
            CommandType.LIGHT_BRIGHTNESS -> {
                val intVal = (value as? Int) ?: return byteArrayOf()
                val mode = when {
                    intVal <= 0 -> 0
                    intVal >= 100 -> 2
                    else -> 1
                }
                buildLegacyCommand(command = 0x73, payload2 = mode + 0x12, payload3 = 0x01)
            }
            CommandType.SET_SPEED_LIMIT -> {
                // KingSong uses a combined command (0x85) that sets alarms + max speed together.
                // SET_SPEED_LIMIT updates the max speed while preserving current alarm values.
                val maxSpeed = (value as? Int)?.coerceIn(0, 100) ?: return byteArrayOf()
                buildAlarmSpeedCommand(
                    alarm1 = lastKnownAlarm1Speed ?: 0,
                    alarm2 = lastKnownAlarm2Speed ?: 0,
                    alarm3 = lastKnownAlarm3Speed ?: 0,
                    maxSpeed = maxSpeed
                )
            }
            CommandType.SET_ALARM_SPEED -> {
                // value is expected to be a Map or IntArray [alarm1, alarm2, alarm3]
                val speeds = when (value) {
                    is IntArray -> value
                    is List<*> -> (value.filterIsInstance<Int>()).toIntArray()
                    else -> return byteArrayOf()
                }
                if (speeds.size < 3) return byteArrayOf()
                buildAlarmSpeedCommand(
                    alarm1 = speeds[0].coerceIn(0, 100),
                    alarm2 = speeds[1].coerceIn(0, 100),
                    alarm3 = speeds[2].coerceIn(0, 100),
                    maxSpeed = lastKnownWheelMaxSpeed ?: 0
                )
            }
            CommandType.CALIBRATE -> buildLegacyCommand(command = 0x89)
            CommandType.REQUEST_SERIAL -> buildLegacyCommand(command = 0x63)
            CommandType.REQUEST_FIRMWARE -> buildLegacyCommand(command = 0x9B)

            else -> byteArrayOf()
        }
    }

    private fun buildAlarmSpeedCommand(alarm1: Int, alarm2: Int, alarm3: Int, maxSpeed: Int): ByteArray {
        val data = byteArrayOf(
            0xAA.toByte(), 0x55.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x85.toByte(), 0x14, 0x5A, 0x5A
        )
        data[2] = alarm1.toByte()
        data[4] = alarm2.toByte()
        data[6] = alarm3.toByte()
        data[8] = maxSpeed.toByte()
        return data
    }

    private fun buildLegacyCommand(
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

    override fun getPollingPlan(): ProtocolPollingPlan {
        return ProtocolPollingPlan(
            enabled = true,
            startupQueries = listOf(
                ProtocolQuerySpec("ks.request-name-version", CommandType.REQUEST_FIRMWARE, maxRetries = 3),
                ProtocolQuerySpec("ks.request-serial", CommandType.REQUEST_SERIAL, initialDelayMs = 200L, maxRetries = 3),
                ProtocolQuerySpec("ks.request-alarms", CommandType.CUSTOM, buildLegacyCommand(command = 0x98), initialDelayMs = 400L, maxRetries = 2)
            ),
            periodicQueries = emptyList()
        )
    }

    override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean {
        if (data.size < MIN_LENGTH) return false
        val headerIdx = run {
            for (i in 0..(data.size - 2)) {
                if (data[i] == header1[0] && data[i + 1] == header1[1]) return@run i
                if (data[i] == header2[0] && data[i + 1] == header2[1]) return@run i
            }
            -1
        }
        if (headerIdx < 0 || data.size - headerIdx < MIN_LENGTH) return false
        val messageType = ByteUtils.getUnsignedByte(data, headerIdx + 16)
        return when (query.commandType) {
            CommandType.REQUEST_FIRMWARE -> messageType == 0xBB
            CommandType.REQUEST_SERIAL -> messageType == 0xB3
            CommandType.CUSTOM -> messageType == 0xA4 || messageType == 0xB5
            else -> false
        }
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        val tempOk = data.temperature < 75.0
        val voltageOk = data.voltage > 30.0
        val batteryOk = data.batteryLevel >= 5
        return voltageOk && tempOk && batteryOk
    }
}

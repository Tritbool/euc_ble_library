package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Single InMotion protocol entrypoint with auto-detected internal dialects (legacy V1 and V2).
 */
class InMotionProtocol : EUCProtocol {

    companion object {
        private val HEADER = byteArrayOf(0xAA.toByte(), 0xAA.toByte())
        private val LEGACY_TAIL = byteArrayOf(0x55.toByte(), 0x55.toByte())
        private const val FLAG_INITIAL = 0x11
        private const val FLAG_DEFAULT = 0x14
        private const val FLAG_EXTENDED = 0x16

        private const val COMMAND_MAIN_INFO = 0x02
        private const val COMMAND_REAL_TIME_INFO = 0x04
        private const val COMMAND_TOTAL_STATS = 0x11
        private const val COMMAND_CONTROL = 0x60

        private const val MIN_FRAME_SIZE = 5
        private const val MAX_LEN = 240

        private const val LEGACY_CURRENT_OFFSET = 39
        private const val LEGACY_VOLTAGE_OFFSET = 43
        private const val LEGACY_TEMP_OFFSET = 51
        private const val LEGACY_MOTOR_TEMP_OFFSET = 53
        private const val LEGACY_TOTAL_DISTANCE_OFFSET = 63
        private const val LEGACY_TRIP_DISTANCE_OFFSET = 83
        private const val LEGACY_SPEED_OFFSET = 95
        private const val LEGACY_RIDE_TIME_OFFSET = 103
        private const val LEGACY_BATTERY_OFFSET = 154

        private const val LEGACY_SPEED_DIVISOR = 820.0
        private const val LEGACY_SPEED_MIN = -80.0
        private const val LEGACY_SPEED_MAX = 80.0
        private const val LEGACY_BATTERY_BASE_VOLTAGE = 55.0
        private const val LEGACY_BATTERY_VOLTAGE_RANGE = 30.0
    }

    override val manufacturer: String = "InMotion"
    override val supportedModels: List<String> = listOf(
        "V5", "V5F", "V8", "V8F", "V8S", "V9", "V10", "V10F", "V11", "V11Y",
        "V12", "V12S", "V12 HS", "V12 HT", "V12 PRO", "V13", "V13 PRO", "V14", "V14 50GB", "V14 50S", "P6"
    )
    override val supportedCommandTypes: Set<CommandType> = setOf(
        CommandType.LIGHT_ON,
        CommandType.LIGHT_OFF,
        CommandType.LIGHT_BRIGHTNESS,
        CommandType.BEEP,
        CommandType.LOCK,
        CommandType.UNLOCK,
        CommandType.POWER_OFF,
        CommandType.REQUEST_SERIAL,
        CommandType.REQUEST_FIRMWARE,
        CommandType.REQUEST_BATTERY_INFO
    )

    override fun getServiceUUID(): UUID {
        return if (lastDetectedDialect == Dialect.V2) {
            UUID.fromString(BLEConstants.INMOTION_V2_SERVICE_UUID)
        } else {
            UUID.fromString(BLEConstants.INMOTION_SERVICE_UUID)
        }
    }

    override fun getDataCharacteristicUUID(): UUID {
        return if (lastDetectedDialect == Dialect.V2) {
            UUID.fromString(BLEConstants.INMOTION_V2_READ_CHARACTERISTIC)
        } else {
            UUID.fromString(BLEConstants.INMOTION_READ_CHARACTERISTIC)
        }
    }

    override fun getWriteCharacteristicUUID(): UUID {
        return if (lastDetectedDialect == Dialect.V2) {
            UUID.fromString(BLEConstants.INMOTION_V2_WRITE_CHARACTERISTIC)
        } else {
            UUID.fromString(BLEConstants.INMOTION_WRITE_CHARACTERISTIC)
        }
    }

    private val _channel = Channel<EUCData>(capacity = Channel.UNLIMITED)
    override val dataFlow: Flow<EUCData> = _channel.receiveAsFlow()

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rawFrameFlow: Flow<ByteArray> = _rawFrameFlow.asSharedFlow()


    private val parseLock = Any()
    private val v2Buffer = ArrayList<Byte>()
    private val legacyBuffer = ArrayList<Byte>()

    private enum class Dialect { UNKNOWN, LEGACY_V1, V2 }

    @Volatile
    private var lastDetectedDialect: Dialect = Dialect.UNKNOWN

    @Volatile
    private var modelName: String = "InMotion"
    @Volatile
    private var serialNumber: String? = null
    @Volatile
    private var firmwareVersion: String? = null
    @Volatile
    private var totalDistanceKm: Double? = null
    @Volatile
    private var v2SessionStartTimestampMs: Long? = null

    override fun canHandle(device: EUCDevice): Boolean {
        val name = device.name
        return device.manufacturerId == BLEConstants.MANUFACTURER_INMOTION ||
                supportedModels.map { model -> model.contains(name, ignoreCase = true) }
                    .reduce { a, b -> a || b }
    }

    override fun looksLikeMyFrames(chunk: ByteArray): Boolean {
        if (chunk.size < 2) return false
        return (chunk[0].toInt() and 0xFF) == 0xAA && (chunk[1].toInt() and 0xFF) == 0xAA
    }

    override fun decode(data: ByteArray): EUCData? {
        if (data.isEmpty()) return null
        _rawFrameFlow.tryEmit(data.clone())
        var lastDecoded: EUCData? = null

        val v2Frames = extractV2Frames(data)
        for (frame in v2Frames) {
            val decoded = parseV2Frame(frame) ?: continue
            lastDetectedDialect = Dialect.V2
            lastDecoded = decoded
            _channel.trySend(decoded)
        }

        val legacyFrames = extractLegacyFrames(data)
        for (frame in legacyFrames) {
            val decoded = parseLegacyFrame(frame) ?: continue
            lastDetectedDialect = Dialect.LEGACY_V1
            lastDecoded = decoded
            _channel.trySend(decoded)
        }
        return lastDecoded
    }

    private fun extractV2Frames(chunk: ByteArray): List<ByteArray> {
        if (!isLikelyV2Chunk(chunk) && v2Buffer.isEmpty()) return emptyList()
        synchronized(parseLock) {
            for (b in chunk) v2Buffer.add(b)
            val out = mutableListOf<ByteArray>()

            while (true) {
                val headerIndex = findHeader(v2Buffer)
                if (headerIndex < 0) {
                    if (v2Buffer.size > 1) {
                        val keep = v2Buffer.last()
                        v2Buffer.clear()
                        v2Buffer.add(keep)
                    }
                    break
                }

                if (headerIndex > 0) {
                    repeat(headerIndex) { v2Buffer.removeAt(0) }
                }

                if (v2Buffer.size < MIN_FRAME_SIZE) break
                val len = v2Buffer[3].toInt() and 0xFF
                if (len !in 1..MAX_LEN) {
                    v2Buffer.removeAt(0)
                    continue
                }

                val frameSize = 2 + 1 + 1 + len + 1
                if (v2Buffer.size < frameSize) break

                val frame = ByteArray(frameSize) { i -> v2Buffer[i] }
                if (!isValidChecksum(frame)) {
                    v2Buffer.removeAt(0)
                    continue
                }

                out.add(frame)
                repeat(frameSize) { v2Buffer.removeAt(0) }
            }
            return out
        }
    }

    private fun extractLegacyFrames(chunk: ByteArray): List<ByteArray> {
        if (!isLikelyLegacyChunk(chunk) && legacyBuffer.isEmpty()) return emptyList()
        synchronized(parseLock) {
            for (b in chunk) legacyBuffer.add(b)
            val out = mutableListOf<ByteArray>()

            while (true) {
                val headerIndex = findHeader(legacyBuffer)
                if (headerIndex < 0) {
                    if (legacyBuffer.size > 1) {
                        val keep = legacyBuffer.last()
                        legacyBuffer.clear()
                        legacyBuffer.add(keep)
                    }
                    break
                }
                if (headerIndex > 0) {
                    repeat(headerIndex) { legacyBuffer.removeAt(0) }
                }

                val frameEndIndex = findTail(legacyBuffer)
                if (frameEndIndex < 0) break

                val frameSize = frameEndIndex + LEGACY_TAIL.size
                val frame = ByteArray(frameSize) { i -> legacyBuffer[i] }
                out.add(frame)
                repeat(frameSize) { legacyBuffer.removeAt(0) }
            }
            return out
        }
    }

    private fun findHeader(source: List<Byte>): Int {
        if (source.size < 2) return -1
        for (i in 0 until source.size - 1) {
            if (source[i] == HEADER[0] && source[i + 1] == HEADER[1]) return i
        }
        return -1
    }

    private fun findTail(source: List<Byte>): Int {
        if (source.size < 4) return -1
        for (i in 2 until source.size - 1) {
            if (source[i] == LEGACY_TAIL[0] && source[i + 1] == LEGACY_TAIL[1]) return i
        }
        return -1
    }

    private fun isLikelyV2Chunk(chunk: ByteArray): Boolean {
        if (chunk.size < MIN_FRAME_SIZE) return false
        if (chunk[0] != HEADER[0] || chunk[1] != HEADER[1]) return false
        val flags = chunk[2].toInt() and 0xFF
        if (flags != FLAG_INITIAL && flags != FLAG_DEFAULT && flags != FLAG_EXTENDED) return false
        val len = chunk[3].toInt() and 0xFF
        return len in 1..MAX_LEN
    }

    private fun isLikelyLegacyChunk(chunk: ByteArray): Boolean {
        if (chunk.size >= 2 && chunk[0] == HEADER[0] && chunk[1] == HEADER[1]) {
            if (chunk.size >= 4) {
                val flags = chunk[2].toInt() and 0xFF
                val len = chunk[3].toInt() and 0xFF
                if ((flags == FLAG_INITIAL || flags == FLAG_DEFAULT || flags == FLAG_EXTENDED) && len in 1..MAX_LEN) {
                    return false
                }
            }
            return true
        }
        return chunk.size >= 2 && chunk[chunk.size - 2] == LEGACY_TAIL[0] && chunk[chunk.size - 1] == LEGACY_TAIL[1]
    }

    private fun isValidChecksum(frame: ByteArray): Boolean {
        var xor = 0
        for (i in 2 until frame.lastIndex) {
            xor = xor xor (frame[i].toInt() and 0xFF)
        }
        return xor == (frame.last().toInt() and 0xFF)
    }

    private fun parseV2Frame(frame: ByteArray): EUCData? {
        val flags = frame[2].toInt() and 0xFF
        if (flags != FLAG_INITIAL && flags != FLAG_DEFAULT && flags != FLAG_EXTENDED) return null

        val len = frame[3].toInt() and 0xFF
        if (len <= 0) return null

        val command = frame[4].toInt() and 0x7F
        val payload = if (len > 1) frame.copyOfRange(5, 5 + (len - 1)) else ByteArray(0)

        return when (command) {
            COMMAND_MAIN_INFO -> {
                parseMainInfo(payload)
                null
            }

            COMMAND_TOTAL_STATS -> {
                parseTotalStats(payload)
                null
            }

            COMMAND_REAL_TIME_INFO -> parseRealTime(payload, frame)
            else -> null
        }
    }

    private fun parseLegacyFrame(frame: ByteArray): EUCData? {
        if (frame.size < 8) return null
        if (frame[0] != HEADER[0] || frame[1] != HEADER[1]) return null

        return when (frame[2].toInt() and 0xFF) {
            0x14 -> {
                parseLegacyInfo(frame)
                null
            }

            0x13 -> parseLegacyRealtime(frame)
            else -> null
        }
    }

    private fun parseLegacyInfo(frame: ByteArray) {
        if (frame.size < 48) return

        // Legacy captures encode model marker and serial seed in the same block.
        serialNumber = decodeLegacySerial(frame)
        modelName = mapLegacyModel(frame.getOrNull(19)?.toInt()?.and(0xFF) ?: 0)
        firmwareVersion = decodeLegacyFirmware(frame)
    }

    private fun decodeLegacySerial(frame: ByteArray): String? {
        if (frame.size < 27) return null
        val serialBytes = frame.copyOfRange(19, 27).reversedArray()
        return serialBytes.joinToString("") { "%02X".format(it) }.ifEmpty { null }
    }

    private fun mapLegacyModel(modelCode: Int): String {
        return when (modelCode) {
            0x1B -> "InMotion V5F"
            0x0E -> "InMotion V8F"
            0x06 -> "InMotion V8S"
            else -> "InMotion"
        }
    }

    private fun decodeLegacyFirmware(frame: ByteArray): String? {
        if (frame.size < 48) return null
        val b43 = frame[43].toInt() and 0xFF
        val b44 = frame[44].toInt() and 0xFF
        val b45 = frame[45].toInt() and 0xFF
        val b46 = frame[46].toInt() and 0xFF
        val b47 = frame[47].toInt() and 0xFF

        return if (b43 == 0 && b47 > 0 && b46 > 0) {
            val build = b44
            val minor = b46
            val major = b47
            "$major.$minor.$build"
        } else {
            val build = ByteUtils.getUnsignedShortLE(frame, 43)
            val minor = b45
            val major = b46
            "$major.$minor.$build"
        }
    }

    private fun parseLegacyRealtime(frame: ByteArray): EUCData? {
        if (frame.size < 67) return null

        val voltage =
            (ByteUtils.tryGetUnsignedShortLE(frame, LEGACY_VOLTAGE_OFFSET) ?: return null) / 100.0
        val current =
            (ByteUtils.tryGetSignedShortLE(frame, LEGACY_CURRENT_OFFSET)?.toInt() ?: 0) / 100.0
        val speedRaw = ByteUtils.tryGetSignedShortLE(frame, LEGACY_SPEED_OFFSET)?.toInt() ?: 0
        val speed = (speedRaw / LEGACY_SPEED_DIVISOR).coerceIn(LEGACY_SPEED_MIN, LEGACY_SPEED_MAX)
        val tripDistanceKm =
            (ByteUtils.tryGetUnsignedIntLE(frame, LEGACY_TRIP_DISTANCE_OFFSET)?.toDouble()
                ?: 0.0) / 1000.0
        val totalDistance =
            (ByteUtils.tryGetUnsignedIntLE(frame, LEGACY_TOTAL_DISTANCE_OFFSET)?.toDouble()
                ?: 0.0) / 1000.0
        val battery = if (frame.size > LEGACY_BATTERY_OFFSET) {
            (frame[LEGACY_BATTERY_OFFSET].toInt() and 0xFF).coerceIn(0, 100)
        } else {
            (((voltage - LEGACY_BATTERY_BASE_VOLTAGE) / LEGACY_BATTERY_VOLTAGE_RANGE) * 100.0).roundToInt()
                .coerceIn(0, 100)
        }

        val temperature = ByteUtils.tryGetSignedByte(frame, LEGACY_TEMP_OFFSET)?.toDouble() ?: 0.0
        val motorTemp = ByteUtils.tryGetSignedByte(frame, LEGACY_MOTOR_TEMP_OFFSET)?.toDouble()
        val rideTimeSeconds =
            ByteUtils.tryGetUnsignedIntLE(frame, LEGACY_RIDE_TIME_OFFSET)?.toLong() ?: 0L

        if (totalDistance > 0.0) totalDistanceKm = totalDistance

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = battery,
            distance = tripDistanceKm,
            power = voltage * current,
            timestamp = System.currentTimeMillis(),
            rawData = frame,
            manufacturer = manufacturer,
            model = modelName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            isCharging = false,
            rideTime = rideTimeSeconds,
            cellVoltages = null,
            motorTemperature = motorTemp,
            totalDistance = totalDistanceKm
        )
    }

    private fun parseMainInfo(payload: ByteArray) {
        if (payload.isEmpty()) return
        when (payload[0].toInt() and 0xFF) {
            0x01 -> { // car type
                if (payload.size >= 4) {
                    val series = payload[2].toInt() and 0xFF
                    val type = payload[3].toInt() and 0xFF
                    modelName = when {
                        series == 6 && type == 1 -> "InMotion V11"
                        series == 6 && type == 2 -> "InMotion V11Y"
                        series == 7 && type == 1 -> "InMotion V12 HS"
                        series == 7 && type == 2 -> "InMotion V12 HT"
                        series == 7 && type == 3 -> "InMotion V12 PRO"
                        series == 8 && type == 1 -> "InMotion V13"
                        series == 8 && type == 2 -> "InMotion V13 PRO"
                        series == 9 && type == 1 -> "InMotion V14 50GB"
                        series == 9 && type == 2 -> "InMotion V14 50S"
                        series == 11 && type == 1 -> "InMotion V12S"
                        series == 12 && type == 1 -> "InMotion V9"
                        series == 13 && type == 1 -> "InMotion P6"
                        else -> "InMotion $series.$type"
                    }
                }
            }

            0x02 -> { // serial
                if (payload.size >= 17) {
                    serialNumber =
                        payload.copyOfRange(1, 17).decodeToString().trim('\u0000').ifEmpty { null }
                }
            }

            0x06 -> { // versions
                if (payload.size >= 24) {
                    val drv3 = ByteUtils.getUnsignedShortLE(payload, 2)
                    val drv2 = ByteUtils.getUnsignedByte(payload, 4)
                    val drv1 = ByteUtils.getUnsignedByte(payload, 5)
                    val main3 = ByteUtils.getUnsignedShortLE(payload, 11)
                    val main2 = ByteUtils.getUnsignedByte(payload, 13)
                    val main1 = ByteUtils.getUnsignedByte(payload, 14)
                    val ble3 = ByteUtils.getUnsignedShortLE(payload, 20)
                    val ble2 = ByteUtils.getUnsignedByte(payload, 22)
                    val ble1 = ByteUtils.getUnsignedByte(payload, 23)
                    firmwareVersion =
                        "Main:$main1.$main2.$main3 Drv:$drv1.$drv2.$drv3 BLE:$ble1.$ble2.$ble3"
                }
            }
        }
    }

    private fun parseTotalStats(payload: ByteArray) {
        val totalMeters = decodeTotalMeters(payload) ?: return
        if (totalMeters >= 0) totalDistanceKm = totalMeters / 1000.0
    }

    private fun decodeTotalMeters(payload: ByteArray): Long? {
        if (payload.size < 4) return null
        return if (isPrefixedTotalStatsEncoding(payload)) {
            ByteUtils.tryGetUnsignedIntLE(payload, 1)?.times(10L)
        } else {
            ByteUtils.tryGetSignedIntLE(payload, 0)?.toLong()?.times(10L)
        }
    }

    private fun isPrefixedTotalStatsEncoding(payload: ByteArray): Boolean {
        return payload.size >= 5 && payload[0] == payload[1]
    }

    private fun parseRealTime(payload: ByteArray, rawFrame: ByteArray): EUCData? {
        if (payload.size < 78) return null

        val voltage = ByteUtils.getUnsignedShortLE(payload, 0) / 100.0
        val current = ByteUtils.getSignedShortLE(payload, 2) / 100.0
        val speed = ByteUtils.getSignedShortLE(payload, 8) / 100.0
        val pitchAngle = ByteUtils.tryGetSignedShortLE(payload, 10)?.let { it / 100.0 }
        val pwm = (ByteUtils.tryGetSignedShortLE(payload, 14)?.toDouble() ?: 0.0) / 100.0
        val distanceKm = (ByteUtils.getUnsignedShortLE(payload, 28) * 10.0) / 1000.0

        val battery1 = ByteUtils.getUnsignedShortLE(payload, 34)
        val battery2 = ByteUtils.getUnsignedShortLE(payload, 36)
        val battery = ((battery1 + battery2) / 200.0).roundToInt().coerceIn(0, 100)

        val mosTemp = decodeTemperature(payload[58])
        val boardTemp = decodeTemperature(payload[59])
        val stateByte = payload[74].toInt() and 0xFF
        val isCharging = ((stateByte shr 7) and 0x01) == 1
        val now = System.currentTimeMillis()
        val rideTimeFromPayload = ByteUtils.tryGetUnsignedIntLE(payload, 24)?.toLong()
            ?.takeIf { it in 0L..604_800L }
        val rideTimeSeconds = rideTimeFromPayload ?: deriveV2RideTimeSeconds(now)

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = mosTemp.toDouble(),
            batteryLevel = battery,
            distance = distanceKm,
            power = voltage * current,
            pwm = pwm,
            timestamp = now,
            rawData = rawFrame,
            manufacturer = manufacturer,
            model = modelName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            isCharging = isCharging,
            rideTime = rideTimeSeconds,
            cellVoltages = null,
            motorTemperature = boardTemp.toDouble(),
            totalDistance = totalDistanceKm,
            angle = pitchAngle
        )
    }

    private fun deriveV2RideTimeSeconds(nowMs: Long): Long {
        val start = v2SessionStartTimestampMs ?: nowMs.also { v2SessionStartTimestampMs = it }
        return ((nowMs - start) / 1000L).coerceAtLeast(0L)
    }

    private fun decodeTemperature(raw: Byte): Int = (raw.toInt() and 0xFF) + 80 - 256

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        if (lastDetectedDialect == Dialect.LEGACY_V1) return byteArrayOf()
        return when (commandType) {
            CommandType.LIGHT_ON -> buildMessage(
                FLAG_DEFAULT,
                COMMAND_CONTROL,
                byteArrayOf(0x50, 0x01)
            )

            CommandType.LIGHT_OFF -> buildMessage(
                FLAG_DEFAULT,
                COMMAND_CONTROL,
                byteArrayOf(0x50, 0x00)
            )

            CommandType.LIGHT_BRIGHTNESS -> {
                val brightness = (value as? Int)?.coerceIn(0, 100) ?: return byteArrayOf()
                buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x2b, brightness.toByte()))
            }

            CommandType.BEEP -> buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x18, 0x00))
            CommandType.LOCK -> buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x31, 0x01))
            CommandType.UNLOCK -> buildMessage(
                FLAG_DEFAULT,
                COMMAND_CONTROL,
                byteArrayOf(0x31, 0x00)
            )

            CommandType.POWER_OFF -> buildMessage(
                FLAG_DEFAULT,
                COMMAND_CONTROL,
                byteArrayOf(0x77, 0x01)
            )
            // InMotion V2 returns model/serial/firmware from the same MAIN_INFO page; both queries use the same request.
            CommandType.REQUEST_SERIAL,
            CommandType.REQUEST_FIRMWARE -> buildMessage(
                FLAG_INITIAL,
                COMMAND_MAIN_INFO,
                byteArrayOf()
            )

            CommandType.REQUEST_BATTERY_INFO -> buildMessage(
                FLAG_DEFAULT,
                COMMAND_REAL_TIME_INFO,
                byteArrayOf()
            )

            else -> byteArrayOf()
        }
    }

    override fun getPollingPlan(): ProtocolPollingPlan {
        return ProtocolPollingPlan(
            enabled = true,
            startupQueries = listOf(
                ProtocolQuerySpec(
                    id = "inmotion.main-info-init",
                    commandType = CommandType.REQUEST_FIRMWARE,
                    initialDelayMs = 0L,
                    maxRetries = 3
                ),
                ProtocolQuerySpec(
                    id = "inmotion.serial",
                    commandType = CommandType.REQUEST_SERIAL,
                    initialDelayMs = 200L,
                    maxRetries = 3
                ),
                ProtocolQuerySpec(
                    id = "inmotion.firmware-version",
                    commandType = CommandType.REQUEST_FIRMWARE,
                    initialDelayMs = 400L,
                    maxRetries = 3
                ),
                ProtocolQuerySpec(
                    id = "inmotion.realtime-init",
                    commandType = CommandType.REQUEST_BATTERY_INFO,
                    initialDelayMs = 600L,
                    maxRetries = 2
                )
            ),
            periodicQueries = listOf(
                ProtocolQuerySpec(
                    id = "inmotion.realtime",
                    commandType = CommandType.REQUEST_BATTERY_INFO,
                    intervalMs = 1_000L,
                    responseTimeoutMs = 1_200L,
                    maxRetries = 1
                )
            )
        )
    }

    override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean {
        if (data.size < 5 || data[0] != HEADER[0] || data[1] != HEADER[1]) return false
        val command = data[4].toInt() and 0x7F
        return when (query.commandType) {
            CommandType.REQUEST_SERIAL,
            CommandType.REQUEST_FIRMWARE -> command == COMMAND_MAIN_INFO

            CommandType.REQUEST_BATTERY_INFO -> command == COMMAND_REAL_TIME_INFO || command == COMMAND_TOTAL_STATS
            else -> false
        }
    }

    private fun buildMessage(flag: Int, command: Int, data: ByteArray): ByteArray {
        val len = data.size + 1
        val body = ByteArray(3 + data.size)
        body[0] = flag.toByte()
        body[1] = len.toByte()
        body[2] = command.toByte()
        if (data.isNotEmpty()) data.copyInto(body, destinationOffset = 3)

        var xor = 0
        for (b in body) xor = xor xor (b.toInt() and 0xFF)
        val checksum = xor.toByte()

        return byteArrayOf(0xAA.toByte(), 0xAA.toByte()) + body + byteArrayOf(checksum)
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        return data.voltage > 30.0 && data.batteryLevel > 0
    }
}

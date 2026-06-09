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
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.UUID

/**
 * Ninebot protocol MVP parser for common WheelLog-style telemetry payloads.
 */
class NinebotProtocol : EUCProtocol {

    companion object {
        private const val FRAME_HEADER = 0x55
        private const val FRAME_ACTION = 0x21
        private const val FRAME_QUERY = 0x22
        private const val MIN_FRAME_SIZE = 18
        private const val WHEELLOG_HEADER_FIRST = 0x5A
        private const val WHEELLOG_HEADER_SECOND = 0xA5
        private const val WHEELLOG_MIN_FRAME_SIZE = 9
        private const val WHEELLOG_MAX_FRAME_SIZE = 128
        private const val WHEELLOG_TELEMETRY_TYPE = 0xB0
        private const val WHEELLOG_SERIAL_TYPE = 0x10
        private const val WHEELLOG_SERIAL_TYPE_PART2 = 0x13
        private const val WHEELLOG_SERIAL_TYPE_PART3 = 0x16
        private const val WHEELLOG_FIRMWARE_TYPE = 0x1A
        private const val WHEELLOG_PARTIAL_HEADER_BYTES_TO_KEEP = 1
        private const val MIN_READY_VOLTAGE_V = 30.0
        private const val MIN_READY_BATTERY_LEVEL = 1
        private const val BATTERY_OFFSET = 16
        private const val STATUS_OFFSET = 17
        private const val RIDE_TIME_OFFSET = 18
    }

    override val manufacturer: String = "Ninebot"
    override val supportedModels: List<String> = listOf(
        "S1", "S2", "S2 Pro", "A1", "A1 Pro", "Z6", "Z8", "Z10", "One S2", "One E+", "One C+"
    )
    override val supportedCommandTypes: Set<CommandType> = setOf(
        CommandType.LIGHT_ON,
        CommandType.LIGHT_OFF,
        CommandType.BEEP,
        CommandType.LOCK,
        CommandType.UNLOCK,
        CommandType.REQUEST_SERIAL,
        CommandType.REQUEST_FIRMWARE,
        CommandType.REQUEST_BATTERY_INFO
    )

    private val _channel = Channel<EUCData>(capacity = Channel.UNLIMITED)
    override val dataFlow: Flow<EUCData> = _channel.receiveAsFlow()

    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val rawFrameFlow: Flow<ByteArray> = _rawFrameFlow.asSharedFlow()

    private var sessionStartTimestampMs: Long? = null
    private val wheelLogBuffer = mutableListOf<Byte>()
    private val serialBuffer = StringBuilder()
    private var serialNumber: String? = null
    private var firmwareVersion: String? = null

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.NINEBOT_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.NINEBOT_READ_CHARACTERISTIC)
    override fun getWriteCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.NINEBOT_WRITE_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        val name = device.name
        return device.manufacturerId == BLEConstants.MANUFACTURER_NINEBOT ||
            name.contains("Ninebot", ignoreCase = true) ||
            name.contains("Segway", ignoreCase = true) ||
            name.startsWith("Z10", ignoreCase = true) ||
            name.startsWith("Z8", ignoreCase = true) ||
            name.startsWith("Z6", ignoreCase = true)
    }

    override fun looksLikeMyFrames(chunk: ByteArray): Boolean {
        if (chunk.isEmpty()) return false
        val first = chunk[0].toInt() and 0xFF
        if (first == FRAME_HEADER) return true
        if (chunk.size >= 2 && first == WHEELLOG_HEADER_FIRST &&
            (chunk[1].toInt() and 0xFF) == WHEELLOG_HEADER_SECOND
        ) return true
        return false
    }

    override fun decode(data: ByteArray): EUCData? {
        _rawFrameFlow.tryEmit(data.clone())
        parseLegacyFrame(data)?.let { decoded ->
            _channel.trySend(decoded)
            return decoded
        }

        var lastDecoded: EUCData? = null
        parseWheelLogFrames(data).forEach { decoded ->
            _channel.trySend(decoded)
            lastDecoded = decoded
        }
        return lastDecoded
    }

    private fun parseLegacyFrame(data: ByteArray): EUCData? {
        if (data.size < MIN_FRAME_SIZE) return null
        if ((data[0].toInt() and 0xFF) != FRAME_HEADER) return null

        val voltage = (ByteUtils.tryGetUnsignedShortLE(data, 4) ?: return null) / 100.0
        val speed = (ByteUtils.tryGetSignedShortLE(data, 6)?.toInt() ?: return null) / 100.0
        val distance = (ByteUtils.tryGetUnsignedIntLE(data, 8) ?: return null) / 1000.0
        val current = (ByteUtils.tryGetSignedShortLE(data, 12)?.toInt() ?: return null) / 100.0
        val temperature = (ByteUtils.tryGetSignedShortLE(data, 14)?.toInt() ?: return null) / 100.0

        if (voltage !in 20.0..150.0) return null
        if (speed !in -120.0..120.0) return null
        if (current !in -300.0..300.0) return null
        if (temperature !in -50.0..130.0) return null

        val battery = ByteUtils.tryGetUnsignedByte(data, BATTERY_OFFSET)
            ?.coerceIn(0, 100)
            ?: estimateBatteryPercent(voltage)
        val status = ByteUtils.tryGetUnsignedByte(data, STATUS_OFFSET) ?: 0
        val isCharging = (status and 0x01) != 0

        val now = System.currentTimeMillis()
        val rideTime = ByteUtils.tryGetUnsignedIntLE(data, RIDE_TIME_OFFSET)?.toLong()
            ?: deriveRideTimeSeconds(now)

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = battery,
            distance = distance,
            power = voltage * current,
            timestamp = now,
            rawData = data,
            manufacturer = manufacturer,
            model = inferModel(data),
            serialNumber = null,
            firmwareVersion = null,
            isCharging = isCharging,
            rideTime = rideTime,
            cellVoltages = null,
            motorTemperature = null,
            totalDistance = null
        )
    }

    private fun parseWheelLogFrames(chunk: ByteArray): List<EUCData> {
        if (chunk.isEmpty()) return emptyList()
        wheelLogBuffer.addAll(chunk.toList())

        val decodedFrames = mutableListOf<EUCData>()

        while (true) {
            if (wheelLogBuffer.size < 3) break

            val headerIndex = findWheelLogHeaderIndex()
            if (headerIndex < 0) {
                // Keep the last byte to preserve a possible partial header (0x5A) across BLE chunks.
                if (wheelLogBuffer.size > WHEELLOG_PARTIAL_HEADER_BYTES_TO_KEEP) {
                    wheelLogBuffer.subList(
                        0,
                        wheelLogBuffer.size - WHEELLOG_PARTIAL_HEADER_BYTES_TO_KEEP
                    ).clear()
                }
                break
            }

            if (headerIndex > 0) {
                wheelLogBuffer.subList(0, headerIndex).clear()
            }

            if (wheelLogBuffer.size < 3) break

            val payloadLength = wheelLogBuffer[2].toInt() and 0xFF
            val expectedFrameLength = payloadLength + WHEELLOG_MIN_FRAME_SIZE
            if (expectedFrameLength !in WHEELLOG_MIN_FRAME_SIZE..WHEELLOG_MAX_FRAME_SIZE) {
                wheelLogBuffer.removeAt(0)
                continue
            }

            if (wheelLogBuffer.size < expectedFrameLength) break

            val frame = wheelLogBuffer.subList(0, expectedFrameLength).toByteArray()
            wheelLogBuffer.subList(0, expectedFrameLength).clear()

            parseWheelLogFrame(frame)?.let(decodedFrames::add)
        }

        return decodedFrames
    }

    private fun findWheelLogHeaderIndex(): Int {
        if (wheelLogBuffer.size < 2) return -1
        for (index in 0 until wheelLogBuffer.size - 1) {
            val first = wheelLogBuffer[index].toInt() and 0xFF
            val second = wheelLogBuffer[index + 1].toInt() and 0xFF
            if (first == WHEELLOG_HEADER_FIRST && second == WHEELLOG_HEADER_SECOND) {
                return index
            }
        }
        return -1
    }

    private fun parseWheelLogFrame(frame: ByteArray): EUCData? {
        if (frame.size < WHEELLOG_MIN_FRAME_SIZE) return null
        if ((frame[0].toInt() and 0xFF) != WHEELLOG_HEADER_FIRST) return null
        if ((frame[1].toInt() and 0xFF) != WHEELLOG_HEADER_SECOND) return null
        val frameType = frame[6].toInt() and 0xFF

        return when (frameType) {
            WHEELLOG_TELEMETRY_TYPE -> parseWheelLogTelemetryFrame(frame)
            WHEELLOG_SERIAL_TYPE,
            WHEELLOG_SERIAL_TYPE_PART2,
            WHEELLOG_SERIAL_TYPE_PART3 -> {
                parseWheelLogSerialFrame(frame)
                null
            }
            WHEELLOG_FIRMWARE_TYPE -> {
                parseWheelLogFirmwareFrame(frame)
                null
            }
            else -> null
        }
    }

    private fun parseWheelLogTelemetryFrame(frame: ByteArray): EUCData? {
        if (frame.size < 41) return null
        if ((frame[6].toInt() and 0xFF) != WHEELLOG_TELEMETRY_TYPE) return null

        val voltage = (ByteUtils.tryGetUnsignedShortLE(frame, 31) ?: return null) / 100.0
        val speed = decodeWheelLogSpeed(frame)
        val current = (ByteUtils.tryGetSignedShortLE(frame, 33)?.toInt() ?: return null) / 100.0
        val temperature = (ByteUtils.tryGetUnsignedShortLE(frame, 29) ?: return null) / 10.0
        val battery = decodeWheelLogBattery(frame) ?: return null

        if (voltage !in 20.0..150.0) return null
        if (speed !in -120.0..120.0) return null
        if (current !in -300.0..300.0) return null
        if (temperature !in -30.0..120.0) return null
        if (battery !in 0..100) return null

        val now = System.currentTimeMillis()
        // Legacy Ninebot B0 provides odometer-style distance; we expose it on both fields until trip data is available.
        val distance = (ByteUtils.tryGetUnsignedIntLE(frame, 21) ?: return null).toDouble() / 1000.0
        val rideTime = ByteUtils.tryGetUnsignedShortLE(frame, 27)
            ?.toLong()
            ?.takeIf { it in 0L..604_800L }
            ?: deriveRideTimeSeconds(now)

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = battery,
            distance = distance,
            power = voltage * current,
            timestamp = now,
            rawData = frame,
            manufacturer = manufacturer,
            model = inferModel(frame),
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            isCharging = current > 0.5,
            rideTime = rideTime,
            cellVoltages = null,
            motorTemperature = null,
            totalDistance = distance
        )
    }

    private fun decodeWheelLogSpeed(frame: ByteArray): Double {
        // Legacy default protocol uses signed short /10 at offset 17; S2 variant reports speed /100 at offset 35.
        val signedLegacySpeed = ByteUtils.tryGetSignedShortLE(frame, 17)?.toInt()
            ?.let { abs(it) / 10.0 }
        val protoS2Speed = ByteUtils.tryGetUnsignedShortLE(frame, 35)?.toDouble()?.div(100.0)
        return when {
            signedLegacySpeed != null && signedLegacySpeed in 0.0..120.0 -> signedLegacySpeed
            protoS2Speed != null && protoS2Speed in 0.0..120.0 -> protoS2Speed
            else -> signedLegacySpeed ?: protoS2Speed ?: 0.0
        }
    }

    private fun decodeWheelLogBattery(frame: ByteArray): Int? {
        val batteryRaw = ByteUtils.tryGetUnsignedShortLE(frame, 15)
            ?: ByteUtils.tryGetUnsignedByte(frame, 15)
            ?: return null
        return if (batteryRaw <= 100) {
            batteryRaw
        } else {
            (batteryRaw / 100.0).roundToInt().coerceIn(0, 100)
        }
    }

    private fun parseWheelLogSerialFrame(frame: ByteArray) {
        if ((frame[6].toInt() and 0xFF) == WHEELLOG_SERIAL_TYPE) {
            serialBuffer.clear()
        }

        val payloadLength = frame[2].toInt() and 0xFF
        val payloadStart = 7
        val payloadEnd = (payloadStart + payloadLength).coerceAtMost(frame.size - 2)
        if (payloadEnd <= payloadStart) return

        val part = frame.copyOfRange(payloadStart, payloadEnd)
            .decodeToString()
            .trim('\u0000', ' ', '\r', '\n')
        if (part.isEmpty()) return

        serialBuffer.append(part)
        serialNumber = serialBuffer.toString().takeIf { it.isNotBlank() }
    }

    private fun parseWheelLogFirmwareFrame(frame: ByteArray) {
        val payloadLength = frame[2].toInt() and 0xFF
        val payloadStart = 7
        val payloadEnd = (payloadStart + payloadLength).coerceAtMost(frame.size - 2)
        if (payloadEnd - payloadStart < 2) return

        val majorByte = frame[payloadStart + 1].toInt() and 0xFF
        val minorBuildByte = frame[payloadStart].toInt() and 0xFF
        // Legacy variants encode major in different nibbles; prefer high nibble and fall back to low nibble.
        val major = (majorByte shr 4).takeIf { it > 0 } ?: (majorByte and 0x0F)
        val minor = (minorBuildByte shr 4) and 0x0F
        val build = minorBuildByte and 0x0F
        firmwareVersion = "$major.$minor.$build"
    }

    private fun inferModel(data: ByteArray): String {
        val messageType = ByteUtils.tryGetUnsignedByte(data, 2) ?: return "Ninebot"
        return if (messageType == 0x01) "Ninebot Z-series" else "Ninebot"
    }

    private fun deriveRideTimeSeconds(nowMs: Long): Long {
        val start = sessionStartTimestampMs ?: nowMs.also { sessionStartTimestampMs = it }
        return ((nowMs - start) / 1000L).coerceAtLeast(0L)
    }

    private fun estimateBatteryPercent(voltage: Double): Int {
        return (((voltage - 52.0) / (84.0 - 52.0)) * 100.0).toInt().coerceIn(0, 100)
    }

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        return when (commandType) {
            CommandType.LIGHT_ON -> buildControlCommand(0x50, 0x01)
            CommandType.LIGHT_OFF -> buildControlCommand(0x50, 0x00)
            CommandType.BEEP -> buildControlCommand(0x18, 0x01)
            CommandType.LOCK -> buildControlCommand(0x31, 0x01)
            CommandType.UNLOCK -> buildControlCommand(0x31, 0x00)
            CommandType.REQUEST_SERIAL -> buildQueryCommand(0x10)
            CommandType.REQUEST_FIRMWARE -> buildQueryCommand(0x1A)
            CommandType.REQUEST_BATTERY_INFO -> buildQueryCommand(0xB0)
            else -> byteArrayOf()
        }
    }

    override fun getPollingPlan(): ProtocolPollingPlan {
        return ProtocolPollingPlan(
            enabled = true,
            startupQueries = listOf(
                ProtocolQuerySpec(id = "ninebot.serial", commandType = CommandType.REQUEST_SERIAL, maxRetries = 3),
                ProtocolQuerySpec(id = "ninebot.firmware", commandType = CommandType.REQUEST_FIRMWARE, maxRetries = 3),
                ProtocolQuerySpec(id = "ninebot.battery.init", commandType = CommandType.REQUEST_BATTERY_INFO, maxRetries = 2)
            ),
            periodicQueries = listOf(
                ProtocolQuerySpec(
                    id = "ninebot.battery.periodic",
                    commandType = CommandType.REQUEST_BATTERY_INFO,
                    intervalMs = 5_000L,
                    responseTimeoutMs = 1_500L,
                    maxRetries = 2
                )
            )
        )
    }

    override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean {
        if (data.size < 7) return false
        val typeAt6 = data[6].toInt() and 0xFF
        val typeAt2 = data[2].toInt() and 0xFF
        return when (query.commandType) {
            CommandType.REQUEST_SERIAL -> typeAt6 == WHEELLOG_SERIAL_TYPE || typeAt6 == WHEELLOG_SERIAL_TYPE_PART2 || typeAt6 == WHEELLOG_SERIAL_TYPE_PART3
            CommandType.REQUEST_FIRMWARE -> typeAt6 == WHEELLOG_FIRMWARE_TYPE
            CommandType.REQUEST_BATTERY_INFO -> typeAt6 == WHEELLOG_TELEMETRY_TYPE || typeAt2 == 0x01
            else -> false
        }
    }

    private fun buildControlCommand(code: Int, value: Int): ByteArray {
        val payload = byteArrayOf(
            FRAME_HEADER.toByte(),
            0x05,
            FRAME_ACTION.toByte(),
            code.toByte(),
            value.toByte()
        )
        var checksum = 0
        for (i in 1 until payload.size) {
            checksum = checksum xor (payload[i].toInt() and 0xFF)
        }
        return payload + byteArrayOf((checksum and 0xFF).toByte())
    }

    private fun buildQueryCommand(code: Int): ByteArray {
        val payload = byteArrayOf(
            FRAME_HEADER.toByte(),
            0x04,
            FRAME_QUERY.toByte(),
            code.toByte()
        )
        var checksum = 0
        for (i in 1 until payload.size) {
            checksum = checksum xor (payload[i].toInt() and 0xFF)
        }
        return payload + byteArrayOf((checksum and 0xFF).toByte())
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        return data.voltage > MIN_READY_VOLTAGE_V && data.batteryLevel >= MIN_READY_BATTERY_LEVEL
    }

    override fun close() {
        wheelLogBuffer.clear()
        serialBuffer.clear()
        _channel.close()
    }
}

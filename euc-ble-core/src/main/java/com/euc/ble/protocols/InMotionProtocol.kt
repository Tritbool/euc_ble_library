package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID
import kotlin.math.roundToInt

/**
 * InMotion V2 protocol implementation (V9-first migration).
 *
 * Frame format:
 *   AA AA | flags | len | command | data(len-1) | checksum(xor flags..data)
 */
class InMotionProtocol : EUCProtocol {

    companion object {
        private val HEADER = byteArrayOf(0xAA.toByte(), 0xAA.toByte())
        private const val FLAG_INITIAL = 0x11
        private const val FLAG_DEFAULT = 0x14
        private const val FLAG_EXTENDED = 0x16

        private const val COMMAND_MAIN_INFO = 0x02
        private const val COMMAND_REAL_TIME_INFO = 0x04
        private const val COMMAND_TOTAL_STATS = 0x11
        private const val COMMAND_CONTROL = 0x60

        private const val MIN_FRAME_SIZE = 5
        private const val MAX_LEN = 240
    }

    override val manufacturer: String = "InMotion"
    override val supportedModels: List<String> = listOf("V9")

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.INMOTION_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.INMOTION_READ_CHARACTERISTIC)
    override fun getWriteCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.INMOTION_WRITE_CHARACTERISTIC)

    private val _dataFlow = MutableSharedFlow<EUCData>(replay = 1, extraBufferCapacity = 32)
    val dataFlow: Flow<EUCData> = _dataFlow

    private val parseLock = Any()
    private val buffer = ArrayList<Byte>()

    @Volatile private var modelName: String = "InMotion"
    @Volatile private var serialNumber: String? = null
    @Volatile private var firmwareVersion: String? = null
    @Volatile private var totalDistanceKm: Double? = null

    override fun canHandle(device: EUCDevice): Boolean {
        val name = device.name
        return device.manufacturerId == BLEConstants.MANUFACTURER_INMOTION ||
            name.contains("InMotion", ignoreCase = true) ||
            name.startsWith("V9", ignoreCase = true) ||
            name.startsWith("V1", ignoreCase = true) ||
            name.startsWith("P6", ignoreCase = true)
    }

    override fun decode(data: ByteArray): EUCData? {
        if (data.isEmpty()) return null
        val frames = extractFrames(data)
        var lastDecoded: EUCData? = null

        for (frame in frames) {
            val decoded = parseFrame(frame) ?: continue
            lastDecoded = decoded
            _dataFlow.tryEmit(decoded)
        }
        return lastDecoded
    }

    private fun extractFrames(chunk: ByteArray): List<ByteArray> {
        synchronized(parseLock) {
            for (b in chunk) buffer.add(b)
            val out = mutableListOf<ByteArray>()

            while (true) {
                val headerIndex = findHeader()
                if (headerIndex < 0) {
                    if (buffer.size > 1) {
                        val keep = buffer.last()
                        buffer.clear()
                        buffer.add(keep)
                    }
                    break
                }

                if (headerIndex > 0) {
                    repeat(headerIndex) { buffer.removeAt(0) }
                }

                if (buffer.size < MIN_FRAME_SIZE) break
                val len = buffer[3].toInt() and 0xFF
                if (len !in 1..MAX_LEN) {
                    buffer.removeAt(0)
                    continue
                }

                val frameSize = 2 + 1 + 1 + len + 1
                if (buffer.size < frameSize) break

                val frame = ByteArray(frameSize) { i -> buffer[i] }
                if (!isValidChecksum(frame)) {
                    buffer.removeAt(0)
                    continue
                }

                out.add(frame)
                repeat(frameSize) { buffer.removeAt(0) }
            }
            return out
        }
    }

    private fun findHeader(): Int {
        if (buffer.size < 2) return -1
        for (i in 0 until buffer.size - 1) {
            if (buffer[i] == HEADER[0] && buffer[i + 1] == HEADER[1]) return i
        }
        return -1
    }

    private fun isValidChecksum(frame: ByteArray): Boolean {
        var xor = 0
        for (i in 2 until frame.lastIndex) {
            xor = xor xor (frame[i].toInt() and 0xFF)
        }
        return xor == (frame.last().toInt() and 0xFF)
    }

    private fun parseFrame(frame: ByteArray): EUCData? {
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

    private fun parseMainInfo(payload: ByteArray) {
        if (payload.isEmpty()) return
        when (payload[0].toInt() and 0xFF) {
            0x01 -> { // car type
                if (payload.size >= 4) {
                    val series = payload[2].toInt() and 0xFF
                    val type = payload[3].toInt() and 0xFF
                    modelName = if (series == 12 && type == 1) "InMotion V9" else "InMotion $series.$type"
                }
            }
            0x02 -> { // serial
                if (payload.size >= 17) {
                    serialNumber = payload.copyOfRange(1, 17).decodeToString().trim('\u0000').ifEmpty { null }
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
                    firmwareVersion = "Main:$main1.$main2.$main3 Drv:$drv1.$drv2.$drv3 BLE:$ble1.$ble2.$ble3"
                }
            }
        }
    }

    private fun parseTotalStats(payload: ByteArray) {
        if (payload.size < 4) return
        val totalMeters = ByteUtils.getSignedIntLE(payload, 0).toLong() * 10L
        if (totalMeters >= 0) totalDistanceKm = totalMeters / 1000.0
    }

    private fun parseRealTime(payload: ByteArray, rawFrame: ByteArray): EUCData? {
        if (payload.size < 78) return null

        val voltage = ByteUtils.getUnsignedShortLE(payload, 0) / 100.0
        val current = ByteUtils.getSignedShortLE(payload, 2) / 100.0
        val speed = ByteUtils.getSignedShortLE(payload, 8) / 100.0
        val distanceKm = (ByteUtils.getUnsignedShortLE(payload, 28) * 10.0) / 1000.0

        val battery1 = ByteUtils.getUnsignedShortLE(payload, 34)
        val battery2 = ByteUtils.getUnsignedShortLE(payload, 36)
        val battery = ((battery1 + battery2) / 200.0).roundToInt().coerceIn(0, 100)

        val mosTemp = decodeTemperature(payload[58])
        val boardTemp = decodeTemperature(payload[59])
        val stateByte = payload[74].toInt() and 0xFF
        val isCharging = ((stateByte shr 7) and 0x01) == 1

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = mosTemp.toDouble(),
            batteryLevel = battery,
            distance = distanceKm,
            power = voltage * current,
            timestamp = System.currentTimeMillis(),
            rawData = rawFrame,
            manufacturer = manufacturer,
            model = modelName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            isCharging = isCharging,
            rideTime = 0,
            cellVoltages = null,
            motorTemperature = boardTemp.toDouble(),
            totalDistance = totalDistanceKm
        )
    }

    private fun decodeTemperature(raw: Byte): Int = (raw.toInt() and 0xFF) + 80 - 256

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        return when (commandType) {
            CommandType.LIGHT_ON -> buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x50, 0x01))
            CommandType.LIGHT_OFF -> buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x50, 0x00))
            CommandType.BEEP -> buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x18, 0x00))
            CommandType.LOCK -> buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x31, 0x01))
            CommandType.UNLOCK -> buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x31, 0x00))
            CommandType.POWER_OFF -> buildMessage(FLAG_DEFAULT, COMMAND_CONTROL, byteArrayOf(0x77, 0x01))
            else -> byteArrayOf()
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

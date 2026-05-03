package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID

/**
 * Ninebot protocol MVP parser for common WheelLog-style telemetry payloads.
 */
class NinebotProtocol : EUCProtocol {

    companion object {
        private const val FRAME_HEADER = 0x55
        private const val MIN_FRAME_SIZE = 18
        private const val BATTERY_OFFSET = 16
        private const val STATUS_OFFSET = 17
        private const val RIDE_TIME_OFFSET = 18
    }

    override val manufacturer: String = "Ninebot"
    override val supportedModels: List<String> = listOf(
        "S1", "S2", "S2 Pro", "A1", "A1 Pro", "Z6", "Z8", "Z10", "One S2", "One E+", "One C+"
    )

    private val _channel = Channel<EUCData>(capacity = Channel.UNLIMITED)
    override val dataFlow: Flow<EUCData> = _channel.receiveAsFlow()

    private var sessionStartTimestampMs: Long? = null

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

    override fun decode(data: ByteArray): EUCData? {
        val decoded = parseFrame(data) ?: return null
        _channel.trySend(decoded)
        return decoded
    }

    private fun parseFrame(data: ByteArray): EUCData? {
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
            else -> byteArrayOf()
        }
    }

    private fun buildControlCommand(code: Int, value: Int): ByteArray {
        val payload = byteArrayOf(
            FRAME_HEADER.toByte(),
            0x05,
            0x21,
            code.toByte(),
            value.toByte()
        )
        var checksum = 0
        for (i in 1 until payload.size) {
            checksum = checksum xor (payload[i].toInt() and 0xFF)
        }
        return payload + byteArrayOf((checksum and 0xFF).toByte())
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        return data.voltage > 30.0 && data.batteryLevel > 0
    }

    override fun close() {
        _channel.close()
    }
}

package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Dedicated Ninebot Z-series protocol split with stateful handshake/polling queries.
 * Decoding is delegated to NinebotProtocol while command/query orchestration is Z-specific.
 */
class NinebotZProtocol : EUCProtocol {

    companion object {
        private const val FRAME_HEADER = 0x55
        private const val FRAME_ACTION = 0x21
        private const val FRAME_QUERY = 0x22
        private const val MIN_READY_VOLTAGE_V = 30.0
        private const val MIN_READY_BATTERY_LEVEL = 1
        private const val WHEELLOG_HEADER_FIRST = 0x5A
        private const val WHEELLOG_HEADER_SECOND = 0xA5
        private const val WHEELLOG_TELEMETRY_TYPE = 0xB0
        private const val WHEELLOG_SERIAL_TYPE = 0x10
        private const val WHEELLOG_FIRMWARE_TYPE = 0x1A

        private val QUERY_BLE_VERSION =
            byteArrayOf(0x5A, 0xA5.toByte(), 0x01, 0x00, 0x00, 0x00, 0x1C)
        private val QUERY_AUTH_KEY = byteArrayOf(0x5A, 0xA5.toByte(), 0x01, 0x00, 0x00, 0x00, 0x1D)
        private val QUERY_PARAMS_PAGE_1 =
            byteArrayOf(0x5A, 0xA5.toByte(), 0x01, 0x00, 0x00, 0x00, 0x20)
        private val QUERY_PARAMS_PAGE_2 =
            byteArrayOf(0x5A, 0xA5.toByte(), 0x01, 0x00, 0x00, 0x00, 0x21)
        private val QUERY_BMS_1 = byteArrayOf(0x5A, 0xA5.toByte(), 0x01, 0x00, 0x00, 0x00, 0x24)
        private val QUERY_BMS_2 = byteArrayOf(0x5A, 0xA5.toByte(), 0x01, 0x00, 0x00, 0x00, 0x25)
    }

    private val delegate = NinebotProtocol()

    override val manufacturer: String = "Ninebot"
    override val supportedModels: List<String> =
        listOf("Z6", "Z8", "Z10", "Ninebot Z-series", "Ninebot Z")
    override val dataFlow: Flow<EUCData> = delegate.dataFlow
    override val rawFrameFlow: Flow<ByteArray> = delegate.rawFrameFlow
    override val supportedCommandTypes: Set<CommandType> = setOf(
        CommandType.LIGHT_ON,
        CommandType.LIGHT_OFF,
        CommandType.BEEP,
        CommandType.LOCK,
        CommandType.UNLOCK,
        CommandType.SET_SPEED_LIMIT,
        CommandType.SET_ALARM_SPEED,
        CommandType.CALIBRATE,
        CommandType.REQUEST_SERIAL,
        CommandType.REQUEST_FIRMWARE,
        CommandType.REQUEST_BATTERY_INFO,
        CommandType.CUSTOM
    )

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.NINEBOT_Z_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID =
        UUID.fromString(BLEConstants.NINEBOT_Z_READ_CHARACTERISTIC)

    override fun getWriteCharacteristicUUID(): UUID =
        UUID.fromString(BLEConstants.NINEBOT_Z_WRITE_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        val name = device.name
        return device.manufacturerId == BLEConstants.MANUFACTURER_NINEBOT &&
                supportedModels.map { model -> model.contains(name, ignoreCase = true) }
                    .reduce { a, b -> a || b }
    }

    override fun decode(data: ByteArray): EUCData? = delegate.decode(data)

    override fun looksLikeMyFrames(chunk: ByteArray): Boolean = delegate.looksLikeMyFrames(chunk)

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        return when (commandType) {
            CommandType.LIGHT_ON -> buildActionCommand(0x50, 0x01)
            CommandType.LIGHT_OFF -> buildActionCommand(0x50, 0x00)
            CommandType.BEEP -> buildActionCommand(0x18, 0x01)
            CommandType.LOCK -> buildActionCommand(0x31, 0x01)
            CommandType.UNLOCK -> buildActionCommand(0x31, 0x00)
            CommandType.SET_SPEED_LIMIT -> {
                val speedKmh = (value as? Int)?.coerceIn(5, 60) ?: return byteArrayOf()
                buildActionCommand(0x70, speedKmh)
            }

            CommandType.SET_ALARM_SPEED -> {
                val speedKmh = (value as? Int)?.coerceIn(5, 60) ?: return byteArrayOf()
                buildActionCommand(0x71, speedKmh)
            }

            CommandType.CALIBRATE -> buildActionCommand(0x7A, 0x01)
            CommandType.REQUEST_SERIAL -> buildQueryCommand(0x10)
            CommandType.REQUEST_FIRMWARE -> buildQueryCommand(0x1A)
            CommandType.REQUEST_BATTERY_INFO -> QUERY_BMS_1.clone()
            CommandType.CUSTOM -> (value as? ByteArray)?.clone() ?: byteArrayOf()
            else -> byteArrayOf()
        }
    }

    override fun getPollingPlan(): ProtocolPollingPlan {
        return ProtocolPollingPlan(
            enabled = true,
            startupQueries = listOf(
                ProtocolQuerySpec(
                    "ninebot-z.ble-version",
                    CommandType.CUSTOM,
                    QUERY_BLE_VERSION,
                    maxRetries = 2
                ),
                ProtocolQuerySpec(
                    "ninebot-z.auth-key",
                    CommandType.CUSTOM,
                    QUERY_AUTH_KEY,
                    maxRetries = 3
                ),
                ProtocolQuerySpec("ninebot-z.serial", CommandType.REQUEST_SERIAL, maxRetries = 3),
                ProtocolQuerySpec(
                    "ninebot-z.firmware",
                    CommandType.REQUEST_FIRMWARE,
                    maxRetries = 3
                ),
                ProtocolQuerySpec(
                    "ninebot-z.params-1",
                    CommandType.CUSTOM,
                    QUERY_PARAMS_PAGE_1,
                    maxRetries = 2
                ),
                ProtocolQuerySpec(
                    "ninebot-z.params-2",
                    CommandType.CUSTOM,
                    QUERY_PARAMS_PAGE_2,
                    maxRetries = 2
                ),
                ProtocolQuerySpec(
                    "ninebot-z.bms1",
                    CommandType.CUSTOM,
                    QUERY_BMS_1,
                    maxRetries = 2
                ),
                ProtocolQuerySpec("ninebot-z.bms2", CommandType.CUSTOM, QUERY_BMS_2, maxRetries = 2)
            ),
            periodicQueries = listOf(
                ProtocolQuerySpec(
                    "ninebot-z.realtime",
                    CommandType.REQUEST_BATTERY_INFO,
                    intervalMs = 1000L,
                    maxRetries = 1
                ),
                ProtocolQuerySpec(
                    "ninebot-z.keepalive",
                    CommandType.CUSTOM,
                    QUERY_PARAMS_PAGE_1,
                    intervalMs = 5000L,
                    maxRetries = 1
                )
            )
        )
    }

    override fun matchesQueryResponse(query: ProtocolQuerySpec, data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        if (data.size >= 7 && (data[0].toInt() and 0xFF) == WHEELLOG_HEADER_FIRST && (data[1].toInt() and 0xFF) == WHEELLOG_HEADER_SECOND) {
            val frameType = data[6].toInt() and 0xFF
            return when (query.commandType) {
                CommandType.REQUEST_SERIAL -> frameType == WHEELLOG_SERIAL_TYPE
                CommandType.REQUEST_FIRMWARE -> frameType == WHEELLOG_FIRMWARE_TYPE
                CommandType.REQUEST_BATTERY_INFO -> frameType == WHEELLOG_TELEMETRY_TYPE
                CommandType.CUSTOM -> true
                else -> false
            }
        }
        return query.commandType == CommandType.CUSTOM
    }

    private fun buildActionCommand(code: Int, value: Int): ByteArray {
        val payload = byteArrayOf(
            FRAME_HEADER.toByte(),
            0x05,
            FRAME_ACTION.toByte(),
            code.toByte(),
            value.toByte()
        )
        return payload + checksum(payload)
    }

    private fun buildQueryCommand(code: Int): ByteArray {
        val payload = byteArrayOf(
            FRAME_HEADER.toByte(),
            0x04,
            FRAME_QUERY.toByte(),
            code.toByte()
        )
        return payload + checksum(payload)
    }

    private fun checksum(payload: ByteArray): ByteArray {
        var checksum = 0
        for (i in 1 until payload.size) {
            checksum = checksum xor (payload[i].toInt() and 0xFF)
        }
        return byteArrayOf((checksum and 0xFF).toByte())
    }

    override fun isDeviceReady(data: EUCData): Boolean =
        data.voltage > MIN_READY_VOLTAGE_V && data.batteryLevel >= MIN_READY_BATTERY_LEVEL

    override fun close() {
        delegate.close()
    }
}

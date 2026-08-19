package io.github.tritbool.euc.ble.protocols

import io.github.tritbool.euc.ble.core.BLEConstants
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Dedicated Ninebot Z-series protocol split with stateful handshake/polling queries.
 * Decoding is delegated to NinebotProtocol while command/query orchestration is Z-specific.
 */
class NinebotZProtocol : EUCProtocol {

    companion object {
        private const val FRAME_HEADER = BLEConstants.NINEBOT_FRAME_FIRST_BYTE
        private const val FRAME_ACTION = 0x21
        private const val FRAME_QUERY = 0x22
        private const val MIN_READY_VOLTAGE_V = BLEConstants.MIN_READY_VOLTAGE_V
        private const val MIN_READY_BATTERY_LEVEL = 1
        private const val WHEELLOG_TELEMETRY_TYPE = 0xB0
        private const val WHEELLOG_SERIAL_TYPE = 0x10
        private const val WHEELLOG_FIRMWARE_TYPE = 0x1A
        private const val WHEELLOG_AUTH_KEY_TYPE = 0x1D
        private const val WHEELLOG_BMS1_TYPE = 0x24
        private const val WHEELLOG_BMS2_TYPE = 0x25
        private const val WHEELLOG_BLE_VERSION_TYPE = 0x68
        private const val WHEELLOG_LOCK_MODE_TYPE = 0x70
        private const val WHEELLOG_LIMITED_MODE_TYPE = 0x72
        private const val WHEELLOG_SPEED_LIMIT_TYPE = 0x74
        private const val WHEELLOG_ALARMS_ARMED_TYPE = 0x7C
        private const val WHEELLOG_ALARM1_TYPE = 0x7D
        private const val WHEELLOG_ALARM2_TYPE = 0x7E
        private const val WHEELLOG_ALARM3_TYPE = 0x7F
        private const val WHEELLOG_LED_MODE_TYPE = 0xC6
        private const val WHEELLOG_PEDAL_SENSITIVITY_TYPE = 0xD2
        private const val WHEELLOG_DRIVE_FLAGS_TYPE = 0xD3
        private const val WHEELLOG_SPEAKER_VOLUME_TYPE = 0xF5

        private val WHEELLOG_HEADER: ByteArray = BLEConstants.NINEBOT_WHEELLOG_FRAME_HEADER

        private val QUERY_BLE_VERSION =
            WHEELLOG_HEADER + byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x1C)
        private val QUERY_AUTH_KEY = WHEELLOG_HEADER + byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x1D)
        private val QUERY_PARAMS_PAGE_1 =
            WHEELLOG_HEADER + byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x20)
        private val QUERY_PARAMS_PAGE_2 =
            WHEELLOG_HEADER + byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x21)
        private val QUERY_BMS_1 = WHEELLOG_HEADER + byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x24)
        private val QUERY_BMS_2 = WHEELLOG_HEADER + byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x25)
    }

    private val delegate = NinebotProtocol()

    override val manufacturer: String = "Ninebot"
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

    /**
     * NinebotZ GATT signatures derived from WheelLog's bluetooth_services.json fingerprint database.
     *
     * Both NinebotZ and InMotion V2 use the Nordic UART service (`6e400001`). The distinction is
     * that InMotion V2's `00001800` (Generic Access) service contains the `00002aa6` (Central
     * Address Resolution) characteristic, while NinebotZ's does not. Excluding this characteristic
     * in the `00001800` spec ensures we match NinebotZ and not InMotion V2.
     */
    override fun decode(data: ByteArray): EUCData? = delegate.decode(data)

    fun getZSettingsSnapshot(): NinebotProtocol.ZSettingsSnapshot = delegate.getZSettingsSnapshot()
    fun getZBmsSnapshots(): List<NinebotProtocol.ZBmsSnapshot> = delegate.getZBmsSnapshots()
    override fun getBMSData(): List<BMSData>? = delegate.getBMSData()

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
        if (data.size >= 7 && data[0] == BLEConstants.NINEBOT_WHEELLOG_FRAME_HEADER[0] && data[1] == BLEConstants.NINEBOT_WHEELLOG_FRAME_HEADER[1]) {
            val frameType = data[6].toInt() and 0xFF
            return when (query.commandType) {
                CommandType.REQUEST_SERIAL -> frameType == WHEELLOG_SERIAL_TYPE
                CommandType.REQUEST_FIRMWARE -> frameType == WHEELLOG_FIRMWARE_TYPE
                CommandType.REQUEST_BATTERY_INFO -> frameType == WHEELLOG_TELEMETRY_TYPE
                CommandType.CUSTOM -> expectedFrameTypesForQuery(query.id)?.contains(frameType) ?: true
                else -> false
            }
        }
        return false
    }

    private fun expectedFrameTypesForQuery(queryId: String): Set<Int>? = when (queryId) {
        "ninebot-z.ble-version" -> setOf(WHEELLOG_BLE_VERSION_TYPE)
        "ninebot-z.auth-key" -> setOf(WHEELLOG_AUTH_KEY_TYPE)
        "ninebot-z.params-1",
        "ninebot-z.keepalive" -> setOf(
            WHEELLOG_LOCK_MODE_TYPE,
            WHEELLOG_LIMITED_MODE_TYPE,
            WHEELLOG_SPEED_LIMIT_TYPE,
            WHEELLOG_ALARMS_ARMED_TYPE,
            WHEELLOG_ALARM1_TYPE,
            WHEELLOG_ALARM2_TYPE,
            WHEELLOG_ALARM3_TYPE
        )

        "ninebot-z.params-2" -> setOf(
            WHEELLOG_LED_MODE_TYPE,
            WHEELLOG_PEDAL_SENSITIVITY_TYPE,
            WHEELLOG_DRIVE_FLAGS_TYPE,
            WHEELLOG_SPEAKER_VOLUME_TYPE
        )

        "ninebot-z.bms1" -> setOf(WHEELLOG_BMS1_TYPE)
        "ninebot-z.bms2" -> setOf(WHEELLOG_BMS2_TYPE)
        else -> null
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

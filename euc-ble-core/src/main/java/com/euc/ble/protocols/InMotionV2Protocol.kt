package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.frames.FrameReassembler
import com.euc.ble.frames.InmotionV2FrameParser
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import java.util.UUID

class InMotionV2Protocol : EUCProtocol {

    override val manufacturer: String = "InMotion"
    override val supportedModels: List<String> = listOf(
        "V11", "V12", "V12 Pro", "V12 HT", "V13"
    )

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.INMOTION_V2_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.INMOTION_V2_READ_CHARACTERISTIC)
    override fun getWriteCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.INMOTION_V2_WRITE_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        return device.name.contains("InMotion", ignoreCase = true) &&
                (device.name.contains("V11", ignoreCase = true) ||
                        device.name.contains("V12", ignoreCase = true) ||
                        device.name.contains("V13", ignoreCase = true))
    }

    /**
     * Usine pour obtenir un FrameReassembler prêt à l'emploi pour ce protocole.
     * Exemple d'utilisation :
     *   val re = protocol.createFrameReassembler()
     *   re.frames.onEach { frame -> protocol.decode(frame)?.let { ... } }.launchIn(scope)
     */
    fun createFrameReassembler(): FrameReassembler {
        return InmotionV2FrameParser().createReassembler()
    }

    /**
     * Utility pour alimenter le reassembler avec des octets reçus (par notifications BLE).
     */
    fun feedReassembler(reassembler: FrameReassembler, data: ByteArray) {
        reassembler.processIncomingBytes(data)
    }

    override fun decode(data: ByteArray): EUCData? {
        if (data.size < 24) {
            return null
        }

        try {
            val startMarker = ByteUtils.getUnsignedByte(data, 0)
            if (startMarker != 0x55) return null

            val frameLength = ByteUtils.getUnsignedByte(data, 1)
            if (frameLength != data.size) return null

            val messageType = ByteUtils.getUnsignedByte(data, 2)
            if (messageType != 0x01) return null

            val voltage = ByteUtils.getUnsignedShortLE(data, 4) / 10.0
            val speed = ByteUtils.getUnsignedShortLE(data, 6) / 10.0
            val distance = ByteUtils.getUnsignedIntLE(data, 8).toDouble() / 1000.0
            val current = ByteUtils.getUnsignedShortLE(data, 12) / 10.0
            val temperature = ByteUtils.getUnsignedShortLE(data, 14) / 10.0
            val batteryLevel = ByteUtils.getUnsignedByte(data, 16).toInt()
            val power = voltage * current

            val statusByte = ByteUtils.getUnsignedByte(data, 17)
            val isCharging = (statusByte and 0x01) != 0
            val hasAlarm = (statusByte and 0x02) != 0
            val isLocked = (statusByte and 0x04) != 0

            val calculatedChecksum = ByteUtils.calculateChecksum(data, 0, data.size - 1)
            val receivedChecksum = data[data.size - 1]
            if (calculatedChecksum != receivedChecksum) return null

            return EUCData(
                speed = speed,
                voltage = voltage,
                current = current,
                temperature = temperature,
                batteryLevel = batteryLevel,
                distance = distance,
                power = power,
                timestamp = System.currentTimeMillis(),
                rawData = data,
                manufacturer = manufacturer,
                model = "Unknown InMotion V2",
                serialNumber = null,
                firmwareVersion = null,
                isCharging = isCharging,
                rideTime = 0,
                cellVoltages = null,
                motorTemperature = null,
            )

        } catch (e: Exception) {
            return null
        }
    }

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        return when (commandType) {
            CommandType.LIGHT_ON -> byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x01, 0x01, 0x01, 0x00, 0x00)
            CommandType.LIGHT_OFF -> byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x01, 0x01, 0x00, 0x00, 0x00)
            CommandType.BEEP -> byteArrayOf(0x55.toByte(), 0x04.toByte(), 0x02, 0x01, 0x00, 0x00)
            CommandType.POWER_OFF -> byteArrayOf(0x55.toByte(), 0x04.toByte(), 0x03, 0x01, 0x00, 0x00)
            CommandType.LIGHT_BRIGHTNESS -> {
                if (value is Int && value in 0..100) {
                    val brightness = (value * 255 / 100).toByte()
                    byteArrayOf(0x55.toByte(), 0x05.toByte(), 0x04, brightness, 0x00, 0x00, 0x00)
                } else byteArrayOf()
            }
            CommandType.REQUEST_SERIAL -> byteArrayOf(0x55.toByte(), 0x04.toByte(), 0x10, 0x01, 0x00, 0x00)
            CommandType.REQUEST_FIRMWARE -> byteArrayOf(0x55.toByte(), 0x04.toByte(), 0x11, 0x01, 0x00, 0x00)
            else -> byteArrayOf()
        }
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        return data.speed >= 0 && data.voltage > 50.0 && data.temperature < 70.0
    }
}

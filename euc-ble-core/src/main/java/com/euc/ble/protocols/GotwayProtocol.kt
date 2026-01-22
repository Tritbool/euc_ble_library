// File: `euc-ble-core/src/main/java/com/euc/ble/protocols/GotwayProtocol.kt`
package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import java.util.UUID

/**
 * Gotway EUC Protocol Implementation
 * Supports Gotway series electric unicycles
 */
class GotwayProtocol : EUCProtocol {

    override val manufacturer: String = "Gotway"
    override val supportedModels: List<String> = listOf(
        "MSuper", "MSX", "MSX Pro", "Mten3", "Mten4", "MTen5",
        "Nikola", "Nikola Plus", "Tesla", "Monster", "Monster Pro",
        "Begode", "Begode RS", "Begode Master", "Begode Hero"
    )

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.GOTWAY_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.GOTWAY_READ_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        return device.manufacturerId == BLEConstants.MANUFACTURER_GOTWAY ||
                device.name.contains("Gotway", ignoreCase = true) ||
                device.name.contains("Begode", ignoreCase = true) ||
                device.name.contains("Mten", ignoreCase = true) ||
                device.name.contains("MSX", ignoreCase = true) ||
                device.name.contains("Nikola", ignoreCase = true)
    }

    private fun toSignedShort(raw: Int): Int = if (raw >= 0x8000) raw - 0x10000 else raw
    private fun toSignedByte(raw: Int): Int = if (raw >= 0x80) raw - 0x100 else raw

    /*
        Gotway/Begode reverse-engineered protocol

        Gotway uses byte stream from a serial port via Serial-to-BLE adapter.
        There are two types of frames, A and B. Normally they alternate.
        Most numeric values are encoded as Big Endian (BE) 16 or 32 bit integers.
        The protocol has no checksums.

        Since the BLE adapter has no serial flow control and has limited input buffer,
        data come in variable-size chunks with arbitrary delays between chunks. Some
        bytes may even be lost in case of BLE transmit buffer overflow.

             0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19 20 21 22 23
            -----------------------------------------------------------------------
         A: 55 AA 19 F0 00 00 00 00 00 00 01 2C FD CA 00 01 FF F8 00 18 5A 5A 5A 5A
         B: 55 AA 00 0A 4A 12 48 00 1C 20 00 2A 00 03 00 07 00 08 04 18 5A 5A 5A 5A
         A: 55 AA 19 F0 00 00 00 00 00 00 00 F0 FD D2 00 01 FF F8 00 18 5A 5A 5A 5A
         B: 55 AA 00 0A 4A 12 48 00 1C 20 00 2A 00 03 00 07 00 08 04 18 5A 5A 5A 5A
            ....

        Frame A:
            Bytes 0-1:   frame header, 55 AA
            Bytes 2-3:   BE voltage, fixed point, 1/100th (assumes 67.2 battery, rescale for other voltages)
            Bytes 4-5:   BE speed, fixed point, 3.6 * value / 100 km/h
            Bytes 6-9:   BE distance, 32bit fixed point, meters
            Bytes 10-11: BE current, signed fixed point, 1/100th amperes
            Bytes 12-13: BE temperature, (value / 340 + 36.53) / 100, Celsius degrees (MPU6050 native data)
            Bytes 14-17: unknown
            Byte  18:    frame type, 00 for frame A
            Byte  19:    18 frame footer
            Bytes 20-23: frame footer, 5A 5A 5A 5A

        Frame B:
            Bytes 0-1:   frame header, 55 AA
            Bytes 2-5:   BE total distance, 32bit fixed point, meters
            Byte  6:     pedals mode (high nibble), speed alarms (low nibble)
            Bytes 7-12:  unknown
            Byte  13:    LED mode
            Bytes 14-17: unknown
            Byte  18:    frame type, 04 for frame B
            Byte  19:    18 frame footer
            Bytes 20-23: frame footer, 5A 5A 5A 5A

        Unknown bytes may carry out other data, but currently not used by the parser.
    */
    override fun decode(data: ByteArray): EUCData? {
        if (data.isEmpty()) return null

        try {
            val packetType = ByteUtils.getUnsignedByte(data, 0)

            // Dispatch selon type de trame. Les valeurs réelles pour 'A'/'B' doivent
            // correspondre au commentaire de `GotwayAdapter.java` (ASCII ou octal selon impl).
            return when {
                // Legacy numeric packets (ex. 0x01 / 0x02)
                packetType == 0x01 || packetType == 0x02 -> parseLegacy(data)

                // ASCII 'A' / 'B' frames (0x41 / 0x42) — ajuste selon commentaire original
                packetType == 0x00 /* 'A' */ -> parseTypeA(data)
                packetType == 0x04 /* 'B' */ -> parseTypeB(data)

                // Certains firmwares utilisent 0xA5 header for commands/packets
                packetType == 0xA5 -> parseA5Like(data)

                else -> null // type inconnu
            }

        } catch (e: Exception) {
            return null
        }
    }

    // Parseur pour les trames "legacy" déjà présentes dans l'implémentation précédente
    private fun parseLegacy(data: ByteArray): EUCData? {
        if (data.size < 16) return null
        val voltage = ByteUtils.getUnsignedShortLE(data, 1) / 10.0
        val speed = ByteUtils.getUnsignedShortLE(data, 3) / 10.0
        val distanceMeters = ByteUtils.getUnsignedIntLE(data, 5).toDouble() // garder en mètres
        val rawCurrent = ByteUtils.getUnsignedShortLE(data, 9)
        val current = toSignedShort(rawCurrent) / 10.0
        val rawTemp = ByteUtils.getUnsignedShortLE(data, 11)
        val temperature = toSignedShort(rawTemp) / 10.0
        val batteryLevel = ByteUtils.getUnsignedByte(data, 13).toInt().coerceIn(0, 100)
        val power = voltage * current

        val statusByte = ByteUtils.getUnsignedByte(data, 14)
        val isCharging = (statusByte and 0x01) != 0

        val motorTemperature = if (data.size > 15) {
            toSignedByte(ByteUtils.getUnsignedByte(data, 15)) / 10.0
        } else null

        val cellVoltages = parseTrailingCellVoltages(data, startIndex = 15)

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = batteryLevel,
            distance = distanceMeters / 1000.0, // convertir en km si EUCData attend km
            power = power,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = "Unknown Gotway",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = isCharging,
            rideTime = 0,
            cellVoltages = cellVoltages,
            motorTemperature = motorTemperature
        )
    }

    // Exemple de parsing pour trame de type 'A' — adapter les offsets selon le commentaire original
    private fun parseTypeA(data: ByteArray): EUCData? {
        // Défensive: vérifier taille minimale attendue
        if (data.size < 18) return null

        val voltage = ByteUtils.getUnsignedShortLE(data, 1) / 10.0
        val speed = ByteUtils.getUnsignedShortLE(data, 3) / 10.0
        val distanceMeters = ByteUtils.getUnsignedIntLE(data, 5).toDouble()
        val rawCurrent = ByteUtils.getUnsignedShortLE(data, 9)
        val current = toSignedShort(rawCurrent) / 10.0
        val rawTemp = ByteUtils.getUnsignedShortLE(data, 11)
        val temperature = toSignedShort(rawTemp) / 10.0
        val batteryLevel = ByteUtils.getUnsignedByte(data, 13).toInt().coerceIn(0, 100)
        val power = voltage * current
        val statusByte = ByteUtils.getUnsignedByte(data, 14)
        val isCharging = (statusByte and 0x01) != 0
        val motorTemperature = if (data.size > 15) toSignedByte(ByteUtils.getUnsignedByte(data, 15)) / 10.0 else null
        val cellVoltages = parseTrailingCellVoltages(data, startIndex = 16) // si BMS starts after 16

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = batteryLevel,
            distance = distanceMeters / 1000.0,
            power = power,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = "Gotway A",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = isCharging,
            rideTime = 0,
            cellVoltages = cellVoltages,
            motorTemperature = motorTemperature
        )
    }

    // Exemple de parsing pour trame de type 'B' — souvent BMS / pack détaillé
    private fun parseTypeB(data: ByteArray): EUCData? {
        if (data.size < 20) return null

        // Certains paquets B contiennent d'abord des infos pack (voltage, current) puis plusieurs paires (cell)
        val voltage = ByteUtils.getUnsignedShortLE(data, 1) / 10.0
        val speed = ByteUtils.getUnsignedShortLE(data, 3) / 10.0
        val distanceMeters = ByteUtils.getUnsignedIntLE(data, 5).toDouble()
        val rawCurrent = ByteUtils.getUnsignedShortLE(data, 9)
        val current = toSignedShort(rawCurrent) / 10.0
        val rawTemp = ByteUtils.getUnsignedShortLE(data, 11)
        val temperature = toSignedShort(rawTemp) / 10.0
        val batteryLevel = ByteUtils.getUnsignedByte(data, 13).toInt().coerceIn(0, 100)
        val power = voltage * current
        val statusByte = ByteUtils.getUnsignedByte(data, 14)
        val isCharging = (statusByte and 0x01) != 0
        val motorTemperature = if (data.size > 15) toSignedByte(ByteUtils.getUnsignedByte(data, 15)) / 10.0 else null

        // Pour BMS : essayer d'extraire des tensions de cellule en 2 octets LE à partir d'un offset connu.
        val cellVoltages = parseTrailingCellVoltages(data, startIndex = 16)

        return EUCData(
            speed = speed,
            voltage = voltage,
            current = current,
            temperature = temperature,
            batteryLevel = batteryLevel,
            distance = distanceMeters / 1000.0,
            power = power,
            timestamp = System.currentTimeMillis(),
            rawData = data,
            manufacturer = manufacturer,
            model = "Gotway B",
            serialNumber = null,
            firmwareVersion = null,
            isCharging = isCharging,
            rideTime = 0,
            cellVoltages = cellVoltages,
            motorTemperature = motorTemperature
        )
    }

    // Parser pour paquets 0xA5-like (command/status). Simple extraction similaire aux legacy.
    private fun parseA5Like(data: ByteArray): EUCData? {
        // Si payload est court, renvoyer null ou une EUCData minimale — ici on tente l'ancienne logique si possible
        return if (data.size >= 16) parseLegacy(data) else null
    }

    // Extrait dynamiquement des tensions de cellules en paires LE (2 octets) depuis startIndex.
    // Retourne null si aucune cellule n'est présente.
    private fun parseTrailingCellVoltages(data: ByteArray, startIndex: Int): List<Double>? {
        if (data.size <= startIndex) return null
        val remaining = data.size - startIndex
        val cellCount = remaining / 2
        if (cellCount <= 0) return null
        val cells = mutableListOf<Double>()
        var idx = startIndex
        repeat(cellCount) {
            if (idx + 1 >= data.size) return@repeat
            val raw = ByteUtils.getUnsignedShortLE(data, idx)
            // raw typically in mV or 0.001V units — tenter mV -> V conversion si valeur plausible (> 1000)
            val cellVoltage = if (raw > 1000) raw / 1000.0 else raw / 100.0
            cells.add(cellVoltage)
            idx += 2
        }
        return if (cells.isNotEmpty()) cells else null
    }

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        return when (commandType) {
            CommandType.LIGHT_ON -> byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x01)
            CommandType.LIGHT_OFF -> byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x01, 0x01, 0x00)
            CommandType.BEEP -> byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x02, 0x01)
            CommandType.POWER_OFF -> byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x03, 0x01)
            CommandType.LIGHT_BRIGHTNESS -> {
                if (value is Int && value in 0..100) {
                    val brightness = (value * 255 / 100).toByte()
                    byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x04, brightness)
                } else byteArrayOf()
            }
            else -> byteArrayOf()
        }
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        return data.speed >= 0 &&
                data.voltage > 30.0 &&
                data.temperature < 85.0 &&
                data.batteryLevel > 3
    }
}
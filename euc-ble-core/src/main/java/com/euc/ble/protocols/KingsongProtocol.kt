package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import java.util.UUID
import kotlin.math.absoluteValue

/**
 * Improved KingSong protocol: tolerant parsing, header resync, safe bounds checks,
 * optional cell voltages parsing, and clamped command generation.
 *
 * File: `euc-ble-core/src/main/java/com/euc/ble/protocols/KingsongProtocol.kt`
 */
class KingsongProtocol : EUCProtocol {

    override val manufacturer: String = "KingSong"
    override val supportedModels: List<String> = listOf(
        "KS-14D", "KS-16", "KS-16S", "KS-16X", "KS-18L", "KS-18XL",
        "KS-19", "KS-S18", "KS-S19", "KS-S20", "KS-S22", "KS-F22"
    )

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.KINGSONG_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString(BLEConstants.KINGSONG_READ_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        return device.manufacturerId == BLEConstants.MANUFACTURER_KINGSONG ||
                device.name.startsWith("KS-", ignoreCase = true) ||
                device.name.contains("KingSong", ignoreCase = true)
    }

    private val header1 = byteArrayOf(0xAA.toByte(), 0x55.toByte())
    private val header2 = byteArrayOf(0x55.toByte(), 0xAA.toByte())
    private val MIN_LENGTH = 16 // flexible minimal length

    private fun findHeaderIndex(data: ByteArray): Int {
        // search for either header sequence to allow some adapter variants
        for (i in 0..(data.size - 2)) {
            if (data[i] == header1[0] && data[i + 1] == header1[1]) return i
            if (data[i] == header2[0] && data[i + 1] == header2[1]) return i
        }
        return -1
    }

    private fun ensureRange(data: ByteArray, offset: Int, length: Int): Boolean {
        return offset >= 0 && data.size >= offset + length
    }

    /**
     * KingSong reverse‑engineered protocol (résumé)
     *
     * Observations générales
     *  - Les paquets KingSong observés commencent typiquement par l'en‑tête \`0xAA 0x55\`.
     *  - Les champs multi‑octets sont encodés en Little Endian (LE).
     *  - Les unités courantes :
     *      - Tension : 0.1 V (ex: valeur 356 -> 35.6 V)
     *      - Vitesse : 0.1 km/h (ex: valeur 300 -> 30.0 km/h)
     *      - Distance : uint32 LE en mètres (conserver en m ou convertir en km selon le modèle)
     *      - Courant : 0.1 A (peut être signé selon le firmware)
     *      - Température : 0.1 °C (peut être signé selon le firmware)
     *  - Taille minimale : généralement ~20 octets, mais certaines variantes peuvent être plus courtes.
     *  - Flux BLE/Serial peut être fragmenté; prévoir resynchronisation sur \`0xAA 0x55\`.
     *
     * Format résumé (extrait / à adapter selon firmware)
     *  - Bytes 0-1 : Header \`0xAA 0x55\`
     *  - Bytes 2-3 : Voltage (uint16 LE, 0.1 V units)
     *  - Bytes 4-5 : Speed (uint16 LE, 0.1 km/h units)
     *  - Bytes 6-9 : Distance (uint32 LE, mètres)
     *  - Bytes 10-11: Current (uint16 LE, 0.1 A units) — vérifier signedness sur les firmwares
     *  - Bytes 12-13: Temperature (uint16 LE, 0.1 °C units) — vérifier signedness
     *  - Byte 14    : Status flags (bit flags: ex. bit0 = charging)
     *  - Byte 15    : Battery percentage (0-100)
     *  - Bytes 16-..: Données additionnelles / statut / padding
     *
     * Exemples observés (hex)
     *  - Paquet type : \`AA 55 64 01 2C 01 40 42 0F 00 E8 03 14 00 01 64 ...\`
     *
     * Variantes et notes pratiques
     *  - Certaines stacks/adaptateurs peuvent émettre paquets différents (headers autres que
     *    \`0xAA 0x55\`) ou reformater le flux. Rendre le parser tolérant aux variantes.
     *  - Tester/détecter la signedness du courant/température (valeurs plausibles négatives).
     *  - Distance : conserver en mètres côté décodage et convertir au besoin dans le modèle
     *    (le code actuel convertit en km pour l'API externe).
     *  - Ne pas faire d'hypothèses strictes sur la longueur exacte : valider la présence des
     *    offsets avant lecture et ignorer/ignorer partiellement les paquets trop courts.
     *  - Kingsong n'envoie généralement pas les tensions de cellules BMS dans ces paquets,
     *    mais d'autres modèles/firmwares peuvent les ajouter en queue : parser dynamiquement
     *    si des paires 16 bits supplémentaires sont présentes.
     *
     * Recommandations de parsing
     *  - Synchroniser sur l'en‑tête \`0xAA 0x55\` puis valider longueur minimale.
     *  - Lire en LE pour tous les entiers multi‑octets.
     *  - Convertir les valeurs fixes (diviser par 10 pour tension/vitesse/courant/temp).
     *  - Tester les flags via \`(statusByte and 0x01) != 0\` pour la charge, etc.
     *  - Être défensif : entourer les lectures par des vérifications d'indice et catcher
     *    les exceptions pour éviter de planter le décodeur.
     */
    override fun decode(data: ByteArray): EUCData? {
        if (data.isEmpty()) return null

        val headerIdx = findHeaderIndex(data)
        if (headerIdx < 0) return null

        if (data.size - headerIdx < MIN_LENGTH) {
            // not enough bytes after header for minimal decoding
            return null
        }

        try {
            val base = headerIdx

            // Safely read fields only if enough bytes remain, fallback to sensible defaults
            val voltage = if (ensureRange(data, base + 2, 2))
                ByteUtils.getUnsignedShortLE(data, base + 2) / 10.0 else 0.0

            val speed = if (ensureRange(data, base + 4, 2))
                ByteUtils.getUnsignedShortLE(data, base + 4) / 10.0 else 0.0

            val distance = if (ensureRange(data, base + 6, 4))
                ByteUtils.getUnsignedIntLE(data, base + 6).toDouble() else 0.0

            val current = if (ensureRange(data, base + 10, 2))
                ByteUtils.getSignedShortLE(data, base + 10) / 10.0 else 0.0

            val temperature = if (ensureRange(data, base + 12, 2))
                ByteUtils.getSignedShortLE(data, base + 12) / 10.0 else 0.0

            val statusByte = if (ensureRange(data, base + 14, 1))
                ByteUtils.getUnsignedByte(data, base + 14) else 0x00.toByte()

            val batteryLevel = if (ensureRange(data, base + 15, 1))
                ByteUtils.getUnsignedByte(data, base + 15).toInt().coerceIn(0, 100) else 0

            val isCharging = (statusByte.toInt() and 0x01) != 0

            // Optional: parse trailing cell voltages if present (pairs of uint16 LE).
            val cellVoltages = mutableListOf<Double>()
            var idx = base + 16
            while (ensureRange(data, idx, 2)) {
                val raw = ByteUtils.getUnsignedShortLE(data, idx)
                // Heuristic: if value looks like mV (e.g. >2000) convert to volts
                val volt = if (raw > 2000) raw / 1000.0 else raw / 100.0
                if (volt > 0.0) cellVoltages.add(volt)
                idx += 2
            }

            val power = voltage * current

            val model = "KingSong" // could be refined if additional identification bytes exist

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
                model = model,
                serialNumber = null,
                firmwareVersion = null,
                isCharging = isCharging,
                rideTime = 0,
                cellVoltages = if (cellVoltages.isNotEmpty()) cellVoltages else null,
                motorTemperature = null
            )
        } catch (e: Exception) {
            // defensive: on any parsing error return null
            return null
        }
    }

    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        return when (commandType) {
            CommandType.LIGHT_ON -> byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01, 0x01)
            CommandType.LIGHT_OFF -> byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01, 0x00)
            CommandType.BEEP -> byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x02, 0x01)
            CommandType.POWER_OFF -> byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x03, 0x01)
            CommandType.LIGHT_BRIGHTNESS -> {
                val intVal = (value as? Int) ?: return byteArrayOf()
                val clamped = intVal.coerceIn(0, 100)
                val brightness = (clamped * 255 / 100).toByte()
                byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x04, brightness)
            }
            else -> byteArrayOf()
        }
    }

    override fun isDeviceReady(data: EUCData): Boolean {
        // Conservative readiness checks: positive voltage, reasonable temperature and battery
        val tempOk = (data.temperature ?: Double.MAX_VALUE) < 75.0
        val voltageOk = data.voltage > 30.0
        val batteryOk = data.batteryLevel >= 5
        return voltageOk && tempOk && batteryOk
    }
}
package com.euc.ble.protocols

import com.euc.ble.core.BLEConstants
import com.euc.ble.core.ByteUtils
import com.euc.ble.frames.ByteByByteFrameParser
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
 */
class KingsongProtocol : EUCProtocol {


    private val header1 = byteArrayOf(0xAA.toByte(), 0x55.toByte())
    private val header2 = byteArrayOf(0x55.toByte(), 0xAA.toByte())
    private val MIN_LENGTH = 20
    private val NANOS_PER_SECOND = 1_000_000_000L
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
        CommandType.SET_LED_MODE
    )

    override fun getServiceUUID(): UUID = UUID.fromString(BLEConstants.KINGSONG_SERVICE_UUID)
    override fun getDataCharacteristicUUID(): UUID =
        UUID.fromString(BLEConstants.KINGSONG_READ_CHARACTERISTIC)

    override fun canHandle(device: EUCDevice): Boolean {
        return device.manufacturerId == BLEConstants.MANUFACTURER_KINGSONG ||
                device.name.startsWith("KS-", ignoreCase = true) ||
                device.name.contains("KingSong", ignoreCase = true)
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
    private var sessionStartTimestampNs: Long? = null
    private var lastRideTimeSeconds: Long = 0L
    private var lastKnownPwm: Double? = null

    init {
        // Start observing frames asynchronously and process them
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            frameReassembler.observeFrames().collect { frame ->
                processFrame(frame)
            }
        }
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
    private fun parseFrame(data: ByteArray): EUCData? {
        if (data.isEmpty()) return null

        val headerIdx = run {
            // trouver l'en-tête dans la trame (souvent 0)
            for (i in 0..(data.size - 2)) {
                if (data[i] == header1[0] && data[i + 1] == header1[1]) return@run i
                if (data[i] == header2[0] && data[i + 1] == header2[1]) return@run i
            }
            -1
        }
        if (headerIdx < 0) return null

        if (data.size - headerIdx < MIN_LENGTH) {
            // pas assez d'octets
            return null
        }

        val messageType = ByteUtils.getUnsignedByte(data, headerIdx + 16)
        return when (messageType) {
            0xA9 -> parseTypeA9(data, headerIdx)
            0xF5 -> {
                parseTypeF5(data, headerIdx)
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

            val distance = if (ensureRange(data, base + 6, 4))
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

            // === Filtres de plage "raisonnable" pour Kingsong ===

            if (voltage !in 40.0..150.0) return null
            if (speed !in 0.0..80.0) return null
            if (temperature !in -40.0..100.0) return null
            if (current !in -200.0..200.0) return null

            val isCharging = (statusByte and 0x01) != 0

            val power = voltage * current
            val rideTimeSeconds = deriveRideTimeSeconds()

            val model = "KingSong"

            return EUCData(
                speed = speed,
                voltage = voltage,
                current = current,
                temperature = temperature,
                batteryLevel = batteryLevel,
                distance = distance,
                power = power,
                pwm = lastKnownPwm,
                timestamp = System.currentTimeMillis(),
                rawData = data,
                manufacturer = manufacturer,
                model = model,
                serialNumber = null,
                firmwareVersion = null,
                isCharging = isCharging,
                rideTime = rideTimeSeconds,
                cellVoltages = null,
                motorTemperature = null,
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseTypeF5(data: ByteArray, base: Int) {
        if (!ensureRange(data, base + 15, 1)) return
        val outputByte = ByteUtils.getUnsignedByte(data, base + 15)
        lastKnownPwm = outputByte / 100.0
    }

    @Synchronized
    private fun deriveRideTimeSeconds(): Long {
        val nowNs = System.nanoTime()
        val startNs = sessionStartTimestampNs ?: nowNs.also {
            sessionStartTimestampNs = it
            lastRideTimeSeconds = 0L
        }
        val elapsedSeconds = ((nowNs - startNs) / NANOS_PER_SECOND).coerceAtLeast(0L)
        lastRideTimeSeconds = maxOf(lastRideTimeSeconds, elapsedSeconds)
        return lastRideTimeSeconds
    }

    private fun estimateBatteryPercent(voltage: Double): Int {
        return (((voltage - 50.0) / (100.0 - 50.0)) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun processFrame(frame: ByteArray) {
        val parsed = parseFrame(frame)
        parsed?.let { _channel.trySend(it) }

    }

    @Synchronized
    override fun close() {
        scope.cancel()
        sessionStartTimestampNs = null
        lastRideTimeSeconds = 0L
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

            else -> byteArrayOf()
        }
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

    override fun isDeviceReady(data: EUCData): Boolean {
        val tempOk = data.temperature < 75.0
        val voltageOk = data.voltage > 30.0
        val batteryOk = data.batteryLevel >= 5
        return voltageOk && tempOk && batteryOk
    }
}

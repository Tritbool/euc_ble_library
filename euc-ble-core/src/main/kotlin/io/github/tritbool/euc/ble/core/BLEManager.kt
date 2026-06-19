package io.github.tritbool.euc.ble.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback as AndroidScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.Companion.PRIVATE
import io.github.tritbool.euc.ble.exceptions.BLEException
import io.github.tritbool.euc.ble.integration.BleBackendEvent
import io.github.tritbool.euc.ble.integration.BleBackendListener
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.ProtocolQuerySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Main BLE manager for Electric Unicycles.
 *
 * This class is responsible for BLE scanning, connection lifecycle management,
 * protocol selection, command transmission, and decoded data delivery.
 *
 * Threading model:
 * - Internal asynchronous work is executed on a background coroutine scope based on
 *   `Dispatchers.IO + SupervisorJob()`.
 * - Public callbacks exposed by this manager (`ConnectionCallback`, `DataCallback`,
 *   and `ErrorCallback`) are not guaranteed to be invoked on the Android main thread.
 * - Callers must explicitly switch to `Dispatchers.Main`, `runOnUiThread`, or an
 *   equivalent UI mechanism before touching Android views.
 *
 * Rationale:
 * - BLE operations, retries, polling, and response timeouts are background work and
 *   should not run on the main thread.
 * - Keeping callback dispatching explicit avoids hiding threading behavior from the
 *   library consumer and keeps the manager usable from non-UI contexts as well.
 */
class BLEManager internal constructor(
    private val context: Context, private val logger: Logger = AndroidLogger(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : BluetoothGattCallback() {
    companion object {
        private const val MIN_QUERY_ATTEMPTS = 1
        private const val MIN_QUERY_TIMEOUT_MS = 200L
    }

    // Configuration
    private var scanTimeout: Long = BLEConstants.DEFAULT_SCAN_TIMEOUT_MS
    private var connectionTimeout: Long = BLEConstants.DEFAULT_CONNECTION_TIMEOUT_MS
    private var autoReconnect: Boolean = true
    private var maxRetries: Int = 3

    // State management
    private var connectionState: BLEConstants.ConnectionState =
        BLEConstants.ConnectionState.DISCONNECTED
    private var currentDevice: EUCDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    // Reconnection state fields
    private var reconnectRetryCount: Int = 0
    private var reconnectJob: Job? = null
    private var manualDisconnect: Boolean = false
    private val reconnectBaseDelayMs: Long = 1000L
    private val maxReconnectDelayMs: Long = 30_000L

    // Protocol management
    @VisibleForTesting(otherwise = PRIVATE)
    internal val protocols: MutableList<EUCProtocol> = mutableListOf()

    @VisibleForTesting(otherwise = PRIVATE)
    internal var currentProtocol: EUCProtocol? = null
    private var metadataMatchedProtocols: List<EUCProtocol> = emptyList()
    private var frameCandidateProtocols: List<EUCProtocol> = emptyList()
    private var queryOrchestrationJob: Job? = null
    private var dataFlowCollectorJob: Job? = null
    private var writeFlowCollectorJob: Job? = null
    private val pendingQueries: MutableMap<String, PendingQueryState> = ConcurrentHashMap()

    // Callbacks
    private var platformScanCallback: AndroidScanCallback? = null
    private var connectionCallback: ConnectionCallback? = null
    private var dataCallback: DataCallback? = null
    private var errorCallback: ErrorCallback? = null

    // Raw frame capture: every raw BLE characteristic notification is emitted here
    private val _rawFrameFlow = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Flow emitting every raw BLE characteristic notification received from the
     * connected device as a defensive copy of the original byte array.
     *
     * This flow can be collected to implement raw frame logging, packet inspection,
     * or custom decoding pipelines independent from the built-in protocol decoder.
     *
     * Threading notes:
     * - Emissions originate from BLE callback handling and therefore must be treated
     *   as background events.
     * - Collectors are responsible for choosing the appropriate collection context.
     *
     * Example:
     * ```kotlin
     * bleManager.rawFrameFlow.collect { bytes ->
     *     outputStream.write(bytes)
     * }
     * ```
     */
    val rawFrameFlow: SharedFlow<ByteArray> = _rawFrameFlow.asSharedFlow()

    private val _queryTraceFlow = MutableSharedFlow<QueryTraceEvent>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val queryTraceFlow: SharedFlow<QueryTraceEvent> = _queryTraceFlow.asSharedFlow()

    // Coroutine management
    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var connectionTimeoutJob: Job? = null

    private data class PendingQueryState(
        val protocolName: String, val query: ProtocolQuerySpec, val attempt: Int, val sentAtMs: Long
    )

    // Device cache
    private val discoveredDevices = ConcurrentHashMap<String, EUCDevice>()

    /**
     * Initialize the BLE Manager
     */
    fun initialize() {
        logger.info("BLEManager", "Initializing BLE Manager")
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            logger.error("BLEManager", "Bluetooth not supported on this device")
            errorCallback?.onError(BLEException("Bluetooth not supported on this device"))
        } else {
            logger.info("BLEManager", "Bluetooth adapter initialized successfully")
        }
    }

    /**
     * Register a protocol for device detection and data processing
     */
    fun registerProtocol(protocol: EUCProtocol) {
        protocols.add(protocol)
    }

    /**
     * Start scanning for EUC devices
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        logger.info("BLEManager", "Starting BLE scan")

        if (connectionState != BLEConstants.ConnectionState.DISCONNECTED) {
            logger.warn("BLEManager", "Cannot scan while connected")
            errorCallback?.onError(BLEException("Cannot scan while connected"))
            return
        }

        if (!isBluetoothEnabled()) {
            logger.error("BLEManager", "Bluetooth is disabled")
            errorCallback?.onError(BLEException("Bluetooth is disabled"))
            return
        }

        discoveredDevices.clear()
        connectionState = BLEConstants.ConnectionState.DISCONNECTED

        scanJob = coroutineScope.launch {
            startBleScan()
        }

        scanTimeoutJob = coroutineScope.launch {
            delay(scanTimeout.milliseconds)
            if (connectionState == BLEConstants.ConnectionState.DISCONNECTED
                && platformScanCallback != null
            ) {
                logger.info("BLEManager", "Scan timeout reached")
                stopScan()
            }
        }
    }

    /**
     * Stop scanning for devices
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        scanJob?.cancel()
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(platformScanCallback)
        platformScanCallback = null
        connectionCallback?.onScanCompleted(discoveredDevices.values.toList())
    }

    /**
     * Connect to a specific device
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect(device: EUCDevice) {
        if (connectionState != BLEConstants.ConnectionState.DISCONNECTED) {
            errorCallback?.onError(BLEException("Already connecting or connected"))
            return
        }

        manualDisconnect = false
        reconnectRetryCount = 0

        currentDevice = device
        connectionState = BLEConstants.ConnectionState.CONNECTING

        // Main connection job
        connectionJob = coroutineScope.launch {
            connectToDevice(device.bluetoothDevice!!)
        }

        connectionTimeoutJob = coroutineScope.launch {
            delay(connectionTimeout.milliseconds)
            if (connectionState == BLEConstants.ConnectionState.CONNECTING) {
                disconnect()
                errorCallback?.onError(BLEException("Connection timeout"))
            }
        }
    }

    /**
     * Disconnect from current device
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        reconnectRetryCount = 0
        cancelPollingOrchestration()
        cancelDataFlowCollection()

        connectionJob?.cancel()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        currentDevice = null
        currentProtocol = null
        metadataMatchedProtocols = emptyList()
        frameCandidateProtocols = emptyList()
        connectionState = BLEConstants.ConnectionState.DISCONNECTED
        connectionCallback?.onDisconnected()
    }

    /**
     * Send a command to the connected device
     */
    fun getCommandSupport(commandType: CommandType): CommandSupport {
        val protocol = currentProtocol ?: return CommandSupport.UNSUPPORTED
        return protocol.getCommandSupport(commandType)
    }

    fun createCommand(commandType: CommandType, value: Any = Unit): ByteArray {
        val protocol = currentProtocol ?: run {
            errorCallback?.onError(BLEException("No protocol selected; cannot create command"))
            return byteArrayOf()
        }
        if (protocol.getCommandSupport(commandType) == CommandSupport.UNSUPPORTED) {
            errorCallback?.onError(
                BLEException("Unsupported command '$commandType' for protocol ${protocol.manufacturer}")
            )
            return byteArrayOf()
        }
        val payload = protocol.createCommand(commandType, value)
        if (payload.isEmpty()) {
            errorCallback?.onError(
                BLEException("Protocol ${protocol.manufacturer} returned empty payload for '$commandType'")
            )
        }
        return payload
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendCommand(commandType: CommandType, value: Any = Unit) {
        val protocol = currentProtocol ?: run {
            errorCallback?.onError(BLEException("No protocol selected; cannot send command"))
            return
        }
        val payload = createCommand(commandType, value)
        if (payload.isEmpty()) return
        sendCommand(payload, protocol.getWriteCharacteristicUUID())
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendCommand(command: ByteArray, characteristicUuid: UUID) {
        if (connectionState != BLEConstants.ConnectionState.CONNECTED) {
            errorCallback?.onError(BLEException("Not connected to a device"))
            return
        }
        if (command.isEmpty()) {
            errorCallback?.onError(BLEException("Cannot send empty command payload"))
            return
        }

        val characteristic = getCharacteristic(characteristicUuid)
        val payload = command.clone() // defensive copy

        characteristic?.let { char ->
            // ensure consistent write type (default)
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // new API 33+ overload
                bluetoothGatt?.writeCharacteristic(char, payload, char.writeType)
            } else {
                // fallback for older APIs
                @Suppress("DEPRECATION") run {
                    char.setValue(payload)
                    bluetoothGatt?.writeCharacteristic(char)
                }
            }
        } ?: run {
            errorCallback?.onError(BLEException("Characteristic not found: $characteristicUuid"))
        }
    }

    /**
     * Get current connection state
     */
    fun getConnectionState(): BLEConstants.ConnectionState = connectionState

    /**
     * Get currently connected device
     */
    fun getConnectedDevice(): EUCDevice? = currentDevice

    /**
     * Check if Bluetooth is enabled
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    // Setters for configuration
    fun setScanTimeout(timeout: Long) {
        this.scanTimeout = timeout
    }

    fun setConnectionTimeout(timeout: Long) {
        this.connectionTimeout = timeout
    }

    fun setAutoReconnect(enabled: Boolean) {
        this.autoReconnect = enabled
    }

    fun setMaxRetries(retries: Int) {
        this.maxRetries = retries
    }

    /**
     * Callback registration methods.
     *
     * Threading contract:
     * - Registered callbacks are invoked from background execution contexts.
     * - No callback registered through these setters is guaranteed to run on the
     *   Android main thread.
     * - UI updates must be marshalled explicitly by the caller.
     */
    // Callback setters
    fun setConnectionCallback(callback: ConnectionCallback) {
        this.connectionCallback = callback
    }

    fun setDataCallback(callback: DataCallback) {
        this.dataCallback = callback
    }

    fun setErrorCallback(callback: ErrorCallback) {
        this.errorCallback = callback
    }

    // Private implementation methods
    @RequiresApi(Build.VERSION_CODES.M)
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    //@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun startBleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT).build()

        platformScanCallback = object : AndroidScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                processScanResult(result)
            }

            override fun onScanFailed(errorCode: Int) {
                errorCallback?.onError(BLEException("Scan failed with error: $errorCode"))
            }
        }

        scanner.startScan(null, settings, platformScanCallback)
        connectionCallback?.onScanStarted()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processScanResult(result: ScanResult) {
        data class WheelManufacturerData(
            val manufacturerId: Int, val data: ByteArray?
        )

        val device = result.device
        val scanRecord = result.scanRecord
        val foundManufacturerData: WheelManufacturerData? = sequenceOf(
            BLEConstants.MANUFACTURER_KINGSONG,
            BLEConstants.MANUFACTURER_GOTWAY,
            BLEConstants.MANUFACTURER_INMOTION,
            BLEConstants.MANUFACTURER_NINEBOT,
            BLEConstants.MANUFACTURER_VETERAN,
            BLEConstants.MANUFACTURER_LEAPERKIM,
        ).firstNotNullOfOrNull { id ->
            scanRecord?.getManufacturerSpecificData(id)
                ?.let { bytes -> WheelManufacturerData(id, bytes) }
        }

        val eucDevice = EUCDevice(
            bluetoothDevice = device,
            name = device.name ?: "Unknown EUC",
            address = device.address,
            manufacturerId = foundManufacturerData?.manufacturerId ?: 0,
            manufacturerData = foundManufacturerData?.data,
            rssi = result.rssi
        )

        // Check if this device is supported by any protocol
        val supportingProtocol = protocols.find { it.canHandle(eucDevice) }
        if (supportingProtocol != null) {
            discoveredDevices[eucDevice.address] = eucDevice
            connectionCallback?.onDeviceDiscovered(eucDevice)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        try {
            bluetoothGatt = device.connectGatt(context, false, this)
        } catch (e: Exception) {
            errorCallback?.onError(BLEException("Failed to connect to device: ${e.message}"))
            disconnect()
        }
    }

    private fun getCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        return bluetoothGatt?.services?.flatMap { service ->
            service.characteristics
        }?.find { characteristic ->
            characteristic.uuid == uuid
        }
    }

    // BluetoothGattCallback implementations
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        super.onConnectionStateChange(gatt, status, newState)

        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                // reset des compteurs de reconnexion
                reconnectRetryCount = 0
                reconnectJob?.cancel()
                reconnectJob = null
                connectionState = BLEConstants.ConnectionState.CONNECTED
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = null
                connectionCallback?.onConnected()
                // Discover services
                gatt.discoverServices()
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                cancelPollingOrchestration()
                connectionState = BLEConstants.ConnectionState.DISCONNECTED
                connectionCallback?.onDisconnected()

                if (!manualDisconnect && autoReconnect && currentDevice != null) {
                    scheduleReconnect()
                } else {
                    // if we don't want to reconnect, reset the counter
                    reconnectRetryCount = 0
                }
            }

            BluetoothProfile.STATE_CONNECTING -> {
                connectionState = BLEConstants.ConnectionState.CONNECTING
            }

            BluetoothProfile.STATE_DISCONNECTING -> {
                connectionState = BLEConstants.ConnectionState.DISCONNECTING
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun scheduleReconnect() {
        // If already in progress or max retries reached, do not reschedule
        if (reconnectJob != null) return
        if (reconnectRetryCount >= maxRetries) {
            connectionCallback?.onConnectionFailed(BLEException("Reconnection failed after $reconnectRetryCount attempts"))
            reconnectRetryCount = 0
            return
        }

        // calculate delay with backoff and jitter
        val multiplier = 1L shl reconnectRetryCount.coerceAtMost(30) // prevent overflow
        val baseDelay = (reconnectBaseDelayMs * multiplier).coerceAtMost(maxReconnectDelayMs)
        val jitter = kotlin.random.Random.nextLong(0, 500)
        val delayMs = (baseDelay + jitter).coerceAtMost(maxReconnectDelayMs)

        reconnectJob = coroutineScope.launch {

            kotlinx.coroutines.delay(delayMs)
            // double-check conditions avant tentative
            if (manualDisconnect) {
                reconnectJob = null
                return@launch
            }
            if (connectionState == BLEConstants.ConnectionState.CONNECTED) {
                reconnectJob = null
                reconnectRetryCount = 0
                return@launch
            }
            reconnectRetryCount++
            connectionState = BLEConstants.ConnectionState.CONNECTING
            try {
                currentDevice?.bluetoothDevice?.let { device ->
                    connectToDevice(device)
                } ?: run {
                    connectionCallback?.onConnectionFailed(BLEException("No device to reconnect"))
                    reconnectJob = null
                }
            } catch (e: Exception) {
                // in case of immediate failure, let the next onConnectionStateChange trigger a new attempt
                reconnectJob = null
            }

        }
    }

    // Optional: helper to explicitly cancel reconnection (used by cleanup if needed)
    private fun cancelReconnectAttempts() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectRetryCount = 0
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        super.onServicesDiscovered(gatt, status)

        if (status == BluetoothGatt.GATT_SUCCESS) {
            val device = currentDevice
            if (device == null) {
                errorCallback?.onError(BLEException("No connected device available for protocol selection"))
                disconnect()
                return
            }

            prepareProtocolCandidates(device)

            if (frameCandidateProtocols.isEmpty()) {
                errorCallback?.onError(BLEException("No protocol found for this device"))
                disconnect()
                return
            }

            frameCandidateProtocols.map { it.getDataCharacteristicUUID() }.distinct()
                .forEach { characteristicUuid ->
                    enableNotifications(characteristicUuid)
                }
            connectionCallback?.onServicesDiscovered(gatt.services)

            when {
                metadataMatchedProtocols.size == 1 -> setActiveProtocol(
                    metadataMatchedProtocols.single(), "metadata"
                )

                frameCandidateProtocols.size == 1 -> setActiveProtocol(
                    frameCandidateProtocols.single(), "single candidate"
                )
            }
        } else {
            errorCallback?.onError(BLEException("Service discovery failed: $status"))
            disconnect()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
    ) {
        super.onCharacteristicChanged(gatt, characteristic, value)
        val data = value.clone()
        handleIncomingBytes(data)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
    ) {
        super.onCharacteristicChanged(gatt, characteristic)
        val raw = characteristic.value ?: return
        val data = raw.clone()
        handleIncomingBytes(data)
    }

    @VisibleForTesting(otherwise = PRIVATE)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal fun handleIncomingBytes(data: ByteArray) {
        _rawFrameFlow.tryEmit(data.clone())
        if (currentProtocol == null) {
            maybeSelectProtocolFromFrame(data)
        }
        currentProtocol?.let { protocol ->
            try {
                matchPendingQueries(protocol, data)
                protocol.decode(data)
            } catch (e: Exception) {
                errorCallback?.onError(BLEException("Data decoding failed: ${e.message}"))
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun maybeSelectProtocolFromFrame(data: ByteArray) {
        if (currentProtocol != null) return
        val candidates =
            if (frameCandidateProtocols.isNotEmpty()) frameCandidateProtocols else protocols
        if (candidates.isEmpty()) return

        val frameMatches = candidates.filter { candidate ->
            runCatching { candidate.looksLikeMyFrames(data) }.getOrDefault(false)
        }

        when {
            frameMatches.size == 1 -> setActiveProtocol(frameMatches.single(), "frame signature")

            metadataMatchedProtocols.size == 1 -> setActiveProtocol(
                metadataMatchedProtocols.single(), "metadata fallback"
            )

            candidates.size == 1 -> setActiveProtocol(
                candidates.single(), "single registered protocol"
            )

            frameMatches.size == 2 -> {
                val manuf1 = frameMatches[0].manufacturer
                val manuf2 = frameMatches[1].manufacturer

                // CANNOT DECIDE BETWEEN LK AND NOSFET
                if (manuf1.equals("Leaperkim", ignoreCase = true) && manuf2.equals(
                        "Nosfet", ignoreCase = true
                    ) || manuf2.equals("Leaperkim", ignoreCase = true) && manuf1.equals(
                        "Nosfet", ignoreCase = true
                    )
                ) {
                    setActiveProtocol(protocols.find {
                        it.manufacturer.equals(
                            "Leaperkim", ignoreCase = true
                        )
                    }!!, "frame signature tie-break LK/Nosfet")
                }

                // CANNOT DECIDE BETWEEN GW AND EB
                if (manuf1.equals("ExtremeBull", ignoreCase = true) && manuf2.equals(
                        "GOTWAY", ignoreCase = true
                    ) || manuf2.equals("Gotway", ignoreCase = true) && manuf1.equals(
                        "ExtremeBull", ignoreCase = true
                    )
                ) {
                    setActiveProtocol(protocols.find {
                        it.manufacturer.equals(
                            "Gotway", ignoreCase = true
                        )
                    }!!, "frame signature tie-break EB/GW")
                }

                // CANNOT DECIDE BETWEEN NINEBOT AND NINEBOTZ
                if (manuf1.equals("ninebot", ignoreCase = true) && manuf2.equals(
                        "ninebot", ignoreCase = true
                    ) || manuf2.equals("ninebot", ignoreCase = true) && manuf1.equals(
                        "ninebot", ignoreCase = true
                    )
                ) {
                    setActiveProtocol(protocols.find {
                        it.supportedModels.contains(
                            "Z10"
                        )
                    }!!, "frame signature tie-break Ninebot/NinebotZ")
                }

            }
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
    ) {
        super.onCharacteristicWrite(gatt, characteristic, status)

        if (status != BluetoothGatt.GATT_SUCCESS) {
            errorCallback?.onError(BLEException("Characteristic write failed: $status"))
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        super.onMtuChanged(gatt, mtu, status)

        if (status == BluetoothGatt.GATT_SUCCESS) {
            connectionCallback?.onMtuChanged(mtu)
        } else {
            errorCallback?.onError(BLEException("MTU change failed: $status"))
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotifications(characteristicUuid: UUID) {
        val characteristic = getCharacteristic(characteristicUuid)
        characteristic?.let { char ->
            val cccdUuid = UUID.fromString(BLEConstants.CCCD_DESCRIPTOR)
            val descriptor = char.getDescriptor(cccdUuid) ?: return@let
            val enableValue =
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.clone() // defensive copy

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Use new API 33+ overload
                bluetoothGatt?.writeDescriptor(descriptor, enableValue)
            } else {
                // Fallback for older APIs (setValue + writeDescriptor)
                @Suppress("DEPRECATION") run {
                    descriptor.value = enableValue
                    bluetoothGatt?.writeDescriptor(descriptor)
                }
            }

            // Enable notifications locally
            bluetoothGatt?.setCharacteristicNotification(char, true)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startPollingOrchestration(protocol: EUCProtocol) {
        cancelPollingOrchestration()
        val plan = protocol.getPollingPlan()
        if (!plan.enabled) return

        queryOrchestrationJob = coroutineScope.launch {
            for (query in plan.startupQueries) {
                executeQueryWithRetry(protocol, query)
            }

            plan.periodicQueries.forEach { query ->
                launch {
                    if (query.initialDelayMs > 0L) delay(query.initialDelayMs.milliseconds)
                    while (connectionState == BLEConstants.ConnectionState.CONNECTED) {
                        executeQueryWithRetry(protocol, query)
                        if (query.intervalMs <= 0L) break
                        delay(query.intervalMs.milliseconds)
                    }
                }
            }
        }
    }

    private fun cancelPollingOrchestration() {
        queryOrchestrationJob?.cancel()
        queryOrchestrationJob = null
        pendingQueries.clear()
    }

    @VisibleForTesting(otherwise = PRIVATE)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal fun startDataFlowCollection(protocol: EUCProtocol) {
        cancelDataFlowCollection()
        dataFlowCollectorJob = coroutineScope.launch {
            protocol.dataFlow.collect { d ->
                dataCallback?.onDataReceived(d)
            }
        }

        writeFlowCollectorJob = coroutineScope.launch {
            protocol.writeFlow.collect { payload ->
                if (payload.isNotEmpty()) {
                    sendCommand(payload, protocol.getWriteCharacteristicUUID())
                }
            }
        }
    }

    @VisibleForTesting(otherwise = PRIVATE)
    internal fun cancelDataFlowCollection() {
        dataFlowCollectorJob?.cancel()
        dataFlowCollectorJob = null
        writeFlowCollectorJob?.cancel()
        writeFlowCollectorJob = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setActiveProtocol(protocol: EUCProtocol, reason: String) {
        if (currentProtocol === protocol) return
        currentProtocol = protocol
        logger.info("BLEManager", "Selected protocol ${protocol.javaClass.simpleName} via $reason")
        startPollingOrchestration(protocol)
        startDataFlowCollection(protocol)
    }

    @VisibleForTesting(otherwise = PRIVATE)
    internal fun prepareProtocolCandidates(device: EUCDevice) {
        metadataMatchedProtocols = protocols.filter { protocol ->
            protocol.canHandle(device)
        }
        frameCandidateProtocols = metadataMatchedProtocols.ifEmpty { protocols.toList() }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun executeQueryWithRetry(protocol: EUCProtocol, query: ProtocolQuerySpec) {
        val retryCount = query.maxRetries.coerceAtLeast(0)
        val totalAttempts = retryCount + MIN_QUERY_ATTEMPTS
        for (attempt in 1..totalAttempts) {
            if (connectionState != BLEConstants.ConnectionState.CONNECTED) return
            val sent = sendProtocolQuery(protocol, query, attempt)
            if (!sent) return

            val timeoutMs = query.responseTimeoutMs.coerceAtLeast(MIN_QUERY_TIMEOUT_MS)
            delay(timeoutMs.milliseconds)

            if (!pendingQueries.containsKey(query.id)) {
                return
            }

            pendingQueries.remove(query.id)
            emitQueryTrace(
                QueryTraceEvent(
                    timestampMs = System.currentTimeMillis(),
                    protocol = protocol.manufacturer,
                    queryId = query.id,
                    commandType = query.commandType,
                    phase = QueryTracePhase.TIMEOUT,
                    attempt = attempt,
                    message = "No matching response after ${timeoutMs}ms"
                )
            )

            if (attempt < totalAttempts) {
                emitQueryTrace(
                    QueryTraceEvent(
                        timestampMs = System.currentTimeMillis(),
                        protocol = protocol.manufacturer,
                        queryId = query.id,
                        commandType = query.commandType,
                        phase = QueryTracePhase.RETRY_SCHEDULED,
                        attempt = attempt + 1,
                        message = "Retry scheduled in ${query.retryBackoffMs}ms"
                    )
                )
                if (query.retryBackoffMs > 0L) delay(query.retryBackoffMs.milliseconds)
            } else {
                emitQueryTrace(
                    QueryTraceEvent(
                        timestampMs = System.currentTimeMillis(),
                        protocol = protocol.manufacturer,
                        queryId = query.id,
                        commandType = query.commandType,
                        phase = QueryTracePhase.FAILED,
                        attempt = attempt,
                        message = "Query exhausted retries"
                    )
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendProtocolQuery(
        protocol: EUCProtocol, query: ProtocolQuerySpec, attempt: Int
    ): Boolean {
        if (protocol.getCommandSupport(query.commandType) == CommandSupport.UNSUPPORTED) {
            emitQueryTrace(
                QueryTraceEvent(
                    timestampMs = System.currentTimeMillis(),
                    protocol = protocol.manufacturer,
                    queryId = query.id,
                    commandType = query.commandType,
                    phase = QueryTracePhase.UNSUPPORTED,
                    attempt = attempt,
                    message = "Command is unsupported by protocol support matrix"
                )
            )
            return false
        }

        val payload = protocol.createCommand(query.commandType, query.value)
        if (payload.isEmpty()) {
            emitQueryTrace(
                QueryTraceEvent(
                    timestampMs = System.currentTimeMillis(),
                    protocol = protocol.manufacturer,
                    queryId = query.id,
                    commandType = query.commandType,
                    phase = QueryTracePhase.UNSUPPORTED,
                    attempt = attempt,
                    message = "Command payload is empty"
                )
            )
            return false
        }

        sendCommand(payload, protocol.getWriteCharacteristicUUID())
        pendingQueries[query.id] = PendingQueryState(
            protocolName = protocol.manufacturer,
            query = query,
            attempt = attempt,
            sentAtMs = System.currentTimeMillis()
        )

        emitQueryTrace(
            QueryTraceEvent(
                timestampMs = System.currentTimeMillis(),
                protocol = protocol.manufacturer,
                queryId = query.id,
                commandType = query.commandType,
                phase = QueryTracePhase.SENT,
                attempt = attempt
            )
        )
        return true
    }

    private fun matchPendingQueries(protocol: EUCProtocol, data: ByteArray) {
        if (pendingQueries.isEmpty()) return
        val now = System.currentTimeMillis()
        val matchedIds = mutableListOf<String>()

        for ((id, state) in pendingQueries) {
            if (state.protocolName != protocol.manufacturer) continue
            if (!protocol.matchesQueryResponse(state.query, data)) continue
            matchedIds.add(id)
            val rawLatency = now - state.sentAtMs
            if (rawLatency < 0L) {
                logger.warn(
                    "BLEQueryTrace",
                    "Negative latency detected for query=${state.query.id} protocol=${state.protocolName}; sentAt=${state.sentAtMs} now=$now"
                )
            }
            emitQueryTrace(
                QueryTraceEvent(
                    timestampMs = now,
                    protocol = state.protocolName,
                    queryId = id,
                    commandType = state.query.commandType,
                    phase = QueryTracePhase.RESPONSE_MATCHED,
                    attempt = state.attempt,
                    latencyMs = if (rawLatency < 0L) 0L else rawLatency
                )
            )
        }

        matchedIds.forEach { pendingQueries.remove(it) }
    }

    private fun emitQueryTrace(event: QueryTraceEvent) {
        _queryTraceFlow.tryEmit(event)
        logger.info(
            "BLEQueryTrace",
            "phase=${event.phase} protocol=${event.protocol} query=${event.queryId} command=${event.commandType} attempt=${event.attempt} latencyMs=${event.latencyMs ?: -1} message=${event.message ?: ""}"
        )
    }

    // Cleanup
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun cleanup() {
        cancelReconnectAttempts()
        scanTimeoutJob?.cancel()
        connectionTimeoutJob?.cancel()
        disconnect()
        scanJob?.cancel()
        connectionJob?.cancel()
        coroutineScope.cancel()
    }
}

/**
 * Scan-related callbacks exposed by [BLEManager].
 *
 * Threading contract:
 * - These callbacks are invoked from background execution contexts.
 * - They are not guaranteed to run on the Android main thread.
 *
 * Implications for callers:
 * - Do not update Android views directly from these methods unless you explicitly
 *   switch to the main thread first.
 * - Prefer forwarding these events to a `Flow`, `StateFlow`, `LiveData`, or another
 *   application-level state container consumed by the UI layer.
 */
// Callback interfaces
sealed interface ScanCallback {
    fun onScanStarted() {}
    fun onDeviceDiscovered(device: EUCDevice) {}
    fun onScanCompleted(devices: List<EUCDevice>) {}
}

/**
 * Connection- and GATT-related callbacks exposed by [BLEManager].
 *
 * This callback extends [ScanCallback], so it also receives scan lifecycle events.
 *
 * Threading contract:
 * - Methods may be invoked either from Android BLE callback paths or from the
 *   manager's background coroutine scope.
 * - No method in this callback is guaranteed to run on the Android main thread.
 *
 * Recommended usage:
 * - Perform non-UI work directly here if needed.
 * - For UI updates, switch explicitly to `Dispatchers.Main`, `runOnUiThread`, or
 *   publish the event into a UI-observed state holder.
 */
sealed class ConnectionCallback : io.github.tritbool.euc.ble.core.ScanCallback {
    open fun onConnected() {}
    open fun onDisconnected() {}
    open fun onConnectionFailed(error: BLEException) {}
    open fun onServicesDiscovered(services: List<BluetoothGattService>) {}
    open fun onMtuChanged(mtu: Int) {}
}

/**
 * Default adapter that forwards [ConnectionCallback] events to a [BleBackendListener].
 *
 * Threading contract:
 * - Forwarded events keep the original threading behavior of [BLEManager].
 * - Listener methods are therefore not guaranteed to run on the Android main thread.
 *
 * Consumers of [BleBackendListener] must explicitly switch to the main thread before
 * performing UI work.
 */
class ListenerConnectionCallback(
    private val listener: BleBackendListener?, private val bleManager: BLEManager
) : ConnectionCallback() {

    override fun onScanStarted() {
        listener?.onEvent(BleBackendEvent.ScanStarted)
    }

    override fun onDeviceDiscovered(device: EUCDevice) {
        listener?.onEvent(BleBackendEvent.DeviceDiscovered(device))
    }

    override fun onScanCompleted(devices: List<EUCDevice>) {
        listener?.onEvent(BleBackendEvent.ScanCompleted(devices))
    }

    override fun onConnected() {
        listener?.onEvent(BleBackendEvent.Connected(bleManager.getConnectedDevice()))
    }

    override fun onDisconnected() {
        listener?.onEvent(BleBackendEvent.Disconnected)
    }

    override fun onConnectionFailed(error: BLEException) {
        listener?.onEvent(BleBackendEvent.Error(error))
    }

    override fun onServicesDiscovered(services: List<BluetoothGattService>) {
        listener?.onEvent(BleBackendEvent.ServicesDiscovered(services.map { it.uuid }))
    }

    override fun onMtuChanged(mtu: Int) {
        listener?.onEvent(BleBackendEvent.MtuChanged(mtu))
    }
}

/**
 * Callback receiving decoded [EUCData] frames.
 *
 * Threading contract:
 * - Invoked from the manager's background coroutine scope.
 * - Not guaranteed to run on the Android main thread.
 *
 * Recommended usage:
 * - Perform parsing, aggregation, logging, persistence, or domain processing directly.
 * - For UI updates, switch explicitly to the main thread or publish the value through
 *   a UI-observed state container.
 */
interface DataCallback {
    fun onDataReceived(data: EUCData)
}

/**
 * Callback receiving errors produced by [BLEManager].
 *
 * Threading contract:
 * - Invoked from background execution contexts.
 * - Not guaranteed to run on the Android main thread.
 *
 * Recommended usage:
 * - Handle logging, metrics, and state transitions directly.
 * - Switch explicitly to the main thread before displaying Android UI such as
 *   dialogs, snackbars, or toasts.
 */
interface ErrorCallback {
    fun onError(error: BLEException)
}

enum class QueryTracePhase {
    SENT, RESPONSE_MATCHED, TIMEOUT, RETRY_SCHEDULED, UNSUPPORTED, FAILED
}

data class QueryTraceEvent(
    val timestampMs: Long,
    val protocol: String,
    val queryId: String,
    val commandType: CommandType,
    val phase: QueryTracePhase,
    val attempt: Int,
    val latencyMs: Long? = null,
    val message: String? = null
)

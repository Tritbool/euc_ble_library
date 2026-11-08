package io.github.tritbool.euc.ble.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
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
import io.github.tritbool.euc.ble.models.BMSData
import io.github.tritbool.euc.ble.models.EUCData
import io.github.tritbool.euc.ble.models.EUCDevice
import io.github.tritbool.euc.ble.models.WheelSecurityState
import io.github.tritbool.euc.ble.models.WheelSecurityEvent
import io.github.tritbool.euc.ble.protocols.CommandSupport
import io.github.tritbool.euc.ble.protocols.CommandType
import io.github.tritbool.euc.ble.protocols.EUCProtocol
import io.github.tritbool.euc.ble.protocols.EucFingerprintDatabase
import io.github.tritbool.euc.ble.protocols.GattSignature
import io.github.tritbool.euc.ble.protocols.InMotionProtocol
import io.github.tritbool.euc.ble.protocols.NosfetProtocol
import io.github.tritbool.euc.ble.protocols.NosfetFrameDetector
import io.github.tritbool.euc.ble.protocols.ProtocolWriteType
import io.github.tritbool.euc.ble.session.PollingSession
import io.github.tritbool.euc.ble.session.ProtocolSelector
import io.github.tritbool.euc.ble.session.ReconnectPolicy
import io.github.tritbool.euc.ble.session.WriteSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import android.bluetooth.le.ScanCallback as AndroidScanCallback

/**
 * Main BLE manager for Electric Unicycles.
 *
 * This class is responsible for BLE scanning, connection lifecycle management,
 * protocol selection, command transmission, and decoded data delivery.
 *
 * Threading model:
 * - Internal asynchronous work runs in a manager-owned child scope of the supplied parent
 *   scope, or `Dispatchers.IO` when no parent is supplied.
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
/**
 * Android-backed implementation of the [Logger] abstraction.
 *
 * This lives inside the Android BLE boundary: framework-facing code (EucBleClient / BLEManager)
 * depends on it, while pure logic (protocols, frames, models) only sees the [Logger] interface.
 */
class AndroidLogger : Logger {
    override fun verbose(tag: String, message: String) {
        android.util.Log.v(tag, message)
    }

    override fun debug(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    override fun info(tag: String, message: String) {
        android.util.Log.i(tag, message)
    }

    override fun warn(tag: String, message: String) {
        android.util.Log.w(tag, message)
    }

    override fun error(tag: String, message: String) {
        android.util.Log.e(tag, message)
    }

    override fun error(tag: String, message: String, throwable: Throwable) {
        android.util.Log.e(tag, message, throwable)
    }
}

class BLEManager internal constructor(
    private val context: Context, private val logger: Logger = AndroidLogger(),
    parentScope: CoroutineScope? = null,
    private val monotonicMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    reconnectRandom: EucRandom = SecureEucRandom()
) : BluetoothGattCallback() {
    private val sessionJob = SupervisorJob(parentScope?.coroutineContext?.get(Job))
    private val sessionScope = CoroutineScope(
        parentScope?.coroutineContext?.plus(sessionJob) ?: Dispatchers.IO + sessionJob
    )
    private val reconnectPolicy = ReconnectPolicy()
    private val reconnectRandom = reconnectRandom

    companion object {
        private const val TAG = "BLEManager"

        val ENABLE_NOTIFICATION_VALUE_COMPAT = byteArrayOf(0x01, 0x00)
        val ENABLE_INDICATION_VALUE_COMPAT = byteArrayOf(0x02, 0x00)
        val DISABLE_NOTIFICATION_VALUE_COMPAT = byteArrayOf(0x00, 0x00)
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

    // Protocol management
    @VisibleForTesting(otherwise = PRIVATE)
    internal val protocols: MutableList<EUCProtocol> = mutableListOf()

    @VisibleForTesting(otherwise = PRIVATE)
    internal var currentProtocol: EUCProtocol? = null
    private val nosfetFrameDetector = NosfetFrameDetector()
    private var currentSelectionReason: ProtocolSelectionReason? = null
    private var protocolSelectionMode: ProtocolSelectionMode = ProtocolSelectionMode.AUTO
    private var forcedProtocol: EUCProtocol? = null
    private var awaitingManualProtocolSelection: Boolean = false
    private var pollingSession: PollingSession? = null
    private var dataFlowCollectorJob: Job? = null
    private var writeFlowCollectorJob: Job? = null
    private var securityFlowCollectorJob: Job? = null
    private var securityEventCollectorJob: Job? = null
    private val _securityStateFlow = MutableStateFlow<WheelSecurityState?>(null)
    /** Null without a selected security-capable protocol; state survives collector changes. */
    val securityStateFlow: StateFlow<WheelSecurityState?> = _securityStateFlow.asStateFlow()
    private val _securityEventFlow = MutableSharedFlow<WheelSecurityEvent>(
        extraBufferCapacity = BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** No replay; bounded, best-effort notifications, including repeated authentication status. */
    val securityEventFlow: SharedFlow<WheelSecurityEvent> = _securityEventFlow.asSharedFlow()

    // Serialized GATT write queue (see sendCommand)
    private val writeSession = WriteSession(sessionScope) {
        errorCallback?.onError(BLEException("Write session failed: ${it.message}"))
    }
    @Volatile
    private var writeGeneration = 0L

    // Callbacks
    private var platformScanCallback: AndroidScanCallback? = null
    private var connectionCallback: ConnectionCallback? = null
    private var dataCallback: DataCallback? = null
    private var errorCallback: ErrorCallback? = null

    private val trafficLock = Any()
    private var trafficSequence = 0L
    private val _trafficFlow = MutableSharedFlow<TrafficEvent>(
        extraBufferCapacity = BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * RX notifications and TX writes accepted by Android, captured before decoding / ack waiting.
     * No replay or history is retained without collectors. Subscribe before connecting to capture
     * initialization. The shared buffer is bounded and drops oldest events for slow collectors;
     * sequence gaps reveal missed events. Capture never suspends for collectors or performs I/O.
     * Consumers must treat event bytes as read-only and perform storage on a background context.
     */
    val trafficFlow: SharedFlow<TrafficEvent> = _trafficFlow.asSharedFlow()

    /**
     * Flow emitting every raw BLE characteristic notification received from the
     * connected device as a defensive snapshot of the original byte array.
     * This is an RX-only view of [trafficFlow], sharing its payloads and bounded buffer.
     * Treat bytes as read-only. Notification chunks are not necessarily complete protocol frames.
     * The public type is now Flow rather than SharedFlow; ordinary collect usage is unchanged.
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
    val rawFrameFlow: Flow<ByteArray> = trafficFlow
        .filter { it.direction == TrafficDirection.RX }
        .map { it.bytes }

    private fun captureTraffic(direction: TrafficDirection, characteristicUuid: UUID, bytes: ByteArray) {
        synchronized(trafficLock) {
            _trafficFlow.tryEmit(
                TrafficEvent(
                    sequence = ++trafficSequence,
                    timestampMs = System.currentTimeMillis(),
                    direction = direction,
                    characteristicUuid = characteristicUuid,
                    bytes = bytes.clone()
                )
            )
        }
    }

    private val _queryTraceFlow = MutableSharedFlow<QueryTraceEvent>(
        extraBufferCapacity = BLEConstants.DEFAULT_FLOW_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val queryTraceFlow: SharedFlow<QueryTraceEvent> = _queryTraceFlow.asSharedFlow()

    // Coroutine management
    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    private var cleanedUp = false

    // Device cache
    private val discoveredDevices = ConcurrentHashMap<String, EUCDevice>()
    private val bluetoothDevicesByAddress = ConcurrentHashMap<String, BluetoothDevice>()

    /**
     * Initialize the BLE Manager
     */
    fun initialize() {
        logger.info(TAG, "Initializing BLE Manager")
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            logger.error(TAG, "Bluetooth not supported on this device")
            errorCallback?.onError(BLEException("Bluetooth not supported on this device"))
        } else {
            logger.info(TAG, "Bluetooth adapter initialized successfully")
        }
    }

    /**
     * Register a protocol for device detection and data processing
     */
    fun registerProtocol(protocol: EUCProtocol) {
        protocols.add(protocol)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setProtocolSelectionMode(mode: ProtocolSelectionMode) {
        protocolSelectionMode =
            if (mode == ProtocolSelectionMode.FORCED && forcedProtocol == null) {
                errorCallback?.onError(BLEException("Cannot enable forced protocol mode without a forced protocol"))
                ProtocolSelectionMode.AUTO
            } else {
                mode
            }
        if (protocolSelectionMode != ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK) {
            awaitingManualProtocolSelection = false
        }
        if (protocolSelectionMode == ProtocolSelectionMode.FORCED) {
            maybeActivateForcedProtocol()
        } else if (protocolSelectionMode == ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK && currentProtocol == null) {
            notifyProtocolSelectionRequired()
        }
    }

    fun getProtocolSelectionMode(): ProtocolSelectionMode = protocolSelectionMode

    /**
     * Returns all registered protocols so the caller can inspect and choose one if needed.
     */
    fun getRegisteredProtocols(): List<EUCProtocol> = protocols.toList()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun selectProtocol(protocol: EUCProtocol): Boolean {
        if (currentDevice == null) {
            errorCallback?.onError(BLEException("No connected device available for manual protocol selection"))
            return false
        }
        if (!protocols.contains(protocol)) {
            errorCallback?.onError(BLEException("Protocol '${protocolIdentifier(protocol)}' is not registered"))
            return false
        }
        awaitingManualProtocolSelection = false
        return activateProtocolIfReady(
            protocol,
            ProtocolSelectionReason.MANUAL_FALLBACK,
            "manual fallback"
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun forceProtocol(protocol: EUCProtocol): Boolean {
        if (!protocols.contains(protocol)) {
            errorCallback?.onError(BLEException("Protocol '${protocolIdentifier(protocol)}' is not registered"))
            return false
        }
        forcedProtocol = protocol
        protocolSelectionMode = ProtocolSelectionMode.FORCED
        awaitingManualProtocolSelection = false
        return maybeActivateForcedProtocol(protocol) ?: true
    }

    fun clearForcedProtocol() {
        forcedProtocol = null
        if (protocolSelectionMode == ProtocolSelectionMode.FORCED) {
            protocolSelectionMode = ProtocolSelectionMode.AUTO
        }
    }

    /**
     * Start scanning for EUC devices
     */
    @RequiresApi(Build.VERSION_CODES.M)
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan() {
        logger.info(TAG, "Starting BLE scan")

        if (connectionState != BLEConstants.ConnectionState.DISCONNECTED) {
            logger.warn(TAG, "Cannot scan while connected")
            errorCallback?.onError(BLEException("Cannot scan while connected"))
            return
        }

        if (!isBluetoothEnabled()) {
            logger.error(TAG, "Bluetooth is disabled")
            errorCallback?.onError(BLEException("Bluetooth is disabled"))
            return
        }

        discoveredDevices.clear()
        connectionState = BLEConstants.ConnectionState.DISCONNECTED

        scanJob = sessionScope.launch {
            startBleScan()
        }

        scanTimeoutJob = sessionScope.launch {
            delay(scanTimeout.milliseconds)
            if (connectionState == BLEConstants.ConnectionState.DISCONNECTED
                && platformScanCallback != null
            ) {
                logger.info(TAG, "Scan timeout reached")
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

        val androidDevice = bluetoothDevicesByAddress[device.address]
        if (androidDevice == null) {
            val error = BLEException("No Android BluetoothDevice available for address ${device.address}")
            errorCallback?.onError(error)
            connectionCallback?.onConnectionFailed(error)
            return
        }

        manualDisconnect = false
        reconnectRetryCount = 0
        currentDevice = device
        connectionState = BLEConstants.ConnectionState.CONNECTING
        connectionCallback?.onConnecting()

        // Main connection job
        connectionJob = sessionScope.launch {
            connectToDevice(androidDevice)
        }

        startConnectionTimeout(isReconnectAttempt = false)
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
        resetProtocolSession()

        connectionJob?.cancel()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        currentDevice = null
        currentProtocol = null
        awaitingManualProtocolSelection = false
        connectionState = BLEConstants.ConnectionState.DISCONNECTED
        connectionCallback?.onDisconnected()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun failConnection(error: BLEException) {
        manualDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = null
        reconnectRetryCount = 0
        cancelPollingOrchestration()
        cancelDataFlowCollection()
        resetProtocolSession()
        connectionJob?.cancel()
        connectionJob = null
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
        }
        bluetoothGatt = null
        currentDevice = null
        currentProtocol = null
        awaitingManualProtocolSelection = false
        connectionState = BLEConstants.ConnectionState.DISCONNECTED
        errorCallback?.onError(error)
        connectionCallback?.onConnectionFailed(error)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startConnectionTimeout(isReconnectAttempt: Boolean) {
        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = sessionScope.launch {
            delay(connectionTimeout.milliseconds)
            if (connectionState != BLEConstants.ConnectionState.CONNECTING) return@launch
            val error = BLEException("Connection timeout")
            if (!isReconnectAttempt) {
                failConnection(error)
                return@launch
            }
            connectionTimeoutJob = null
            connectionJob?.cancel()
            connectionJob = null
            bluetoothGatt?.close()
            bluetoothGatt = null
            connectionState = BLEConstants.ConnectionState.DISCONNECTED
            errorCallback?.onError(error)
            scheduleReconnect()
        }
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
        val generation = writeGeneration
        val protocol = currentProtocol ?: run {
            errorCallback?.onError(BLEException("No protocol selected; cannot send command"))
            return
        }
        val payload = createCommand(commandType, value)
        if (payload.isEmpty()) return
        val sensitivePayload = commandType == CommandType.AUTHENTICATE ||
            commandType == CommandType.SET_PASSWORD || commandType == CommandType.CHANGE_PASSWORD ||
            commandType == CommandType.REMOVE_PASSWORD
        if (generation != writeGeneration || currentProtocol !== protocol) {
            if (sensitivePayload) payload.fill(0)
            return
        }
        try {
            sendCommand(payload, protocol.getWriteCharacteristicUUID())
        } finally {
            if (sensitivePayload) payload.fill(0)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendCommand(command: ByteArray, characteristicUuid: UUID) {
        enqueueCommand(command, characteristicUuid)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enqueueCommand(
        command: ByteArray, characteristicUuid: UUID,
        valid: () -> Boolean = { true },
        accepted: () -> Unit = {},
        failed: () -> Unit = {}
    ) {
        val generation = writeGeneration
        if (connectionState != BLEConstants.ConnectionState.CONNECTED) {
            errorCallback?.onError(BLEException("Not connected to a device"))
            failed()
            return
        }
        if (command.isEmpty()) {
            errorCallback?.onError(BLEException("Cannot send empty command payload"))
            failed()
            return
        }
        if (currentProtocol?.canSendCommand(command) == false) {
            errorCallback?.onError(BLEException("Command blocked by protocol security state or pending operation"))
            failed()
            return
        }

        val characteristic = getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            errorCallback?.onError(BLEException("Characteristic not found: $characteristicUuid"))
            failed()
            return
        }

        // Android allows a single outstanding GATT operation: a write issued while another
        // one (or a CCCD descriptor write) is in flight is rejected and never reaches the
        // wheel. Writes are therefore serialized through a queue and retried when busy.
        val protocol = currentProtocol
        val payloadSize = command.size
        var blocked = false
        writeSession.enqueue(command,
            valid = { !blocked && generation == writeGeneration &&
                connectionState == BLEConstants.ConnectionState.CONNECTED && valid() },
            submit = { bytes ->
                if (protocol?.canSendCommand(bytes) == false) {
                    blocked = true
                    errorCallback?.onError(BLEException("Command blocked by protocol security state or pending operation"))
                    failed()
                    false
                } else {
                    val target = getCharacteristic(characteristicUuid)
                    if (target == null) {
                        blocked = true
                        errorCallback?.onError(BLEException("Characteristic not found: $characteristicUuid"))
                        failed()
                        false
                    } else writeCharacteristicNow(target, bytes)
                }
            },
            retryable = { protocol?.canRetryCommand(it) != false },
            accepted = { bytes ->
                if (generation == writeGeneration && currentProtocol === protocol) {
                    protocol?.onCommandSubmitted(bytes)
                    accepted()
                }
                captureTraffic(TrafficDirection.TX, characteristicUuid, bytes)
            },
            failed = { attempts ->
                errorCallback?.onError(BLEException("BLE stack rejected write after $attempts attempts ($payloadSize bytes)"))
                failed()
            })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun writeCharacteristicNow(char: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        val gatt = bluetoothGatt ?: return false
        val writeType = resolveWriteType(char)
        char.writeType = writeType

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // new API 33+ overload
                gatt.writeCharacteristic(char, payload, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                // fallback for older APIs
                @Suppress("DEPRECATION")
                char.setValue(payload)
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(char)
            }
        } catch (e: Exception) {
            logger.warn(TAG, "writeCharacteristic failed: ${e.message}")
            false
        }
    }

    /**
     * Resolve the GATT write type to use for a characteristic.
     *
     * The protocol may declare a preferred write type (see [EUCProtocol.preferredWriteType]); it is
     * honoured when the characteristic supports it. Otherwise the write type is derived from the
     * properties advertised by the characteristic, which is important for serial-to-BLE bridges
     * (Gotway/Begode FFE1) that only expose PROPERTY_WRITE_NO_RESPONSE and reject
     * write-with-response.
     */
    internal fun resolveWriteType(characteristic: BluetoothGattCharacteristic): Int {
        val properties = characteristic.properties
        val supportsWithResponse = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val supportsNoResponse =
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

        return when (currentProtocol?.preferredWriteType ?: ProtocolWriteType.AUTO) {
            ProtocolWriteType.NO_RESPONSE ->
                if (supportsNoResponse || !supportsWithResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }

            ProtocolWriteType.WITH_RESPONSE ->
                if (supportsWithResponse || !supportsNoResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }

            ProtocolWriteType.AUTO ->
                if (!supportsWithResponse && supportsNoResponse) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
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
        val device = result.device
        val name = device.name ?: result.scanRecord?.deviceName ?: "Unknown"

        val eucDevice = EUCDevice(
            name = name,
            address = device.address,
            manufacturerId = 0,       // inconnu jusqu'à la connexion GATT
            manufacturerData = null,  // idem
            rssi = result.rssi
        )

        discoveredDevices[eucDevice.address] = eucDevice
        bluetoothDevicesByAddress[eucDevice.address] = device
        connectionCallback?.onDeviceDiscovered(eucDevice)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        try {
            bluetoothGatt = device.connectGatt(context, false, this)
                ?: throw IllegalStateException("Android returned no GATT connection")
        } catch (e: Exception) {
            failConnection(BLEException("Failed to connect to device: ${e.message}"))
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
        if (gatt !== bluetoothGatt) return

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
                if (bluetoothGatt === gatt && connectionState == BLEConstants.ConnectionState.CONNECTED) {
                    gatt.discoverServices()
                }
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                val disconnectedDevice = currentDevice
                cancelPollingOrchestration()
                cancelDataFlowCollection()
                resetProtocolSession()
                currentProtocol = null
                connectionState = BLEConstants.ConnectionState.DISCONNECTED
                connectionTimeoutJob?.cancel()
                connectionTimeoutJob = null
                bluetoothGatt = null
                gatt.close()
                connectionCallback?.onDisconnected()

                if (!manualDisconnect && autoReconnect && currentDevice === disconnectedDevice &&
                    connectionState == BLEConstants.ConnectionState.DISCONNECTED
                ) {
                    scheduleReconnect()
                } else if (currentDevice === disconnectedDevice) {
                    // if we don't want to reconnect, reset the counter
                    reconnectRetryCount = 0
                }
            }

            BluetoothProfile.STATE_CONNECTING -> {
                connectionState = BLEConstants.ConnectionState.CONNECTING
                connectionCallback?.onConnecting()
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
        // calculate delay with backoff and jitter
        val delayMs = reconnectPolicy.delayForRetry(reconnectRetryCount, maxRetries, reconnectRandom)
        if (delayMs == null) {
            connectionCallback?.onConnectionFailed(
                BLEException("Reconnection failed after $reconnectRetryCount attempts")
            )
            reconnectRetryCount = 0
            return
        }

        reconnectJob = sessionScope.launch {

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
            reconnectJob = null
            connectionCallback?.onConnecting()
            if (manualDisconnect) return@launch
            try {
                val androidDevice = currentDevice?.let { bluetoothDevicesByAddress[it.address] }
                if (androidDevice != null) {
                    connectionJob = sessionScope.launch { connectToDevice(androidDevice) }
                    startConnectionTimeout(isReconnectAttempt = true)
                } else {
                    failConnection(BLEException("No device to reconnect"))
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
        if (gatt !== bluetoothGatt) return

        if (status != BluetoothGatt.GATT_SUCCESS) {
            errorCallback?.onError(BLEException("Service discovery failed: $status"))
            disconnect()
            return
        }

        val device = currentDevice
        if (device == null) {
            errorCallback?.onError(BLEException("No connected device available for protocol selection"))
            disconnect()
            return
        }

        // Handle forced protocol mode
        if (protocolSelectionMode == ProtocolSelectionMode.FORCED) {
            val forced = forcedProtocol
            if (forced == null) {
                errorCallback?.onError(BLEException("Forced protocol is not registered"))
                disconnect()
                return
            }
            forced.getCandidateDataCharacteristicUUIDs().distinct()
                .forEach { enableNotifications(it) }
            connectionCallback?.onServicesDiscovered(gatt.services)
            if (!activateProtocolIfReady(
                    forced,
                    ProtocolSelectionReason.FORCED,
                    "forced override"
                )
            ) {
                disconnect()
            }
            return
        }

        // Nosfet shares the Veteran serial bridge; its explicit name takes precedence over GATT.
        if (device.name.contains("nosfet", ignoreCase = true)) {
            val nosfet = protocols.filterIsInstance<NosfetProtocol>().singleOrNull()
            if (nosfet != null) {
                nosfet.getCandidateDataCharacteristicUUIDs().distinct()
                    .forEach { enableNotifications(it) }
                connectionCallback?.onServicesDiscovered(gatt.services)
                if (!activateProtocolIfReady(
                        nosfet,
                        ProtocolSelectionReason.AUTO_DEVICE_NAME,
                        "explicit Nosfet device name"
                    )
                ) {
                    disconnect()
                }
                return
            }
        }

        // Try GATT fingerprint matching for other device names.
        logger.debug(TAG + ":onServicesDiscovered", "NB Found services: ${gatt.services.size}")
        logger.debug(TAG + ":onServicesDiscovered", "Services: ${gatt.services}")


        gatt.services.forEach { service ->
            logger.debug(TAG + ":onServicesDiscovered", "Service UUID: ${service.uuid}")
            // Optional: Log characteristics count
            logger.debug(
                TAG + ":onServicesDiscovered",
                "  -> ${service.characteristics.size} characteristics"
            )
            service.characteristics.forEach { characteristic ->
                logger.debug(
                    TAG + ":onServicesDiscovered",
                    "  -> ${characteristic.uuid}"
                )
            }
        }
        val fingerprintMatchRaw = selectByGattFingerprint(gatt.services)
        val fingerprintMatch = fingerprintMatchRaw.first
        val fingerprintMatchVersion = fingerprintMatchRaw.second

        logger.info(
            TAG,
            "FOUND protocol ${fingerprintMatch?.javaClass?.simpleName ?: "NONE"} with version $fingerprintMatchVersion"
        )

        connectionCallback?.onServicesDiscovered(gatt.services)



        if (fingerprintMatch != null) {
            // Refine with device name when a more-specific sub-protocol is registered

            logger.info(
                TAG,
                "Checking subclasses for protocol $fingerprintMatch with device name ${device.name}"
            )
            val subclassOverride = selectSubclassByDeviceName(fingerprintMatch, device.name)
            val selected = subclassOverride ?: fingerprintMatch

            val reason = if (subclassOverride != null) ProtocolSelectionReason.AUTO_DEVICE_NAME
            else ProtocolSelectionReason.AUTO_GATT_FINGERPRINT
            val logReason = if (subclassOverride != null) "GATT fingerprint + device name"
            else "GATT fingerprint"
            selected.getCandidateDataCharacteristicUUIDs().distinct()
                .forEach { enableNotifications(it) }
            if (!activateProtocolIfReady(selected, reason, logReason, fingerprintMatchVersion)) {
                disconnect()
            }
        } else {
            // No fingerprint match — try device name matching before falling back to manual
            logger.info(TAG, "Trying to get protocol for device ${device.name}")
            val deviceNameMatch = selectByDeviceName(device.name)
            if (deviceNameMatch != null) {
                deviceNameMatch.getCandidateDataCharacteristicUUIDs().distinct()
                    .forEach { enableNotifications(it) }
                if (!activateProtocolIfReady(
                        deviceNameMatch,
                        ProtocolSelectionReason.AUTO_DEVICE_NAME,
                        "device name"
                    )
                ) {
                    disconnect()
                }
            } else {
                // No automatic match — caller must choose manually
                when (protocolSelectionMode) {
                    ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK -> {
                        protocols.forEach { proto ->
                            proto.getCandidateDataCharacteristicUUIDs().distinct()
                                .forEach { enableNotifications(it) }
                        }
                        notifyProtocolSelectionRequired()
                    }

                    else -> {
                        val nosfet = protocols.filterIsInstance<NosfetProtocol>().singleOrNull()
                        if (nosfet != null && nosfet.getCandidateDataCharacteristicUUIDs().any {
                                getCharacteristic(it) != null
                            }
                        ) {
                            nosfet.getCandidateDataCharacteristicUUIDs().distinct()
                                .forEach { enableNotifications(it) }
                            return
                        }
                        errorCallback?.onError(BLEException("No protocol found for this device; register its fingerprint or use AUTO_WITH_MANUAL_FALLBACK mode"))
                        disconnect()
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
    ) {
        if (gatt !== bluetoothGatt) return
        val data = value.clone()
        handleIncomingBytes(data, characteristic.uuid)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
    ) {
        if (gatt !== bluetoothGatt) return
        val raw = characteristic.value ?: return
        val data = raw.clone()
        handleIncomingBytes(data, characteristic.uuid)
    }

    @VisibleForTesting(otherwise = PRIVATE)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal fun handleIncomingBytes(data: ByteArray, characteristicUuid: UUID) {
        captureTraffic(TrafficDirection.RX, characteristicUuid, data)
        if (protocolSelectionMode != ProtocolSelectionMode.FORCED &&
            currentSelectionReason != ProtocolSelectionReason.MANUAL_FALLBACK &&
            currentProtocol !is NosfetProtocol
        ) {
            val nosfet = protocols.filterIsInstance<NosfetProtocol>().singleOrNull()
            if (nosfet != null && characteristicUuid in nosfet.getCandidateDataCharacteristicUUIDs()) {
                val frame = nosfetFrameDetector.detect(data)
                if (frame != null && activateProtocolIfReady(
                        nosfet, ProtocolSelectionReason.AUTO_TELEMETRY, "Nosfet firmware in telemetry"
                    )
                ) {
                    nosfet.decode(frame)
                    return
                }
            }
        }
        currentProtocol?.let { protocol ->
            try {
                pollingSession?.onNotification(data)
                protocol.decode(data)
            } catch (e: Exception) {
                errorCallback?.onError(BLEException("Data decoding failed: ${e.message}"))
            }
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
    ) {
        if (gatt !== bluetoothGatt) return
        writeSession.onAcknowledgment()

        if (status != BluetoothGatt.GATT_SUCCESS) {
            errorCallback?.onError(BLEException("Characteristic write failed: $status"))
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {

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
                ENABLE_NOTIFICATION_VALUE_COMPAT.clone() // defensive copy

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
        pollingSession = PollingSession(protocol, sessionScope,
            now = monotonicMillis,
            trace = { event -> emitQueryTrace(event.copy(timestampMs = System.currentTimeMillis())) },
            onError = { errorCallback?.onError(BLEException("Polling session failed: ${it.message}")) },
            enqueue = { payload, valid, accepted, failed ->
                enqueueCommand(payload, protocol.getWriteCharacteristicUUID(), valid, accepted, failed)
            }).also { it.start() }
    }

    private fun cancelPollingOrchestration() {
        pollingSession?.stop()
        pollingSession = null
    }

    @VisibleForTesting(otherwise = PRIVATE)
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal fun startDataFlowCollection(protocol: EUCProtocol) {
        cancelDataFlowCollection()
        _securityStateFlow.value = protocol.getSecurityState()
        securityFlowCollectorJob = sessionScope.launch {
            protocol.securityStateFlow.collect { state ->
                if (currentProtocol === protocol) _securityStateFlow.value = state
            }
        }
        securityEventCollectorJob = sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            protocol.securityEventFlow.collect { event ->
                if (currentProtocol === protocol) _securityEventFlow.tryEmit(event)
            }
        }
        dataFlowCollectorJob = sessionScope.launch {
            protocol.dataFlow.collect { d ->
                dataCallback?.onDataReceived(d)
            }
        }

        writeFlowCollectorJob = sessionScope.launch {
            protocol.writeFlow.collect { payload ->
                if (payload.isNotEmpty()) {
                    sendCommand(payload, protocol.getWriteCharacteristicUUID())
                }
            }
        }
    }

    /**
     * Returns the latest BMS snapshot decoded by the active protocol, or `null` when the
     * connected wheel does not expose BMS data.
     *
     * Intended to be polled by client applications as often as needed (display, logging, ...).
     */
    fun getBMSData(): List<BMSData>? = currentProtocol?.getBMSData()?.takeIf { it.isNotEmpty() }

    @VisibleForTesting(otherwise = PRIVATE)
    internal fun cancelDataFlowCollection() {
        dataFlowCollectorJob?.cancel()
        dataFlowCollectorJob = null
        writeFlowCollectorJob?.cancel()
        writeFlowCollectorJob = null
        securityFlowCollectorJob?.cancel()
        securityFlowCollectorJob = null
        securityEventCollectorJob?.cancel()
        securityEventCollectorJob = null
    }

    private fun resetProtocolSession() {
        nosfetFrameDetector.reset()
        currentSelectionReason = null
        writeGeneration++
        writeSession.reset()
        currentProtocol?.resetSession()
        _securityStateFlow.value = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setActiveProtocol(
        protocol: EUCProtocol,
        reason: String,
        selectionReason: ProtocolSelectionReason,
        fingerprintDialect: Int? = null
    ) {
        if (currentProtocol === protocol) {
            currentSelectionReason = selectionReason
            return
        }
        cancelDataFlowCollection()
        resetProtocolSession()
        protocol.resetSession()
        if (protocol is InMotionProtocol && fingerprintDialect != null) {
            protocol.setDialect(fingerprintDialect)
        }
        currentProtocol = protocol
        currentSelectionReason = selectionReason
        awaitingManualProtocolSelection = false
        logger.info(TAG, "Selected protocol ${protocol.javaClass.simpleName} via $reason")
        startPollingOrchestration(protocol)
        startDataFlowCollection(protocol)
        connectionCallback?.onProtocolSelected(buildProtocolSelection(protocol, selectionReason))
    }

    /**
     * Attempts to identify the connected device's protocol by matching the discovered GATT
     * service+characteristic profile against the [EucFingerprintDatabase].
     *
     * Returns the single matching protocol if exactly one protocol's fingerprint matches.
     * Returns `null` when zero protocols match (unknown device) or when multiple protocols
     * match the same profile (ambiguous — a warning is logged in that case).
     *
     * @param signaturesProvider Overridable signature lookup — defaults to [EucFingerprintDatabase].
     *   Exposed as a parameter for testing.
     */
    @VisibleForTesting(otherwise = PRIVATE)
    internal fun selectByGattFingerprint(
        discoveredServices: List<BluetoothGattService>,
        signaturesProvider: (String) -> List<GattSignature> = EucFingerprintDatabase::getSignatures
    ): Pair<EUCProtocol?, Int> {
        return ProtocolSelector(protocols, logger).byFingerprint(
            discoveredServices.map { service ->
                ProtocolSelector.Service(service.uuid, service.characteristics.map { it.uuid }.toSet())
            }, signaturesProvider)
    }

    /**
     * Looks for a registered protocol that is a strict subclass of [baseProtocol]'s class
     * and whose [EUCProtocol.matchesDeviceName] returns true for [deviceName].
     *
     * Used to refine a GATT fingerprint match with a more specific sub-protocol
     * (e.g. GotwayProtocol → ExtremeBullProtocol when the device name contains "bull").
     *
     * Returns the single matching subclass, or null if none or multiple match.
     * Exposed for testing.
     */
    @VisibleForTesting(otherwise = PRIVATE)
    internal fun selectSubclassByDeviceName(
        baseProtocol: EUCProtocol,
        deviceName: String
    ): EUCProtocol? {
        return ProtocolSelector(protocols, logger).subclass(baseProtocol, deviceName)
    }

    /**
     * Attempts to identify the protocol solely by matching [deviceName] against all registered
     * protocols' [EUCProtocol.matchesDeviceName] implementations.
     *
     * Used when GATT fingerprinting yields no match (e.g. Leaperkim/Nosfet share the
     * same service UUID and cannot be disambiguated by GATT alone).
     *
     * Returns the single matching protocol, or null if none or multiple protocols match.
     * Exposed for testing.
     */
    @VisibleForTesting(otherwise = PRIVATE)
    internal fun selectByDeviceName(deviceName: String): EUCProtocol? {
        return ProtocolSelector(protocols, logger).byName(deviceName)
    }

    private fun protocolIdentifier(protocol: EUCProtocol): String {
        return protocol.javaClass.simpleName.ifBlank { protocol.javaClass.name }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun maybeActivateForcedProtocol(protocol: EUCProtocol? = forcedProtocol): Boolean? {
        if (protocolSelectionMode != ProtocolSelectionMode.FORCED) return null
        val selectedProtocol = protocol ?: return false
        val servicesReady =
            currentDevice != null && bluetoothGatt?.services?.isNotEmpty() == true
        if (!servicesReady) return null
        selectedProtocol.getCandidateDataCharacteristicUUIDs()
            .distinct()
            .forEach { characteristicUuid ->
                enableNotifications(characteristicUuid)
            }
        return activateProtocolIfReady(
            selectedProtocol,
            ProtocolSelectionReason.FORCED,
            "forced override"
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun activateProtocolIfReady(
        protocol: EUCProtocol,
        selectionReason: ProtocolSelectionReason,
        logReason: String,
        fingerprintDialect: Int? = null
    ): Boolean {
        val resolvedDialect = if (protocol is InMotionProtocol) {
            fingerprintDialect ?: when {
                getCharacteristic(UUID.fromString(BLEConstants.INMOTION_V2_WRITE_CHARACTERISTIC)) != null &&
                    getCharacteristic(UUID.fromString(BLEConstants.INMOTION_V2_READ_CHARACTERISTIC)) != null -> 2
                else -> null
            }
        } else null
        if (protocol is InMotionProtocol && resolvedDialect != null) {
            protocol.setDialect(resolvedDialect)
        }
        val hasReadableCharacteristic =
            protocol.getCandidateDataCharacteristicUUIDs().any { uuid ->
                getCharacteristic(uuid) != null
            }
        if (!hasReadableCharacteristic) {
            errorCallback?.onError(
                BLEException("Protocol ${protocolIdentifier(protocol)} data characteristic is unavailable on this device")
            )
            return false
        }
        if (getCharacteristic(protocol.getWriteCharacteristicUUID()) == null) {
            errorCallback?.onError(
                BLEException("Protocol ${protocolIdentifier(protocol)} write characteristic is unavailable on this device")
            )
            return false
        }
        logger.info(TAG, "Applied protocol $protocol")
        setActiveProtocol(protocol, logReason, selectionReason, resolvedDialect)
        return true
    }

    private fun notifyProtocolSelectionRequired() {
        if (protocolSelectionMode != ProtocolSelectionMode.AUTO_WITH_MANUAL_FALLBACK || currentProtocol != null) return
        if (protocols.isEmpty()) return
        if (awaitingManualProtocolSelection) return
        awaitingManualProtocolSelection = true
        connectionCallback?.onProtocolSelectionRequired(protocols.toList())
    }

    private fun buildProtocolSelection(
        protocol: EUCProtocol,
        reason: ProtocolSelectionReason
    ): ProtocolSelection {
        return ProtocolSelection(
            protocolId = protocolIdentifier(protocol),
            manufacturer = protocol.manufacturer,
            protocolClassName = protocol.javaClass.name,
            reason = reason
        )
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
        if (cleanedUp) return
        cleanedUp = true
        cancelReconnectAttempts()
        scanTimeoutJob?.cancel()
        connectionTimeoutJob?.cancel()
        try {
            if (platformScanCallback != null) stopScan()
        } finally {
            try {
                disconnect()
            } finally {
                scanJob?.cancel()
                connectionJob?.cancel()
                sessionJob.cancel()
            }
        }
    }
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
abstract class ConnectionCallback : io.github.tritbool.euc.ble.core.ScanCallback {
    open fun onConnecting() {/*empty body to avoid multiple reimplementing*/}
    open fun onConnected() {/*empty body to avoid multiple reimplementing*/}
    open fun onDisconnected() {/*empty body to avoid multiple reimplementing*/}
    open  fun onConnectionFailed(error: BLEException) {/*empty body to avoid multiple reimplementing*/}
    open fun onServicesDiscovered(services: List<BluetoothGattService>) {/*empty body to avoid multiple reimplementing*/}
    open fun onMtuChanged(mtu: Int) {/*empty body to avoid multiple reimplementing*/}
    open fun onProtocolSelectionRequired(protocols: List<EUCProtocol>) {/*empty body to avoid multiple reimplementing*/}
    open fun onProtocolSelected(selection: ProtocolSelection) {/*empty body to avoid multiple reimplementing*/}
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

    override fun onConnecting() {
        listener?.onEvent(BleBackendEvent.Connecting)
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

    override fun onProtocolSelectionRequired(protocols: List<EUCProtocol>) {
        listener?.onEvent(BleBackendEvent.ProtocolSelectionRequired(protocols))
    }

    override fun onProtocolSelected(selection: ProtocolSelection) {
        listener?.onEvent(BleBackendEvent.ProtocolSelected(selection))
    }
}

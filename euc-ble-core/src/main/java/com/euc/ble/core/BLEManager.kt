package com.euc.ble.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.euc.ble.exceptions.BLEException
import com.euc.ble.models.EUCData
import com.euc.ble.models.EUCDevice
import com.euc.ble.protocols.EUCProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Main BLE Manager for Electric Unicycles
 * Handles device scanning, connection, and data processing
 */
class BLEManager(private val context: Context, private val logger: Logger = AndroidLogger()) : BluetoothGattCallback() {
    
    // Configuration
    private var scanTimeout: Long = BLEConstants.DEFAULT_SCAN_TIMEOUT_MS
    private var connectionTimeout: Long = BLEConstants.DEFAULT_CONNECTION_TIMEOUT_MS
    private var autoReconnect: Boolean = true
    private var maxRetries: Int = 3
    
    // State management
    private var connectionState: BLEConstants.ConnectionState = BLEConstants.ConnectionState.DISCONNECTED
    private var currentDevice: EUCDevice? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    
    // Protocol management
    private val protocols: MutableList<EUCProtocol> = mutableListOf()
    private var currentProtocol: EUCProtocol? = null
    
    // Callbacks
    private var scanCallback: ScanCallback? = null
    private var connectionCallback: ConnectionCallback? = null
    private var dataCallback: DataCallback? = null
    private var errorCallback: ErrorCallback? = null
    
    // Coroutine management
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var scanJob: Job? = null
    private var connectionJob: Job? = null
    
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
            withContext(Dispatchers.IO) {
                startBleScan()
            }
        }
        
        // Set timeout for scanning
        Handler(Looper.getMainLooper()).postDelayed({
            logger.info("BLEManager", "Scan timeout reached")
            stopScan()
        }, scanTimeout)
    }
    
    /**
     * Stop scanning for devices
     */
    fun stopScan() {
        scanJob?.cancel()
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanCallback = null
        connectionCallback?.onScanCompleted(discoveredDevices.values.toList())
    }
    
    /**
     * Connect to a specific device
     */
    fun connect(device: EUCDevice) {
        if (connectionState != BLEConstants.ConnectionState.DISCONNECTED) {
            errorCallback?.onError(BLEException("Already connecting or connected"))
            return
        }
        
        currentDevice = device
        connectionState = BLEConstants.ConnectionState.CONNECTING
        
        connectionJob = coroutineScope.launch {
            withContext(Dispatchers.IO) {
                connectToDevice(device.bluetoothDevice)
            }
        }
        
        // Set connection timeout
        Handler(Looper.getMainLooper()).postDelayed({
            if (connectionState == BLEConstants.ConnectionState.CONNECTING) {
                disconnect()
                errorCallback?.onError(BLEException("Connection timeout"))
            }
        }, connectionTimeout)
    }
    
    /**
     * Disconnect from current device
     */
    fun disconnect() {
        connectionJob?.cancel()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        currentDevice = null
        currentProtocol = null
        connectionState = BLEConstants.ConnectionState.DISCONNECTED
        connectionCallback?.onDisconnected()
    }
    
    /**
     * Send a command to the connected device
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendCommand(command: ByteArray, characteristicUuid: UUID) {
        if (connectionState != BLEConstants.ConnectionState.CONNECTED) {
            errorCallback?.onError(BLEException("Not connected to a device"))
            return
        }
        
        val characteristic = getCharacteristic(characteristicUuid)

        characteristic?.let { char ->
            char.value = command
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(char)
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
    
    // Callback setters
    fun setScanCallback(callback: ScanCallback) {
        this.scanCallback = callback
    }
    
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
        
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
            .build()
        
        scanCallback = object : ScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                processScanResult(result)
            }
            
            override fun onScanFailed(errorCode: Int) {
                errorCallback?.onError(BLEException("Scan failed with error: $errorCode"))
            }
        }
        
        scanner.startScan(null, settings, scanCallback)
        connectionCallback?.onScanStarted()
    }
    
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processScanResult(result: ScanResult) {
        data class WheelManufacturerData(
            val manufacturerId: Int,
            val data: ByteArray?
        )
        val device = result.device
        val scanRecord = result.scanRecord
        val foundManufacturerData: WheelManufacturerData? = sequenceOf(
            BLEConstants.MANUFACTURER_KINGSONG,
            BLEConstants.MANUFACTURER_GOTWAY,
            BLEConstants.MANUFACTURER_INMOTION,
            BLEConstants.MANUFACTURER_NINEBOT,
            BLEConstants.MANUFACTURER_VETERAN,
        ).firstNotNullOfOrNull { id ->
            scanRecord
                ?.getManufacturerSpecificData(id)
                ?.let { bytes -> WheelManufacturerData(id, bytes) }
        }
        
        val eucDevice = EUCDevice(
            bluetoothDevice = device,
            name = device.name ?: "Unknown EUC",
            address = device.address,
            manufacturerId = foundManufacturerData?.manufacturerId?:0 ,
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
                connectionState = BLEConstants.ConnectionState.CONNECTED
                connectionCallback?.onConnected()
                // Discover services
                gatt.discoverServices()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                connectionState = BLEConstants.ConnectionState.DISCONNECTED
                connectionCallback?.onDisconnected()
                if (autoReconnect && currentDevice != null) {
                    // Implement reconnection logic
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
    
    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        super.onServicesDiscovered(gatt, status)
        
        if (status == BluetoothGatt.GATT_SUCCESS) {
            // Find the appropriate protocol for this device
            currentProtocol = protocols.find { protocol ->
                protocol.canHandle(currentDevice!!)
            }
            
            currentProtocol?.let { protocol ->
                // Enable notifications for data characteristics
                enableNotifications(protocol.getDataCharacteristicUUID())
                connectionCallback?.onServicesDiscovered(gatt.services)
            } ?: run {
                errorCallback?.onError(BLEException("No protocol found for this device"))
                disconnect()
            }
        } else {
            errorCallback?.onError(BLEException("Service discovery failed: $status"))
            disconnect()
        }
    }
    
    override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        super.onCharacteristicChanged(gatt, characteristic)
        
        val data = characteristic.value
        currentProtocol?.let { protocol ->
            try {
                val eucData = protocol.decode(data)
                eucData?.let { data ->
                    dataCallback?.onDataReceived(data)
                }
            } catch (e: Exception) {
                errorCallback?.onError(BLEException("Data decoding failed: ${e.message}"))
            }
        }
    }
    
    override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
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
    
    private fun enableNotifications(characteristicUuid: UUID) {
        val characteristic = getCharacteristic(characteristicUuid)
        characteristic?.let { char ->
            val cccdUuid = UUID.fromString(BLEConstants.CCCD_DESCRIPTOR)
            val descriptor = char.getDescriptor(cccdUuid)
            descriptor?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                bluetoothGatt?.writeDescriptor(desc)
                bluetoothGatt?.setCharacteristicNotification(char, true)
            }
        }
    }
    
    // Cleanup
    fun cleanup() {
        disconnect()
        scanJob?.cancel()
        connectionJob?.cancel()
        coroutineScope.cancel() //coroutineContext.cancelChildren()
    }
}

// Callback interfaces
interface ScanCallback {
    fun onScanStarted() {}
    fun onDeviceDiscovered(device: EUCDevice) {}
    fun onScanCompleted(devices: List<EUCDevice>) {}
}

abstract class ConnectionCallback : com.euc.ble.core.ScanCallback {
    fun onConnected() {}
    fun onDisconnected() {}
    fun onConnectionFailed(error: BLEException) {}
    fun onServicesDiscovered(services: List<BluetoothGattService>) {}
    fun onMtuChanged(mtu: Int) {}
}

interface DataCallback {
    fun onDataReceived(data: EUCData)
}

interface ErrorCallback {
    fun onError(error: BLEException)
}
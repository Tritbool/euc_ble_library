# EUC BLE Core Library

A modular Bluetooth Low Energy library for Electric Unicycles (EUC) that provides a clean, reusable interface for connecting to and communicating with various EUC manufacturers.

## Features

- **Modular Architecture**: Clean separation of BLE core functionality and manufacturer-specific protocols
- **Minimal Dependencies**: Only requires Android BLE API and Kotlin coroutines
- **Extensible Design**: Easy to add support for new EUC manufacturers
- **Comprehensive Error Handling**: Robust error management and recovery
- **Coroutines Support**: Modern async/await pattern for BLE operations

## Supported Manufacturers

- **KingSong** (KS-14D, KS-16, KS-16S, KS-16X, KS-18L, KS-18XL, KS-19, KS-S18, KS-S19, KS-S20, KS-S22, KS-F22)
- **Gotway** (Coming soon)
- **InMotion** (Coming soon)
- **Ninebot** (Coming soon)
- **Veteran** (Coming soon)

## Installation

Add the library to your project by including it in your `settings.gradle`:

```gradle
include ':euc-ble-core'
```

And in your app's `build.gradle`:

```gradle
dependencies {
    implementation project(':euc-ble-core')
}
```

## Quick Start

### Initialize the BLE Manager

```kotlin
// Create BLE Manager instance
val bleManager = BLEManager(context)

// Initialize
bleManager.initialize()

// Register protocols
bleManager.registerProtocol(KingsongProtocol())
// bleManager.registerProtocol(GotwayProtocol())
// bleManager.registerProtocol(InMotionProtocol())

// Set up callbacks
bleManager.setScanCallback(object : ScanCallback {
    override fun onScanStarted() {
        // Scan started
    }
    
    override fun onDeviceDiscovered(device: EUCDevice) {
        // New device discovered
    }
    
    override fun onScanCompleted(devices: List<EUCDevice>) {
        // Scan completed with list of devices
    }
})

bleManager.setConnectionCallback(object : ConnectionCallback {
    override fun onConnected() {
        // Successfully connected
    }
    
    override fun onDisconnected() {
        // Disconnected
    }
    
    override fun onServicesDiscovered(services: List<BluetoothGattService>) {
        // Services discovered
    }
})

bleManager.setDataCallback(object : DataCallback {
    override fun onDataReceived(data: EUCData) {
        // Real-time data received
        val speed = data.speed
        val voltage = data.voltage
        val batteryLevel = data.batteryLevel
        // Update your UI
    }
})

bleManager.setErrorCallback(object : ErrorCallback {
    override fun onError(error: BLEException) {
        // Handle errors
    }
})
```

### Scan and Connect

```kotlin
// Start scanning for devices
bleManager.startScan()

// Connect to a device
val device = discoveredDevices.first()
bleManager.connect(device)

// Send a command
val lightOnCommand = bleManager.createCommand(CommandType.LIGHT_ON, Unit)
bleManager.sendCommand(lightOnCommand, device.getDataCharacteristicUUID())

// Disconnect
bleManager.disconnect()
```

## Architecture

### Core Components

1. **BLEManager**: Main class that handles BLE operations
2. **EUCProtocol**: Interface for manufacturer-specific protocols
3. **EUCDevice**: Data class representing a discovered EUC device
4. **EUCData**: Data class containing real-time EUC data
5. **ByteUtils**: Utility class for byte operations

### Protocol System

Each manufacturer has its own protocol implementation:

```kotlin
interface EUCProtocol {
    val manufacturer: String
    val supportedModels: List<String>
    
    fun canHandle(device: EUCDevice): Boolean
    fun decode(data: ByteArray): EUCData?
    fun createCommand(commandType: CommandType, value: Any): ByteArray
    fun getServiceUUID(): UUID
    fun getDataCharacteristicUUID(): UUID
    fun isDeviceReady(data: EUCData): Boolean
}
```

### Data Model

```kotlin
data class EUCData(
    val speed: Double,              // km/h
    val voltage: Double,            // volts
    val current: Double,            // amps
    val temperature: Double,        // degrees Celsius
    val batteryLevel: Int,         // percentage 0-100
    val distance: Double,           // kilometers
    val power: Double,              // watts
    val timestamp: Long,            // milliseconds
    val rawData: ByteArray,         // raw byte data
    val manufacturer: String,       // manufacturer name
    val model: String,              // device model
    val serialNumber: String?,      // device serial
    val firmwareVersion: String?,   // firmware version
    val isCharging: Boolean,        // charging status
    val rideTime: Long,             // ride time in seconds
    val cellVoltages: List<Double>? // individual cell voltages
)
```

## Configuration

```kotlin
// Configure scan timeout (default: 10000ms)
bleManager.setScanTimeout(15000)

// Configure connection timeout (default: 5000ms)
bleManager.setConnectionTimeout(8000)

// Enable/disable auto-reconnect (default: true)
bleManager.setAutoReconnect(false)

// Set maximum reconnection attempts (default: 3)
bleManager.setMaxRetries(5)
```

## Error Handling

The library provides comprehensive error handling through the `ErrorCallback`:

```kotlin
bleManager.setErrorCallback(object : ErrorCallback {
    override fun onError(error: BLEException) {
        when (error.errorType) {
            BLEException.ErrorType.BLUETOOTH_DISABLED -> {
                // Request Bluetooth enable
            }
            BLEException.ErrorType.CONNECTION_FAILED -> {
                // Handle connection failure
            }
            BLEException.ErrorType.DATA_DECODING_FAILED -> {
                // Handle data parsing error
            }
            // ... other error types
        }
    }
})
```

## Logging

The library includes a simple logging interface that can be customized:

```kotlin
// Use default Android logger
val bleManager = BLEManager(context, AndroidLogger())

// Use no-op logger (for testing)
val bleManager = BLEManager(context, NoOpLogger())

// Implement custom logger
class CustomLogger : Logger {
    override fun verbose(tag: String, message: String) { /* ... */ }
    override fun debug(tag: String, message: String) { /* ... */ }
    override fun info(tag: String, message: String) { /* ... */ }
    override fun warn(tag: String, message: String) { /* ... */ }
    override fun error(tag: String, message: String) { /* ... */ }
    override fun error(tag: String, message: String, throwable: Throwable) { /* ... */ }
}
```

## Adding New Manufacturers

To add support for a new manufacturer:

1. Create a new protocol class implementing `EUCProtocol`
2. Implement the required methods for device detection and data decoding
3. Register the protocol with the BLE manager

```kotlin
class NewManufacturerProtocol : EUCProtocol {
    override val manufacturer: String = "NewManufacturer"
    override val supportedModels: List<String> = listOf("Model1", "Model2")
    
    override fun canHandle(device: EUCDevice): Boolean {
        // Implement device detection logic
    }
    
    override fun decode(data: ByteArray): EUCData? {
        // Implement data decoding logic
    }
    
    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        // Implement command creation logic
    }
    
    override fun getServiceUUID(): UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    
    override fun isDeviceReady(data: EUCData): Boolean {
        // Implement readiness check
    }
}

// Register the protocol
bleManager.registerProtocol(NewManufacturerProtocol())
```

## Dependencies

The library has minimal dependencies:

- `androidx.core:core-ktx:1.12.0` - Android core extensions
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3` - Coroutines for async operations

## License

This library is open-source and available under the MIT License.

## Contributing

Contributions are welcome! Please follow the existing code style and architecture patterns.

## Roadmap

- [x] Core BLE functionality
- [x] Kingsong protocol implementation
- [ ] Gotway protocol implementation
- [ ] InMotion protocol implementation
- [ ] Ninebot protocol implementation
- [ ] Veteran protocol implementation
- [ ] Comprehensive test suite
- [ ] Sample application
- [ ] Documentation improvements

## Support

For issues, questions, or feature requests, please open an issue on the GitHub repository.
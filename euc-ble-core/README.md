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
- **Gotway / Begode** (MSuper, MSX, Mten, Nikola, Tesla, Monster, RS, Master, Hero series)
- **InMotion** (V9, V5F, V8S and related legacy/V2 variants)
- **Ninebot** (S-series, A-series, Z-series MVP support)
- **Leaperkim / Veteran** (Patton, Patton S, Sherman, Sherman S, Sherman L, Lynx, Lynx S, Abrams, Oryx, Nosfet series)

### Protocol Coverage Matrix

| Manufacturer | Protocol class | WheelLog test coverage | Command support | Telemetry completeness |
| --- | --- | --- | --- | --- |
| KingSong | `KingsongProtocol` | `WheelLogKingsongTest` | Light/BEEP/Power/Brightness | Core telemetry + battery and session ride time fallback |
| Gotway/Begode | `GotwayProtocol` | `WheelLogGotwayTest` | Light/BEEP/Power/Brightness | Type A: rich telemetry, Type B: settings + total distance with legacy-style carry-forward telemetry |
| InMotion | `InMotionProtocol` | `WheelLogInMotionTest` | V2 control commands (legacy read-only) | Legacy: rich telemetry + ride time, V2: rich telemetry + ride time fallback |
| Ninebot (standard) | `NinebotProtocol` | `NinebotProtocolTest`, `WheelLogNinebotTest` | Light/BEEP/Lock/Unlock + query commands (serial/firmware/battery) | Core telemetry + startup/periodic query orchestration |
| Ninebot (Z-series) | `NinebotZProtocol` | `ProtocolParityContractTest` | Light/BEEP/Lock/Unlock + Z settings/query flow (speed/alarm/calibrate/BMS/auth/custom) | Dedicated Z handshake/BMS/settings polling path |
| Leaperkim/Veteran | `LeaperkimProtocol` | `WheelLogLeaperkimTest` | Light/BEEP + custom payload | Rich telemetry + session ride time fallback |

> **Test methodology:** protocol decoders are validated against real BLE captures recorded from
> physical wheels and exported from WheelLog. No mock frames — real bytes, real edge cases.
> See [Testing](#testing) in the root README for details.

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
bleManager.registerProtocol(GotwayProtocol())
bleManager.registerProtocol(InMotionProtocol())
bleManager.registerProtocol(NinebotProtocol())
bleManager.registerProtocol(NinebotZProtocol())
bleManager.registerProtocol(LeaperkimProtocol())

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

## WheelLog Migration Adapter Layer

The library now includes a backend abstraction to help migrate WheelLog BLE incrementally:

- `BleBackend`: common contract consumed by app/UI layers
- `FrameworkBleBackend`: adapter over `BLEManager` (new framework)
- `LegacyBleBackend`: wrapper over a legacy BLE engine contract
- `SwitchableBleBackend`: runtime switch between `LEGACY` and `FRAMEWORK`

### Minimal parity checklist (before legacy removal)

- Scan start/stop + discovered devices
- Connect/disconnect + connection state changes
- BLE data stream decoding events
- Command write path
- Error events
- Raw notification frames (`RawFrameReceived`)
- Reconnection behavior

### Runtime switch example

```kotlin
val backend = BleBackendFactory.fromFlag(
    useLegacy = BuildConfig.USE_LEGACY_BLE,
    frameworkBackend = FrameworkBleBackend(bleManager),
    legacyBackend = LegacyBleBackend(myLegacyEngine)
)

backend.setListener { event ->
    when (event) {
        is BleBackendEvent.DeviceDiscovered -> { /* update list */ }
        is BleBackendEvent.DataReceived -> { /* update dashboard */ }
        is BleBackendEvent.RawFrameReceived -> { /* optional raw log */ }
        else -> Unit
    }
}

backend.initialize()
backend.startScan()
```

To switch backend at runtime for A/B validation:

```kotlin
backend.switchTo(BleBackendType.LEGACY)
backend.switchTo(BleBackendType.FRAMEWORK)
```

### Client Example: Save Raw + Decoded Frames to Two CSV Files

```kotlin
import com.euc.ble.core.BLEManager
import com.euc.ble.core.DataCallback
import com.euc.ble.models.EUCData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File

class CsvBleLoggerClient(
    private val bleManager: BLEManager,
    private val appFilesDir: File
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rawJob: Job? = null

    private val rawCsv = File(appFilesDir, "raw_frames.csv")
    private val decodedCsv = File(appFilesDir, "decoded_frames.csv")

    fun start() {
        ensureHeaders()

        // 1) Raw BLE chunks exactly as they arrive from notifications
        rawJob = bleManager.rawFrameFlow
            .onEach { raw ->
                rawCsv.appendText("${System.currentTimeMillis()},${raw.toHexString()}\n")
            }
            .launchIn(scope)

        // 2) Decoded telemetry frames from protocol parsing
        bleManager.setDataCallback(object : DataCallback {
            override fun onDataReceived(data: EUCData) {
                scope.launch {
                    decodedCsv.appendText(
                        listOf(
                            data.timestamp,
                            data.manufacturer.csvSafe(),
                            data.model.csvSafe(),
                            data.speed,
                            data.voltage,
                            data.current,
                            data.temperature,
                            data.batteryLevel,
                            data.distance,
                            data.power,
                            data.isCharging,
                            data.rideTime
                        ).joinToString(",") + "\n"
                    )
                }
            }
        })
    }

    fun stop() {
        rawJob?.cancel()
        scope.cancel()
    }

    private fun ensureHeaders() {
        if (!rawCsv.exists()) {
            rawCsv.writeText("timestamp_ms,raw_hex\n")
        }
        if (!decodedCsv.exists()) {
            decodedCsv.writeText(
                "timestamp_ms,manufacturer,model,speed_kmh,voltage_v,current_a,temperature_c,battery_pct,distance_km,power_w,is_charging,ride_time_s\n"
            )
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02X".format(byte.toInt() and 0xFF) }

    private fun String.csvSafe(): String = "\"${replace("\"", "\"\"")}\""
}
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
    val dataFlow: Flow<EUCData>
    val rawFrameFlow: Flow<ByteArray>
    
    fun canHandle(device: EUCDevice): Boolean
    fun decode(data: ByteArray): EUCData?
    fun createCommand(commandType: CommandType, value: Any): ByteArray
    fun getServiceUUID(): UUID
    fun getDataCharacteristicUUID(): UUID
    fun getWriteCharacteristicUUID(): UUID
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
- [x] Gotway protocol implementation
- [x] InMotion protocol implementation
- [x] Ninebot protocol MVP implementation
- [x] Leaperkim/Veteran protocol implementation
- [x] Comprehensive test suite (real-device capture methodology, 84%+ line coverage on protocols/models/frames)
- [ ] Sample application
- [ ] Documentation improvements

## Support

For issues, questions, or feature requests, please open an issue on the GitHub repository.
### Command support contract

Each protocol now declares a command support matrix:

- `supportedCommandTypes`
- `getCommandSupport(commandType)`

Use `BLEManager.getCommandSupport(...)` before creating/sending commands to distinguish supported vs explicitly unsupported operations.

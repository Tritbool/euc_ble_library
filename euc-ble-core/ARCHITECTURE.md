# EUC BLE Core Library Architecture

This document provides an overview of the library architecture using PlantUML diagrams.

## Diagram Files

### 1. **Architecture Diagram** (`architecture.puml`)

**Purpose**: Shows the high-level component structure and relationships

**Key Components**:
- **Core Components**: BLEManager, BLEConstants, ByteUtils, Logger
- **Data Models**: EUCDevice, EUCData
- **Protocols**: EUCProtocol interface and implementations
- **Callbacks**: ScanCallback, ConnectionCallback, DataCallback, ErrorCallback
- **Android Integration**: Shows how the library connects to Android BLE system

**How to Read**:
- Boxes represent components/packages
- Arrows show dependencies and relationships
- Notes provide additional context
- Color coding distinguishes interfaces, data models, and exceptions

### 2. **Sequence Diagram** (`sequence.puml`)

**Purpose**: Illustrates the typical usage flow and interaction sequence

**Key Flows**:
1. **Initialization**: Library setup and protocol registration
2. **Device Discovery**: Scanning and device detection
3. **Connection**: Establishing BLE connection
4. **Data Streaming**: Real-time data reception and processing
5. **Command Sending**: Sending commands to the EUC
6. **Error Handling**: Comprehensive error management

**How to Read**:
- Vertical lines represent participants (actors, classes)
- Horizontal arrows show method calls and data flow
- Loops indicate repeated operations
- Notes explain key steps
- Alternative flows show error handling

### 3. **Class Diagram** (`class_diagram.puml`)

**Purpose**: Detailed class structure with methods, properties, and relationships

**Key Elements**:
- **Classes**: Detailed method signatures and properties
- **Interfaces**: Clearly marked with `<<Interface>>` stereotype
- **Data Models**: Marked with `<<DataModel>>` stereotype
- **Enums**: CommandType and ErrorType enumerations
- **Relationships**: Associations, dependencies, and inheritance

**How to Read**:
- Class boxes show name, properties, and methods
- Arrows indicate relationships (inheritance, composition, usage)
- Stereotypes (`<<Interface>>`, `<<DataModel>>`) categorize components
- Color coding helps distinguish different types of components

## Generating the Diagrams

To generate the diagrams from the PlantUML files:

### Option 1: Using PlantUML Server

1. Copy the content of any `.puml` file
2. Visit [PlantUML Web Server](http://www.plantuml.com/plantuml/)
3. Paste the content and generate the diagram

### Option 2: Using IntelliJ IDEA with PlantUML Plugin

1. Install the PlantUML Integration plugin
2. Open the `.puml` file in IntelliJ
3. Right-click and select "Generate Diagram"

### Option 3: Using VS Code with PlantUML Extension

1. Install the PlantUML extension
2. Open the `.puml` file
3. Use the preview functionality

### Option 4: Command Line with Java

```bash
java -jar plantuml.jar architecture.puml
java -jar plantuml.jar sequence.puml
java -jar plantuml.jar class_diagram.puml
```

## Architecture Overview

### Layered Architecture

```
┌─────────────────────────────────────────────────┐
│                 Application Layer                 │
│  (MainActivity, ViewModel, UI Components)         │
└─────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────┐
│               EUC BLE Core Library                │
│                                                     │
│  ┌─────────────┐    ┌─────────────────────────┐  │
│  │ EucBleClient│───▶│ EUCProtocol Interface  │  │
│  └─────────────┘    └─────────────────────────┘  │
│          │                  │                    │
│          ▼                  ▼                    │
│  ┌─────────────┐    ┌─────────────────────────┐  │
│  │ Core Utils  │    │ Manufacturer Protocols │  │
│  └─────────────┘    └─────────────────────────┘  │
│          │                  │                    │
│          ▼                  ▼                    │
│  ┌─────────────┐    ┌─────────────────────────┐  │
│  │ Data Models │    │ KingsongProtocol       │  │
│  └─────────────┘    │ GotwayProtocol         │  │
│                     │ InMotionProtocol       │  │
│                     │ NinebotProtocol        │  │
│                     │ NinebotZProtocol       │  │
│                     │ LeaperkimProtocol      │  │
│                     │ NosfetProtocol         │  │
│                     └─────────────────────────┘  │
└─────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────┐
│               Android BLE System                  │
│  (BluetoothAdapter, BluetoothGatt, etc.)         │
└─────────────────────────────────────────────────┘
```

### Sync protocol core and session layer (migration in progress)

Protocols are being migrated to synchronous, I/O-free ("sans-IO") state machines so that the
core stays portable (Kotlin Multiplatform, and a later Rust port for ESP32 / SailfishOS):

| Layer | Package | Responsibility |
|---|---|---|
| Contract | `protocols.EucProtocolSpec` | Pure metadata, command encoding, polling plan, state queries |
| Sync protocol | `protocols.SyncEucProtocol` | `onNotification` / `onTimer` / `onCommandSubmitted` inputs; outputs via `pollOutput()` |
| Outputs | `protocols.ProtocolOutput` | Telemetry, security updates, writes, timer actions and diagnostic logs |
| Platform inputs | `core.EucClock`, `core.EucRandom` | Injected time and randomness (JVM defaults in `core/JvmPlatform.kt`) |
| Session bridge | `session.FlowProtocolAdapter` | Serializes calls, drains outputs into the `EUCProtocol` flows, runs timers |
| Selection policy | `session.ProtocolSelector` | Matches plain GATT snapshots and device names; rejects ambiguous matches |
| Polling state | `session.PollingEngine` | Synchronous startup/periodic scheduling, response matching and retries |
| Polling driver | `session.PollingSession` | Supplies monotonic time and transport callbacks to the polling state machine |
| Write driver | `session.WriteSession` | Serializes writes, retries stack rejections, owns acknowledgment waits and wipes byte snapshots |
| Reconnect policy | `session.ReconnectPolicy` | Computes bounded exponential backoff and jitter without scheduling or platform APIs |

Sync protocols must not use coroutines, threads, locks, timers or direct clock reads, and need not be
thread-safe. The migrated implementations use synchronous parsing and flow-compatible facades.
All built-in protocol families now have synchronous state machines. Gotway and Leaperkim/Nosfet
process incoming chunks in order instead of launching one coroutine per chunk. KingSong's password
timeout starts only after write acceptance and expires as unconfirmed, not as proof of a bad password.
UUIDs, other JVM APIs, and the coroutine session bridge remain JVM-specific until later phases.

Polling response deadlines and `SENT` traces start at transport acceptance, never at enqueue time.
Response latency uses monotonic milliseconds; Android translates trace timestamps to wall-clock
time for the public flow. Startup queries finish sequentially before periodic polling begins.
Model-dependent periodic plans are refreshed without restarting completed one-shot queries.
Stopping a session invalidates queued requests and callbacks without cancelling the caller's scope.
Accepted writes are never automatically retried: a missing GATT callback only releases the queue
after its guard timeout, and is not evidence of a wheel acknowledgment.

`BLEManager` now supplies Android service snapshots, transport submission and callback notifications.
Connection/reconnection lifecycle, notification setup, forced/manual activation and public flow wiring
remain in the Android connector. The reconnect delay policy itself is pure and independently tested.
Each manager and client owns a child coroutine job: cleanup cancels its own work without cancelling
the caller's scope. GATT callbacks are accepted only from the currently owned connection, and failed
device resolution leaves the connection state disconnected. The session drivers still use coroutines;
the protocol engines and polling/reconnect policies do not.

### Key Design Principles

1. **Separation of Concerns**: Core BLE functionality separated from manufacturer-specific protocols
2. **Interface-based Design**: All protocols implement the same interface for consistency
3. **Dependency Injection**: Clean separation with constructor injection
4. **Immutable Data Models**: Thread-safe data handling with immutable objects
5. **Callback Pattern**: Event-driven architecture for real-time updates
6. **Error Handling**: Comprehensive error management with custom exceptions

### Data Flow

```
1. Scan for Devices
   └─> Discover BLE devices
       └─> Filter by manufacturer protocols
           └─> Notify via ScanCallback

2. Connect to Device
   └─> Establish BLE connection
       └─> Discover services
           └─> Enable notifications
               └─> Notify via ConnectionCallback

3. Receive Data
   └─> BLE characteristic updates
       └─> Protocol-specific decoding
           └─> Create EUCData objects
               └─> Notify via DataCallback

4. Send Commands
   └─> Create protocol-specific commands
       └─> Write to BLE characteristic
           └─> Handle response/confirmation

5. Error Handling
   └─> Comprehensive error detection
       └─> Custom BLEException types
           └─> Notify via ErrorCallback
```

## Integration Guide

### Step 1: Add Library to Project

```gradle
// settings.gradle
include ':euc-ble-core', ':euc-ble-android'

// app/build.gradle (Android) — euc-ble-android exposes euc-ble-core as `api`
dependencies {
    implementation project(':euc-ble-android')
}
```

### Step 2: Use EucBleClient (single entry point)

`EucBleClient` is the public API. It registers all built-in protocols internally — client code must
not register brand-specific handlers manually.

```kotlin
val client = EucBleClient(context)

client.setConnectionCallback(object : ConnectionCallback() {
    override fun onDeviceDiscovered(device: EUCDevice) {
        client.connect(device)
    }
    override fun onConnected() { /* ... */ }
    override fun onDisconnected() { /* ... */ }
})

client.setDataCallback(object : DataCallback {
    override fun onDataReceived(data: EUCData) {
        val speed = data.speed
        val voltage = data.voltage
        val batteryLevel = data.batteryLevel
    }
})

client.setErrorCallback(object : ErrorCallback {
    override fun onError(error: BLEException) { /* ... */ }
})

client.initialize()
client.startScan()
```

### Step 3: Send commands

```kotlin
client.sendCommand(CommandType.LIGHT_ON)
```

## Best Practices

### Error Handling

```kotlin
client.setErrorCallback(object : ErrorCallback {
    override fun onError(error: BLEException) {
        when (error.errorType) {
            ErrorType.BLUETOOTH_DISABLED -> requestBluetoothEnable()
            ErrorType.CONNECTION_FAILED -> showRetryOption()
            ErrorType.DATA_DECODING_FAILED -> logError(error)
        }
    }
})
```

### Memory Management

```kotlin
override fun onDestroy() {
    client.cleanup()
    super.onDestroy()
}
```

### Logging

```kotlin
// Use no-op logger for testing
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

## Extending the Library

### Adding New Manufacturers

1. Implement `EUCProtocol`
2. Register the new protocol inside `EucBleClient` alongside the existing ones
3. Add a WheelLog capture test (see [README_TESTS.md](README_TESTS.md))

```kotlin
class NewManufacturerProtocol : EUCProtocol {
    override val manufacturer: String = "NewManufacturer"
    override val supportedModels: List<String> = listOf("Model1", "Model2")

    override fun canHandle(device: EUCDevice): Boolean { /* device detection logic */ }
    override fun decode(data: ByteArray): EUCData? { /* frame parsing logic */ }
    override fun createCommand(commandType: CommandType, value: Any): ByteArray { /* ... */ }
    override fun getServiceUUID(): UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    override fun isDeviceReady(data: EUCData): Boolean { /* ... */ }
}
```

## Performance Considerations

- Limit scan duration to conserve battery
- Process data on background threads; switch to main thread only for UI updates
- Be mindful of byte array allocations in the decode hot path
- Clean up callbacks to prevent leaks

## Troubleshooting

### Common Issues

1. **Bluetooth Not Enabled** — check `client.isBluetoothEnabled()` and request Bluetooth enable if needed
2. **Device Not Detected** — verify device is in range and powered on; check manufacturer ID and name patterns in the protocol's `canHandle()`
3. **Connection Failures** — check for proper Android permissions; handle connection timeouts
4. **Data Decoding Errors** — validate data packet structure; check for correct protocol implementation

### Debugging

```kotlin
val debugLogger = object : Logger {
    override fun verbose(tag: String, message: String) { Log.v("BLE_DEBUG", "$tag: $message") }
    // implement other levels similarly
}
val bleManager = BLEManager(context, debugLogger)
```

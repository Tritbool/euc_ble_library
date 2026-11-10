# EUC BLE Core Library Architecture

This document provides an overview of the library architecture using PlantUML diagrams.
The older diagrams below describe the Android-facing API. The
[portable engines and session section](#portable-synchronous-engines-and-jvm-session-layer)
defines the current module boundaries and the remaining architecture work.

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

### Portable synchronous engines and JVM session layer

The module boundary is enforced by Gradle: `euc-ble-protocols` is Kotlin Multiplatform with all
production sources in `commonMain` and no coroutine dependency. `euc-ble-session` is a separate
Kotlin Multiplatform module for selection, polling, reconnect policies and session-facing types.
`euc-ble-core` depends on both modules and still provides JVM compatibility facades and coroutine
drivers; `euc-ble-android` depends on core and owns GATT. Dependencies never point from the
portable engines or session policies back to the Android connector.

| Layer | Package | Responsibility |
|---|---|---|
| Contract | `protocols.EucProtocolSpec` | Pure metadata, command encoding, polling plan, state queries |
| Sync protocol | `protocols.SyncEucProtocol` | `onNotification` / `onTimer` / `onCommandSubmitted` inputs; outputs via `pollOutput()` |
| Outputs | `protocols.ProtocolOutput` | Telemetry, security updates, writes, timer actions and diagnostic logs |
| Platform inputs | `core.EucClock`, `core.EucRandom` | Required engine inputs; JVM facade defaults remain in `euc-ble-core/core/JvmPlatform.kt` |
| Protocol session | `euc-ble-session/session.ProtocolSession` | Pure timer bookkeeping and ordered protocol output delivery |
| Coroutine facade driver | `euc-ble-core/session.ProtocolSessionDriver` | Serializes callers, exposes compatibility flows and schedules pure-session deadlines; replaces the temporary bridge |
| Selection policy | `euc-ble-session/session.ProtocolSelector` | Matches plain GATT snapshots and device names; rejects ambiguous matches |
| Polling state | `euc-ble-session/session.PollingEngine` | Synchronous startup/periodic scheduling, response matching and retries |
| Polling driver | `euc-ble-core/session.PollingSession` | Supplies monotonic time and transport callbacks to the polling state machine |
| Write policy | `euc-ble-session/session.WriteQueue` | Bounded queue, generations, acceptance/ack deadlines, stack-rejection backoff and byte wiping |
| Write driver | `euc-ble-core/session.WriteSession` | Supplies stack results and acknowledgments to the pure write policy |
| Reconnect policy | `euc-ble-session/session.ReconnectPolicy` | Computes bounded exponential backoff and jitter without scheduling or platform APIs |
| Connection lifecycle | `euc-ble-session/session.ConnectionSession` | Connect failure/timeout, discovery actions, reconnect deadlines and manual cancellation |
| Connection driver | `euc-ble-core/session.ConnectionSessionDriver` | Executes lifecycle actions through `BleTransport` and schedules deadlines |
| Transport port | `euc-ble-session/session.BleTransport` | Connect/disconnect, discovery, subscriptions, characteristic availability and accepted writes; Android handles stay in the connector |

Sync protocols must not use coroutines, threads, locks, timers or direct clock reads, and need not be
thread-safe. The migrated implementations use synchronous parsing and flow-compatible facades.
All built-in protocol families now have synchronous state machines. Gotway and Leaperkim/Nosfet
intentionally process incoming chunks in wire order instead of launching one coroutine per chunk;
the previous scheduling-dependent reordering could associate state carried by one frame with a
later frame. KingSong's password
timeout starts only after write acceptance and expires as unconfirmed, not as proof of a bad password.
UUID handling and protocol byte/CRC operations now use portable core implementations; conversion to
`java.util.UUID` is confined to the Android connector. The engine sources compile for JVM and
JavaScript and their shared tests run on both targets. Java streams, sorted maps, reflection,
byte-array string constructors and platform-provider defaults are absent from this artifact.
Selection uses explicit protocol and parent identities, not runtime class names. The
`EUCDevice.timestamp` default is `0` when the caller has no discovery timestamp; Android scan
results provide the actual wall-clock timestamp.

#### Why JVM dependencies matter

`java.io`, `Calendar`, `SecureRandom`, Java reflection, and JVM-specific standard-library extensions
exist only with the Java runtime. They cannot be referenced from KMP common code or executed by
Kotlin/JS, Kotlin/Native, or Rust on an ESP32. Even seemingly ordinary Kotlin calls such as
`toSortedMap()` can hide a JVM dependency. Non-JVM compilation is the check, not an import search.
JVM APIs are fine in the JVM session/connector boundary; they are not a problem everywhere.
Coroutines themselves support KMP, but do not belong in synchronous protocol engines.

The JVM facades retain production clocks and secure randomness. Other hosts supply their own
`EucClock` and `EucRandom`; deterministic test providers must not become production defaults.
Drain every output after each input, perform writes externally, report actual write acceptance,
and deliver timer expiries only while that session remains active.

#### Typed protocol inputs

The synchronous `createCommand` contract and `ProtocolQuerySpec.value` use `CommandValue`, not
`Any`. Available variants are `None`, `Integer`, `Toggle`, `Text`, `Bytes`, `Integers` and
`PasswordChangeRequest`. This is a Kotlin type-safety and ownership boundary, not a Rust binding.
Binary/list inputs snapshot their sources and return copies; mutating a command result cannot
alter the next polling request. Text/password diagnostics are redacted. This does not make
credential strings erasable, nor make other protocol byte arrays immutable.

The JVM `EUCProtocol.createCommand(CommandType, Any)` overload remains the legacy extension point.
Built-in facades convert `Unit`, `Int`, `Boolean`, `String`, `ByteArray`, `IntArray`, integer lists,
and password-change requests before entering the engine. Unrepresentable values are logged and
return an empty payload, preserving the existing failure convention. Mixed-type integer lists
are now rejected rather than silently filtering out non-integers. Android reports empty payloads
through its error callback. Polling still invokes the legacy extension point so existing custom
facades and delegated decorators keep their encoding overrides.

Direct engine callers and custom polling-plan builders must migrate to typed values:

```kotlin
engine.createCommand(CommandType.BEEP)
engine.createCommand(CommandType.SET_LIGHT_MODE, CommandValue.Integer(2))
ProtocolQuerySpec("probe", CommandType.CUSTOM, CommandValue.Bytes(probeBytes))
```

An empty encoded payload still conflates unsupported commands, wrong value variants and state
restrictions. A typed encoding result with explicit rejection reasons remains follow-up work;
the typed value model alone does not enforce each command's permitted variant at compile time.

#### Architecture audit and remaining work

**Goal:** this remains a Kotlin/KMP library. A separate sister Rust library may use the architecture
and behavior as a model; this project does not need FFI, Rust-compatible APIs, Rust crates, or
allocation-free firmware constraints. KMP does not imply Kotlin can target ESP32 RISC-V.
The common command vectors in `euc-ble-protocols/fixtures/commands.tsv` and replay tests are
behavioral regression evidence, not proof of a complete architecture or a complete future sister library.

Execution order for finishing phases 0-6: freeze capture outputs; enforce module boundaries;
extract write/timer/lifecycle policies; wire the transport port; run cross-target and compatibility
checks; then audit against the original completion criteria. Rust code remains out of scope.

The current dependency direction is:

```text
Kotlin application
  -> JVM coroutine drivers -> KMP synchronous sessions -> KMP synchronous protocols
           |
           v
     BLE transport interface <- platform BLE connector
```

Both portable modules enforce coroutine-free runtime dependencies and compile/test on JVM and JS.
The portable sessions are synchronous state machines, not a portable async runtime. JVM locks, jobs
and flows deliberately remain in `euc-ble-core`; a different host drives the same policies with its
own serialization and scheduling. Android permission checks and GATT handles remain in Android.

| Phase | Implementation and evidence | Completion check |
|---|---|---|
| 0 Safety net | All 58 corpus files are accounted for: 52 raw CSVs and 2 HCI captures replay synchronously; 4 decoded/example CSVs are explicitly classified. Compressed JSON goldens preserve complete telemetry, security events, write bytes, timer actions and BMS snapshots. | JVM replay compares every ordered output and input hash; regeneration is opt-in. CI includes this test. |
| 1 Module split | Separate protocol, session, JVM-driver and Android modules; both KMP modules reject coroutine dependencies. | Protocol/session checks run without Android on JVM and JS. |
| 2 Contracts/bridge | Synchronous contracts remain; the temporary `FlowProtocolAdapter` is removed. Permanent public facades use a driver over the pure `ProtocolSession`. | Compatibility tests exercise the same public facades. |
| 3 Migration | All eight engines reassemble synchronously; clocks/randomness are injected. | Portable vectors and existing protocol suites exercise the migrated behavior. |
| 4 Session extraction | Pure selection, polling, write queue/generation, protocol timers, connection lifecycle and reconnect deadlines are in `euc-ble-session`. | Common tests use supplied time without transports or real delays; JVM drivers have fake-transport tests. |
| 5 Android boundary | `BLEManager` translates GATT callbacks, snapshots, permissions and public flows; the consumed `BleTransport` port executes connection/discovery/write actions. Public client API and child-scope cleanup remain. | Android source/test compilation is checked against API 36; standard AGP tests and lint require the Android SDK and Google Maven access. Do not equate source compilation with a full Android quality gate. |
| 6 Portable behavior | UUIDs use two 64-bit halves; byte/CRC helpers are common code. TSV vectors cover commands and notifications for all engines, and connection lifecycle inputs/actions. Capture goldens are JSON, not Kotlin serialization. | JVM/JS shared tests plus full synchronous capture replay; no Rust toolchain or interop added. |

Local checks pass for both portable targets and the complete core suite. The updated CI workflow
has not yet run against these changes. Android compilation used an API-36 JVM harness, not AGP.

This is not a claim of Kotlin/Native, real-radio, or every-model compatibility. Additional platform
connectors, Native targets, full transport capability negotiation and serialized CCCD subscription
operations are follow-up work. The standard Android quality gate must pass before calling phase 5
fully verified.

Polling response deadlines and `SENT` traces start at transport acceptance, never at enqueue time.
Response latency uses monotonic milliseconds; Android translates trace timestamps to wall-clock
time for the public flow. Startup queries finish sequentially before periodic polling begins.
Model-dependent periodic plans are refreshed without restarting completed one-shot queries.
Stopping a session invalidates queued requests and callbacks without cancelling the caller's scope.
Accepted writes are never automatically retried: a missing GATT callback only releases the queue
after its guard timeout, and is not evidence of a wheel acknowledgment.
Transport write callbacks run outside the write-queue lock. Connection outputs carry generations
so reentrant application callbacks cannot apply stale failures to a replacement connection.

`BLEManager` supplies Android service snapshots, transport submission and callback notifications.
Protocol-selection precedence, connection/reconnection decisions, timeout state and write-queue
mechanics live in portable sessions. Notification setup, forced/manual activation wiring and public
flow collection remain in the connector/driver boundary.
Each manager and client owns a child coroutine job: cleanup cancels its own work without cancelling
the caller's scope. GATT callbacks are accepted only from the currently owned connection, and failed
device resolution, stack rejection or initial timeout leaves the connection state disconnected.
Protocol timers are bounded to 32 entries; lifecycle outputs to 32; writes to 64 queued entries;
protocol/polling/frame outputs to 256. Control overflow is explicit. Compatibility telemetry alone
uses bounded drop-oldest delivery; it is not the lossless golden replay path.

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

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
│  │ BLEManager  │───▶│ EUCProtocol Interface  │  │
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
│                     │ VeteranProtocol        │  │
│                     └─────────────────────────┘  │
└─────────────────────────────────────────────────┘
                    │
                    ▼
┌─────────────────────────────────────────────────┐
│               Android BLE System                  │
│  (BluetoothAdapter, BluetoothGatt, etc.)         │
└─────────────────────────────────────────────────┘
```

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
include ':euc-ble-core'

// app/build.gradle
dependencies {
    implementation project(':euc-ble-core')
}
```

### Step 2: Initialize BLE Manager

```kotlin
val bleManager = BLEManager(context)
bleManager.initialize()
```

### Step 3: Register Protocols

```kotlin
bleManager.registerProtocol(KingsongProtocol())
bleManager.registerProtocol(GotwayProtocol())
// Add more protocols as needed
```

### Step 4: Set Up Callbacks

```kotlin
bleManager.setScanCallback(object : ScanCallback {
    override fun onDeviceDiscovered(device: EUCDevice) {
        // Update UI with discovered device
    }
})

bleManager.setDataCallback(object : DataCallback {
    override fun onDataReceived(data: EUCData) {
        // Update dashboard with real-time data
    }
})
```

### Step 5: Start Using the Library

```kotlin
// Scan for devices
bleManager.startScan()

// Connect to a device
bleManager.connect(discoveredDevice)

// Send commands
val command = KingsongProtocol().createCommand(CommandType.LIGHT_ON, Unit)
bleManager.sendCommand(command, KingsongProtocol().getDataCharacteristicUUID())
```

## Best Practices

### 1. **Error Handling**

```kotlin
bleManager.setErrorCallback(object : ErrorCallback {
    override fun onError(error: BLEException) {
        when (error.errorType) {
            ErrorType.BLUETOOTH_DISABLED -> requestBluetoothEnable()
            ErrorType.CONNECTION_FAILED -> showRetryOption()
            ErrorType.DATA_DECODING_FAILED -> logError(error)
            // Handle other error types appropriately
        }
    }
})
```

### 2. **Memory Management**

```kotlin
override fun onDestroy() {
    bleManager.cleanup()
    super.onDestroy()
}
```

### 3. **Configuration**

```kotlin
// Customize timeouts and behavior
bleManager.setScanTimeout(15000) // 15 seconds
bleManager.setConnectionTimeout(8000) // 8 seconds
bleManager.setAutoReconnect(true)
bleManager.setMaxRetries(3)
```

### 4. **Logging**

```kotlin
// Use custom logger for better control
val customLogger = CustomLogger()
val bleManager = BLEManager(context, customLogger)
```

## Extending the Library

### Adding New Manufacturers

1. **Create Protocol Class**

```kotlin
class NewManufacturerProtocol : EUCProtocol {
    override val manufacturer: String = "NewManufacturer"
    override val supportedModels: List<String> = listOf("Model1", "Model2")
    
    override fun canHandle(device: EUCDevice): Boolean {
        // Implement device detection logic
    }
    
    override fun decode(data: ByteArray): EUCData? {
        // Implement data parsing logic
    }
    
    override fun createCommand(commandType: CommandType, value: Any): ByteArray {
        // Implement command creation
    }
    
    override fun getServiceUUID(): UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    override fun getDataCharacteristicUUID(): UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    
    override fun isDeviceReady(data: EUCData): Boolean {
        // Implement readiness check
    }
}
```

2. **Register Protocol**

```kotlin
bleManager.registerProtocol(NewManufacturerProtocol())
```

### Customizing Data Processing

```kotlin
class CustomDataProcessor : DataCallback {
    override fun onDataReceived(data: EUCData) {
        // Apply custom processing
        val processedData = processEUCData(data)
        
        // Forward to other components
        analyticsService.logData(processedData)
        cloudService.syncData(processedData)
        uiService.updateDashboard(processedData)
    }
    
    private fun processEUCData(rawData: EUCData): ProcessedData {
        // Implement custom processing logic
    }
}
```

## Performance Considerations

### 1. **Scan Optimization**

- Use appropriate scan settings for your use case
- Limit scan duration to conserve battery
- Filter devices early to reduce processing

### 2. **Connection Management**

- Implement proper connection timeouts
- Handle reconnection logic appropriately
- Clean up resources when done

### 3. **Data Processing**

- Process data on background threads
- Batch UI updates when possible
- Use efficient data structures

### 4. **Memory Usage**

- Be mindful of byte array allocations
- Reuse buffers when possible
- Clean up callbacks to prevent leaks

## Troubleshooting

### Common Issues

1. **Bluetooth Not Enabled**
   - Check `bleManager.isBluetoothEnabled()`
   - Request Bluetooth enable if needed

2. **Device Not Detected**
   - Verify device is in range and powered on
   - Check manufacturer ID and naming patterns
   - Ensure protocol can handle the device

3. **Connection Failures**
   - Check for proper permissions
   - Verify device compatibility
   - Handle connection timeouts appropriately

4. **Data Decoding Errors**
   - Validate data packet structure
   - Check for correct protocol implementation
   - Handle malformed data gracefully

### Debugging Tips

```kotlin
// Enable detailed logging
val debugLogger = object : Logger {
    override fun verbose(tag: String, message: String) {
        Log.v("BLE_DEBUG", "$tag: $message")
    }
    // Implement other methods similarly
}

val bleManager = BLEManager(context, debugLogger)
```

## Conclusion

The EUC BLE Core Library provides a clean, modular architecture for working with Electric Unicycle Bluetooth protocols. The PlantUML diagrams help visualize the structure and relationships between components, making it easier to understand, extend, and maintain the library.

For more detailed information, refer to the individual diagram files and the comprehensive README.
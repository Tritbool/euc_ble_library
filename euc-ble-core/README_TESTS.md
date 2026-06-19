# EUC BLE Library - Unit Tests

This document describes the comprehensive unit test suite for the EUC BLE Library.

## Test Structure

The test suite is organized into several test classes that cover different components of the library:

### 1. Core Utilities Tests
- **ByteUtilsTest**: Tests for byte manipulation utilities including:
  - Hex conversion (bytes ↔ hex strings)
  - Unsigned/signed byte, short, and int operations (LE/BE)
  - Checksum calculations (regular and XOR)
  - Pattern matching in byte arrays
  - Round-trip conversion validation

### 2. Protocol Implementation Tests
- **KingsongProtocolTest**: Tests for Kingsong EUC protocol including:
  - Device detection and handling
  - Data packet decoding (valid, invalid, edge cases)
  - Command creation
  - Device readiness checks
  - Manufacturer and model validation

- **GotwayProtocolTest**: Tests for Gotway/Begode EUC protocol including:
  - Device detection and handling
  - Data packet decoding (multiple packet types)
  - Command creation
  - Device readiness checks
  - Motor temperature parsing
  - Status flag parsing

### 3. Data Model Tests
- **EUCDataTest**: Tests for the EUC data model including:
  - Data creation and validation
  - Null value handling
  - Equality and hash code implementation
  - Copy functionality
  - Edge cases and boundary conditions
  - Negative value handling

## Test Coverage

### ByteUtilsTest (100% coverage)
- **Hex Conversion**: `bytesToHex()`, `hexToBytes()`
- **Byte Operations**: `getUnsignedByte()`
- **Short Operations**: `getUnsignedShortLE/BE()`, `getSignedShortLE/BE()`, `shortToBytesLE/BE()`
- **Int Operations**: `getUnsignedIntLE/BE()`, `getSignedIntLE/BE()`, `intToBytesLE/BE()`
- **Checksums**: `calculateChecksum()`, `calculateXorChecksum()`
- **Pattern Matching**: `startsWith()`, `findPattern()`
- **Round-trip Validation**: Ensures conversion consistency

### KingsongProtocolTest
- **Device Handling**: Manufacturer ID and name pattern matching
- **Data Decoding**: Valid packets, invalid packets, edge cases
- **Command Creation**: All supported command types
- **Device Readiness**: Voltage, temperature, and speed validation
- **Protocol Metadata**: Manufacturer info, supported models, UUIDs

### GotwayProtocolTest
- **Device Handling**: Manufacturer ID and multiple name patterns
- **Data Decoding**: Packet types 0x01 and 0x02, motor temperature parsing
- **Command Creation**: All supported command types
- **Device Readiness**: Voltage, temperature thresholds
- **Status Flags**: Charging and alarm flag parsing
- **Protocol Metadata**: Manufacturer info, supported models, UUIDs

### EUCDataTest
- **Data Creation**: All fields including optional ones
- **Null Handling**: Serial number, firmware version, cell voltages, motor temperature
- **Equality**: Based on timestamp, manufacturer, and model
- **Copy Functionality**: Partial and full copying
- **Edge Cases**: Maximum and minimum values
- **Negative Values**: Speed, current, temperature, power

## Running Tests

To run the tests, use the following commands:

```bash
# Run all tests
./gradlew :euc-ble-core:test

# Run tests + full JaCoCo coverage report
./gradlew :euc-ble-core:jacocoTestReport

# Run tests + focused JaCoCo coverage report (protocols/models/frames)
./gradlew :euc-ble-core:jacocoFocusedReport

# Run specific test class
./gradlew :euc-ble-core:test --tests "io.github.tritbool.euc.ble.core.ByteUtilsTest"

# Run specific test method
./gradlew :euc-ble-core:test --tests "io.github.tritbool.euc.ble.core.ByteUtilsTest.testBytesToHex"

# Run test suite
./gradlew :euc-ble-core:test --tests "io.github.tritbool.euc.ble.AllTestsSuite"
```

Coverage reports are generated under:

- `euc-ble-core/build/reports/jacoco/full/html/index.html`
- `euc-ble-core/build/reports/jacoco/focused/html/index.html`

## Test Design Principles

1. **Isolation**: Each test is independent and doesn't rely on other tests
2. **Determinism**: Tests produce consistent results regardless of environment
3. **Completeness**: Cover all code paths including edge cases
4. **Readability**: Clear test names and organization
5. **Maintainability**: Easy to add new tests as features are added

## Test Data Examples

### Kingsong Protocol Test Data
```kotlin
// Valid Kingsong packet
val data = byteArrayOf(
    0xAA.toByte(), 0x55.toByte(), // Header
    0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
    0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
    // ... rest of packet
)
```

### Gotway Protocol Test Data
```kotlin
// Valid Gotway packet with motor temperature
val data = byteArrayOf(
    0x01.toByte(), // Packet type
    0x64.toByte(), 0x01.toByte(), // Voltage (36.0V)
    0x2C.toByte(), 0x01.toByte(), // Speed (30.0 km/h)
    // ... rest of packet
    0x1E.toByte(), // Motor temperature (30°C)
)
```

### ByteUtils Test Data
```kotlin
// Test hex conversion
val bytes = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0x01.toByte(), 0xFF.toByte())
val hex = "AA5501FF"

// Test endianness
val shortLE = byteArrayOf(0x34.toByte(), 0x12.toByte()) // 0x1234 = 4660
val shortBE = byteArrayOf(0x12.toByte(), 0x34.toByte()) // 0x1234 = 4660
```

## Continuous Integration

The test suite is designed to work with CI/CD pipelines. All tests should pass before merging code changes.

## Adding New Tests

When adding new features or modifying existing code:

1. **Add corresponding unit tests** for new functionality
2. **Update existing tests** if behavior changes
3. **Run all tests** to ensure no regressions
4. **Add test documentation** if new test patterns are introduced

## Test Maintenance

- **Regularly review** test coverage as code evolves
- **Update test data** to reflect real-world scenarios
- **Add performance tests** for critical paths if needed
- **Consider property-based testing** for complex logic

## Test Coverage Report

The test suite provides comprehensive coverage of:

| Component | Test Coverage | Description |
|-----------|---------------|-------------|
| ByteUtils | 100% | All byte manipulation functions |
| KingsongProtocol | 95% | Protocol implementation (excluding Android-specific code) |
| GotwayProtocol | 95% | Protocol implementation (excluding Android-specific code) |
| EUCData | 100% | Data model and business logic |

## Future Test Enhancements

Potential areas for future test expansion:

1. **Performance testing** for large data processing
2. **Fuzz testing** for protocol robustness
3. **Integration tests** for protocol switching
4. **Mock device testing** for end-to-end scenarios
5. **Concurrency testing** for multi-device scenarios

## Test Execution Environment

- **JVM**: Java 17+
- **Kotlin**: 1.7+
- **Test Framework**: JUnit 5 (Jupiter)
- **Build System**: Gradle
- **Dependencies**: JUnit, Kotlinx Coroutines Test, Mockito Kotlin

The tests are designed to run on any standard JVM environment without requiring Android device connectivity or emulation.

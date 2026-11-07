# EUC BLE Core

This pure Kotlin/JVM module provides the protocol implementation (decoders, command builders,
frame parsers and models) used by [`EucBleClient`](../README.md). It has no Android dependency;
the Android BLE connector lives in [`euc-ble-android`](../euc-ble-android). The client registers
built-in protocols; most applications should use that entry point instead of registering protocol
classes themselves.

> **Work in progress — version 0.1.0.** Protocol support and command coverage are incomplete.
> Behavior may be broken or vary by wheel and firmware, and APIs may evolve. Validate on the
> physical device you intend to use. Passing unit tests is not a guarantee of device compatibility.

## Current protocol implementations

- KingSong
- Gotway / Begode
- ExtremeBull (currently reuses Gotway decoding)
- InMotion
- Ninebot and Ninebot Z
- Leaperkim / Veteran
- Nosfet (currently reuses Leaperkim protocol behavior)

Support is not uniform: telemetry fields, BMS data, polling, and supported commands vary by
protocol and firmware. Consult the protocol support declarations and tests for implementation
details. Some wheels do not expose BMS data, and unsupported commands should not be sent.

## Testing

The test suite combines protocol unit tests with offline replay of available WheelLog captures.
The captures and synthetic edge cases cover only part of the supported device/firmware space.
Run the tests and coverage reports from the repository root:

```bash
# Pure core (no Android SDK required)
./gradlew -Peuc.includeAndroid=false :euc-ble-core:test --tests io.github.tritbool.euc.ble.AllTests
./gradlew -Peuc.includeAndroid=false :euc-ble-core:jacocoTestReport :euc-ble-core:jacocoFocusedReport
# Android connector (requires the Android SDK)
./gradlew :euc-ble-android:testDebugUnitTest --tests io.github.tritbool.euc.ble.AndroidTests
```

See [`README_TESTS.md`](README_TESTS.md) for the test inventory and
[`PARITY_MATRIX.md`](PARITY_MATRIX.md) for known areas of partial protocol parity.

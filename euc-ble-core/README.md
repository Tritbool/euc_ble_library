# EUC BLE Core

This Kotlin Multiplatform module provides coroutine protocol facades and session drivers,
exposing the synchronous protocol and session modules used by [`EucBleClient`](../README.md).
All production sources are in `src/commonMain/kotlin`, with JVM, JavaScript/Node and iOS
device/simulator targets. It has no Android or Apple framework imports;
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

## Randomness

`SecureEucRandom()` uses Kotlin's `Uuid.random()` entropy on JVM/Android and Apple targets,
without a cryptography dependency. Only random UUID bits are used; Kotlin's bounded-integer
sampling avoids modulo bias. This is for protocol nonce digits and reconnect jitter, not a
general cryptographic API. Kotlin/WasmWasi is not covered by the secure-entropy guarantee.
The former constructor accepting `java.security.SecureRandom` is no longer available;
callers needing a custom source can supply their own `EucRandom`.

## Testing

The test suite combines protocol unit tests with offline replay of available WheelLog captures.
The captures and synthetic edge cases cover only part of the supported device/firmware space.
Run the tests and coverage reports from the repository root:

```bash
# Pure core (no Android SDK required)
./gradlew -Peuc.includeAndroid=false :euc-ble-core:test --tests io.github.tritbool.euc.ble.AllTests
./gradlew -Peuc.includeAndroid=false :euc-ble-core:jacocoTestReport :euc-ble-core:jacocoFocusedReport
# Shared runtime tests on JavaScript (Node 22 or newer)
./gradlew -Peuc.includeAndroid=false :euc-ble-core:jsNodeTest
# iOS simulator tests on an Apple Silicon Mac (use iosX64Test on Intel)
./gradlew -Peuc.includeAndroid=false :euc-ble-core:iosSimulatorArm64Test
# Android connector (requires the Android SDK)
./gradlew :euc-ble-android:testDebugUnitTest --tests io.github.tritbool.euc.ble.AndroidTests
```

See [`README_TESTS.md`](README_TESTS.md) for the test inventory and
[`PARITY_MATRIX.md`](PARITY_MATRIX.md) for known areas of partial protocol parity.

The legacy `test` task retains its JVM test filters and report paths; `jvmTest` is also
available. Existing capture/JUnit tests remain in `src/test`, and shared runtime tests live
in `src/commonTest`. JVM-only fixture sources in `src/testFixtures` are compiled into the
core and Android test source sets, not published as Java test fixtures.

## Platform boundaries and consumption

Coroutines/flows stay in core; protocol engines and session policies remain coroutine-free.
Core uses Stately's reentrant lock, Kotlin monotonic time, `kotlinx-datetime` for local time
and UUID-backed randomness. These dependencies implement platform primitives internally;
core contains no platform-specific implementations. Android GATT stays in `euc-ble-android`;
an iOS BLE connector is separate work.

Gradle consumers keep `io.github.tritbool:euc-ble-core` and select the target automatically.
JVM-only consumers that do not read Gradle module metadata should use
`io.github.tritbool:euc-ble-core-jvm`. The root publication is now KMP metadata, not the
previous JVM JAR. Apple KLIBs can be cross-compiled; running simulator tests requires macOS.
The release workflow uses macOS to validate the Apple build environment.

# EUC BLE Library – Test Suite

## Philosophy: real-device captures, not synthetic frames

Most BLE protocol libraries test against hand-crafted byte arrays — frames that are  *thought*
to be valid. This library takes a different approach: **every protocol decoder is validated against
raw BLE traffic recorded from physical wheels**, replayed offline under JUnit 5 without any Android
device or emulator.

Captures are stored as CSV files exported from
[WheelLog](https://github.com/Wheellog/Wheellog.Android) under:

```
src/test/resources/bleframes/<brand>/RAWWHEELLOG/
```

Each CSV row contains the exact byte sequence received from the wheel over BLE. This means:

- Decoders are validated against **authentic protocol behaviour**, not assumptions
- Edge cases encountered in the wild (fragmented frames, garbage between headers, firmware variants)
  are permanently encoded as regression tests
- Adding coverage for a new firmware version is as simple as dropping a new capture file in the
  right folder

---

## Test inventory

### WheelLog integration tests (real captures)

These tests feed actual BLE capture sequences through the full decode pipeline and assert on the
resulting `EUCData` fields.

| Test class | Brand | Capture files |
|---|---|---|
| `WheelLogGotwayTest` | Gotway / Begode | `bleframes/gotway/RAWWHEELLOG/` |
| `WheelLogKingsongTest` | KingSong | `bleframes/kingsong/RAWWHEELLOG/` |
| `WheelLogLeaperkimTest` | Leaperkim / Veteran | `bleframes/leaperkim/RAWWHEELLOG/` |
| `WheelLogInMotionTest` | InMotion | `bleframes/inmotion/RAWWHEELLOG/` |
| `WheelLogNinebotTest` | Ninebot | `bleframes/ninebot/RAWWHEELLOG/` |
| `WheelLogNosfetTest` | Nosfet | `bleframes/nosfet/RAWWHEELLOG/` |

### Protocol unit tests (edge cases)

These tests cover boundary values, invalid frames, frame fragmentation, and firmware-specific
branching that may not appear in captures.

| Test class | What it covers |
|---|---|
| `GotwayProtocolTest` | Type A/B frames, PWM, trip distance, negative current, out-of-range drop |
| `KingsongProtocolTest` | A4/A9/F5/F6/B3/B9/BB/F2 frame types, BMS pages, alarm speeds, speed limit |
| `KingsongProtocolAsyncTest` | Fragmented frames, header variants (55AA), leading noise, resync |
| `InMotionProtocolTest` | Legacy vs V2 dialect switching, alert frames |
| `NinebotProtocolTest` | Query orchestration, serial/firmware/battery polling |
| `NinebotZProtocolTest` | Z-series handshake, BMS, settings polling |
| `LeaperkimProtocolTest` | Legacy settings fields, Smart BMS pages, out-of-range voltage drop |
| `NosfetProtocolTest` | Telemetry decoding |

### No-drop tests

Verify that no decoded frame is silently lost between `decode()` and the `dataFlow` collector,
under concurrent load.

| Test class | Brand |
|---|---|
| `GotwayNoDropTest` | Gotway / Begode |
| `KingsongNoDropTest` | KingSong |
| `InmotionNoDropTest` | InMotion |
| `LeaperkimNoDropTest` | Leaperkim / Veteran |
| `NinebotNoDropTest` | Ninebot |
| `NosfetNoDropTest` | Nosfet |

### Cross-protocol and utility tests

| Test class | What it covers |
|---|---|
| `ProtocolParityContractTest` | Same field semantics across all protocol implementations |
| `BleFrequencyAnalysisTest` | Emission rate and timing characteristics per brand |
| `GotwayFrameReassemblerTest` | Frame reassembly from fragmented BLE notifications |
| `EucBleClientEntryPointWheelLogTest` | Full stack: metadata detection + frame selection per protocol |
| `FrameReassemblerStaticFlowTest` | Static flow through the reassembler |
| `ByteUtilsTest` / `ByteUtilsSafeAccessTest` | All byte manipulation helpers (LE/BE, checksums, pattern match) |
| `EUCDataTest` | Data model: equality, copy, null handling, boundary values |

### Test suites

| Suite | Contents |
|---|---|
| `AllTests` | Entry point for CI – runs everything |
| `RegularTestsSuite` | All tests except those tagged `@SlowTest` |
| `NoDropTestsSuite` | No-drop tests only |

### Enhancement needed
| Class | What it covers |
|---|---|
| `ExtremeBullProtocol` | Raw frames needed for test |
| `NinebotProtocol` | Raw frames from ninebot non-Z wheel needed to enhance testing |
---

## Running tests

```bash
# Full suite (used in CI)
./gradlew :euc-ble-core:testDebugUnitTest --tests com.euc.ble.AllTests

# Full suite + JaCoCo coverage reports
./gradlew :euc-ble-core:testDebugUnitTest \
  :euc-ble-core:jacocoTestReport \
  :euc-ble-core:jacocoFocusedReport

# Single test class
./gradlew :euc-ble-core:testDebugUnitTest --tests com.euc.ble.protocols.KingsongProtocolTest

# Single test method
./gradlew :euc-ble-core:testDebugUnitTest \
  --tests "com.euc.ble.protocols.KingsongProtocolTest.decodeA4ThenA9IncludesAlarmSpeeds"
```

Coverage reports are generated under:

```
euc-ble-core/build/reports/jacoco/full/html/index.html      # full module
euc-ble-core/build/reports/jacoco/focused/html/index.html   # protocols + models + frames only
```

---

## Adding a new protocol or firmware variant

1. Record a BLE session with WheelLog on the target wheel.
2. Export the raw CSV and drop it in `src/test/resources/bleframes/<brand>/RAWWHEELLOG/`.
3. Create a `WheelLog<Brand>Test` that loads the file and feeds frames through the protocol decoder.
4. Add edge-case unit tests for any branching not covered by the capture.
5. Add a `<Brand>NoDropTest` extending `ProtocolNoDropTestBase`.

---

## Test environment

- **JVM**: Java 17
- **Test framework**: JUnit 5 (Jupiter) + Platform Suite
- **Async utilities**: kotlinx-coroutines-test, Turbine
- **Mocking**: Mockito Kotlin
- **No Android device or emulator required**

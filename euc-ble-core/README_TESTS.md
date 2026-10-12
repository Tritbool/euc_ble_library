# EUC BLE Library – Test Suite

## Test approach: captures and unit tests

The suite combines protocol-specific unit tests (including constructed edge cases) with offline
replay of raw BLE captures from physical wheels. Capture coverage is partial: a passing test does
not establish compatibility with every model, firmware, command, or operating condition.

Captures are stored as CSV files exported from
[WheelLog](https://github.com/Wheellog/Wheellog.Android) under:

```
src/test/resources/ble_frames/<brand>/RAW_WHEELLOG/
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
| `WheelLogGotwayTest` | Gotway / Begode | `ble_frames/gotway/RAW_WHEELLOG/` |
| `WheelLogKingsongTest` | KingSong | `ble_frames/kingsong/RAW_WHEELLOG/` |
| `WheelLogLeaperkimTest` | Leaperkim / Veteran | `ble_frames/leaperkim/RAW_WHEELLOG/` |
| `WheelLogInMotionTest` | InMotion | `ble_frames/inmotion/RAW_WHEELLOG/` |
| `WheelLogNinebotTest` | Ninebot | `ble_frames/ninebot/RAW_WHEELLOG/` |
| `WheelLogNosfetTest` | Nosfet | `ble_frames/nosfet/RAW_WHEELLOG/` |

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
| `ExtremeBullProtocolTest` | Advertisement-name matching, manufacturer identity, inherited Gotway BLE requirements |

### No-drop tests

Exercise decoded-data delivery from each protocol under concurrent load.

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
| `ProtocolParityContractTest` | Selected command support and polling-plan contracts |
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
| `RegularTestsSuite` | Selected protocol, capture, and utility tests |
| `NoDropTestsSuite` | No-drop tests plus client entry-point capture integration |

### Known capture gaps

- ExtremeBull currently inherits Gotway telemetry parsing. One ExtremeBull Rocket capture is
  replayed by `WheelLogGotwayTest`; additional models and firmware variants need broader coverage.
- Additional Ninebot non-Z captures would improve validation of that parser.
---

## Running tests

```bash
# Full suite (used in CI)
./gradlew :euc-ble-core:testDebugUnitTest --tests io.github.tritbool.euc.ble.AllTests

# Full suite + JaCoCo coverage reports
./gradlew :euc-ble-core:testDebugUnitTest \
  :euc-ble-core:jacocoTestReport \
  :euc-ble-core:jacocoFocusedReport

# Single test class
./gradlew :euc-ble-core:testDebugUnitTest --tests io.github.tritbool.euc.ble.protocols.KingsongProtocolTest

# Single test method
./gradlew :euc-ble-core:testDebugUnitTest \
  --tests "io.github.tritbool.euc.ble.protocols.KingsongProtocolTest.decodeA4ThenA9IncludesAlarmSpeeds"
```

Coverage reports are generated under:

```
euc-ble-core/build/reports/jacoco/full/html/index.html      # full module
euc-ble-core/build/reports/jacoco/focused/html/index.html   # protocols + models + frames only
```

---

## Adding a new protocol or firmware variant

1. Record a BLE session with WheelLog on the target wheel.
2. Export the raw CSV and drop it in `src/test/resources/ble_frames/<brand>/RAW_WHEELLOG/`.
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

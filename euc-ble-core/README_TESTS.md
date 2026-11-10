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

## Portable engine tests

Run `./gradlew -Peuc.includeAndroid=false :euc-ble-protocols:check` with Node.js/npm on `PATH`.
This compiles the engines without JVM APIs and runs common tests on JVM and JavaScript.
`PortableReplayTest` covers golden commands and notification vectors for all eight engines,
fragmented InMotion authentication, acceptance-aware
timers, reset and byte/CRC utilities. The separate `euc-ble-session:check` also tests portable
selection, polling deadlines, bounded writes/generations, protocol timers and connection/reconnect
state. Shared lifecycle inputs/actions are in `euc-ble-session/fixtures/connection.tsv`.
`BMSDataTest` now lives in `euc-ble-protocols/src/commonTest` and runs on both targets.
Command and telemetry fixtures are in `euc-ble-protocols/fixtures/commands.tsv` and
`euc-ble-protocols/fixtures/telemetry.tsv`.

`CaptureGoldenTest` (in core's `AllTests`) replays the synchronous engines directly, draining outputs
after every input without flows, jobs or real time. Its manifest accounts for all 58 corpus files:
52 raw CSVs and both HCI captures are replayed; 4 decoded/example CSVs are explicitly excluded from
byte decoding. HCI ACL continuations are reassembled before extracting ATT notifications. Two raw
KingSong noise-only captures intentionally have no decoded outputs.

Full expected outputs are checked in under `euc-ble-protocols/fixtures/captures/` as gzip-compressed
UTF-8 JSON Lines. Each row has `line` (CSV source line or HCI record index) and `event`; `type`
discriminates event/action objects. Telemetry includes every model field, byte arrays are lowercase
hex, enums are strings, and unavailable values are JSON null. Changed BMS pull snapshots are included.
Diagnostic logs are excluded. Inputs remain in the original corpus; `manifest.tsv` records their
SHA-256 hashes, classifications, and input/output counts. A foreign-language runner can pair these
inputs and outputs without loading Kotlin objects; compare floating-point measurements numerically.
The replay clock is fixed at epoch millisecond 100000 with local date 2026-01-01 00:00:00, and
randomness returns zero. Command/lifecycle TSV headers describe their input/value/action columns.

Regenerate only after reviewing an intentional behavior change, then run again without the flag:

```bash
./gradlew -Peuc.includeAndroid=false -PupdateReplayGoldens \
  :euc-ble-core:test --tests io.github.tritbool.euc.ble.AllTests
./gradlew -Peuc.includeAndroid=false \
  :euc-ble-protocols:check :euc-ble-session:check \
  :euc-ble-core:test --tests io.github.tritbool.euc.ble.AllTests
```

Golden fixtures freeze observed behavior, including existing limitations; they do not prove every
captured field is semantically correct. Gotway's intentional wire-order change is documented in
`ARCHITECTURE.md` and tested with ordered coalesced frames.

The JVM `AllTests` suite below covers compatibility/session behavior and existing capture replays;
it is not a replacement for the portable module's `check` task.

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
| `AllTests` | JVM compatibility/session and capture replay suite |
| `RegularTestsSuite` | Selected protocol, capture, and utility tests |
| `NoDropTestsSuite` | No-drop tests plus client entry-point capture integration |

### Known capture gaps

- ExtremeBull currently inherits Gotway telemetry parsing. One ExtremeBull Rocket capture is
  replayed by `WheelLogGotwayTest`; additional models and firmware variants need broader coverage.
- Additional Ninebot non-Z captures would improve validation of that parser.
---

## Running tests

```bash
# Core suite (used in CI; no Android SDK required with -Peuc.includeAndroid=false)
./gradlew -Peuc.includeAndroid=false :euc-ble-core:test --tests io.github.tritbool.euc.ble.AllTests

# Core suite + JaCoCo coverage reports
./gradlew :euc-ble-core:test \
  :euc-ble-core:jacocoTestReport \
  :euc-ble-core:jacocoFocusedReport

# Android connector suite (BLEManager, EucBleClient)
./gradlew :euc-ble-android:testDebugUnitTest --tests io.github.tritbool.euc.ble.AndroidTests

# Single test class
./gradlew :euc-ble-core:test --tests io.github.tritbool.euc.ble.protocols.KingsongProtocolTest

# Single test method
./gradlew :euc-ble-core:test \
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
4. Regenerate and review the synchronous goldens and manifest using the opt-in command above,
   then verify without the update flag.
5. Add edge-case unit tests for any branching not covered by the capture.
6. Add a `<Brand>NoDropTest` extending `ProtocolNoDropTestBase`.

---

## Test environment

- **Gradle daemon**: JDK 21; published JVM bytecode targets Java 17
- **Test framework**: JUnit 5 (Jupiter) + Platform Suite
- **Async utilities**: kotlinx-coroutines-test, Turbine
- **Mocking**: Mockito Kotlin
- **No Android device or emulator required**

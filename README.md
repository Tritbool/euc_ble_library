# EUC BLE Library

[![Tests](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml/badge.svg)](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml)
[![Coverage (protocols/models/frames)](https://img.shields.io/badge/coverage-protocols%2Fmodels%2Fframes%20in%20CI%20summary-blue)](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml)

## Automated quality checks

CI runs Android Lint, unit tests and full/focused JaCoCo coverage on pushes and PRs.
SonarCloud analyzes PRs and `main` updates; other branch pushes run the local checks only.
The **Quality gate** check fails on lint errors, test failures, missing Sonar analysis or a failed
Sonar quality gate. SonarCloud receives the full JaCoCo XML and Android Lint XML reports.
The former separate Sonar workflow is consolidated into CI so reports describe the same run.

The bilingual [quality dashboard](https://tritbool.github.io/euc_ble_library/en/quality/)
is published after completed `main` push checks, including failures. It displays the commit,
timestamp, test/lint results, coverage and Sonar quality gate, with detailed report links.
Sonar results are collected only after the submitted analysis completes. The gate is fetched by
analysis ID; metrics are omitted as stale if a newer analysis replaces that commit during collection.
Missing reports are unavailable, not zero or passing. PR artifacts are never deployed to Pages.
The dashboard anonymously checks GitHub's current `main` SHA to flag an older published snapshot;
if GitHub cannot be reached, freshness is unavailable. No authentication is sent from the website.
Manual Pages runs require a completed CI run for the current `main` commit and retained artifacts
(30 days). Dokka generation failure does not prevent publication of a failed quality snapshot.

Repository administrators must configure:

- `SONAR_TOKEN` as an Actions secret with permission to analyze this SonarCloud project.
  Disable SonarCloud automatic analysis if enabled, to use the CI-supplied reports.
- GitHub Pages source: **GitHub Actions**.
- A branch protection rule/ruleset for `main` requiring the **Quality gate** status check.
  Workflow files cannot enable required checks themselves; select the check after its first run.
- Fork PRs do not receive `SONAR_TOKEN`. Their lint/tests still run, but the gate fails explicitly
  until a maintainer opens a trusted-branch PR for the reviewed change. Do not use
  `pull_request_target` to execute fork code with secrets.

> **Work in progress — version 0.1.0.** This library is experimental: protocol support is
> incomplete, some features may be broken or behave differently across wheel/firmware versions,
> and public APIs and behavior may evolve. Validate it with the specific wheel and firmware you
> intend to use; do not assume that passing unit tests guarantees safe or complete behavior.

## Client application

[TuxWheel](https://github.com/Tritbool/TuxWheel) is the first client app built with this library.
It is available as a pre-release, with publication on F-Droid planned.

## Usage (single entry point)

`io.github.tritbool.euc.ble.EucBleClient` is the public BLE entry point for client applications.
It registers built-in protocols internally, so client code must not register brand-specific handlers.

Scan lifecycle and connection events are exposed through `ConnectionCallback`.
Decoded telemetry is exposed through `DataCallback`.
Errors are exposed through `ErrorCallback`.

Callbacks are invoked from background contexts and are not guaranteed to run on the Android main thread.
Switch explicitly to the main thread before touching Android UI.

## Telemetry units

`EUCData` exposes physical measurements in metric units: speed in km/h, distance in km, voltage in V,
current in A, temperature in °C, power in W, durations in seconds, angles in degrees, torque in N·m,
and tire pressure in kPa. Decoders convert supported wheel imperial settings before publishing values;
`usesMiles` is metadata for the wheel's preference and does not change those units.

`distance` is trip distance when the protocol reports it, while `totalDistance` is lifetime/odometer
distance. Legacy Ninebot telemetry only reports an odometer and therefore exposes that value in both
fields. KingSong `distance` and `wheelDistance` are populated from its trip frame; before that frame
arrives, `distance` is zero and `wheelDistance` is null. Integer mode/status settings are protocol-
specific codes unless otherwise documented on `EUCData`.

For KingSong, `speedLimit` and `wheelMaxSpeed` expose the configured maximum/tilt-back speed
from `A4`/`B5` byte 10; `alarm1Speed`/`alarm2Speed`/`alarm3Speed` are separate alarm thresholds
at bytes 4/6/8. These settings remain null until a settings reply arrives (startup sends `98`).
`dynamicSpeedLimit` exposes the independent runtime ceiling from `F6` bytes 2–3, unsigned
little-endian hundredths of km/h. Previously, KingSong `speedLimit` exposed this F6 value;
clients displaying the runtime ceiling should now use `dynamicSpeedLimit`.
Do not double F6 or infer a configured setting from it: the supplied S18 `ksbtsnoop.hex`
capture reports F6 `D0 07` (20 km/h) alongside B5 maximum `28` (40 km/h), whereas
`kslockbtsnoop.hex` contains F6 but no A4/B5 settings reply. The captures do not establish
why the runtime ceiling is lower (for example, a temporary restriction).
`SET_SPEED_LIMIT` writes the configured maximum via `85` byte 8, not F6, and
`SET_ALARM_SPEED` preserves the last reported configured maximum. Sending a command does
not optimistically change telemetry; a subsequent settings reply confirms it.

```kotlin
val client = EucBleClient(context)

client.setConnectionCallback(object : ConnectionCallback() {
    override fun onScanStarted() {
        // scan started
    }

    override fun onDeviceDiscovered(device: EUCDevice) {
        client.connect(device)
    }

    override fun onConnected() {
        // connected
    }

    override fun onDisconnected() {
        // disconnected
    }
})

client.setDataCallback(object : DataCallback {
    override fun onDataReceived(data: EUCData) {
        // telemetry
    }
})

client.setErrorCallback(object : ErrorCallback {
    override fun onError(error: BLEException) {
        // handle error
    }
})

client.initialize()
client.startScan()
```

For commands:

```kotlin
client.sendCommand(CommandType.LIGHT_ON)
```

## KingSong PIN authentication and lock control

Collect `client.securityStateFlow` for a client-owned PIN popup and lock indicator.
`WheelSecurityState`, `AuthenticationState`, and `WheelLockState` are in
`io.github.tritbool.euc.ble.models`. The state is `null` without a selected security-capable
protocol. For KingSong, authentication and lock status start as `UNKNOWN` and reset on
disconnect/reconnect or protocol changes. `StateFlow` delivers the latest state to new collectors;
it conflates identical updates. `client.securityEventFlow` emits each valid notification as
`WheelSecurityEvent.AuthenticationStatus` or `WheelSecurityEvent.LockStatus` (also in `models`),
including repeated authentication-required responses. Subscribe before submitting a PIN to
observe replies and let the app offer retry/cancel; these events do not identify a particular
attempt or contain the PIN. Events have no replay and bounded, drop-oldest buffering, so use the
state flow as the authoritative latest snapshot and implement waiting/timeouts in the client.

```kotlin
client.securityStateFlow.collect { state ->
    if (state?.authentication == AuthenticationState.REQUIRED) {
        // Ask for a PIN in your app (switch to Main for UI).
    }
}
// Explicit user actions, after checking getCommandSupport:
client.authenticate("0012")  // exactly four ASCII digits; keep leading zeros
client.requestLockState()
client.lock()
client.unlock()             // EXPERIMENTAL: validate on your wheel/firmware
```

Equivalent commands are `AUTHENTICATE` (String PIN), `REQUEST_LOCK_STATE`, `LOCK`, and `UNLOCK`.
The typed PIN API rejects invalid input with `IllegalArgumentException`; generic invalid commands
report through `ErrorCallback`. Authentication sends a standard 20-byte `AA 55` frame, ASCII PIN at
offsets 2..5, opcode `44` at offset 16, and trailer `14 5A 5A`. A `46` notification with byte 2
equal to 1 means authentication is required; 0 confirms authentication. A repeated 1 after submitting
a PIN means only that authentication is still required, **not definitive proof of an invalid PIN**.
No confirmation/timeout result is inferred from a successful BLE write.

Lock/unlock sends opcode `5D`, byte 2 = 1/0, with the same framing. Lock has a zeroed
remaining payload. Unlock additionally requires six ASCII token digits at bytes 10–15,
derived from the latest `5F` challenge (ASCII digits at bytes 4, 6, and 8) and three random
digits. The S18 `kslockbtsnoop.hex` capture contains this exchange; the encoder follows
the [ESPHome KingSong codec](https://github.com/lufton/esphome-kingsong-euc/blob/main/components/kingsong_euc/codec.cpp).
Wait for a challenge-bearing reply to `requestLockState()` before calling `unlock()`;
without a valid challenge, unlock returns an empty payload and is not sent. A bare lock
acknowledgement has no challenge. Challenge data is cleared on a new lock request,
disconnect/reconnect, or protocol reset.
**Unlock is experimental**, not verified across wheel models/firmware; do not use it as a safety
guarantee. When the wheel reports authentication required, lock/unlock is rejected (including raw
KingSong `5D` commands), also rechecked before queued submission. `UNKNOWN` does not imply that
authentication is unnecessary. There is no automatic authentication, locking, or unlocking, and a
blocked request must be explicitly submitted again after authentication.

Query opcode `5E` requests lock status; only `5F` notifications with byte 2 = 1 (locked) or 0
(unlocked) confirm it. Requests and transport acceptance never update the confirmed lock state.
The existing delayed startup `5E` query is retained. Unknown status values are ignored.
These wire contracts have capture-based and synthetic regression coverage; the corrected unlock
has not been tested on live hardware, and compatibility across models/firmware remains unverified.

The library does not persist PINs, include them in normal logs/state, or replay them on reconnect.
Transient PIN bytes necessarily exist in the BLE write queue/platform; ordinary busy-stack
submission retries still apply. **Deliberate raw `trafficFlow` capture includes the plaintext PIN
in outgoing `44` frames**. Disable recording during authentication or redact offsets 2..5 before
storing/sharing traces, and never log the entered PIN. Android/platform buffers and caller Strings
cannot be reliably erased by this library.

## Raw BLE traffic capture

`client.trafficFlow: SharedFlow<TrafficEvent>` exposes both received notification chunks
(`TrafficDirection.RX`) and outgoing writes accepted by Android (`TrafficDirection.TX`).
`TrafficEvent` and `TrafficDirection` are in `io.github.tritbool.euc.ble.core`.
Each event carries `sequence`, `timestampMs` (Unix epoch milliseconds), `characteristicUuid`,
and `bytes`. TX means **submitted to Android**, not that the wheel received or executed the
command. Rejected submissions are not emitted; an accepted retry is emitted once. This covers
user commands, automatic polling, and protocol-generated replies/follow-up writes.

There is one underlying shared capture stream. `client.rawFrameFlow` remains **RX-only**,
as a filtered view of that stream, without a second buffer or payload copy. Its public type
changes from `SharedFlow<ByteArray>` to `Flow<ByteArray>`: normal `.collect` usage is unchanged,
but code relying on the old type or `replayCache` must migrate and binary consumers must recompile.
Both views share defensive byte snapshots: **treat bytes as read-only**, copying before mutation.
Transport chunk boundaries are preserved; events are not necessarily complete protocol frames.

### TuxWheel migration

- Replace the raw-log recorder's `rawFrameFlow` subscription with `trafficFlow`.
- Ensure collection is active **before connecting**, to include initialization exchanges.
  There is no replay/history, and events with no subscribers are not retained.
- Save sequence, timestamp, direction, characteristic UUID, and bytes in a single ordered log.
  Use background, buffered file I/O and remove any separate command logging to avoid duplicates.
- Keep decoding/replay input RX-only; TX entries describe the conversation, not telemetry.
- The library performs no file I/O. Capture is bounded to 256 pending events and drops oldest
  events for slow collectors rather than suspending BLE callbacks. Recording is therefore
  best-effort, not guaranteed lossless. Consecutive sequence gaps reveal missed events in
  `trafficFlow`; an RX-only view naturally skips TX sequence numbers.
- Sequence starts at 1 for each client/manager instance, spans both directions, and continues
  across reconnects and periods without subscribers. It records library observation order, not
  over-the-air timing. Wall-clock timestamps can move backwards; use sequence for ordering.

## BMS data

Wheels that expose a smart BMS publish their pack details through the active protocol:

```kotlin
// Poll as often as needed (UI refresh, logging, diagnostics, ...)
val packs: List<BMSData>? = client.getBMSData()
```

Each `BMSData` entry carries pack voltage/current, remaining and factory capacity, cycles,
temperature probes, cell voltages and the pack charging status (`isCharging`).
`null` is returned when the connected wheel does not expose BMS data.

## Testing

Coverage reports for protocol/model/frame code are generated in CI and published in each
[CI run summary](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml).
Coverage is a testing aid, not a measure of device or firmware compatibility.

### Real-device capture methodology

Protocol tests combine offline unit tests with **raw BLE captures recorded from real wheels**,
replayed via JUnit 5 without an Android device or emulator. Capture coverage is partial and does
not mean every supported model, firmware, command, or edge case has been verified on a physical wheel.

Captures are stored in `src/test/resources/ble_frames/<brand>/RAW_WHEELLOG/` as CSV files exported from
[WheelLog](https://github.com/Wheellog/Wheellog.Android), a well-established EUC logging app.
Each CSV row contains the exact byte sequence received from the wheel over BLE.

Captures help to:

- Decoders are validated against **authentic protocol behaviour**, not assumptions
- Edge cases found in the wild (fragmented frames, garbage between headers, firmware variants) are
  permanently encoded as regression tests
- Preserve observed device behavior as regression fixtures; parser support and review are still needed for new variants

### What is tested

| Test class | What it validates |
|---|---|
| `WheelLogGotwayTest` | Full decode pipeline on real Gotway/Begode and ExtremeBull captures |
| `WheelLogKingsongTest` | Full decode pipeline on real KingSong captures |
| `WheelLogLeaperkimTest` | Full decode pipeline on real Leaperkim/Veteran captures |
| `WheelLogInMotionTest` | Full decode pipeline on real InMotion captures |
| `WheelLogNinebotTest` | Full decode pipeline on real Ninebot captures |
| `WheelLogNosfetTest` | Full decode pipeline on real Nosfet captures |
| `GotwayProtocolTest`, `KingsongProtocolTest`, … | Protocol-specific parsing, commands, and edge cases |
| `ExtremeBullProtocolTest` | ExtremeBull advertisement matching and inherited Gotway BLE requirements |
| `ProtocolParityContractTest` | Selected cross-protocol command and polling contracts |
| `BleFrequencyAnalysisTest` | Emission rate and timing characteristics per brand |

See [`euc-ble-core/README_TESTS.md`](euc-ble-core/README_TESTS.md) for the full test inventory.

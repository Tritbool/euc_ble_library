---
layout: default
title: Client guide
---

# Client guide

Details behind the quick start in the [README](../README.md). Everything here is client-facing
usage of `io.github.tritbool:euc-ble-library`.

## Client application

[TuxWheel](https://github.com/Tritbool/TuxWheel) is the first client app built with this library.
It is available as a pre-release, with publication on F-Droid planned.

The [command contract](commands/README.md) exposes names, input types and permitted values
through `getCommandDescriptors()`, including model/dialect variations. Use the checked
`encodeCommand(id, value)` API (Android: `sendCommand(id, value)`) rather than duplicating
protocol values in clients. Its pure-data dictionary is separate from client migration and
maintainer actions. Encoding bounds are not a guarantee of safe hardware limits.


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

For InMotion V9/P6, lock and unlock are supported after the wheel confirms the automatic
control-token handshake. Requests made before confirmation are rejected through `ErrorCallback`;
submit them again after `securityStateFlow` reports `AUTHENTICATED`. The wheel supplies the token
itself, so this handshake is not protection against another BLE client. InMotion lock-state
queries are supported only after identification as **V9** (`getCommandSupport(REQUEST_LOCK_STATE)`).
Call `requestLockState()` after identification and after lock/unlock requests; collect
`securityStateFlow` or `WheelSecurityEvent.LockStatus` on `securityEventFlow` for wheel-reported
confirmation. Neither writing a command nor its acknowledgment confirms the lock state.
The query uses the captured main-MCU detailed-data request `02 21 04`; the V9's 86-byte
`21 02 84` response body reports `03` (locked) or `20` (unlocked) at offset 74, verified
against two lock/unlock cycles in `v9_lock.log`. Other values/layouts are ignored, and
P6/other InMotion models do not advertise lock-state querying. State starts as `UNKNOWN`
and resets on disconnect. Live behavior through the library remains unverified.
Diagnostic request/cancel capabilities are likewise advertised only after identification
as V9/P6. Clients must recheck capabilities as model identification completes rather
than caching the initial, unidentified protocol's support matrix.
When using the Android connector in a Gradle composite build, both builds must use
the same Android Gradle plugin version. Set `-Peuc.androidGradlePluginVersion=<version>`
to match the consuming app; standalone library builds retain the default plugin version.


## KingSong PIN authentication, password management, and lock control

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

Password management is explicit and KingSong-only:

```kotlin
client.setPassword("0012")              // initial password, not authentication
client.changePassword("0012", "0034")   // old, new
client.removePassword("0034")          // current password
```

Each input must be exactly four ASCII digits; leading zeros are preserved. Generic equivalents
are `SET_PASSWORD` (String), `CHANGE_PASSWORD` (`protocols.PasswordChangeRequest(oldPassword,
newPassword)`), and `REMOVE_PASSWORD` (String). Invalid typed API inputs throw
`IllegalArgumentException`; invalid generic inputs produce no frame. The change request's
`toString()` is redacted; do not persist or log its password properties.

All use 20-byte `AA 55` frames with opcode at byte 16 and trailer `14 5A 5A`.
Set uses opcode `41`, new digits at bytes 2..5, and **zero bytes**, not ASCII `"0000"`, at 6..9.
Change uses `41`, new digits at 2..5 and old digits at 6..9. Remove uses `42`, current digits
at 2..5 and zeros in the remaining payload. A matching `43` notification with byte 6 = `01`
confirms new digits at 2..5 after set/change, or `FF FF FF FF` after removal.

Subscribe to `securityEventFlow` before submitting. `WheelSecurityEvent.PasswordOperationResult`
contains only `PasswordOperation.SET`/`CHANGE`/`REMOVE` and `PasswordOperationStatus.CONFIRMED`
or `TIMEOUT_UNCONFIRMED` (both enums in `models`). The 3000 ms timeout starts only after GATT
accepts the write, not when a command is encoded or queued. Missing replies **never prove a wrong
password**, and a timeout does not prove the wheel made no change. Authentication and lock state
are preserved. Only one password operation may be pending; overlapping writes are rejected by
the queue's protocol-state check. Password operations are never polled, automatically retried,
or replayed on reconnect. Disconnect, reset, or close cancels pending confirmation without a
result. Check support and wait for each result before submitting another operation.

KingSong replies have no request IDs: a delayed reply with the same expected target can be
indistinguishable from a later operation's reply. Confirmation is not a transport-level
transaction guarantee, and models/firmware may differ.
OS acceptance does not guarantee delivery: a later GATT callback failure can still end in
`TIMEOUT_UNCONFIRMED` when no matching wheel reply arrives.

Query opcode `5E` requests lock status; only `5F` notifications with byte 2 = 1 (locked) or 0
(unlocked) confirm it. Requests and transport acceptance never update the confirmed lock state.
The existing delayed startup `5E` query is retained. Unknown status values are ignored.
These wire contracts have capture-based and synthetic regression coverage; the corrected unlock
has not been tested on live hardware, and compatibility across models/firmware remains unverified.

The library does not persist PINs, include them in normal logs/state, or replay them on reconnect.
Transient PIN bytes necessarily exist in the BLE write queue/platform; ordinary busy-stack
submission retries still apply to authentication, but not password management. Pending password
correlation retains only expected confirmation bytes and clears them on completion/reset.
**Raw `trafficFlow` and protocol `rawFrameFlow` expose plaintext passwords**, including outgoing
`41`/`42`/`44` and incoming `43` frames. Disable recording during these operations or redact all
password fields (including old digits at 6..9 in `41`) before storing/sharing traces. Never log
entered passwords. Android/platform buffers and caller Strings cannot be reliably erased by
this library; no PIN persistence is provided.


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

### Begode / Gotway smart BMS

- Begode smart BMS wheels (e.g. ROCKET) expose **four cell groups** (the Begode app's G1..G4
  tabs), each reported through Type 1 frames: subpage byte 0..3 selects the group, carrying
  its own group voltage, current and two temperature probes.
- Cell voltages arrive on two streams: Type 2 frames carry groups 1 and 2, Type 3 frames
  carry groups 3 and 4. Pages of 8 cells cycle repeatedly (5 pages = 20 cells per group on
  the ROCKET, 4 pages = 16 on the EXTREME); a group's cells are the first/second half of its
  stream. `getBMSData()` returns one `BMSData` per group (`bmsIndex` 0..3).

### InMotion P6/V9 BMS

- The two packs are addressed independently (`0x24` and `0x25`) and exposed as
  `bmsIndex` 1 and 2, each with 56 (P6) or 20 (V9) cell voltages in volts.
- Summary, cell and identity replies update each pack independently. `getBMSData()`
  reads the latest snapshots; it does not initiate BLE requests.
  The protocol polls summaries and cells after P6/V9 identification and requests
  identity once per session. These use V2 command `0x02` with the pack address and
  subtype `0x01` (summary), `0x02` (cells) or `0x04` (identity).
- V2 UART characteristics are selected for automatic, manual and forced connections.
  If startup identification replies are lost, model requests continue until the
  wheel identifies itself; P6 pack polling then starts without reconnecting.
- Pack voltage, serial number, BMS firmware version and separate positive
  `chargeCurrent` / `dischargeCurrent` readings are available when reported.
  Net `current` uses the library convention: discharge minus charge, in amps.
  Charging is derived from that pack's current, not the wheel's charging state.
- V9 `temperatures` (°C) are, in order: charge MOS, discharge MOS, then the
  pack's four cell probes. P6 temperatures stay unset.
- Capacity, cycle count and individual status/error/warning bits remain unset
  until their mappings are independently verified. Whole-wheel
  current and temperatures are not substituted for pack measurements.


## InMotion diagnostic log

InMotion P6 and V9 wheels expose the diagnostic file (`2:/log.bin`, 512 KiB on the V9) that
the official app downloads. The library transfers the opaque binary; saving, sharing or
decoding it belongs to the client application.

```kotlin
client.requestDiagnosticLog() // or sendCommand(CommandType.REQUEST_DIAGNOSTIC_LOG)

val transfer = client.getDiagnosticLogTransfer() // poll as often as needed
when (transfer?.state) {
    DiagnosticLogState.COMPLETED -> save(transfer.copyData()!!)
    DiagnosticLogState.FAILED -> showError(transfer.failure)
    else -> showProgress(transfer?.progress) // 0.0..1.0, null until the size is known
}

client.cancelDiagnosticLog() // or CommandType.CANCEL_DIAGNOSTIC_LOG
```

- The command is only accepted once the wheel identified itself as a P6 or V9; otherwise it
  is skipped. A second request while a transfer is active is ignored.
- The download uses the wheel's main-MCU file service (open, 144-byte chunk reads, CRC-32
  verification, close), with one request in flight, per-request timeouts and up to 5
  retries. Expect roughly two minutes for 512 KiB.
- Background telemetry, BMS and control-auth polling pauses while the download is active,
  matching the official app, and resumes when it ends. Telemetry may remain unchanged
  during the transfer; this alone does not mean the BLE connection was lost.
- Data is only exposed in `COMPLETED`, after the wheel-reported CRC-32 matched. Timeouts,
  rejected opens, CRC mismatches, wheel-side aborts and disconnections (`SESSION_RESET`)
  end in `FAILED` without partial data.


### Nosfet / Veteran telemetry

- An explicit `NOSFET` token in the BLE name selects `NosfetProtocol` before automatic
  GATT fingerprint matching (case-insensitive). Forced selection still takes precedence.
  Model identification comes from firmware bytes 28–29 divided by 1000: 42 = Apex,
  43 = Aero, 44 = AEON. The name alone cannot distinguish those models.
- Automatic selection also recognizes those firmware IDs in complete Veteran-format
  telemetry on FFE1, including fragmented notifications, without relying on the BLE name.
  This can correct an automatic GATT match; forced and manual selections are preserved.
  Unknown serial-bridge devices stay connected while waiting for identification.
- Nosfet speed is a signed value in tenths of km/h: raw 390 means 39 km/h.
  Leaperkim speed scaling is unchanged.
- Trip and lifetime distances are unsigned metres encoded as big-endian 16-bit words,
  low word first, at offsets 8 and 12. Both are exposed in kilometres; do not sum successive
  `distance` values as if they were increments. For example, trip bytes `00 17 00 00`
  represent 23 metres, not 5.888 km.
- Correct AEON identification selects the 151-V-class voltage curve: 145.24 V estimates
  86%, not 100%. This remains a voltage-based estimate, not a BMS-reported state of charge.
- Detailed BMS pages arrive unsolicited. Poll `getBMSData()` after frames have arrived to
  obtain per-pack currents, temperatures and individual cell voltages. AEON/Apex have
  36 cells per pack; unused tail slots are excluded. Pack voltage, capacity and cycle
  counts remain `null` because this decoder has no verified mapping for them. Missing
  pages yield incomplete/empty snapshots, not fabricated readings; page 8 is not decoded.
- Statistics pages should consume `distance`, `totalDistance`, and available live telemetry
  regardless of brand. `rideTime` here is elapsed connection-session time, not moving time.
  Lifetime ride/power-on times and wheel-reported top speed are unavailable (`null`);
  client applications own session aggregates and statistics UI.

Wire semantics were cross-checked against EUC Planet's
[Veteran parser](https://github.com/Tritbool/eucplanet/blob/27dffb1f5210e8c0a5fb2e1dac3584c65f172923/app/src/main/java/com/eried/eucplanet/ble/VeteranParser.kt)
and model mappings. The included Nosfet recording is Aero firmware 043.2.54, not AEON.
Live AEON confirmation still needs the tester's RX capture, including firmware and BMS
page IDs; a screenshot cannot establish which detailed pages its firmware transmits.


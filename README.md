# EUC BLE Library

[![Tests](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml/badge.svg)](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml)
[![Coverage (protocols/models/frames)](https://img.shields.io/badge/coverage-protocols%2Fmodels%2Fframes%20in%20CI%20summary-blue)](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml)

> **Work in progress — version 0.1.0.** This library is experimental: protocol support is
> incomplete, some features may be broken or behave differently across wheel/firmware versions,
> and public APIs and behavior may evolve. Validate it with the specific wheel and firmware you
> intend to use; do not assume that passing unit tests guarantees safe or complete behavior.

## Usage (single entry point)

`io.github.tritbool.euc.ble.EucBleClient` is the public BLE entry point for client applications.
It registers built-in protocols internally, so client code must not register brand-specific handlers.

Scan lifecycle and connection events are exposed through `ConnectionCallback`.
Decoded telemetry is exposed through `DataCallback`.
Errors are exposed through `ErrorCallback`.

Callbacks are invoked from background contexts and are not guaranteed to run on the Android main thread.
Switch explicitly to the main thread before touching Android UI.

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

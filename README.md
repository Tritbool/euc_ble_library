# EUC BLE Library

[![Tests](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml/badge.svg)](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml)
[![Coverage (protocols/models/frames)](https://img.shields.io/badge/coverage-protocols%2Fmodels%2Fframes%20in%20CI%20summary-blue)](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml)

## Usage (single entry point)

`com.euc.ble.EucBleClient` is the public BLE entry point for client applications.
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

## Testing

**84%+ line coverage on protocol/model/frame code**, verified on every push.
Coverage details are published in each [CI run summary](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml).

### Real-device capture methodology

Most BLE protocol libraries test against hand-crafted byte arrays — frames that the author *believes* to be valid.
This library takes a different approach: protocols are tested against **raw BLE captures recorded from real wheels**,
replayed offline via JUnit 5 without any Android device or emulator.

Captures are stored in `src/test/resources/bleframes/<brand>/RAWWHEELLOG/` as CSV files exported from
[WheelLog](https://github.com/Wheellog/Wheellog.Android), a well-established EUC logging app.
Each CSV row contains the exact byte sequence received from the wheel over BLE.

This means:

- Decoders are validated against **authentic protocol behaviour**, not assumptions
- Edge cases found in the wild (fragmented frames, garbage between headers, firmware variants) are
  permanently encoded as regression tests
- Adding support for a new firmware version is as simple as dropping a new capture file in the right folder

### What is tested

| Test class | What it validates |
|---|---|
| `WheelLogGotwayTest` | Full decode pipeline on real Gotway/Begode BLE captures |
| `WheelLogKingsongTest` | Full decode pipeline on real KingSong captures |
| `WheelLogLeaperkimTest` | Full decode pipeline on real Leaperkim/Veteran captures |
| `WheelLogInMotionTest` | Full decode pipeline on real InMotion captures |
| `WheelLogNinebotTest` | Full decode pipeline on real Ninebot captures |
| `WheelLogNosfetTest` | Full decode pipeline on real Nosfet captures |
| `GotwayProtocolTest`, `KingsongProtocolTest`, … | Unit tests for edge cases, boundary values, frame fragmentation |
| `GotwayNoDropTest`, `KingsongNoDropTest`, … | No decoded frame is silently dropped between `decode()` and `dataFlow` |
| `ProtocolParityContractTest` | Cross-protocol consistency: same field semantics across all implementations |
| `BleFrequencyAnalysisTest` | Emission rate and timing characteristics per brand |

See [`euc-ble-core/README_TESTS.md`](euc-ble-core/README_TESTS.md) for the full test inventory.

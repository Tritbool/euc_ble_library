# EUC BLE Library

[![Tests](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml/badge.svg)](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml)
[![Coverage (protocols/models/frames)](https://img.shields.io/badge/coverage-protocols%2Fmodels%2Fframes%20in%20CI%20summary-blue)](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml)

## Usage (single entry point)

`com.euc.ble.EucBleClient` is the public BLE entry point for client applications.
It handles protocol registration internally, so client code must not register brand/protocol handlers.

```kotlin
val client = EucBleClient(context)

client.setConnectionCallback(object : ConnectionCallback() {
    override fun onDeviceDiscovered(device: EUCDevice) {
        client.connect(device)
    }
})

client.setDataCallback(object : DataCallback {
    override fun onDataReceived(data: EUCData) {
        // telemetry
    }
})

client.initialize()
client.startScan()
```

For commands:

```kotlin
client.sendCommand(CommandType.LIGHT_ON)
```

## Test coverage (protocols, models, frames)

Focused JaCoCo coverage for these packages is generated in CI using:

- `:euc-ble-core:jacocoFocusedReport`

Coverage values are published in each workflow run job summary under:

- **Coverage summary (protocols/models/frames)**

Report output paths:

- `euc-ble-core/build/reports/jacoco/focused/html/index.html`
- `euc-ble-core/build/reports/jacoco/focused/jacoco.xml`

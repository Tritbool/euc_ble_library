# BLE Protocol Parity Matrix

Status keys:
- ✅ legacy-equivalent
- ◑ partial
- ❌ missing

| Protocol | Telemetry completeness | Query/polling orchestration | Command parity | Legacy parity status | Known gaps |
| --- | --- | --- | --- | --- | --- |
| `KingsongProtocol` | ◑ core realtime + PWM + battery pages | ◑ framework supports orchestration, protocol has no dedicated polling plan yet | ◑ explicit support matrix for light/beep/power/pedals/LED | ◑ | Extended settings pages, fuller BMS metadata |
| `GotwayProtocol` | ◑ core realtime + Type A/B + smart-BMS cell pages | ◑ framework supports orchestration, protocol has no dedicated polling plan yet | ◑ explicit support matrix for light/beep/power/brightness | ◑ | Firmware/model bootstrap edge variants |
| `InMotionProtocol` | ◑ legacy+V2 realtime + total distance/model/serial parsing | ✅ startup + periodic query plan (`REQUEST_*`) | ◑ explicit support matrix + V2 query commands + controls | ◑ | Broader V2 settings/diagnostics coverage |
| `NinebotProtocol` | ◑ WheelLog + legacy frame support + serial/firmware carry-forward | ✅ startup + periodic query plan (`REQUEST_*`) | ◑ explicit support matrix + query commands | ◑ | Full Z-specific handshake/settings parity |
| `NinebotZProtocol` | ◑ decode delegated to `NinebotProtocol` | ✅ dedicated startup handshake + periodic keepalive/realtime/BMS polling | ◑ explicit support matrix incl. speed/alarm/calibrate/custom | ◑ | Full response-type granularity and settings roundtrip validation |
| `LeaperkimProtocol` | ◑ rich realtime + version/model mapping + smart-BMS extraction | ◑ framework supports orchestration, protocol has no dedicated polling plan yet | ◑ explicit support matrix for controls/custom | ◑ | Additional settings/control and BMS variant pages |
| `NosfetProtocol` | ◑ inherits Leaperkim realtime model with Nosfet version/battery mapping | ◑ framework supports orchestration, protocol has no dedicated polling plan yet | ◑ inherits Leaperkim command support matrix | ◑ | Nosfet-specific settings/control expansion |

## Cross-protocol framework parity

| Capability | Status | Notes |
| --- | --- | --- |
| Framework-side startup polling | ✅ | `BLEManager` executes startup query lists from protocol polling plans |
| Framework-side periodic polling | ✅ | `BLEManager` executes periodic queries with interval support |
| Retry/backoff | ✅ | Per-query retries and backoff in orchestration loop |
| Query/response observability | ✅ | `queryTraceFlow` + structured logger lines (`BLEQueryTrace`) |
| Explicit unsupported command API | ✅ | `supportedCommandTypes` + `getCommandSupport(...)` |
| Legacy scenario parity test scaffolding | ◑ | Added contract tests; more end-to-end legacy scenarios still needed |

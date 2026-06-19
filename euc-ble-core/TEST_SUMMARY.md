# Test suite – current status

> For the full test inventory and methodology, see [README_TESTS.md](README_TESTS.md).

## Coverage (protocols / models / frames)

Measured on every push via JaCoCo. Latest values are always in the
[CI run summary](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml).

| Metric | Value |
|---|---|
| Line coverage | 84%+ |
| Branch coverage | 56%+ |

## Test classes (summary)

| Category | Classes |
|---|---|
| WheelLog integration (real captures) | `WheelLogGotwayTest`, `WheelLogKingsongTest`, `WheelLogLeaperkimTest`, `WheelLogInMotionTest`, `WheelLogNinebotTest`, `WheelLogNosfetTest` |
| Protocol unit tests | `GotwayProtocolTest`, `KingsongProtocolTest`, `KingsongProtocolAsyncTest`, `InMotionProtocolTest`, `NinebotProtocolTest`, `NinebotZProtocolTest`, `LeaperkimProtocolTest`, `NosfetProtocolTest` |
| No-drop tests | `GotwayNoDropTest`, `KingsongNoDropTest`, `InmotionNoDropTest`, `LeaperkimNoDropTest`, `NinebotNoDropTest`, `NosfetNoDropTest` |
| Cross-protocol / utility | `ProtocolParityContractTest`, `BleFrequencyAnalysisTest`, `GotwayFrameReassemblerTest`, `EucBleClientEntryPointWheelLogTest`, `ByteUtilsTest`, `ByteUtilsSafeAccessTest`, `EUCDataTest` |

## What makes this test suite different

Protocol decoders are validated against **raw BLE captures from real wheels**, not hand-crafted
byte arrays. Captures are CSV files exported from WheelLog and stored in
`src/test/resources/bleframes/<brand>/RAWWHEELLOG/`. Real bytes mean real edge cases:
fragmented frames, firmware quirks, and out-of-spec sequences are all represented.

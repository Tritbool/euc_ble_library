# Test suite – current status

> For the full test inventory and methodology, see [README_TESTS.md](README_TESTS.md).

## Coverage (protocols / models / frames)

JaCoCo reports are generated in CI. See the
[latest CI run summary](https://github.com/Tritbool/euc_ble_library/actions/workflows/ci.yml)
for current measurements. Coverage is not a guarantee of protocol correctness or device support.

## Test classes (summary)

| Category | Classes |
|---|---|
| WheelLog integration (real captures) | `WheelLogGotwayTest`, `WheelLogKingsongTest`, `WheelLogLeaperkimTest`, `WheelLogInMotionTest`, `WheelLogNinebotTest`, `WheelLogNosfetTest` |
| Protocol unit tests | `GotwayProtocolTest`, `KingsongProtocolTest`, `KingsongProtocolAsyncTest`, `InMotionProtocolTest`, `NinebotProtocolTest`, `NinebotZProtocolTest`, `LeaperkimProtocolTest`, `NosfetProtocolTest`, `ExtremeBullProtocolTest` |
| No-drop tests | `GotwayNoDropTest`, `KingsongNoDropTest`, `InmotionNoDropTest`, `LeaperkimNoDropTest`, `NinebotNoDropTest`, `NosfetNoDropTest` |
| Cross-protocol / utility | `ProtocolParityContractTest`, `BleFrequencyAnalysisTest`, `GotwayFrameReassemblerTest`, `EucBleClientEntryPointWheelLogTest`, `ByteUtilsTest`, `ByteUtilsSafeAccessTest`, `EUCDataTest` |

## What makes this test suite different

The suite combines raw BLE captures from physical wheels with constructed unit-test cases.
Captures are CSV files exported from WheelLog and stored in
`src/test/resources/ble_frames/<brand>/RAW_WHEELLOG/`; capture and firmware coverage is partial.

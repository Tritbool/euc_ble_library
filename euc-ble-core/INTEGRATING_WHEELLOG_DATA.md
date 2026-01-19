# 🔧 Integrating WheelLog RAW Data into Test Suite

## Overview

This guide explains how to integrate real BLE frames from WheelLog RAW files into our test suite to enhance protocol validation and discover real-world edge cases.

## Current Status

✅ **Test infrastructure is ready** - We have:
- Test class structure (`RealWorldTestData.kt`)
- Resource directories for BLE frames
- Sample format files showing expected structure
- Integration with main test suite

❌ **Awaiting real data** - We need actual WheelLog RAW files to complete the integration

## How WheelLog Data Will Enhance Our Tests

### 1. **Protocol Validation**
- Verify our decoding matches real device behavior
- Catch protocol implementation errors
- Validate edge cases we might have missed

### 2. **Real-World Scenarios**
- Test with actual ride data sequences
- Validate data consistency across frames
- Check protocol behavior under real conditions

### 3. **Performance Testing**
- Measure decoding speed with real data volumes
- Test memory usage with large datasets
- Validate consistency under load

### 4. **Edge Case Discovery**
- Find unexpected packet formats
- Discover rare status flag combinations
- Identify device-specific quirks

## File Structure Prepared

```
euc-ble-core/
└── src/
    └── test/
        ├── java/
        │   └── com/euc/ble/protocols/
        │       └── RealWorldTestData.kt  # Test class for real data
        └── resources/
            └── ble_frames/
                ├── kingsong/
                │   ├── sample_format.csv  # Format example
                │   └── (your real files here)
                └── gotway/
                    ├── sample_format.csv  # Format example
                    └── (your real files here)
```

## What We Need From You

### 1. **Sample WheelLog RAW Files**
- A few CSV files from Kingsong wheels
- A few CSV files from Gotway/Begode wheels (if available)
- Files should contain real BLE frame data

### 2. **CSV Format Documentation**
- Column names and their meanings
- Data formats (hex, decimal, etc.)
- Any special encoding or headers

### 3. **Example Data Interpretation**
- What a "normal" frame looks like
- Any known edge cases or anomalies
- Expected value ranges

## How to Provide the Data

### Option 1: Direct File Copy
```bash
# Copy Kingsong files
cp /path/to/wheellog/kingsong_*.csv /home/tritbool/Workspace/euc-ble-library/euc-ble-core/src/test/resources/ble_frames/kingsong/

# Copy Gotway files  
cp /path/to/wheellog/gotway_*.csv /home/tritbool/Workspace/euc-ble-library/euc-ble-core/src/test/resources/ble_frames/gotway/
```

### Option 2: Share File Samples
- Share a few sample files via your preferred method
- I can integrate them into the test suite

### Option 3: Describe the Format
- If you can describe the CSV format, I can create a parser
- We can then add the files later

## Expected CSV Format (Based on Samples)

Our sample files show this expected format, but we'll adapt to the actual WheelLog format:

### Kingsong Format (sample)
```csv
# Comment header
# timestamp,raw_hex_data,voltage,speed,distance,current,temperature,battery,status
1712345678000,AA 55 64 01 2C 01 40 42 0F 00 E8 03 14 00 01 64 00 00 00 00,67.2,30.0,1000.0,100.0,20.0,100,1
```

### Gotway Format (sample)
```csv
# Comment header
# timestamp,raw_hex_data,voltage,speed,distance,current,temperature,battery,status,motor_temp
1712345678000,A5 5A 01 64 01 2C 01 40 42 0F 00 E8 03 14 00 64 01 1E 00,67.2,30.0,1000.0,100.0,20.0,100,1,30.0
```

## What the Tests Will Do

### 1. **Frame Decoding Validation**
```kotlin
// For each frame in CSV:
val decoded = protocol.decode(frame.bleData)
assertNotNull("Frame should decode", decoded)
assertEquals("Voltage should match", expectedVoltage, decoded?.voltage, 0.1)
assertEquals("Speed should match", expectedSpeed, decoded?.speed, 0.1)
// ... other validations
```

### 2. **Protocol Consistency**
```kotlin
// Across multiple frames:
val previousFrame = frames[i-1]
val currentFrame = frames[i]

// Validate smooth transitions
val speedChange = currentFrame.speed - previousFrame.speed
assertTrue("Speed change should be reasonable", abs(speedChange) < MAX_SPEED_CHANGE)

// Validate timestamp progression
assertTrue("Timestamps should increase", currentFrame.timestamp > previousFrame.timestamp)
```

### 3. **Edge Case Detection**
```kotlin
// Identify and catalog edge cases
if (frame.hasUnexpectedFormat()) {
    edgeCases.add("Unexpected format at ${frame.timestamp}")
}

if (frame.hasRareStatusFlags()) {
    edgeCases.add("Rare status flags at ${frame.timestamp}: ${frame.status}")
}
```

## Implementation Plan

### Step 1: Analyze CSV Format
- Examine the actual WheelLog CSV structure
- Document column meanings and data formats
- Identify any special encoding

### Step 2: Create Parser
- Implement `loadKingsongFramesFromCSV()`
- Implement `loadGotwayFramesFromCSV()`
- Handle format variations and edge cases

### Step 3: Implement Validation Tests
- Add frame-by-frame decoding validation
- Implement protocol consistency checks
- Add performance measurements

### Step 4: Run and Refine
- Execute tests with real data
- Identify and fix any issues
- Refine test parameters based on results

## Benefits of Real Data Integration

### For the Test Suite
- ✅ Higher confidence in protocol implementation
- ✅ Real-world edge case coverage
- ✅ Performance validation with real data volumes
- ✅ Regression prevention for real scenarios

### For Development
- ✅ Better understanding of real device behavior
- ✅ Early detection of protocol issues
- ✅ Data-driven test improvements
- ✅ Realistic performance benchmarks

## Next Steps

### If You Can Provide Sample Files:
1. **Share 2-3 sample CSV files** from Kingsong wheels
2. **Describe the CSV format** (columns, data types, etc.)
3. **Point out any interesting frames** (edge cases, anomalies)

### What I'll Do:
1. **Analyze the CSV format** and create appropriate parsers
2. **Integrate the files** into the test resources
3. **Implement comprehensive validation tests**
4. **Run tests and provide results**

## Example of What We're Looking For

```csv
# A few consecutive frames showing:
# - Normal riding conditions
# - Any edge cases (high speed, low battery, etc.)
# - Protocol variations if any
# - Timestamps that allow sequence validation

# Header line describing columns
timestamp,raw_hex_data,voltage,speed,distance,current,temperature,battery,status

# Some normal riding frames
1712345678000,AA 55 64 01 2C 01 40 42 0F 00 E8 03 14 00 01 64 00 00 00 00,67.2,30.0,1000.0,100.0,20.0,100,1
1712345678100,AA 55 50 01 28 01 41 42 0F 00 E0 03 15 00 01 63 00 00 00 00,66.8,28.0,1000.1,96.0,21.0,99,1
1712345678200,AA 55 4C 01 25 01 42 42 0F 00 D8 03 16 00 01 62 00 00 00 00,66.4,25.0,1000.2,92.0,22.0,98,1

# Maybe an edge case frame
1712345678300,AA 55 FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF,85.5,45.0,1000.3,120.0,45.0,80,3
```

## Contact Information

**How to reach me for file sharing:**
- Share files directly in this conversation
- Provide a download link
- Describe the format if files can't be shared directly

**What I need to know:**
- File format details
- Any known issues or edge cases in the data
- Device models the data comes from
- Any special conditions during recording

Let's get this real data integrated! The test suite is ready and waiting for authentic BLE frames to make it even more powerful and realistic. 🚀

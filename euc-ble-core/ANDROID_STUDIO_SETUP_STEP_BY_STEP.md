# 📱 Android Studio Setup - Step by Step Guide

## 🎯 Goal
Set up the EUC BLE Library project in Android Studio so you can run the tests.

## 🚀 Step 1: Create Proper Project Structure

### 1.1 Create a Parent Project (if needed)
Since this is a library module, let's create a parent project structure:

```bash
cd /home/tritbool/Workspace/euc-ble-library

# Create parent settings.gradle if it doesn't exist
cat > settings.gradle << 'EOF'
// Top-level build file where you can add configuration options common to all sub-projects/modules.
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "EUC BLE Library"
include ":euc-ble-core"
EOF

# Create parent build.gradle if it doesn't exist
cat > build.gradle << 'EOF'
// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    ext.kotlin_version = '1.9.0'
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.1.0'
        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
    }
}

task clean(type: Delete) {
    delete rootProject.buildDir
}
EOF
```

### 1.2 Update euc-ble-core settings
```bash
cd euc-ble-core
cat > settings.gradle << 'EOF'
// Module-level settings
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

rootProject.name = "euc-ble-core"
EOF
```

## 🚀 Step 2: Open Project in Android Studio

### 2.1 Launch Android Studio
- Open Android Studio
- If you have a project open, close it (File > Close Project)

### 2.2 Open the Parent Project
1. Click "Open an existing Android Studio project"
2. Navigate to: `/home/tritbool/Workspace/euc-ble-library`
3. Select the `euc-ble-library` directory (not euc-ble-core)
4. Click "Open"

### 2.3 Wait for Android Studio to Initialize
- Android Studio will:
  - Index files
  - Download dependencies
  - Set up Gradle
  - This may take a few minutes

## 🚀 Step 3: Set Up Android SDK (if prompted)

### 3.1 If Android Studio asks for SDK
1. Click "Install SDK" or "Configure"
2. Accept default settings
3. Let it download required components

### 3.2 If no prompt, check SDK manually
1. Click "File" > "Project Structure"
2. Under "SDK Location", verify Android SDK path
3. If empty, click "..." and browse to your Android SDK (usually `~/Android/Sdk`)

## 🚀 Step 4: Create Run Configuration

### 4.1 Create Test Configuration
1. Click "Run" > "Edit Configurations..."
2. Click "+" (Add New Configuration)
3. Select "Gradle"
4. Configure as follows:
   - Name: `Run All Tests`
   - Gradle project: `euc-ble-core`
   - Tasks: `test`
   - Working directory: `/home/tritbool/Workspace/euc-ble-library/euc-ble-core`
5. Click "OK"

### 4.2 Create Specific Test Configuration
1. Click "+" again
2. Select "JUnit"
3. Configure as follows:
   - Name: `Kingsong Tests`
   - Test kind: "Class"
   - Class: `com.euc.ble.protocols.WheelLogKingsongTest`
   - Module: `euc-ble-core`
4. Click "OK"

## 🚀 Step 5: Run Tests

### 5.1 Run All Tests
1. Select "Run All Tests" from the configuration dropdown
2. Click the green play button
3. Or press `Shift+F10`

### 5.2 Run Specific Test Class
1. In Project view, navigate to:
   `euc-ble-core/src/test/java/com/euc/ble/protocols/`
2. Right-click on `WheelLogKingsongTest.kt`
3. Select "Run 'WheelLogKingsongTest'"

### 5.3 Run Specific Test Method
1. Open `WheelLogKingsongTest.kt`
2. Find the test method (e.g., `testRealKingsongFramesDecoding`)
3. Click the green play button in the gutter (left side)
4. Or right-click and select "Run"

## 🚀 Step 6: Alternative - Use Terminal in Android Studio

### 6.1 Open Terminal in Android Studio
1. Click "View" > "Tool Windows" > "Terminal"
2. Or press `Alt+F12`

### 6.2 Run Tests from Terminal
```bash
cd euc-ble-core
./gradlew test

# Or run specific tests
./gradlew test --tests "com.euc.ble.protocols.WheelLogKingsongTest"
```

## 🚀 Step 7: Troubleshooting

### 7.1 If "No tests found"
1. Make sure you're in the `euc-ble-core` module
2. Check that test files are in `src/test/java`
3. Verify test classes have `@Test` annotations

### 7.2 If Gradle sync fails
1. Click "File" > "Sync Project with Gradle Files"
2. Check Android SDK path in "File" > "Project Structure"
3. Try "File" > "Invalidate Caches / Restart"

### 7.3 If Android plugin missing
1. Make sure you opened the parent project
2. Check that `euc-ble-core/build.gradle.kts` has the Android plugin
3. Verify Android Studio has internet access to download plugins

## 🎯 Expected Results

### Successful Test Run
```
> Task :euc-ble-core:test

com.euc.ble.core.ByteUtilsTest > testBytesToHex PASSED
com.euc.ble.core.ByteUtilsTest > testHexToBytes PASSED
... (many more tests)

com.euc.ble.protocols.WheelLogKingsongTest > testRealKingsongFramesDecoding PASSED
com.euc.ble.protocols.WheelLogKingsongTest > testRealKingsongFramesConsistency PASSED

BUILD SUCCESSFUL in 2m 15s
```

### Test Results View
1. After running, check the "Run" tool window
2. See green checkmarks for passed tests
3. Click on tests to see details
4. Expand test output to see logging

## 🚀 Step 8: Analyze Test Results

### 8.1 Check Test Output
- Look for success rates (>80% expected)
- Verify real data decoding works
- Check for edge cases discovered

### 8.2 Review Test Reports
1. After tests complete
2. Click on "Test Report" tab
3. See detailed breakdown by test class
4. Check timing and performance

## 🎉 Summary

### What You Should Have Now
- ✅ Proper project structure
- ✅ Android Studio configuration
- ✅ Run configurations set up
- ✅ Ready to execute tests

### Next Steps
1. **Open parent project** in Android Studio
2. **Let Android Studio sync** dependencies
3. **Run tests** using the methods above
4. **Analyze results** and validate implementations

### If Still Having Issues
1. Try "File" > "Invalidate Caches / Restart"
2. Check Android SDK is installed and configured
3. Verify project structure matches expectations
4. Try creating a new simple Android project first to verify setup

The tests are **ready to run** - Android Studio just needs the proper project structure and configuration! 🚀

plugins {
    id("com.android.library") version "9.0.0"
    //id("org.jetbrains.kotlin.android") version "2.3.0"
}

android {
    namespace = "com.euc.ble"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        // DEPR
        //targetSdk = 35

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            //jvmTarget = "17"
            //languageVersion = "2.0" // or KotlinVersion.KOTLIN_2_0
        }
    }


    testOptions {
        unitTests.all { testTask ->
            val isCi = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"

            testTask.useJUnitPlatform {
                if (isCi) {
                    excludeTags("slow") // NoDrop exclus en CI
                }
            }

            testTask.maxParallelForks =
                if (isCi) 1
                else (project.findProperty("maxParallelForks") as String?)?.toInt()
                    ?: Runtime.getRuntime().availableProcessors()

            testTask.jvmArgs("-Xmx1g")
        }
    }
}

dependencies {
    // Minimal dependencies for BLE functionality
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    
    // Test dependencies (single JUnit 5 line)
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-suite-api")
    testRuntimeOnly("org.junit.platform:junit-platform-suite-engine")

// Coroutines Test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") // dernière 1.10.x [web:6][web:9][web:15]

// Mockito Kotlin
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0") //
}

plugins {
    id("com.android.library") version "9.0.0"
    //id("org.jetbrains.kotlin.android") version "2.3.0"
}

android {
    namespace = "com.euc.ble"
    compileSdk = 35

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
            testTask.useJUnitPlatform()
            // Sérialisé en CI, parallèle en local
            testTask.maxParallelForks =
                (project.findProperty("maxParallelForks") as String?)?.toInt() ?:
                        Runtime.getRuntime().availableProcessors()
            testTask.jvmArgs("-Xmx1g")
        }
    }
}

dependencies {
    // Minimal dependencies for BLE functionality
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    
    // Test dependencies
// JUnit 4 (reste inchangé, 4.13.2 est déjà la dernière)
    testImplementation("junit:junit:4.13.2") // latest stable [web:17][web:22]

// JUnit Jupiter (JUnit 5) – API + Engine
    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")    // latest stable 6.0.x [web:21][web:23]
    testImplementation("org.junit.jupiter:junit-jupiter-engine:6.0.3") // aligné sur l'API 6.0.1 [web:21][web:29]

// JUnit Vintage Engine - permet d'exécuter les tests JUnit 4 sur JUnit Platform
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:6.0.3")

// JUnit Platform Launcher
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3") // dernière version GA 6.0.1 [web:2][web:8]

// Coroutines Test
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2") // dernière 1.10.x [web:6][web:9][web:15]

// Mockito Kotlin
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0") //
}
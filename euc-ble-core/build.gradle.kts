import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.library") version "9.1.0"
    id("jacoco")
}

jacoco {
    toolVersion = "0.8.12"
}

val jacocoClassExclusions = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    "**/*Test*.*"
)

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
                    //excludeTags("slow") // NoDrop exclus en CI
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

tasks.withType<Test>().configureEach {
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generates JaCoCo coverage report for all debug unit tests."
    dependsOn("testDebugUnitTest")

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/full/jacoco.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/full/html"))
    }

    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/kotlin/debug/classes")) {
            exclude(jacocoClassExclusions)
        },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
            exclude(jacocoClassExclusions)
        }
    )

    sourceDirectories.setFrom(
        files("src/main/java", "src/main/kotlin")
    )

    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "jacoco/testDebugUnitTest.exec.ec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec.ec"
            )
        }
    )
}

tasks.register<JacocoReport>("jacocoFocusedReport") {
    group = "verification"
    description = "Generates JaCoCo coverage report focused on protocols, models, and frames."
    dependsOn("testDebugUnitTest")

    val focusedPackages = listOf(
        "com/euc/ble/protocols/**",
        "com/euc/ble/models/**",
        "com/euc/ble/frames/**"
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/focused/jacoco.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/focused/html"))
    }

    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/kotlin/debug/classes")) {
            include(focusedPackages)
            exclude(jacocoClassExclusions)
        },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/compileDebugJavaWithJavac/classes")) {
            include(focusedPackages)
            exclude(jacocoClassExclusions)
        }
    )

    sourceDirectories.setFrom(
        files("src/main/java", "src/main/kotlin")
    )

    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "jacoco/testDebugUnitTest.exec",
                "jacoco/testDebugUnitTest.exec.ec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec.ec"
            )
        }
    )
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-suite-api")
    testRuntimeOnly("org.junit.platform:junit-platform-suite-engine")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
}

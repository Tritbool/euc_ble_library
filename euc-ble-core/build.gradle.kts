import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.android.library") version "9.1.0"
    id("jacoco")
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.36.0"
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
    namespace = "io.github.tritbool.euc.ble"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        // DEPR
        //targetSdk = 35

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude(jacocoClassExclusions)
        }
    )

    sourceDirectories.setFrom(
        files("src/main/java", "src/main/kotlin")
    )

    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include(
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
            )
        }
    )
}

tasks.register<JacocoReport>("jacocoFocusedReport") {
    group = "verification"
    description = "Generates JaCoCo coverage report focused on protocols, models, and frames."
    dependsOn("testDebugUnitTest")

    val focusedPackages = listOf(
        "io/github/tritbool/euc/ble/protocols/**",
        "io/github/tritbool/euc/ble/models/**",
        "io/github/tritbool/euc/ble/frames/**"
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/focused/jacoco.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/focused/html"))
    }

    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
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
                "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"
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

dokka {
    moduleName.set("EUC BLE Library")

    dokkaSourceSets.configureEach {
        //includes.from("README.md") // optionnel

        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/Tritbool/euc_ble_library/tree/main/euc-ble-core/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }

    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
}


mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            // quel type de javadoc.jar publier
            // - JavadocJar.None()
            // - JavadocJar.Empty()
            // - JavadocJar.Javadoc()
            // - JavadocJar.Dokka("nomDeTaTâcheDokka")
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            // sources.jar :
            // - SourcesJar.None()
            // - SourcesJar.Empty()
            // - SourcesJar.Sources()
            sourcesJar = SourcesJar.Sources(),
            // le variant Android publié
            variant = "release",
        )
    )

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }

    coordinates("io.github.tritbool", "euc-ble-library", "0.0.2")

    pom {
        name.set("EUC BLE Library")
        description.set("Android BLE library for Electric Unicycles (KingSong, Begode/Gotway, InMotion, Ninebot, Leaperkim, Nosfet).")
        url.set("https://github.com/Tritbool/euc_ble_library")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("Tritbool")
                name.set("Gauthier Le Bartz Lyan")
                url.set("https://github.com/Tritbool")
            }
        }
        scm {
            url.set("https://github.com/Tritbool/euc_ble_library")
            connection.set("scm:git:git://github.com/Tritbool/euc_ble_library.git")
            developerConnection.set("scm:git:ssh://git@github.com:Tritbool/euc_ble_library.git")
        }
    }
}



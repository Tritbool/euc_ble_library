import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("com.android.library")
    id("jacoco")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

dependencyLocking {
    lockAllConfigurations()
}

sonar {
  properties {
    property("sonar.coverage.jacoco.xmlReportPaths",
        layout.buildDirectory.file("reports/jacoco/full/jacoco.xml").get().asFile.absolutePath)
    property("sonar.androidLint.reportPaths",
        layout.buildDirectory.file("reports/lint-results-debug.xml").get().asFile.absolutePath)
  }
}

jacoco {
    toolVersion = "0.8.14"
}

val mockitoAgent = configurations.create("mockitoAgent") {
    isTransitive = false
}

abstract class MockitoAgentArgumentProvider : CommandLineArgumentProvider {
    @get:Classpath
    abstract val agentClasspath: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> =
        listOf("-javaagent:${agentClasspath.singleFile.absolutePath}")
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

    testCoverage {
        jacocoVersion = "0.8.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    kotlin {
        compilerOptions {
            optIn.add("io.github.tritbool.euc.ble.core.InternalEucBleApi")
            //jvmTarget = "17"
            //languageVersion = "2.0" // or KotlinVersion.KOTLIN_2_0
        }
    }


    sourceSets {
        // Replay fixtures (WheelLog CSV / btsnoop captures) live with the pure core tests.
        getByName("test").resources.directories.add("../euc-ble-core/src/test/resources")
        // AGP's built-in Kotlin compiler does not compile Kotlin from custom Java source directories.
        getByName("test").kotlin.directories.add("../euc-ble-core/src/testFixtures/kotlin")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
            testTask.jvmArgumentProviders.add(
                objects.newInstance<MockitoAgentArgumentProvider>().apply {
                    agentClasspath.from(mockitoAgent)
                }
            )
        }
    }

    lint {
        xmlReport = true
        htmlReport = true
        abortOnError = true
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "Generates JaCoCo coverage report for the Android connector unit tests."
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


dependencies {
    api(project(":euc-ble-core"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-suite-api")
    testRuntimeOnly("org.junit.platform:junit-platform-suite-engine")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    mockitoAgent("org.mockito:mockito-core:5.23.0")

}

dokka {
    moduleName.set("euc-ble-android")

    dokkaSourceSets.configureEach {
        //includes.from("README.md") // optionnel

        sourceLink {
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/Tritbool/euc_ble_library/tree/main/euc-ble-android/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
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

    coordinates("io.github.tritbool", "euc-ble-library", "1.0.0")

    pom {
        name.set("EUC BLE Library")
        description.set("Android BLE connector and client for Electric Unicycles (KingSong, Begode/Gotway, InMotion, Ninebot, Leaperkim, Nosfet).")
        url.set("https://github.com/Tritbool/euc_ble_library")
        licenses {
            license {
                name.set("GNU General Public License v3.0")
                url.set("https://www.gnu.org/licenses/gpl-3.0.html")
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

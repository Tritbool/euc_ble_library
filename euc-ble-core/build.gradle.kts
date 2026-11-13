import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    jacoco
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

dependencyLocking {
    lockAllConfigurations()
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    js(IR) {
        outputModuleName.set("euc-ble-core")
        nodejs()
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        all {
            languageSettings.optIn("io.github.tritbool.euc.ble.core.InternalEucBleApi")
        }
        commonMain.dependencies {
            api(project(":euc-ble-protocols"))
            api(project(":euc-ble-session"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("co.touchlab:stately-concurrency:2.1.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        jvmTest {
            kotlin.srcDirs("src/test/kotlin", "src/testFixtures/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation(project.dependencies.platform("org.junit:junit-bom:5.10.2"))
                implementation("org.junit.jupiter:junit-jupiter")
                implementation("org.junit.platform:junit-platform-suite-api")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
                runtimeOnly("org.junit.platform:junit-platform-suite-engine")
                implementation("app.cash.turbine:turbine:1.1.0")
            }
        }
    }
}

jacoco {
    toolVersion = "0.8.14"
}

// Keep the existing CLI entry point and report paths for JVM consumers and quality reporting.
val test by tasks.registering(Test::class) {
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("test")
    testClassesDirs = files(compilation.output.classesDirs)
    classpath = files(compilation.output.allOutputs, configurations.getByName("jvmTestRuntimeClasspath"))
    dependsOn(compilation.compileTaskProvider)
}

tasks.withType<Test>().configureEach {
    systemProperty("replay.captureRoot", file("src/test/resources/ble_frames"))
    systemProperty("replay.goldenRoot", rootProject.file("euc-ble-protocols/fixtures/captures"))
    systemProperty("replay.updateGoldens", providers.gradleProperty("updateReplayGoldens").isPresent)
    if (!providers.gradleProperty("updateReplayGoldens").isPresent) {
        inputs.dir(rootProject.file("euc-ble-protocols/fixtures/captures"))
    }
    useJUnitPlatform()
    val isCi = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
    maxParallelForks =
        if (isCi) 1
        else (project.findProperty("maxParallelForks") as String?)?.toInt()
            ?: Runtime.getRuntime().availableProcessors()
    jvmArgs("-Xmx1g")
}

val focusedPackages = listOf(
    "io/github/tritbool/euc/ble/protocols/**",
    "io/github/tritbool/euc/ble/models/**",
    "io/github/tritbool/euc/ble/frames/**"
)

val protocolClasses = project(":euc-ble-protocols").layout.buildDirectory.dir("classes/kotlin/jvm/main")
val protocolSources = project(":euc-ble-protocols").file("src/commonMain/kotlin")

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn(test)
    executionData(test.get())
    classDirectories.from(layout.buildDirectory.dir("classes/kotlin/jvm/main"))
    sourceDirectories.from("src/commonMain/kotlin")
    additionalClassDirs.from(protocolClasses)
    additionalSourceDirs.from(protocolSources)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/full/jacoco.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/full/html"))
    }
}

tasks.register<JacocoReport>("jacocoFocusedReport") {
    group = "verification"
    description = "Generates JaCoCo coverage report focused on protocols, models, and frames."
    dependsOn(test)
    executionData(test.get())
    sourceDirectories.from("src/commonMain/kotlin")
    classDirectories.from(fileTree(layout.buildDirectory.dir("classes/kotlin/jvm/main")) {
        include(focusedPackages)
    })
    additionalClassDirs.from(fileTree(protocolClasses) { include(focusedPackages) })
    additionalSourceDirs.from(protocolSources)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/focused/jacoco.xml"))
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/focused/html"))
    }
}

sonar {
    properties {
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            layout.buildDirectory.file("reports/jacoco/full/jacoco.xml").get().asFile.absolutePath
        )
    }
}

dokka {
    moduleName.set("euc-ble-core")
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src/commonMain/kotlin"))
            remoteUrl("https://github.com/Tritbool/euc_ble_library/tree/main/euc-ble-core/src/commonMain/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
        )
    )

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }

    coordinates("io.github.tritbool", "euc-ble-core", "0.1.3")

    pom {
        name.set("EUC BLE Core")
        description.set("Platform-independent protocol core for Electric Unicycles (KingSong, Begode/Gotway, InMotion, Ninebot, Leaperkim, Nosfet).")
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

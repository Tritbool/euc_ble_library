import com.vanniktech.maven.publish.KotlinMultiplatform
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

dependencyLocking {
    lockAllConfigurations()
}

dokka {
    moduleName.set("euc-ble-protocols")
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src/commonMain/kotlin"))
            remoteUrl("https://github.com/Tritbool/euc_ble_library/tree/main/euc-ble-protocols/src/commonMain/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

val generateReplayFixtures by tasks.registering {
    val commandFile = layout.projectDirectory.file("fixtures/commands.tsv")
    val telemetryFile = layout.projectDirectory.file("fixtures/telemetry.tsv")
    val output = layout.buildDirectory.dir("generated/replay")
    inputs.files(commandFile, telemetryFile)
    outputs.dir(output)
    doLast {
        fun literals(file: java.io.File): String = file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString(",\n") {
            "    \"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t").replace("$", "\\$") + "\""
        }
        output.get().file("ReplayFixtures.kt").asFile.apply {
            parentFile.mkdirs()
            writeText(
                "package io.github.tritbool.euc.ble.replay\n\n" +
                    "internal val commandFixtures = listOf(\n${literals(commandFile.asFile)}\n)\n\n" +
                    "internal val telemetryFixtures = listOf(\n${literals(telemetryFile.asFile)}\n)\n"
            )
        }
    }
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    js(IR) {
        outputModuleName.set("euc-ble-protocols")
        nodejs()
    }
    sourceSets {
        all {
            languageSettings.optIn("io.github.tritbool.euc.ble.core.InternalEucBleApi")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        commonTest {
            kotlin.srcDir(generateReplayFixtures)
        }
    }
}

val verifyPortableDependencies by tasks.registering {
    doLast {
        val modules = configurations.getByName("jvmRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
        check(modules.none { it.group == "org.jetbrains.kotlinx" && it.name.startsWith("kotlinx-coroutines") }) {
            "The synchronous protocol artifact must not depend on coroutines"
        }
    }
}
tasks.named("check") { dependsOn(verifyPortableDependencies) }

mavenPublishing {
    configure(KotlinMultiplatform())
    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) signAllPublications()
    coordinates("io.github.tritbool", "euc-ble-protocols", "0.1.3")
    pom {
        name.set("EUC BLE Protocols")
        description.set("Synchronous multiplatform EUC protocol engines and policies.")
        url.set("https://github.com/Tritbool/euc_ble_library")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        scm {
            url.set("https://github.com/Tritbool/euc_ble_library")
            connection.set("scm:git:git://github.com/Tritbool/euc_ble_library.git")
            developerConnection.set("scm:git:ssh://github.com/Tritbool/euc_ble_library.git")
        }
    }
}

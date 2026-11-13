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

val generateSessionFixtures by tasks.registering {
    val input = layout.projectDirectory.file("fixtures/connection.tsv")
    val output = layout.buildDirectory.dir("generated/fixtures")
    inputs.file(input)
    outputs.dir(output)
    doLast {
        val rows = input.asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val literals = rows.joinToString(",\n") {
            "    \"" + it.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t").replace("$", "\\$") + "\""
        }
        output.get().file("SessionFixtures.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package io.github.tritbool.euc.ble.session\n\ninternal val connectionFixtures = listOf(\n$literals\n)\n")
        }
    }
}

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    js(IR) {
        outputModuleName.set("euc-ble-session")
        nodejs()
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":euc-ble-protocols"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        commonTest {
            kotlin.srcDir(generateSessionFixtures)
        }
    }
}

val verifyPortableDependencies by tasks.registering {
    doLast {
        val modules = configurations.getByName("jvmRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id }
        check(modules.none { it.group == "org.jetbrains.kotlinx" && it.name.startsWith("kotlinx-coroutines") }) {
            "The synchronous session artifact must not depend on coroutines"
        }
    }
}
tasks.named("check") { dependsOn(verifyPortableDependencies) }

dokka {
    moduleName.set("euc-ble-session")
    dokkaSourceSets.configureEach {
        sourceLink {
            localDirectory.set(file("src/commonMain/kotlin"))
            remoteUrl("https://github.com/Tritbool/euc_ble_library/tree/main/euc-ble-session/src/commonMain/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

mavenPublishing {
    configure(KotlinMultiplatform())
    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) signAllPublications()
    coordinates("io.github.tritbool", "euc-ble-session", "0.1.3")
    pom {
        name.set("EUC BLE Session")
        description.set("Platform-neutral EUC BLE session policies and state machines.")
        url.set("https://github.com/Tritbool/euc_ble_library")
        licenses {
            license {
                name.set("GNU General Public License v3.0")
                url.set("https://www.gnu.org/licenses/gpl-3.0.html")
            }
        }
        scm {
            url.set("https://github.com/Tritbool/euc_ble_library")
            connection.set("scm:git:git://github.com/Tritbool/euc_ble_library.git")
            developerConnection.set("scm:git:ssh://git@github.com:Tritbool/euc_ble_library.git")
        }
    }
}

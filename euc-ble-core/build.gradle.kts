import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-test-fixtures`
    jacoco
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

dependencyLocking {
    lockAllConfigurations()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("io.github.tritbool.euc.ble.core.InternalEucBleApi")
    }
}

jacoco {
    toolVersion = "0.8.14"
}

dependencies {
    // Transitional: protocols still expose Flow-based views. Removed once every protocol
    // is a synchronous state machine (see ARCHITECTURE.md, "Sync core").
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testFixturesApi(platform("org.junit:junit-bom:5.10.2"))
    testFixturesApi("org.junit.jupiter:junit-jupiter-api")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.platform:junit-platform-suite-api")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.platform:junit-platform-suite-engine")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("app.cash.turbine:turbine:1.1.0")
}

tasks.test {
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

tasks.jacocoTestReport {
    dependsOn(tasks.test)
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
    dependsOn(tasks.test)
    executionData(tasks.test.get())
    sourceSets(sourceSets.main.get())
    classDirectories.setFrom(
        sourceSets.main.get().output.classesDirs.files.map { dir ->
            fileTree(dir) { include(focusedPackages) }
        }
    )
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
            localDirectory.set(file("src/main/kotlin"))
            remoteUrl("https://github.com/Tritbool/euc_ble_library/tree/main/euc-ble-core/src/main/kotlin")
            remoteLineSuffix.set("#L")
        }
    }
}

mavenPublishing {
    configure(
        KotlinJvm(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
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

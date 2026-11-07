// Plugins shared by several modules are declared once here so that every module
// uses the same plugin classloader (avoids "Kotlin Gradle plugin loaded multiple times").
// The Android Gradle Plugin is declared in :euc-ble-android only, so the pure core can
// be built without access to Google's Maven repository.
plugins {
    kotlin("jvm") version "2.2.10" apply false
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
    id("org.sonarqube") version "7.5.0.8588"
}

val includeAndroid = providers.gradleProperty("euc.includeAndroid").getOrElse("true").toBoolean()

dependencies {
    dokka(project(":euc-ble-core"))
    if (includeAndroid) {
        dokka(project(":euc-ble-android"))
    }
}

dokka {
    moduleName.set("EUC BLE Library")
    dokkaPublications.html {
        outputDirectory.set(layout.buildDirectory.dir("dokka/html"))
    }
}

sonar {
    properties {
        property("sonar.projectKey", "tritbool-github_euc-ble-library")
        property("sonar.organization", "tritbool-github")
        property("sonar.host.url", "https://sonarcloud.io")
    }
}

// Plugins shared by several modules are declared once here so that every module
// uses the same plugin classloader (avoids "Kotlin Gradle plugin loaded multiple times").
// KGP, Dokka, vanniktech and Sonar integrate with AGP, so AGP must live in this root
// classloader too. It is only added when Android is included, so the pure core can be
// built without access to Google's Maven repository (-Peuc.includeAndroid=false).
buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.testing.*")
            }
        }
    }
    dependencies {
        if ((findProperty("euc.includeAndroid") as String?)?.toBoolean() != false) {
            classpath("com.android.tools.build:gradle:9.1.0")
        }
    }
}

plugins {
    kotlin("jvm") version "2.2.10" apply false
    kotlin("multiplatform") version "2.2.10" apply false
    id("org.jetbrains.dokka") version "2.2.0"
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
    id("org.sonarqube") version "7.5.0.8588"
}

allprojects {
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
        extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec> {
            download.set(false)
        }
    }
}

val includeAndroid = providers.gradleProperty("euc.includeAndroid").getOrElse("true").toBoolean()

dependencies {
    dokka(project(":euc-ble-protocols"))
    dokka(project(":euc-ble-session"))
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

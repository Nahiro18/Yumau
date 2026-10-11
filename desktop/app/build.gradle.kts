import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Compose Multiplatform (desktop) plugin: provides the `compose.desktop.*`
    // dependency accessors and the `compose.desktop.application` DSL.
    id("org.jetbrains.compose") version "1.10.1"
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":desktop:runtime"))
    implementation(project(":desktop:shims"))
    implementation(project(":core"))
    implementation(project(":core:innertube"))
    implementation(project(":lyrics:lrclib"))
    implementation(project(":spotifycore"))
    implementation(project(":morideobfuscator"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.components.resources)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
}

compose.desktop {
    application {
        mainClass = "moe.rukamori.archivetune.desktop.app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "yumaplayer"
            packageVersion = "1.0.0"
            description = "YumaPlayer - Music Player for Linux"
            vendor = "Nahiro18"
            copyright = "© 2026 Nahiro18"

            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                debPackageVersion = "1.0.0"
                debMaintainer = "Nahiro18 <nahiro18@github.com>"
                appCategory = "sound"
                shortcut = true
                menuGroup = "Multimedia"
            }
        }
    }
}


import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation(project(":desktop:database"))
    implementation(project(":desktop:shims"))
    // Runtime-only: installs the JDBC-backed androidx.sqlite/Room provider via
    // the DesktopSqliteBridge hook in :desktop:shims.
    runtimeOnly(project(":desktop:sqlite-jdbc-factory"))
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
}

compose.desktop {
    application {
        mainClass = "moe.rukamori.archivetune.desktop.app.MainKt"
    }
}

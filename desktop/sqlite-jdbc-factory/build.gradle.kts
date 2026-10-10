import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    // Pure-JVM flavoured androidx.sqlite (no Android framework classes involved).
    api("androidx.sqlite:sqlite:2.6.2")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation(libs.kotlinx.coroutines.core)
}

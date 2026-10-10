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

    // Android API stand-ins (Context, Cursor, ContentValues, DesktopSqliteBridge hook).
    implementation(project(":desktop:shims"))

    // Room JVM runtime: the bridge delegates Room.databaseBuilder(...) calls to
    // Room.createApplicationBuilder(klass) { factory(...) }. The generated *Impl
    // database classes come from :desktop:database at runtime; we only need the
    // abstract runtime here.
    implementation("androidx.room:room-runtime:2.8.4")
}

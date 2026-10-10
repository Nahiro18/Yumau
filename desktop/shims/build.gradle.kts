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
    // The shims reference androidx.room.RoomDatabase and androidx.sqlite types in
    // their signatures (Room.kt, FrameworkSQLiteCompat.kt), so they must be on the
    // compile classpath. Consumers provide the same artifacts anyway.
    api("androidx.room:room-runtime-jvm:2.8.4")
    implementation(libs.kotlinx.coroutines.core)
}

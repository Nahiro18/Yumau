import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // The shared sources reference R-class-free code only; keep opt-ins permissive.
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

// Reuse the Android module's Kotlin sources verbatim (they are platform-neutral
// Room/DAO/entity code). Android-specific APIs are provided by :desktop:shims.
sourceSets {
    main {
        kotlin.srcDir(rootProject.file("database/src/main/kotlin"))
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":desktop:shims"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.apache.lang3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.compose.runtime)

    api(project(":core"))

    // Pure-JVM SQLite driver backing androidx.sqlite on desktop
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

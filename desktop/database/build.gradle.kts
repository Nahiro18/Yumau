import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.ksp)
    alias(libs.plugins.compose.compiler)
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
        kotlin.srcDir(rootProject.file("designsystem/src/main/kotlin"))
        kotlin.exclude(
            "moe/rukamori/archivetune/constants/PreferenceConstants.kt",
            "moe/rukamori/archivetune/constants/Dimensions.kt",
            "moe/rukamori/archivetune/ui/**",
            "moe/rukamori/archivetune/utils/**",
            "moe/rukamori/archivetune/LocalPlayerAwareWindowInsets.kt",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":desktop:shims"))
    // Bridge hook (DesktopSqliteBridge) lives in :desktop:shims; the JDBC-backed
    // androidx.sqlite factory implementation is contributed at runtime by
    // :desktop:sqlite-jdbc-factory (wired through :desktop:app).

    // ROOT CAUSE of the KSP "MissingType" failure: androidx.room:room-compiler
    // is a multiplatform module (android + jvm variants). On a pure-JVM project
    // Gradle can still select the *android* variant for room-common/room-runtime
    // via Gradle module metadata; those variants reference Android framework
    // types absent from this classpath, so KSP2's symbol solver cannot load the
    // androidx.room annotation classes and reports every @Dao element as
    // referencing a missing type.
    //
    // Fix: steer resolution to the -jvm variants by declaring the concrete
    // -jvm artifacts above and adding capability-based substitutions so any
    // transitive request for the abstract module id resolves to the JVM jar.
    api("androidx.room:room-runtime-jvm:2.8.4")
    api("androidx.room:room-common-jvm:2.8.4")
    api("androidx.room:room-migration-jvm:2.8.4")
    // room-ktx is an AAR-only artifact (Android); the JVM Room runtime already exposes coroutine suspend support.
    ksp("androidx.room:room-compiler:2.8.4")

    implementation(libs.apache.lang3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.compose.runtime)

    api(project(":core"))
    api(project(":core:innertube"))

    // Pure-JVM SQLite driver backing androidx.sqlite on desktop
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

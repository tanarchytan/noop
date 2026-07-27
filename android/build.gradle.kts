// Root build file — declares plugin versions once; applied per-module in app/build.gradle.kts.
// Toolchain contract: Android Gradle Plugin 9.x · Kotlin 2.3.x · KSP matched to Kotlin · Room 2.8.x.
//
// AGP 9 supplies Kotlin itself, so there is no `org.jetbrains.kotlin.android` plugin any more. AGP
// 9.3.1 bundles Kotlin 2.2.10; the classpath line below raises that to 2.3.10 so it matches KSP,
// which Room's compiler runs on. Only KSP2 (2.3.x) works with AGP built-in Kotlin — the older paired
// KSP1 builds refuse outright — and 2.3.10 is its newest, so that is the ceiling for the whole
// toolchain until KSP ships for Kotlin 2.4.x.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    // From Kotlin 2.0 the Compose compiler ships with Kotlin, so it takes the Kotlin version and
    // `composeOptions.kotlinCompilerExtensionVersion` no longer exists. Still a separate plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}

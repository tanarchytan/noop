// Root build file — declares plugin versions once; applied per-module in app/build.gradle.kts.
// Toolchain contract: Android Gradle Plugin 9.x · Kotlin 2.3.x · KSP matched to Kotlin · Room 2.8.x.
//
// AGP supplies Kotlin itself, so there is no `org.jetbrains.kotlin.android` plugin. The classpath
// below raises the bundled Kotlin to the version KSP is built for; only KSP2 works with AGP's
// built-in Kotlin, so KSP's newest release caps the Kotlin version for the whole project.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    // The Compose compiler ships inside Kotlin and takes its version; it stays a separate plugin.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}

import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Optional release signing. Credentials live in `keystore.properties` (git-ignored, never
// committed); when it's absent — clones, CI without secrets — release falls back to the debug
// key so `assembleRelease` always produces an installable APK. See docs/BUILD.md.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.noop"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.noop.tan"
        minSdk = 26
        targetSdk = 34
        versionCode = 292
        versionName = "9.0.1-rc2-tan"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // The Rust whoop-ffi .so ships for these two ABIs only (arm64 phones, x86_64 emulator).
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        getByName("debug") {
            val forkDebugKeystore = rootProject.file("fork-debug.keystore")
            if (forkDebugKeystore.exists()) {
                storeFile = forkDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Shipped UNMINIFIED for reliability. R8 minification crashes this app at runtime: full-mode
            // over-strips reflective paths, and even with full-mode OFF + broad keeps (com.noop.** +
            // Tink/Worker/ViewModel) a minified build STILL died right after the terms gate on a real
            // device — a library reflective path we couldn't pin without a device to trace. Offline app,
            // a ~18 MB APK is fine. Re-enabling minify needs the exact crash trace + device verification.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Real release key when keystore.properties is present; otherwise the debug key,
            // so a fresh clone can still build an installable release APK.
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            // Fork staging release: built with -PstagingRelease (the fork testing-build CI only), the
            // release APK gets its own id/name so it installs BESIDE both the official app and the
            // .debug staging build. A real release (no property) keeps the true com.noop.tan id.
            if (project.hasProperty("stagingRelease")) {
                applicationIdSuffix = ".staging"
                versionNameSuffix = "-staging"
            }
        }
    }

    // Two clearly-distinct apps that install side-by-side:
    //   • full → "NOOP"      (com.noop.tan)     — the real app, starts empty, pair a strap / import.
    //   • demo → "NOOP Demo"  (com.noop.tan.demo) — preloaded with 120 days of synthetic data and
    //                          a visible DEMO badge, so anyone can explore every screen with no strap.
    // Build e.g. ./gradlew assembleFullRelease assembleDemoRelease.
    flavorDimensions += "tier"
    productFlavors {
        create("full") {
            dimension = "tier"
            buildConfigField("String", "TIER", "\"full\"")
            buildConfigField("boolean", "ENABLE_DEMO", "false")
        }
        create("demo") {
            dimension = "tier"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("String", "TIER", "\"demo\"")
            buildConfigField("boolean", "ENABLE_DEMO", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        // Compose Compiler extension matched to Kotlin 1.9.24 (see the official
        // Compose-to-Kotlin compatibility map). Bumping Kotlin requires bumping this.
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        // Unmocked android.jar methods (e.g. android.util.Log reached via analyzeDay's logging) return
        // defaults instead of throwing, so the pure-logic JVM tests aren't blocked by the Android stub jar.
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // The RustCodec (FFI) parity test loads the host build of libwhoop_ffi via JNA. The desktop
            // library (whoop_ffi.dll on Windows) lives in the sibling whoop-rs checkout's release dir;
            // point jna.library.path at it. When absent (CI / no sibling), the parity test self-skips.
            it.systemProperty(
                "jna.library.path",
                (System.getenv("WHOOP_RS_DIR")?.let { d -> file(d) }
                    ?: rootProject.projectDir.resolve("../../whoop-rs"))
                    .resolve("target/release").absolutePath,
            )
            // Forward any -Dnoop.* fixture-path override from the gradle CLI into the forked test JVM,
            // so a local run with -Dnoop.hrvGoldFixtures=<dir> (or -Dnoop.rrFixture=<file>) actually
            // reaches the agreement/gold tests (Gradle does not propagate system properties to the
            // fork by default). With no override they fall back to a local default and self-skip when
            // the fixtures are absent (e.g. CI), keeping the build green.
            System.getProperties().stringPropertyNames()
                .filter { name -> name.startsWith("noop.") }
                .forEach { name -> it.systemProperty(name, System.getProperty(name)) }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- Compose (BOM pins all Compose artifact versions in lockstep) ---
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // --- Home-screen widget (1.1.x: last line compatible with compileSdk 34) ---
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Glance's own POM pins work-runtime 2.7.1 (Oct 2021) — pre-Android-14. Pin a current one
    // explicitly so the widget scheduler runs on a WorkManager that's maintained for targetSdk 34.
    // (2.10+ needs compileSdk 35; 2.9.x is the ceiling for this module.)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // --- Activity / lifecycle / navigation ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2") // collectAsStateWithLifecycle
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Rust whoop-ffi codec (the from-scratch whoop-rs core, via uniffi). JNA is its Kotlin runtime;
    //     the .so ships in jniLibs. No BLE crosses the FFI — native BLE feeds decoded bytes in. ---
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // --- Room (local-only persistence; on-device, nothing leaves the phone) ---
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // --- AI Coach (opt-in, bring-your-own-key). HTTP client + Keystore-backed key storage. ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // --- Health Connect (optional native Android import of steps/HR/HRV/sleep/etc.) ---
    // Pinned to alpha07: alpha11+ require compileSdk 35; this module is compileSdk 34.
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    // --- Unit / instrumentation tests ---
    testImplementation("junit:junit:4.13.2")
    // Plain JNA jar (not the @aar) so the desktop jnidispatch is on the JVM unit-test classpath — lets
    // the in-JVM RustCodec/FFI parity test load libwhoop_ffi on the host.
    testImplementation("net.java.dev.jna:jna:5.14.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests (android.jar ships throwing stubs)
    testImplementation("net.sf.kxml:kxml2:2.3.0") // real XmlPullParser for JVM tests (android.util.Xml is a throwing stub)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    // --- Compose tooling (debug-only) ---
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// --- Host cdylib for the JVM unit tests (see jna.library.path in testOptions) ---
// The decode-pipeline tests load the host build of libwhoop_ffi via JNA. Build it from the sibling
// whoop-rs checkout before unit tests run so they exercise the real Rust decode. Skips cleanly when
// the sibling checkout (or cargo) is absent, in which case the FFI test self-skips as before.
// CI sets WHOOP_RS_DIR to its whoop-rs checkout (the sibling ../../whoop-rs resolves above the runner workspace).
val whoopRsDir = System.getenv("WHOOP_RS_DIR")?.let { file(it) } ?: rootProject.projectDir.resolve("../../whoop-rs")
val buildRustHostDll = tasks.register<Exec>("buildRustHostDll") {
    workingDir = whoopRsDir
    commandLine("cargo", "build", "--release", "-p", "whoop-ffi")
    onlyIf { whoopRsDir.resolve("Cargo.toml").exists() }
}
tasks.withType<Test>().configureEach { dependsOn(buildRustHostDll) }

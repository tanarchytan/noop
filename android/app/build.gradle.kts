import java.util.Properties

plugins {
    // AGP 9 provides Kotlin itself — there is no kotlin.android plugin here any more.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
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
    compileSdk = 36

    defaultConfig {
        applicationId = "com.noop.tan"
        minSdk = 26
        targetSdk = 36
        versionCode = 298
        versionName = "10.0.0-dev-tan"

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

    // Three shipped things, and one that never leaves this machine:
    //   • release → the stable app.                    com.noop.tan
    //   • rc      → the same app, debuggable.          com.noop.tan   (replaces stable, KEEPS its data)
    //   • mock    → the flavor below, synthetic data.  com.noop.tan.mock
    //   • debug   → local dev + instrumentation only, never published, own id so it cannot
    //               overwrite the real app's data.     com.noop.tan.debug
    // `rc` deliberately shares the stable id AND the stable signing key, so installing a candidate
    // over a release is an ordinary update rather than a wipe-and-reinstall.
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
        }
        // Release candidate: byte-for-byte the release build plus debuggability. No id or version
        // suffix on purpose — it must land on top of an installed release and keep its data, which
        // needs the same applicationId and the same signing key. The versionName already says which
        // candidate this is (e.g. 9.0.1-rc3-tan).
        create("rc") {
            initWith(getByName("release"))
            isDebuggable = true
            matchingFallbacks += listOf("release")
        }
    }

    // Two clearly-distinct apps that install side-by-side:
    //   • full → "NOOP"      (com.noop.tan)      — the real app, starts empty, pair a strap / import.
    //   • mock → "NOOP Mock" (com.noop.tan.mock) — preloaded with 120 days of synthetic data, so every
    //                         screen can be explored with no strap. Its own id, so it can never write
    //                         mock data into the real app's database.
    // Build e.g. ./gradlew assembleFullRelease assembleMockRc.
    flavorDimensions += "tier"
    productFlavors {
        create("full") {
            dimension = "tier"
            buildConfigField("String", "TIER", "\"full\"")
            buildConfigField("boolean", "ENABLE_MOCK", "false")
        }
        create("mock") {
            dimension = "tier"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
            buildConfigField("String", "TIER", "\"mock\"")
            buildConfigField("boolean", "ENABLE_MOCK", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 replaced the `kotlinOptions` block with Kotlin's own, inside `android`.
    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // --- Home-screen widget ---
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // Glance's own POM pins work-runtime 2.7.1 (Oct 2021) — pre-Android-14. Pin a current one
    // explicitly so the widget scheduler runs on a WorkManager that's maintained for targetSdk 34.
    // (2.10+ needs compileSdk 35; 2.9.x is the ceiling for this module.)
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // --- Activity / lifecycle / navigation ---
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0") // collectAsStateWithLifecycle
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Rust whoop-ffi codec (the from-scratch whoop-rs core, via uniffi). JNA is its Kotlin runtime;
    //     the .so ships in jniLibs. No BLE crosses the FFI — native BLE feeds decoded bytes in. ---
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // --- Room (local-only persistence; on-device, nothing leaves the phone) ---
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // --- AI Coach (opt-in, bring-your-own-key). HTTP client + Keystore-backed key storage. ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // --- Health Connect (native read/write of steps/HR/HRV/sleep/skin temperature/etc.) ---
    implementation("androidx.health.connect:connect-client:1.1.0")

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

// --- The libraries that reach a PHONE, which the task above does not cover ---
// `buildRustHostDll` keeps the JVM tests honest, and cargo tracks its freshness. The `.so` under
// jniLibs/ are committed artifacts, so nothing rebuilt them: on 2026-07-31 they sat 17 hours and 13
// library source files behind crates/ while the suite passed green against a freshly built host
// library. Green tests, stale device, and no signal anywhere.
//
// Bindings and library must move TOGETHER. uniffi puts an API checksum in whoop_ffi.kt and the pair
// disagreeing fails at init — and the checksum covers DOCSTRINGS, so a comment-only edit to an
// exported item is enough to break it. That is far too subtle to leave to remembering a four-step.
//
// --ensure hashes crates/ and rebuilds only when the stamp disagrees, so the common case costs
// milliseconds. The onlyIf covers one case and one only: no sibling whoop-rs checkout at all, as on a
// CI runner building the app alone. A checkout that IS present with a missing cargo or cargo-ndk fails
// the build from inside the script, rather than quietly shipping whatever `.so` is lying around.
val syncRustJniLibs = tasks.register<Exec>("syncRustJniLibs") {
    workingDir = whoopRsDir
    commandLine("python", whoopRsDir.resolve("tools/sync-jnilibs.py").absolutePath, "--ensure")
    onlyIf { whoopRsDir.resolve("tools/sync-jnilibs.py").exists() }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(syncRustJniLibs) }

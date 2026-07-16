# Rust whoop-ffi integration (experimental)

The app can decode WHOOP frames with the from-scratch Rust codec from `whoop-rs`
(`crates/whoop-ffi`, a `uniffi` surface over the pure sans-IO codec), instead of the
Kotlin `com.noop.protocol` decoders. Bluetooth stays native Kotlin (`com.noop.ble`);
only frame bytes cross the FFI, nothing async or radio-bound.

## What is committed here

- `app/src/main/jniLibs/{arm64-v8a,x86_64}/libwhoop_ffi.so` — the prebuilt Rust core.
- `app/src/main/java/uniffi/whoop_ffi/whoop_ffi.kt` — the generated uniffi Kotlin binding.
- `app/src/main/java/com/noop/protocol/RustCodec.kt` — a thin bridge (`decodeHistory`).
- `build.gradle.kts` — the JNA runtime dep and an `arm64-v8a`/`x86_64` `abiFilters`.

## Regenerating (after a whoop-rs change)

From the `whoop-rs` checkout, with the Android NDK path set:

```bash
export ANDROID_NDK_HOME=".../Android/Sdk/ndk/<version>"
cargo ndk -t arm64-v8a -t x86_64 build --release -p whoop-ffi
cargo run -p whoop-ffi --features cli --bin uniffi-bindgen -- \
  generate --library target/aarch64-linux-android/release/libwhoop_ffi.so \
  --language kotlin --out-dir target/bindings
```

Then copy the two `.so` into `jniLibs/<abi>/` and `whoop_ffi.kt` into
`app/src/main/java/uniffi/whoop_ffi/`.

## Status

Compiles into the app and ships in the APK (both ABIs). Runtime decode is validated on a
device/emulator, not in CI. A follow-up can build the `.so` + bindings from source via a
Gradle cargo-ndk task instead of committing the artifacts.

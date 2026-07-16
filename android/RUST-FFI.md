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

The FFI surface is at **full client parity**: `WhoopCodec` decodes history (all fields), live
(realtime HR/R-R, on-wrist r22, event/battery, console), and command responses, builds every
command frame the app writes (hello/battery/data-range/offload-abort/stop-raw-flood/toggle-hr/
reboot/buzz/broadcast-hr/set-config/alarm), and exposes the derived metrics (ppg-hr, HRV, SpO2).
It compiles into the app and ships in the APK (both ABIs). The core and the Rust bridge are
**parallel to** the old `com.noop.protocol` Kotlin decoders, which are still the live path.

## Next batch: retire the Kotlin decoders (route decode only through whoop-rs)

The goal is one decoder. Concrete steps:

1. **Find the decode call sites** in `com.noop.ble` (the `WhoopBleClient`) and `com.noop.collect`/
   `data`: where notification bytes go into `Framing` / `HistoricalStreams` / `Streams` / `Enums`
   response+live decode.
2. **Adapter layer** — map `RustCodec` outputs (`HistorySummary` / `Live` / `Response` / `Step`) onto
   the app's existing record/stream/Room types (or move the consumers to the FFI types). The stored
   values must stay byte-identical to the old path (Room migrations depend on them).
3. **Route the radio** — feed offload frames to `WhoopCodec.feed`, live-notify frames to `decodeLive`,
   command replies to `decodeResponse`; write the frames the builders return (seq + gates stay native).
4. **Validate on a real strap** (BLE contract) — a parity night: same band, decode both ways, diff the
   stored records. Compile proves nothing about connection behaviour.
5. **Delete the superseded Kotlin**: `Crc`, `Framing`, `HistoricalStreams`, `Streams`, `Whoop5RawImu`,
   `PpgHr`, and the response/live decode in `Enums`. **Keep**: `DeviceFamily` (label→Gen resolution),
   `BackfillCaptureJsonl` (the capture writer — Rust hands back `Step` + `Frame.raw()`), the
   command-SEND orchestration (seq/allow-list/confirm gates), standard-GATT parse (`0x2A37`/`0x2A19`),
   and timezone→epoch for the alarm.
6. **Optional**: a Gradle cargo-ndk task to build the `.so` + bindings from the whoop-rs source, so the
   generated artifacts stop being committed here.

Deferred whoop-rs core-gaps to add only if the swap surfaces a need: a SET_CLOCK frame builder (handshake
clock-set, FORBIDDEN/gated), the WHOOP4 alarm leg (Gen4 still unverified), and off-nominal
GET_DATA_RANGE scanning.

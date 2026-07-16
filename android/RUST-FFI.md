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

## Decoder convergence (done in whoop-rs, pre-wiring)

Before routing decode through the FFI, the two decoders' per-field outputs were adjudicated so the swap can
be byte-identical. Each mismatch was resolved to its superior side and landed in whoop-rs (the app keeps
only app-policy scaling); verified against the external RE cross-check and real hardware, not against
Kotlin as ground truth:

- **skin_temp** — whoop-rs now exposes `skin_temp_raw` (the raw register) alongside `skin_temp_c`. The app
  stores the raw value byte-identically and keeps its family/device-specific °C scale (the 4.0 per-device
  anchor). Verified: v18 `/100` → 33.3 °C on the 838 dump; v24 raw 861 → ~34 °C on a real 4.0 frame. The
  4.0 °C scale is device-dependent, so raw is the dispute-neutral store.
- **activity_class (v18)** — whoop-rs gates it to the mapped `{0,1,2}` codes (0xFF / unmapped store
  nothing), matching noop's guard. Empirical on both sides (not in the external RE map).
- **spo2_pct (v18)** — whoop-rs decodes it (sleep-only tri-mode, 70..100); noop does not store 5.0 SpO2
  today. Default stays byte-identical (unstored); wiring a gated consumer is a separate opt-in.
- **4.0 Gen4 leg** — was unverified; now confirmed decoding a real WHOOP 4.0 v24 hardware frame end to end.

Since then the FFI also gained (all hardware-verified, additive): 4.0 serial+firmware from the
`GET_HELLO_HARVARD` hello (the 4.0 omits the DeviceInfo GATT service), a gen5-hello `?` bug fix (a truncated
5.0 hello no longer drops the serial), the 5.0 strap-battery decode-offset fix (was `p[13]`, real is `p[2]`),
and two new command responses — `ExtendedBattery` (4.0 fuel gauge: mV / remaining-mAh / current) and
`BatteryPack` (5.0 pack: serial / SOC / mV / pack-id).

The whoop-rs `.so` + Kotlin binding in this tree are now several convergences behind and must be
**regenerated** before the wiring step below (`HistorySummary.skin_temp_raw`, plus the `ExtendedBattery` /
`BatteryPack` `Response` variants).

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

# Rust whoop-ffi integration (experimental)

The app can decode WHOOP frames with the from-scratch Rust codec from `whoop-rs`
(`crates/whoop-ffi`, a `uniffi` surface over the pure sans-IO codec), instead of the
Kotlin `com.noop.protocol` decoders. Bluetooth stays native Kotlin (`com.noop.ble`);
only frame bytes cross the FFI, nothing async or radio-bound.

## What is committed here

- `app/src/main/jniLibs/{arm64-v8a,x86_64}/libwhoop_ffi.so` — the prebuilt Rust core (regenerated,
  carries the skin-temp-raw / ExtendedBattery / BatteryPack surface, the sub-lag v26 ppgHr, f64 battery
  precision, and the widened `Live.Event` that reproduces the stored event contract).
- `app/src/main/java/uniffi/whoop_ffi/whoop_ffi.kt` — the generated uniffi Kotlin binding (regenerated
  to match the `.so`; binding + `.so` must always regenerate together or reads corrupt).
- `app/src/main/java/com/noop/protocol/RustCodec.kt` — the bridge
  (`decodeHistory`/`decodeLive`/`decodeResponse`/`rmssd`/`readiness`).
- `app/src/main/java/com/noop/protocol/RustAdapter.kt` — FFI→StreamBatch row mappers + shadow diff.
- `app/src/main/java/com/noop/protocol/RustShadowParity.kt` — thread-safe per-field parity counters.
- `build.gradle.kts` — the JNA runtime dep, an `arm64-v8a`/`x86_64` `abiFilters`, and the host-JVM
  `jna.library.path` for the parity test.

## Regenerating (after a whoop-rs change) — DONE for the current surface

The committed `.so` + binding are current. Regenerate both together after any future whoop-rs change.
From the `whoop-rs` checkout, with the Android NDK path set:

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

### Done: regen + harness + shadow adapter

- **Regen — DONE.** Both `.so` (arm64-v8a + x86_64) and the uniffi binding were regenerated together
  so `HistorySummary.skin_temp_raw`, `Response.ExtendedBattery`, and `Response.BatteryPack` are all
  present and layout-consistent. App build (`assembleFullDebug`) and unit tests are green.
- **Harness — DONE.** Two parity mechanisms: a Rust-golden set in whoop-rs (`real_frames.rs` +
  `ppg_hr_real.rs`, real-frame v26 PPG + battery/wrist EVENT + `ppg_hr`, all from a 5.0 capture), and a
  host-JVM JNA test `RustKotlinHistoryParityTest` that decodes every shared fixture through both the
  Rust FFI and the Kotlin decoders and asserts field-by-field equality (18 type-47 frames byte-identical
  incl. exact f32→f64 gravity). It self-skips when the host dll is absent so CI never breaks.
- **Adapter (shadow) — DONE.** `RustAdapter` maps FFI outputs onto `StreamBatch` rows and diffs them
  against the authoritative Kotlin decode into `RustShadowParity`, behind a default-OFF flag.

### Shadow flag + Test Centre location

- **Flag:** `PuffinExperiment.isRustShadowEnabled` (SharedPreferences key `noopRustShadow`, file
  `noop_experiments`), **default OFF**.
- **UI:** Test Centre → **Diagnostic tools** card → **"Rust shadow decode (parity)"** switch, with a
  parity-report readout and Refresh / Reset buttons.
- When OFF the native codec is never loaded and no stored value changes; when ON the Rust decode runs in
  parallel at three seams (offload `Backfiller.finishChunk`; live + response `WhoopBleClient.onInbound`)
  and only writes parity counters. All entry points are `runCatching`-wrapped so a native failure can
  never crash the BLE thread.

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

The whoop-rs `.so` + Kotlin binding in this tree have now been **regenerated** to carry all of the above
(`HistorySummary.skin_temp_raw`, plus the `ExtendedBattery` / `BatteryPack` `Response` variants), so they
are current with the convergence work.

## Rust-primary cutover — WIRED behind a flag (Kotlin not deleted) — GATED

The Rust-primary save path is now **wired and shipping in the APK**, default OFF. When
`PuffinExperiment.isRustPrimaryEnabled` (SharedPreferences key `noopRustPrimary`) is ON, whoop-rs is the
**authoritative writer** at every storing seam and the Kotlin decode feeds the `RustShadowParity`
comparator; any native error falls back to Kotlin for that frame (recorded, no data lost). Wired seams:
`WhoopBleClient.flushLive`, `Backfiller.finishChunk`, `RawHistoryArchive.replay`, `CaptureImporter`, and
the `COMMAND_RESPONSE` battery leg. The app-side ts/#547/#72/rrInterval-seq PK path is untouched — Rust
supplies only the raw unix seconds into the existing correction pipeline. Test Centre switch:
**"Rust primary decode (authoritative)"**.

### Adjudicated decode choices (whoop-rs = single source of truth)

Each divergent field was adjudicated against real captures + external RE, and the better implementation
landed in whoop-rs (analysis in `whoop-research/decoder-adjudication/`). The **intended behavioral deltas
vs the OLD Kotlin** (correct-by-design, classified EXPECTED by the comparator):

- **ppgHr (v26)** — whoop-rs gained the sub-lag parabolic ACF refine. Net-zero vs the current app (Kotlin
  already ships sub-lag hardwired ON); the visible delta is only vs the old integer-lag whoop-rs
  (fixture 76→78). Confirmed a real-data win on the 838 night: sub-lag MAE 2.12 vs integer 2.50 bpm.
- **battery SOC** — f64 division of the deci-percent (999 → exact 99.9). Net-zero vs the current app
  (Kotlin already divides in f64); fixes whoop-rs to match (was f32-domain 99.90000152).
- **gravity gate** — whoop-rs gates v18/v24 |g| to [0.5,1.5], dropping garbage the ungated Kotlin path
  would store. On 1861 real v18 frames the gate never fired, so on real data this is a no-op that only
  rejects corrupt/future-firmware vectors (cleaner sleep-stager input).
- **event row** — `Live.Event` widened to carry the raw battery deci-% + mV + charging + Gen5
  `payload_hex`, so `RustAdapter` reproduces Kotlin's exact `NAME(raw)` kind + canonical `payloadJSON`.
- **5.0 sleep spo2_pct (deferred)** — whoop-rs decodes a real signal the Kotlin app discards as aux byte
  82. Adding a stored Room stream is a schema migration, **out of this batch** — flagged for David.

The goal is still one decoder. **No Kotlin decoder is deleted**; deletion is hard-gated on both of:

- **(a)** the Rust-primary parity report reading **clean on a real strap night** (turn the flag on, wear +
  sync a real 5.0 AND 4.0 band, read the Test Centre parity counters — 0 UNEXPECTED on raw/contract
  fields, sanity-OK on the whoop-rs-win fields, 0 native fallbacks, rows persisted) — see
  `DECODE-CUTOVER-PLAN.md` for the field-tiered runbook, and
- **(b)** **explicit approval from David** to delete Kotlin decode.

The remaining steps after the gate:

1. **Route the radio** — feed offload frames to `WhoopCodec.feed`, live-notify frames to `decodeLive`,
   command replies to `decodeResponse`; write the frames the builders return (seq + gates stay native).
   The single cleanest cutover tap is `WhoopCodec.feed(chan,bytes)` at `onInbound` (includes reassembly).
2. **Delete the superseded Kotlin**: `Crc`, `Framing` (`parseFrame` + `Reassembler`), `HistoricalStreams`
   decode, `Streams.extractStreams`, `Whoop5RawImu`, `PpgHr`, and the response/live decode role in `Enums`.
   **Keep**: `DeviceFamily.forRegistryModel` (label→Gen resolution), `BackfillCaptureJsonl` (the capture
   writer), the command-SEND orchestration (seq/allow-list/confirm gates), standard-GATT parse
   (`0x2A37`/`0x2A19`), `AlarmPayload` timezone→epoch math, and `DataRange` until Rust grows a range-scan.
3. **Optional**: a Gradle cargo-ndk task to build the `.so` + bindings from the whoop-rs source, so the
   generated artifacts stop being committed here.

### Pre-deletion checklist (from the shadow-pass completeness review)

The current shadow proves parity only for **per-second historical scalars + transient live HR/R-R +
battery percent**. Every item below must be closed before any Kotlin decoder is deleted; each is a
known gap in the current shadow, not a hypothetical:

- [x] **v26 PPG `ppgHr` (bpm/conf)** — RESOLVED: whoop-rs `ppg_hr` now does the same sub-lag parabolic
      refine the app hardwires ON, so the FFI matches `PpgHr.estimate(subLagInterp = true)` window-for-window
      (host-JNA `RustAdjudicationParityTest`, 22/22 on the real v26 fixture, golden bpm 78). A shadow diff on
      device is still worth adding, but the algorithm divergence is closed.
- [ ] **`ppgWaveform` LE-i16 BLOB** — layout agrees by construction but is not routed through the
      adapter (`decodeHistory` has no samples field) and is untested end-to-end. Wire + diff it.
- [x] **EVENT rows** — RESOLVED: `Live.Event` is widened to `{number,unix,battery_soc_deci,
      battery_millivolts,battery_charging,payload_hex}`, and `RustAdapter.liveToBatch` rebuilds the stored
      `NAME(raw)` kind (EventNumber table) + canonical sorted-key `payloadJSON` (StreamPersistence) from it.
      Byte-identical to the Kotlin decode on the real battery_level + wrist_on fixtures (parity test). Edge
      residual: the `charging` bit is a `bool`, so the raw-byte `<= 1` store gate can't be reproduced.
- [ ] **Stored live rows** — the shadow diffs the transient `parseFrame` map, not the values that
      `flushLive`→`extractStreams` actually persists. Diff the stored decode.
- [ ] **ts + PK path** — the shadow diffs pre-correction field values; the #547 plausibility drop, #72
      snap-to-5-min grid, and the rrInterval `(deviceId,ts,rrMs,seq)` PK are app-side and unexercised.
      Confirm the corrected-ts + seq PK path is stable across the swap (ts logic MUST stay app-side).
- [x] **Battery soc precision** — RESOLVED: the FFI battery-bearing values divide in f64 now
      (`Response.Battery.percent`, `BatteryPack.socPct` are f64; the event carries the raw deci-% so the
      adapter divides in f64). Gen4 999 → the exact 99.9 (not the f32-domain 99.90000152), pinned by the
      host-JNA parity test.
- [ ] **Gen4 / WHOOP 4.0 leg** — no automated or hardware parity yet (v24/v25 layouts, different live
      chars). Needs a real 4.0 offload parity run.
- [ ] **Enforced CI gate** — the host parity test self-skips without the native dll and CI never builds
      the cdylib, so no always-running test loads Rust. Add a hard CI parity gate (build the cdylib,
      diff a captured frame corpus) and wire the diff into both offload replay sites
      (`RawHistoryArchive.replay`, `CaptureImporter`) so a stored `.jsonl` corpus can be diffed offline.
- [ ] **COMMAND_RESPONSE coverage** — only battery percent + a firmware-present boolean are diffed; the
      new `ExtendedBattery` / `BatteryPack` variants, the fw version string, clock, and result codes are
      not. Broaden the response diff.
- [ ] **Reassembly** — the shadow feeds Rust only complete Kotlin-reassembled frames; Rust's own
      reassembly (the intended `WhoopCodec.feed` cutover tap) is unvalidated. Exercise it.

Deferred whoop-rs core-gaps to add only if the swap surfaces a need: a SET_CLOCK frame builder (handshake
clock-set, FORBIDDEN/gated), the WHOOP4 alarm leg (Gen4 still unverified), and off-nominal
GET_DATA_RANGE scanning (Kotlin `DataRange` stays until this lands).

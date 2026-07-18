# Sleep: the whoop-rs border

All sleep **algorithm** lives in whoop-rs (`physio-algo::sleep`, see `whoop-rs/docs/sleep.md`). The app is
a thin frontend over one FFI call. This note is the contract: what crosses the border, and what stays
Kotlin (and why).

## The one call

`SleepStager.detectSleep(hr, rr, resp, gravity, steps, tz, wristOff, bandSleepState)` →
`RustSleepStager.analyze(...)` → the `analyzeSleep(SleepStreams)` uniffi FFI. Rust returns one
`SleepSession` per detected in-bed span (span + hypnogram + efficiency + resting HR + windowed avg HRV +
the per-30 s-epoch motion & band-sleep-state grids), mapped back to `DetectedSleep`. `detectSleep` keeps a
bounded LRU (`detectCache`) in front — a memo that only ever skips a recompute, never changes the result.

The edit self-heal (`SleepStageHealer`) re-stages one edited span via `RustSleepStager.stage` →
`stageSleepRefined(input, steps)` (V2 staging + motion-aware wake refinement in Rust).

## What stays Kotlin (frontend / storage / display — not algorithm)

- **The detect memo** (`detectCache`) and stream fingerprints — a perf cache, not math.
- **`SleepStageTotals`** — `stagesJSON` decode + the `dailyAggregateHonoringEdits` user-edit seam
  (substitute an edited block's stages, union manual naps, honor the effective onset). Pure storage/edit
  glue over Room-persisted JSON. Its NightBlock main-night selection is a byte-identical twin of the Rust
  `mainNight*` functions (routing it through the FFI is an optional follow-up).
- **The HRV-window / respiration layer** — `sessionHrvWindows`, `respRateFromRR`, `lastDeepRun`,
  `sessionAvgHRV`, `HrvWindow` — a separate display concern (the #141 deep-HRV-window feature), not
  detection/staging.
- **`remFunnelDiagnostic`** (#688) — a read-only Test-Centre REM-loss triage that re-runs a Kotlin epoch
  classifier (`buildEpochGrid`/`coleKripke`/`gravityDeltas`/`onsetAndFinalWake`). It keeps that classifier
  alive; deleting the diagnostic + its Test-Centre caller would retire the last Kotlin staging code.
- **`hypnogramMetrics`** (AASM minutes from stages), `isGravitySparse`/`isOvernightOnset` (small shared
  predicates), Room persistence, and the whole UI.

## Regenerating the `.so` + binding (after any whoop-rs change)

See `RUST-FFI.md`. Short form, from the whoop-rs checkout with `ANDROID_NDK_HOME` set:
`cargo ndk -t arm64-v8a -t x86_64 build --release -p whoop-ffi`, then
`cargo run -p whoop-ffi --features cli --bin uniffi-bindgen -- generate --library
target/aarch64-linux-android/release/libwhoop_ffi.so --language kotlin --out-dir target/bindings`, then copy
the two `.so` into `jniLibs/<abi>/` and `whoop_ffi.kt` into `app/src/main/java/uniffi/whoop_ffi/`. The `.so`
and the binding must always regenerate **together**.

## Verified

The Kotlin gate tests that predate this move now run **through the native `analyzeSleep`** and pass with
their **original byte-identical expectations** — cross-language parity confirmed on the JVM host. 2446 unit
tests green, `assembleFullDebug` builds. The redundant Kotlin sleep tests were dropped (ported to Rust).

## Optional follow-ups (behavior-identical, non-blocking)

Neither changes any score — both are purity cleanups a later pass can take.

1. **Route main-night selection through Rust.** `AnalyticsEngine.analyzeDay` still calls the Kotlin
   `SleepStageTotals.mainNightGroupIndices` — a byte-identical twin of the Rust `mainNightGroupIndices`
   already on the FFI. Swap it (map the matched sessions → `MainNightBlock`, use the returned indices),
   and use `mainNightSelection` / `habitualMidsleepSec` for the UI reason + learned timing. The
   `SleepStageTotals` `stagesJSON` decode + `dailyAggregateHonoringEdits` edit-seam stay Kotlin (storage).
2. **Retire the last Kotlin classifier.** `remFunnelDiagnostic` (#688, a read-only Test-Centre REM-loss
   triage) is the only remaining caller of the Kotlin epoch classifier (`buildEpochGrid`, `coleKripke`,
   `gravityDeltas`, `onsetAndFinalWake` + the Stage 1–3 constants). Deleting the diagnostic and its
   Test-Centre entry point lets that whole classifier and its constants go — the last of the Kotlin staging.

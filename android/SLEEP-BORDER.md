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
- **The HRV-window / respiration layer** — `sessionHrvWindows`, `lastDeepRun`, `HrvWindow` — a separate
  display concern (the #141 deep-HRV-window feature), not detection/staging. The scored session avgHrv
  itself is computed in whoop-rs (`RustScores.windowedAvgHrv`); this path stays only for the stage-tagged
  deep-pool and the HRV trace.
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

## Done: the last Kotlin classifier is retired

`remFunnelDiagnostic` (#688) and its Test-Centre caller (`AndroidDiagnostics`) were deleted, which took the
whole Kotlin epoch classifier with them: `buildEpochGrid`, `EpochGrid`, `coleKripke`, `rescaleCounts`,
`onsetAndFinalWake`, `dogHRVariability`/`gaussianKernel`/`convolveReflect`, `extractFeatures`/`EpochFeatures`,
`respRateAndRRV`, `classifyEpochs`/`classifyOne`/`isCardiacSparse`, `smoothLabels`/`reimposePhysiology`,
`percentile`/`percentileSorted`, `gravityDeltas`, `rowsBetween`, and the Stage 1–3 constants. `SleepStager`
dropped ~770 lines (1378 → ~610). No epoch staging runs in Kotlin anymore; every stage comes from
whoop-rs. Kept as pure primitives: `findPeaks` / `standardDeviation` / `respPlausibleRangeBpm` (the
resp-rate parity test reconstructs the spec from them to pin the Rust scorer byte-identical),
`isGravitySparse` / `isOvernightOnset` / `largestGapS` (small predicates other engines share).

## Done: main-night selection routed through Rust

`SleepStageTotals`'s span-path selectors — `mainNightIndex`, `mainNightGroupIndices`, `mainNightSelection`,
`bridgedNightGroups`, and the `habitualMidsleepSec` learner — now delegate to the whoop-rs FFI
(`mainNight*` / `bridgedNightGroups` / `habitualMidsleepSec`, mapping `NightBlock` ↔ `MainNightBlock` and
`HistoryBlock` ↔ `SleepHistoryBlock`). Their Kotlin scoring/bridging bodies + the `bridgeAdjacent`,
`circularMeanSec`, and `MEANINGFUL_BONUS_EPSILON`/`CIRCULAR_MEAN_MIN_RESULTANT` helpers were deleted — every
main-night pick (analytics day total, the Sleep-tab hero, the "why this sleep" reason, the Health Connect
export) now computes in Rust. The public Kotlin entry points + data types (`NightBlock`, `MainNightReason`,
`MainNightSelection`, `BridgedNightGroup`, `HistoryBlock`) stay as thin holders, so callers and the existing
`MainNightConsistencyTest` / `BridgedNightGroupsTest` / `MainSleepReasonCopyTest` are unchanged — they now
run **through the FFI** and pass with their original byte-identical expectations (the parity net).

**Stays Kotlin (storage-coupled, no Rust twin):** the `dailyAggregateHonoringEdits` edit-seam and its
`...ByStages` selector, which score by `stagesJSON`-decoded minutes; plus the shared scoring helpers
(`alignmentBonusMinutes` / `targetMidsleepSec` / `localSecOfDay` / `circularDistanceSec` /
`coldStartAnchorSec` / `isOvernightOnset`) that seam still uses.

## Done: the obsolete sleep toggles are retired

The `useExperimentalSleepV2` / `useMotionAwareWake` (and the `useSleepStagerV2` alias) flags — hardwired
`true` since V2 + motion-refine became unconditional in the Rust stager — were removed from
`PuffinExperiment`, `AppViewModel`, `WhoopBleClient`, `IntelligenceEngine`, `AnalyticsEngine`, and
`SleepStageHealer`. Behaviour is preserved (`SleepStageHealer` now always fetches the step stream, the old
always-true path; the diagnostic trace always reports `stager=V2`). `PuffinExperiment.ppgHrSubLagInterp`
(a separate hardwired flag) is untouched.

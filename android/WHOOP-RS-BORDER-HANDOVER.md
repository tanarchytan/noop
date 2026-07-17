# whoop-rs ↔ noop-tan border — handover

Authoritative map of the FFI border after the decode + Tier-1 analytics cutover. noop-tan
(Kotlin/Compose/Room) is the **frontend**; whoop-rs (Rust `whoop-protocol` + `physio-algo` core) is
**all wire decode and all Tier-1 physiology**, reached through one uniffi binding. The border is
**one-way**: noop calls whoop-rs; whoop-rs depends on nothing from noop.

State at write (2026-07-18): whoop-rs `master` **40df2a7** (this doc's twin arch edit; FFI doors
`8a7681b`/`f187e5b`, cleaning fix `0595d63` — pushed at `8a7681b`); noop-tan `noop-tan` **30a39970**
(pushed). Prior facts: `DECODE-BORDER-AUDIT.md`, `ANALYTICS-CUTOVER-SCOPE.md`, `CEILING-REPORT.md`.

---

## (i) The border as it stands

### One-way FFI contract

- **Direction is strictly one-way.** whoop-ffi imports only `physio-algo` + `whoop-protocol`; no
  callback, no async, no radio, no Room handle crosses the FFI. `WhoopCodec` is stateful but the state
  (deframers + offload) is codec-internal, not noop state. So: **noop → whoop-rs only.**
- **The binding** is uniffi-generated at `app/src/main/java/uniffi/whoop_ffi/whoop_ffi.kt` (a 1:1
  translation of the `#[uniffi::export]` surface in whoop-rs `crates/whoop-ffi/src/lib.rs` — line-cite
  lib.rs, it is authoritative). noop wraps it behind **four** hand-written bridges:
  - `com/noop/protocol/RustCodec.kt` — stateful `WhoopCodec` + decode + command-frame builders.
  - `com/noop/protocol/RustAdapter.kt` — re-shapes whoop-rs types into the app's flat parsed-map /
    `StreamBatch` / `ParsedFrame` storage contracts; owns the label/enum tables only, no decode logic.
  - `com/noop/analytics/RustScores.kt` — the Tier-1 score free-fns.
  - `com/noop/analytics/RustSleepStager.kt` — the V1/V2 hypnogram.
- **The real border is wider than the four bridges.** `lib.rs` exports more `WhoopCodec` methods + free
  fns callable directly under `uniffi.whoop_ffi.*` without a wrapper (`feed`, `offload_start`,
  `r22_frames`, `reset`, `get_hello_frame`, `reboot_frame`, `toggle_realtime_hr_frame`, `imu_features`,
  `decode_imu_frame`, `haptic_clock_pulses`, …). `RustCodec.kt:22-24` documents this. A future exhaustive
  call-site audit should grep the `uniffi.whoop_ffi` import sites, not just the four bridges.

### Full FFI surface (grouped)

**Decode** (frame bytes IN → decoded records OUT). Stateful `WhoopCodec` (one `Arc<Mutex<Inner>>` per
band). `RustCodec.kt` wraps: `decodeHistory`→`decode_history` (type-47 → `HistorySummary`, CRC-gated —
bad CRC returns null), `decodeLive`→`decode_live` (Realtime/R22/Event/Console), `decodeResponse`→
`decode_response` (Battery/Clock/Hello/DataRange/Version/ExtendedBattery/BatteryPack/Other),
`decodeMetadata`→`decode_metadata` (drives offload trim), `decodePpg`→`decode_ppg_frame` (v26 24 Hz).
Free fns `dataRangeNewest`/`dataRangeOldest`. `RustAdapter` does no decode itself — `recordFields`/
`eventFields`/`parseFrame` all delegate to `RustCodec.decode*` then re-shape.

**Decode-adjacent metrics** on `RustCodec.kt`: `ppgEstimates`→`ppg_hr`, `rmssd`→`hrv_rmssd_gap_aware`,
`readiness`→`hrv_readiness`.

**Scoring** (primitive stream lists IN → scalar/segment scores OUT), free fns wrapped by `RustScores.kt`:
`recovery`/`band`/`recoveryIndexSlope`/`bankedNights` (recovery / Charge), `strain`/`strainDenominator`,
`rmssdGapAware`, `windowedAvgHrv`→`hrv_windowed_avg` (session avgHrv, 5-min bucket-mean), `sessionRestingHr`/
`dailyRestingHr`, `respRateFromRr`, `stressIndex`/`stressComponents` (Baevsky), `spo2FromPaired`,
`nightlySpo2RawMeans`→`nightly_spo2_raw_means` (4.0 raw red/IR ADC means), `hrZonesForAge`/`hrTimeInZone`,
`vo2maxEstimate`/`fitnessAgeCompute`.

**Sleep staging** via `RustSleepStager.kt`: `stage()`→`stage_sleep_v2` (5.0/MG default) or `stage_sleep_v1`
(4.0), `SleepInput{start,end,hr,rr,accel,resp}` → `Vec<SleepSegment>{start,end,stage}`. The 4-class
hypnogram recipe lives only in Rust now.

**Command-build** (send policy IN → ready-to-write frame bytes OUT; the FFI never writes the radio).
`RustCodec.kt` wraps `commandFrame` (refuses destructive opcodes internally), `buzzFrame`, `getBatteryFrame`,
`setClockFrame`/`setClockLegacyFrame`, `alarmSetFrame`/`alarmSetFrameGen4`/`alarmDisableFrame`,
`advertisingNameFrame`, `setConfigFrame`, `broadcastHrFrame`.

### Invariants the border guarantees

- **Bit-for-bit stored values.** hr=0 / rr=0 dropped (the store rule); raw registers stored UNSCALED
  (skinTempRaw, spo2 red/ir, respRaw — the consumer applies scale); gravity widened f32→Double exactly;
  battery deci-% divided in f64 (`/10.0`); null-preserving small enums. `spo2Pct` is pre-sleep-gated +
  sentinel-dropped in Rust, so a non-null == a real reading (`RustAdapter.kt:22-38`).
- **CRC / trim safety.** whoop-rs CRC-gates internally: a null decode == bad/forged frame, archived by
  `rejectedHistoricalRecords`, never stored past the trim. METADATA carries `crc_ok` so a forged
  HISTORY_END cannot advance the trim over undrained history.
- **RMSSD grouping parity.** `RustScores.groupRuns` CLAMPS `rrMs` into `UShort` range rather than
  dropping, so an out-of-range beat still reaches the Rust clean and is dropped there WITH a contiguity
  break — dropping it Kotlin-side would splice neighbours into a spurious pair. `respRateFromRr`
  instead pre-filters to `[RR_MIN,RR_MAX]` to match `HrvAnalyzer.rangeFilter`. The Rust gap-aware RMSSD
  cleaning was aligned to the Kotlin range[300,2000] + Malik ectopic path in whoop-rs `0595d63` (killed
  a 12.5 % divergence).
- **Only primitives cross.** Inbound: raw frame `ByteArray`; primitive stream lists (`HrTick`, `RrRun`,
  `SleepInput` samples, `Spo2Span`/`Spo2RawSample`) mapped from Room; scalar params. Outbound: decoded
  records, `Option<f64>`/`Option<i32>` nightly values + small info structs, `Vec<SleepSegment>`,
  command-frame `Vec<u8>`. No shared mutable state, no dates, no objects.

---

## (ii) What noop-tan IS now

A **pure frontend + a deterministic behavioral/coaching analytics engine that stays Kotlin by scope
decision**. Honest framing: decode and Tier-1 physiology are gone, but ~4.3k lines of pure, deterministic
scoring math remain in `com.noop.analytics`. Those are portable — they are Kotlin by choice, not by any
UI/timing/notification necessity (all clock/journal/notification handling lives in `com.noop.notif/*`
wrappers + `IntelligenceEngine`, the sole Room writer).

**Genuinely frontend (stays Kotlin, legitimately):**
- UI / Compose.
- The BLE radio (`BluetoothGatt`) + the `Reassembler` (BLE fragment reassembly; feeds complete frames
  to the FFI).
- Send-orchestration: the seq counter, the 5/MG allow-list, the opt-in / R22-ordering gates. The FFI
  builds frames; the app decides whether and when to write them.
- Room / SQLite storage + `IntelligenceEngine` (sole Room writer).
- App-side ts plausibility / clock correction (the FFI takes an already-corrected ts, never re-derives it).
- Standard-GATT parse, `AlarmPayload` epoch math, `DeviceFamily` resolution, `BackfillCaptureJsonl`, the
  label/enum tables in `RustAdapter`.
- `.noopbak` whitelist (a byte-identical cross-platform contract; a frontend responsibility the FFI never
  touches).

**Behavioral / coaching layer — stays Kotlin by scope decision** (17 scorers, ~4,322 LoC, all pure
deterministic math, zero android/notification/clock coupling): Live-session coaching, Cycle phase,
Circadian, Recovery forecast, Sleep-debt, Readiness (ACWR), Illness Heads-Up, Vitality/Body-Age,
Activity-cost / Dose-response / Effect-rank, Caffeine, Nap, Sedentary, Smart-alarm, Battery estimator,
Resonance, DaytimeStress notif wrapper, HrvFreqDomain (LF/HF), plus `HrvAnalyzer` (the primitive those
consumers use).

**Why they stay** (pragmatic, not technical):
- **Journal / behavior-log coupled** (`EffectRanker`, `DoseResponseEngine`, `CaffeineDecay`,
  `IllnessSignalEngine`) — consume app-owned journal/behavior tag models; FFI marshalling cost > benefit,
  and noop-tan is Android-only so there is no Swift-twin parity pressure.
- **Calendar / timezone-shaped** (`CircadianEngine`, `CyclePhaseEngine`) — `java.time` is more natural
  app-side.
- Both categories are coaching/lifestyle tier — lower parity stakes than the Tier-1 physiology.

---

## (iii) What is LEFT to do — ranked

**[TRIM NOW — zero risk]**
1. Delete `RustScores.spo2FromPaired` (`RustScores.kt:191`) and `RustCodec.readiness` (`RustCodec.kt:61`)
   — two dead wrappers, no production callers, no tests (leave the FFI symbols). Optionally delete
   `HrvAnalyzer.analyze` + `HrvAnalyzerGateTest` (production-dead, test-only) or keep with a doc note;
   `HrvAnalyzer.rejectEctopic`/`pnn50GapAware` could tighten to `private` (used only internally).

**[ROUTE NEXT — closes real parity gaps]**
2. **`recovery_drivers` FFI (task #8) — highest value.** `RecoveryDrivers.chargeDrivers`
   (`RecoveryDrivers.kt:69` + private `scoreOf` `:210`) reimplements the routed Charge logistic in Kotlin
   to compute each term's marginal point-swing. It matches the Rust score BY CONSTRUCTION today, but any
   future whoop-rs recovery edit silently drifts the "what shaped it" breakdown from the stored score. Fix
   = a `recovery_drivers` door returning per-term z + marginal contribution; this is the sole remaining
   Tier-1 door and a live drift hazard.
3. **Route `deepHrvWindow` avgHrv (#141) + feed the trace from the FFI (task #10).** With the experimental
   toggle ON, `AnalyticsEngine.kt:347-358` still Kotlin-aggregates `avgHrv` over deep-stage windows,
   bypassing `RustScores.windowedAvgHrv`. It feeds off `SleepStager.sessionHrvWindows`
   (`SleepStager.kt:1968`), which also feeds the #141 HRV nightly trace — retire that primitive only once
   the aggregate routes to Rust AND the trace is fed from the FFI, else the displayed value and the trace
   diverge. (The whole-night avgHrv path IS already routed.)

**[ROUTE LATER — opportunistic Tier-2/3, whoop-rs side]**
4. **whoop-rs Tier-2 FFI doors (task #4)** — surface the pure-numeric frontend scorers as they land in
   `physio-algo`: `HrvFreqDomain` (LF/HF spectral), `ReadinessEngine` (ACWR), `SleepDebt`,
   `RecoveryForecast`, `DaytimeStress` (already on `RustScores.stressIndex` primitives), `VitalityEngine`,
   `ActivityCostEngine`, `ResonanceEngine`. Clean primitive-in/struct-out signatures.
5-7. **whoop-rs Tier-3 builds (tasks #5-#7)** — sleep detection + efficiency + performance; steps +
   calories + workout detection (unblocks the full `StrainScorer` delete, currently kept for the per-bout
   path); skin-temp deviation + daytime stress + EWMA baselines.

**[OPTIONAL — "even purer frontend", only if David wants zero Kotlin math]**
Route the remaining pure-math frontend scorers through the FFI too (they are all portable, no IO). Leave
the journal/behavior-coupled + calendar scorers Kotlin regardless — marshalling cost > benefit, coaching-
tier stakes, no Swift-twin pressure.

---

## (iv) How to run / verify

**Parity-gate method.** Each Tier-1 route landed with a `Rust<Score>ParityTest` that runs the (now-frozen)
Kotlin reference AND the real Rust FFI on the same golden fixture and asserts the STORED value bit-for-bit.
That is the gate — a Rust edit that drifts a stored value fails the JUnit assertion, not a numpy tolerance.

```bash
cd android
JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" \
  ./gradlew.bat :app:testFullDebugUnitTest --no-daemon   # buildRustHostDll → real Rust via JNA
```
Read `BUILD SUCCESSFUL` from the output, never a piped exit code. Key guards: `RustHrvParityTest`
(4 legs / skipped=0), `RustSpo2ParityTest` (2 legs), `RustRecoveryParityTest` (frozen Kotlin composite,
maxErr 0.0 at machine precision), `RustStressParityTest`. The Rust side is pinned separately —
`cd whoop-rs && cargo test` (physio-algo goldens: sleep, resting_hr, resp_rate, hr_zones, stress, vo2max)
plus `cargo clippy --all-targets`.

**Fixtures.** DREAMT n=100 + AAUWSS gold for sleep staging; Task-Force numpy reference for HRV time-domain;
the 838 overnight real 5.0 drain for v26/PPG-HR + SpO2; per-score golden fixtures under `physio-algo` tests.
Ceiling numbers (all Tier-1 scores AT-CEILING) live in `CEILING-REPORT.md`.

**APK build.**
```bash
cd android
JAVA_HOME="/c/Program Files/Android/Android Studio1/jbr" \
  ./gradlew.bat :app:assembleFullRelease --no-daemon      # com.noop.whoop; cargo-ndk .so per ABI
```
The Rust `.so` regenerates via `cargo-ndk` per ABI + the uniffi Kotlin binding; verify both ABIs green.
Note the noop-tan `.so` can lag whoop-rs `master` — regen before wiring a fresh whoop-rs commit.

---

_Companion docs (this dir): `ANALYTICS-CUTOVER-SCOPE.md` (Tier-1 per-score ledger), `DECODE-BORDER-AUDIT.md`
(the decode delete record), `CEILING-REPORT.md` (headroom per score), `RUST-FFI.md` / `DECODE-CUTOVER-PLAN.md`.
whoop-rs authoritative map: `../../whoop-rs/docs/architecture.md`. Cross-project state:
`../../docs/NOOP-TAN-HANDOFF.md`._

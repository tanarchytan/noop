# Border algo TODO — Kotlin algorithm still to move into whoop-rs

The rule (David's, strict): **all algorithm / decode / scoring lives in whoop-rs (Rust) behind the
uniffi FFI; noop-tan is a pure frontend** — UI, BLE radio, Room persistence, send-policy,
userEdited-merge, caching. Any numeric algorithm or WHOOP frame/record decode still computed in Kotlin
is a border leak to relocate.

Sleep detection / staging / wake-refine / main-night span selection are **done** (see `SLEEP-BORDER.md`).
This is the ledger of everything *else* still in Kotlin. Classified 2026-07-19 by a 4-agent sweep of
`com.noop.analytics` + `com.noop.protocol`: **A** = already delegates to a Rust bridge
(`RustScores` / `RustSleepStager` / `RustCodec` / `RustAdapter` / `uniffi.whoop_ffi`), **B** = genuine
frontend (no algorithm to move), **C** = leak. ~42 C files. Almost every one already has a Kotlin
parity/unit test, so each move has a ready oracle (port the test as the Rust parity fixture, then delete
the Kotlin).

## How to work an item
1. Build the algorithm in `whoop-rs/crates/physio-algo` (pure, deterministic), porting the Kotlin math
   byte-identically; add a Rust test seeded from the existing Kotlin fixture.
2. Expose it on `whoop-ffi` (uniffi record in / out); regenerate the `.so` + binding **together**
   (`RUST-FFI.md`).
3. Route the Kotlin caller through a `RustScores`-style bridge; keep the Kotlin entry point/types as thin
   holders only if callers need them, else delete.
4. The existing Kotlin test should pass **through the FFI** unchanged (the parity net), then retire the
   Kotlin compute.

---

## Tier 0 — quick wins (Rust twin ALREADY exists + proven; just reroute callers, then delete)

These are the cheapest: the Rust implementation and a passing parity test already exist; the Kotlin
duplicate only survives because a few callers still call it directly.

| File | Algorithm | Why it still exists | Existing test |
|---|---|---|---|
| `HrvAnalyzer.kt` | RMSSD / SDNN / pNN50 gap-aware + Malik ectopic cleaning + rolling rMSSD | `RustScores.rmssdGapAware`/`windowedAvgHrv` are live + proven, but `SpotHrvReading` and `SleepStager.sessionHrvWindows` still call the Kotlin directly. Needs a **stage-tagged windowed** FFI variant, then delete. | `RustHrvParityTest` + 8 HRV tests |
| `RecoveryScorer.kt` (residual) | `recoveryIndexSlope` (OLS overnight HR-decline), robust `zScore`, `bankedNights` | The composite `recovery()` already moved; these three have Rust twins in `RustScores`. Reroute `RecoveryDrivers`/`…Trace`/`CalibrationMilestones` + delete. | `RustRecoveryParityTest`, `RecoveryIndexActivityBalanceTest` |
| `StrainScorer.kt` | TRIMP (Edwards/Banister), Karvonen %HRR, percentile HRmax, log map, denominator LSQ fit | `RustScores.strain` is live for the daily figure (bit-for-bit), but `WorkoutDetector`/`ManualWorkoutRescore` still call `StrainScorer` for **per-bout** scoring. Moves together with WorkoutDetector (Tier 3). | `RustStrainParityTest` |

## Tier 1 — decode leaks (must move: byte decode belongs in Rust)

| File | Decode | Difficulty | Existing test |
|---|---|---|---|
| `protocol/Whoop5RawImu.kt` | 1244-byte columnar 6-axis IMU offload buffer (100 accel + 100 gyro, i16 LE at fixed offsets → g / deg·s⁻¹) | small–med | `Whoop5RawImuTest` |
| `protocol/Streams.kt` → `skinTempCelsius` / `Whoop4SkinTemp.deviceAnchorRaw` | family-aware raw skin-temp register → °C affine calibration + per-device median anchor (the `extractStreams` mapping half is frontend; the temp math is the leak) | medium | `SkinTempConversionTest` |
| `protocol/Framing.kt` → `Reassembler` | BLE fragment reassembly + per-family frame-length/SOF parse (puffin +8 vs whoop4 +4). Radio-adjacent — the field decode is already in Rust; this re-implements framing the Rust framer also knows | medium | `ReassemblerTest` |
| `protocol/Crc.kt` | full zlib CRC-32 table + compute. Inbound gating already in Rust; now only synthesises trailers for historical **tests** → lowest priority, deletable once those tests synth via the Rust codec | small | ⚠️ indirect only (no dedicated `CrcTest`) |

## Tier 2 — core physio that FEEDS the others (build these first)

| File | Algorithm | Difficulty | Existing test |
|---|---|---|---|
| `Baselines.kt` | Winsorized-EWMA baseline center + abs-dev spread + cold-start/outlier gates + z-deviation + trailing mean/SD. **Feeds recovery / illness / cycle / vitals** — highest-value single move. | large | `BaselineSeedingTest` +9 |
| `AnalyticsEngine.kt` → `rest()` | inline sleep "Rest" (sleep-performance) composite: weighted duration/efficiency/restorative/consistency + deep-adequacy factor. (Recovery/strain/RHR/resp/SpO2 in this file already delegate to `RustScores`.) | medium | `ChargeEffortRestScoringTest` |
| `HrvFreqDomain.kt` | Lomb-Scargle periodogram → LF / HF / LF:HF / total power. Pure DSP, no Rust twin yet. | medium | `HrvFreqDomainTest`, `HrvFreqAgreementTest` |

## Tier 3 — self-contained statistical / detection engines (straight ports)

Ranked roughly by how unambiguously "algorithm" they are.

| File | Algorithm | Difficulty | Existing test |
|---|---|---|---|
| `EffectRanker.kt` | Welch t-test p (erf / normal-CDF approx) + Cohen's d + lag-aware effect ranking | medium | `EffectRankerTest` |
| `CircadianEngine.kt` | single-component COSINOR OLS fit (Cramer's rule) → acrophase/amplitude | medium | `CircadianEngineTest` |
| `IllnessDistance.kt` | Mahalanobis distance D²=xᵀC⁻¹x + Gauss-Jordan matrix inversion + diagonal fallback | medium | `IllnessDistanceTest` |
| `VitalityEngine.kt` | summed log-hazard ratios → Gompertz "body age" + CoV + piecewise-linear norms | medium | `VitalityEngineTest` |
| `ResonanceEngine.kt` | per-breath-cycle RSA amplitude (instantaneous-HR peak-to-trough) + resonance-pace sweep | medium | `ResonanceEngineTest` |
| `DaytimeStress.kt` | per-hour autonomic z-scores + logistic 0–3 squash + quartile calm-reference | medium | `DaytimeStressTest` |
| `CyclePhaseEngine.kt` | weighted temp/RHR/HRV luteal index + median/MAD elevation-onset + cycle-length | medium | `CyclePhaseEngineTest` |
| `DoseResponseEngine.kt` | OLS dose→outcome slope + prior-shrinkage blend w=n/(n+k) + contradiction rule | small–med | `DoseResponseEngineTest` |
| `IllnessSignalEngine.kt` | 0–100 illness = capped per-signal z-sum + ≥2 corroboration gate + confounder dampen | small | `IllnessSignalEngineTest` |
| `StressOnsetDetector.kt` | slow-EMA RMSSD baseline + fast-window drop ratio + edge-crossing JITAI trigger | medium | `StressOnsetDetectorTest` |
| `ReadinessEngine.kt` | per-signal z-scores + ACWR acute:chronic + Foster monotony + resp drift | med–large | `ReadinessEngineTest` |
| `RecoveryForecast.kt` | OLS slope + sample SD + 3-nudge weighted tomorrow-Charge | medium | `RecoveryForecastTest` |
| `RecoveryDrivers.kt` | per-driver marginal point swing via the recovery logistic + weights (must mirror the Rust recovery model exactly) | medium | `RecoveryDriversTest` |
| `NapDetector.kt` | motion quiet-run + HR-settle gate + confidence (leans on `WorkoutDetector` motion primitives → port together) | medium | `NapDetectorTest` |
| `WorkoutDetector.kt` (+ `Calories`) | motion+HR bout detection, run merge/backdate; Keytel-2005 active EE + Harris-Benedict BMR calorimetry. Largest single leak. | large | `WorkoutDetectorTest`, `DayCaloriesTest` |
| `AutoWorkoutDetector.kt` | sustained-elevated-HR span growth + dip tolerance + merge + motion-mean confirm | medium | `AutoWorkoutDetectorTest` |
| `StepsEstimateEngine.kt` | motion-volume integration + robust motion-weighted-median coefficient fit + confidence | medium | `StepsEstimateEngineTest` |
| `ActivityCostEngine.kt` | rest-baseline vs next-morning delta means + forward bounce-back trajectory + ranking | medium | `ActivityCostEngineTest` |
| `ImuFeatureExtractor.kt` | accel-AC RMS energy, gyro energy, jerk RMS, autocorrelation cadence + strength | medium | `ImuFeatureExtractorTest` |
| `SedentaryDetector.kt` | smoothed gravity-motion threshold bout detection (the buzz de-dup/window gating around it is frontend policy) | small–med | `SedentaryDetectorTest` |
| `StepsCounter.kt` | wrap-aware u16 `step_motion_counter` delta sum + gap/reboot rejection | small | `StepsCounter Test` |
| `FitnessAgeEngine.kt` | HUNT PA-Q index reconstruction (freq×intensity×duration) — an FFI input; headline VO2max/FitnessAge already delegate | small | `FitnessAgeEngineTest` |
| `BatteryEstimator.kt` | discharge-slope fit (%/h) + SoC→runtime + rated fallback + hysteresis alert | medium | `BatteryEstimatorTest` +2 |
| `CaffeineDecay.kt` | exponential half-life decay 0.5^(t/hl) + ln-cutoff time + residual-mg sum | small | `CaffeineDecayTest`, `CaffeineCutoffTest` |
| `SleepDebt.kt` | rolling Σ(slept−need) debt ledger + deadband (borderline aggregation, but Swift byte-parity contract) | small | `SleepDebtTest` |
| `SleepStager.kt` (residual) | `sessionHrvWindows` (5-min RMSSD windowing, reuse HRV FFI), `hypnogramMetrics` (AASM TST/WASO/efficiency/%), `findPeaks`/`standardDeviation` | medium | partial (`HrvAnalyzerTraceTest`; `findPeaks` via `RustRespRateParityTest`) |
| `SleepStageTotals.kt` (residual) | the **stages-path** main-night selectors (`mainNightIndexByStages`/`GroupIndicesByStages` = asleep-min + alignment-bonus over decoded JSON) + `dailyAggregate` efficiency. Needs a decoded-minutes FFI variant so it converges with the already-moved span path. Storage-coupled. | medium | `MainNightConsistencyTest`, `Issue547…` |
| `analytics/Analytics.kt` (legacy) | `IllnessWatch.evaluate` (28-day-baseline anomaly), naive `Hrv.rmssd`, Tanaka HRmax + zone ladder — a secondary/legacy path (gold path already uses the Rust gap-aware rMSSD) | medium | `AnalyticsTest` |
| `V5HealthSignals.kt` | rolling mean/sample-SD z-scores feeding the 3 illness/stress engines (mostly orchestration + one local `zAgainst`) — **no test**, needs a fixture first | small | ⚠️ **none** |
| `VitalBands.kt` | personal-baseline z-banding (|z|≤2) over `Baselines` + skin-temp bimodal split — move with `Baselines` | small | `VitalBandsTest` |

## Tier 4 — borderline (David's call: may legitimately STAY Kotlin frontend)

Numeric, but arguably frontend-behavioral or generic (not WHOOP physiology/decode). Flagged, not assumed.

| File | Why borderline |
|---|---|
| `LiveSessionEngine.kt` | real-time **stateful** haptic coach (HRR target band + median smoothing + step-change/hysteresis). Genuine algorithm, but statefulness makes an FFI port large; could sit in the frontend-behavioral layer. |
| `FusionResolver.kt` | multi-source arbitration (tier-rank winner + delta-vs-tolerance agreement). Thin numeric content; mostly policy. |
| `HydrationGoal.kt` | trivial linear goal (sex baseline + effort bump, round to 50). Near-B. |
| `RouteMath.kt` | Haversine distance + polyline codec + pixel projection. Generic phone-GPS geometry, not WHOOP decode/physio. Only a maximalist reading pulls it in (distance/pace are workout metrics). |
| `HrDownPacer.kt`, `BreathPacer.kt` | haptic metronome timing — behavioral, no scoring. Stay frontend. |

## What correctly STAYS Kotlin (category B — do not move)
Orchestration (`AnalyticsEngine`/`IntelligenceEngine` shells), decode bridges (`RustCodec`/`RustAdapter`/
`HistoricalStreams` — delegates all byte decode, keeps only clock-correction + ingest plausibility policy),
edit/merge glue (`SleepWindowReclip`, `SleepSessionDedup`, `DismissedSleepGuard`, `SleepEditGuard`),
caches (`StagerCache`), model/enum/type holders (`AnalyticsModels`, `Enums`, `DeviceFamily`, `FusionTypes`,
`WorkoutSport`, `ParsedFrame`), constant/policy tables (`MetricArbitrationPolicy`, `DoseResponsePriors`,
`Whoop5Config`, `ScoreConfidence`), command-payload builders whose bytes are built in Rust
(`AlarmPayload`, `HapticClock`), trace/formatter files (`*Trace`, `BatterySocLine`, `BadClockDiagnostics`),
and the already-delegated `RustScores`/`RustSleepStager`/`StressIndex`(gutted)/`HrZones`(trivial display)/
`WatchRecovery`.

## Notes
- **Coverage gaps to close before/at move time:** `Crc.kt` (no dedicated test), `V5HealthSignals.kt`
  (no test) — write a fixture first so the Rust port has an oracle.
- **Order that minimises rework:** Tier 0 (free) → `Baselines` (Tier 2, unblocks illness/cycle/vitals/
  recovery-drivers) → decode (Tier 1) → the rest of Tier 3. `VitalBands` moves with `Baselines`;
  `NapDetector` with `WorkoutDetector` motion primitives; `StrainScorer` dies when `WorkoutDetector` lands.
- None of this is accuracy work — it's relocation for the pure-frontend contract. Behaviour must stay
  byte-identical (the existing Kotlin tests are the parity net).

# NOOP Analytics

On-device analytics for **NOOP**, a standalone, fully offline companion app for WHOOP straps (4.0 and
5.0/MG). NOOP talks to your own strap over Bluetooth, stores everything locally in SQLite, and computes its
three daily scores plus HRV and sleep on-device. There is no cloud and no account in any of the math here.

## Where the algorithms live

The scoring and decode math is **not** in this repo. It lives in **whoop-rs**, a Rust workspace shared by
NOOP (Android, Kotlin via uniffi) and any other client. The authoritative per-algorithm reference, with
every formula, its file, and its tests, is:

> **`whoop-rs/docs/algorithms.md`** (crate `physio-algo`, 206 tests). Read that first.

Android reaches it through the generated `uniffi.whoop_ffi` binding. Two Kotlin bridge files are the entire
delegation surface:

- **`analytics/RustScores.kt`** — recovery, strain, HRV, resting HR, respiratory rate, HR zones, Baevsky
  stress, stress onset, fitness age, SpO2 nightly means, baselines.
- **`analytics/RustSleepStager.kt`** — sleep detection, staging, and main-night selection.

Everything a bridge exposes is computed in Rust; the Kotlin is a thin marshaller. This keeps the numbers
identical across clients and gives the math one home with one test suite.

> **These are NOT WHOOP's scores.** We do not have WHOOP's private algorithms and do not pretend to. NOOP's
> scores answer the same questions with published, peer-reviewed methods (Task Force 1996 HRV, Karvonen
> %HRR, Edwards/Banister TRIMP, Tanaka HRmax, Nes 2011 VO2max, Baevsky SI), so they track WHOOP in
> direction but not number-for-number. Every score is APPROXIMATE and non-clinical. When a value cannot be
> computed honestly, NOOP shows nothing rather than fabricating one.

## NOOP's three daily scores

| Score | Answers | Kotlin entry | Computed in | Internal key |
|---|---|---|---|---|
| **Charge** | How recovered are you? | `RecoveryScorer` -> `RustScores.recovery` | whoop-rs `recovery.rs` | `recovery` |
| **Effort** | How hard did your heart work? | `StrainScorer` -> `RustScores.strain` | whoop-rs `strain.rs` | `strain` |
| **Rest** | How restorative was your sleep? | Rest composite in `AnalyticsEngine` | Kotlin (whoop-rs `rest.rs` not yet wired) | `sleep_performance` |

The display names changed (Recovery to Charge, Strain to Effort, Sleep Performance to Rest) and Effort was
rescaled 0-21 to 0-100, but the internal keys are unchanged so stored history and imports keep working.

---

## What runs where

This is the honest map, verified against the source. A metric is in one of three states.

### 1. Delegated to whoop-rs (Kotlin is a thin bridge)

The algorithm is in Rust; the Kotlin file marshals inputs and calls `RustScores` / `RustSleepStager`.

| Metric | Kotlin caller | FFI function |
|---|---|---|
| Sleep detection + staging + main-night | `SleepStager`, `RustSleepStager`, `SleepStageHealer`, `SleepStageTotals` | `analyzeSleep`, `stageSleepRefined`, `mainNight*`, `habitualMidsleepSec`, `bridgedNightGroups` |
| HRV (RMSSD, gap-aware, windowed, SDNN, range-filter) | `HrvAnalyzer` | `hrvRmssd*`, `hrvSdnn`, `hrvRangeFilter`, `hrvWindowedAvg*` |
| HR from PPG (v26 gap-fill) | offload path | `ppgHr` |
| Charge / Recovery | `RecoveryScorer`, `RecoveryScorerTrace`, `WatchRecovery` | `recoveryScore`, `recoveryBand`, `recoveryIndexSlope`, `recoveryBankedNights` |
| Effort / Strain | `StrainScorer` | `strainScore`, `strainDefaultDenominator` |
| Resting HR | `AnalyticsEngine` | `sessionRestingHr`, `dailyRestingHr` |
| Respiratory rate (RSA) | `AnalyticsEngine` | `respRateFromRr` |
| HR zones + time-in-zone | `HrZones` | `hrZonesForAge`, `hrTimeInZone` |
| Baevsky Stress Index | `StressIndex` | `stressIndex`, `stressComponents` |
| Stress onset (live nudge) | `StressOnsetDetector` | `stressOnsetEvaluate` |
| Fitness Age / VO2max | `FitnessAgeEngine` | `vo2maxEstimate`, `fitnessAgeCompute` |
| SpO2 nightly raw means | `AnalyticsEngine` | `nightlySpo2RawMeans` |
| Personal baselines (EWMA update) | `Baselines` | `baselineUpdate` |
| Steps (5/MG counter) | `AnalyticsEngine`, `AppViewModel` (via `RustScores.steps`) | `stepsCounter` |
| Calories (whole-day) | `AnalyticsEngine` (via `RustScores.caloriesDay`) | `caloriesEstimateDay` |
| Rest (sleep performance) | `RestScorer.restFromDaily`, `AnalyticsEngine` | `restScore` |
| Sleep debt ledger | `RustScores.sleepDebtLedger`; `SleepModels` reads the types | `sleepDebtLedger` |
| Daily stress | `StressModel.build` (baseline assembly stays Kotlin) | `dailyStress` |
| Daytime stress | `DaytimeStress` (Kotlin bucketing, Rust scoring) | `daytimeStress` |
| HRV frequency domain (Lomb-Scargle LF/HF) | `HrvFreqDomain` (thin router) | `hrvFreqDomain` |

Steps / Calories(day) / Rest / SleepDebt / DaytimeStress / HRV-freq no longer keep a Kotlin oracle: the math
was DELETED once whoop-rs parity was proven, so each engine is now a thin router and its Kotlin test is an
FFI smoke test (Rust owns the exact numbers). Daily and daytime stress ADOPT the whoop-rs semantics (14-day
daily cold-start gate; last-hour peak on a tie), a deliberate Stress-screen behaviour change. `Baselines.kt`
is a mixed case: it delegates the per-night `update` to Rust but still carries its Kotlin `foldHistory`
recency-weighted path and the full config table.

### 2. Ported to whoop-rs, still running in Kotlin

whoop-rs implements these too, but the Android call site has not been cut over yet, so the Kotlin copy is
what runs today.

| Metric | Kotlin file | whoop-rs home | Why not yet |
|---|---|---|---|
| Workout detection | `AutoWorkoutDetector`, `WorkoutDetector` | `workout.rs` | Rust `WorkoutSession` drops `hrmax`/`hrmaxSource`; independent impls, not byte-parity. Needs reconcile. |
| Calories (per-bout) | `Calories` | `calories.rs` | only `WorkoutDetector` (Kotlin) calls it; moves with Workout. |
| IMU activity features | `ImuFeatureExtractor` | `imu_features.rs` | no UI surface (BLE deep-buffer diagnostic only). |

### 3. Kotlin-only (no whoop-rs port yet)

These engines have no Rust counterpart and are computed entirely in `com.noop.analytics`. They are the
downstream and secondary metrics NOOP layers on top of the core scores.

| Area | Kotlin engines |
|---|---|
| Illness early-warning | `IllnessSignalEngine`, `IllnessDistance` (Mahalanobis) |
| Vitality / Body Age (Gompertz) | `VitalityEngine`, `VitalBands` |
| Readiness (ACWR / Foster monotony) | `ReadinessEngine` |
| Recovery forecast | `RecoveryForecast`, `RecoveryDrivers` |
| Circadian phase (COSINOR) | `CircadianEngine` |
| Menstrual cycle phase | `CyclePhaseEngine` |
| Dose to response | `DoseResponseEngine`, `DoseResponsePriors` |
| Effect ranking (Cohen's d, Welch t) | `EffectRanker` |
| Resonance breathing / pacing | `ResonanceEngine`, `BreathPacer`, `HrDownPacer` |
| Nap / sedentary | `NapDetector`, `SedentaryDetector` |
| Caffeine decay, hydration | `CaffeineDecay`, `HydrationGoal` |
| Route / distance (Haversine) | `RouteMath` |
| 4.0 step estimation | `StepsEstimateEngine` |
| Activity cost, battery, digest | `ActivityCostEngine`, `BatteryEstimator`, `WeeklyDigest` |
| Metric fusion / arbitration | `FusionResolver`, `MetricArbitrationPolicy` |
| Live session, spot HRV, 5.0 health | `LiveSessionEngine`, `SpotHrvReading`, `V5HealthSignals` |

Plus orchestration and plumbing that is not itself an algorithm: `AnalyticsEngine` and
`IntelligenceEngine` (the day orchestrators), the `*Trace` twins, `ScoreConfidence`, the day-owner
resolvers, the sleep edit / dedup / reclip guards, and the various stores and prefs.

---

## The score confidence tier

Every score carries a **Solid / Building / Calibrating** label (`ScoreConfidence`) so a sparse day reads
truthfully. Calibrating means the baseline is not usable yet (or there is no in-bed data for Rest, no HR
window for Effort). Building means enough to show but thin. Solid means full inputs. A score that cannot be
computed honestly shows nothing.

## Data flow

```
WHOOP strap (BLE) ─┐
                   ├─► whoop-rs WhoopCodec (frame decode, uniffi) ─► Room (SQLite, 1 Hz streams)
WHOOP CSV export ──┤                                                      │
Apple Health XML ──┘                                                      │
                                                                         ▼
   importers copy per-day recovery / strain / sleep ──► DailyMetric (metrics cache)
                                                                         │
                            ┌─────────────────────────────────────────────┤
                            ▼                                             ▼
   IntelligenceEngine ─► AnalyticsEngine.analyzeDay                Repository.days ─► screens
   every ~15 min while connected; calls RustScores /              (charts, insights, compare)
   RustSleepStager for the core scores, runs the Kotlin-only
   engines for the rest; persists under the "<deviceId>-noop"
   source, MERGED UNDER imports (a WHOOP export still wins)
```

Imports always win. NOOP recompute fills the days the strap offloaded but no export covers, stored under the
`"-noop"` source and merged beneath imported rows.

## Conventions and honesty notes

- **One home for the math.** All core scoring is in whoop-rs; Android does not keep a second copy of a
  delegated formula. The still-Kotlin metrics in section 2 are the migration backlog, tracked against
  `whoop-rs/docs/algorithms.md`.
- **Approximate by design, non-clinical.** Charge, Effort, Rest, sleep stages, workout intensity, calories,
  and every derived signal are transparent approximations of published methods, not reproductions of any
  proprietary model and not medical advice.
- **Deterministic.** No randomness, no wall-clock inside the math, no DB or network access. Same inputs give
  same outputs, which keeps everything unit-testable against fixed vectors.
- **Cold-start honesty.** When a baseline is not trustworthy yet, the recovery path returns nothing rather
  than a fabricated number.
- **Not affiliated with WHOOP.** NOOP interoperates with hardware and exports you already own, on-device.
  Protocol decoding builds on community reverse-engineering of the WHOOP 4.0 and 5.0 protocols.

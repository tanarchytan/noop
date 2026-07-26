# Algorithms — what runs where, and what is left

The scoring math is **not** in this repo. It lives in [whoop-rs](https://github.com/tanarchytan/whoop-rs)
(`crates/physio-algo`), whose `docs/algorithms.md` is the per-formula source of truth with tests and
citations. This file answers the other question: **which Kotlin still owns math it should not**, and how
far the migration has got.

The rule: a number the user sees is computed in whoop-rs. Kotlin decides *when* to ask, *what to store*
and *how to word it* — never *what the number is*. `RustScores.kt` is the single seam; it holds one thin
adapter per engine and no arithmetic.

**State: every whoop-rs FFI export is called by the app. There is no unwired backlog** — what remains
below is Kotlin that still carries its own maths, not Rust waiting to be reached.

---

## Migrated — the app calls whoop-rs

`WorkoutDetector` (522 → 75 lines) · `VitalityEngine` · `CircadianEngine` · `ImuFeatureExtractor` ·
`WeeklyDigest` · `Baselines` · `HrvAnalyzer` · `HrvFreqDomain` · `SleepStageTotals` · `RecoveryScorer` ·
`NapDetector` · `StressOnsetDetector` · `DaytimeStress` · `SleepDebt` · `FitnessAgeEngine` ·
`V5HealthSignals`

Each was proven by its existing tests: they pin figures produced by the old Kotlin and still pass against
Rust. Two exports were **added** so the move lost nothing — `WorkoutSession.hrmax`/`hrmax_source`, and
`activity_series` + `smoothed_intensity` so the sedentary and nap reads share the workout detector's
motion spine.

## Correctly Kotlin — no maths to move

`BatteryEstimator` · `CyclePhaseEngine` · `DoseResponseEngine` · `IllnessSignalEngine` ·
`SedentaryDetector` · `VitalBands` · plus the orchestration layer (`AnalyticsEngine`,
`IntelligenceEngine`), source arbitration (`FusionResolver`, `DayOwnerResolver`,
`MetricArbitrationPolicy`), presentation (`CalibrationMilestones`, `ScoreConfidence`) and the pacers.

---

## Remaining — Kotlin that still owns maths

### Partial — half-crossed already, so cheapest to finish

| File | What is left |
|---|---|
| `StrainScorer` (188) | per-bout TRIMP; the daily figure already delegates |
| `SleepStager` (506) | `sessionHrvWindows`, `hypnogramMetrics`, `findPeaks`, `standardDeviation` |

### Statistical engines, untouched

| File | Algorithm | Why it matters |
|---|---|---|
| `RecoveryDrivers` (295) | per-driver marginal swing via the recovery logistic | **Highest risk.** It re-implements the model Rust owns; if they drift, the app explains a score using different maths than produced it |
| `ReadinessEngine` (388) | z-scores + ACWR acute:chronic + Foster monotony | feeds the Coupled screen |
| `EffectRanker` (323) | Welch t-test + Cohen's d | |
| `ActivityCostEngine` (307) | rest-baseline vs next-morning delta + bounce-back | |
| `RecoveryForecast` (242) | OLS slope + weighted tomorrow-Charge | |
| `StepsEstimateEngine` (242) | motion-weighted-median coefficient fit | 4.0 only |
| `ResonanceEngine` (218) | per-breath RSA amplitude | |
| `AutoWorkoutDetector` (210) | elevated-HR span growth + dip tolerance | |
| `CaffeineDecay` (165) | exponential half-life | |
| `IllnessDistance` (157) | Mahalanobis + Gauss-Jordan inversion | |
| `Analytics` (157) | legacy `IllnessWatch`, naive RMSSD, Tanaka ladder | secondary path; the gold path already uses Rust |

### Decode leaks — the clearest border violations

Byte decode belongs in Rust, and a hardware-verified twin already exists for each. Mostly rerouting.

| File | Lines | Rust twin |
|---|---|---|
| `protocol/Streams.kt` | 288 | skin-temp register → °C affine calibration |
| `protocol/Framing.kt` | 119 | `framing::decode` already does this reassembly |
| `protocol/Whoop5RawImu.kt` | 96 | `records::gen5::v21_imu` |
| `protocol/Crc.kt` | 42 | `crc::crc32_zlib`; now only synthesises test trailers |

---

## Suggested order

1. **`RecoveryDrivers`** — the only one where divergence is actively wrong rather than untidy.
2. **The four decode leaks as a batch** — 545 lines total, twins already exist and are hardware-verified.
3. The remaining engines, largest first, each behind its existing test.

## How to work an item

Port to Rust behind the existing Kotlin test, expose over uniffi, reroute the caller, **delete the Kotlin**,
then confirm the test still passes unchanged. A test that needed editing to pass means the port changed
behaviour — investigate before accepting it.

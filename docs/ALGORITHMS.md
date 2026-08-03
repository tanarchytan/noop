# Algorithms — what runs where, and what is left

The scoring math is **not** in this repo. It lives in [whoop-rs](https://github.com/tanarchytan/whoop-rs)
(`crates/physio-algo`), whose `docs/algorithms.md` is the per-formula source of truth with tests and
citations. This file answers the other question: **which Kotlin still owns math it should not**, and how
far the migration has got.

The rule: a number the user sees is computed in whoop-rs. Kotlin decides *when* to ask, *what to store*
and *how to word it* — never *what the number is*. `RustScores.kt` is the single seam; it holds one thin
adapter per engine and no arithmetic.

**State: five FFI exports have no Kotlin caller** (see *Exported but unreached*, below); the rest are
called. What remains after that is Kotlin still carrying its own maths. Read the counts off the audit,
never off this sentence.

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

**One thing `SleepStageTotals` did NOT take across:** `minutes()` and `dailyAggregate()` still SUM the
stored `stagesJSON` in Kotlin. That sum is the definition of a night's asleep/deep/REM totals, so it is a
decode and belongs in Rust; the audit still lists the file as a candidate for that reason. Until it moves,
the invariant it encodes is the store's: **the per-epoch segment array is the primitive and the
`DailyMetric` sleep columns are its aggregate.** A producer that writes the two from different arithmetic
puts two nights in the store under one date, and the screens split five to one over which is the night.

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
| `StrainScorer` (68) | per-bout TRIMP; the daily figure already delegates. Its gates, scale and denominator now read whoop-rs |
| `SleepStager` (198) | `sessionHrvWindows`, `hypnogramMetrics`. `findPeaks` moved to its parity test, `standardDeviation` deleted |

### Ported

| File | Algorithm | Where it went |
|---|---|---|
| `ChargeDrivers` (289) | per-driver marginal swing via the recovery logistic | `physio_algo::recovery_drivers::driver_rows`, over uniffi as `recovery_driver_rows`. The Kotlin file is gone; `ui/ChargeDriverRows.kt` holds only the wording, and `ChargeDriversGoldenTest` pins the output to the literals frozen off the Kotlin |

### Statistical engines, untouched

| File | Algorithm | Why it matters |
|---|---|---|
| `ReadinessEngine` (369) | z-scores + ACWR acute:chronic + Foster monotony | feeds the Coupled screen |
| `EffectRanker` (311) | Welch t-test + Cohen's d | |
| `ActivityCostEngine` (211) | rest-baseline vs next-morning delta + bounce-back | |
| `RecoveryForecast` (210) | OLS slope + weighted tomorrow-Charge | |
| `StepsEstimateEngine` (227) | motion-weighted-median coefficient fit | 4.0 only |
| `ResonanceEngine` (214) | per-breath RSA amplitude | |
| `AutoWorkoutDetector` (192) | elevated-HR span growth + dip tolerance | |
| `CaffeineDecay` (156) | exponential half-life | |
| `IllnessDistance` (151) | Mahalanobis + Gauss-Jordan inversion | |
| `Analytics` (143) | legacy `IllnessWatch`, Tanaka ladder; RMSSD and the means now delegate | secondary path; the gold path already uses Rust |

### Decode leaks — the clearest border violations

Byte decode belongs in Rust, and a hardware-verified twin already exists for each. Mostly rerouting.

| File | Lines | Rust twin |
|---|---|---|
| `protocol/Streams.kt` | 297 | skin-temp register → °C affine calibration |
| `protocol/Framing.kt` | 119 | `framing::decode` already does this reassembly |
| `protocol/Whoop5RawImu.kt` | 96 | `records::gen5::v21_imu` |
| `protocol/Crc.kt` | 42 | `crc::crc32_zlib`. **Gone from `main/`** — its only callers were fixture builders, which now use `java.util.zip.CRC32` through `test/…/protocol/TestCrc32.kt` |

---

## Exported but unreached — Rust waiting for a caller

The optical-quality surface. The strap flags its own bad optical seconds, the decoder stores that flag as
`v18Sample.opticalSignalPoor`, and whoop-rs owns the rule for what the flag disqualifies. Nothing in Kotlin
asks. The rule is already on the Rust side, so wiring these is adapter work and moves no arithmetic.

| Export | What it decides | What reads it today |
|---|---|---|
| `rr_beats_trusted` | whether one record's R-R beats may enter an `RrRun` | nothing; `RustScores.groupRuns` folds every beat |
| `ppg_hr_derate_poor` | drops flagged seconds to zero confidence before any reduction | nothing |
| `ppg_hr_aggregate` | confidence-weighted downsample, instead of a plain mean | nothing |
| `ppg_signal_check` | a span's Poor/Fair/Good verdict from its clean-second fraction | nothing |
| `ppg_check_cfg` | the four trust constants, so a caller cannot hold a stale copy | nothing |

**Do not wire these as a cleanup.** `rr_beats_trusted` changes every HRV figure already on screen by
dropping beats that currently count, and no measurement exists of how many. Measure the drop rate against
the stored `v18Sample` rows first, then wire it as instrumentation beside the incumbent, the way any
derived-signal change lands here.

---

**The counts above are re-derived** by `dev-notes/noop-tan/audit_kotlin_algorithms.py`, which reads each
one out of this table, compares it against the file, checks the row's named member is still there, and
checks every whoop-rs FFI export is reached from app Kotlin by a qualified `uniffi.whoop_ffi` call. It
audits `ANALYTICS.md` in the same pass. It prints how many rows still carry their maths and which have
left `main/`; read that, never a count restated here.

## Suggested order

1. **The three remaining decode leaks as a batch** — twins already exist and are hardware-verified.
2. The remaining engines, largest first, each behind its existing test.

## How to work an item

Port to Rust behind the existing Kotlin test, expose over uniffi, reroute the caller, **delete the Kotlin**,
then confirm the test still passes unchanged. A test that needed editing to pass means the port changed
behaviour — investigate before accepting it.

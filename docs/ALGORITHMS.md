# Algorithms — what runs where, and what is left

The scoring math is **not** in this repo. It lives in [whoop-rs](https://github.com/tanarchytan/whoop-rs)
(`crates/physio-algo`), whose `docs/algorithms.md` is the per-formula source of truth with tests and
citations. This file answers the other question: **which Kotlin still owns math it should not**, and how
far the migration has got.

The rule: a number the user sees is computed in whoop-rs. Kotlin decides *when* to ask, *what to store*
and *how to word it* — never *what the number is*. `RustScores.kt` is the single seam; it holds one thin
adapter per engine and no arithmetic.

**State: every FFI export has a Kotlin caller.** The five that did not were deleted rather than wired
(see *The optical-quality surface*, below). What remains is Kotlin still carrying its own maths. Read
the counts off the audit, never off this sentence.

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
`SedentaryDetector` · plus the orchestration layer (`AnalyticsEngine`,
`IntelligenceEngine`), source arbitration (`FusionResolver`, `DayOwnerResolver`,
`MetricArbitrationPolicy`), presentation (`CalibrationMilestones`, `ScoreConfidence`) and the pacers.

**`WHOOP_DAY_STRAIN_MAX = 21.0` is no longer Kotlin-held.** This file used to argue it had to be:
another vendor's display axis is not a property of our Effort score, so there was said to be nothing on
the Rust side to read it from. That argument was about who OWNS the number, and the border rule is about
where the arithmetic RUNS — `21.0 / maxStrain` is a conversion behind a displayed figure wherever the two
constants come from. It now lives in `physio_algo::strain` with both directions and the conversion itself,
and `StrainScorer` reads all three off `strain_cfg`. Before 2026-08-04 the ratio was written out five
times — `MockSeeder.STRAIN_SCALE`, `WhoopCsvImporter.DAY_STRAIN_TO_EFFORT_SCALE`,
`UnitFormatter.EFFORT_SCALE_FACTOR` and twice inline in `WhoopCsvExporter` — each with its own hardcoded
`100`, held together only by comments promising they were byte-identical, one of which cited a class that
does not exist in this fork. All five now read `StrainScorer`, and `EffortScaleOneOwnerTest` fails on a
sixth copy.

---

## Remaining — Kotlin that still owns maths

### Partial — half-crossed already, so cheapest to finish

| File | What is left |
|---|---|
| `StrainScorer` (85) | per-bout TRIMP; the daily figure already delegates. Its gates, scale, denominator and both Day Strain ↔ Effort ratios now read whoop-rs |
| `SleepStager` (198) | `sessionHrvWindows`, `hypnogramMetrics`. `findPeaks` moved to its parity test, `standardDeviation` deleted |

### Ported

| File | Algorithm | Where it went |
|---|---|---|
| `ChargeDrivers` (289) | per-driver marginal swing via the recovery logistic | `physio_algo::recovery_drivers::driver_rows`, over uniffi as `recovery_driver_rows`. The Kotlin file is gone; `ui/ChargeDriverRows.kt` holds only the wording, and `ChargeDriversGoldenTest` pins the output to the literals frozen off the Kotlin |
| `UnitFormatter` | `EFFORT_SCALE_FACTOR` and `effortValue` — the Day Strain display conversion | `physio_algo::strain::{WHOOP_DAY_STRAIN_MAX, WHOOP_DAY_STRAIN_TO_EFFORT, EFFORT_TO_WHOOP_DAY_STRAIN, effort_on_axis}`, over uniffi as `strain_cfg` + `effort_on_axis`. The Kotlin constant is gone; `effortValue` is a pass-through and `WeeklyDigestCard` reads `StrainScorer` |
| `HydrationGoal` (70 → 27) | the whole displayed daily goal: sex baselines, the Effort bump, the clamp and the 50 ml grid | `physio_algo::hydration`, over uniffi as `hydration_cfg` + `hydration_baseline_for_sex` + `hydration_effort_bump_ml` + `hydration_daily_goal_ml`. What is left in Kotlin is the quick-log ladder (30 / 237 / 500 ml), which is what a tap STORES, not a formula |
| `VitalBands` (126 → 100) | `band()`, the 2σ gate, the six typical-adult windows and the 20 °C absolute-vs-deviation split | `physio_algo::vital_bands`, over uniffi as `vital_band` + `vital_typical_range` + `skin_temp_is_absolute` + `skin_temp_history`. The enums stay in Kotlin to carry the wire strings a tile colours and captions; `WhoopHealthData` reads its six windows through `VitalBands.typicalRange` and holds no range literal |

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

## The optical-quality surface — measured, then deleted rather than wired

Five algorithm exports had no Kotlin caller: `rr_beats_trusted`, `ppg_hr_derate_poor`,
`ppg_hr_aggregate`, `ppg_signal_check` and `ppg_check_cfg`. **All five were deleted from the FFI on
2026-08-06**, taking the surface from 130 exports to 125, so every remaining export has a caller. The
algorithms stay in `physio-algo` with their tests: `hrv::rr_trusted` is still applied inside
`HrvReadiness::nightly_hrv`, and `ppg::aggregate`, `ppg::signal_check` and `ppg::derate_poor_seconds`
still carry their sensitivity harness. Only the doors were removed.

**Kotlin held no twin of any of them**, checked before deciding, because a Kotlin copy would have made
this the case that matters most under the border rule. `RustScores.groupRuns` folds every beat with no
filter; `RustScores.groupReports` passes `null` for the optical flag and says in its own comment that
null means unknown, never a claim the signal was good; and no file under `analytics/` or `ui/` computes
a PPG signal-quality verdict. There was nothing to delete on the Kotlin side.

| Export | Why it was deleted rather than given a door |
|---|---|
| `rr_beats_trusted` | Its only possible input is `v18Sample.opticalSignalPoor`, which is measured to be an amplitude sentinel rather than a beat-quality flag. Wiring it drops 71.7% of every HRV figure's beats |
| `ppg_hr_derate_poor` | Same flag, same falsified premise: it would zero the confidence of 70.66% of seconds on that sentinel |
| `ppg_hr_aggregate` | Fed but inert: 32,867 of the 32,966 stored PPG-HR seconds are shadowed by a measured `hrSample` and excluded by the anti-join, so only 99 reach a chart, and confidence weighting moves those buckets by at most 0.241 bpm |
| `ppg_signal_check` | Nothing shows or gates on PPG signal quality. Inventing a screen to justify an export is the wrong direction |
| `ppg_check_cfg` | It exists only to hand a caller the four constants behind `ppg_signal_check`. With no caller for the check, nothing needs the constants |

### The drop-rate measurement, 2026-08-04 — reproduced exactly, 2026-08-06

Run against the stored `v18Sample` rows in `whoop-data/own-data/strap-data/mine/noop-merged-with-drain-20260801.noopbak`,
which is **one UTC day (2026-08-01), two straps, 6,970 seconds** — a real cohort, not a large one. Every
figure below was re-derived from the same backup on 2026-08-06 and came back identical.

| | seconds | flagged `opticalSignalPoor` |
|---|---|---|
| `whoop-D5:B2:02:FA:38:29` | 6,794 | 4,749 (69.90%) |
| `whoop-E4:0B:03:2B:C6:21` | 176 | 176 (100%) |
| **both** | **6,970** | **4,925 (70.66%)** |

Joining `rrInterval` on `(deviceId, ts)`, wiring `rr_beats_trusted` as it stands would drop **4,008 of
5,593 beats (71.7%)**.

**The decode is sound, and the flag still does not mean what the export assumes.** Two checks, opposite
verdicts:

* *Sound*: 4,925 of 4,925 flagged seconds have `opticalAmpA` NULL and 0 of 2,045 unflagged ones do —
  perfect separation, so the bit is being read correctly and matches the decoder's own contract that a
  flagged second withholds its amplitude.
* *Not beat quality*: flagged seconds carry beats at the **same** rate as clean ones (0.814 vs 0.775
  beats/sec, flagged slightly higher) and carry a measured HR that is physiologically ordinary
  (mean 94.1 bpm, range 80-114, against 88.1 bpm and 50-113 on the clean seconds). The flag also toggles
  152 times across the day with a median flagged run of 15 seconds, so it is not one long dropout that
  could be dismissed as off-wrist.

`optical_signal_poor` is an **amplitude sentinel** — the front-end had no amplitude to report — and
whoop-rs's own doc for `rr_trusted` says exactly that before treating it as a beat-trust signal. Nothing
here shows the beats on those seconds are bad. A replacement signal has to come from something that
actually tracks beat quality, and until one exists there is no caller to write.

### Correction 2026-08-06 — the PPG stream is NOT unfed

The 2026-08-04 write-up added that "`ppgHrSample` is empty in both the merged backup and the 2026-07-31
debug database, so the v26 optical stream that would supply them has never landed." **Half of that is
false.** Measured directly:

| database | `ppgHrSample` | `ppgWaveformSample` |
|---|---|---|
| `noop-merged-with-drain-20260801.noopbak` | **32,966 rows, 3 devices** | **43,552 rows** |
| `noop-debug-db-20260731/noop_whoop.db` | 0 | 0 |
| `phone-20260728/noop_whoop.db` | 0 | 0 |

The stream has landed. What makes the reductions inert is a different fact, and a stronger one: the
anti-join in `WhoopDao.hrSamples`/`hrBuckets` admits a PPG second only when no measured `hrSample`
covers it, and **32,867 of the 32,966 are shadowed**. The 99 that survive fall into 11 sixty-second
buckets, where a confidence-weighted mean differs from the plain SQL mean by a median of 0.000 bpm and
at most 0.241 bpm — under the whole bpm the chart draws.

Two loose threads, recorded rather than fixed: 95 of the stored rows carry a confidence below
`ppg::MIN_CONFIDENCE` (minimum 0.258 against a 0.3 emission gate), so either they predate the gate or
the gate is not applied where it is documented; and `WhoopDao.hrBuckets` computes a displayed mean in
SQL, which is arithmetic behind a displayed number living in the app rather than in whoop-rs.

## One quantity, two producers — the nightly RMSSD series

`HrvReadiness::evaluate` is reached two ways, and they do not agree.

| Producer | Trust filter | Missing night |
|---|---|---|
| `whoopctl report` → `HrvReadiness::nightly_rmssd` | applies `rr_trusted` | absent from the vector entirely |
| Android → `WhoopRecoveryScreen` → `RustScores.hrvReadiness(days.map { it.avgHrv })` | none | absent, because `dailyMetric` has no row for an unworn day |

On the day measured above the CLI would compute its series from 28% of the beats the app uses. Same
function, same name, different number.

**Gaps are compressed, not honoured**, on both paths. `evaluate` documented `None` slots as missing nights
and then dropped them, so its windows count READINGS, not calendar days: `baseline7` is the last 7 nights
that produced a value, and after a fortnight off-wrist it spans a month. `cv_slope` then fits a trend over
index, treating those unevenly spaced nights as evenly spaced. This is the same defect the weekly sleep
charts carried until `calendarWindow` landed — there, a day with no data became a slot; here it still
vanishes.

Corrected in the whoop-rs doc comments and pinned by `evaluate_compresses_gaps_it_does_not_honour_them`,
which asserts that padding a series with `None` gives byte-identical output to omitting those nights.
**Behaviour deliberately unchanged**: it moves a displayed readiness tier, so it lands as instrumentation
beside the incumbent, the way any derived-signal change lands here.

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

# Analytics cutover scope — noop-tan Kotlin scoring → whoop-rs `physio-algo`

Phase 3 of the north-star: route every derived score through the Rust `physio-algo` core, then delete the
Kotlin math. noop-tan becomes a pure frontend (UI + BLE radio + Room storage + FFI). Written from two
read-only mappers (2026-07-17): the noop-tan analytics inventory and the physio-algo/FFI mapping.

## Status headline

*(Mapping-day snapshot, 2026-07-17 — for LIVE per-score progress see "Tier-1 cutover status" below: ALL 11
Tier-1 scores are now routed + Kotlin-deleted. Recovery is HARDENED (bit-for-bit parity pinned at machine
precision). HRV `avgHrv` is ROUTED through the windowed bucket-mean FFI (`RustScores.windowedAvgHrv` →
`hrv_windowed_avg`, `f187e5b`) bit-for-bit, after the `rmssd` divergence was FIXED in whoop-rs `0595d63` — the
Rust gap-aware RMSSD had used a >5 s time-gap split + >200 ms delta-skip; it now uses the Kotlin
range[300,2000] + Malik ectopic-reject + contiguous-pair-count clean (that mismatch was the 12.5% divergence
root cause). SpO2 is ROUTED through the raw-means FFI (`RustScores.nightlySpo2RawMeans` →
`nightly_spo2_raw_means`, `8a7681b`). The post-cutover de-cruft (dead FFI wrappers, stale doc-comments,
resp-rate range guard) has landed. ONE residual: with the experimental `deepHrvWindow` (#141) toggle ON,
`avgHrv` is still Kotlin-aggregated (tracked as a follow-up). whoop-rs was edited to land these doors
(`0595d63`/`f187e5b`/`8a7681b` — its first cross-tree commits, unpushed; coordinate with the parallel agent).
Closeout gate = GREEN: `:app:testFullDebugUnitTest` 0 failures with the real Rust DLL through JNA
(`RustHrvParityTest` 4 legs / skipped=0, `RustSpo2ParityTest` 2 legs).)*

At mapping time **only sleep *staging* crossed into Rust** (`SleepStager.kt:1197` → `RustSleepStager` →
`stageSleepV1/V2`). Everything else in `com.noop.analytics` (~30 metrics) was pure Kotlin — faithful ports of
the Swift `StrandAnalytics`. So this is a large, multi-round cutover, and it splits into three tiers by readiness.

`physio-algo` is pure/sans-IO (runtime dep = `whoop-protocol` only) — the algo core takes decoded records in
and returns values out. Persistence stays app-side (Room); the FFI hands scores back, noop-tan writes them.

## Tier-1 cutover status (2026-07-17)

The `RustScores` bridge (`android/…/analytics/RustScores.kt`, sibling of `RustSleepStager`) wraps the Tier-1
score FFI; each route lands as its own `refactor(analytics): route <score> …` commit that adds a
`Rust<Score>ParityTest` (Kotlin scorer AND FFI on the same fixture, asserting the STORED value bit-for-bit),
rewires the store seam to `RustScores`, and trims the now-dead Kotlin composite (keeping helpers a frontend
or Tier-2/3 consumer still reads). Verified via `:app:testFullDebugUnitTest` (buildRustHostDll → real Rust
through JNA).

| Tier-1 score | State | Notes |
|---|---|---|
| Sleep staging | **ROUTED + DELETED** | Baseline route (`RustSleepStager` → `stageSleepV1/V2`); Kotlin stagers gone. |
| Recovery / Charge | **ROUTED + DELETED + HARDENED** | `RustScores.recovery` at all 3 store sites (`AnalyticsEngine`, `IntelligenceEngine.recomputeRecovery`, `WatchRecovery`). `RecoveryScorer` keeps `zScore`/`band`/`recoveryIndexSlope`/`bankedNights` for the frontend `RecoveryDrivers` breakdown (ruled OUT), `RecoveryScorerTrace`, `CalibrationMilestones`, + the Resting-HR route. **Hardened (`0577f078`):** `RustRecoveryParityTest` now freezes a byte-identical copy of the deleted Kotlin composite and pins `RustScores.recovery` to it at machine precision across the full term matrix + null-semantics edges — observed maxErr 0.0 (bit-for-bit), replacing the loose 1e-6 numpy reference so no future whoop-rs edit can silently drift the stored value. |
| Day Strain / Effort | **ROUTED (store site) + parity-proven** | Daily store site → `RustScores.strain`. `StrainScorer.strain` itself STAYS (Tier-3 per-bout workout path: `WorkoutDetector`/`ManualWorkoutRescore`/live UI) — full delete waits on Tier-3 workout detection landing in physio-algo. |
| Resting HR | **ROUTED + DELETED** | `RustScores.sessionRestingHr`/`dailyRestingHr`; `RecoveryScorer.restingHR` removed. |
| Respiratory rate | **ROUTED + DELETED** | `RustScores.respRateFromRr`; `SleepStager.respRateFromRR` removed. |
| HR zones + time-in-zone | **ROUTED + DELETED** | `RustScores.hrZonesForAge`/`hrTimeInZone`; `HrZones` keeps only the direct `zones(maxHR)` builder. |
| Baevsky Stress Index | **ROUTED + DELETED + HARDENED** | `RustScores.stressIndex`/`stressComponents`; `StressIndex` keeps `MIN_BEATS` (documented gate the Rust door enforces internally — no production path reads it) + the `Components` shape. **Hardened (`0577f078`):** `RustStressParityTest` now feeds raw series with out-of-range dropouts + ectopic spikes and asserts the SI and every histogram component match bit-for-bit, proving the Rust `stress_index` reproduces the Kotlin `clean_rr` (range band + Malik ectopic) internally — so the bridge feeds raw rrMs with no pre-clean. |
| VO2max / Fitness Age | **ROUTED + DELETED** | `RustScores.vo2maxEstimate`/`fitnessAgeCompute`. `FitnessAgeEngine` keeps PA-index reconstruction (Tier-2 gap #5) + the UI readiness checklist. |
| PPG-HR (v26) | **ROUTED (decode cutover)** | `PpgHr.kt` deleted with the decode cutover; v26 HR comes from Rust decode. The `RustScores.ppgHr` re-derive wrapper (and the unused `hrvReadiness` wrapper) were pruned in the de-cruft pass (`8e60c117`) — no consumer read them; the FFI symbols remain if a re-derive is ever needed. |
| **HRV / RMSSD** | **ROUTED + DELETED** | `avgHrv` now routes through `RustScores.windowedAvgHrv` → the whoop-rs `hrv_windowed_avg` FFI (`f187e5b`), the windowed bucket-mean of `rmssdGapAware(cleanRRGapAware(bucket))` bit-for-bit — the now-deleted `SleepStager.sessionAvgHRV` twin (`aea515ee`). `RustHrvParityTest` is a live guard (`988ae184`) asserting the routed value == the frozen Kotlin reference across the golden night + local real/gold fixtures (4 legs / skipped=0). This landed only after the `rmssd` grain was aligned in whoop-rs `0595d63`: the Rust gap-aware RMSSD had used a >5 s time-gap split + >200 ms delta-skip; it now uses the Kotlin range[300,2000] + Malik ectopic-reject + contiguous-pair-count clean (that mismatch was the 12.5% divergence root cause). **ONE residual:** with the experimental `deepHrvWindow` (#141) toggle ON, `avgHrv` is still Kotlin-aggregated (follow-up). `HrvAnalyzer` keeps `cleanRR`/`rangeFilter`/`rejectEctopic`/`rmssdRaw`/`median` (freq-domain + frontend OUT-scope consumers). |
| **SpO2 (4.0 red/IR)** | **ROUTED + DELETED** | `AnalyticsEngine.nightlySpo2RawMeans` now routes through `RustScores.nightlySpo2RawMeans` → the whoop-rs `nightly_spo2_raw_means` FFI (`8a7681b`), which returns the integer-truncated raw red/IR ADC means `(red_i32, ir_i32)` with the SAME in-span filter + `sum/kept` truncation the Kotlin used — NOT the `spo2_from_paired` wellness percent (that was the door mismatch). Kotlin `nightlySpo2RawMeans` deleted (`1f531f76`); `RustSpo2ParityTest` guards it bit-for-bit (2 legs). |

### whoop-rs fix requests (parallel-agent lane) — 2 CLOSED, 1 open

Two of the three doors below LANDED in whoop-rs (its first cross-tree commits, unpushed — coordinate with the
parallel agent); the third stays open. whoop-rs is read+build-only from the noop-tan lane.

- **SpO2 — `nightly_spo2_raw_means` LANDED (task #3 CLOSED).** The old `spo2_from_paired` was the wrong door:
  it returned a single wellness percent (`110 − 25·R`, clamped 70–100), which can never equal the
  integer-truncated RAW red/IR ADC means Kotlin `nightlySpo2RawMeans` stored (`Pair<Int,Int>` → Room
  `DailyMetric.spo2Red`/`spo2Ir`), and one percent can't reconstruct the two ADC columns. whoop-rs `8a7681b`
  added `nightly_spo2_raw_means` returning `(red_mean_i32, ir_mean_i32)` with the SAME in-span filter +
  `sum/kept` integer truncation, so `AnalyticsEngine.nightlySpo2RawMeans` is now ROUTED + DELETED (`1f531f76`).
  Guard = `RustSpo2ParityTest` (bit-for-bit, 2 legs).
- **HRV — `hrv_windowed_avg` LANDED (task #9 CLOSED).** The whole-night `hrv_rmssd_gap_aware` was the WRONG
  grain for the stored `avgHrv`, which is a mean over 5-min buckets (see the Tier-1 HRV row). whoop-rs
  `f187e5b` added `hrv_windowed_avg` reproducing the windowed bucket-mean of
  `rmssdGapAware(cleanRRGapAware(bucket))` (≥2 contiguous-pair guard + index-contiguity mask), and `0595d63`
  first aligned the underlying gap-aware RMSSD clean to the Kotlin range[300,2000] + Malik ectopic path
  (fixing the 12.5% divergence). `avgHrv` is now ROUTED + DELETED (`SleepStager.sessionAvgHRV` gone,
  `aea515ee`); `HrvAnalyzer` keeps its `cleanRR`/`rangeFilter`/`rejectEctopic`/`rmssdRaw`/`median` + freq-domain
  helpers (Tier-3 / frontend). Residual: the experimental `deepHrvWindow` (#141) path is still
  Kotlin-aggregated (follow-up).
- **Recovery breakdown — per-term marginal-contribution door (task #8, OPEN — the sole remaining item).**
  `RecoveryDrivers.chargeDrivers`
  (`RecoveryDrivers.kt`) reimplements the routed logistic in Kotlin (same term set / append order / weights,
  neutralize-one-term to get each row's point swing) to render the "what shaped it" breakdown. It reads the
  surviving `RecoveryScorer.zScore`/`wHRV`/`wRHR`/… helpers, so it currently matches the Rust score by
  construction — but any future whoop-rs recovery edit would silently drift the breakdown from the stored
  score. **Fix:** expose a `recovery_drivers` FFI returning each term's z + marginal contribution from the
  SAME physio-algo composite, so the frontend renders Rust-sourced rows instead of re-deriving them. Not a
  blocker for the score (that's routed + hardened); it removes a parity-fragile reimplementation.
- **Recovery / Stress alignment — NONE outstanding.** The `0577f078` hardening pass PROVED alignment rather
  than surfacing gaps: recovery pins bit-for-bit (maxErr 0.0) and stress reproduces `clean_rr` internally
  (no bridge pre-clean). No whoop-rs change is needed for either family.

No Tier-1 FAIL(drift) outcomes — every routed score matched bit-for-bit on its fixture. All 11 Tier-1 scores
are now routed + Kotlin-deleted; the HRV bucket-mean and SpO2 raw-means doors both landed, so the only open
whoop-rs item is the Recovery-breakdown marginal-contribution FFI (task #8), which does not block a score.

## Tier 1 — DIRECT ROUTE (physio-algo has it AND it's FFI-exposed; just wire + prove parity)

| Kotlin score | Kotlin site | FFI fn (exists) |
|---|---|---|
| Recovery / Charge | `RecoveryScorer.recovery` (RecoveryScorer.kt:346) | `recovery_score` (+`recovery_band`, `recovery_index_slope`, `recovery_banked_nights`) |
| Day Strain | `StrainScorer.strain` (StrainScorer.kt:254) | `strain_score` (+`strain_default_denominator`, `StrainMethod`) |
| HRV / RMSSD (`avgHrv`) | `SleepStager.sessionAvgHRV` (routed+deleted) | `hrv_windowed_avg` (+`hrv_rmssd_gap_aware`) |
| Resting HR | `RecoveryScorer.restingHR` (:191) | `session_resting_hr`, `daily_resting_hr` |
| Sleep staging | already routed | `stage_sleep_v1` / `stage_sleep_v2` (DONE) |
| Respiratory rate | `SleepStager.respRateFromRR` (:1535) | `resp_rate_from_rr` |
| Baevsky Stress Index | `StressIndex.stressIndex` (StressIndex.kt:43) | `stress_index`, `stress_components` |
| SpO2 (4.0 red/IR) | `AnalyticsEngine.nightlySpo2RawMeans` (routed+deleted) | `nightly_spo2_raw_means` |
| PPG-HR | (v26 path) | `ppg_hr` |
| HR zones + time-in-zone | `HrZones` (HrZones.kt:88) | `hr_zones_for_age`, `hr_time_in_zone` |
| VO2max / Fitness Age | `FitnessAgeEngine.compute` (:109) | `vo2max_estimate`, `fitness_age_compute` (needs PA-index, see Tier 2) |

## Tier 2 — FFI-GAP-THEN-ROUTE (physio-algo has the logic; the FFI door is missing)

Close these FFI gaps (whoop-rs / parallel-agent lane) before the Kotlin can call them:
1. `hr_anomaly::HrWatch::evaluate` — zero FFI, `HistoryRecord`-typed → needs a plain-value adapter.
2. `calibration` unlock/full schedule — not surfaced; Kotlin can't gate metric display on it.
3. `stats::pearson` / `linear_fit` — per-strap calibration fit, not surfaced.
4. `spo2::rolling_reading` — multi-night smoothed readout + calibrating-night count (SpO2 % rollup, item 9).
5. `vo2max::physical_activity_index[_from_strain]` — FFI takes `pa_index` pre-computed; expose so Kotlin stops reimplementing it.
6. `strain::estimate_hrmax` / `fit_strain_denominator` — personalised HRmax + denominator calibration.
7. `hrv::nightly_rmssd` — per-day series builder (`HistoryRecord`-typed).

## Tier 3 — NEEDS physio-algo TO GROW (no Rust twin yet — parallel-agent build, then FFI, then route)

Core physiology (clearly algo → belongs in physio-algo):
- Sleep **detection** (in-bed span; `SleepStager.detectSleep` :914) — staging exists, detection does not.
- Sleep **efficiency** composite (`:1137`) + **Rest / sleep-performance** composite (`RestScorer` AnalyticsEngine.kt:975).
- **Skin-temp deviation** vs baseline (`AnalyticsEngine.kt:435`) — protocol decodes raw °C; the deviation math is derived.
- **Steps** wrap-aware delta (`StepsCounter.kt:34`), **Calories** Keytel/Harris-Benedict (`WorkoutDetector.Calories`), **Workout detection** + per-bout stats (`WorkoutDetector.detect` :272).
- **EWMA baselines** (`Baselines.kt:34`) — the shared mean+spread primitive every scorer leans on.
  (Vitality / Body-Age was listed here but is RULED OUT — see below; it's an insight composite, stays frontend.)

Behavioral / lifestyle / coaching layer — RULED OUT (David, 2026-07-17): stays frontend Kotlin, NOT a
physio-algo gap. The boundary is: a physiological metric derived from WHOOP sensor data or a configured
physiological algorithm (the raw kind, with proper physiology behind it) is IN; the behavioral / lifestyle /
coaching / insight layer is OUT — even when it reads WHOOP-derived values.
- STAYS FRONTEND (no Rust twin; parallel agent does NOT build these): Live-session coaching, Cycle phase
  (`CyclePhaseEngine`), Circadian (`CircadianEngine`), Recovery forecast, Sleep-debt ledger, Readiness
  (ACWR/monotony), Illness "Heads-Up" (journal-blended), Vitality / Body-Age (`VitalityEngine`),
  Activity-cost / Dose-response / Effect-rank (journal-driven), Caffeine decay, Nap labeling, Sedentary
  reminder, Smart-alarm (`SleepWindowWatcher`), Battery estimator, Resonance biofeedback.
- The ONE from that list that stays IN: Daytime/intraday Stress — it is a stress calculation off HR/R-R
  (physiological), so it routes with the Stress family. The stress-ONSET notification wrapper stays frontend.

## The acceptance spec — bit-for-bit parity PINS (the hard part of every route)

At switchover the Rust score MUST equal today's Kotlin value, or stored history / `.noopbak` / cross-platform
parity breaks. Verified pins:
1. **Sleep efficiency = 0–1 FRACTION** (`sleepSession`/`dailyMetric.efficiency`) — `MIGRATION_18_19` heals `>1.5 → /100`; iOS 0.92 vs Android 92 parity.
2. **rrInterval PK `(deviceId, ts, rrMs, seq)`** (`MIGRATION_17_18`) — RMSSD must not bias high; `seq` dedups equal same-second beats.
3. **Charge skin-temp term scale = 1.0 °C/z** (`RecoveryScorer.skinTempDevScale` :83) — a prior 0.5 diverged every user's Charge from macOS/iOS.
4. **`stagesJSON` key order = `end, stage, start`** (sorted-keys) — unstable order defeats the self-heal equality check.
5. **Strain scale pinned**: `maxStrain=100.0`, `strainDenominator=7201.0` (StrainScorer.kt:62,71).
6. **`ScoreConfidence` strings** `"calibrating"/"building"/"solid"` byte-identical to Swift (parity tests).
7. **PPG waveform BLOB** byte-identical to Swift `packPpgSamples` (`.noopbak` round-trip).
8. **FNV-1a cross-platform hash** (offset `0xcbf29ce484222325`, prime `0x100000001b3`, over UTF-16) for `.noopbak`-crossing dedup keys (`AppleHealthImporter.kt:777`).
9. Room `CREATE TABLE` column order == entity field order; every migration additive + `MigrationRoundTripTest` (DB version 21).

Persistence orchestrator (the single Room writer to keep app-side): `IntelligenceEngine.kt`
(`upsertDailyMetrics` :1011, `upsertSleepSessions` :1153, `upsertWorkouts` :1212, `upsertMetricSeries`).

## Division of labor

- **My lane (noop-tan):** for each ready score — build the FFI-consuming adapter, feed the Room-sourced
  inputs, prove the Rust output is bit-for-bit == the Kotlin output on real captures, then delete the Kotlin
  scorer. Keep `IntelligenceEngine` as the Room writer.
- **Parallel-agent lane (whoop-rs):** close the 7 Tier-2 FFI gaps; grow physio-algo for the Tier-3 scores;
  keep `physio-algo` pure. The FFI surface is the one-way contract between the lanes.

## Execution order (per score, mirrors the decode cutover)

1. Start with **Tier 1** (FFI ready) — one score at a time: adapter → parity-prove (bit-for-bit on real
   nights) → delete Kotlin. Recovery / Strain / HRV / Resting-HR are the highest-value first cut.
2. **Tier 2** as the parallel agent lands each FFI door.
3. **Tier 3** after physio-algo grows the twin (needs the behavioral-layer ruling first).
4. **Close-out gate** (definition-of-done): crate-border audit + resolve trespass; full dead/dup/refactor;
   quality + layout consistency; `cargo clippy` 0-warn (whoop-rs read-only, flag for the parallel agent).

Final gate throughout: BLE/stored-data behaviour needs a real device confirmation — parity on captures proves
the math, a night proves the pipeline.

## Adversarial verification (2026-07-17) — completeness + parity

Two read-only adversary passes stress-tested the tiers above. Both found real gaps; folding them in.

### Completeness — scores the tiers MISSED (add these)
- **StepsEstimateEngine** (`StepsEstimateEngine.kt:25` → `metricSeries "steps_est"`, `IntelligenceEngine.kt:1099/1107`) — a SECOND, 4.0 motion→steps regression, distinct from the v5 counter-delta. **Tier 3** (its per-strap fit overlaps Tier-2 `linear_fit`).
- **CyclePhaseEngine** (`CyclePhaseEngine.kt:20`, run via `V5HealthSignals.kt:79`) — menstrual-cycle phase off skin-temp/RHR/HRV z. Pure cross-platform math. **Tier 3.** (Distinct from `CircadianEngine`.)
- **HrvFreqDomain** (`HrvFreqDomain.kt:29`) — LF/HF/LF:HF/total-power (Lomb-Scargle), a distinct HRV score with NO FFI twin (physio-algo only has a resp-band DFT). **Tier 3 / new FFI door** — the Tier-1 HRV row covers only time-domain RMSSD.
- **SedentaryDetector** (`SedentaryDetector.kt` → `WhoopBleClient.kt:2391`) — inactivity-buzz. **Tier 3 behavioral.**
- **SleepWindowWatcher** (`com/noop/alarm/SleepWindowWatcher.kt:20` → `WhoopConnectionService.kt:117/361`) — smart-alarm light-sleep detector, scoring OUTSIDE `com.noop.analytics`. **Tier 3 behavioral / ruling.**
- **Tier 1 Recovery is UNDER-scoped** (two extra surfaces): `RecoveryDrivers.chargeDrivers` (`RecoveryDrivers.kt:69`) re-implements the logistic for the "what shaped it" breakdown → needs a per-term marginal-contribution FFI or it diverges; and `WatchRecovery.compute` (`IntelligenceEngine.kt:1326`) is a SECOND write site for `DailyMetric.recovery` (imported Apple/Health-Connect/Oura days). Route both, not just `RecoveryScorer.recovery`.
- Secondary (augment, not full routes): `SpotHrvReading`, `SleepStageTotals` (the staging→daily-column seam), `IllnessDistance` (Mahalanobis, beside the illness z), `AutoWorkoutDetector` (a 2nd, byte-parity workout detector), `ResonanceEngine` (RSA biofeedback).
- Flag-as-frontend (feed a gate but likely stay Kotlin): `BatteryEstimator` (depletion notif), `CaffeineDecay` (UI guide, but carries a parity contract).

### Parity — the 9 PINS are NECESSARY but NOT SUFFICIENT (17 more traps)
The dominant silent-drift classes the pin list did not name, each verified in-tree:
- **Rounding MODE differs per call-site:** `skinTempDevC` = half-to-EVEN (`round2`=`Math.rint`, AnalyticsEngine.kt:685/436); `sleep_performance` = half-UP (`roundToInt`, :1009); `strain` = half-UP (`roundToLong`, StrainScorer.kt:213). A single Rust `round()` (half-away) breaks whichever it doesn't match — and `skinTempDevC` feeds Charge, so a 0.01 °C tie flips `recovery` + breaks `.noopbak`.
- **Median tie-break is INCONSISTENT:** averaging-middles (`HrvAnalyzer.median` :430 → stored `avgHrv`/`respRateBpm`; `nightlySpo2PctMedian` :770 → stored `spo2Pct`) vs upper-middle (`medianBpm` :1043, `HrZones.medianInterval` :189). One canonical Rust `median()` will drift `respRateBpm`/`spo2Pct` on every even-count window.
- **`rmssdGapAware` divides by the contiguous-pair COUNT, not `n−1`** (`HrvAnalyzer.kt:225`), with an exact range→ectopic-drop + contiguity mask. This is the shipped `avgHrv` path → cascades into `recovery` (~55% of Charge). The FFI `hrv_rmssd_gap_aware` must reproduce this exactly.
- **Integer truncation vs round:** `spo2Red/Ir` (`(sum/kept).toInt()`, :731), `avgHr` (`avg.toInt()`), `durMin` (`/60L` floor), steps (half-up after `/stepTicksPerStep`, and `stepTicksPerStep` is EXCLUDED from `.noopbak` → restore recomputes with default 1.0).
- **Float accumulation ORDER** in stored REALs: `activeKcalEst` (thousands of left-to-right per-second adds, WorkoutDetector.kt:584), weighted `avgHrv` (:360). Pairwise/SIMD summation forks the last bit.
- **Winsorized-EWMA baselines** (`Baselines.kt`): `λ=1−0.5^(1/halfLife)`, `1.253*spread` z, winsorK=3/hardK=5, early-adapt window, clamp-then-EWMA with spread on the UNCLAMPED value, oldest-first fold. These drive HRV/RHR/skin baselines → z → stored `recovery`/`skinTempDevC`; the logistic is hypersensitive near cold-start.
- **Recovery specifics:** term insertion order + `logisticK=1.6`/`z0=−0.20` + omit-and-renormalize nulls; the sleep term is `rest/100` (the Rest composite), NOT raw efficiency. **RestScorer injects a NEUTRAL 0.5** consistency term when null (does not drop-and-renormalize, :1001).
- **A 2nd stored sorted-keys JSON** beyond stagesJSON: `EventRow.payloadJSON` (`StreamPersistence.kt:83`), Kotlin `Double.toString()` number formatting vs Swift `JSONEncoder`.

### The parity method (the real takeaway)
These micro-conventions (per-site rounding, median tie-break, division base, accumulation order) **cannot be reliably reasoned** — they must be **MEASURED**. So the cutover's parity gate is a **golden-value harness**, the analog of the decode cutover's 869/869: for each score, run the Kotlin scorer AND the Rust FFI on the SAME real captured nights and assert the STORED value is bit-identical (or within a documented, justified ULP). Build that harness FIRST; no Kotlin scorer is deleted until its Rust twin passes it on real data. This is what makes "delete the Kotlin math" safe.

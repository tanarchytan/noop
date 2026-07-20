# Border Audit: Kotlin → whoop-rs Engine Cutover

**Goal:** whoop-rs = ALL algorithm, decode, scoring. Kotlin = ONLY UI + BLE radio + Room persistence + send-policy + user-edited merge + caching. Every algorithm line in Kotlin is a border leak.

**Format:** each item shows `NOW` (current leak) → `SHOULD` (target state). Bold = correctness or data-integrity fault. Non-bold = border hygiene.

---

## 1. Sleep — detection

**NOW:** `AnalyticsEngine.analyzeDay` calls `SleepStager.detectSleep` (Kotlin, ~400 lines) to find in-bed spans. Then passes those spans to `RustSleepStager.stage()` for V2 staging. The Kotlin detector lacks: daytime resting-HR guard, off-wrist fraction gate, deeply-quiescent HR multiplier, sparse-gravity HR-vouched bridging, morning-stillness guard with band-state re-onset, night-continuation chain, 16h span cap. WHOOP 4 sparse-gravity nights get shredded into fragments.

**SHOULD:** `analyzeDay` calls `RustSleepStager.analyze()` (one FFI → detect + V2 stage + wake refinement + avg HRV + motion/sleep-state grids). Kotlin `SleepStager.kt` deleted entirely. One detector, one truth.

---

## 2. Sleep — V2 staging + wake refinement

**NOW:** `RustSleepStager.stage()` (FFI → `stage_refined`) is called per-span by `analyzeDay`. The Rust stager runs, but on Kotlin-detected spans (see #1). Wake refinement is in Rust. Good — but the input is wrong.

**SHOULD:** Already in whoop-rs. Fix → call `analyzeSleep` (unified entry) instead of `detectSleep` + `stage`. Delete `stage` FFI exposure or keep only for edit self-heal.

---

## 3. Sleep — main-night selection

**NOW:** Two parallel implementations of the same scored selector:
- `analyzeDay` → `SleepStageTotals.mainNightIndexByStages` (Kotlin mirror, ~70 lines)
- Edit/recompute seam → `ffiMainNightSelection` (Rust FFI)

Both are byte-identical formula. But `analyzeDay` runs on Kotlin-detected spans; the edit seam runs on stored session start/end. Same day can get different main-night picks.

**SHOULD:** `analyzeDay` calls `ffiMainNightSelection` (already exists). Delete the Kotlin mirror (`mainNightIndexByStages`, `mainNightGroupIndicesByStages`, `alignmentBonusMinutes`, `circularDistanceSec`, `targetMidsleepSec`, `coldStartAnchorSec` — ~200 lines). One selector, one truth.

---

## 4. Sleep — stage JSON encoding/decoding

**NOW:** Android stores stages as JSON `[{start,end,stage}]` strings in Room. `SleepStageTotals.minutes()` parses these. The JSON shape is an Android-only concern — whoop-rs returns `Vec<StageSegment>` and never sees JSON.

**SHOULD:** Keep as-is. JSON is a persistence concern, not an algorithm concern. `SleepStageTotals.minutes()` is a format decoder, not a scorer. The Rust segments → JSON encoding lives in `AnalyticsEngine.encodeStages()` — move that to the FFI bridge (Rust returns segments, Kotlin serializes for Room). Low priority.

---

## 5. Sleep — Rest composite

**NOW:** `AnalyticsEngine.computeRest` (Kotlin) computes a 0–100 Rest score from the main night's stages, efficiency, duration. Not in whoop-rs. No parity test. Charge scorer consumes it as a 15%-weighted term.

**SHOULD:** `Rest` computed in `whoop-rs/physio-algo` behind an FFI call. Input: main-night `Vec<StageSegment>`, efficiency, duration, prior-night Rest (for carry-over). Output: Rest score + trace. Kotlin only persists and displays.

---

## 6. Sleep — debt / need / consistency

**NOW:** `SleepDebt.kt` (Kotlin, ~150 lines) computes debt from a rolling 14-night window vs fixed 8h target. `SleepModels.kt` computes consistency. Both are pure math, no I/O. No parity test.

**SHOULD:** `SleepDebt` and consistency math in `whoop-rs/physio-algo`. FFI: input = array of (date, totalSleepMin), output = debt hours + consistency score + trend. Kotlin reads stored `DailyMetric` rows, passes them over FFI, displays the result.

---

## 7. Sleep — habitual midsleep

**NOW:** `SleepStageTotals.habitualMidsleepSec` is a Kotlin mirror of the Rust `habitual_midsleep_sec`. Both implement circular-mean-of-longest-block-per-day. The Rust version is called via FFI from `SleepStageTotals` for the edit seam; the Kotlin mirror exists for `analyzeDay`.

**SHOULD:** Delete the Kotlin mirror. `analyzeDay` calls `ffiHabitualMidsleepSec`. Already wired, just not called from the right path.

---

## 8. Sleep — historical block building

**NOW:** `SleepStageTotals.HistoryBlock` is built in Kotlin from stored `SleepSession` rows, passed to the Rust FFI for `habitual_midsleep_sec`. The history-building (selecting the right sessions, grouping by day) is a data query, not an algorithm — fine in Kotlin.

**SHOULD:** Keep as-is. History assembly is a Room query + sort, not an algorithm.

---

## 9. Effort / Strain

**NOW:** Already in whoop-rs (`physio-algo/src/strain.rs`). FFI called from `RustScores.strain`. Kotlin `StrainScorer.kt` still exists as a parity reference but is NOT the production path. Good.

**Remaining leaks:**
- **First-gap duration bug** (Effort audit #1): `sampleDurationMinutes` reads only first two timestamps. Fix in `whoop-rs/strain.rs`. Kotlin parity reference needs same fix or gets deleted.
- **No output clamp** (Effort audit #2): values above 100 possible. Fix in `whoop-rs/strain.rs`.
- **Confidence uses wrong HR count** (Effort audit #6): `ScoreConfidence.forEffort` counts night-window HR, not day-HR. Fix in `ScoreConfidence.kt` or move confidence to whoop-rs.
- **Live Effort uses Kotlin `StrainScorer`** (`TodayScreen.kt` → `StrainScorer.strain()`): Kotlin parity reference, NOT the Rust FFI. Call `RustScores.strain()` instead or delete live Effort.

**SHOULD:** Delete `StrainScorer.kt`. Route live Effort through `RustScores.strain()`. Fix first-gap integration bug in Rust. Move `ScoreConfidence` math to whoop-rs (input: HR row count + source type + coverage fractions → output: confidence tier).

---

## 10. Charge / Recovery

**NOW:** Already in whoop-rs (`physio-algo/src/recovery.rs`). FFI called from `RustScores.recovery`. Good.

**Remaining leaks:**
- **Skin temperature computed after Charge** (Charge audit #1): `IntelligenceEngine.kt` pass-2 ordering bug. Fix in Kotlin orchestration, not the Rust formula.
- **Raw-strap rescoring uses current+future nights in baselines** (Charge audit #2): `AnalyticsEngine` pass-2 builds one baseline from all recent nights, then re-scores every night against it. Fix in Kotlin orchestration: chronological baseline folding.
- **Recovery Index + Activity Balance dormant** (Charge audit #6): already in Rust, not wired. Wire or remove from FFI.
- **SpO2 documentation claims score effect but none exists** (Charge audit #5): doc fix only.

**SHOULD:** Keep Rust scorer as-is. Fix orchestration bugs in `IntelligenceEngine.kt`. Pass-2 must fold baselines chronologically (score day D from state through D-1, then fold D). Skin temp must be computed BEFORE Charge. Wire Recovery Index and Activity Balance or remove from exposed API.

---

## 11. HRV / RMSSD

**NOW:** `HrvAnalyzer.kt` (Kotlin, ~200 lines) computes RMSSD, SDNN, range-filter, median, gap-aware RMSSD. `RustScores.kt` calls whoop-rs `hrv.rs` for windowed avg HRV and HRV readiness. Two implementations.

**SHOULD:** Delete `HrvAnalyzer.kt`. All HRV math in `whoop-rs/hrv.rs` behind FFI. Kotlin only passes `Vec<(ts, rrMs)>` arrays and receives RMSSD/SDNN/median/avgHrv back.

---

## 12. Resting HR

**NOW:** `resting_hr.rs` in whoop-rs computes session resting HR (lowest 5-min rolling mean). Called from `detect.rs` during sleep detection. `AnalyticsEngine.kt` also computes nightly resting HR in Kotlin for the daily aggregate.

**SHOULD:** Route all resting HR computation through whoop-rs. FFI: input = `Vec<(ts, bpm)>`, output = resting HR bpm. Kotlin only stores the result.

---

## 13. Respiration rate

**NOW:** `respiratory_rate.rs` in whoop-rs. `AnalyticsEngine.kt` computes respiration in Kotlin from R-R intervals. Two implementations.

**SHOULD:** Route through whoop-rs FFI. Delete Kotlin respiration math.

---

## 14. Skin temperature

**NOW:** `AnalyticsEngine.kt` computes nightly skin temperature mean in Kotlin, with a WHOOP 4 raw-ADC→°C conversion (`Whoop4SkinTemp`) in the protocol layer. The baseline (EWMA center/spread) is in `Baselines.kt`. The deviation from baseline feeds Charge.

**SHOULD:** Skin temperature deviation in whoop-rs. FFI: input = nightly mean °C + baseline state → output = deviation °C + updated baseline. The WHOOP 4 raw→°C conversion is a codec concern (protocol layer), not an algorithm — keep in `whoop-protocol`. Delete the Kotlin `Baselines.kt` skin-temp section.

---

## 15. Baselines (EWMA center/spread for all metrics)

**NOW:** `Baselines.kt` (Kotlin, ~400 lines) manages EWMA baselines for HRV, RHR, respiration, skin temp, Effort. Half-lives, winsorization, cold-start acceleration, staleness, manual recalibration epochs. The Rust recovery scorer receives pre-computed baseline values; it does not manage baseline state.

**SHOULD:** Baseline state management in whoop-rs. FFI: input = array of (date, value) + recalibration epochs → output = `(baseline_center, baseline_spread, tier: Calibrating|Provisional|Trusted|Stale)`. Kotlin stores the baseline state serialized (JSON/blob in Room) and passes it back on next scoring pass. This is the hardest cutover — baseline state crosses scoring runs. But it's the right border: whoop-rs owns the math; Kotlin owns the persistence.

---

## 16. Stress — daily

**NOW:** `StressModel.kt` (Kotlin) computes daily 0–3 Stress from RHR + HRV z-scores vs prior-day baselines. Not in whoop-rs. Demo seeder uses a different linear formula. No parity test.

**SHOULD:** Daily Stress in `whoop-rs/physio-algo/src/stress.rs`. FFI: input = today (RHR, HRV) + prior daily rows → output = 0–3 score + baseline state + trace. Kotlin passes stored `DailyMetric` rows over FFI.

---

## 17. Stress — daytime

**NOW:** `DaytimeStress.kt` (Kotlin, ~300 lines) computes hourly 0–3 Stress from mean HR + RMSSD vs calm-hour quartiles. Not in whoop-rs. No exercise gate.

**SHOULD:** Daytime Stress in `whoop-rs/physio-algo`. FFI: input = array of (hour, meanHR, rmssd) → output = array of (hour, 0–3 score) + sustained-high flag.

---

## 18. Stress — Baevsky SI

**NOW:** `StressIndex.kt` (Kotlin) computes Baevsky Stress Index from R-R histogram. `RustScores.kt` also calls whoop-rs for it. Two implementations.

**SHOULD:** Route through whoop-rs FFI. Delete Kotlin `StressIndex.kt`.

---

## 19. Stress — live onset detector

**NOW:** `StressOnsetDetector.kt` (Kotlin, ~200 lines) detects live stress onset from rolling R-R + HR + motion. Exercise-gated. Not in whoop-rs.

**SHOULD:** Live onset detector in `whoop-rs/physio-algo`. Input: rolling R-R window + HR + recent motion flag → output: stress-onset event or suppression reason. Kotlin feeds live BLE notifications into a ring buffer, passes snapshots to FFI, displays the result.

---

## 20. Score confidence

**NOW:** `ScoreConfidence.kt` (Kotlin) computes Solid/Building/Calibrating tiers per metric from baseline maturity and data presence. Not all metrics use it (Effort passes wrong HR count; Charge ignores input completeness; Stress has no confidence at all).

**SHOULD:** Confidence math in whoop-rs. Per-metric FFI: input = baseline state + data coverage fractions → output = confidence tier + label. Kotlin only displays the result.

---

## 21. Calibration milestones

**NOW:** `CalibrationMilestones.kt` (Kotlin) computes overlay cards (4/7/14/30 day countdown). Pure math. Not in whoop-rs.

**SHOULD:** Milestone math in whoop-rs. FFI: input = days since first data + baseline tier → output = current milestone + days remaining. Kotlin displays the card.

---

## 22. HR zones

**NOW:** `hr_zones.rs` in whoop-rs computes HRmax + zone boundaries. Kotlin `Analytics.kt` has a mirror. Two implementations.

**SHOULD:** Delete Kotlin mirror. Route through whoop-rs FFI.

---

## 23. VO2max

**NOW:** `vo2max.rs` in whoop-rs. Kotlin does not have a mirror. Already clean.

**SHOULD:** Keep as-is. Already on the right side.

---

## 24. SpO2

**NOW:** `spo2.rs` in whoop-rs decodes v24 paired red/IR. `AnalyticsEngine.kt` stores the result. No consumer exists (Charge doesn't use it, Sleep tab doesn't show it).

**SHOULD:** Already clean on the algorithm side. Decide consumer policy: display-only, Charge penalty, or remove collection.

---

## 25. Clock correlation

**NOW:** `WhoopBleClient.kt` sends WHOOP 4 `SET_CLOCK`/`GET_CLOCK` opcodes 10/11. WHOOP 5/MG sends setter 10 only, getter 147 in Rust but not decoded. `Backfiller.kt` uses WHOOP 4 clock replies for timestamp correlation. WHOOP 5/MG clock correlation is disabled (records carry native Unix timestamps).

**SHOULD:** Clock is a protocol-layer concern, not an algorithm. Keep clock ops in `whoop-protocol` (Rust). `Backfiller` correlation logic stays in Kotlin (it's a data-plumbing concern, not a scoring concern). Unify: one clock path that branches on device family inside whoop-protocol. Kotlin only calls `set_clock`/`get_clock` FFI and receives a verified `ClockRef`.

---

## 26. R-R beat order preservation

**NOW:** Room query returns R-R sorted by `(ts, rrMs, seq)`. Same-second distinct intervals are sorted by magnitude, not physiological order. `RustSleepStager.groupRuns` preserves intra-second order for the FFI call, but the stored rows lose it.

**SHOULD:** Add a true beat-sequence column to the R-R schema. `whoop-protocol` decoder preserves emission order. `WhoopRepository` stores it. Room query returns emission-ordered rows. RMSSD (both whoop-rs and any remaining Kotlin HRV paths) reads the preserved order.

---

## 27. Auto workout detection

**NOW:** `AutoWorkoutDetector.kt` (Kotlin, ~300 lines) detects workouts from HR + motion patterns. Not in whoop-rs.

**SHOULD:** Detection logic in `whoop-rs/physio-algo`. FFI: input = HR stream + gravity stream + step cadence → output = workout start/end + type + confidence. Kotlin feeds streams, persists the result.

---

## 28. Steps estimation

**NOW:** `StepsEstimateEngine.kt` (Kotlin) estimates steps from gravity when no step-counter data exists. Not in whoop-rs.

**SHOULD:** Estimation logic in `whoop-rs/physio-algo`. FFI: input = gravity stream → output = estimated steps. Kotlin persists.

---

## 29. Circadian engine

**NOW:** `CircadianEngine.kt` (Kotlin) computes circadian phase from sleep midpoint history. Not in whoop-rs.

**SHOULD:** Circadian math in `whoop-rs/physio-algo`. FFI: input = sleep midpoint history → output = circadian phase + next-night recommendation.

---

## 30. Sedentary detection

**NOW:** `SedentaryDetector.kt` (Kotlin). Not in whoop-rs.

**SHOULD:** In `whoop-rs/physio-algo`. FFI in, result out.

---

## 31. Readiness engine

**NOW:** `ReadinessEngine.kt` (Kotlin). Not in whoop-rs.

**SHOULD:** In `whoop-rs/physio-algo`. FFI in, result out.

---

## 32. Recovery forecast

**NOW:** `RecoveryForecast.kt` (Kotlin). Not in whoop-rs.

**SHOULD:** In `whoop-rs/physio-algo`. FFI in, result out.

---

## 33. Vitality engine

**NOW:** `VitalityEngine.kt` (Kotlin). Not in whoop-rs.

**SHOULD:** In `whoop-rs/physio-algo`. FFI in, result out.

---

## 34. Weekly digest

**NOW:** `WeeklyDigest.kt` (Kotlin). Not in whoop-rs.

**SHOULD:** Digest math (averages, trends, best/worst day) in `whoop-rs/physio-algo`. FFI: input = array of daily metrics → output = digest values. Kotlin formats the text and renders the UI.

---

## 35. AI Coach

**NOW:** `AiCoach.kt` (Kotlin). Generates text insights from daily metric patterns. Not in whoop-rs.

**SHOULD:** Insight generation logic (threshold checks, pattern matching) in `whoop-rs/physio-algo`. FFI: input = daily metric snapshot + N-day trends → output = insight type + parameters. Kotlin formats the natural-language text from the insight type and renders it.

---

## 36. Import scoring (watch recovery / wearable imports)

**NOW:** `WatchRecovery.kt` (Kotlin) computes sparse Charge from imported daily HRV+RHR. Uses the Rust scorer via `RustScores.recovery()` but the orchestration (baseline building, day iteration) is in Kotlin.

**SHOULD:** Orchestration stays in Kotlin (it's a data query + iteration loop). The scoring call already goes through Rust. The aggregate-only HRV baseline building should move to whoop-rs (it's the same EWMA math as the raw-strap path). See #15 (Baselines).

---

## 37. StagerCache

**NOW:** `StagerCache.kt` (Kotlin) caches staged segments across recompute passes. Pure caching, no algorithm.

**SHOULD:** Keep as-is. Caching is a frontend/persistence concern.

---

## 38. Sleep edit guard / session dedup / stage healer / window reclip

**NOW:** `SleepEditGuard.kt`, `SleepSessionDedup.kt`, `SleepStageHealer.kt`, `SleepWindowReclip.kt` in Kotlin. These are data-plumbing and edit-reconciliation concerns, not algorithms.

**SHOULD:** Keep as-is. These handle user edits, dedup, and window management — frontend/data concerns.

---

## Implementation order

The order respects: fix correctness bugs first, then eliminate duplicate implementations, then move pure-math modules, then clean up.

| # | Item | Type | Impact |
|---|---|---|---|
| 1 | Unify sleep detection → `analyzeSleep` FFI | Correctness bug | Detection quality (sparse 4.0, off-wrist 5.0) |
| 2 | Unify main-night selection → Rust FFI | Correctness bug | Same-day disagreement |
| 3 | Fix Effort first-gap duration | Correctness bug | Whole-day TRIMP wrong |
| 4 | Fix Charge pass-2 skin ordering | Correctness bug | Stored Charge omits skin temp |
| 5 | Fix Charge pass-2 chronological baselines | Correctness bug | Self-referential baselines |
| 6 | Preserve R-R beat order | Correctness bug | RMSSD bias in HRV, Stress, Charge |
| 7 | Delete `SleepStager.kt` (Kotlin detector) | Border hygiene | ~400 lines dead |
| 8 | Delete `StrainScorer.kt` (Kotlin Effort mirror) | Border hygiene | ~200 lines, already routed through Rust |
| 9 | Delete `HrvAnalyzer.kt` (Kotlin HRV mirror) | Border hygiene | ~200 lines |
| 10 | Delete Kotlin main-night mirror in `SleepStageTotals` | Border hygiene | ~200 lines |
| 11 | Route Rest composite through whoop-rs | Border hygiene | Rest feeds Charge at 15% weight |
| 12 | Route daily Stress through whoop-rs | Border hygiene | ~150 lines |
| 13 | Route daytime Stress through whoop-rs | Border hygiene | ~300 lines |
| 14 | Route Baevsky SI through whoop-rs exclusively | Border hygiene | Delete `StressIndex.kt` |
| 15 | Route live Stress onset detector through whoop-rs | Border hygiene | ~200 lines |
| 16 | Route respiration rate through whoop-rs | Border hygiene | Delete Kotlin respiration math |
| 17 | Route resting HR through whoop-rs | Border hygiene | Already mostly there |
| 18 | Route sleep debt/need/consistency through whoop-rs | Border hygiene | ~150 lines |
| 19 | Route HR zones through whoop-rs exclusively | Border hygiene | Delete Kotlin mirror |
| 20 | Route ScoreConfidence through whoop-rs | Border hygiene | ~100 lines |
| 21 | Route calibration milestones through whoop-rs | Border hygiene | ~100 lines |
| 22 | Move Baselines state to whoop-rs | Architecture | ~400 lines, hardest cutover |
| 23 | Route auto workout detection through whoop-rs | Border hygiene | ~300 lines |
| 24 | Route steps estimation through whoop-rs | Border hygiene | ~150 lines |
| 25 | Route circadian engine through whoop-rs | Border hygiene | ~100 lines |
| 26 | Route sedentary detection through whoop-rs | Border hygiene | ~100 lines |
| 27 | Route readiness engine through whoop-rs | Border hygiene | ~100 lines |
| 28 | Route recovery forecast through whoop-rs | Border hygiene | ~100 lines |
| 29 | Route vitality engine through whoop-rs | Border hygiene | ~100 lines |
| 30 | Route weekly digest through whoop-rs | Border hygiene | ~100 lines |
| 31 | Route AI coach insight generation through whoop-rs | Border hygiene | ~100 lines |
| 32 | Clock: unify family-aware opcodes in whoop-protocol | Protocol hygiene | After hardware research |
| 33 | Delete remaining dead Kotlin algorithm files | Cleanup | `SleepStagerTrace`, etc. |
| 34 | Run full DREAMT benchmark after any staging change | Validation gate | Prevents staging regression |

### After cutover — what remains in Kotlin (correctly)

| Layer | What lives there |
|---|---|
| UI | Every Compose screen, chart, card, sheet |
| BLE | `WhoopBleClient`, `Backfiller`, `Puffin` framing, GATT operations |
| Room | `WhoopDao`, `WhoopRepository`, `WhoopDatabase`, all `@Entity` rows |
| Send-policy | Offload scheduling, backfill windows, write gating |
| User edits | Bed/wake editing, nap add/delete, manual workout entry |
| Merge | Imported-over-computed field coalescing |
| Caching | `StagerCache`, in-memory HR ring buffers |
| Import/export | WHOOP CSV, Apple Health, Health Connect, wearable imports |
| FFI bridge | `RustScores.kt`, `RustSleepStager.kt`, `RustAdapter.kt` (thin wrappers — no algorithm) |

Everything else = whoop-rs.

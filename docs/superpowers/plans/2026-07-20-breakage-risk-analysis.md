# Breakage Risk Analysis: Sleep, Stress, Recovery Border Cutover

**Scope:** 23 changes from `2026-07-20-border-audit-sleep-stress-recovery.md`. Each scored on: score impact (do stored numbers change?), data integrity (can existing data become unreadable?), app stability (can the app crash/hang?), test coverage (what guards exist?), hardware risk (can BLE/device behavior break?).

---

## Risk summary

| Risk | Count | Changes |
|---|---|---|
| **SCORE-CHANGING** — users see different numbers | 5 | #5 Effort integration, #6 skin order, #7 chronological baselines, #8 R-R order, #13 Stress baseline gate |
| **BORDER-HYGIENE** — swap call sites, delete mirrors, no score change | 13 | #1, #2, #3, #4, #9, #10, #11, #15, #16, #18, #19, #20, #21 |
| **FORMULA-EXTRACTION** — reverse-engineer then move, parity-gated | 2 | #12 Rest, #14 daytime Stress |
| **ARCHITECTURE** — persistence contract changes | 1 | #17 Baselines |
| **HARDWARE-GATED** — needs real-device validation | 1 | #1 (detection span changes) |

---

## Per-change analysis

### #1 — Route detection through `analyzeSleep` FFI

| Dimension | Assessment |
|---|---|
| Score impact | **YES, material.** The Rust detector has 6 gates the Kotlin detector lacks. WHOOP 4 sparse-gravity nights: Rust bridges fragments that Kotlin shreds → more detected sleep → higher totalSleepMin → different Rest → different Charge. WHOOP 5/MG off-wrist nights: Rust rejects fragments Kotlin accepts → less detected sleep. Daytime naps: Rust's resting-HR dip gate is stricter. |
| Data integrity | No. Stored data unchanged. Only NEW scoring passes produce different detected spans. Existing stored sessions (start/end, stagesJSON) are not modified. |
| App stability | Low. FFI already compiles and is called from the edit path. `analyzeSleep` is already wired in `RustSleepStager.analyze()`. |
| Test coverage | `RustSleepStagerParityTest` compares Kotlin vs Rust detection on synthetic fixtures. Does NOT cover: real WHOOP 4 sparse gravity, real off-wrist nights, multi-night chains. |
| Blast radius | Every downstream consumer of detected sleep: `totalSleepMin`, `efficiency`, `deepMin/remMin/lightMin`, `Rest`, `Charge` (via Rest term), `SleepDebt`, sleep tab hero, Intelligence > By Day. |
| Mitigation | (a) Capture 3 nights each from WHOOP 4, WHOOP 5, MG BEFORE the change. (b) Run old Kotlin detector + new Rust detector on each, diff the spans. (c) Run full scoring pipeline on both, diff the DailyMetrics. (d) Gate behind a flag, A/B compare on David's own data for 1 week before shipping. |

**Verdict: SAFE TO DO, but MUST be A/B-validated on real captured nights first.** The Rust detector is more correct — the risk is users seeing DIFFERENT (better) sleep detection, not worse. But the change is visible.

---

### #2 — Route main-night selection through `ffiMainNightSelection`

| Dimension | Assessment |
|---|---|
| Score impact | **Theoretically no, practically yes** (because of #1). The formula is byte-identical. But the input spans differ after #1, so the pick can change. Without #1, swapping the call site produces identical output. |
| Data integrity | No. Main-night index is not persisted — it's computed at read time. |
| App stability | Low. FFI already called from edit seam. |
| Test coverage | `MainNightConsistencyTest` covers synthetic cases. `MainSleepSpanTest`, `MainSleepReasonCopyTest` cover the Kotlin mirror's behavior. The Rust `mainnight.rs` has its own comprehensive test suite (cold-start, habitual, biphasic bridge, realistic nap-vs-night matrix, antipodal rejection). |
| Blast radius | Sleep tab hero pick, daily sleep aggregate, Intelligence > By Day. |
| Mitigation | Swap call, run `MainNightConsistencyTest`. If green, ship. |

**Verdict: SAFE.** Byte-identical formula. The Kotlin mirror tests pin the expected behavior; the Rust tests are more comprehensive.

---

### #3 — Delete `SleepStager.kt`

| Dimension | Assessment |
|---|---|
| Score impact | No — only dead after #1. |
| Data integrity | No. |
| App stability | Compile-check catches any missed caller. |
| Test coverage | Kotlin compiler. |
| Blast radius | None if #1 is complete. |

**Verdict: SAFE** after #1. Compile check is sufficient.

---

### #4 — Delete Kotlin main-night mirror

| Dimension | Assessment |
|---|---|
| Score impact | No — only dead after #2. |
| Data integrity | No. |
| App stability | Compile-check. |
| Test coverage | Kotlin compiler. |
| Blast radius | None if #2 is complete. |

**Verdict: SAFE** after #2.

---

### #5 — Fix Effort first-gap → per-interval integration

| Dimension | Assessment |
|---|---|
| Score impact | **YES, POTENTIALLY LARGE.** Modeled edge case: 28.7× inflation or 29.9× undercount. Real-world effect depends on cadence distribution — a uniform 30s cadence day sees zero change. A mixed live/offload/PPG day with a cadence transition sees the full error. David's data: 5.0 live at ~30s, offload at ~1s. The first two samples after offload determine the whole day's duration. A 1s-first-gap day gets ~30× undercounted Effort. |
| Data integrity | No. Formula change only. |
| App stability | Low. Pure math change in `strain.rs`. |
| Test coverage | `RustStrainParityTest` pins current (buggy) behavior. Will need updating. No test currently covers irregular cadence. |
| Blast radius | Daily Effort, workout Effort, Today hero, Activity Balance (dormant). |
| Mitigation | (a) Build per-interval integration behind a feature flag. (b) Run on David's multi-month HR database, compare old vs new Effort per day, report distribution. (c) Add synthetic tests for: first-gap outlier, cadence transition, long dropout, duplicate timestamps, unsorted input, mixed measured+PPG. (d) Ship only after confirming the distribution is reasonable (no days jumping from 4→80 or 80→4). |

**Verdict: SAFE to implement, but MUST be validated on real multi-month data before shipping.** The current behavior is a correctness bug — a single early cadence change controls the whole day. But users have been living with these numbers. The fix must not trade one wrong number for another wrong number.

---

### #6 — Fix Charge pass-2 skin ordering

| Dimension | Assessment |
|---|---|
| Score impact | **YES, BOUNDED.** Skin temp contributes at most 5% weight. For a night where skin temp deviation is 1°C, the term is −1.0 → renormalized contribution depends on other present terms. With all 5 terms present: skin is 5% of composite z. With only HRV+RHR+Rest+skin: skin is ~5.6%. Maximum Charge shift from enabling skin temp: ~2-3 points. |
| Data integrity | No. The `skinTempDevC` field is persisted on the row, just computed too late to enter scoring. |
| App stability | Low. Reorder 2 lines in `IntelligenceEngine.kt`. |
| Test coverage | No end-to-end test proves skin temp changes stored Charge. `ChargeEffortRestScoringTest` exists but may not cover this ordering. |
| Blast radius | Stored Charge, Today Recovery ring, Recovery drivers display ("What shaped it"), Charge trend. |
| Mitigation | (a) Add end-to-end test: pass-2 with only skin temp changed → prove Charge changes. (b) Run on multi-night data, compare before/after Charge distribution. (c) Validate skin temp deviation units on WHOOP 4 (raw ADC→°C) and WHOOP 5/MG (centidegrees) separately before enabling the term. |

**Verdict: SAFE, but gate skin-temp scoring behind a per-family validation flag.** WHOOP 5/MG skin temp units are direct centidegrees (confirmed). WHOOP 4 needs the ×0.04 scale factor verified. Enable only for validated families.

---

### #7 — Fix Charge pass-2 chronological baselines

| Dimension | Assessment |
|---|---|
| Score impact | **YES, POTENTIALLY LARGE for early-history days.** Current: all recent nights scored against one shared baseline. New: each night scored against only prior nights. Early in the window (nights 5-13 of a new strap), the baseline is built from fewer nights → larger spread → smaller z-scores → Charge closer to neutral (~58). Late in the window (night 20+), the baseline is mature and the difference from the shared-baseline approach shrinks. |
| Data integrity | No. Stored rows unchanged. Only rescoring changes values. |
| App stability | Low. Change the loop in `IntelligenceEngine.kt`. |
| Test coverage | `IntelligenceBaselineShadowTest` — name suggests it may cover this. Need to verify. |
| Blast radius | Every stored Charge value for every rescored day. The effect shrinks with baseline maturity. |
| Mitigation | (a) Run on David's multi-month data. Compare per-day Charge: shared-baseline vs chronological. Plot delta vs baseline night count. (b) Expectation: delta is largest at nights 5-10, shrinks to near-zero by night 20+. (c) If the delta distribution is wide at mature baselines (>2 points at 30+ nights), investigate before shipping. (d) Gate: only apply to new passes after a cutoff date, or rescore all history with a user-visible "recalibrated" flag. |

**Verdict: SAFE to implement, MUST validate on real multi-month data.** Correctness improvement — self-referential baselines dampen real deviations. But the score shift must be measured and communicated.

---

### #8 — Preserve R-R beat order

| Dimension | Assessment |
|---|---|
| Score impact | **YES, SMALL.** Only affects nights with same-second distinct R-R intervals. WHOOP 5/MG: records carry multiple beats per second (v18 `rr_count` + sequential intervals). Same-second intervals are currently sorted by magnitude (`ORDER BY ts, rrMs, seq`). After fix: preserved in emission order. RMSSD is order-sensitive — reordering same-second intervals changes successive-beat differences. Effect size: bounded by the number of same-second collisions × typical RR difference. For a typical night with ~5% same-second collisions and ~50ms typical adjacent difference: RMSSD shift < 1-2ms. |
| Data integrity | **YES, SCHEMA MIGRATION.** Adding a true beat-sequence column requires a Room migration. Existing stored R-R rows will have a default/zero sequence. The migration must be additive and reversible. |
| App stability | Low for the math. Medium for the migration — Room migrations must be tested. |
| Test coverage | `HrvGapAwareTest`, `HrvArtifactDensityTest`, `HrvGoldAgreementTest`, `HrvOpticalRobustnessTest`. |
| Blast radius | Nightly HRV → Charge (55% weight), daily Stress, daytime Stress (hourly RMSSD). Cascade: HRV shift → Charge shift → Recovery ring. |
| Mitigation | (a) Additive migration only — add `beatSeq` column, default 0. (b) New rows get proper sequence from `whoop-protocol` decoder. (c) Measure RMSSD difference on real captured nights before/after. (d) If the shift is >0.5ms RMSSD, gate behind a flag and communicate. |

**Verdict: SAFE, but measure RMSSD shift on real data first.** The schema migration is the riskiest part — test it.

---

### #9 — Route all HRV through whoop-rs FFI

| Dimension | Assessment |
|---|---|
| Score impact | **No.** Parity tests (`RustHrvParityTest`) prove byte-identical output. |
| Data integrity | No. |
| App stability | Low. Swap call site. |
| Test coverage | `RustHrvParityTest` + all HRV tests (`HrvAnalyzerGateTest`, `HrvAnalyzerRollingTest`, etc.) — these test the Kotlin implementation being deleted. After deletion, the parity test plus the Rust unit tests are the guard. |
| Blast radius | Every HRV consumer: nightly Charge, daily Stress, daytime Stress, HRV snapshot. |
| Mitigation | Keep `RustHrvParityTest` as the golden guard. Delete other HRV tests only after confirming Rust tests cover the same cases. |

**Verdict: SAFE.** Parity-gated.

---

### #10 — Route resting HR through whoop-rs FFI

| Dimension | Assessment |
|---|---|
| Score impact | **No.** Parity test exists (`RustRestingHrParityTest`). |
| Data integrity | No. |
| App stability | Low. |
| Test coverage | `RustRestingHrParityTest`. |
| Blast radius | Charge (20% weight via RHR term), daily Stress. |

**Verdict: SAFE.** Parity-gated.

---

### #11 — Route respiration through whoop-rs FFI

| Dimension | Assessment |
|---|---|
| Score impact | **No.** Parity test exists (`RustRespRateParityTest`). |
| Data integrity | No. |
| App stability | Low. |
| Test coverage | `RustRespRateParityTest`. |
| Blast radius | Charge (5% weight). |

**Verdict: SAFE.** Parity-gated.

---

### #12 — Extract Rest formula, move to whoop-rs

| Dimension | Assessment |
|---|---|
| Score impact | **No, if parity-tested.** Yes if extracted wrong. |
| Data integrity | No. |
| App stability | Low once parity-gated. |
| Test coverage | `ChargeEffortRestScoringTest`, `RestFreshnessTest`. Neither directly pins the Rest formula — they test UI behavior and Charge integration. |
| Blast radius | Charge (15% weight via Rest term). Wrong Rest → wrong Charge. |
| Mitigation | (a) Extract exact formula from `AnalyticsEngine.computeRest` first. Document it. (b) Build a focused `RestFormulaTest` that pins inputs→output for synthetic cases. (c) Implement in Rust, verify against the same synthetic cases. (d) Run on real multi-night data, compare old Kotlin Rest vs new Rust Rest. (e) Only cut over when every night matches to 2 decimal places. |

**Verdict: SAFE with the parity gate.** The formula extraction is the hard part — the Kotlin code is deeply nested. Must be done carefully.

---

### #13 — Move daily Stress to whoop-rs, add baseline gate

| Dimension | Assessment |
|---|---|
| Score impact | **YES for cold-start users.** With <14 baseline days, current Kotlin scores anyway (often 1.5 Medium from zeroed z-scores). New: returns null/Calibrating. For established users (>30 days), no change — the formula is identical, just running in Rust. |
| Data integrity | No — but persistence contract changes. Currently daily Stress is NOT persisted (derived at read time). New: should be persisted into `metricSeries("my-whoop", "stress")` so Trends Report can show it. |
| App stability | Low. New FFI surface. |
| Test coverage | `RustStressParityTest` (formula parity). `StressModelTest` (Kotlin behavior, deleted after). |
| Blast radius | Today Stress card, Stress detail screen, Trends Report (if persisted). |
| Mitigation | (a) Parity-test without gate first. (b) Add baseline gate. (c) Persist result. (d) Compare before/after on multi-month data: check how many days change from scored→unscored. |

**Verdict: SAFE.** The gate is correct — scoring without a baseline produces noise. But cold-start users will see "Calibrating" instead of a number. Communicate this.

---

### #14 — Move daytime Stress to whoop-rs, add exercise gate

| Dimension | Assessment |
|---|---|
| Score impact | **YES for exercise hours.** Hours containing workouts currently score as High Stress. After the exercise gate, those hours become unscored or "Suppressed — exercise detected." The total number of scored hours may drop. |
| Data integrity | No. Daytime Stress is computed at read time, not persisted. |
| App stability | Low. New FFI surface. |
| Test coverage | `DaytimeStressTest`. |
| Blast radius | Daytime Stress timeline on Stress screen. |
| Mitigation | (a) Parity-test without gate. (b) Add gate. (c) Run on real days with known workouts, verify exercise hours are gated. |

**Verdict: SAFE.** The exercise gate fixes a correctness bug (exercise labeled as Stress).

---

### #15 — Move Baevsky SI to whoop-rs exclusively

| Dimension | Assessment |
|---|---|
| Score impact | **No.** Already in Rust. |
| Data integrity | No. |
| App stability | Low. Delete Kotlin mirror, route through FFI. |
| Test coverage | `RustStressParityTest`. |
| Blast radius | Advanced Stress Index display. |

**Verdict: SAFE.** Already duplicated — just delete the Kotlin copy.

---

### #16 — Move onset detector to whoop-rs

| Dimension | Assessment |
|---|---|
| Score impact | **No.** Opt-in feature, exercise-gated. |
| Data integrity | No. |
| App stability | Medium. This is the first LIVE-DATA FFI call. The onset detector consumes in-memory ring buffers from BLE notifications. The FFI must be called on each new R-R sample arrival — latency matters. If the FFI call is slow (>5ms), it could block the BLE notification thread. |
| Test coverage | `StressOnsetDetectorTest`. |
| Blast radius | Live stress nudge feature (opt-in, Experimental tab). |
| Mitigation | (a) Measure FFI call latency on-device before shipping. (b) If >5ms, run on a background coroutine with a ring-buffer snapshot. (c) Gate behind the existing Experimental toggle. |

**Verdict: SAFE with latency measurement.** Live-data FFI is new territory — verify it doesn't block the BLE thread.

---

### #17 — Move Baselines EWMA math to whoop-rs (Option B: per-value FFI)

| Dimension | Assessment |
|---|---|
| Score impact | **No, if byte-identical.** Option B keeps Kotlin iterating days; whoop-rs only does the per-value EWMA update math. The iteration order (chronological) is unchanged. |
| Data integrity | **YES, PERSISTENCE CONTRACT.** Currently baseline state is transient — reconstructed from DailyMetric rows on each pass. After: baseline state could be persisted as a JSON blob from whoop-rs. If the serialization format changes between app versions, old blobs become unreadable → baselines reset → scores jump. |
| App stability | Medium. New FFI surface called N times per pass (once per metric per day). Must be fast — a 90-day window × 5 metrics = 450 FFI calls per pass. |
| Test coverage | `BaselineSeedingTest`, `IntelligenceBaselineShadowTest`, `HrvBaselineRecalibrationTest`. |
| Blast radius | EVERY score that uses baselines: Charge, daily Stress, skin temp deviation. A baseline bug poisons everything. |
| Mitigation | (a) Start with Option B (per-value FFI, Kotlin keeps iteration). (b) Add comprehensive round-trip tests: seed baseline, feed values, serialize, deserialize, feed more values, verify output matches pure-Kotlin baseline. (c) Measure FFI call overhead — if 450 calls take >100ms, batch them. (d) Version the serialization format. (e) Gate behind a flag, A/B compare on multi-month data. |

**Verdict: SAFE with Option B, but REQUIRES the most testing of any change.** This is the hardest cutover. Do it LAST, after everything else is stable.

---

### #18-19 — Move confidence + milestones to whoop-rs

| Dimension | Assessment |
|---|---|
| Score impact | **No.** Display-only labels. |
| Data integrity | No. |
| App stability | Low. |
| Test coverage | UI-level tests for calibration states. |
| Blast radius | Solid/Building/Calibrating labels, calibration milestone cards. |

**Verdict: SAFE.** Display-only. Wrong labels are annoying but don't affect scores.

---

### #20 — Delete dead Kotlin files

| Dimension | Assessment |
|---|---|
| Score impact | No. |
| Data integrity | No. |
| App stability | Compile-check. |
| Test coverage | Compiler. |

**Verdict: SAFE.** Mechanical cleanup after all other changes.

---

### #21 — Route sleep debt through whoop-rs

| Dimension | Assessment |
|---|---|
| Score impact | **No.** Formula is simple arithmetic over stored totals. |
| Data integrity | No. |
| App stability | Low. |
| Test coverage | `SleepDebtTest`. |
| Blast radius | Sleep debt ledger card. |

**Verdict: SAFE.** Parity-test and swap.

---

### #22-23 — Run test suite + on-device smoke

These are validation gates, not changes. No risk.

---

## Risk matrix (ordered by risk)

| # | Change | Score change | Data integrity | App stability | Overall |
|---|---|---|---|---|---|
| **17** | Baselines EWMA → whoop-rs | None (if correct) | **Persistence contract** | Medium (N FFI calls) | **HIGHEST** |
| **5** | Effort per-interval integration | **Large, variable** | None | Low | **HIGH** |
| **7** | Charge chronological baselines | **Moderate, early history** | None | Low | **HIGH** |
| **1** | Sleep detection → analyzeSleep | **Material, per-generation** | None | Low | **MEDIUM** |
| **6** | Charge skin ordering | **Small, bounded (~2-3pt)** | None | Low | **MEDIUM** |
| **8** | R-R beat order | **Small (<2ms RMSSD)** | **Schema migration** | Medium | **MEDIUM** |
| **13** | Daily Stress baseline gate | **Cold-start only** | None | Low | **MEDIUM** |
| **12** | Rest formula extraction | None (if parity-gated) | None | Low | **MEDIUM** |
| **14** | Daytime Stress exercise gate | **Exercise hours only** | None | Low | **MEDIUM** |
| **16** | Onset detector → whoop-rs | None | None | **Live FFI latency** | **MEDIUM** |
| **2** | Main-night selection FFI | None | None | Low | LOW |
| **9-11** | HRV/RHR/respiration FFI | None | None | Low | LOW |
| **15** | Baevsky SI dedup | None | None | Low | LOW |
| **18-19** | Confidence + milestones | None | None | Low | LOW |
| **3-4** | Delete SleepStager + main-night mirror | None | None | Low | LOW |
| **20** | Delete dead files | None | None | Low | LOW |
| **21** | Sleep debt → whoop-rs | None | None | Low | LOW |

---

## What CANNOT be tested without hardware

| Validation | Required for |
|---|---|
| WHOOP 4 sparse-gravity detection quality | #1 |
| WHOOP 5/MG off-wrist detection gating | #1 |
| WHOOP 5/MG skin temp deviation units | #6 |
| Live FFI call latency on BLE thread | #16 |
| Multi-night score stability (3 straps × 5 nights) | #1, #6, #7 |
| Imported WHOOP Recovery merge behavior after rescore | #7 |
| .noopbak round-trip after schema migration | #8 |

---

## Safe implementation order (risk-ordered)

Group by risk, not by subsystem. Low-risk changes first build confidence.

**Phase 0 — zero-risk parity swaps (do immediately):**
- #9 HRV → whoop-rs FFI
- #10 Resting HR → whoop-rs FFI
- #11 Respiration → whoop-rs FFI
- #15 Baevsky SI dedup
- #20 Delete dead files (after above)
- #21 Sleep debt → whoop-rs
- #18-19 Confidence + milestones → whoop-rs

**Phase 1 — formula extraction with parity gate:**
- #12 Rest formula (extract → parity-test → cut over)
- #2 Main-night selection FFI (parity-gated)
- #4 Delete Kotlin main-night mirror

**Phase 2 — score-changing fixes with A/B validation:**
- #5 Effort per-interval integration (validate on multi-month data)
- #13 Daily Stress baseline gate (validate on multi-month data)
- #14 Daytime Stress exercise gate (validate on real workout days)
- #6 Charge skin ordering (validate per-generation)
- #7 Charge chronological baselines (validate on multi-month data)
- #8 R-R beat order (measure RMSSD shift first)

**Phase 3 — detection unification (hardware-gated):**
- #1 Sleep detection → `analyzeSleep` FFI (capture real nights first)
- #3 Delete `SleepStager.kt`

**Phase 4 — live FFI + persistence contract (highest risk, do last):**
- #16 Onset detector → whoop-rs (measure FFI latency on-device)
- #17 Baselines EWMA → whoop-rs Option B (comprehensive round-trip tests)

**Phase 5 — validation gates:**
- #22 Run full test suite + DREAMT benchmark
- #23 On-device smoke (5.0 + MG + 4.0, one full night each)

---

## Rollback strategy

Every score-changing fix (#5, #6, #7, #8, #13, #14) must ship behind a feature flag that can be toggled OFF without a new APK. The flag controls which code path runs; the stored data is unchanged either way. If a user reports regressed scores, toggle the flag back to the old path. Once validated for 2+ weeks across all three generations, remove the flag.

Baselines (#17) is the only change that CANNOT be trivially rolled back if the persistence format changes. Do NOT change the persistence format in the first release — keep Option B where Kotlin iterates and whoop-rs only computes per-value updates. The stored `DailyMetric` rows are the baseline source of truth, unchanged.

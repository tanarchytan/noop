# Raw-Data Validation Plan: Fact-Check Every Border Change

**Goal:** Before shipping any score-changing fix, run it against real data and prove: (a) the fix is correct, (b) no DREAMT regression, (c) the score shift on real WHOOP data is measured and bounded.

---

## Validation inventory — what we have

| Dataset | Size | What it validates | Format |
|---|---|---|---|
| DREAMT v2.2.0 fixtures | n=100 | V2 staging accuracy (4-class kappa vs PSG gold) | `sleep-benchmark/fixtures_multi/dreamt/` — per-subject gravity.csv, hr.csv, rr.csv, truth.csv, meta.txt |
| AAUWSS fixtures | n=13 | Cross-validation (E4 + ECG gold) | `sleep-benchmark/fixtures_multi/aauwss/` |
| whoop-rs frozen golden | 1 synthetic night | Staging byte-identical regression | `golden_tests.rs` — pinned hypnogram |
| whoop-rs detect tests | 30+ synthetic cases | Detection gate correctness | `detect.rs` tests — daytime guard, off-wrist, sparse, morning-stillness, night chain |
| whoop-rs mainnight tests | 15+ synthetic cases | Main-night selection correctness | `mainnight.rs` tests — cold-start, habitual, biphasic, nap-vs-night matrix |
| Kotlin parity tests | 12 test classes | Rust ↔ Kotlin byte-identical output | `Rust*ParityTest.kt` — strain, recovery, HRV, stress, RHR, resp, sleep, SpO2, HR zones, VO2max |
| rr-real-fixture.json | 8 nights (David's 5.0) | R-R ordering, RMSSD computation | `whoop-data/harnesses/rr-real-fixture.json` |
| recovery_cases.json | Synthetic | Recovery formula parity | `whoop-data/harnesses/agreement-fixtures/recovery_cases.json` |
| aauwss_optical_vs_gold.json | 13 subjects | HRV agreement vs ECG gold | `whoop-data/harnesses/agreement-fixtures/` |
| hrv_freq_cases.json | Synthetic | Frequency-domain HRV parity | `whoop-data/harnesses/agreement-fixtures/` |
| whoop5_real_rr.json | David's 5.0 R-R | Real HRV vs gold agreement | `whoop-data/harnesses/agreement-fixtures/` |
| strap fixtures | killa 5.0 (14 nights), whoop4 (3 nights), David 5.0 (10 matched) | Detection end-to-end | `sleep-benchmark/fixtures_multi/strap/` |
| noop-raw-capture JSONL | 1 night, full dump | Detection + staging on real WHOOP 5.0 data | `whoop-data/own-data/strap-data/mine/user-0001/5.0/raw-captures/` |
| noop-export zip | Multi-day export | Multi-day scoring stability | `whoop-data/own-data/my_whoop_data_2026_07_16.zip` |

---

## Gap: no Rust→DREAMT pipeline

The DREAMT benchmark runs against a **Python port** (`v2_port.py`, 517 lines), not the actual Rust code. The Python port was validated to match the shipped Kotlin V2 on AAUWSS (kappa 0.403 vs doc 0.415, within rounding). But the current production code is the **Rust implementation** in `whoop-rs/crates/physio-algo/src/sleep/v2.rs`, which was independently ported and validated against Kotlin parity tests.

**Risk:** a Python-port benchmark pass doesn't guarantee a Rust-implementation pass. The Python port could have drifted from the Rust code during the border refactor.

**Fix needed:** build a `whoop-rs` binary that reads the fixture CSV format and scores against DREAMT gold, producing a kappa number directly from the production code.

---

## Per-change validation plan

### Change #1 — Sleep detection → `analyzeSleep` FFI

**What changes:** Kotlin-detected spans → Rust-detected spans. 6 new gates active.

**Validation data:**
- `sleep-benchmark/fixtures_multi/strap/` — 14 killa 5.0 nights + whoop4 nights + David 5.0 matched nights
- `noop-raw-capture-260713-1728.jsonl` — one full-frame WHOOP 5.0 night
- `whoop-rs detect.rs` tests (30 synthetic cases)

**Validation steps:**

1. **NOW (baseline):** Run Rust detect tests → all 30 green.
   ```bash
   cd whoop-rs && cargo test -p physio-algo "sleep::detect::"  # 30 tests
   ```

2. **NOW (baseline):** Score strap fixtures with current Kotlin detector. Run `build_strap_dataset.py --detect-only` to get detected spans per fixture. Save as `baseline_detection.json`.

3. **AFTER change:** Build a Rust binary that reads the same fixture CSVs, runs `detect::detect_sessions`, writes spans. Compare span-by-span against baseline.

4. **Acceptance criteria:**
   - WHOOP 5.0 dense nights: ≥90% span overlap (Jaccard) with Kotlin detector
   - WHOOP 4 sparse nights: Rust detector MUST produce fewer fragments than Kotlin (it bridges correctly)
   - Off-wrist nights: Rust detector MUST reject spans Kotlin incorrectly accepted
   - No crash on empty inputs, HR-only inputs, gravity-only inputs

5. **DREAMT:** detection quality on DREAMT is measured by the downstream staging kappa. Detection that misses sleep → lower kappa. Detection that over-includes wake → higher WASO → also lower kappa. The detection change is CORRECT if kappa doesn't drop.

**Can run now:** Rust detect tests (30, green). Kotlin parity test (`RustSleepStagerParityTest`).
**Must build:** Rust fixture-reader binary + compare harness.
**Must capture:** More WHOOP 4.0 raw nights (only 3 in fixtures).

---

### Change #5 — Effort per-interval integration

**What changes:** `sampleDurationMinutes` uses all intervals, not just the first two timestamps.

**Validation data:** David's multi-month WHOOP export (`my_whoop_data_2026_07_16.zip`) + stored HR rows in noop-tan Room database.

**Validation steps:**

1. **NOW (baseline):** Export David's daily Effort values for the last 90 days from the noop-tan database. These are the current (buggy) values the user sees.

2. **AFTER change:** Recompute Effort with per-interval integration on the same HR data. Compare day-by-day:
   - Days with uniform cadence: delta should be near-zero
   - Days with cadence transition (live→offload): delta measured
   - Max delta, median delta, days where delta >5 points
   - Days where old value was >100: does new value fix this?

3. **Acceptance criteria:**
   - No day where delta >20 points without a clear cadence-explanation
   - No day where old Effort was reasonable (4-80) and new Effort is nonsensical (<1 or >150)
   - Per-interval median cadence (not first-gap) becomes the default
   - Synthetic edge-case tests pass: first-gap outlier, cadence transition, long dropout, duplicate timestamps, unsorted input

4. **Synthetic tests** (add to `strain.rs`):
   - 600 samples, all 1s apart → TRIMP unchanged vs current
   - 600 samples, first gap 30s, rest 1s → old inflates, new correct
   - 600 samples, first gap 1s, rest 30s → old undercounts, new correct
   - Mixed 30s/1s cadence (realistic offload transition)
   - Single sample → null/0
   - Gaps >10 min → capped at dropout threshold
   - Duplicate timestamps → skip or interpolate

**Can run now:** `RustStrainParityTest` (pins current behavior, will need updating).
**Must build:** Cadence-distribution analysis script over real HR data.
**Needs decision:** Dropout cap threshold — what's the maximum plausible HR gap during sleep? 10 min? 20 min?

---

### Change #6 — Charge skin ordering

**What changes:** Skin temp deviation computed BEFORE Charge scoring, so it actually enters the formula.

**Validation data:** Multi-night WHOOP 5.0 data (David's export). WHOOP 4.0 data (user-69) for skin temp unit verification.

**Validation steps:**

1. **NOW (baseline):** For each night in David's export, record: stored Charge, stored skinTempDevC, what the Charge WOULD be with skin temp included. The delta = skin temp's contribution.

2. **AFTER change:** Stored Charge now includes skin temp. Compare per-night:
   - Distribution of skin temp contribution (expected: 0-3 points at 5% weight)
   - Nights where skin temp deviation is large (>2°C) → larger shift
   - WHOOP 4.0: verify ×0.04 scale factor produces physiological skin temps (30-38°C)

3. **Acceptance criteria:**
   - End-to-end test: pass-2 with only skin temp changed → Charge changed
   - No night where Charge shifts >5 points from skin temp alone
   - WHOOP 4 skin temp units verified (raw ADC → °C)
   - WHOOP 5/MG skin temp units verified (centidegrees → °C)

4. **Per-family gate:** Enable skin temp scoring ONLY for families where units are verified. WHOOP 5/MG first (direct centidegrees, confirmed). WHOOP 4 second (after scale factor verification).

**Can run now:** `ChargeEffortRestScoringTest`, `RecoveryAgreementTest`.
**Must build:** End-to-end pass-2 skin-ordering test.
**Must verify:** WHOOP 4 skin temp scale factor on real 4.0 data.

---

### Change #7 — Charge chronological baselines

**What changes:** Day D scored from state through D-1 (not from shared state including D).

**Validation data:** David's multi-month export (90+ nights). The longer the history, the better the validation.

**Validation steps:**

1. **NOW (baseline):** Reconstruct current pass-2 behavior: score all nights against one shared baseline. Record per-day Charge.

2. **AFTER change:** Reconstruct chronological behavior: score day D from state through D-1, then fold D. Record per-day Charge.

3. **Compare:**
   - Per-day delta vs baseline night count (expectation: delta shrinks with maturity)
   - Nights 5-10: largest delta (baseline built from 4-9 nights vs shared 30+)
   - Nights 20+: delta near zero (baseline converged)
   - Maximum delta, median delta, days where delta >3 points
   - Does the delta direction make physiological sense? (Self-referential = dampened deviations → chronological should show MORE extreme Charge on outlier nights)

4. **Acceptance criteria:**
   - Chronological test: day D uses exactly D-1 prior nights in baseline
   - Adding tomorrow does not change yesterday's stored Charge
   - Delta distribution is physiologically sensible (outlier nights get more extreme scores, not dampened)
   - Baseline maturity gate unchanged: <4 nights = Calibrating, <14 = Provisional, ≥14 = Trusted

**Can run now:** `IntelligenceBaselineShadowTest`.
**Must build:** Chronological baseline comparison script over real multi-month data.
**Must decide:** Immutable (morning-as-known) or retrospective (update as more data arrives)? Default: immutable.

---

### Changes #9-11 — HRV, RHR, respiration → whoop-rs FFI

**What changes:** Call site swap. Output should be byte-identical.

**Validation data:** Kotlin parity tests + agreement fixtures.

**Validation steps:**

1. **NOW (baseline):** All parity tests green.
   ```bash
   # Kotlin side (needs Android build):
   ./gradlew testFullDebugUnitTest --tests "com.noop.analytics.RustHrvParityTest"
   ./gradlew testFullDebugUnitTest --tests "com.noop.analytics.RustRestingHrParityTest"
   ./gradlew testFullDebugUnitTest --tests "com.noop.analytics.RustRespRateParityTest"
   ```

2. **AFTER change:** Same parity tests must stay green. Delete Kotlin mirrors (`HrvAnalyzer.kt`, resting HR math, respiration math). The parity tests themselves become the regression guard — if they're still green after the call site swap, the output is byte-identical.

3. **Acceptance criteria:**
   - Parity tests green before AND after
   - `HrvAnalyzer.kt` deleted, compile green
   - WhoopRepository HRV consumers unchanged (they call RustScores, not HrvAnalyzer directly — verify)

**Can run now:** Parity tests (need Android build).
**Must verify:** No direct `HrvAnalyzer` callers outside the analytics engine.

---

### Change #13 — Daily Stress → whoop-rs, add baseline gate

**What changes:** Formula unchanged. New: <14 baseline days → Calibrating instead of scored.

**Validation data:** `RustStressParityTest` + David's multi-month RHR/HRV history.

**Validation steps:**

1. **NOW (baseline):** RustStressParityTest green (formula parity).

2. **AFTER change (formula):** Parity test still green. Swap call site.

3. **Baseline gate:** Count how many days in David's history have <14 prior RHR+HRV rows. These days currently show a Stress score (often 1.5 from zeroed z-scores). After the gate, they show "Calibrating."

4. **Acceptance criteria:**
   - Formula parity test green before AND after
   - Baseline gate: 0 Stress for <14 valid prior days
   - Baseline gate: 0 Stress when population SD ≈ 0 (single prior day)
   - Historical trend uses rolling baselines (day D scored from days 1..D-1), not today's baseline
   - Stress persisted to `metricSeries("my-whoop", "stress")` so Trends Report can show it

**Can run now:** `RustStressParityTest`.
**Must build:** Rolling-baseline historical Stress computation in whoop-rs.

---

### Change #8 — R-R beat order

**What changes:** Same-second intervals stored in emission order, not sorted by magnitude.

**Validation data:** `rr-real-fixture.json` (8 nights of David's real R-R). `whoop5_real_rr.json` in agreement fixtures.

**Validation steps:**

1. **NOW (baseline):** For each night in `rr-real-fixture.json`, compute RMSSD with current (sorted) order. Record baseline RMSSD per night.

2. **AFTER change:** For the same nights, compute RMSSD with emission-order intervals. Compare:
   - Per-night RMSSD delta
   - Nights with >1% same-second collisions: larger delta
   - Nights with 0 same-second collisions: zero delta

3. **Schema migration test:**
   - Create Room database with old schema (no beatSeq column)
   - Run migration
   - Insert R-R rows with same-second intervals
   - Query — verify emission order preserved
   - Rollback: migration is additive (beatSeq column default 0), so downgrade is a no-op

4. **Acceptance criteria:**
   - RMSSD delta <2ms on all real nights
   - Schema migration test passes
   - Migration is additive (downgrade-safe)

**Can run now:** `HrvGapAwareTest`, `HrvArtifactDensityTest`, `HrvGoldAgreementTest`.
**Must build:** RMSSD comparison script over rr-real-fixture.json.

---

### DREAMT regression gate (applies to #1, #12, any staging change)

**What changes:** Anything touching the V2 staging pipeline (detection, staging, wake refinement).

**Validation data:** DREAMT n=100 processed fixtures.

**Validation steps:**

1. **Build `whoop-rs-dreamt` binary.** Reads `fixtures_multi/dreamt/` CSVs, runs `analyze()` (detect + stage + refine), scores per-epoch kappa against gold hypnogram. This MUST use the actual Rust production code, not the Python port.

2. **Establish baseline kappa** with current whoop-rs code. Expected: close to the Python port's kappa 0.325 on DREAMT (the Python port was validated against shipped Kotlin V2). If the Rust implementation scores differently, investigate and reconcile BEFORE making any staging changes.

3. **After each staging change**, re-run the DREAMT binary:
   - Kappa must not drop below 0.320 (guardrail — 0.005 below baseline)
   - Per-stage F1 scores must not regress more than 0.02 in any single stage
   - Deep-stage recall must not drop below 0.15 (the DREAMT re-tune already reduced deep; further loss is unacceptable)
   - Wake precision must not drop below 0.50

4. **Cross-validate on AAUWSS** (n=13, independent dataset, different sensor):
   - Kappa must stay within 0.05 of baseline
   - AAUWSS catches overfitting to DREAMT's OSA-heavy population

**Can run now:** Python port DREAMT benchmark (`dreamt_v1v2_verify.py`) — useful for DIRECTION but not authoritative for Rust.
**Must build:** Rust DREAMT binary (`whoop-rs --feature dreamt-bench`).

---

### Recovery/stress validation (changes #6, #7, #13, #14)

**Validation data:** `recovery_cases.json`, `aauwss_optical_vs_gold.json`, `hrv_freq_cases.json`.

**Validation steps:**

1. **NOW (baseline):** Run `RecoveryAgreementTest` (Kotlin), `RustRecoveryParityTest` (Kotlin). Both green.

2. **AFTER changes:** Same tests green. Additionally:
   - `recovery_cases.json`: synthetic recovery cases with known outputs. Assert byte-identical before/after for the UNCHANGED parts of the formula (#6 skin ordering changes output — update the expected values).
   - `aauwss_optical_vs_gold.json`: HRV agreement vs ECG gold. Assert RMSSD correlation unchanged by R-R ordering fix.

3. **Multi-night stability:** For changes #6 and #7, score David's full 90-night export with old and new code. Compute per-night Charge delta distribution. A stable distribution (median delta <2, max <5 after night 20) is the acceptance threshold.

**Can run now:** Agreement tests (need Android build).
**Must build:** Multi-night scoring comparison script.

---

## What CANNOT be validated without hardware

| Validation | Blocked by | Mitigation |
|---|---|---|
| WHOOP 4 sparse-gravity detection | Only 3 fixture nights, no raw captures | Capture 5+ nights with whoop-debug before shipping |
| Off-wrist detection gating | No off-wrist fixture | Simulate: inject wrist-off intervals into existing fixtures |
| Live FFI call latency | No on-device measurement | Add `measureTimeMillis` around FFI call, log on first release |
| WHOOP 5 vs MG detection parity | Only WHOOP 5 fixtures | MG strap needed |
| Multi-night score stability across reconnects | No reconnect-interrupted fixture | Capture a night with intentional mid-night disconnect |
| Clock-dependent history banking | Hardware-gated (see clock gap report) | Validate after clock research is complete |
| Skin temp on WHOOP 4 real nights | Only 5.0 data has skin temp verified | Verify ×0.04 scale on captured 4.0 data |

---

## Build list — what needs creating

| # | Artifact | Purpose | Effort |
|---|---|---|---|
| 1 | `whoop-rs --bench dreamt` binary | Score Rust stager against DREAMT gold → kappa | Medium: read CSVs, call analyze(), score epochs |
| 2 | Detection compare script | Old Kotlin vs new Rust spans on strap fixtures | Small: Python, read CSVs, run both detectors |
| 3 | Effort cadence analysis script | Per-interval vs first-gap Effort on real HR data | Small: Python, read HR CSVs |
| 4 | Charge multi-night compare script | Old shared-baseline vs new chronological Charge | Small: Python, read DailyMetric exports |
| 5 | RMSSD order compare script | Sorted vs emission-order RMSSD on rr-real-fixture | Small: Python, read JSON |
| 6 | Skin temp contribution analysis | Per-night skin temp term on real data | Small: Python, read export |
| 7 | Rust DREAMT kappa CI gate | GitHub Actions? Run DREAMT on every physio-algo PR | Large: needs DREAMT fixtures in CI (100MB) |
| 8 | Kotlin parity test suite runner script | One command to run all 12 parity tests | Trivial: shell script |

---

## Execution order (cheapest signal first)

| Step | What | Time | Signal |
|---|---|---|---|
| 1 | Run all existing tests (Rust 157 + Kotlin parity) | 5 min | Baseline green count |
| 2 | Build Rust DREAMT binary, establish Rust kappa | 2-4 h | Authoritative staging baseline |
| 3 | Run detection compare on strap fixtures | 1 h | Span overlap %, fragment counts |
| 4 | Run Effort cadence analysis on real HR data | 1 h | Per-day delta distribution |
| 5 | Run Charge multi-night compare on export | 1 h | Chronological vs shared baseline deltas |
| 6 | Run RMSSD order compare on rr-real-fixture | 30 min | RMSSD delta per night |
| 7 | Run skin temp contribution analysis | 30 min | Per-night skin term contribution |
| 8 | After each fix: re-run Rust DREAMT | 5 min | Kappa guardrail check |
| 9 | After all fixes: on-device smoke (3 straps × 1 night) | 3 nights | Real hardware validation |

**Total validation time:** ~6 hours build + 3 nights hardware.

# Border Audit: Sleep, Stress, Recovery — Algorithm Inventory

**Goal:** Every formula below lives in `whoop-rs/crates/physio-algo/src/`. Kotlin is ONLY a caller: pass arrays over FFI, receive numbers back, persist, display. No algorithm line in Kotlin.

**Format:** formula → NOW (where it runs) → SHOULD (target state). **Bold** = correctness bug or data-integrity fault.

---

## SLEEP — 6 algorithms, 1 pipeline

### S1. In-bed detection

```
gravity_deltas = L2(Δx, Δy, Δz) per sample
still_sample = rolling_window(gravity_deltas, interval-derived-size) → fraction < 0.01g >= 0.70
runs = collapse(still_samples) breaking on class change or >20 min gap
        sparse gravity: HR-vouched gap bridge (≤baseline×1.05 across gap)
merged = absorb runs <15 min into neighbours
bridged = sparse-only: merge sleep runs ≤90 min apart when HR stays in sleep band
gate loop (per sleep run, in order):
  reject: ≤60 min, >16 h
  reject: median HR > baseline × 1.05 (×1.30 if deeply motion-quiescent)
  reject: off-wrist fraction ≥ 50%
  daytime [11:00, 20:00) local: reject unless ≥90 min AND resting HR ≤ baseline × 0.95
  morning-stillness window (≤3 h after prior overnight wake):
    also require band-state ≥60% asleep OR resting HR ≤ baseline × 0.90
  night-continuation chain: overnight-onset run anchors chain
    subsequent runs ≤90 min from chain tail = kept as night tails
output: DetectedSpan { start, end, resting_hr }
```

**NOW:** Kotlin `SleepStager.detectSleep` (~400 lines). The Kotlin detector HAS: gravity deltas, classify-still, build-runs, merge-periods, min-duration gate, HR-confirm (median only, no deeply-quiescent multiplier). It LACKS: daytime guard, off-wrist gate, sparse-gravity bridging, morning-stillness guard, night-continuation chain, 16 h span cap. The Rust `detect.rs` has ALL the above.

**SHOULD:** `analyzeDay` calls `RustSleepStager.analyze()` → `analyzeSleep` FFI → `detect::detect_sessions`. Delete `SleepStager.kt`.

---

### S2. V2 cardiorespiratory staging

```
Per 30s epoch over [start, end], z-scored within-night:

EMISSION:
  deep  = -0.8·z(hr_var) + 0.5·z(HR) - 0.1·z(move_frac) - deep_gate + 0.6·z(resp_reg) + ln(0.15)
  rem   = +0.8·z(hr_var) - 0.4·z(move_frac) + 0.4·z(HR) - 0.6·z(resp_reg) + ln(0.22)
  light = ln(0.50)
  wake  = 1.0·z(move_frac) + 0.5·dz(z(hr_var)) + 0.6·dz(z(HR)) + motion_gate_boost + ln(0.34)

  where:
    z() = per-night z-score from present values (absent channel → 0)
    dz(x) = deadzone: x in [-0.30,+0.30] → 0
    deep_gate = 5 × max(0, hr_flat11_percentile - 0.40)
    motion_quiescent = move_frac==0 AND jerk_max ≤ night_median_jerk × 35
      → clamps cardiac wake term to ≤0
    jerk_max > night_median_jerk × 35 → +4.0 motion_gate_boost on wake
    resp_reg = R-R RSA: tachogram → 4Hz resample → detrend → DFT peak/sum 0.15-0.40 Hz

CYCLE PRIOR:
  deep = 1.2 × (1 - clock/0.55), decays to 0 after 55% of night
  rem  = 1.0 × clock - (clock < 0.12 ? 3.0 : 0)

VITERBI (4-state, rows=from, cols=to):
       deep   rem    light  wake
deep   0.76   0.012  0.216  0.012
rem    0.003  0.92   0.067  0.01
light  0.08   0.08   0.80   0.04
wake   0.0    0.0    0.10   0.90

Ties → earlier stage (deep < rem < light < wake)
```

**NOW:** `whoop-rs/crates/physio-algo/src/sleep/v2.rs`. Called via `RustSleepStager.stage()` FFI. Already on the right side.

**SHOULD:** Already correct. Keep as-is. DEEP_GATE_THRESH = 0.40 is DREAMT-validated — do NOT change without re-running DREAMT benchmark.

---

### S3. Motion-aware wake refinement

```
Per wake segment ≥ 5 min:
  density gate: ≥80% of minutes have ≥2 gravity samples AND ≥1 step sample → run
                else pass through (sparse WHOOP 4.0 → no-op)

  has_locomotion: single minute ≥40 walk ticks OR ≥2 consecutive ≥10 walk ticks → skip (keep wake)
  posture_stable: ≥80% of minutes have posture_variance < 0.05g²
                  → burst minutes (+/- 1 min pad) stay wake
                  → non-burst minutes become light
  output: shrinks wake only (wake→light), never promotes to deep/REM
```

**NOW:** `whoop-rs/crates/physio-algo/src/sleep/refine.rs`. Already on the right side.

**SHOULD:** Already correct. Sub-30s fragments from minute-level reclassification need a rendering contract in the Kotlin hypnogram (`SleepTimeline.kt`).

---

### S4. Main-night selection

```
score(block) = asleep_minutes + alignment_bonus

alignment_bonus:
  target = habitual_midsleep (circular mean, longest-block-per-day, ≥14 days) ?? 03:30 local (cold-start)
  distance = circular_distance(block_midpoint_local, target)
  bonus = 90 min when distance ≤ 2h, linear decay to 0 at 5h

bridge:
  adjacent blocks <60 min apart → merge
  overnight-onset block 60-90 min apart → merge (night-tail widening)
  daytime-onset block ≥60 min apart → stays separate

reason:
  onlyBlock | longest | longestNearUsual | alignedToUsual (from same signals as score)

habitual_midsleep:
  per local day: pick longest block's midpoint (selection-independent)
  ≥14 days → circular mean of midpoints
  antipodal (00:00 + 12:00) → degenerate → None (cold-start fallback)
```

**NOW:** Two byte-identical implementations:
- `whoop-rs/crates/physio-algo/src/sleep/mainnight.rs` — called from edit/recompute seam via FFI
- `SleepStageTotals.kt` Kotlin mirror (~200 lines) — called from `analyzeDay`

Same formula, different input sets (Kotlin-detected vs stored session spans).

**SHOULD:** Delete Kotlin mirror. `analyzeDay` calls `ffiMainNightSelection` + `ffiHabitualMidsleepSec`. One selector, one truth.

---

### S5. Sleep stage totals (daily aggregate)

```
For main-night group:
  sum awake/light/deep/rem minutes from stagesJSON
  fold inter-fragment wake gaps into awake

DailySleep:
  totalSleepMin = light + deep + rem
  efficiency    = totalSleepMin / (totalSleepMin + awake)
  deepMin, remMin, lightMin = per-stage totals
```

**NOW:** `SleepStageTotals.minutes()` parses JSON shapes (`[{start,end,stage}]` seconds spans and `{stage,min}` dict). `dailyAggregate` sums. `dailyAggregateHonoringEdits` substitutes user-edited blocks. These are format decoders + edit reconciliation, not algorithm math. Correctly in Kotlin.

**SHOULD:** Keep as-is. JSON is a persistence format; parsing it is a data concern. The aggregate math is simple addition — not worth an FFI round-trip.

---

### S6. Rest quality composite

```
Rest = f(main_night_stages, efficiency, duration, prior_night_Rest?)
```

**NOW:** `AnalyticsEngine.computeRest` (Kotlin). Exact formula not extracted — deeply nested in the analytics engine. Feeds Charge at 15% weight via `(Rest/100 - 0.85) / 0.12`. No parity test.

**SHOULD:** Extract formula, implement in `whoop-rs/physio-algo/src/sleep/rest.rs`. FFI: input = Vec<StageSegment> + efficiency + duration + prior_rest → output = rest_score. Parity-test against current Kotlin output on real nights before cutover.

---

## STRESS — 4 separate formulas under one name

### T1. Daily Stress (night baseline deviation)

```
For today's (RHR, HRV) against up to 30 prior daily rows:

  baseline_mean_RHR = mean(prior RHR values)
  baseline_sd_RHR   = population_sd(prior RHR values)
  baseline_mean_HRV = mean(prior HRV values)
  baseline_sd_HRV   = population_sd(prior HRV values)

  zRHR = (RHR_today - mean_RHR) / max(sd_RHR, 0.0001)    [sd≤0.0001 → contributes 0]
  zHRV = (mean_HRV - HRV_today) / max(sd_HRV, 0.0001)    [note: HRV inverted — higher HRV = lower stress]

  raw = (available zRHR) + (available zHRV)
  Stress = 3 / (1 + exp(-raw))

  bands: low[0,1) medium[1,2) high[2,3]
```

**NOW:** `StressModel.kt` (Kotlin, ~100 lines). At read time, not persisted. No baseline minimum gate. Demo seeder uses a DIFFERENT linear formula `clamp(1.5 + 0.6*zRHR - 0.6*zHRV, 0, 3)`. Trends Report reads only stored series → derived Stress never appears in exports.

**SHOULD:** Implement in `whoop-rs/physio-algo/src/stress.rs` → `daily_stress` FFI. Input: today (RHR, HRV) + Vec<(date, RHR, HRV)> prior rows. Output: 0–3 score + baseline state + per-signal z-scores. Add baseline gate (minimum N days before scoring). Kotlin persists result into `metricSeries("my-whoop", "stress")`. Delete `StressModel.kt`. Delete demo seeder formula. Unify: every surface reads the persisted series.

---

### T2. Daytime Stress (intraday activation)

```
For today, hours 06:00-21:59:

  Per hour: require ≥300 HR rows
    hourly_mean_HR = mean(HR in hour)
    hourly_RMSSD   = RMSSD(R-R in hour) if sufficient clean R-R

  Reference (from same day's waking hours):
    calm_HR  = lower_quartile(hourly_mean_HRs)
    calm_HRV = upper_quartile(hourly_RMSSDs)
    spread_HR  = population_sd(hourly_mean_HRs)
    spread_HRV = population_sd(hourly_RMSSDs)

  Per scored hour:
    zHR   = (mean_HR - calm_HR) / max(spread_HR, 0.0001)       [HR up = stress]
    zHRV  = (calm_HRV - RMSSD) / max(spread_HRV, 0.0001)       [HRV down = stress]
    score = 3 / (1 + exp(-(available_zHR + available_zHRV)))

  sustained_high: 3 trailing scored hours all ≥ 2 (gaps from unscored hours ignored → these need not be consecutive)
```

**NOW:** `DaytimeStress.kt` (Kotlin, ~300 lines). No exercise gate. Reads only `my-whoop` source. One-shot load, never refreshes. 300-row hourly gate unreachable from live 30s WHOOP 5 cadence (~120 rows/h).

**SHOULD:** Implement in `whoop-rs/physio-algo/src/stress.rs` → `daytime_stress` FFI. Input: Vec<(hour, mean_HR, rmssd?)> → output: Vec<(hour, 0–3 score)> + sustained_high flag. Add exercise gate (HR zone gate OR step cadence gate). Delete `DaytimeStress.kt`.

---

### T3. Baevsky Stress Index

```
SI = AMo / (2 × Mo × MxDMn)
  where AMo = mode amplitude (histogram peak %)
        Mo  = mode (histogram peak RR, seconds)
        MxDMn = RR range (max - min, seconds)
  from R-R histogram over analysis window
```

**NOW:** `StressIndex.kt` (Kotlin). `RustScores.kt` also calls whoop-rs for it. Two implementations.

**SHOULD:** Implement in `whoop-rs/physio-algo/src/stress.rs` → `baevsky_si` FFI. Input: Vec<(ts, rrMs)> → output: SI + components. Delete `StressIndex.kt`.

---

### T4. Live stress-onset detector

```
Rolling window over live R-R + HR:
  detect: successive-beat RMSSD decline + HR stability or rise
  suppress: HR zone (exercise), recent motion, low R-R count
  output: onset event OR suppression reason
```

**NOW:** `StressOnsetDetector.kt` (Kotlin, ~200 lines). Opt-in, exercise-gated. Consumes in-memory `WhoopBleClient.rrRecent` buffer.

**SHOULD:** Implement in `whoop-rs/physio-algo/src/stress.rs` → `stress_onset` FFI. Input: Vec<(ts, rrMs)> ring buffer + Vec<(ts, bpm)> ring buffer + recent_motion flag → output: onset_event? + suppression_reason?. Kotlin feeds live BLE notifications into ring buffers, calls FFI, displays result.

---

## RECOVERY (CHARGE) — 1 composite formula, 5 baseline-tracked inputs

### R1. NOOP Charge

```
For each nightly metric x, EWMA baseline (center μ, spread s):

  σ ≈ 1.253 × s
  z(x) = (x - μ) / max(σ, 1e-9)

TERMS (only present terms enter; weights renormalize):

  HRV           z(HRV)                        × 0.55   [higher → better]
  Resting HR    z(RHR_baseline, RHR_current)  × 0.20   [lower → better]
  Respiration   z(resp_baseline, resp_current)× 0.05   [lower → better]
  Rest quality  (Rest/100 - 0.85) / 0.12      × 0.15   [higher → better]
  Skin temp     -|deviation_C| / 1.0          × 0.05   [near baseline → better]
  Recovery Idx  -overnight_HR_slope / 2.0     × 0.05   [optional, dormant]
  Activity Bal  z(Effort_baseline, prior_day_Effort) × 0.05 [optional, dormant]

  composite_z = Σ(term_z × weight) / Σ(weights_of_present_terms)
  Charge = clamp(100 / (1 + exp(-1.6 × (composite_z + 0.20))), 0, 100)

  bands: red[0,34) yellow[34,67) green[67,100]
  neutral inputs → ~57.93
```

**NOW:** `whoop-rs/crates/physio-algo/src/recovery.rs`. Already on the right side. The Kotlin `RecoveryScorer.kt` is a parity reference, not the production path.

**SHOULD:** Already correct. Keep as-is. Delete `RecoveryScorer.kt` parity reference once FFI is the only path.

---

### R2. Personal baselines (EWMA state machine)

```
Per metric (HRV, RHR, respiration, skin_temp, Effort):

  center half-life: 14 nights
  spread half-life: 21 nights
  valid range, spread floor, winsor clamp: per-metric constants

  lifecycle:
    < 4 valid nights              → Calibrating (unusable)
    4-13 valid nights             → Provisional (usable)
    ≥ 14 valid nights             → Trusted
    > 14 missing after usable     → Stale (unusable)

  cold-start (first 8 nights):
    center half-life: 3 nights
    winsor clamp: 2.5× wider
    no hard-outlier rejection

  steady-state:
    accepted values clamped to 3× spread before updating center
    values beyond 5× spread: seen but NOT folded
    manual recalibration epoch: drops prior history

  deviceEraEpoch: detects Oura/Fitbit/Garmin transitions (NOT WHOOP 4→5, Apple Health, Health Connect)
```

**NOW:** `Baselines.kt` (Kotlin, ~400 lines). Rust recovery scorer receives pre-computed `(center, spread, tier)` values from Kotlin. Baseline state lives in Kotlin memory during the scoring pass, constructed from stored `DailyMetric` rows.

**SHOULD:** This is the hardest cutover. Baseline state crosses scoring runs — it must be persisted between passes. Two approaches:

**Option A (recommended):** whoop-rs owns the state machine. FFI: serialize baseline state as a JSON blob → Kotlin stores it in Room (one row per metric per source). Next pass: Kotlin reads blob, passes to FFI, FFI deserializes + updates + returns new blob. Kotlin never sees the internals.

**Option B (simpler, less clean):** Keep `Baselines.kt` in Kotlin but reduce it to a thin persistence shell. The EWMA update math moves to whoop-rs. FFI: `ewma_update(center, spread, new_value, half_lives, valid_range) → (new_center, new_spread, accepted)`. Kotlin iterates days, calls FFI per value, persists results. The iteration order (chronological) stays a data concern.

Option B is lower-risk and achieves the same border: no algorithm math in Kotlin.

---

### R3. Nightly HRV (RMSSD from R-R)

```
For session [start, end]:
  range_filter: keep R-R in [300, 2000] ms
  RMSSD = sqrt(mean((rr[i+1] - rr[i])²)) over valid intervals
  windowed: 5-min tumbling windows, average RMSSD across windows
  deep-window option: pool only deep-stage 5-min windows when enabled
```

**NOW:** whoop-rs `hrv.rs` has `windowed_avg_hrv`, `rmssd_raw`, `range_filter`. Kotlin `HrvAnalyzer.kt` has mirrors. `AnalyticsEngine.kt` computes nightly HRV in Kotlin. Two code paths to the same formula.

**SHOULD:** Delete `HrvAnalyzer.kt`. Route ALL RMSSD computation through whoop-rs FFI. `AnalyticsEngine` passes `Vec<(ts, rrMs)>` arrays to FFI, receives RMSSD back.

---

### R4. Session resting HR

```
For session [start, end]:
  5-min rolling mean HR across session
  resting_HR = lowest rolling mean (floor)
```

**NOW:** whoop-rs `resting_hr.rs` has `session_resting_hr`. Called from `detect.rs` during sleep detection. `AnalyticsEngine.kt` ALSO computes nightly resting HR in Kotlin.

**SHOULD:** Route through whoop-rs FFI. Delete Kotlin resting HR math. The Rust detector already computes it; pass it through the `Session` struct that `analyzeSleep` returns.

---

### R5. Nightly respiration rate

```
For session [start, end]:
  RSA estimate from R-R intervals (respiratory sinus arrhythmia)
  median per-session value
```

**NOW:** whoop-rs `respiratory_rate.rs`. `AnalyticsEngine.kt` also computes respiration in Kotlin. Two paths.

**SHOULD:** Route through whoop-rs FFI. Delete Kotlin respiration math.

---

## Border map — what lives where after cutover

```
whoop-rs/crates/physio-algo/src/
├── sleep/
│   ├── detect.rs          S1  in-bed detection
│   ├── v2.rs              S2  V2 cardiorespiratory staging
│   ├── refine.rs          S3  motion-aware wake→light
│   ├── mainnight.rs       S4  main-night selection + habitual midsleep
│   ├── rest.rs            S6  Rest quality composite [NEW]
│   ├── input.rs               protocol-free input types
│   ├── common.rs              median, std, z-score, R-R flatten
│   └── mod.rs                 analyze(), stage_refined() entry points
├── stress.rs              T1  daily Stress, T2 daytime Stress, T3 Baevsky SI, T4 onset detector [MERGE]
├── recovery.rs            R1  Charge composite formula
├── baselines.rs           R2  EWMA baseline state machine [NEW]
├── hrv.rs                 R3  RMSSD, SDNN, windowed avg HRV
├── resting_hr.rs          R4  session resting HR
├── respiratory_rate.rs    R5  RSA respiration estimate
├── strain.rs                  Effort (already correct)
├── strain_firstgap.rs         per-interval TRIMP integration [FIX]
├── confidence.rs              Solid/Building/Calibrating per metric [NEW]
├── stats.rs                   percentiles, circular mean
├── hr_zones.rs                HRmax + zone boundaries
├── vo2max.rs                  VO2max estimate
└── lib.rs

Kotlin (noop-tan) KEEPS:
├── ui/                    Every Compose screen, chart, card
├── ble/                   WhoopBleClient, Backfiller, Puffin framing, GATT
├── data/                  Room DAO, Repository, entities, migrations
├── analytics/
│   ├── RustScores.kt      THIN FFI bridge (no formula)
│   ├── RustSleepStager.kt THIN FFI bridge (no formula)
│   ├── AnalyticsEngine.kt ORCHESTRATION: window selection, stream reads, FFI calls, persistence
│   ├── IntelligenceEngine.kt ORCHESTRATION: pass-1/pass-2 loops, merge, rescore triggers
│   ├── SleepStageTotals.kt FORMAT DECODER: stagesJSON parse, daily aggregate sum
│   ├── SleepDebt.kt        → DELETED (moves to whoop-rs)
│   ├── SleepStager.kt      → DELETED (moves to whoop-rs)
│   ├── SleepStagerTrace.kt → DELETED
│   ├── HrvAnalyzer.kt      → DELETED (already in whoop-rs)
│   ├── StrainScorer.kt     → DELETED (already in whoop-rs)
│   ├── StressModel.kt      → DELETED (moves to whoop-rs)
│   ├── DaytimeStress.kt    → DELETED (moves to whoop-rs)
│   ├── StressIndex.kt      → DELETED (already in whoop-rs)
│   ├── StressOnsetDetector.kt → DELETED (moves to whoop-rs)
│   ├── Baselines.kt        → SHRINK to persistence shell (EWMA math in whoop-rs)
│   ├── ScoreConfidence.kt  → DELETED (moves to whoop-rs)
│   ├── CalibrationMilestones.kt → DELETED (moves to whoop-rs)
│   └── RecoveryScorer.kt   → DELETED (parity reference, already in whoop-rs)
└── ingest/                Importers (data plumbing, stays)
```

## Implementation order

Sorted by risk: correctness bugs first, then border hygiene, then new modules.

| # | What | Risk | Effort |
|---|---|---|---|
| 1 | Route `analyzeDay` sleep detection through `analyzeSleep` FFI | Detection quality on WHOOP 4 + off-wrist 5.0 | Small: swap one call |
| 2 | Route main-night selection through `ffiMainNightSelection` | Same-day disagreement | Small: swap one call |
| 3 | Delete `SleepStager.kt` | ~400 lines dead after #1 | Trivial |
| 4 | Delete Kotlin main-night mirror in `SleepStageTotals` | ~200 lines dead after #2 | Trivial |
| 5 | Fix Effort first-gap → per-interval integration in `strain.rs` | Whole-day TRIMP wrong | Medium: new integration math |
| 6 | Fix Charge pass-2: skin temp before Charge scoring | Charge omits skin temp term | Small: reorder 2 lines |
| 7 | Fix Charge pass-2: chronological baseline folding | Self-referential baselines | Medium: change loop order |
| 8 | Preserve R-R beat order in Room schema | RMSSD bias in HRV→Stress→Charge | Medium: schema migration |
| 9 | Route all HRV through whoop-rs FFI, delete `HrvAnalyzer.kt` | Border hygiene | Small |
| 10 | Route all resting HR through whoop-rs FFI | Border hygiene | Small |
| 11 | Route all respiration through whoop-rs FFI | Border hygiene | Small |
| 12 | Extract Rest formula, implement in `whoop-rs/sleep/rest.rs` | Rest feeds Charge at 15% | Medium: needs reverse-engineering |
| 13 | Move daily Stress to `whoop-rs/stress.rs`, add baseline gate | No baseline gate today | Medium: new FFI surface |
| 14 | Move daytime Stress to `whoop-rs/stress.rs`, add exercise gate | Exercise labeled as Stress | Medium: new FFI surface |
| 15 | Move Baevsky SI to `whoop-rs/stress.rs` | Already duplicated | Small |
| 16 | Move onset detector to `whoop-rs/stress.rs` | Border hygiene | Medium: live-data FFI |
| 17 | Move Baselines EWMA math to whoop-rs (Option B: per-value FFI) | ~400 lines, hardest cutover | Large: FFI contract + migration |
| 18 | Move ScoreConfidence to `whoop-rs/confidence.rs` | Border hygiene | Small |
| 19 | Move CalibrationMilestones to whoop-rs | Border hygiene | Small |
| 20 | Delete `StrainScorer.kt`, `RecoveryScorer.kt`, `StressIndex.kt` | Already unused / duplicated | Trivial |
| 21 | Route sleep debt/need/consistency through whoop-rs | Border hygiene | Medium |
| 22 | Run full test suite + DREAMT benchmark after any staging change | Validation gate | Mandatory |
| 23 | On-device smoke: 5.0 + MG + 4.0 each one full night | Hardware validation | Mandatory |

## Acceptance criteria per subsystem

### Sleep
- `analyzeDay` calls exactly ONE FFI for detection+staging: `analyzeSleep`
- `analyzeDay` calls exactly ONE FFI for main-night selection: `mainNightSelection`
- `SleepStager.kt` deleted
- Kotlin main-night mirror deleted
- Rest formula in whoop-rs, parity-tested against current Kotlin output
- Sleep debt in whoop-rs
- DREAMT benchmark re-run, kappa ≥ 0.320 (no regression)

### Stress
- Daily Stress in whoop-rs, persisted to `metricSeries("my-whoop", "stress")`
- Daily Stress refuses to score <14 baseline days
- Daytime Stress in whoop-rs, exercise-gated
- Baevsky SI in whoop-rs only
- Onset detector in whoop-rs, live-FFI-called from BLE notification path
- `StressModel.kt`, `DaytimeStress.kt`, `StressIndex.kt`, `StressOnsetDetector.kt` deleted
- Demo seeder linear formula deleted (or moved to test fixture)

### Recovery / Charge
- Pass-2: skin temp deviation computed BEFORE Charge scoring
- Pass-2: baselines folded chronologically (D scored from state through D-1, then D folded)
- All nightly physiology (HRV, RHR, respiration) computed in whoop-rs
- `HrvAnalyzer.kt` deleted
- Baselines EWMA math in whoop-rs (Option B)
- `Baselines.kt` reduced to persistence shell (serialize/deserialize + iterate days)
- `RecoveryScorer.kt` deleted (parity reference)

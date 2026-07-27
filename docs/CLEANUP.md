# Cleanup backlog

Pre-existing debt found while doing other work. Nothing here is a regression from a recent change, and
nothing here is urgent — this is the list to work through when there is room, so the findings are not
lost the next time someone trips over them.

Every count below was measured on 2026-07-27 against `noop-tan`, not estimated. Re-measure before
starting an item; they move.

---

## 1. Comment-rule debt

`CLAUDE.md` sets a strict house rule for this fork: a comment is at most 3 lines, says only **what it
does** and **where it connects in-tree**, and **never refers to external things** — no PR or issue
numbers, no URLs, no other repos, no "ported from the Swift file", no version strings. Most of the tree
predates that rule.

| Category | Count | Files |
|---|---|---|
| Issue / PR refs in comments (`#123`) | 1631 lines | 175 |
| Cross-platform refs (Swift / macOS / iOS / GRDB) | 1808 lines | 225 |
| History narration ("used to", "no longer", "previously", "legacy") | 186 lines | — |

Heaviest files, by issue refs: `ble/WhoopBleClient.kt` (340), `data/WhoopRepository.kt` (111),
`ui/AppViewModel.kt` (106), `analytics/IntelligenceEngine.kt` (79), `analytics/AnalyticsEngine.kt` (52).

**Do not do this as one sweep.** A 3600-line comment diff is unreviewable and would collide with every
in-flight branch. Clean a file's comments only when already editing it for another reason, and keep
that part of the diff separate from the behavioural change.

Worth deciding first: the `Mirrors Swift` notes are the fork's record of the byte-identical parity
contract with upstream. If a Kotlin change is still meant to be PR-able back to `ryanbr/noop`, that
provenance has value and belongs in an `android/*.md` doc rather than being deleted outright.

---

## 2. Files over the house size limit

`CLAUDE.md` says 200-400 lines typical, 800 max. **31 files are over 800.**

```
6109  ble/WhoopBleClient.kt
2511  ui/AppChangelog.kt          (generated; grows by design)
2311  ui/HealthScreen.kt
2274  ui/AppViewModel.kt
2102  ui/WorkoutsScreen.kt
1921  data/WhoopRepository.kt
1892  analytics/IntelligenceEngine.kt
1769  ui/InsightsScreen.kt
1470  ui/TodayScreen.kt
1461  ui/AddDeviceWizard.kt
```

`AppChangelog.kt` is generated and legitimately accumulates. `WhoopBleClient.kt` at 6109 lines is the
real one: it is the BLE spine, the least test-covered layer in the app, and the hardest to review.

**`IntelligenceEngine.analyzeRecentOnCpu` is still ~846 lines** after the two trailing phases were
extracted. That extraction happened because Kotlin 2.3 codegen pushed the method past the JVM's hard
64 KB limit and the build failed outright — so the ceiling is real and already been hit once. The
remaining body has clear phase seams (pass 1 detect, baseline seed, pass 2 re-score, source-only fold)
that would extract the same way.

---

## 3. Health Connect

**Fixed 2026-07-27** (recorded so the pattern is recognisable, not as outstanding work):

- `READ_DISTANCE` was never declared while `DistanceRecord` sat in the read set. The runtime request
  was made, but an undeclared permission can never be granted, so that read could only ever fail.
- `WRITE_STEPS` and `WRITE_ACTIVE_CALORIES_BURNED` were declared but deliberately never written (the
  phone pedometer already supplies authoritative totals), so they had been dead since that decision.

A permission set and its record list drift apart silently, in both directions. There is now a check
that derives the needed set from `READ_RECORDS` / `WRITE_RECORDS` / `EXERCISE_PERMISSIONS` and diffs it
against the manifest; it is worth making that a unit test rather than a one-off script.

**Still open:** the reads that were planned and then deliberately backed out until they have a
consumer — blood pressure (a paired cuff writes there, which is the cheapest route to unparking the BP
work), body temperature, hydration and nutrition — plus the VO2 max and BMR writes. Their permissions
are correctly absent until the code exists. See `PLAN-9.0.2.md`.

---

## 4. Calibration schedule

`physio-algo::calibration` now carries WHOOP's published per-feature schedule, with two known gaps
recorded in the source rather than hidden:

- **VO2 max** requires *14 sleeps within a 21-day period*; we count 14 sleeps total.
- **Sleep consistency** requires *5 consecutive nights*; we count 5 nights.

Both mean a sparse history unlocks earlier than WHOOP would. Fixing needs date-aware counting rather
than a tally, so `Calibration` grows a variant that takes the night dates.

**Open question for David:** WHOOP's own table lists Health Monitor as unlocking at 7 recoveries while
its notes say "accessible at 0, fully calibrated at 7". Ours is 7/7 (hidden until 7). If the note is
the truer reading it should be 0/7 — visible immediately, badged as calibrating.

The constants also have almost no consumers: only `hrv.rs`, `spo2.rs` and `whoop-store` gate on them.
Nothing gates steps, sleep efficiency, sleep performance or hours-vs-needed, so a fresh install shows
figures WHOOP would withhold. Wiring them is a **behaviour change** — steps would disappear from a
WHOOP 4.0's first day — so it needs a release note, not a silent landing.

---

## 5. Release commits never come back to dev — 2026-07-27

`fork-release.yml` bumps the version and generates the `AppChangelog.kt` entry, then commits that to
the branch it ran on. For 9.0.1 that worked: `origin/stable` carries `b440867d` with
`CURRENT_VERSION = "9.0.1-tan"`.

Nothing merges that commit back to `noop-tan`. Dev sat at `8.7.0` and would have shipped 9.0.2 with
the 9.0.1 card missing from the list entirely. The 9.0.1 entry has now been carried back by hand.

**This recurs at every release** unless the flow closes. Options, none taken yet: the workflow opens
a PR back to dev; or cutting the next rc starts by merging `stable` into `noop-tan`; or the entry is
generated on dev before the rc is cut, so the release branch inherits it.

**Also open:** the shipped 9.0.1 entry tells users "Blood oxygen works on WHOOP 4.0 … an
uncalibrated estimate". 9.0.2 withdraws that reading as unreliable. The card was kept as shipped —
rewriting what a released version told people is a judgement call, not a cleanup.

**A caution worth keeping:** the local `stable` and `rc/9.0.1-tan` refs were three commits stale, and
reading them produced a confident and wrong diagnosis ("the workflow silently failed"). Fetch before
concluding anything from a branch you did not just push.

---

## 6. Test fixtures

- **`e9night` has no labels, and that is handled — NOT A BUG (checked 2026-07-27).** Its `truth.csv`
  is 0 lines while gravity/hr/rr carry real data. Running the report shows it prints
  `e9night  -  0  no ground truth` explicitly, never kappa 0, and the test is `#[ignore]`d and
  report-only. The earlier note here claimed it implied a missing fifth result; it does not. Keep the
  set: the signals are real, so labels arriving later make it score with no other change.
- **The parity gates are alive.** The same run confirms AAUWSS kappa 0.412 and DREAMT 0.311 against
  their shipped targets, and killa5 at 0.537 — these are the gates the `whoop data` rename silently
  disabled for ten days, so they are worth re-confirming rather than assuming.
- **`noop-pr-crossfork/` still has 5 stale `whoop data/` fixture paths** — the same rename that
  silently disabled three parity gates here for ten days. That tree targets `ryanbr/noop`, where the
  absolute Windows paths would never resolve anyway, so it is cosmetic; worth fixing before that PR
  moves so it does not teach the pattern.

---

## 7. Toolchain ceilings to revisit

Pinned deliberately, each with a reason that will expire:

- **Kotlin 2.3.10, not 2.4.x** — only KSP2 works with AGP's built-in Kotlin, and 2.3.10 is its newest.
  Room needs KSP, so KSP releasing for 2.4 is what unblocks this.
- **`core-ktx` 1.18.0 and `lifecycle` 2.10.0**, one minor back — their newest require compileSdk 37,
  which is not a stable platform yet.
- **R8 minification is off** for release builds. A minified build died right after the terms gate on a
  real device and the reflective path was never pinned. Re-enabling needs the exact crash trace and
  device verification, not another guess at keep rules.

---

## 8. Debt this branch created (2026-07-27)

Recorded at the time rather than discovered later.

**`IntelligenceEngine.analyzeRecentOnCpu` is still ~846 lines.** Two trailing phases were extracted
because Kotlin 2.3 codegen pushed the method past the JVM's 64 KB limit and the build failed outright.
That bought headroom, it did not fix the function. The remaining body has the same phase seams
(pass-1 detect, baseline seed, pass-2 re-score, source-only fold) and the ceiling is now known to be
reachable.

**`Spo2::rolling_reading` is exposed but unused.** The FFI export and the `RustScores` adapter exist;
no screen calls them. The 4.0 card still reads `DailyMetric.spo2Pct`, which is now usually null on that
hardware. Deciding what the card shows instead — the anchored multi-night value, or nothing — is a UI
question that was not part of the decode fix.

**The 4.0 SpO2 story is half-told.** The pulsatility gate stops a wrong percent being published, but the
raw red/IR nightly means are still stored and still have no consumer. If the relative reading is the
answer, it should be built on those means rather than on a percent that no longer computes.

The reason the percent cannot work is arithmetic, not calibration and not anyone's opinion. The
channel is sampled at **1 Hz** (199,963 of 200,000 consecutive intervals are exactly one second), so
the Nyquist limit is 0.5 Hz. A cardiac waveform runs 0.83–3.0 Hz at 50–180 bpm — **entirely above
that limit**, so the pulsatile component ratio-of-ratios reads is aliased away before the app ever
sees it. No curve constant, window size or calibration recovers it.

The measurements agree: two straps, 2.1M samples, 89.3% and 98.2% of windows with zero amplitude,
both producing ~80% for a healthy wearer. The "it is a sleep-only channel" explanation was tested
and **rejected** — in-bed windows are 95.7% flat, worse than the corpus.

**What is NOT established:** whether the stored red/IR means support a *relative* reading. That
framing was taken from OpenStrap's metric tiering and asserted here before it was tested. What the
data shows so far is only that the nightly red/IR ratio varies (0.73–0.93 over 34 nights, sd 0.066)
— it is not constant, so a relative reading is not obviously dead. But nothing establishes that the
variation tracks oxygenation rather than skin contact, temperature, position or sensor drift, and
there is no ground truth on either strap to decide. Treat it as an open question, not a plan.

**The v18 optical channels reach the store — RESOLVED 2026-07-27.** All five cross the FFI and land
in `v18Sample`. Whether `optical_signal_poor` should gate the R-R path is measured and answered in
section 12: yes, but the effect is -0.5% to -4.7% on nightly RMSSD, not the large win it looked like.

**The record-level sentinel is CONFIRMED — 2026-07-27, and the old note here was wrong twice.** It
claimed all our captures hold the two amplitude bytes equal: they differ on 52,458 of 82,185 records
(64%). It also called the model unverifiable: exactly one channel reading 128 occurs in **0** of those
82,185. Both or neither, always. The claim rested on three small captures; six exist, in
`whoop-research/own data raw/`.

**`resp_raw` @76 on 4.0 reads a channel tag, not respiration — 2026-07-27.** Measured on the
independent 4.0: **2,066,290 rows, 3 distinct values, 99.2% pinned at 3073**. Those values are
`0x0C01`, `0x0B01`, `0x0701` — the same family the 5.0 v18 record carries at @77, beside `0x0C02` at
@79. A shared (tag, index) word, so this is not a mis-scaled ADC that a new divisor would fix.
Respiratory rate is unaffected; it comes from R-R via RSA. Recommendation in the plan: stop writing
it. Re-deriving the true offset needs 4.0 raw frames, which we do not hold.

**Health Connect reads have no visible surface yet.** Blood pressure, hydration and nutrition land in
`metricSeries` under the keys the existing screens use, so they should appear — but that has only been
reasoned about, not seen on a device with real Health Connect data.


---

## 9. WHOOP 4.0 blood oxygen — PARKED 2026-07-27

Closed as far as the data allows; reopen only with a raw capture.

**Settled.** The percent cannot be computed from what the strap sends. The red/IR pair arrives at
1 Hz, so the 0.5 Hz Nyquist limit sits below the 0.83-3.0 Hz cardiac band and the pulsatile component
is aliased away. A second line of evidence says the same thing: in the windows that do vary, the AC
amplitude is 3-6 LSB with red and IR moving in near-lockstep, and the resulting ratio (p10 1.13,
p50 1.20, p90 1.37) sits on the DC ratio of the two channels (1.24) rather than on anything
physiological. Filtering to worn windows changes nothing — off-wrist windows never scored in the
first place, because a constant channel has no amplitude. Replicated across two straps and 2.1M
samples, and re-checked at 4.0 days on the live band with the same result (2.0% scored, median
81.8%). A pulsatility gate now returns `None`.

**Not settled, and the only way forward.** The official app shows a real SpO2 for the 4.0, so the
strap computes one internally — the MAX86171 runs to 2.9 kfps and the strap returns millisecond R-R,
which 1 Hz sampling cannot produce, so the fast dual-wavelength waveform exists on-device and is
simply never transmitted. The open question is whether it BANKS the computed value in a record byte
we have not mapped. Raw capture now works on the 4.0, so the next step is a capture plus an offline
hunt for a byte behaving like a saturation percentage. Until that exists there is nothing to analyse.

**Do not** try to fix this with different curve constants, a different window size, or a wear filter.
All three were tested and none of them can work against an aliased signal.

## 10. Smaller things noticed while working, not chased

- **`Spo2::rolling_reading` is exposed but no screen calls it.** The 4.0 card now shows nothing where
  it used to show a wrong number. Whether it should show the anchored multi-night value instead is a
  UI decision nobody has made.
- **One `sleepSession` row across four days of 4.0 data, with `startTs == endTs`.** The daily metrics
  did stage a full night earlier in the same database, so this is probably an artifact of one
  offload's slice rather than a stager fault. Worth a look once a night is worn end to end.
- **`resp_raw` @76 on 4.0 decodes to 3 distinct values across 29,851 samples**, 98% pinned at 3073.
  Respiratory rate is unaffected (it comes from R-R via RSA). Either re-derive the offset against the
  4.0 corpus or stop storing the field.
- **The v18 optical channels stop at the Rust border.** `optical_signal_poor` is the valuable one: a
  first-party per-second flag that the band's own beat detection failed, which the HRV windows and the
  sleep stager currently infer from motion. Wiring it needs the four-step FFI regen.


---

## 11. R-R is read in magnitude order, and it costs up to 25% of RMSSD — 2026-07-27

`WhoopDao.rrIntervals` reads `ORDER BY ts ASC, seq ASC` under a comment saying this fixed a
magnitude-sort bug. It did not. The plan walks the primary-key index `(deviceId, ts, rrMs, seq)`,
which already delivers `rrMs` order, then uses a temp b-tree for the last ORDER BY term only — and
`assignRrSeq` keys on `(ts, rrMs)`, so every distinct beat in a second carries `seq = 0` and that
sort is a no-op over ties. Running the app's own query returns **98.8-100% of multi-beat seconds in
ascending `rrMs` order** across five straps.

**Why it matters more than it looks.** `RustScores.groupRuns` groups R-R by `unix`, one run per
second, and `rmssd_runs` takes successive differences only WITHIN a run. So every pair RMSSD consumes
is a same-second pair: the within-second order is not a fraction of the input, it is the whole input.

Measured with the real algorithm (per-second runs, `MAX_BEAT_DELTA_MS` = 200 artifact drop) over the
last 14 sleep sessions per strap, current order against a within-second shuffle:

| strap | nights | RMSSD now | shuffled | delta | seconds with >=3 beats |
|---|---|---|---|---|---|
| David 5.0 | 14 | 23.5 ms | 29.3 ms | **+25%** | 51.1% |
| other-B | 9 | 47.1 ms | 55.3 ms | **+17%** | 48.9% |
| other-A | 13 | 81.1 ms | 84.1 ms | +4% | 43.9% |
| David 4.0 | 1 | 28.2 ms | 28.6 ms | +1% | 7.8% |
| killa 5.0 | 14 | 22.7 ms | 22.7 ms | 0% | 2.5% |

The exposure tracks how often a second carries **three or more** beats. A two-beat second is immune:
swapping the pair flips the sign of the difference and `d²` is unchanged. Sorting biases RMSSD DOWN,
the direction upstream reports.

The shuffle is a proxy for emission order, which was never stored — that is precisely why upstream
stamps `ord` at decode time rather than trying to recover it. It bounds the size of the error without
claiming to reconstruct the true value.

**Scope of the fix.** Legacy rows keep `ord` NULL and SQLite sorts NULL first, so existing history
falls through to the old order and reads back unchanged. Only beats decoded after the migration are
corrected. Ours would be Room v100 to v101, additive.

**Two corrections to earlier versions of this section.** The first claimed a 42.8 ms worst case from
comparing `ORDER BY rowid` against `ORDER BY ts, rrMs`; rowid is not time-ordered on a restored
database (David 5.0 steps backward on 1.3% of adjacent rows, other-B 0.4%, never-restored killa 0%),
so that measured cross-second jumbling. The second concluded the impact was about a millisecond and
not worth chasing; that computed RMSSD over the whole night's flat sequence, including the
cross-second pairs the gap-aware algorithm never uses. Both understated a real 25% error on this
fork's own band by measuring something the shipped code does not do.


---

## 12. Open points as of 2026-07-27

Everything below is unstarted or waiting on a decision. Nothing here is broken; it is the queue.

### Needs David

- **Enable raw capture, sync the WHOOP 4.0.** The only route to a real 4.0 blood oxygen: the strap
  computes a value the official app shows, and the question is whether it banks it in a record byte
  nobody has mapped. Capture now works on the 4.0 (it was gated to 5.0/MG), so this is one toggle and
  one sync. Until the capture exists there is nothing to analyse. See section 9.
- **Health Connect per-metric fill — a policy call.** WHOOP publishes a real SpO2 to Health Connect
  and we already read `OxygenSaturationRecord`, but the importer gates on DAY coverage, so on a 4.0
  day the band was worn we discard WHOOP's value while our own is null. Filling per METRIC instead of
  per day is right in principle, and also changes HRV, resting HR, sleep and respiratory rate on
  historical days for every user. Narrower option: scope it to SpO2, where our value is provably null.
- **Smoke-test 9.0.1-stable.** Released, never launched. Same commit as the verified rc3, so this is
  a formality rather than a risk.
- **The rc3 UI items that need a strap.** Body Clock card, End sleep card and the Health cards were
  never checked, because Health Monitor is live-only and reads "No biometrics yet" without a band.

### Mine, ready to start

- **`optical_signal_poor` SHOULD gate the R-R path — an earlier rejection here was wrong.** It was
  measured on the one daytime capture, where it fires on 53% of records and looks useless. On the two
  real nights in `whoop-research/own data raw/` it fires on 7% and 24%, and flagged R-R disagree with
  their own record's HR 3-6x more (median 7.27 vs 2.42 bpm on 838-night, 17.09 vs 2.86 on 206-sleep,
  54,294 records, two straps). Through the shipped grouping nightly RMSSD moves -0.51% and -4.67%.
  Small but real. Belongs in the R-R/HRV path, not `hr_anomaly` (HR quality is unaffected). Forward
  only — the flag is banked from v101 on, so past R-R has nothing to join to.
- **The thermal pair is the better candidate.** `auxRaw1`/`auxRaw2` give a skin-to-ambient gradient,
  replicated on four straps, which is the input a core-temperature correction needs. Not yet measured
  against the existing skin-temp deviation.
- **Settle the four unpinned channels.** `raw_u8_28/29`, `raw_u16_30` and `raw_f32_105` are banked
  under names that claim nothing. `raw_f32_105` is the interesting one: continuous, finite, always
  negative between -5.28 and -2.14, 1,851 distinct values in 1,861 records, and not a transform of
  dynamic acceleration. Settling any of them needs raw frames from a second 5.0 over several days.
- **Upstream #872/#873 — the feature-flag probe, deferred on hardware.** Read-only enumeration of the
  flags a strap knows (opcodes 117/118, neither of which we hold). It might name a 4.0 flag bearing on
  blood oxygen, but a probe that has never run on a strap cannot be validated in CI. Revisit alongside
  the 4.0 capture.
- **Upstream #875 — let a user stop a running sync.** `ABORT_HISTORICAL_TRANSMITS` (20): we hold the
  constant but no builder or caller, and it is in neither `FORBIDDEN` nor `DESTRUCTIVE`, so it is a
  benign stop rather than a trim. Worth taking; the value is the stop path and the backfiller teardown,
  which needs a strap to prove.
- **Upstream #874/#875, #818.** Sync robustness and the `pagesBehind` field offsets. Unassessed.

### Cut 9.0.2 when the above settles

`docs/releases/v9.0.2-tan.md` is written. Cut `rc/9.0.2-tan` off `noop-tan`, bump to `9.0.2-rc1-tan`,
dispatch the rc workflow — it resolves the notes from the version file automatically. Promote with
`bump: none`.

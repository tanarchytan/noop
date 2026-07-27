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

## 5. In-app "What's New" is three releases stale — RESOLVED 2026-07-27

`AppChangelog.CURRENT_VERSION` is `8.7.0`; the app is `9.0.2-dev-tan`. It never got entries for
9.0.0-tan, the 9.0.1 candidates, or 9.0.1 itself.

Fixed by cutting 9.0.1: the release workflow ran `Tools/appchangelog-gen.py` against
`docs/releases/v9.0.1-tan.md` and committed the entry, moving `CURRENT_VERSION` from `8.7.0` to
`9.0.1-tan`. Nothing was hand-written. Kept here as the worked example: the fix for a stale in-app
changelog is to cut a release with its notes file present, never to edit `AppChangelog.kt`.

---

## 6. Test fixtures

- **`sleep-benchmark/fixtures_multi/e9night/n1118/truth.csv` is 0 lines** while its siblings hold
  24750 (gravity), 23824 (hr) and 1359 (rr) rows. The dataset harness reports "no ground truth" for
  e9night and scores four sets instead of five. Either restore the labels or drop the set from
  `dataset_parity.rs`, so the report stops implying a fifth result is coming.
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

**The v18 optical channels stop at the Rust border.** `optical_baseline_a/b`, `optical_amp_a/b` and
`optical_signal_poor` decode and are tested, but nothing reads them. The sentinel is the valuable one:
it is a first-party per-second signal that the band's own beat detection failed, which the HRV windows
and the sleep stager currently infer from motion instead. Wiring it needs the four-step FFI regen.

**The record-level sentinel claim is not verifiable on our fixtures.** All three real v18 captures hold
the two amplitude bytes equal, so record-level and per-channel are indistinguishable in our corpus. The
model is pinned by a synthetic unit test and taken on upstream's 18,650-record evidence. A capture with
the two bytes differing would settle it.

**`resp_raw` @76 on 4.0 is wrong.** 29,851 real samples carry 3 distinct values, 98% pinned at 3073.
Respiratory rate is unaffected (it comes from R-R via RSA), but the field is decoded, stored and
meaningless. Either re-derive the offset against the 4.0 corpus or stop storing it.

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

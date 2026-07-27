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

## 5. In-app "What's New" is three releases stale

`AppChangelog.CURRENT_VERSION` is `8.7.0`; the app is `9.0.2-dev-tan`. It never got entries for
9.0.0-tan, the 9.0.1 candidates, or 9.0.1 itself.

`docs/releases/v9.0.1-tan.md` now carries the front-matter that `Tools/appchangelog-gen.py` needs, and
the release workflow regenerates the entry at release time — so **cutting 9.0.1 stable fixes this by
itself**. No manual edit needed; just don't hand-write the Kotlin.

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

Replicated on a second strap since: 2,066,290 samples over 25.6 days, 89.3% of windows with no
pulsation and a median scored value of 80.1%, against this fork's own band at 98.2% and 81.8%.
Upstream reaches the same conclusion by declining to compute one at all, and OpenStrap's 4.0 app
ships no SpO2 path. Three independent routes, one answer: the channel cannot carry a percent. Still
open is whether the stored red/IR means support a RELATIVE reading — meaningful as a change against
your own baseline, never as an absolute — which is how OpenStrap tiers the metric.

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

# Analytics Ceiling Verification Report

Measure-only. No code edits, no commits, no git-touch of whoop-rs. Every number
below was read from an actual harness run (Python sleep-benchmark, `cargo test -p
physio-algo`, or the Kotlin agreement JUnit XML), never from a piped exit code.

Purpose: establish the REAL headroom of each Tier-1 analytics score against its
known research ceiling BEFORE any optimization, so the optimize loop is bounded
and we never chase an impossible 100%.

Grounding rule: sleep accuracy is graded to PSG/AASM gold, never to WHOOP labels
(WHOOP under-reports wake, so fitting to it overfits). Formula scores are graded
to their published literature reference.

---

## Summary table

| Algorithm | Current | Ceiling | Headroom | Classification | How measured |
|---|---|---|---|---|---|
| **sleep-staging** (SleepStagerV2, 5.0/MG default) | DREAMT n=100 kappa **0.325** (4c-acc 64.1%, wake 81.3%, 3c 68.9%); AAUWSS n=13 kappa **0.431** (4c 61.1%, wake 87.4%, 3c 74.0%) | DREAMT 4-class per-epoch kappa **~0.33** (wrist-optical, waveform-free floor); raw-PPG NN 0.42-0.44 needs a waveform the band lacks; R-R-only 3-class 0.553 | **~0.005 kappa** = within measurement noise; feature-aug tune only +0.004; NN gain unreachable | **AT-CEILING** | `dreamt_v1v2_verify.py` per-epoch vs PSG in `fixtures_multi/{dreamt,aauwss}`; `cargo test -p physio-algo sleep` = 6 golden/contract tests PASS (Rust==Kotlin==port) |
| **HRV time-domain** (gap-aware RMSSD/SDNN/pNN50, avgHrv primitives; Kotlin `HrvAnalyzer`) | Formula maxErr vs numpy Task-Force ref (394 gold windows): RMSSD **0.0**, SDNN **4.8e-13**, pNN50 **0.0** (bit-exact). Gap-aware RMSSD MAE vs gold-ECG (resting, n=102): **0.70/0.86/1.11 ms** @ 5/10/20% artifact (39/59/72% below plain-splice). LF/HF rel err mean 0.058 | Bit-exact Task-Force = 0 formula error by definition; gap-aware is the artifact-robustness lever. Only untested rival = Kubios/Lipponen adaptive dRR (not implemented) | **~0** — formula already 0.0, gap-aware recovers gold to sub-1ms in the regime that matters | **AT-CEILING** | Kotlin `HrvGoldAgreementTest` / `HrvOpticalRobustnessTest` / `HrvFreqAgreementTest` (JVM unit tests); read [M2]/[M3]/[freq] println from JUnit XML |
| **recovery / Charge** (whoop-rs physio-algo composite via FFI, `RustScores.recovery`) | maxErr **0.0** across 33 driver cases vs independent numpy z-score+logistic ref (incl null-semantics / cold-start / dropped-term renorm) -> effective r=1.000000 vs reference | (1) exact formula reproduction (maxErr 0 / r=1) = reached; (2) the quoted r~0.992 is vs WHOOP's proprietary model, external, no paired gold on box | **Zero** on the measurable metric; the r~0.992-vs-WHOOP gap is not dataset-measured or lever-closable | **AT-CEILING** | `RecoveryAgreementTest` via gradle; loads real whoop-rs core via JNA (`whoop_ffi.dll`); read `maxErr=0.0` from JUnit system-out |
| **strain** (Banister/Edwards TRIMP) | golden PASS (banister_tracks_rising_load, trimp_to_strain, edwards_zone) | published TRIMP formula | none | **AT-CEILING** | `cargo test -p physio-algo` = 88 passed / 0 failed |
| **resting_hr** (session-floor min) | `resting_hr_parity.rs` **10/10** PASS | lowest-window-mean floor definition | none | **AT-CEILING** | `cargo test -p physio-algo` |
| **resp_rate** (breathing-freq recovery) | recovers_known_breathing_frequency PASS | breathing-frequency recovery | none | **AT-CEILING** | `cargo test -p physio-algo` |
| **hr_zones** (Tanaka HRmax) | tanaka_matches_formula + zone_number_boundaries PASS | Tanaka 208-0.7*age | none | **AT-CEILING** | `cargo test -p physio-algo` |
| **stress** (Baevsky SI) | golden_stress_index_hand_computed PASS | Baevsky stress index | none | **AT-CEILING** | `cargo test -p physio-algo` |
| **vo2max** (Nes) | vo2max_men/women + reference-person goldens PASS | Nes/Keytel formula | none | **AT-CEILING** | `cargo test -p physio-algo` |

---

## Classification

### AT-CEILING — stop, do not optimize (chasing = chasing an impossible 100%)

All nine measured scores are at ceiling:

- **sleep-staging** — DREAMT kappa 0.325 vs ~0.33 ceiling. The remaining ~0.005
  is measurement noise. AAUWSS 0.431 already sits *above* the DREAMT-derived
  ceiling (13-night cohort, high variance, not evidence of headroom). The only
  gain that clears 0.33 (raw-PPG NN 0.42-0.44) needs a PPG waveform the band
  never exposes.
- **HRV time-domain** — RMSSD/pNN50 formula error is exactly 0.0; gap-aware
  already recovers gold-ECG RMSSD to sub-1ms MAE in the sleep-HRV regime.
- **recovery** — exact to its only reference (maxErr 0.0); no external
  physiological gold exists to grade against.
- **strain, resting_hr, resp_rate, hr_zones, stress, vo2max** — deterministic
  literature formulas (Banister/Edwards, session-floor, Tanaka, Baevsky, Nes),
  all bit-for-bit to reference at 1e-6. A "better" number here would mean
  *diverging* from the validated formula, not improving accuracy.

### HEADROOM — genuine dataset-measured gap worth a measure->fix->measure loop

**None.** No score shows a dataset-measured gap that a known lever can close.

The two candidate levers were checked and both are dead ends:
- Sleep **feature-aug golden tune** — measured worth only +0.004 kappa, inside
  the noise band. Not worth a loop.
- HRV **Kubios/Lipponen adaptive dRR** — a theoretical rival only; not on box,
  not implemented, and gap-aware already recovers gold to sub-1ms, so there is
  no measured gap for it to close.

### PORT-DIVERGENCE — Rust below validated Kotlin (a real fix)

**None.** Every FFI-routed path that could be checked reproduces its validated
reference bit-for-bit:
- Sleep: `cargo test -p physio-algo sleep` golden-parity gate green -> Rust ==
  Kotlin == Python-port; the 0.325 DREAMT kappa holds for the FFI path.
- Recovery: maxErr 0.0 through JNA against the live `whoop_ffi.dll` rules out
  divergence.
- Six formula scores: all 88 goldens green in the Rust core.

**Not-yet-applicable:** HRV/avgHrv is not yet routed through whoop-rs FFI (avgHrv
door pending, task #9), so there is no Rust HRV number to fall below. When the
door opens, re-run `HrvGoldAgreementTest` to confirm Rust reproduces maxErr=0.0.

### CANNOT-MEASURE on this box

- **recovery r~0.992 vs WHOOP** — external figure; WHOOP's model is closed and
  no WHOOP-paired recovery gold dataset is present. Not gradeable here.
- **avgHrv Rust twin** — FFI door still closed; no Rust HRV path to measure yet.
- **SpO2** — held pending FFI doors; needs type-43 raw red/IR (RE frontier), not
  in the current dataset set.
- **PPG-DaLiA** — zip unextracted on this box; gold R-R truth used instead was
  AAUWSS PSG 200Hz ECG + GalaxyPPG Polar H10 belt (sufficient clean ground truth).

---

## Recommendation

**Do not open an optimization loop.** Eight of nine scores are deterministic
literature formulas or exact-parity composites already reproduced bit-for-bit;
the ninth (sleep) is the only score with a real PSG-gold accuracy number, and it
sits ~0.005 kappa under a hard wrist-optical floor with no reachable lever.

The optimize loop is therefore **empty and bounded**:
- 0 algorithms have genuine dataset-measured headroom.
- 0 show a port-divergence to fix.
- 9 are AT-CEILING -> leave them alone; further tuning chases an impossible 100%.

The only future work that could move sleep past 0.33 is a hardware/data change
(a raw-PPG waveform the band does not expose), not an algorithm tweak, and it is
explicitly out of scope. The only measurement debt to clear later is re-running
the HRV/recovery agreement suite once the avgHrv/SpO2 FFI doors open, to confirm
the Rust twins reproduce the Kotlin maxErr=0.0 (a parity check, not an accuracy
grind).

---

## Harness defects found (report-only, NOT fixed — out of scope)

Two infra defects surfaced during measurement. They do not affect any number
above (worked around with data-only staging, no code edit), but they will bite a
plain `gradle` run:

1. **Stale fixture path.** `HrvGoldAgreementTest`, `HrvFreqAgreementTest`, and
   `RecoveryAgreementTest` hard-code the space-path
   `whoop data/datasets/agreement-fixtures`, which was renamed to `whoop-data`
   on 2026-07-16. The dir no longer exists, so all three **self-SKIP green** on a
   plain run (skipped=1, failures=0) and their numbers are silently unmeasured.
2. **Inert `-D` override.** The documented `-Dnoop.hrvGoldFixtures` override
   never reaches the forked test JVM because `build.gradle` `testOptions`
   forwards only `jna.library.path`. So the path cannot be redirected from the
   command line.

Workaround used for this report: copied the 7 gold fixtures from
`whoop-data/harnesses/agreement-fixtures` into the hardcoded path (data only, no
code), ran with skipped=0, read the numbers, then deleted the temp dir.

Proper fix (a real code edit, deferred): update the three tests to the
`whoop-data` path AND add
`it.systemProperty("noop.hrvGoldFixtures", ...)` to the forked-test config so the
override works.

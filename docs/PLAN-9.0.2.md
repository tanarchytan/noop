# 9.0.2 plan — calibration gates and Health Connect

Two themes for `9.0.2-rc1`: finish the WHOOP-parity calibration schedule, and make Health Connect a
real two-way bridge rather than the partial one it is today.

Everything below was checked against the shipped code and the actual artifacts on 2026-07-26, not
against memory. Version claims come from the AAR metadata, not the release notes.

---

## Part 1 — Calibration

### Done already

`physio-algo::calibration` now carries the full published schedule. The ten that existed were all
correct; these are the ones that were missing:

| Constant | unlock, full | Note |
|---|---|---|
| `DAY_STRAIN` | 0, 0 | |
| `SLEEP_EFFICIENCY` | 1, 1 | |
| `SLEEP_PERFORMANCE` | 1, 1 | |
| `HOURS_VS_NEEDED` | 1, 1 | |
| `HEALTH_REPORT` | 14, 14 | |
| `STEPS_GEN5` / `STEPS_GEN4` | 0,0 / 1,1 | the only generation-dependent schedule; use `steps(is_gen4)` |

`CalibrationMilestones.kt` no longer claims to mirror a WHOOP timeline it does not mirror. It is
NOOP's own baseline countdown (4 / 7 / 14 / 30) and now says so.

### Still to do

**1. Wire the gates.** The constants have no consumers except `hrv.rs`, `spo2.rs` and `whoop-store`.
Nothing gates steps, sleep efficiency, sleep performance or hours-vs-needed today, so a fresh install
shows figures WHOOP would withhold. The seam question is where the gate belongs: `spo2.rs` and
`hrv.rs` return `None` below their unlock, which is the honest pattern and keeps the decision in the
data layer. Follow it.

*Behaviour change:* steps disappear from a WHOOP 4.0's first day. That is the intent (a 4.0 step
figure is estimated from motion, and WHOOP itself withholds it), but it will look like a regression
if it lands unannounced. Put it in the release notes.

**2. Expose the schedule over FFI.** Kotlin currently cannot render a consistent "calibrating" badge
because it cannot see the schedule. One `calibration_for(metric) -> Calibration` export, one adapter
in `RustScores`, and every screen can show the same countdown from one source.

**3. The two windowed rules.** Both are counted as plain totals today, so a sparse history unlocks
earlier than WHOOP would:
- VO2 max — "14 sleeps within a 21-day period".
- Sleep consistency — "5 **consecutive** nights".

This needs date-aware counting rather than a tally, so `Calibration` grows a variant that takes the
night dates instead of a count. Do it as its own commit with its own tests.

**4. Open question for David.** WHOOP's own table lists Health Monitor as unlocking at 7 while its
notes say "accessible at 0, fully calibrated at 7". Ours is 7/7 (hidden until 7). If the note is the
truer reading it should be 0/7 — visible immediately, badged as calibrating. Left unchanged pending a
decision.

---

## Part 2 — Health Connect

### Where we stand

`HealthConnectImporter` (965 lines) reads 14 permissions; `HealthConnectWriter` (283 lines) writes 10.

| Direction | Covered |
|---|---|
| **Read** | ActiveCalories, BodyFat, Exercise, HeartRate, HRV, LeanBodyMass, OxygenSaturation, RespiratoryRate, RestingHeartRate, Sleep, Steps, TotalCalories, VO2Max, Weight |
| **Write** | HeartRate, HRV, OxygenSaturation, RespiratoryRate, RestingHeartRate, Sleep, Exercise, Distance |

Steps and active calories hold write permissions but are **deliberately not written** (#528): the
phone pedometer and any watch already feed Health Connect authoritative totals, and adding NOOP's
strap estimates would double-count the OS daily figures. iOS excludes them for the same reason.
**Do not "fix" this.** If it is ever revisited it needs a user-facing opt-in, not a silent change.

### The dependency finding

We are pinned to `connect-client:1.1.0-alpha07`. The comment beside it says "alpha11+ require
compileSdk 35". Both halves are wrong, and the truth is better than the comment:

| Version | minCompileSdk | `SkinTemperatureRecord` |
|---|---|---|
| **1.1.0-alpha08** | **34** | **yes** |
| 1.1.0-alpha09 … alpha12 | 35 | yes |
| 1.1.0-beta02, 1.1.0 (stable) | 36 | yes |

Read from each AAR's `aar-metadata.properties`. Our toolchain is AGP 8.5.2 / Gradle 8.7 /
compileSdk 34, and AGP 8.5.2 warns above compileSdk 34, so 1.1.0 stable would drag in an AGP and
Gradle upgrade as well.

**`1.1.0-alpha08` is a one-line bump that needs no toolchain change and unlocks skin temperature.**
Verified: `compileFullDebugKotlin` succeeds against it at compileSdk 34. Take that now; leave the
move to 1.1.0 stable to its own release, where the AGP bump can be tested on its own.

### What we could add

Of the 41 record types Health Connect defines, 38 exist in alpha08 (`SkinTemperatureRecord`,
`ActivityIntensityRecord`, `MindfulnessSessionRecord` and `PlannedExerciseSessionRecord` arrived
later — only the first of those matters to us, and it IS in alpha08).

**Write — data NOOP owns and nothing else can supply:**

| Record | Source | Why |
|---|---|---|
| `SkinTemperatureRecord` | `dailyMetrics.skinTempAbsC` | the headline gain; almost nothing else on a phone measures it, and we store both the absolute value and the baseline delta, which maps onto the record's `Delta` type |
| `BasalMetabolicRateRecord` | profile + our BMR | we already compute it for calories |
| `Vo2MaxRecord` | `vo2max` | we read VO2 max but never write ours back |
| `BodyFatRecord` | profile | read-only today |

**Read — inputs that would improve our own scores:**

| Record | Feeds |
|---|---|
| `HeightRecord` | BMI, BMR and the Nes VO2 max estimate; today height is profile-entry only |
| `HydrationRecord`, `NutritionRecord` | the nutrition CSV import already models this; Health Connect would remove the manual step |
| `BloodPressureRecord` | **the BP work is parked waiting for a cuff** — a phone-paired cuff writes here, which is a way to get calibration readings with no new hardware integration |
| `BodyTemperatureRecord` | an independent check on skin temperature |
| `BasalBodyTemperatureRecord`, `MenstruationPeriodRecord` | `CyclePhaseEngine` currently has no data source |
| `ExerciseSessionRecord` route/laps | richer workout detail than we import today |

**Deliberately out:** blood glucose, cervical mucus, ovulation tests, sexual activity, wheelchair
pushes, cycling cadence, power, speed, elevation, floors — no NOOP surface consumes them, and
requesting permissions we do not use is exactly what Google's Health Connect review rejects.

### Order of work

1. **Bump to `1.1.0-alpha08`** and correct that stale comment. One line, no toolchain change.
2. **Write `SkinTemperatureRecord`.** Biggest user-visible win, uses data only the strap has.
3. **Read `HeightRecord` + `BloodPressureRecord`.** Height improves three existing estimates;
   BP is the cheapest possible path to unparking the BP work.
4. **Write `Vo2MaxRecord` + `BasalMetabolicRateRecord`.** Small, and closes the read/write asymmetry.
5. **Cycle-tracking reads** for `CyclePhaseEngine`, if that engine is staying.
6. **Nutrition/hydration reads**, replacing the manual CSV step.

Each step is a permission the user must grant, so each needs its rationale string and a Data Sources
entry. Steps 1-2 are the release; 3 onwards can slip to 9.0.3 without leaving anything half-done.

### Things to get right

- **Every new permission needs a declared rationale.** Health Connect's policy is that an app may
  only request types it visibly uses. Adding a permission with no screen behind it risks the whole
  integration.
- **`isSelfWritten` already guards the import loop** — records NOOP wrote must never be re-imported
  as if they came from elsewhere. Any new read type needs the same guard, and its own test; there is
  a test for this today (`HealthConnectSelfWriteSkipTest`) and it should grow a case per type.
- **`clientRecordId` / `clientRecordVersion`** are how a recompute replaces a day rather than
  duplicating it. Keep the `noop-<metric>-<day>` shape for every new writer.
- Nothing here changes what a number *is*; this is transport. The scores stay in whoop-rs.

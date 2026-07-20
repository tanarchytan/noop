# Test Suite Audit: Post-Border-Cutover State

**Goal:** Every test tests something real. No test for deleted code. No gap where a correctness
fault can hide. Rust owns algorithm tests; Kotlin owns integration + UI + plumbing tests.

---

## Tests that become REDUNDANT after border cutover

These test Kotlin algorithm code that will be deleted. Parity tests confirm Rust equivalence
before deletion; after deletion, the Rust tests are the guard.

### Delete when `SleepStager.kt` deleted (after #1)

| Test | Lines | Why redundant |
|---|---|---|
| `RustSleepStagerParityTest` | ~200 | Tests Kotlin detector = Rust detector. After cutover, no Kotlin detector exists. Rust golden tests cover detection. |
| `SleepMotionLineTest` | ~100 | Tests Kotlin V2 staging line output. Replaced by `whoop-rs golden_tests.rs` frozen hypnogram. |
| `SleepStageHealTest` | ~100 | Tests Kotlin stage healer. Healer logic stays in Kotlin (edit plumbing), but V2 staging is in Rust now. Verify healer still works with Rust-staged segments. |
| `SleepEditDurabilityTest` | ~50 | Tests edit persistence. Plumbing test — KEEP. Not an algorithm test. |

**Verdict:** `RustSleepStagerParityTest` deleted after #1. `SleepMotionLineTest` replaced by Rust golden tests. `SleepStageHealTest` KEPT (plumbing). `SleepEditDurabilityTest` KEPT.

### Delete when `HrvAnalyzer.kt` deleted (after #9)

| Test | Lines | Why redundant |
|---|---|---|
| `HrvAnalyzerGateTest` | ~50 | Tests Kotlin HRV gate logic. Deleted with `HrvAnalyzer.kt`. |
| `HrvAnalyzerRollingTest` | ~50 | Tests Kotlin rolling HRV. Deleted. |
| `HrvAnalyzerTraceTest` | ~50 | Tests Kotlin HRV trace. Deleted. |
| `HrvArtifactDensityTest` | ~50 | Tests Kotlin artifact detection. Deleted. |
| `HrvGapAwareTest` | ~100 | Tests gap-aware RMSSD. Keep as `RustHrvParityTest` guard. After cutover, replace with Rust unit tests. |
| `HrvRrCoverageTest` | ~50 | Tests R-R coverage thresholds. Deleted. |
| `HrvFreqDomainTest` | ~100 | Tests Kotlin frequency-domain HRV. Keep `RustHrvParityTest` coverage. |
| `HrvBaselineRecalibrationTest` | ~100 | Tests baseline recalibration. This tests `Baselines.kt` logic — KEEP until baselines move to Rust. |
| `RustHrvParityTest` | ~100 | Parity gate. Delete AFTER all HRV callers route through Rust and parity is proven. |
| `SpotHrvReadingTest` | ~50 | Tests live spot HRV reading. KEEP — this is a BLE/UI integration test, not algorithm. |
| `ContinuousHrvWindowTest` | ~50 | Tests live continuous HRV window. KEEP — BLE integration. |
| `HrvGoldAgreementTest` | ~100 | Tests HRV vs ECG gold. KEEP — valuable validation, should be ported to Rust. |
| `HrvOpticalRobustnessTest` | ~100 | Tests optical HRV robustness. KEEP — port to Rust. |
| `HrvFreqAgreementTest` | ~50 | Frequency HRV agreement. KEEP — port to Rust. |

**Verdict:** Delete 6 HRV algorithm tests. Keep 3 agreement tests (port to Rust). Keep 2 BLE integration tests. Keep 1 parity test until proven.

### Delete when `StrainScorer.kt` deleted (after #5)

| Test | Lines | Why redundant |
|---|---|---|
| `RustStrainParityTest` | ~100 | Parity gate. Delete AFTER all Effort callers route through Rust. |
| `ChargeEffortRestScoringTest` | ~200 | Tests Charge + Effort + Rest scoring. Keep — integration test, but update to call Rust FFI. |

**Verdict:** `RustStrainParityTest` deleted. `ChargeEffortRestScoringTest` KEPT (integration).

### Delete when `StressModel.kt` + `DaytimeStress.kt` + `StressIndex.kt` + `StressOnsetDetector.kt` deleted (after #13-16)

| Test | Lines | Why redundant |
|---|---|---|
| `StressModelTest` | ~100 | Tests Kotlin daily Stress. Deleted. |
| `DaytimeStressTest` | ~100 | Tests Kotlin daytime Stress. Deleted. |
| `StressOnsetDetectorTest` | ~100 | Tests Kotlin onset detector. Deleted. |
| `RustStressParityTest` | ~50 | Parity gate. Delete after cutover. |
| `AiCoachStressLineTest` | ~50 | Tests AI coach stress insights. KEEP — this tests coach output, not stress math. |

**Verdict:** Delete 4 stress algorithm tests. Keep 1 coach test.

### Delete when `Baselines.kt` shrunk (after #17)

| Test | Lines | Why redundant |
|---|---|---|
| `BaselineSeedingTest` | ~100 | Tests baseline seeding. Keep but update to test FFI bridge. |
| `IntelligenceBaselineShadowTest` | ~100 | Tests baseline shadow (pass-2 chronology). Keep — integration test. |
| `HrvBaselineRecalibrationTest` | ~100 | Already listed above. |
| `DeviceEraEpochTest` | ~50 | Tests source-brand era detection. Keep — stays in Kotlin or moves to Rust. |

**Verdict:** All KEPT but updated to test the FFI bridge instead of Kotlin internals.

### Delete when `RecoveryScorer.kt` parity reference deleted (after #10-11)

| Test | Lines | Why redundant |
|---|---|---|
| `RecoveryScorerTraceTest` | ~50 | Tests Kotlin recovery trace. Deleted. |
| `RustRecoveryParityTest` | ~100 | Parity gate. Delete after Kotlin RecoveryScorer deleted. |
| `RecoveryAgreementTest` | ~100 | Recovery formula agreement. KEEP — port to Rust. |
| `RecoveryDriversTest` | ~100 | Tests "What shaped it" drivers. KEEP — UI integration test. |
| `RecoveryForecastTest` | ~50 | Tests recovery forecast. Move algorithm to Rust, keep integration test. |
| `RecoveryIndexActivityBalanceTest` | ~50 | Tests dormant terms. KEEP or delete based on dormancy decision. |
| `WatchRecoveryTest` | ~50 | Tests aggregate-only scoring. KEEP — integration test. |
| `IntelligenceWatchRecoveryTest` | ~50 | Tests watch recovery engine. KEEP — integration. |

**Verdict:** Delete 2 recovery algorithm tests. Keep 6 integration/agreement tests.

### Other parity tests to delete after cutover

| Test | Deleted with |
|---|---|
| `RustHrZonesParityTest` | HR zones Kotlin mirror |
| `RustRespRateParityTest` | Kotlin respiration math |
| `RustRestingHrParityTest` | Kotlin resting HR math |
| `RustSpo2ParityTest` | KEEP — SpO2 decode is protocol, stays in whoop-rs, parity valuable |
| `RustVo2maxParityTest` | KEEP — VO2max already in Rust only, parity test proves FFI works |

---

## Tests that MUST be ADDED before cutover

| Test | Protects | Priority |
|---|---|---|
| `EffortPerIntervalTest` (Rust) | Per-interval TRIMP integration produces correct values for edge cases | HIGH |
| `ChargeSkinOrderingTest` (Kotlin, end-to-end) | Pass-2 skin temp computed before Charge → Charge changes | HIGH |
| `ChargeChronologicalBaselineTest` (Kotlin, end-to-end) | Day D scored from state through D-1, not shared baseline | HIGH |
| `RrBeatOrderTest` (Kotlin, schema) | Room migration adds beatSeq, emission order preserved | MEDIUM |
| `DailyStressBaselineGateTest` (Rust) | <14 baseline days → null, not 1.5 | MEDIUM |
| `DaytimeStressExerciseGateTest` (Rust) | Exercise hours suppressed, not scored as Stress | MEDIUM |
| `AnalyzeSleepEndToEndTest` (Kotlin) | `analyzeDay` calls `analyzeSleep` FFI → valid sleep sessions | HIGH |
| `MainNightSelectionFfiTest` (Kotlin) | Main-night selection through FFI matches stored sessions | MEDIUM |
| `RestFormulaParityTest` (Rust→Kotlin) | Rust Rest matches Kotlin Rest on real data | MEDIUM |
| `BaselineEwmaRoundTripTest` (Rust) | Serialize → deserialize → same center/spread/tier | HIGH |

---

## Tests that should be PORTED to Rust (from Kotlin agreement fixtures)

| Kotlin test | Rust equivalent |
|---|---|
| `HrvGoldAgreementTest` | `physio-algo/tests/hrv_gold_agreement.rs` |
| `HrvOpticalRobustnessTest` | `physio-algo/tests/hrv_optical_robustness.rs` |
| `HrvFreqAgreementTest` | `physio-algo/tests/hrv_freq_agreement.rs` |
| `RecoveryAgreementTest` | `physio-algo/tests/recovery_agreement.rs` |
| `RealDataRundownTest` | `physio-algo/tests/real_data_rundown.rs` |

These read from `whoop-data/harnesses/agreement-fixtures/` JSON files. Porting them ensures
Rust produces the same output as the Kotlin originals on real data.

---

## Test count impact

| Phase | Delete | Add | Net |
|---|---|---|---|
| Phase 0 (parity swaps) | 3 Rust*ParityTest | 0 | -3 |
| Phase 1 (sleep cutover) | RustSleepStagerParityTest, SleepMotionLineTest | AnalyzeSleepEndToEndTest | -1 |
| Phase 2 (Effort/HRV/RHR/resp) | 10 algorithm tests + 3 parity tests | EffortPerIntervalTest + 5 Rust ports | -8 |
| Phase 3 (Stress) | 4 stress tests + RustStressParityTest | 2 gate tests | -3 |
| Phase 4 (Recovery/Baselines) | 2 recovery tests + RustRecoveryParityTest | 3 end-to-end tests | 0 |
| **Total** | **~25 tests deleted** | **~12 tests added** | **~13 fewer tests** |

Net reduction is fine — the deleted tests tested code that no longer exists. The added tests
protect the new FFI borders and the correctness fixes.

---

## Test suite completeness verdict

| Subsystem | Algorithm coverage | Integration coverage | Gap |
|---|---|---|---|
| Sleep detection | Rust detect.rs tests (30) + Kotlin parity | 0 end-to-end tests | **AnalyzeSleepEndToEndTest missing** |
| Sleep staging | Rust golden tests (6) + DREAMT benchmark | SleepStageSegmentsTest | OK |
| Main-night selection | Rust mainnight.rs tests (15+) + Kotlin mirror tests | MainNightConsistencyTest | OK |
| Effort | Rust strain.rs tests (golden) + Kotlin parity | 0 cadence tests | **Per-interval integration tests missing** |
| HRV | Rust hrv.rs tests + Kotlin agreement fixtures | BLE integration tests | OK (after parity swap) |
| Charge | Rust recovery.rs tests + Kotlin parity | ChargeEffortRestScoringTest | **Skin ordering + chronological baseline tests missing** |
| R-R ordering | 0 | Room migration tests | **RrBeatOrderTest missing** |
| Daily Stress | Rust stress.rs (Baevsky only) + Kotlin StressModelTest | 0 baseline gate test | **Missing gate test + missing formula test** |
| Daytime Stress | Kotlin DaytimeStressTest only | 0 exercise gate test | **Missing gate test** |
| Baselines | Kotlin BaselineSeedingTest | IntelligenceBaselineShadowTest | **Missing EWMA round-trip test** |
| Skin temp | SkinTempAnalyticsTest | 0 end-to-end Charge test | OK (covered by Charge ordering test) |
| Rest formula | ChargeEffortRestScoringTest (partial) | 0 focused test | **RestFormulaParityTest missing** |

**8 tests need adding before any score-changing fix ships.**

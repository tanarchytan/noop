# Today UI Refactor Verification

## Scope

`TodayScreen.kt` was split by responsibility after its dead-code pass. Rendering,
state keys, effect keys, repository reads, calculations, callbacks, and navigation
remain unchanged.

## Resulting Files

| File | Lines | Responsibility |
|---|---:|---|
| `TodayScreen.kt` | 1,427 | State, loading, dialogs, composition |
| `TodayActions.kt` | 265 | Workout, live-session, quick-action controls |
| `TodayNavigation.kt` | 439 | Day navigation, header, sync, battery, wordmark |
| `TodayHero.kt` | 596 | Score vessels, synthesis, recovery vitals |
| `TodayCards.kt` | 609 | Dashboard cards and editor |
| `TodayRecovery.kt` | 982 | Charge, contributors, score state, provenance |
| `TodayMetrics.kt` | 828 | Key metrics, readiness, formatting, metric editor |
| `TodayHeartRate.kt` | 884 | HR windows, chart gestures, annotations |
| `TodayFooter.kt` | 224 | Workout and source summaries |

## Equivalence Checks

- Complete top-level declaration multiset matches the cleaned source: 129 each.
- Reassembled declaration bodies match after visibility, comment, and whitespace
  normalization: 123,883 characters each.
- Visibility changed only where package-level cross-file access required it.
- Structure test pins every declaration to one planned owner.
- All files remain UTF-8 without BOM and use CRLF exclusively.
- Forced Kotlin compilation reports no Today UI warning.

## Test Evidence

`TodayUiStructureTest` failed before extraction because target files were absent.
It passes after extraction and enforces the 1,800-line orchestration limit.

Passing checks:

```text
gradlew compileFullDebugKotlin -Pkotlin.incremental=false
gradlew testFullDebugUnitTest --tests 'com.noop.ui.TodayUiStructureTest'
gradlew testFullDebugUnitTest --tests 'com.noop.ui.*Today*' plus related recovery and HR tests
```

Full verification:

```text
gradlew testFullDebugUnitTest
gradlew assembleFullDebug
git diff --check
```

Android lint retains the project baseline of 13 errors and 89 warnings, led by
`WhoopBleClient.kt` missing-permission errors. Today retains ten pre-existing
findings: two warnings and eight primitive-state information suggestions.

Code review found no behavior, ownership, visibility, initialization, annotation,
state-key, effect-key, or declaration-integrity issue. Its CRLF finding was fixed.

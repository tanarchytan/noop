# Sleep UI Refactor Verification

## Scope

`SleepScreen.kt` was split by responsibility without changing UI behavior,
repository calls, Compose state keys, analytics formulas, or public entry points.

## Dead-Code Audit

The pre-refactor 3,814-line source had no orphan top-level declarations. Kotlin
reported no unused sleep-screen parameters or variables. Android lint reported no
dead sleep UI code.

## Resulting Files

| File | Lines | Responsibility |
|---|---:|---|
| `SleepScreen.kt` | 433 | State, loading, sheets, composition |
| `SleepEditor.kt` | 698 | Marks, deletion undo, night and nap editing |
| `SleepHero.kt` | 454 | Rest hero, sleep hero, naps, sleep window |
| `SleepMetrics.kt` | 1,040 | Metric tiles, charts, debt, need, consistency |
| `SleepModels.kt` | 733 | UI models, selection, derivation, formatting |
| `SleepTimeline.kt` | 617 | Stages, hypnogram, motion, interval parsing |

## Equivalence Checks

- Top-level declaration multiset matches the original source exactly.
- One private helper rename avoids package-level Kotlin overload collisions:
  `averageOrNull` became `sleepAverageOrNull`.
- Two boxed `Long` states became `mutableLongStateOf` after lint identified them.
- Reassembling split bodies in original order produces identical normalized source:
  123,841 characters on both sides.
- Files remain UTF-8 without BOM and preserve CRLF line endings.
- `git diff --check` reports no whitespace errors.

## Test Evidence

`SleepUiStructureTest` failed before extraction because target files were absent.
It passed after extraction and pins every top-level declaration to its planned
owner plus a 700-line orchestration limit.

Commands passing after final import cleanup:

```text
gradlew testFullDebugUnitTest --tests 'com.noop.ui.SleepUiStructureTest'
gradlew testFullDebugUnitTest --tests 'com.noop.ui.*Sleep*' --tests 'com.noop.ui.Stage*'
gradlew testFullDebugUnitTest
gradlew compileFullDebugKotlin -Pkotlin.incremental=false
gradlew assembleFullDebug
```

Full Android lint retains the exact pre-refactor project baseline: 13 errors and 89
warnings, led by `WhoopBleClient.kt` missing-permission findings. No sleep UI lint
finding remains; both sleep autoboxing findings were removed.

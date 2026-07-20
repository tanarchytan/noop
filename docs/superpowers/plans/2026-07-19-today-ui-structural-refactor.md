# Today UI Structural Refactor Plan

**Status:** DONE (verified 2026-07-19). All 9 files extracted, structure test green, verification report at `docs/superpowers/reports/2026-07-19-today-ui-refactor-verification.md`.

**Goal:** Split `TodayScreen.kt` by responsibility without runtime changes.

**Architecture:** Keep orchestration in `TodayScreen.kt`. Move actions, navigation,
hero, cards, recovery, metrics, heart-rate, and footer declarations into package-
local files. Promote only declarations required across files.

## Task 1: Pin boundaries

- Add `TodayUiStructureTest.kt`.
- Require all nine source files.
- Pin every top-level declaration to one owner.
- Require `TodayScreen.kt` below 1,800 lines.
- Run test and confirm RED before extraction.

## Task 2: Extract actions and navigation

- Create `TodayActions.kt`.
- Create `TodayNavigation.kt`.
- Move declarations without body edits.
- Compile and run day-navigation tests.

## Task 3: Extract hero and cards

- Create `TodayHero.kt`.
- Create `TodayCards.kt`.
- Move declarations without body edits.
- Compile and run Today card tests.

## Task 4: Extract recovery and provenance

- Create `TodayRecovery.kt`.
- Preserve score-state and provenance APIs.
- Compile and run recovery, carry, calibration, and provenance tests.

## Task 5: Extract metrics

- Create `TodayMetrics.kt`.
- Preserve metric order, values, captions, and editor behavior.
- Compile and run metric, step, weight, and readiness tests.

## Task 6: Extract heart-rate and footer

- Create `TodayHeartRate.kt`.
- Create `TodayFooter.kt`.
- Preserve chart state, gesture math, workout annotations, and source summaries.
- Compile and run HR-window, chart, workout-feed, and source tests.

## Task 7: Finish orchestration

- Remove unused imports from every file.
- Compare declaration multisets.
- Compare normalized declaration bodies against the cleaned source.
- Run structure and Today-focused tests.

## Task 8: Verify and review

- Run full unit suite.
- Force full Kotlin compilation.
- Assemble full debug APK.
- Run lint and confirm no Today findings.
- Run `git diff --check`.
- Check BOM, line endings, cycles, duplicate declarations, and Compose state keys.
- Request code review and resolve findings.

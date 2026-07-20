# Today UI Structural Refactor Design

## Goal

Turn the 6,106-line Today UI monolith into responsibility-owned Kotlin files.
Preserve rendering, state, callbacks, repository reads, analytics, and navigation.

## Prerequisite

Dead code was removed first. Forced compilation now reports no unused Today
bindings, and static reference scanning finds no orphan top-level declarations.

## Boundaries

| File | Responsibility |
|---|---|
| `TodayScreen.kt` | Screen state, data loading, dialogs, lazy-list composition |
| `TodayActions.kt` | Live-session entry, workout state, quick actions, dismiss controls |
| `TodayNavigation.kt` | Day navigation, header, sync, battery, wordmark |
| `TodayHero.kt` | Score vessels, synthesis, recovery vitals |
| `TodayCards.kt` | User dashboard cards and editor |
| `TodayRecovery.kt` | Charge breakdown, contributors, score state, provenance |
| `TodayMetrics.kt` | Key metrics, readiness, tiles, formatting, metric editor |
| `TodayHeartRate.kt` | HR windows, chart interaction, markers, annotations |
| `TodayFooter.kt` | Workouts and source summary sections |

## Rules

- Move existing declarations verbatim.
- Change visibility only for cross-file calls.
- Keep `TodayScreen` signature stable after dead-API cleanup.
- Keep state keys and effect keys unchanged.
- Keep repository calls unchanged.
- Preserve declaration ownership with a source-structure test.
- Keep `TodayScreen.kt` below 1,800 lines.
- Preserve UTF-8, CRLF, and existing comments.

## Verification

- Prove structure test red before extraction, green afterward.
- Compare declaration multisets before and after extraction.
- Reassemble moved declaration bodies and compare normalized source.
- Run Today-focused tests, full unit tests, compile, assemble, and lint audit.
- Run code review after extraction.

## Follow-On

Use this clean Today base for redesign work. Continue through remaining UI screens
one screen at a time, repeating dead-code audit, pure extraction, and verification.

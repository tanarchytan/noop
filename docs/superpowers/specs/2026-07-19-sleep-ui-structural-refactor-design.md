# Sleep UI Structural Refactor

## Goal

Split `SleepScreen.kt` into cohesive Kotlin files without changing UI, data flow,
navigation, persistence, or analytics behavior. Preserve all current working-tree
changes. Establish a clean base for a later visual redesign.

## Dead-Code Audit

Audit completed against the current 3,814-line working copy before extraction.

- Every top-level declaration has at least one call or type reference.
- Reachability checks found no orphaned private declaration chain.
- Kotlin compilation emitted no unused parameter or variable warning for
  `SleepScreen.kt`.
- Android lint emitted no dead-code finding for `SleepScreen.kt`.
- Lint reported two unrelated Compose autoboxing suggestions in this file.
- Full lint remains red from existing project-wide findings, led by BLE permission
  errors outside this refactor.

No sleep UI production code should be deleted during extraction. Any later dead-code
removal requires separate evidence and tests.

## File Boundaries

### `SleepScreen.kt`

Keep screen entry point, state collection, repository loading, sheet coordination,
and top-level section composition. This file owns orchestration only.

### `SleepHero.kt`

Move rest hero, sleep hero, naps card, sleep-window rows, and related presentation
helpers. Inputs remain immutable UI models and callbacks.

### `SleepTimeline.kt`

Move hypnogram, stage timeline, stage rows, motion strip, stage colors, persisted
segment parsing, interval construction, smoothing, and span calculations.

### `SleepEditor.kt`

Move night navigation, bed/wake editing, nap editing, date/time pickers, and edit
callbacks. Repository mutation remains coordinated through existing callbacks.

### `SleepMetrics.kt`

Move metric grid, debt ledger, stage comparison, duration trend, spark tiles,
hours-needed card, consistency card, and metric-detail sheet.

### `SleepModels.kt`

Move immutable sleep UI models, night selection, main-sleep grouping, model building,
fallback construction, formatting, and pure metric helpers.

## Dependency Direction

`SleepScreen.kt` composes extracted UI files. UI files consume types and pure helpers
from `SleepModels.kt` and `SleepTimeline.kt`. Extracted files must not collect flows,
launch repository reads, or acquire new global state.

Existing `internal` helpers referenced by tests, `TodayScreen.kt`, and
`CoupledScreen.kt` keep names, signatures, visibility, and package.

## Behavior Contract

- Pixel output remains unchanged.
- Design tokens remain unchanged.
- Navigation and edit behavior remain unchanged.
- Repository calls and keys remain unchanged.
- Compose state keys remain unchanged.
- Analytics values remain byte-identical.
- Existing working-tree edits remain intact.
- Comments follow the three-line house rule when moved or rewritten.

## Test Strategy

Add a source-structure regression test first. It must fail while sleep declarations
remain monolithic, then pass after extraction. Run existing sleep UI unit tests after
each file move. Finish with full Android unit tests, full-debug Kotlin compilation,
debug assembly, diff checks, and a dedicated Kotlin review pass.

Known project-wide lint failures remain documented, not folded into this refactor.

## Follow-On Sequence

After sleep verification, repeat the same dead-code audit and one-file-at-a-time
structural cycle for `TodayScreen.kt`. Continue through front-facing UI files in
navigation order. Each source file gets its own bounded refactor and verification
checkpoint before the next begins.

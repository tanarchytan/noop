# Today UI Dead-Code Audit

## Scope

Audit `TodayScreen.kt` before structural extraction. Semantic Kotlin graph tooling
was unavailable, so symbol reachability used a manual declaration/reference scan.
Confidence is high for compiler findings and moderate for static reachability.

## Baseline

- Source size: 6,247 lines.
- Top-level declarations: 137 before cleanup.
- Direct orphan declarations: 3.
- Forced Kotlin compile: 12 unused Today bindings.

## Removed

- Unused `TodayScreen.onOpenUpdates` callback and caller binding.
- Unused `scoringCardSeen` Compose state.
- Retired `launchDayOffset` data-shape parameters.
- Unused ring, hero, and metric-grid parameters.
- Unconsumed step-activity and Rest-spark async reads.
- Unreferenced `stepsEstimateCaption` formatter.
- Unreferenced 14-day `Window` model and builder.
- Orphan recording chip, spark tile, and Rest-caption renderer.
- Transitive orphan step-icon and score-info helpers.

## Result

- Source size: 5,914 lines.
- Top-level declarations: 129 after cleanup.
- Orphan top-level declarations: none.
- Forced Kotlin compile: no Today UI unused-binding warnings.
- `TodayLaunchLandTest`: passing with the reduced policy API.
- `git diff --check`: clean.

Comment- and string-stripped identifier scanning confirms every surviving
declaration has a source or test reference.

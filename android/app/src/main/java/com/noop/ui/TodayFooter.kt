package com.noop.ui

import com.noop.data.WorkoutRow

// The Today "Last Workouts" contract, pure and unit-locked (LastWorkoutsFeedTest): cross-source
// dedup, newest first, at most four. The seam already dedups, so the dedup here is an
// idempotent guard that keeps the contract honest for any future caller feeding a raw union.
internal fun lastWorkoutsFeed(rows: List<WorkoutRow>): List<WorkoutRow> =
    WorkoutEditing.dedupCrossSource(rows)
        .sortedByDescending { it.startTs }
        .take(4)


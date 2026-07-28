package com.noop.analytics

import com.noop.data.HrSample

/**
 * Re-scores a manual workout's HR-derived metrics (avg/peak HR, strain, calories) from the HR
 * samples now available for its time window.
 *
 * A manual workout is scored at save time from sparse live HR (WHOOP 5.0/MG), so calories
 * collapse toward ~1 kcal, average is off, and strain is empty. Once the strap's denser
 * offloaded HR covers the window, this recomputes from it.
 *
 * Pure and deterministic. The post-sync pass feeds it under-scored `manual` workouts and
 * persists only a genuine improvement, matching `AppViewModel.endWorkout`'s formulas.
 */
object ManualWorkoutRescore {

    data class Scored(val avgHr: Int, val maxHr: Int, val strain: Double?, val kcal: Double?)

    /** At/under this many kcal a manual workout looks under-scored (no/negligible energy). */
    const val UNDER_SCORED_KCAL_THRESHOLD = 5.0
    /** A rescore must beat the stored calories by at least this to be persisted — so a still-sparse
     *  window (recompute ≈ current) is a no-op and the pass is idempotent. */
    const val IMPROVEMENT_MARGIN_KCAL = 1.0

    /** Does this manual workout currently look under-scored? The gate the post-sync pass uses so a
     *  well-scored workout (a 4.0's dense live HR) is never touched. */
    fun looksUnderScored(currentKcal: Double?): Boolean =
        (currentKcal ?: 0.0) <= UNDER_SCORED_KCAL_THRESHOLD

    /** Recompute avg/peak HR, strain and calories from [windowSamples] (the HR now stored for the
     *  workout's [start, end]). Returns null when there are too few samples to score meaningfully. */
    fun scored(windowSamples: List<HrSample>, profile: UserProfile, hrMax: Double): Scored? {
        if (windowSamples.size < 2) return null
        val bpms = windowSamples.map { it.bpm }
        // Integer mean, matching AppViewModel.endWorkout (truncates toward zero).
        val avg = bpms.sum() / bpms.size
        val peak = bpms.maxOrNull() ?: 0
        val strain = StrainScorer.strain(windowSamples, maxHR = hrMax, sex = profile.sex)
        val kcalRaw = Calories.estimateBoutCalories(windowSamples, profile, hrMax, null).first
        return Scored(avg, peak, strain, if (kcalRaw > 0) kcalRaw else null)
    }

    /** Is [scored] a worthwhile improvement? Strictly more energy always qualifies (never lowers
     *  a workout's numbers) and is the only path for a plain 2-arg call. With [allowStrainOnlyFill],
     *  a merged workout's null strain (summed kcal never looks under-scored) can also be filled. */
    fun improves(
        scored: Scored,
        currentKcal: Double?,
        currentStrain: Double? = null,
        allowStrainOnlyFill: Boolean = false,
    ): Boolean {
        val newK = scored.kcal
        if (newK != null && newK > (currentKcal ?: 0.0) + IMPROVEMENT_MARGIN_KCAL) return true
        // Strain-only improvement: fill a missing strain even when kcal doesn't beat the stored sum.
        return allowStrainOnlyFill && currentStrain == null && scored.strain != null
    }
}

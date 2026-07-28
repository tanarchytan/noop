package com.noop.analytics

import kotlin.math.roundToInt

/**
 * Pure, testable daily fluid-goal engine for the Hydration tracker.
 *
 * Daily goal (ml) = sexBaseline + effortBump, rounded to the nearest 50.
 *   - sexBaseline: male 3700, female 2700, unspecified/other 3200, from `UserProfile.sex`
 *     ("male" | "female" | "nonbinary").
 *   - effortBump: round(effort / 100 * 700) capped to 0..700 when today's Effort/strain (0..100)
 *     is available, else 0.
 *
 * A target derived only from the body profile and the day's load — never from logged totals,
 * which live in the metric-series store.
 */
object HydrationGoal {

    /** Sex baselines (ml). */
    const val BASELINE_MALE: Int = 3700
    const val BASELINE_FEMALE: Int = 2700
    const val BASELINE_OTHER: Int = 3200

    /** The most extra fluid a hard day can add (ml). The effort bump is capped here. */
    const val MAX_EFFORT_BUMP: Int = 700

    /** Goals are rounded to the nearest multiple of this (ml) so the readout is a clean round number. */
    const val ROUND_TO: Int = 50

    /** Quick-log amounts (ml). Each tap adds one of these to the day total. */
    const val SIP_ML: Int = 30
    const val CUP_ML: Int = 237
    const val BOTTLE_ML: Int = 500

    /**
     * The sex baseline (ml) for a profile `sex` tag. Anything besides "male" / "female" (nonbinary,
     * unspecified, or unknown) falls to the neutral [BASELINE_OTHER]. Case- and whitespace-insensitive.
     */
    fun baselineForSex(sex: String): Int = when (sex.trim().lowercase()) {
        "male", "m" -> BASELINE_MALE
        "female", "f" -> BASELINE_FEMALE
        else -> BASELINE_OTHER
    }

    /**
     * The effort bump (ml) for an Effort/strain score in 0..100, or 0 when [effort] is null (no Effort
     * scored yet). `round(effort / 100 * 700)`, then clamped into 0..[MAX_EFFORT_BUMP] so an out-of-range
     * input can't push the goal past the cap or below the baseline.
     */
    fun effortBump(effort: Double?): Int {
        if (effort == null) return 0
        val raw = (effort / 100.0 * MAX_EFFORT_BUMP).roundToInt()
        return raw.coerceIn(0, MAX_EFFORT_BUMP)
    }

    /**
     * The daily goal (ml): [baselineForSex] + [effortBump], rounded to the nearest [ROUND_TO]. [effort]
     * is today's Effort/strain (0..100) or null when not yet scored. Pure — no store reads.
     */
    fun dailyGoalMl(sex: String, effort: Double?): Int {
        val raw = baselineForSex(sex) + effortBump(effort)
        return roundToNearest(raw, ROUND_TO)
    }

    /** Round [value] to the nearest multiple of [step] (step > 0), rounding half up. */
    fun roundToNearest(value: Int, step: Int): Int {
        if (step <= 0) return value
        return ((value + step / 2) / step) * step
    }
}

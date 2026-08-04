package com.noop.analytics

/**
 * The Hydration tracker's seam onto whoop-rs: the daily fluid goal and its parts are computed there,
 * so the baselines, the Effort bump ceiling and the rounding grid have one owner. What stays here is
 * the quick-log ladder — how much one tap adds, which is a choice about what to store, not a formula.
 */
object HydrationGoal {

    /** Quick-log amounts (ml). Each tap adds one of these to the day total. */
    const val SIP_ML: Int = 30
    const val CUP_ML: Int = 237
    const val BOTTLE_ML: Int = 500

    /** Baselines, bump ceiling and rounding grid, read from whoop-rs. */
    val cfg: uniffi.whoop_ffi.HydrationCfgInfo by lazy { uniffi.whoop_ffi.hydrationCfg() }

    /** The sex baseline (ml) for a profile `sex` tag ("male" | "female" | "nonbinary"). */
    fun baselineForSex(sex: String): Int = uniffi.whoop_ffi.hydrationBaselineForSex(sex)

    /** The extra fluid (ml) today's Effort (0..100) adds, or 0 when the day is unscored. */
    fun effortBump(effort: Double?): Int = uniffi.whoop_ffi.hydrationEffortBumpMl(effort)

    /** The displayed daily goal (ml). [effort] is today's Effort (0..100), null when unscored. */
    fun dailyGoalMl(sex: String, effort: Double?): Int =
        uniffi.whoop_ffi.hydrationDailyGoalMl(sex, effort)
}

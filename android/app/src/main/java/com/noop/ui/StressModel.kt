package com.noop.ui

import com.noop.data.DailyMetric
import com.noop.ui.whoop.dailyStressScore

// MARK: - Stress model (stored value, else the whoop-rs derivation)
//
// The 0-3 autonomic-load read for a day: which day to score, and whether a stored value wins over the
// derivation. The score itself is whoop-rs's; nothing here recomputes it.

// Read by the Today stress card through WhoopHomeState. The constructor stays private; only the
// companion `build` factory is exposed.
internal class StressModel private constructor(
    val score: Double, // 0–3 (today)
) {
    companion object {
        /** Build from oldest→newest daily metrics plus any stored "stress" series. Returns null
         *  only when there is no usable signal at all. */
        fun build(days: List<DailyMetric>, stored: Map<String, Double>): StressModel? {
            // Score the NEWEST day carrying usable signal (RHR/HRV or a stored value) rather than
            // calibrating on a vitals-less today; falls back to the last row when no day has any.
            val idx = days.indexOfLast {
                it.restingHr != null || it.avgHrv != null || stored.containsKey(it.day)
            }.let { if (it >= 0) it else days.size - 1 }
            if (idx < 0) return null   // no days at all

            // One owner for "score this day": the stored value if there is one, else whoop-rs's
            // daily_stress over the trailing baseline. Null rather than a fabricated mid-scale score.
            val score = dailyStressScore(days, stored, idx) ?: return null
            return StressModel(score = score)
        }
    }
}

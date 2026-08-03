package com.noop.ui

import com.noop.analytics.RustScores
import com.noop.data.DailyMetric
import com.noop.ui.whoop.STRESS_BASELINE_DAYS

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
            // calibrating on a vitals-less today. The predicate mirrors the storedToday||derived gate
            // below; falls back to the last row when no day has any signal.
            val idx = days.indexOfLast {
                it.restingHr != null || it.avgHrv != null || stored.containsKey(it.day)
            }.let { if (it >= 0) it else days.size - 1 }
            if (idx < 0) return null   // no days at all
            val today = days[idx]

            // Baseline window ends the day BEFORE the scored day, so it is measured against its own
            // recent past rather than itself. Same window the Health stress detail reads.
            val baseline = if (idx > 0) days.subList(0, idx).takeLast(STRESS_BASELINE_DAYS) else emptyList()
            val baselineDays = baseline.map { it.restingHr?.toDouble() to it.avgHrv }

            // Score + baseline gate live in whoop-rs (daily_stress): a value only above the 14-day
            // baseline floor with usable signal, else null.
            val storedToday = stored[today.day]
            val derivedToday: Double? =
                RustScores.dailyStress(today.restingHr?.toDouble(), today.avgHrv, baselineDays)
            // Nothing to show rather than a fabricated mid-scale score.
            val score = storedToday ?: derivedToday ?: return null
            return StressModel(score = score)
        }
    }
}

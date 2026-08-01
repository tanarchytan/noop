package com.noop.analytics

import java.util.Locale
import kotlin.math.roundToLong

/**
 * "~X days left" for a strap, worked out from its battery state-of-charge (SoC) history. Neither the
 * WHOOP app nor WHOOP's API give a runtime estimate, but NOOP already banks a SoC series from the strap
 * over BLE. We fit the recent DISCHARGE slope and divide the current charge by it; when the run is too
 * short or too flat to trust, we fall back to the device's typical full-charge life for its generation.
 *
 * The measured slope bakes in how the user actually runs their strap, so there are no hand-tuned usage
 * multipliers — the discharge curve IS the personalisation.
 *
 * Battery drain is non-linear (faster near full and near empty) and SoC reports are sparse, so this is
 * an ESTIMATE, not a guarantee.
 */
object BatteryEstimator {

    /** Typical full-charge life in hours per WHOOP generation, used before enough of the user's own
     *  discharge has been seen to fit a slope. WHOOP 4.0 is about 4.5 days, WHOOP 5.0 / MG about 12 days.
     *  The caller maps its connected strap to one of these. */
    const val ratedLifeHoursWhoop4 = 108.0   // 4.5 days
    const val ratedLifeHoursWhoop5 = 288.0   // 12 days

    /** A discharge run has to span at least this long AND drop at least this much before its measured
     *  slope is trusted over the rated fallback. Short or noisy spans produce wild rates. */
    const val minSpanHours = 2.0
    const val minDropPct = 2.0

    /** A SoC rise larger than this (percentage points) between two consecutive readings marks a CHARGE.
     *  The discharge run restarts after it, so we never fit a rate across a charge. */
    const val chargeStepPct = 1.0

    /** A charge only ANCHORS a fresh discharge run when it returns the strap NEAR FULL. A partial top-up
     *  (e.g. 40% -> 55%) does not reset the run — that would discard the long clean discharge history and
     *  inflate "days left" off a short tail — so a rise anchors only when post-rise SoC reaches this. */
    const val nearFullPct = 90.0

    /** Where the drain rate came from: the user's own measured discharge, or the rated fallback. */
    enum class Source { MEASURED, RATED }

    data class Estimate(
        /** Estimated hours of runtime left at the latest reading. */
        val remainingHours: Double,
        val source: Source,
        /** The latest SoC the estimate is anchored to, in percent. */
        val currentSoc: Double,
    ) {
        /** Convenience for callers that just want the days figure. */
        val daysRemaining: Double get() = remainingHours / 24
        /** Mirror so callers can read either name. */
        val hoursRemaining: Double get() = remainingHours
    }

    /**
     * Estimate remaining runtime from a SoC series.
     *
     * [samples] = (unix-seconds, SoC%) pairs in any order; the caller drops nil-SoC rows first.
     * [ratedHours] = the strap's typical full-charge life (one of the `ratedLifeHours…` constants) for
     * its generation. Returns null only when there isn't a single reading to anchor to.
     */
    fun estimate(samples: List<Pair<Long, Double>>, ratedHours: Double): Estimate? {
        val sorted = samples.sortedBy { it.first }
        val last = sorted.lastOrNull() ?: return null
        val current = last.second

        // The discharge segment whose slope we fit: anchored at the most recent NEAR-FULL charge, and ending
        // before any later partial top-up, so neither an earlier charge nor a quick desk top-up distorts the
        // fitted slope.
        val dischargeRun = dischargeFitWindow(sorted)

        // Fit the discharge slope over the segment as a simple endpoints rate (%/h): the series is short and
        // monotone-ish, so endpoints are as good as least-squares and far cheaper. null when too short, too
        // flat, or not discharging. The estimate stays anchored to `current` even when the fit window ends earlier.
        val measuredRate: Double? = run {
            if (dischargeRun.size < 2) return@run null
            val first = dischargeRun.first()
            val lastRun = dischargeRun.last()
            val spanHours = (lastRun.first - first.first) / 3600.0
            val drop = first.second - lastRun.second
            if (spanHours < minSpanHours || drop < minDropPct) return@run null
            val rate = drop / spanHours
            if (rate > 0) rate else null
        }

        val rate = measuredRate ?: (100.0 / maxOf(ratedHours, 1.0))
        val remaining = maxOf(0.0, current) / rate
        // Cap scaled to the CURRENT SoC, not a flat multiple of the FULL rated life: a too-slow measured
        // slope at low charge (idle/off-wrist spans, sparse 5/MG readings) could extrapolate 9% to ~3 days,
        // more than a full 12-day MG charge. Bound to ~1.5x the rated per-% runtime, scaled to `current`%.
        val clamped = minOf(remaining, ratedHours * 1.5 * (maxOf(0.0, current) / 100.0))
        return Estimate(clamped, if (measuredRate != null) Source.MEASURED else Source.RATED, current)
    }

    /**
     * The slice of the sorted SoC series whose endpoints we fit the discharge slope on. Starts at the most
     * recent NEAR-FULL charge (a partial top-up is stepped over) and ends before the next partial top-up,
     * so the fit uses the longer pre-top-up segment; `current` is read from the series end, not this window.
     */
    fun dischargeFitWindow(sorted: List<Pair<Long, Double>>): List<Pair<Long, Double>> {
        if (sorted.size < 2) return sorted

        // 1. Most recent NEAR-FULL charge anchors the run start; partial top-ups are stepped over.
        var startIdx = 0
        for (i in sorted.size - 1 downTo 1) {
            if (sorted[i].second > sorted[i - 1].second + chargeStepPct && sorted[i].second >= nearFullPct) {
                startIdx = i
                break
            }
        }

        // 1b. With no near-full charge to anchor on, anchor at the HIGHEST SoC instead of the oldest
        //     reading — the max is >= every later reading, so the window can only discharge. Bounded to
        //     the last two charge-step cycles so a strap that rarely tops to full anchors on CURRENT usage.
        if (startIdx == 0) {
            val chargeStepIdxs = mutableListOf<Int>()
            for (i in 1 until sorted.size) {
                if (sorted[i].second > sorted[i - 1].second + chargeStepPct) chargeStepIdxs.add(i)
            }
            val searchFloor = if (chargeStepIdxs.size >= 2) chargeStepIdxs[chargeStepIdxs.size - 2] else 0
            var maxIdx = searchFloor
            for (i in searchFloor until sorted.size) if (sorted[i].second >= sorted[maxIdx].second) maxIdx = i
            startIdx = maxIdx
        }

        // 2. End before the most recent PARTIAL top-up after the start anchor (a rise > chargeStepPct that
        //    does NOT reach near-full), so the fit prefers the longer pre-top-up discharge segment.
        var endIdx = sorted.size - 1
        if (endIdx - startIdx >= 1) {
            for (i in sorted.size - 1 downTo startIdx + 1) {
                if (sorted[i].second > sorted[i - 1].second + chargeStepPct && sorted[i].second < nearFullPct) {
                    endIdx = i - 1
                    break
                }
            }
        }
        if (endIdx <= startIdx) return sorted.subList(startIdx, sorted.size)
        return sorted.subList(startIdx, endIdx + 1)
    }

    /**
     * Side-effect-free diagnostic twin of [estimate]: returns the SAME Estimate plus trace lines describing
     * the full (t, soc) series, charge step(s), discharge-run start/span/drop, fitted slope, and which gate
     * decided source = measured vs rated. Gated behind TestCentre.active(BATTERY); off, it is never called.
     */
    fun estimateTrace(samples: List<Pair<Long, Double>>, ratedHours: Double):
        Pair<Estimate?, List<String>> {
        val sorted = samples.sortedBy { it.first }
        val last = sorted.lastOrNull()
        val first0 = sorted.firstOrNull()
        if (last == null || first0 == null) {
            return null to listOf("battery series=0 readings, no reading to anchor to")
        }
        val lines = mutableListOf<String>()
        lines.add("battery series=${sorted.size} readings span ${first0.first}..${last.first}s")
        for (s in sorted) lines.add("battery read t=${s.first}s soc=${soc(s.second)}")

        // The most recent NEAR-FULL charge anchors the run start (same scan as estimate); a partial
        // top-up does NOT anchor and is reported separately below.
        var startIdx = 0
        if (sorted.size >= 2) {
            for (i in sorted.size - 1 downTo 1) {
                if (sorted[i].second > sorted[i - 1].second + chargeStepPct && sorted[i].second >= nearFullPct) {
                    startIdx = i
                    val rise = sorted[i].second - sorted[i - 1].second
                    lines.add("battery chargeStep at t=${sorted[i].first}s +${soc(rise)}pp " +
                        "(>chargeStepPct ${soc(chargeStepPct)})")
                    break
                }
            }
        }
        // The most recent PARTIAL top-up after the anchor (a rise that does NOT reach near-full): the fit
        // ends before it and prefers the longer pre-top-up discharge segment.
        if (sorted.size >= 2 && startIdx < sorted.size - 1) {
            for (i in sorted.size - 1 downTo startIdx + 1) {
                if (sorted[i].second > sorted[i - 1].second + chargeStepPct && sorted[i].second < nearFullPct) {
                    val rise = sorted[i].second - sorted[i - 1].second
                    lines.add("battery partialTopUp at t=${sorted[i].first}s +${soc(rise)}pp " +
                        "(<nearFullPct ${soc(nearFullPct)}) -> fit pre-top-up segment")
                    break
                }
            }
        }
        val run = dischargeFitWindow(sorted)

        var spanPass = false
        var dropPass = false
        if (run.size >= 2) {
            val runFirst = run.first()
            val runLast = run.last()
            val spanHours = (runLast.first - runFirst.first) / 3600.0
            val drop = runFirst.second - runLast.second
            lines.add("battery dischargeRun start=${runFirst.first}s " +
                "span=${hrs(spanHours)}h drop=${soc(drop)}pp")
            spanPass = spanHours >= minSpanHours
            dropPass = drop >= minDropPct
            if (spanPass && dropPass && drop / spanHours > 0) {
                lines.add("battery slope=${slopeText(drop / spanHours)}pct/h fitted from run endpoints")
            }
        } else {
            lines.add("battery dischargeRun too short to fit (run=${run.size} readings)")
        }

        val measured = spanPass && dropPass && run.size >= 2 &&
            (run.first().second - run.last().second) /
            ((run.last().first - run.first().first) / 3600.0) > 0
        lines.add("battery gate minSpanHours ${hrs(minSpanHours)} " +
            "${if (spanPass) "PASS" else "FAIL"}, minDropPct ${soc(minDropPct)} " +
            "${if (dropPass) "PASS" else "FAIL"} -> source=${if (measured) "measured" else "rated"}")

        return estimate(samples, ratedHours) to lines
    }

    private fun soc(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun hrs(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun slopeText(v: Double) = String.format(Locale.US, "%.1f", v)

    /** Display rule: hours under 48h ("~14h"), days above ("~4.5 days"). Unit text only, the UI adds the
     *  "left" / "remaining" copy. Locale-fixed so the tests stay stable. */
    fun label(hours: Double): String =
        if (hours < 48) "~${hours.roundToLong()}h"
        else "~${String.format(Locale.US, "%.1f", hours / 24)} days"

    // ---- Predictive low-battery alert policy ----

    /** Fire the runtime alert when the estimate drops to this many hours of remaining life. A fixed
     *  SoC threshold gives wildly different lead time per strap generation (15% is ~16 h on a 4.0 but
     *  ~1.8 days on a 5.0/MG); a runtime threshold means the same "charge it tonight" warning for both. */
    const val runtimeAlertHours = 24.0

    /** Re-arm only when the estimate recovers to this. The 12 h hysteresis band means jitter in the
     *  fitted slope around the alert line can't re-fire; only a genuine charge opens the gate again. */
    const val runtimeRearmHours = 36.0

    data class RuntimeAlertDecision(val fire: Boolean, val newAlerted: Boolean)

    /**
     * Crossing-with-hysteresis decision for the predictive alert, mirroring BatteryAlertPolicy's shape:
     * PERSISTED `alerted` gate in, fire decision plus next gate state out. `charging == null` means unknown,
     * so the alert still fires — only a confirmed `true` suppresses it (the strap reports its charge bit rarely).
     */
    fun runtimeAlert(remainingHours: Double, charging: Boolean?, alerted: Boolean): RuntimeAlertDecision {
        var armedOff = alerted
        if (remainingHours >= runtimeRearmHours) armedOff = false
        val fire = !armedOff && remainingHours <= runtimeAlertHours && charging != true
        if (fire) armedOff = true
        return RuntimeAlertDecision(fire, armedOff)
    }
}

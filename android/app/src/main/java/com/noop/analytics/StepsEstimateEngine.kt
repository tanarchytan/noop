package com.noop.analytics

import com.noop.data.GravitySample
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Estimate daily steps for a WHOOP 4.0 from the strap's MOTION, calibrated per-user against a phone
 * step count (Apple Health / Health Connect).
 *
 * A WHOOP 4.0 sends no step count over BLE, and its gravity data is sparse (~one vector per record,
 * ~minute granularity) — far below the ~25-50 Hz a true step counter needs. So instead we measure
 * movement VOLUME (gravity-vector change over the day) and map it to steps via
 * `steps ≈ k · motionIntensity`, through-origin. `k` is personal (wrist placement, gait, strap fit):
 * fit as a MOTION-WEIGHTED median of per-day steps/motion ratios so one odd day can't drag it and
 * high-activity days (which pin the ratio more reliably) drive the fit more than near-still ones. A
 * user with no phone step history can set `k` by hand via the calibration slider. Output is always
 * framed as an estimate, never a measured count.
 */
object StepsEstimateEngine {

    /** Fewest calibration days (each with both motion and a reference step count) before we trust an auto-fit `k`. */
    const val MIN_CALIBRATION_DAYS = 3
    /** Calibration days at/above which confidence saturates toward 1. */
    const val GOOD_CALIBRATION_DAYS = 14
    /** A day must move at least this much (summed gravity-delta) to enter the fit / produce an estimate. */
    const val MIN_MOTION_FOR_FIT = 1.0
    /** Sanity clamp on a daily estimate. */
    const val MAX_DAILY_STEPS = 60_000

    /** One calibration day: the strap's motion volume and the phone's step count for the SAME day. */
    data class CalibrationPoint(val motion: Double, val steps: Double)

    /** The fitted (or manually-set) personal model. */
    data class Calibration(
        val coefficient: Double,
        val sampleDays: Int,
        val confidence: Double,
        val manual: Boolean,
    )

    /**
     * A coarse confidence tier for the auto-fit, for a one-word badge on the steps tile/Settings.
     * Derived from the engine's 0-1 confidence by fixed thresholds. A manual `k` is always reported
     * as [HIGH] (the user asserted it).
     */
    enum class ConfidenceTier(val word: String) {
        LOW("low confidence"),
        MEDIUM("medium confidence"),
        HIGH("high confidence");

        companion object {
            /** 0-1 confidence -> tier: < 0.34 low, < 0.67 medium, else high. */
            fun from(confidence: Double): ConfidenceTier = when {
                confidence < 0.34 -> LOW
                confidence < 0.67 -> MEDIUM
                else -> HIGH
            }
        }
    }

    /**
     * A readable read-out of the calibration state, for the Today steps tile and the Settings section.
     * Pure data (no UI strings beyond a single short status line) so both surfaces stay in step.
     */
    sealed interface CalibrationStatus {
        /** True when an estimate can be produced right now (manual or a usable auto-fit). */
        val canEstimate: Boolean

        /** A short, honest one-liner for the tile/Settings, locale-neutral (no em-dashes). */
        val headline: String

        /** The confidence tier for the steps estimate. [Calibrated] maps its 0-1 confidence; [Manual] is
         *  HIGH (asserted by the user); [NeedsMoreDays] is LOW. */
        val confidenceTier: ConfidenceTier

        /** The personal coefficient `k` in force, or null when none is fit/set yet. */
        val coefficientOrNull: Double?

        /** A denser status line (numbers, vs the plain-English [headline]): confidence tier plus, when
         *  calibrated/manual, `k` and the day count, so a frozen or dashed steps tile self-explains. */
        val detail: String

        /** A manual `k` is in force. [sampleDays] = auto-fit days that exist alongside it (informational). */
        data class Manual(val coefficient: Double, val sampleDays: Int) : CalibrationStatus {
            override val canEstimate: Boolean get() = true
            override val headline: String get() = "Calibrated by hand"
            override val confidenceTier: ConfidenceTier get() = ConfidenceTier.HIGH
            override val coefficientOrNull: Double get() = coefficient
            override val detail: String get() = "manual k=${formatK(coefficient)}"
        }

        /** Enough overlapping days fit an auto coefficient. Carries the fit and its 0–1 confidence. */
        data class Calibrated(val coefficient: Double, val sampleDays: Int, val confidence: Double) : CalibrationStatus {
            override val canEstimate: Boolean get() = true
            override val headline: String
                get() = "Estimated from $sampleDays day${if (sampleDays == 1) "" else "s"} your phone also counted"
            override val confidenceTier: ConfidenceTier get() = ConfidenceTier.from(confidence)
            override val coefficientOrNull: Double get() = coefficient
            override val detail: String
                get() = "k=${formatK(coefficient)} from $sampleDays day${if (sampleDays == 1) "" else "s"}, ${ConfidenceTier.from(confidence).word}"
        }

        /** Not yet calibrated: [have] overlapping phone-counted days out of [need]. */
        data class NeedsMoreDays(val have: Int, val need: Int) : CalibrationStatus {
            override val canEstimate: Boolean get() = false
            override val headline: String
                get() {
                    val more = maxOf(0, need - have)
                    return "Need $more more day${if (more == 1) "" else "s"} where your phone also counted steps"
                }
            override val confidenceTier: ConfidenceTier get() = ConfidenceTier.LOW
            override val coefficientOrNull: Double? get() = null
            override val detail: String get() = "calibrating: ${minOf(have, need)}/$need days"
        }
    }

    /** Format the steps coefficient `k` to one decimal place for the status line, fixed to Locale.US so
     *  the format never varies with a device's regional settings. */
    internal fun formatK(k: Double): String = String.format(java.util.Locale.US, "%.1f", k)

    /**
     * Classify the current calibration state from the inputs [calibrate] sees. A positive
     * [manualOverride] always reports [CalibrationStatus.Manual]; otherwise, once usable days reach
     * [MIN_CALIBRATION_DAYS], report [CalibrationStatus.Calibrated], else [CalibrationStatus.NeedsMoreDays].
     */
    fun status(points: List<CalibrationPoint>, manualOverride: Double? = null): CalibrationStatus {
        val usableDays = points.count { it.motion >= MIN_MOTION_FOR_FIT && it.steps > 0 }
        if (manualOverride != null && manualOverride > 0) {
            return CalibrationStatus.Manual(manualOverride, usableDays)
        }
        val cal = calibrate(points)
        return if (cal != null && usableDays >= MIN_CALIBRATION_DAYS) {
            CalibrationStatus.Calibrated(cal.coefficient, cal.sampleDays, cal.confidence)
        } else {
            CalibrationStatus.NeedsMoreDays(have = usableDays, need = MIN_CALIBRATION_DAYS)
        }
    }

    /**
     * Total daily MOTION INTENSITY = sum of per-record gravity-vector deltas (L2 magnitude of the change
     * between consecutive samples) — the same movement-volume proxy the sleep stager uses for stillness,
     * integrated over the day.
     */
    fun dayMotionIntensity(grav: List<GravitySample>): Double {
        if (grav.size < 2) return 0.0
        var total = 0.0
        var prev = grav[0]
        for (i in 1 until grav.size) {
            val r = grav[i]
            val dx = prev.x - r.x; val dy = prev.y - r.y; val dz = prev.z - r.z
            total += sqrt(dx * dx + dy * dy + dz * dz)
            prev = r
        }
        return total
    }

    /**
     * Fit the personal coefficient from days with both a motion volume and a reference step count: a
     * MOTION-WEIGHTED median of each day's steps/motion ratio (days below MIN_MOTION_FOR_FIT skipped, each
     * ratio weighted by its motion volume so high-activity days drive the fit). Returns null below
     * MIN_CALIBRATION_DAYS unless a positive [manualOverride] is supplied (always wins, confidence 1).
     */
    fun calibrate(points: List<CalibrationPoint>, manualOverride: Double? = null): Calibration? {
        if (manualOverride != null && manualOverride > 0) {
            return Calibration(manualOverride, points.size, 1.0, manual = true)
        }
        // Usable days carry (ratio, weight) where weight = motion volume: a busier day votes harder.
        val weighted = points
            .filter { it.motion >= MIN_MOTION_FOR_FIT && it.steps > 0 }
            .map { Pair(it.steps / it.motion, it.motion) }
        if (weighted.size < MIN_CALIBRATION_DAYS) return null
        val ratios = weighted.map { it.first }
        val weights = weighted.map { it.second }
        val k = weightedMedian(ratios, weights)
        if (k <= 0) return null
        // Confidence: grows with sample size toward GOOD_CALIBRATION_DAYS, discounted by relative spread
        // (weighted MAD / weighted median) so a noisy fit is honestly less trusted than a tight one. The MAD is
        // also motion-weighted so spread is measured against the same days that drove `k`.
        val sizeTerm = min(1.0, weighted.size.toDouble() / GOOD_CALIBRATION_DAYS)
        val mad = weightedMedian(ratios.map { abs(it - k) }, weights)
        val spread = if (k > 0) mad / k else 1.0
        val tightness = (1.0 - spread).coerceAtLeast(0.0)
        val confidence = (0.5 * sizeTerm + 0.5 * tightness).coerceIn(0.0, 1.0)
        return Calibration(k, weighted.size, confidence, manual = false)
    }

    /**
     * Estimated steps for a day from its motion volume and the personal calibration. null below
     * MIN_MOTION_FOR_FIT (too little to say) — the UI then shows "—", never a fake 0.
     */
    fun estimate(motion: Double, calibration: Calibration): Int? {
        if (motion < MIN_MOTION_FOR_FIT || calibration.coefficient <= 0) return null
        return Math.round(motion * calibration.coefficient).toInt().coerceIn(0, MAX_DAILY_STEPS)
    }

    internal fun median(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * Weighted median of [xs] with per-element [weights]. Sort by value, walk the cumulative weight, and
     * return the value at which it first reaches half the total weight; on an exact half-mass boundary,
     * average the two straddling values (reduces to the plain even-count midpoint when weights are equal).
     * Falls back to the plain median if weights are absent/degenerate (empty, mismatched, non-positive total).
     */
    internal fun weightedMedian(xs: List<Double>, weights: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        if (weights.size != xs.size) return median(xs)
        val order = xs.indices.sortedBy { xs[it] }
        val total = weights.sum()
        if (total <= 0) return median(xs)
        val half = total / 2.0
        var cum = 0.0
        for (pos in order.indices) {
            val idx = order[pos]
            val w = weights[idx].coerceAtLeast(0.0)
            cum += w
            if (cum > half) return xs[idx]
            if (cum == half) {
                // Half-mass falls on a boundary: average this value with the next distinct one (if any).
                val next = if (pos + 1 < order.size) order[pos + 1] else idx
                return (xs[idx] + xs[next]) / 2.0
            }
        }
        return xs[order[order.size - 1]]
    }
}

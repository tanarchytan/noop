package com.noop.analytics

import com.noop.data.HrSample
import kotlin.math.ln
import kotlin.math.roundToLong

/*
 * Cardiovascular load ("Effort"), a 0–100 logarithmic scale. Independent implementation of
 * published exercise-physiology methods (WHOOP-like, not the proprietary algorithm; not
 * medical advice, an estimate).
 *
 * Karvonen %HRR = (HR − RHR) / HRR × 100 (clamped 0..100) accumulates into TRIMP (Edwards
 * 5-zone weights 1..5 at 50/60/70/80/90 %HRR, or Banister duration × x × 0.64 × e^(b·x)), then
 * effort = 100 × ln(TRIMP + 1) / ln(D). D = 7201 keeps the log curve fixed across the 21→100
 * rescale. Operates on Room [HrSample]; HRR zone math is independent of [HrZones]'s %HRmax zones.
 */
object StrainScorer {

    // ---- Constants ----

    /** Minimum HR readings before computing strain on a DENSE stream (≈10 min at 1 Hz). */
    const val minReadings: Int = 600
    /** Sparse-stream floor: a low-cadence strap (HR sample ~every 30 s) would take hours to reach
     *  [minReadings], so also accept once the series spans [minSpanSeconds] of wall-clock. TRIMP
     *  still integrates honestly — a genuine low-HR day scores 0 either way. */
    const val minSparseReadings: Int = 20
    /** Wall-clock coverage (seconds) qualifying a sparse stream. 600 s = 10 min, matching the dense
     *  gate's ≈10 min of 600 × 1 Hz samples, so both cadences trust the number at the same age. */
    const val minSpanSeconds: Int = 600

    /** Top of the Effort scale (0–100). */
    const val maxStrain: Double = 100.0

    /** Log-map denominator D = 7200 + 1: the Edwards daily ceiling (zone weight 5 for 24 h = 7200)
     *  maps to exactly maxStrain, so ln(7201)/ln(7201) = 1 and the curve's saturation point is
     *  independent of maxStrain (the rescale is a pure linear scale of the curve). */
    const val strainDenominator: Double = 7201.0
    val lnStrainDenominator: Double get() = ln(strainDenominator)

    /** Fallback per-sample duration (minutes) — 1 s at 1 Hz. */
    const val fallbackSampleMin: Double = 1.0 / 60.0

    const val defaultAge: Int = 30
    const val defaultRestingHR: Double = 60.0

    /** Minimum HR samples before the observed high-percentile HRmax is trusted. */
    const val hrmaxMinSamples: Int = 600

    /** Upper percentile for the observed-HRmax estimate. */
    const val hrmaxPercentile: Double = 99.5

    /** Banister coefficients. */
    const val banisterScale: Double = 0.64
    const val banisterBMen: Double = 1.92
    const val banisterBWomen: Double = 1.67

    /** Edwards zone cut-offs as (%HRR threshold, weight), highest-first. */
    val edwardsZones: List<Pair<Double, Int>> = listOf(
        90.0 to 5, 80.0 to 4, 70.0 to 3, 60.0 to 2, 50.0 to 1,
    )

    /** TRIMP accumulation method. */
    enum class Method { EDWARDS, BANISTER }

    // ---- HRmax helpers ----

    /** Tanaka (2001): HRmax = 208 − 0.7 × age (gender-independent). */
    fun tanakaHRmax(age: Double): Double = 208.0 - 0.7 * age

    /** Linear-interpolated percentile of an already-sorted sequence (numpy-style). */
    fun percentile(sortedValues: List<Double>, pct: Double): Double {
        val n = sortedValues.size
        if (n == 0) return 0.0
        if (n == 1) return sortedValues[0]
        val position = (pct / 100.0) * (n - 1).toDouble()
        val lower = position.toInt()
        val upper = minOf(lower + 1, n - 1)
        val frac = position - lower.toDouble()
        return sortedValues[lower] + frac * (sortedValues[upper] - sortedValues[lower])
    }

    /**
     * Estimate a personalized HRmax from a trailing HR series.
     * Returns (hrmax bpm, source) where source ∈ {"observed", "tanaka", "unknown"}.
     */
    fun estimateHRmax(hrHistory: List<Double>, age: Double?): Pair<Double, String> {
        val n = hrHistory.size
        val tanaka = age?.let { tanakaHRmax(it) }

        if (n >= hrmaxMinSamples) {
            val observed = percentile(hrHistory.sorted(), hrmaxPercentile)
            if (tanaka == null) return observed to "observed"
            return if (observed >= tanaka) observed to "observed" else tanaka to "tanaka"
        }
        if (tanaka != null) return tanaka to "tanaka"
        return 0.0 to "unknown"
    }

    // ---- Karvonen %HRR and Edwards zone weight ----

    /** Karvonen %HRR, clamped [0, 100]. */
    fun pctHRR(bpm: Double, restingHR: Double, hrReserve: Double): Double {
        val pct = (bpm - restingHR) / hrReserve * 100.0
        if (pct < 0) return 0.0
        if (pct > 100) return 100.0
        return pct
    }

    /**
     * Edwards 5-zone weight (0–5) from %HRR (unclamped; extremes agree with
     * the clamped path at both ends).
     */
    fun zoneWeight(bpm: Double, restingHR: Double, hrReserve: Double): Int {
        val pct = (bpm - restingHR) / hrReserve * 100.0
        for ((threshold, weight) in edwardsZones) {
            if (pct >= threshold) return weight
        }
        return 0
    }

    // ---- Logarithmic map (kept for test compatibility) ----

    fun trimpToStrain(trimp: Double, denominator: Double = strainDenominator): Double {
        if (trimp <= 0) return 0.0
        val value = maxStrain * ln(trimp + 1.0) / ln(denominator)
        return (value * 100).roundToLong() / 100.0
    }

    // ---- Public API ----

    /**
     * Cardiovascular Effort (0–100) from an HR series. Approximate.
     *
     * Returns null when there isn't yet enough data to trust the number (below [minReadings]
     * samples and below [minSpanSeconds] of HR coverage) or when maxHR ≤ restingHR (invalid HRR).
     *
     * @param hr time-ordered [HrSample] list.
     * @param maxHR HRmax (bpm). Defaults to 220 − defaultAge when null.
     * @param restingHR resting HR (bpm) for the HRR denominator (default 60).
     * @param sex "male"/"female" — selects the Banister coefficient (ignored by Edwards).
     */
    fun strain(
        hr: List<HrSample>,
        maxHR: Double? = null,
        restingHR: Double = defaultRestingHR,
        method: Method = Method.EDWARDS,
        sex: String = "male",
        denominator: Double = strainDenominator,
    ): Double? {
        // Delegates to RustScores.
        return RustScores.strain(hr, maxHR, restingHR, method, sex, denominator)
    }
}

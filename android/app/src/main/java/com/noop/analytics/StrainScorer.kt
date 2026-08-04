package com.noop.analytics

import com.noop.data.HrSample

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

    // The gates, the scale top and the log-map denominator are READ from whoop-rs (physio-algo
    // strain); one displayed Effort score reads them, so they have exactly one owner.

    /** Minimum HR readings before computing strain on a DENSE stream (≈10 min at 1 Hz). */
    val minReadings: Int = RustScores.strainCfg.minReadings.toInt()

    /** Wall-clock coverage (seconds) qualifying a sparse stream, matching the dense gate’s span. */
    val minSpanSeconds: Int = RustScores.strainCfg.minSpanSeconds.toInt()

    /** Top of the Effort scale (0–100). */
    val maxStrain: Double = RustScores.strainCfg.maxStrain

    /** Log-map denominator: the Edwards daily ceiling maps to exactly [maxStrain]. */
    val strainDenominator: Double = RustScores.strainCfg.denominator

    /**
     * WHOOP's published Day Strain ceiling. The one number in the conversion below that whoop-rs does
     * NOT own: it is another vendor's axis, not a property of our Effort score, so there is nothing on
     * the Rust side to read it from. Recorded in `docs/ALGORITHMS.md` as Kotlin-held.
     */
    const val WHOOP_DAY_STRAIN_MAX: Double = 21.0

    /**
     * WHOOP Day Strain (0–[WHOOP_DAY_STRAIN_MAX]) → our Effort axis (0–[maxStrain]). The import
     * boundary multiplies by this so an imported history sits on the same axis as a live-computed
     * Effort. [maxStrain] comes from whoop-rs, so the ceiling has one owner and cannot drift here.
     */
    val whoopDayStrainToEffort: Double = maxStrain / WHOOP_DAY_STRAIN_MAX

    /**
     * The same conversion the other way, for the DISPLAY toggle that shows a stored Effort on WHOOP's
     * axis. Stated as a ratio of the same two constants rather than `1 / whoopDayStrainToEffort`.
     *
     * Not for the CSV export boundary: `x * effortToWhoopDayStrain` and `x / whoopDayStrainToEffort`
     * differ in the last bit on about 29% of values, and only the division is the exact inverse of what
     * the importer does, so the exporter divides and a round trip returns the value it started with.
     */
    val effortToWhoopDayStrain: Double = WHOOP_DAY_STRAIN_MAX / maxStrain

    const val defaultRestingHR: Double = 60.0

    /** TRIMP accumulation method. */
    enum class Method { EDWARDS, BANISTER }

    // ---- HRmax helpers ----

    /** Tanaka (2001): HRmax = 208 − 0.7 × age (gender-independent). */
    fun tanakaHRmax(age: Double): Double = 208.0 - 0.7 * age

    // ---- Public API ----

    /**
     * Cardiovascular Effort (0–100) from an HR series. Approximate.
     *
     * Returns null when there isn't yet enough data to trust the number (below [minReadings]
     * samples and below [minSpanSeconds] of HR coverage) or when maxHR ≤ restingHR (invalid HRR).
     *
     * @param hr time-ordered [HrSample] list.
     * @param maxHR HRmax (bpm). Defaults to the whoop-rs default-age HRmax when null.
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

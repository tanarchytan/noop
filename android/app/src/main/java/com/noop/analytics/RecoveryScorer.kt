package com.noop.analytics

import com.noop.data.HrSample
import kotlin.math.max

/*
 * RecoveryScorer.kt — resting HR during sleep + a transparent 0-100 recovery score
 * (NOOP "Charge").
 *
 * recovery() is a z-score + logistic composite, APPROXIMATE and not WHOOP-identical
 * (WHOOP's model is proprietary). Weights: HRV vs baseline W_HRV=0.55 (dominant,
 * higher is better), resting HR W_RHR=0.20 (lower better), respiration W_RESP=0.05
 * (lower better), sleep performance W_SLEEP=0.15 (higher better), skin-temp deviation
 * W_SKIN_TEMP=0.05 (SYMMETRIC: either direction away from baseline lowers Charge).
 * Two optional terms — resting-HR decline slope W_RECOVERY_INDEX=0.05, previous-day
 * Effort vs its EWMA baseline W_ACTIVITY_BALANCE=0.05 — fold in only when supplied;
 * a null term drops out and the remaining weights renormalize, so the score is
 * unchanged without it.
 *
 * Each metric is a robust z-score vs its personal baseline (mean + EWMA-abs-dev
 * spread); the composite squashes through a logistic anchored so Z=0 -> ~58%
 * (WHOOP's published population average). Returns null (cold-start) until the HRV
 * baseline has MIN_NIGHTS_SEED valid nights; [populationMean] (58.0) is a fallback
 * callers should flag.
 *
 * `start` / `end` are wall-clock unix SECONDS (Long).
 */

/** Resting-HR estimate + transparent recovery score. */
object RecoveryScorer {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    const val wHRV: Double = 0.55
    const val wRHR: Double = 0.20
    const val wResp: Double = 0.05
    const val wSleep: Double = 0.15

    /** Skin-temperature deviation weight (symmetric illness/overreach penalty). */
    const val wSkinTemp: Double = 0.05

    /**
     * Skin-temp deviation scale (°C per z-unit). The term is −|skinTempDevC| / scale,
     * so a 1.0 °C absolute deviation from the personal baseline costs ≈ 1 z-unit of
     * Charge. skinTempDevC is the raw ±°C delta (DailyMetric.skinTempDevC), not a z.
     *
     * Must stay 1.0: halving it doubles the effective penalty weight of this term.
     */
    const val skinTempDevScale: Double = 1.0

    /**
     * Recovery-Index weight (overnight resting-HR DECLINE slope, Oura's "Recovery Index"
     * concept). Small and additive like [wSkinTemp]: folds in only when a slope is supplied.
     */
    const val wRecoveryIndex: Double = 0.05

    /**
     * Recovery-Index slope scale (bpm/hour): a slope this many bpm/hour steeper than flat (0)
     * costs/earns ≈ 1 z-unit before weighting. Resting HR falling through the night is the
     * expected good pattern; flat or rising (illness, alcohol, a late stimulant, restlessness)
     * is not — the SIGN carries the meaning (negative = declining = good), unlike skin-temp's
     * symmetric |deviation| penalty.
     */
    const val recoveryIndexScaleBpmPerHr: Double = 2.0

    /**
     * Activity-Balance / previous-day-Effort weight (collapses Oura's "Previous Day Activity"
     * and "Activity Balance" readiness concepts into one term). Small and additive like
     * [wSkinTemp]: folds in only when BOTH a previous-day Effort value and its personal EWMA
     * baseline ([Baselines.strainCfg]) are supplied.
     */
    const val wActivityBalance: Double = 0.05

    /** Logistic spread: ±2 z-units ≈ full Red–Green band (15%–95%). */
    const val logisticK: Double = 1.6

    /** Logistic offset so Z=0 → 58%. */
    const val logisticZ0: Double = -0.20

    /** Recovery band thresholds (WHOOP color scheme). */
    const val bandRedMax: Double = 34.0
    const val bandYellowMax: Double = 67.0

    /** Sleep-performance center ("good night" at ~85% efficiency). */
    const val sleepPerfCenter: Double = 0.85

    /** Sleep-performance scale (±2 z spans the normal range). */
    const val sleepPerfScale: Double = 0.12

    /** Rolling-mean HR window (seconds) for the resting-HR estimate (read by [recoveryIndexSlope]). */
    const val restingHRWindowS: Int = 5 * 60

    // ─────────────────────────────────────────────────────────────────────────
    // Cold-start calibration progress
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The UNCAPPED count of nights carrying a usable nightly HRV, the signal that seeds every
     * baseline. Uses the SAME validity predicate as [Baselines.update] (within the metric config
     * bounds, not just non-null). The cold-start gate caps this to the seed window;
     * [CalibrationMilestones] counts it all the way to 30.
     */
    fun bankedNights(nightlyHrv: List<Double?>, cfg: MetricCfg = Baselines.hrvCfg): Int =
        nightlyHrv.count { it != null && it in cfg.minVal..cfg.maxVal }

    // ─────────────────────────────────────────────────────────────────────────
    // Recovery Index (overnight HR-decline slope)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Minimum 5-minute bins (the SAME [restingHRWindowS] binning) required before a slope is
     * trusted; below this, too little of the night has elapsed to fit a trend and a 1-2-point
     * regression is noise, not a pattern. 6 bins = 30 minutes, a deliberately low floor so a
     * short/partial night still gets a number rather than a routine null.
     */
    const val recoveryIndexMinBins: Int = 6

    /**
     * Overnight resting-HR DECLINE slope (bpm/hour) across the in-bed window, the "Recovery
     * Index" component of Oura's Readiness.
     *
     * Least-squares slope of the SAME non-overlapping 5-minute HR bin means the resting-HR
     * floor uses ([restingHRWindowS]), against each bin's midpoint time (hours from `start`).
     * NEGATIVE = declining (HR falling through the night, the expected good pattern); POSITIVE
     * = rising (restlessness, illness, alcohol, a late stimulant). Returns null when fewer than
     * [recoveryIndexMinBins] bins have data, or there are no samples at all — never fabricates
     * a slope from a sliver of the night.
     *
     * @param start / @param end window bounds, unix SECONDS (Long).
     */
    fun recoveryIndexSlope(hr: List<HrSample>, start: Long, end: Long): Double? {
        val seg = hr.filter { it.ts in start..end }
        if (seg.isEmpty()) return null

        // Same non-overlapping 5-minute binning as restingHR: both read the identical
        // underlying series, one as a floor, one as a trend across it.
        val points = ArrayList<Pair<Double, Double>>() // (tHours, meanBpm)
        var t = start
        while (t < end) {
            val binEnd = t + restingHRWindowS
            val win = seg.filter { it.ts >= t && it.ts < binEnd }
            if (win.isNotEmpty()) {
                val mean = win.sumOf { it.bpm }.toDouble() / win.size.toDouble()
                val midpointS = (t - start).toDouble() + restingHRWindowS / 2.0
                points.add((midpointS / 3600.0) to mean)
            }
            t += restingHRWindowS
        }
        if (points.size < recoveryIndexMinBins) return null

        // Least-squares slope: Σ((t−t̄)(y−ȳ)) / Σ((t−t̄)²), bpm per hour.
        val n = points.size.toDouble()
        val tBar = points.sumOf { it.first } / n
        val yBar = points.sumOf { it.second } / n
        var num = 0.0
        var den = 0.0
        for ((tHours, meanBpm) in points) {
            val dt = tHours - tBar
            num += dt * (meanBpm - yBar)
            den += dt * dt
        }
        // Degenerate (all bins at the same instant): no time spread to fit against.
        if (den <= 1e-9) return 0.0
        return num / den
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recovery band
    // ─────────────────────────────────────────────────────────────────────────

    /** WHOOP-style color band for a recovery score [0, 100]. */
    fun band(score: Double): String {
        if (score < bandRedMax) return "red"
        if (score < bandYellowMax) return "yellow"
        return "green"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recovery score
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A baseline driver: mean + spread (internal abs-dev units, as in [BaselineState]).
     */
    data class DriverBaseline(val mean: Double, val spread: Double) {
        constructor(state: BaselineState) : this(mean = state.baseline, spread = state.spread)
    }

    /** Robust z-score using EWMA spread: (value − mean) / (1.253 × spread). */
    internal fun zScore(value: Double, mean: Double, spread: Double): Double {
        val sigma = max(1.253 * spread, 1e-9)
        return (value - mean) / sigma
    }

    // The recovery/Charge composite (z-score + logistic) lives in whoop-rs physio-algo, reached
    // via [RustScores.recovery]. [DriverBaseline], [zScore], [band], [recoveryIndexSlope] and
    // [bankedNights] stay here because frontend consumers (RecoveryDrivers / RecoveryScorerTrace
    // / CalibrationMilestones) read them directly.
}

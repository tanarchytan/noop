package com.noop.analytics

import com.noop.data.HrSample

/*
 * RecoveryScorer.kt — resting HR during sleep + a transparent 0-100 recovery score
 * (NOOP "Charge").
 *
 * recovery() is a z-score + logistic composite, APPROXIMATE and not WHOOP-identical
 * (WHOOP's model is proprietary). Drivers: HRV vs baseline (dominant, higher better),
 * resting HR (lower better), respiration (lower better), sleep performance (higher
 * better) and skin-temp deviation (SYMMETRIC: either direction away from baseline
 * lowers Charge). Two optional terms — resting-HR decline slope and previous-day
 * Effort vs its EWMA baseline — fold in only when supplied; a null term drops out and
 * the remaining weights renormalize, so the score is unchanged without it. Every
 * weight is read from whoop-rs, never declared here.
 *
 * Each metric is a robust z-score vs its personal baseline (mean + EWMA-abs-dev
 * spread); the composite squashes through a logistic anchored so Z=0 -> ~58%
 * (WHOOP's published population average). Returns null (cold-start) until the HRV
 * baseline has MIN_NIGHTS_SEED valid nights; the population mean (58.0) is a fallback
 * callers should flag.
 *
 * `start` / `end` are wall-clock unix SECONDS (Long).
 */

/** Resting-HR estimate + transparent recovery score. */
object RecoveryScorer {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    // Every value below is READ from whoop-rs (physio-algo recovery), never declared here: the weights
    // must sum coherently and one displayed score reads them, so they have exactly one owner.

    val wHRV: Double = RustScores.recoveryCfg.wHrv
    val wRHR: Double = RustScores.recoveryCfg.wRhr
    val wResp: Double = RustScores.recoveryCfg.wResp
    val wSleep: Double = RustScores.recoveryCfg.wSleep

    /** Skin-temperature deviation weight (symmetric illness/overreach penalty). */
    val wSkinTemp: Double = RustScores.recoveryCfg.wSkinTemp

    /**
     * Skin-temp deviation scale (°C per z-unit). The term is −|skinTempDevC| / scale,
     * so a 1.0 °C absolute deviation from the personal baseline costs ≈ 1 z-unit of
     * Charge. skinTempDevC is the raw ±°C delta (DailyMetric.skinTempDevC), not a z.
     */
    val skinTempDevScale: Double = RustScores.recoveryCfg.skinTempDevScale

    /**
     * Recovery-Index weight (overnight resting-HR DECLINE slope). Small and additive like
     * [wSkinTemp]: folds in only when a slope is supplied.
     */
    val wRecoveryIndex: Double = RustScores.recoveryCfg.wRecoveryIndex

    /**
     * Recovery-Index slope scale (bpm/hour): a slope this many bpm/hour steeper than flat (0)
     * costs/earns ≈ 1 z-unit before weighting. Resting HR falling through the night is the
     * expected good pattern; flat or rising is not — the SIGN carries the meaning (negative =
     * declining = good), unlike skin-temp's symmetric |deviation| penalty.
     */
    val recoveryIndexScaleBpmPerHr: Double = RustScores.recoveryCfg.recoveryIndexScaleBpmPerHr

    /**
     * Activity-Balance / previous-day-Effort weight. Small and additive like [wSkinTemp]: folds in
     * only when BOTH a previous-day Effort value and its personal EWMA baseline
     * ([Baselines.strainCfg]) are supplied.
     */
    val wActivityBalance: Double = RustScores.recoveryCfg.wActivityBalance

    /** Logistic spread: ±2 z-units ≈ full Red–Green band (15%–95%). */
    val logisticK: Double = RustScores.recoveryCfg.logisticK

    /** Logistic offset so Z=0 → 58%. */
    val logisticZ0: Double = RustScores.recoveryCfg.logisticZ0

    /** Recovery band thresholds (WHOOP color scheme). */
    val bandRedMax: Double = RustScores.recoveryCfg.bandRedMax
    val bandYellowMax: Double = RustScores.recoveryCfg.bandYellowMax

    /** Sleep-performance center ("good night" at ~85% efficiency). */
    val sleepPerfCenter: Double = RustScores.recoveryCfg.sleepPerfCenter

    /** Sleep-performance scale (±2 z spans the normal range). */
    val sleepPerfScale: Double = RustScores.recoveryCfg.sleepPerfScale

    /** Rolling-mean HR window (seconds) for the resting-HR estimate (read by [recoveryIndexSlope]). */
    val restingHRWindowS: Int = RustScores.recoveryCfg.restingHrWindowS.toInt()

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
    val recoveryIndexMinBins: Int = RustScores.recoveryCfg.recoveryIndexMinBins.toInt()

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

    /** Robust z-score against a baseline mean + EWMA spread. Computed in whoop-rs. */
    internal fun zScore(value: Double, mean: Double, spread: Double): Double =
        RustScores.zScore(value, mean, spread)

    // The recovery/Charge composite (z-score + logistic) lives in whoop-rs physio-algo, reached
    // via [RustScores.recovery]. [DriverBaseline], [zScore], [band], [recoveryIndexSlope] and
    // [bankedNights] stay here because frontend consumers (RecoveryDrivers / RecoveryScorerTrace
    // / CalibrationMilestones) read them directly.
}

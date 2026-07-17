package com.noop.analytics

import com.noop.data.HrSample
import kotlin.math.max

/*
 * RecoveryScorer.kt — resting HR during sleep + a transparent 0–100 recovery score
 * (NOOP "Charge").
 *
 * Faithful Kotlin port of StrandAnalytics/RecoveryScorer.swift (verified on macOS),
 * itself ported from server/ingest/app/analysis/recovery.py.
 *
 * recovery() is a z-score + logistic composite. It is APPROXIMATE — not
 * WHOOP-identical (WHOOP's model is proprietary). It is a transparent,
 * HRV-dominant, baseline-normalized proxy.
 *
 * Weighting (documented, grounded, explainable):
 *   higher HRV vs baseline        → higher recovery  (W_HRV   = 0.55, dominant)
 *   lower resting HR vs baseline   → higher recovery  (W_RHR   = 0.20)
 *   lower resp vs baseline         → higher recovery  (W_RESP  = 0.05)
 *   higher sleep performance       → higher recovery  (W_SLEEP = 0.15)
 *   skin temp NEAR baseline        → higher recovery  (W_SKIN_TEMP = 0.05)
 *
 * Skin temp is a SYMMETRIC penalty: further from the personal baseline in EITHER
 * direction (illness / overreach) lowers Charge. It enters as −|dev| / scale, like
 * the "lower is better" terms but on the absolute deviation. When skinTempDev is null
 * the term drops and the remaining weights renormalize, so the score is IDENTICAL to
 * the pre-skin-temp model (the no-skin-temp path is byte-for-byte unchanged).
 *
 * Two OPTIONAL Oura-Readiness-style terms (dormant until a caller supplies them):
 *   overnight resting-HR DECLINE slope ("Recovery Index")      → W_RECOVERY_INDEX    = 0.05
 *   previous-day Effort vs personal baseline ("Activity Balance" /
 *   "Previous Day Activity", collapsed into one term)          → W_ACTIVITY_BALANCE  = 0.05
 * Both are ADDITIVE and NON-BREAKING: each is null unless the caller supplies it, in which
 * case it folds in with its small weight above; when null the term drops and the weights
 * renormalize exactly like the skin-temp term, so the default score for every existing caller
 * is BYTE-IDENTICAL to before either term existed. Recovery Index needs no personal baseline
 * (a fixed, documented bpm/hour scale, same style as sleepPerf/skin-temp); Activity Balance
 * needs BOTH the previous-day Effort value AND its EWMA baseline ([Baselines.strainCfg]) —
 * supplying only one drops the term.
 *
 * Each metric is standardized to a robust z-score against the personal baseline
 * (mean + EWMA-abs-dev spread). Missing terms are dropped and the weights
 * renormalized. The composite z is squashed through a logistic anchored so that
 * Z = 0 → ~58% (WHOOP's published population-average recovery).
 *
 * Cold-start: if the HRV baseline (dominant driver) is not yet usable
 * (< MIN_NIGHTS_SEED valid nights), recovery() returns null. Callers may use
 * [populationMean] (58.0) as a fallback but should flag it.
 *
 * `start` / `end` are wall-clock unix SECONDS (Long), matching the com.noop.data
 * layer and HrSample.ts (the Swift source uses Int seconds).
 */

/** Resting-HR estimate + transparent recovery score. Mirrors Swift `RecoveryScorer`. */
object RecoveryScorer {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants (recovery.py)
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
     * Kept at 1.0 to match the Swift reference (RecoveryScorer.skinTempScaleC = 1.0).
     * A prior 0.5 here applied a 2× penalty Charge never intended — every user's
     * Charge diverged from macOS/iOS by the skin-temp term. (Cross-platform parity.)
     */
    const val skinTempDevScale: Double = 1.0

    /**
     * Recovery-Index weight (overnight resting-HR DECLINE slope — Oura's "Recovery Index"
     * contributor). Small and additive like [wSkinTemp]: folds in only when a slope is
     * supplied. Mirrors Swift `RecoveryScorer.wRecoveryIndex`.
     */
    const val wRecoveryIndex: Double = 0.05

    /**
     * Recovery-Index slope scale (bpm/hour): a slope this many bpm/hour steeper than flat (0)
     * costs/earns ≈ 1 z-unit before weighting. Resting HR falling through the night is the
     * physiologically expected, good pattern; flat or rising (illness, alcohol, a late
     * stimulant, restlessness) is not. The SIGN carries the meaning (negative = declining =
     * good), unlike skin-temp's symmetric |deviation| penalty. Mirrors Swift
     * `RecoveryScorer.recoveryIndexScaleBpmPerHr`.
     */
    const val recoveryIndexScaleBpmPerHr: Double = 2.0

    /**
     * Activity-Balance / previous-day-Effort weight (collapses Oura's "Previous Day Activity"
     * and "Activity Balance" readiness contributors into one term). Small and additive like
     * [wSkinTemp]: folds in only when BOTH a previous-day Effort value and its personal EWMA
     * baseline ([Baselines.strainCfg]) are supplied. Mirrors Swift
     * `RecoveryScorer.wActivityBalance`.
     */
    const val wActivityBalance: Double = 0.05

    /** Logistic spread: ±2 z-units ≈ full Red–Green band (15%–95%). */
    const val logisticK: Double = 1.6

    /** Logistic offset so Z=0 → 58%. */
    const val logisticZ0: Double = -0.20

    /** WHOOP-published population-average recovery (%). Cold-start fallback. */
    const val populationMean: Double = 58.0

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
     * The UNCAPPED count of nights carrying a usable nightly HRV — the signal that seeds every
     * baseline. Uses the SAME validity predicate as [Baselines.update] (value within the metric config
     * bounds), not just non-null, so an implausible out-of-range night is excluded and this can never
     * over-state nValid. The recovery cold-start gate uses it capped to the seed window; the
     * calibration-milestone countdown cards ([CalibrationMilestones]) count it all the way to 30. Pure +
     * unit-tested. Mirrors Swift `RecoveryScorer.bankedNights`.
     */
    fun bankedNights(nightlyHrv: List<Double?>, cfg: MetricCfg = Baselines.hrvCfg): Int =
        nightlyHrv.count { it != null && it in cfg.minVal..cfg.maxVal }

    // ─────────────────────────────────────────────────────────────────────────
    // Recovery Index (overnight HR-decline slope)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Minimum 5-minute bins ([restingHR]'s SAME binning) required before a slope is trusted —
     * below this, too little of the night has elapsed to fit a trend, and a 1-2-point
     * regression is noise, not a night-long pattern. 6 bins = 30 minutes of binned coverage,
     * a deliberately low floor so a short/partial night still gets a number rather than a
     * routine null. Mirrors Swift `RecoveryScorer.recoveryIndexMinBins`.
     */
    const val recoveryIndexMinBins: Int = 6

    /**
     * Overnight resting-HR DECLINE slope (bpm/hour) across the in-bed window — the "Recovery
     * Index" component of Oura's Readiness that Charge lacked (it previously only read the
     * overnight FLOOR via [restingHR] above, never the trend that reaches it).
     *
     * Computed as the least-squares slope of the SAME non-overlapping 5-minute HR bin means
     * [restingHR] uses ([restingHRWindowS]) against each bin's midpoint time (hours from
     * `start`). NEGATIVE = declining (HR falling through the night — the physiologically
     * expected, good pattern); POSITIVE = rising (restlessness, illness, alcohol, a late
     * stimulant). Returns null when fewer than [recoveryIndexMinBins] bins have data (too
     * little of the window to fit a trend) or there are no samples at all — it never
     * fabricates a slope from a sliver of the night. Mirrors Swift `recoveryIndexSlope`.
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
     * Mirrors Swift `RecoveryScorer.DriverBaseline`.
     */
    data class DriverBaseline(val mean: Double, val spread: Double) {
        constructor(state: BaselineState) : this(mean = state.baseline, spread = state.spread)
    }

    /** Robust z-score using EWMA spread: (value − mean) / (1.253 × spread). */
    internal fun zScore(value: Double, mean: Double, spread: Double): Double {
        val sigma = max(1.253 * spread, 1e-9)
        return (value - mean) / sigma
    }

    // The recovery/Charge composite (z-score + logistic) now lives in whoop-rs physio-algo,
    // reached via [RustScores.recovery] (proven bit-for-bit == the deleted Kotlin twin by
    // RustRecoveryParityTest over the recovery_cases fixtures). The [DriverBaseline] data class,
    // [zScore], [band], [restingHR], [recoveryIndexSlope] and [bankedNights] stay here — they are
    // read by frontend consumers (RecoveryDrivers / RecoveryScorerTrace) and the Resting-HR route.
}

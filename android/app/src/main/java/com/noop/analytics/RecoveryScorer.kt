package com.noop.analytics

import com.noop.data.HrSample
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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

    /** Rolling-mean HR window (seconds) for the resting-HR estimate. */
    const val restingHRWindowS: Int = 5 * 60

    /**
     * Minimum HR samples a 5-min bin must hold before its mean is eligible to WIN the resting
     * floor (#686). A thinly-populated bin — at the limit a single lone beat — lets one artifact
     * (a dropout, a decode glitch) become the bin "mean" and win the night's minimum, dragging
     * resting HR implausibly low. Requiring a handful of samples means the floor is a genuine
     * sustained dip, not a one-sample fluke. Worn nights stream ~1 Hz HR so a real 5-min bin holds
     * hundreds of samples and clears this trivially; only sparse/edge bins fall below it. Does NOT
     * change the floor DEFINITION — still the min of 5-min bin means — only stops an under-sampled
     * artifact bin from being that min. Mirrors Swift `RecoveryScorer.restingHRMinBinSamples`.
     */
    const val restingHRMinBinSamples: Int = 5

    /**
     * Physiological resting-HR floor (bpm) below which a bin mean is rejected as a dropout artifact
     * (#686), never the resting floor. An adult's true sleeping resting HR essentially never sits
     * below this; a 5-min mean that does is a run of dropout/decode-zero beats, not a real cardiac
     * dip. 25 bpm clears even deeply-bradycardic trained athletes (resting HRs in the low 30s) with
     * margin while rejecting the implausible artifact range. Mirrors Swift
     * `RecoveryScorer.restingHRMinPlausibleBpm`.
     */
    const val restingHRMinPlausibleBpm: Double = 25.0

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
    // Resting HR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lowest sustained HR during the in-bed window (bpm, rounded), or null.
     *
     * "Sustained" = the minimum of 5-minute non-overlapping bin means of the HR
     * samples whose ts ∈ [start, end]. Rejects single-beat dips while capturing
     * the night's true floor. Returns null when there are no HR samples in window.
     *
     * Artifact hardening (#686): a bin may only WIN the floor when it is BOTH well-populated
     * (≥ [restingHRMinBinSamples], so one lone artifact beat can't be a bin "mean") AND
     * physiologically plausible (mean ≥ [restingHRMinPlausibleBpm], rejecting dropout-driven
     * sub-physiological dips). The floor DEFINITION is unchanged — still the minimum of the 5-min
     * bin means — only artifact bins are barred from being that minimum. If no bin qualifies (a
     * wholly sparse/degenerate window), fall back to the lowest of ALL bin means, else the
     * all-sample mean, preserving the never-null-on-data behaviour. Mirrors Swift `restingHR`.
     *
     * @param start / @param end window bounds, unix SECONDS (Long).
     */
    fun restingHR(hr: List<HrSample>, start: Long, end: Long): Int? {
        val seg = hr.filter { it.ts in start..end }
        if (seg.isEmpty()) return null

        val means = ArrayList<Double>()      // every bin mean (legacy floor, the fallback)
        val qualified = ArrayList<Double>()  // bins eligible to WIN the floor (#686)
        var t = start
        while (t < end) {
            val binEnd = t + restingHRWindowS
            val win = seg.filter { it.ts >= t && it.ts < binEnd }
            if (win.isNotEmpty()) {
                val mean = win.sumOf { it.bpm }.toDouble() / win.size.toDouble()
                means.add(mean)
                // A bin wins the floor only if it is well-populated AND physiologically plausible —
                // a thin (single-artifact) or sub-physiological (dropout) bin can't be the minimum.
                if (win.size >= restingHRMinBinSamples && mean >= restingHRMinPlausibleBpm) {
                    qualified.add(mean)
                }
            }
            t += restingHRWindowS
        }
        val floor: Double = qualified.minOrNull()
            ?: means.minOrNull()
            ?: (seg.sumOf { it.bpm }.toDouble() / seg.size.toDouble())
        return floor.roundToInt()
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

    /**
     * Z-score + logistic recovery score in [0, 100]. APPROXIMATE.
     *
     * Returns null when the HRV baseline (dominant driver) is not yet usable, or
     * no valid driver is available at all.
     *
     * @param hrv tonight's HRV (RMSSD, ms).
     * @param rhr tonight's resting HR (bpm).
     * @param resp tonight's respiration (raw or calibrated — z is scale-invariant);
     *   null drops the term.
     * @param hrvBaseline HRV baseline (required for a score).
     * @param rhrBaseline resting-HR baseline; null drops the RHR term.
     * @param respBaseline respiration baseline; null drops the resp term.
     * @param sleepPerf sleep-performance proxy (Rest composite 0..1, or efficiency
     *   0..1 for legacy callers); null drops the term.
     * @param skinTempDev tonight's skin-temperature deviation from the personal
     *   baseline (raw ±°C, DailyMetric.skinTempDevC); applied as a SYMMETRIC penalty
     *   −|dev| / skinTempDevScale. null drops the term and renormalizes (score then
     *   identical to the pre-skin-temp model).
     * @param hrvBaselineUsable whether the HRV baseline has enough nights
     *   (BaselineState.usable). When false, returns null (cold-start).
     */
    fun recovery(
        hrv: Double,
        rhr: Double,
        resp: Double?,
        hrvBaseline: DriverBaseline?,
        rhrBaseline: DriverBaseline?,
        respBaseline: DriverBaseline?,
        sleepPerf: Double?,
        skinTempDev: Double? = null,
        hrvBaselineUsable: Boolean = true,
    ): Double? {
        // Cold-start gate: HRV is the dominant driver; if its baseline isn't
        // usable, refuse to score (more honest than a fabricated value).
        if (!hrvBaselineUsable) return null

        val terms = ArrayList<Pair<Double, Double>>() // (z, weight)

        // HRV term: higher is better.
        hrvBaseline?.let { b ->
            terms.add(zScore(hrv, b.mean, b.spread) to wHRV)
        }
        // RHR term: lower is better → (μ − x) / σ.
        rhrBaseline?.let { b ->
            terms.add(zScore(b.mean, rhr, b.spread) to wRHR)
        }
        // Resp term: lower is better, optional.
        if (resp != null && respBaseline != null) {
            terms.add(zScore(respBaseline.mean, resp, respBaseline.spread) to wResp)
        }
        // Sleep-performance term: no baseline needed; centered at SLEEP_PERF_CENTER.
        if (sleepPerf != null) {
            terms.add(((sleepPerf - sleepPerfCenter) / sleepPerfScale) to wSleep)
        }
        // Skin-temp term: SYMMETRIC penalty, no baseline arg (skinTempDev is already a
        // deviation). Further from baseline in either direction → more negative z.
        if (skinTempDev != null) {
            terms.add((-abs(skinTempDev) / skinTempDevScale) to wSkinTemp)
        }

        if (terms.isEmpty()) return null
        val totalWeight = terms.sumOf { it.second }
        if (totalWeight <= 0.0) return null

        val z = terms.sumOf { it.first * it.second } / totalWeight
        val score = 100.0 / (1.0 + exp(-logisticK * (z - logisticZ0)))
        return max(0.0, min(100.0, score))
    }

    /**
     * Convenience overload taking [BaselineState] directly. Enforces the cold-start
     * gate using `hrvBaseline.usable`. Mirrors the Swift `recovery(...)` overload.
     */
    fun recovery(
        hrv: Double,
        rhr: Double,
        resp: Double?,
        hrvBaseline: BaselineState,
        rhrBaseline: BaselineState?,
        respBaseline: BaselineState?,
        sleepPerf: Double?,
        skinTempDev: Double? = null,
    ): Double? = recovery(
        hrv = hrv,
        rhr = rhr,
        resp = resp,
        hrvBaseline = DriverBaseline(hrvBaseline),
        rhrBaseline = rhrBaseline?.let { DriverBaseline(it) },
        respBaseline = respBaseline?.let { DriverBaseline(it) },
        sleepPerf = sleepPerf,
        skinTempDev = skinTempDev,
        hrvBaselineUsable = hrvBaseline.usable,
    )
}

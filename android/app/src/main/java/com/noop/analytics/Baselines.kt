package com.noop.analytics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Baselines.kt — personal rolling baselines per nightly metric.
 *
 * Production model is a Winsorized EWMA: robust, recency-weighted center, EWMA-of-absolute-
 * deviation spread, cold-start gating, hard outlier rejection, and Winsor clamping
 * ([update] / [foldHistory]). Produces a [BaselineState] that RecoveryScorer consumes.
 *
 * Value types ([MetricCfg], [BaselineStatus], [BaselineState], [Deviation]) live in
 * AnalyticsModels.kt. `ts` values elsewhere are wall-clock unix seconds; baselines work on
 * per-night scalar values and carry no timestamps.
 *
 * Outputs are approximate, not medical advice.
 */

/** Personal rolling baselines, one state per metric. */
object Baselines {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    /** Winsorization clamp: fold only within ±`winsorK` × spread. */
    const val winsorK: Double = 3.0

    /** Hard-reject gate: drop the night if beyond `hardOutlierK` × spread. */
    const val hardOutlierK: Double = 5.0

    /** Minimum valid nights before "provisionally" trusted. */
    const val minNightsSeed: Int = 4

    /** Minimum valid nights before fully trusted. */
    const val minNightsTrust: Int = 14

    /** Missing-night count after which a baseline is marked stale. */
    const val staleDays: Int = 14

    // ─────────────────────────────────────────────────────────────────────────
    // Early-life anti-anchoring
    // ─────────────────────────────────────────────────────────────────────────
    //
    // A cold-start seed with an artificially high first night can lock the baseline high for
    // weeks: the tight floor spread makes the hard-outlier gate reject genuine lower nights and
    // makes the z-score hypersensitive. Early life adapts the center fast and suspends that gate
    // until spread has widened (settles back to normal smoothing after earlyAdaptNights).

    /** Valid-night count below which the baseline is "young": fast center adaptation + suspended
     *  hard-outlier gate. Chosen so convergence happens in days, not weeks. */
    const val earlyAdaptNights: Int = 8

    /** Center half-life (nights) used while the baseline is young — much faster than halfLifeB. */
    const val earlyHalfLifeB: Double = 3.0

    /** Multiplier on spread for the Winsor clamp while young, so an honest lower night isn't clamped
     *  flat against a floor-tight band before the spread has had a chance to widen. */
    const val earlySpreadInflate: Double = 2.5

    /** SharedPreferences key for the manual HRV-baseline recalibration epoch (epoch SECONDS).
     *  0 / absent = no recalibration. Written by the Settings "Recalibrate HRV baseline" button. */
    const val hrvBaselineEpochKey: String = "noop.hrvBaselineEpoch"

    /** SharedPreferences key for the manual recovery-baseline recalibration epoch (epoch SECONDS).
     *  0 / absent = no recalibration. HRV re-anchors on [hrvBaselineEpochKey]; resting-HR,
     *  respiration, and skin-temp re-anchor on this one; [recalibrateRecoveryBaselines] writes both. */
    const val recoveryBaselineEpochKey: String = "noop.recoveryBaselineEpoch"

    /**
     * Default per-metric configurations (HRV, resting HR, respiration, skin temp, daily Effort/strain).
     * "strain" bounds match `StrainScorer.maxStrain`'s 0-100 scale with a wider floorSpread, since
     * day-to-day training load swings hard and a tight floor would make the z-score hypersensitive.
     */
    // The validity bands, spread floors and EWMA half-lives are read from whoop-rs, so the app cannot
    // carry a second copy that drifts from the one the baseline maths actually uses.
    val metricCfg: Map<String, MetricCfg> =
        listOf("hrv", "resting_hr", "resp", "skin_temp", "strain")
            .mapNotNull { name -> RustScores.baselineMetricCfg(name)?.let { name to it } }
            .toMap()

    /** Convenience accessor for the standard HRV config. */
    val hrvCfg: MetricCfg get() = metricCfg.getValue("hrv")

    /** Convenience accessor for the standard resting-HR config. */
    val restingHRCfg: MetricCfg get() = metricCfg.getValue("resting_hr")

    /** Convenience accessor for the standard respiration config. */
    val respCfg: MetricCfg get() = metricCfg.getValue("resp")

    /** Baseline config for the RecoveryScorer Activity-Balance / previous-day-Effort term. */
    val strainCfg: MetricCfg get() = metricCfg.getValue("strain")

    /** Convert a half-life in nights to an EWMA smoothing factor. */
    internal fun lambda(halfLife: Double): Double = 1.0 - 0.5.pow(1.0 / halfLife)

    internal fun computeStatus(nValid: Int, nightsSinceUpdate: Int): BaselineStatus {
        if (nightsSinceUpdate > staleDays && nValid >= minNightsSeed) return BaselineStatus.STALE
        if (nValid < minNightsSeed) return BaselineStatus.CALIBRATING
        if (nValid < minNightsTrust) return BaselineStatus.PROVISIONAL
        return BaselineStatus.TRUSTED
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Winsorized EWMA update (production model)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Incorporate one new nightly value into the baseline state: seeds on null state, skip-and-holds
     * a null or out-of-range value, marks a value beyond `hardOutlierK` × spread as seen-but-not-
     * folded, and otherwise folds it via Winsorized EWMA center + EWMA-abs-dev spread update.
     */
    fun update(state: BaselineState?, value: Double?, cfg: MetricCfg): BaselineState {
        val ffiState = state?.let { uniffi.whoop_ffi.BaselineStateInfo(
            baseline = it.baseline, spread = it.spread, nValid = it.nValid,
            nightsSinceUpdate = it.nightsSinceUpdate, status = it.status.raw,
        ) }
        val r = uniffi.whoop_ffi.baselineUpdate(ffiState, value, RustScores.metricCfgInfo(cfg))
        return BaselineState(
            baseline = r.baseline, spread = r.spread, nValid = r.nValid,
            nightsSinceUpdate = r.nightsSinceUpdate,
            status = BaselineStatus.entries.first { it.raw == r.status },
        )
    }

    /**
     * Replay an ordered sequence of nightly values (oldest first) to build state.
     * `null` entries are treated as missing nights (skip-and-hold).
     */
    fun foldHistory(values: List<Double?>, cfg: MetricCfg): BaselineState =
        RustScores.baselineFoldHistory(values, cfg)

    /**
     * Replay nightly values (oldest first), honouring a manual recalibration [baselineEpoch] (epoch
     * seconds; 0 = disabled). [dayKeys] parallels [values]; a night starting (UTC) before the epoch
     * is dropped, not skip-and-held, so the baseline re-seeds from the first night on or after it.
     */
    fun foldHistory(
        values: List<Double?>,
        dayKeys: List<String>,
        cfg: MetricCfg,
        baselineEpoch: Double,
    ): BaselineState {
        if (baselineEpoch <= 0.0) return foldHistory(values, cfg)

        var state: BaselineState? = null
        for (i in values.indices) {
            // Drop (not skip-and-hold) any night dated before the recalibration epoch.
            if (i < dayKeys.size) {
                val dayStart = runCatching {
                    java.time.LocalDate.parse(dayKeys[i])
                        .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toDouble()
                }.getOrNull()
                if (dayStart != null && dayStart < baselineEpoch) continue
            }
            state = update(state, values[i], cfg)
        }
        state?.let { return it }
        val seed = (cfg.minVal + cfg.maxVal) / 2.0
        return BaselineState(
            baseline = seed, spread = cfg.floorSpread, nValid = 0,
            nightsSinceUpdate = 0, status = BaselineStatus.CALIBRATING,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Device-era boundary
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recalibration epoch (UTC start-of-day, seconds) at the latest device-era boundary in a
     * source-tagged history, so [foldHistory]'s `baselineEpoch` never mixes brands' incompatible HRV
     * scales (Oura RMSSD ~120-155 ms vs WHOOP ~72-112 ms). [sourceDays] must carry exactly one
     * winning `(dayKey, sourceId)` per night; returns 0.0 when the whole history is one brand.
     */
    fun deviceEraEpoch(sourceDays: List<Pair<String, String>>): Double {
        if (sourceDays.isEmpty()) return 0.0
        // Total order by (day, sourceId): a same-day mixed-brand row breaks the tie by sourceId,
        // not insertion order, so the computed epoch is deterministic regardless of input order.
        val sorted = sourceDays.sortedWith(compareBy({ it.first }, { it.second }))
        val currentBrand = brandBucket(sorted.last().second)
        // No brand change anywhere → no recalibration epoch.
        if (sorted.none { brandBucket(it.second) != currentBrand }) return 0.0
        // Walk back over the contiguous current-brand suffix; its first day opens the current era.
        var eraStartDay = sorted.last().first
        for (i in sorted.indices.reversed()) {
            if (brandBucket(sorted[i].second) != currentBrand) break
            eraStartDay = sorted[i].first
        }
        return runCatching {
            java.time.LocalDate.parse(eraStartDay)
                .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toDouble()
        }.getOrDefault(0.0)
    }

    /**
     * Coarse HRV-scale brand for a source id. Every WHOOP-origin id shares one scale; each
     * wearable-export brand is its own. Unknown ids bucket to "whoop" (the strap source and its
     * Apple/Health-Connect riders), so only a positively-identified wearable export changes the era.
     */
    internal fun brandBucket(sourceId: String): String = when {
        // `startsWith` deliberately catches BOTH the export id ("oura-import") and the cloud id
        // ("oura-api"), so an Oura-cloud era and an Oura-export era read as the same brand.
        sourceId.startsWith("oura") -> "oura"
        sourceId.startsWith("fitbit") -> "fitbit"
        sourceId.startsWith("garmin") -> "garmin"
        // "apple-health" / "health-connect" fall through to "whoop" ON PURPOSE: NOOP's Apple/HC daily
        // rows ride the strap source's scale, and HC is a pass-through whose true origin is unknowable,
        // so they must NOT open a false era boundary against WHOOP nights.
        else -> "whoop"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deviation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute z / delta / ratio / in-normal-range for a value vs a baseline.
     * z uses (value − baseline) / (1.253 × spread); 1.253 converts EWMA-abs-dev
     * to an approximate Gaussian σ (E[|X−μ|] = σ·√(2/π) ≈ σ/1.253).
     */
    fun deviation(value: Double, state: BaselineState): Deviation {
        val sigma = max(1.253 * state.spread, 1e-9)
        val z = (value - state.baseline) / sigma
        val delta = value - state.baseline
        val ratio = if (state.baseline != 0.0) (value / state.baseline - 1.0) else 0.0
        return Deviation(z = z, delta = delta, ratio = ratio, inNormalRange = abs(z) <= 1.0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Manual recalibration ("Recalibrate Charge baseline")
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Recalibrates every baseline that feeds Charge: writes [nowSeconds] to both the HRV and recovery
     * epoch keys so they re-anchor together, without deleting any stored day (only the re-learn point
     * moves). Stored as a Long since SharedPreferences has no putDouble; caller supplies the editor.
     */
    fun recalibrateRecoveryBaselines(editor: android.content.SharedPreferences.Editor, nowSeconds: Long) {
        editor.putLong(hrvBaselineEpochKey, nowSeconds)
        editor.putLong(recoveryBaselineEpochKey, nowSeconds)
    }
}

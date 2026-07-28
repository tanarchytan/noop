package com.noop.analytics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Baselines.kt — personal rolling baselines per nightly metric.
 *
 * The production model is a Winsorized EWMA: robust, recency-weighted center with an
 * EWMA-of-absolute-deviation spread tracker, cold-start gating, hard outlier rejection,
 * and Winsor clamping ([update] / [foldHistory]). Produces a [BaselineState] that
 * RecoveryScorer consumes.
 *
 * Value types ([MetricCfg], [BaselineStatus], [BaselineState], [Deviation]) live in
 * AnalyticsModels.kt. All `ts` elsewhere are wall-clock unix SECONDS (Long); baselines
 * work on per-night scalar values and carry no timestamps.
 *
 * Outputs are APPROXIMATE, not medical advice.
 */

/** Personal rolling baselines, one state per metric. */
object Baselines {

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    /** Winsorization clamp: fold only within ±WINSOR_K × spread. */
    const val winsorK: Double = 3.0

    /** Hard-reject gate: drop the night if > HARD_OUTLIER_K × spread away. */
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
    // A cold-start seed with an artificially HIGH first night can lock the baseline high for
    // weeks: the still-tight floor spread makes the hard-outlier gate REJECT the user's genuine
    // LOWER nights as "seen but not folded", and makes the z-score hypersensitive, crushing Charge.
    //
    // Fix: during the baseline's EARLY life let reality pull the center down quickly, then settle
    // to the normal long-term smoothing (unchanged after earlyAdaptNights, once spread has lifted).

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

    /** SharedPreferences key for the manual RECOVERY-baseline recalibration epoch (epoch SECONDS).
     *  0 / absent = no recalibration. Charge-wide sibling of [hrvBaselineEpochKey]: HRV re-anchors
     *  on its own epoch, while resting-HR / respiration / skin-temp re-anchor on this one. The
     *  Settings "Recalibrate Charge baseline" button writes both keys (see [recalibrateRecoveryBaselines]). */
    const val recoveryBaselineEpochKey: String = "noop.recoveryBaselineEpoch"

    /**
     * Default per-metric configurations (HRV, resting HR, respiration, skin temp, daily
     * Effort/strain).
     *
     * "strain" bounds match `StrainScorer.maxStrain`'s 0-100 scale. Its floorSpread is wider
     * than the physiological metrics (5.0 vs ~1-2% of range) because day-to-day training load
     * swings hard by nature; a tight floor would make the z-score hypersensitive to routine
     * variation. Same half-lives as the other metrics.
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
     * Incorporate one new nightly value into the baseline state.
     *
     * - `state == null`: seed the first night.
     * - `value == null` or out-of-range: skip-and-hold (carry forward).
     * - hard outlier (> HARD_OUTLIER_K × spread): seen but not folded.
     * - otherwise: Winsorized EWMA center + EWMA-abs-dev spread update.
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
     * Replay an ordered sequence of nightly values (oldest first) to build state, honouring a manual
     * recalibration [baselineEpoch] (epoch SECONDS; 0 = no recalibration).
     *
     * [dayKeys] runs parallel to [values] ("yyyy-MM-dd", same order/length). Any night whose day
     * STARTS (UTC) before [baselineEpoch] is dropped entirely (not skip-and-hold), so the baseline
     * re-seeds from the first on-or-after-epoch night — this is what lets "Recalibrate HRV baseline"
     * in Settings reset a baseline that anchored too high.
     *
     * When [baselineEpoch] <= 0 this is byte-identical to plain [foldHistory]. The caller reads the
     * persisted epoch from SharedPreferences (the analytics layer is Context-free).
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
     * Recalibration epoch (seconds, UTC start-of-day) at the LATEST device-era boundary in a
     * source-tagged nightly history — feeds [foldHistory]'s `baselineEpoch` so a baseline can't mix
     * two brands' incompatible HRV scales (Oura RMSSD ~120-155 ms vs WHOOP ~72-112 ms, no overlap: a
     * straddling window would read the newer brand's early nights as suppressed against the old mean).
     *
     * [sourceDays] must be exactly ONE `(dayKey, sourceId)` per night — the day's WINNING source, not
     * one row per source — or a same-day multi-brand tie could misread which brand is current.
     *
     * Walks newest-to-oldest over the contiguous run matching the newest night's brand and returns
     * that run's first day's start; a lone off-brand day inside the run truncates it (drops more
     * history, never mixes scales). Returns 0.0 (no recalibration) when the whole history is one brand.
     *
     * Brand bucketing is coarse (every WHOOP-origin id is one brand; each wearable-export brand is
     * its own) and must be read from the ORIGINAL per-source rows before any merge re-homes them.
     */
    fun deviceEraEpoch(sourceDays: List<Pair<String, String>>): Double {
        if (sourceDays.isEmpty()) return 0.0
        // Total order by (day, sourceId) — a same-day mixed-brand row (an overlap night) must break the
        // tie IDENTICALLY to the Swift twin, so a plain by-day sort (stable in Kotlin, unstable in Swift)
        // can't diverge the computed epoch across platforms.
        val sorted = sourceDays.sortedWith(compareBy({ it.first }, { it.second }))
        val currentBrand = brandBucket(sorted.last().second)
        // No brand change anywhere → no epoch (byte-identical fold for every single-brand user).
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
     * Coarse HRV-scale brand for a source id (#459). Every WHOOP-origin id shares ONE scale; each
     * wearable-export brand is its own. Unknown ids bucket to "whoop" (the strap source and its Apple/
     * Health-Connect riders), so only a positively-identified wearable export changes the era. Mirrors
     * the Swift twin.
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
     * Recalibrate every baseline that feeds Charge: drop the anchor so the ~4-night build-up restarts
     * from [nowSeconds]. This is the single source of truth behind the Settings "Recalibrate Charge
     * baseline" button — it writes [nowSeconds] (epoch SECONDS, whole) to BOTH the HRV epoch and the
     * recovery epoch, so HRV (the dominant driver, already wired) and the resting-HR / respiration /
     * skin-temp baselines re-anchor together. It does NOT delete any stored day: only the day from
     * which the baselines re-learn moves. After this the next foldHistory re-seeds from the first
     * on-or-after-[nowSeconds] night, so Today honestly shows the calibrating/building state again.
     *
     * The analytics layer is Context-free, so the caller passes in the prefs editor. Epochs are stored
     * as whole seconds in a Long (SharedPreferences has no putDouble; the readers do getLong→toDouble),
     * matching the "epoch SECONDS" the keys document and the iOS UserDefaults values byte-for-byte.
     */
    fun recalibrateRecoveryBaselines(editor: android.content.SharedPreferences.Editor, nowSeconds: Long) {
        editor.putLong(hrvBaselineEpochKey, nowSeconds)
        editor.putLong(recoveryBaselineEpochKey, nowSeconds)
    }
}

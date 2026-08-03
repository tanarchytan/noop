package com.noop.analytics

import com.noop.protocol.DeviceFamily
import kotlin.math.abs

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

    /** Minimum valid nights before "provisionally" trusted. */
    val minNightsSeed: Int = RustScores.baselinesCfg.minNightsSeed

    /** Minimum valid nights before fully trusted. */
    val minNightsTrust: Int = RustScores.baselinesCfg.minNightsTrust

    /** Valid-night count below which the baseline is "young": fast center adaptation + suspended
     *  hard-outlier gate. Chosen so convergence happens in days, not weeks. */
    val earlyAdaptNights: Int = RustScores.baselinesCfg.earlyAdaptNights

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

    // ─────────────────────────────────────────────────────────────────────────
    // Winsorized EWMA update (production model)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Incorporate one new nightly value into the baseline state: seeds on null state, skip-and-holds
     * a null or out-of-range value, marks a value beyond the hard-outlier gate as seen-but-not-
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
     * scales (Oura RMSSD ~120-155 ms vs WHOOP ~72-112 ms, with no overlap).
     *
     * Walks newest to oldest while the brand matches the newest night's, and returns that run's first
     * day's start. A lone off-brand day inside the current era truncates it, which is the fail-safe
     * direction: it drops more history rather than mixing two scales. Returns 0.0 when the whole
     * history is one brand, leaving [foldHistory] byte-identical.
     *
     * [sourceDays] must carry exactly ONE winning `(dayKey, sourceId)` per night, since the current
     * era is read off the newest day's bucket and a day carrying two would let the lexically later
     * source pass for the current one. Detection must run before the per-day merge, which loses the
     * source. [familyByDeviceId] resolves each WHOOP strap's [DeviceFamily] from the registry, so a
     * 4.0 ↔ 5.0/MG swap opens an era (MAX86171 vs MAX86176 optics) while two straps of one family do
     * not; a source with no family (an Apple/Health-Connect rider) is era-NEUTRAL and never opens one.
     */
    fun deviceEraEpoch(
        sourceDays: List<Pair<String, String>>,
        familyByDeviceId: Map<String, DeviceFamily> = emptyMap(),
    ): Double {
        if (sourceDays.isEmpty()) return 0.0
        // Total order by (day, sourceId): a same-day mixed-bucket row breaks the tie by sourceId,
        // not insertion order, so the computed epoch is deterministic regardless of input order.
        val sorted = sourceDays.sortedWith(compareBy({ it.first }, { it.second }))
        val buckets = sorted.map { eraBucket(it.second, familyByDeviceId) }
        // The current era is the newest day that names one; a history of only neutral days has none.
        val currentBucket = buckets.lastOrNull { it != null } ?: return 0.0
        // No bucket change anywhere → no recalibration epoch.
        if (buckets.none { it != null && it != currentBucket }) return 0.0
        // Walk back over the contiguous current-bucket suffix (neutral days ride it); its first day
        // opens the current era. A lone off-bucket day truncates it, dropping MORE history rather than
        // mixing two scales.
        var eraStartDay = sorted.last().first
        for (i in sorted.indices.reversed()) {
            if (buckets[i] != null && buckets[i] != currentBucket) break
            eraStartDay = sorted[i].first
        }
        return runCatching {
            java.time.LocalDate.parse(eraStartDay)
                .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toDouble()
        }.getOrDefault(0.0)
    }

    /**
     * The era bucket for a source id, or null when the source is era-NEUTRAL and rides whichever era
     * it falls in. Each wearable export is its own brand; a WHOOP strap buckets by [DeviceFamily], so
     * 5.0 and MG (one AFE) share an era and a 4.0 does not.
     */
    internal fun eraBucket(sourceId: String, familyByDeviceId: Map<String, DeviceFamily>): String? = when {
        // `startsWith` deliberately catches BOTH the export id ("oura-import") and the cloud id
        // ("oura-api"), so an Oura-cloud era and an Oura-export era read as the same brand.
        sourceId.startsWith("oura") -> "oura"
        sourceId.startsWith("fitbit") -> "fitbit"
        sourceId.startsWith("garmin") -> "garmin"
        // A registry strap: its family is the era. The computed sibling shares its strap's row.
        else -> familyByDeviceId[sourceId.removeSuffix("-noop")]?.let { "whoop-${it.name}" }
        // Everything else is NEUTRAL: an Apple/Health-Connect rider carries the strap's scale, and a
        // source with no registry row has no family to claim, so neither may open a false boundary.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deviation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute z / delta / ratio / in-normal-range for a value vs a baseline.
     * The robust z comes from whoop-rs via [RustScores.zScore]; Kotlin holds no copy of its scale.
     */
    fun deviation(value: Double, state: BaselineState): Deviation {
        val z = RustScores.zScore(value, state.baseline, state.spread)
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

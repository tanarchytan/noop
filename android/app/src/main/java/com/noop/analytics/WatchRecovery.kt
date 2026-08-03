package com.noop.analytics

/**
 * Recovery/Charge from daily aggregates (Apple Watch / Health Connect / an Oura/Fitbit/Garmin export).
 *
 * A WHOOP strap runs RecoveryScorer off dense overnight RMSSD; a daily-aggregate source gives only a
 * daily HRV (SDNN-ish) reading plus a resting HR. We do not invent a new formula: every recovery term
 * is relative to the person's OWN baseline, so the metric scale cancels out (SDNN-vs-baseline behaves
 * like RMSSD-vs-baseline) — this builds HRV/RHR baselines via [Baselines] and feeds the SAME
 * [RecoveryScorer.recovery] the strap uses, landing on the same 0-100 scale.
 *
 * Dropped vs the strap: respiration, sleep-performance and skin-temp terms aren't supplied, so the
 * scorer renormalises to HRV + RHR (HRV stays dominant). Honesty rule: null recovery + CALIBRATING
 * when today's HRV or a usable baseline is missing, or history is under [minHrvNights] nights.
 */
object WatchRecovery {

    /** Result: the score (null while calibrating) and its confidence tier. */
    data class Result(val recovery: Double?, val confidence: ScoreConfidence)

    /**
     * Minimum nights of HRV history before scoring recovery from a daily-aggregate source. Sits
     * above the baseline's own seed gate (4) deliberately: a strap user crosses the seed faster on
     * dense data, but a sparse daily HRV deserves a longer warm-up before it's trusted.
     */
    const val minHrvNights = 7

    /**
     * Compute recovery/Charge from a daily HRV + resting HR vs the person's own baseline.
     *
     * @param todayHrv today's HRV reading (ms), or null if the source logged none.
     * @param todayRhr today's resting HR (bpm), or null to drop the RHR term.
     * @param hrvHistory ordered nightly HRV values (oldest -> newest), the baseline input.
     * @param rhrHistory ordered nightly resting-HR values (oldest -> newest).
     */
    fun compute(
        todayHrv: Double?,
        todayRhr: Int?,
        hrvHistory: List<Double>,
        rhrHistory: List<Double>,
    ): Result {
        // Build both baselines through the production model (Winsorized EWMA + cold-start gating), exactly
        // as the strap path does. HRV feeds the HRV config; resting HR feeds the RHR config.
        val hrvBase = Baselines.foldHistory(hrvHistory, Baselines.hrvCfg)
        val rhrBase = Baselines.foldHistory(rhrHistory, Baselines.restingHRCfg)

        // Confidence is the SAME helper the strap Charge uses, so the calibrating -> building -> solid arc
        // matches. It reads CALIBRATING whenever recovery would be null (no usable HRV baseline).
        val conf = ScoreConfidence.forCharge(todayHrv, hrvBase)

        // Honesty gate: no number unless we have today's HRV, a usable baseline, AND at least a week of
        // nights. Any miss -> null recovery + calibrating, never a fabricated value.
        if (todayHrv == null || !hrvBase.usable || hrvHistory.size < minHrvNights) {
            return Result(recovery = null, confidence = ScoreConfidence.CALIBRATING)
        }

        // Reuse the canonical Charge engine. Drop the resp / sleep / skin-temp terms (the daily aggregate
        // doesn't carry them here) -> RecoveryScorer renormalises to HRV + RHR. RHR is optional: a missing
        // resting HR today passes the at-baseline value (z~0, neutral term) and drops the RHR term entirely.
        val recovery = RustScores.recovery(
            hrv = todayHrv,
            rhr = todayRhr?.toDouble() ?: rhrBase.baseline,
            resp = null,
            hrvBaseline = hrvBase,
            rhrBaseline = if (todayRhr != null) rhrBase else null,
            respBaseline = null,
            sleepPerf = null,
        ) ?: return Result(recovery = null, confidence = ScoreConfidence.CALIBRATING)

        return Result(recovery = recovery, confidence = conf)
    }
}

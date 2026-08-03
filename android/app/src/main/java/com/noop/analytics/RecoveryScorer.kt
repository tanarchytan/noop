package com.noop.analytics

/*
 * RecoveryScorer.kt — the Charge ("recovery") tuning table the driver rows read.
 *
 * Every weight, scale, logistic shape and band cut below is READ from whoop-rs
 * (physio-algo recovery) via [RustScores.recoveryCfg], never declared here. The score
 * itself is computed by [RustScores.recovery]; this object only names the terms so
 * [RecoveryScorerTrace] can narrate them.
 */

/** The Charge driver weights and shape, read from whoop-rs. */
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

    // The recovery/Charge composite, its band, the overnight slope and the banked-night count all
    // live in whoop-rs physio-algo, reached via [RustScores]. Only [DriverBaseline] and the
    // weights/shape this object re-reads from `recoveryCfg` stay here, for the driver rows.
}

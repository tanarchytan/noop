package com.noop.analytics

import kotlin.math.abs

/*
 * SleepDebt.kt — the rolling sleep-debt ledger types (one night's contribution + the windowed ledger)
 * and its display thresholds. The Σ(slept − need) accumulation over the capped trailing window lives in
 * whoop-rs (sleep_debt), reached via RustScores.sleepDebtLedger; these are the shapes it returns.
 */

/**
 * One night's contribution to the ledger: its day key, minutes slept, and the signed
 * delta against need (positive = surplus, negative = deficit). Mirrors Swift
 * `SleepDebtNight`.
 */
data class SleepDebtNight(
    /** "yyyy-MM-dd" day key for the night (as carried on the DailyMetric). */
    val day: String,
    /** Total sleep for the night (minutes). */
    val sleptMin: Double,
    /** Signed delta vs need (minutes): sleptMin − needMin. Positive = surplus. */
    val deltaMin: Double,
)

/**
 * The rolling sleep-debt ledger over the capped trailing window. Mirrors Swift
 * `SleepDebtLedger`.
 */
data class SleepDebtLedger(
    /** Net running balance (minutes): Σ(slept − need). Negative = net DEBT, positive = net SURPLUS. */
    val balanceMin: Double,
    /** Per-night contributions, oldest → newest (skipped nights absent); the per-night bar/spark. */
    val nights: List<SleepDebtNight>,
    /** Personal sleep need (minutes) the ledger was computed against (for labelling). */
    val needMin: Double,
) {
    /** Number of nights that contributed (nights with usable sleep data). */
    val nightCount: Int get() = nights.size

    /** True when the net balance is a debt (under need overall). */
    val isDebt: Boolean get() = balanceMin < 0.0

    /** Magnitude of the balance in minutes, regardless of sign. */
    val magnitudeMin: Double get() = abs(balanceMin)
}

object SleepDebt {

    /**
     * Cap the ledger at the trailing two weeks — recent enough to be actionable, short
     * enough that one rough patch doesn't read as months of compounding debt.
     */
    const val DEFAULT_WINDOW_NIGHTS: Int = 14

    /**
     * "On target" deadband (minutes): a |balance| under this reads as balanced rather than
     * a debt/surplus, so a few stray minutes don't flip the headline.
     */
    const val ON_TARGET_BAND_MIN: Double = 30.0
}

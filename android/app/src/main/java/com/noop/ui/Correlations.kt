package com.noop.ui

import androidx.annotation.StringRes
import com.noop.R
import com.noop.analytics.RustScores
import uniffi.whoop_ffi.CorrelationStrength

// MARK: - Correlation engine
//
// The one day-alignment adapter the correlation surfaces (Compare, Mind, Insights) share. The r, the
// pair gate and the strength ladder are whoop-rs's; only the join and the wording live here.

/** A Pearson r over [n] aligned day pairs. */
internal data class Correlation(val r: Double, val n: Int)

internal object CorrelationEngine {

    /** Below this many overlapping days a correlation is not shown at all. */
    val MIN_PAIRS: Int get() = RustScores.correlationMinPairs

    /** Inner-join two day-keyed series on the day key → (x, y) pairs sorted by day. */
    fun alignByDay(
        a: List<Pair<String, Double>>,
        b: List<Pair<String, Double>>,
    ): List<Pair<Double, Double>> {
        val mapA = HashMap<String, Double>()
        for ((day, v) in a) mapA[day] = v
        val mapB = HashMap<String, Double>()
        for ((day, v) in b) mapB[day] = v
        val common = mapA.keys.filter { mapB.containsKey(it) }.sorted()
        return common.map { mapA[it]!! to mapB[it]!! }
    }

    /** Pearson r over the pairs, computed in whoop-rs. Null under [MIN_PAIRS] pairs or when
     *  either variable is flat. */
    fun pearson(xy: List<Pair<Double, Double>>): Correlation? {
        if (xy.size < MIN_PAIRS) return null
        val r = RustScores.pearson(xy.map { it.first }, xy.map { it.second })
            ?.coerceIn(-1.0, 1.0) ?: return null
        return Correlation(r = r, n = xy.size)
    }

    /** The band every correlation surface reads; whoop-rs owns where one band ends and the next begins. */
    fun strength(r: Double): CorrelationStrength = RustScores.correlationStrength(r)

    /** The band as a sentence-opening noun phrase, as Insights and Mind word it. */
    @StringRes
    fun strengthPhraseRes(r: Double): Int = when (strength(r)) {
        CorrelationStrength.NEGLIGIBLE -> R.string.insights_strength_negligible
        CorrelationStrength.WEAK -> R.string.insights_strength_weak
        CorrelationStrength.MODERATE -> R.string.insights_strength_moderate
        CorrelationStrength.STRONG -> R.string.insights_strength_strong
        CorrelationStrength.VERY_STRONG -> R.string.insights_strength_very_strong
    }

    /** The same wording for a caller that cannot reach resources yet; delete once every
     *  correlation surface reads [strengthPhraseRes]. */
    fun strengthPhrase(r: Double): String = when (strength(r)) {
        CorrelationStrength.NEGLIGIBLE -> "No"
        CorrelationStrength.WEAK -> "A weak"
        CorrelationStrength.MODERATE -> "A moderate"
        CorrelationStrength.STRONG -> "A strong"
        CorrelationStrength.VERY_STRONG -> "A very strong"
    }
}

package com.noop.ui

import com.noop.analytics.RustScores
import kotlin.math.abs

// MARK: - Correlation engine
//
// The one day-alignment adapter, strength ladder and display gate the correlation surfaces (Compare,
// Mind, Insights) share. The r itself is whoop-rs's; only the join, the band and the "too few pairs
// to show" gate live here.

/** A Pearson r over [n] aligned day pairs. */
internal data class Correlation(val r: Double, val n: Int)

/** Strength band of a correlation by |r|. Each surface words the band its own way. */
internal enum class CorrelationStrength { NEGLIGIBLE, WEAK, MODERATE, STRONG, VERY_STRONG }

internal object CorrelationEngine {

    /** Below this many overlapping days a correlation is not shown at all. */
    const val MIN_PAIRS = 3

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

    /** The |r| cut points every correlation surface bands on. */
    fun strength(r: Double): CorrelationStrength {
        val m = abs(r)
        return when {
            m < 0.1 -> CorrelationStrength.NEGLIGIBLE
            m < 0.3 -> CorrelationStrength.WEAK
            m < 0.5 -> CorrelationStrength.MODERATE
            m < 0.7 -> CorrelationStrength.STRONG
            else -> CorrelationStrength.VERY_STRONG
        }
    }

    /** The band as a sentence-opening noun phrase, as Insights and Mind word it. */
    fun strengthPhrase(r: Double): String = when (strength(r)) {
        CorrelationStrength.NEGLIGIBLE -> "No"
        CorrelationStrength.WEAK -> "A weak"
        CorrelationStrength.MODERATE -> "A moderate"
        CorrelationStrength.STRONG -> "A strong"
        CorrelationStrength.VERY_STRONG -> "A very strong"
    }
}

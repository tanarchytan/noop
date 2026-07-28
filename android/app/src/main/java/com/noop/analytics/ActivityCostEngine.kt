package com.noop.analytics

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * ActivityCostEngine — what each activity costs your recovery.
 *
 * Pure, deterministic, DB-free. Given which days each sport was tagged and daily Charge
 * (recovery, 0-100) history, computes per sport how far next-morning Charge sits below the
 * rest-day baseline (days untouched by any tagged sport or its D+1..D+maxLookahead
 * after-effect window), and how many days it takes to climb back within tolerance. A
 * descriptive average over aligned day keys, not a measurement of any single session -
 * nothing here is learned.
 */

/**
 * One sport's recovery cost: how far below your rest baseline your next-morning Charge
 * sits after a session of this sport, and how long it takes to bounce back.
 */
data class ActivityCost(
    /** The sport key (raw WHOOP sport / activity name, as tagged on the day). */
    val sport: String,
    /** Signed cost in Charge points: baselineMean - meanNextMorning. Positive = the
     *  morning after sits BELOW your rest baseline (it cost you recovery). */
    val delta: Double,
    /** Mean next-morning (D+1) Charge over tagged days that had a D+1 value, 0–100. */
    val meanNextMorning: Double,
    /** Mean rest-day Charge this sport is measured against (shared across sports), 0–100. */
    val baselineMean: Double,
    /** Days for the averaged forward trajectory to climb back within [ActivityCostEngine.tolerance]
     *  of the baseline; null when it never recovers inside the lookahead window (or too thin). */
    val daysToBaseline: Int?,
    /** Number of tagged days that contributed a D+1 Charge value. */
    val n: Int,
    /** Per-result certainty tier (reuses the Charge/Effort/Rest confidence ladder). */
    val confidence: ScoreConfidence,
) {
    /**
     * Plain-English summary of this sport's recovery cost. Degrades gracefully: drops the
     * bounce-back clause when [daysToBaseline] is null, and says "barely move" when the
     * cost is under a point in either direction.
     */
    fun sentence(): String {
        val mag = abs(delta)
        val points = ActivityCostEngine.roundToIntHalfUp(mag)
        if (mag < ActivityCostEngine.barelyMovesPoints) {
            return "Sessions like this barely move your next-day Charge (n=$n)."
        }
        val direction = if (delta >= 0) "cost you" else "lift"
        val head = "Sessions like this usually $direction about $points Charge " +
            "point${if (points == 1) "" else "s"} the next morning"
        val days = daysToBaseline
        return if (days != null) {
            "$head and take about $days day${if (days == 1) "" else "s"} to bounce back (n=$n)."
        } else {
            "$head (n=$n)."
        }
    }
}

object ActivityCostEngine {

    // Tunables: documented, deterministic constants, not learned.

    /** Tagged next-morning pairs below which a sport is OMITTED (too thin to report). */
    const val minSessions: Int = 4

    /** Pairs at/above which a sport's confidence is SOLID (else BUILDING). */
    const val solidSessions: Int = 8

    /** How many days forward the bounce-back trajectory is probed (D+1 … D+maxLookahead). */
    const val maxLookahead: Int = 7

    /** Charge points within the baseline that count as "recovered" for daysToBaseline. */
    const val tolerance: Double = 3.0

    /** |delta| under this (points) reads as "barely moves" in [ActivityCost.sentence]. */
    const val barelyMovesPoints: Double = 1.0

    /**
     * Compute each sport's recovery cost from tagged activity days and daily Charge.
     *
     * @param activityDaysBySport per sport, the SET of "yyyy-MM-dd" day keys that sport was
     *   tagged on. Using a Set means same-day duplicates are already collapsed.
     * @param recoveryByDay daily Charge (recovery, 0–100) keyed by "yyyy-MM-dd".
     * @return one [ActivityCost] per sport that cleared [minSessions], ranked by |delta|
     *   desc, SOLID before BUILDING, sport name ascending on a tie. Empty input (or no
     *   sport thick enough) → an empty list.
     */
    fun evaluate(
        activityDaysBySport: Map<String, Set<String>>,
        recoveryByDay: Map<String, Double>,
    ): List<ActivityCost> {
        if (activityDaysBySport.isEmpty() || recoveryByDay.isEmpty()) return emptyList()

        // Rest days = days with a Charge value not tagged with any sport and not inside the
        // forward recovery window (D+1 … D+maxLookahead) of any tagged day. Excluding that window
        // keeps the baseline genuinely untouched, since those mornings are what the cost measures.
        val activeUnion = HashSet<String>()
        for ((_, days) in activityDaysBySport) activeUnion.addAll(days)
        val affected = HashSet(activeUnion)
        for (day in activeUnion) {
            for (k in 1..maxLookahead) {
                shiftDay(day, k)?.let { affected.add(it) }
            }
        }
        val restValues = ArrayList<Double>()
        for ((day, value) in recoveryByDay) {
            if (!affected.contains(day)) restValues.add(value)
        }
        // No untouched days → no baseline to measure against → nothing honest to say.
        if (restValues.isEmpty()) return emptyList()
        val baselineMean = mean(restValues)

        val results = ArrayList<ActivityCost>()
        // Sort sports up front so the build order is deterministic regardless of map order.
        for (sport in activityDaysBySport.keys.sorted()) {
            val taggedDays = activityDaysBySport.getValue(sport)

            // Collect next-morning (D+1) Charge for each tagged day that has one.
            val nextMornings = ArrayList<Double>()
            for (day in taggedDays) {
                val d1 = shiftDay(day, 1) ?: continue
                val v = recoveryByDay[d1] ?: continue
                nextMornings.add(v)
            }
            val n = nextMornings.size
            // Thin sports are omitted entirely — better silent than fabricated.
            if (n < minSessions) continue

            val meanNextMorning = mean(nextMornings)
            val delta = baselineMean - meanNextMorning
            val daysToBaseline = forwardDaysToBaseline(taggedDays, recoveryByDay, baselineMean)
            val confidence = if (n >= solidSessions) ScoreConfidence.SOLID else ScoreConfidence.BUILDING

            results.add(
                ActivityCost(
                    sport = sport,
                    delta = delta,
                    meanNextMorning = meanNextMorning,
                    baselineMean = baselineMean,
                    daysToBaseline = daysToBaseline,
                    n = n,
                    confidence = confidence,
                ),
            )
        }

        return rank(results)
    }

    // Bounce-back trajectory.

    /**
     * Smallest k in 1..maxLookahead where the averaged forward trajectory (mean Charge[D+k]
     * over tagged days with a D+k value) reaches within [tolerance] of [baselineMean]; null
     * if it never does, or no day has a value at that horizon.
     */
    internal fun forwardDaysToBaseline(
        taggedDays: Set<String>,
        recoveryByDay: Map<String, Double>,
        baselineMean: Double,
    ): Int? {
        val target = baselineMean - tolerance
        for (k in 1..maxLookahead) {
            val vals = ArrayList<Double>()
            for (day in taggedDays) {
                val dk = shiftDay(day, k) ?: continue
                val v = recoveryByDay[dk] ?: continue
                vals.add(v)
            }
            if (vals.isEmpty()) continue
            if (mean(vals) >= target) return k
        }
        return null
    }

    // Ranking.

    /** Stable rank: |delta| desc, then SOLID before BUILDING, then sport name asc. */
    internal fun rank(items: List<ActivityCost>): List<ActivityCost> =
        items.sortedWith(
            compareByDescending<ActivityCost> { abs(it.delta) }
                .thenByDescending { confidenceRank(it.confidence) }
                .thenBy { it.sport },
        )

    /** Ordinal so SOLID sorts ahead of BUILDING (and CALIBRATING last). */
    internal fun confidenceRank(c: ScoreConfidence): Int = when (c) {
        ScoreConfidence.SOLID -> 2
        ScoreConfidence.BUILDING -> 1
        ScoreConfidence.CALIBRATING -> 0
    }

    // Stats (self-contained).

    internal fun mean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        return values.sum() / values.size
    }

    /** Round half away from zero to an Int, for the non-negative magnitudes used in
     *  [ActivityCost.sentence]. */
    internal fun roundToIntHalfUp(x: Double): Int = x.roundToInt()

    /** Shift a "yyyy-MM-dd" day by [delta] days; null when the key is unparseable, which the
     *  caller treats as no such day rather than as the day itself. */
    internal fun shiftDay(day: String, delta: Int): String? {
        if (delta == 0) return day
        CalendarDay.parse(day) ?: return null
        return CalendarDay.addDays(day, delta)
    }
}

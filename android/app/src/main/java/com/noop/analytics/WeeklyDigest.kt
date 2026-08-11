package com.noop.analytics

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// WeeklyDigest.kt — a deterministic, offline "week in review".
//
// Pure, deterministic, DB-free. Given daily series per metric (keyed "yyyy-MM-dd"), builds a
// Monday-anchored "this week" summary: per-metric stats (mean / median / min / max / SD / OLS
// slope), a week-over-week comparison (vs the preceding Mon-Sun week), a vs-baseline delta
// (this week's mean vs the trailing [baselineWeeks] weeks), Rest-score steadiness (SD of Rest
// values, lower = steadier), a strain-vs-recovery balance read, and 1-2 [FocalPoint]s the UI words.
// Consumes plain Map<String, Double> series, decoupled from the Room DailyMetric type.
// Week math is timezone/locale-free: weekday via Sakamoto's algorithm, week windows as
// inclusive ISO-string ranges (string comparison is chronological for ISO dates).

/** The five headline metrics a weekly digest reports on. */
enum class WeeklyMetric(val key: String) {
    CHARGE("charge"),  // recovery, 0–100
    EFFORT("effort"),  // strain / Effort, 0–100
    REST("rest"),      // sleep performance composite, 0–100
    RHR("rhr"),        // resting heart rate, bpm
    HRV("hrv");        // heart-rate variability, ms

    /** Display unit symbol (empty for the unitless 0–100 scores). */
    val unit: String
        get() = when (this) {
            CHARGE, EFFORT, REST -> ""
            RHR -> "bpm"
            HRV -> "ms"
        }

    /** True when a HIGHER value is the better outcome. Resting HR is the lone exception. */
    val higherIsBetter: Boolean
        get() = this != RHR

    /**
     * A coarse "typical day-to-day range" used to normalise week-over-week deltas so
     * movers on different scales are rankable against each other. Deterministic constants,
     * not personal baselines.
     */
    val typicalSpread: Double
        get() = when (this) {
            CHARGE -> 12.0
            EFFORT -> 12.0
            REST -> 12.0
            RHR -> 4.0
            HRV -> 8.0
        }
}

/** Summary statistics for one slice of a daily series. */
data class SeriesStat(
    val mean: Double,
    val median: Double,
    val min: Double,
    val max: Double,
    val stdev: Double,
    val n: Int,
    val slopePerDay: Double,
) {
    companion object {
        val EMPTY = SeriesStat(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0.0)
    }
}

/** The comparison of a `current` period against a `previous` one. */
data class PeriodComparison(
    val current: SeriesStat,
    val previous: SeriesStat,
    /** Signed change in mean: current.mean − previous.mean. */
    val delta: Double,
    /** Percent change vs previous.mean, or null when previous is empty / mean is 0. */
    val pctChange: Double?,
    /** Direction: -1 (down), 0 (flat / a period empty), +1 (up). */
    val direction: Int,
)

/** One metric's line in the weekly digest. */
data class WeeklyMetricSummary(
    val metric: WeeklyMetric,
    val thisWeek: SeriesStat,
    val weekOverWeek: PeriodComparison,
    val baselineMean: Double?,
    val vsBaseline: Double?,
) {
    /** Signed week-over-week change in the metric's own units (this − last). */
    val wowDelta: Double get() = weekOverWeek.delta

    /**
     * Week-over-week change as GOOD (+1) / BAD (-1) / FLAT (0), folding in
     * `higherIsBetter` (so a Resting-HR rise reads as worse). 0 when flat or a period empty.
     */
    val wowGoodness: Int
        get() {
            if (weekOverWeek.direction == 0) return 0
            val up = weekOverWeek.direction > 0
            return if (up == metric.higherIsBetter) 1 else -1
        }

    /** The week-over-week change scaled by the metric's typical spread. 0 when a period empty. */
    val normalisedMove: Double
        get() {
            if (weekOverWeek.current.n == 0 || weekOverWeek.previous.n == 0) return 0.0
            val s = metric.typicalSpread
            return if (s > 0) wowDelta / s else 0.0
        }

    /**
     * True when the week-over-week comparison is sparse: both weeks have a reading, but
     * either has fewer than [WeeklyDigestEngine.MIN_DAYS_FOR_FOCUS] days. Still shown with
     * its raw arrow + %, but not dressed as a confident good/bad verdict.
     */
    val isRoughComparison: Boolean
        get() {
            val cur = weekOverWeek.current.n
            val prev = weekOverWeek.previous.n
            if (cur == 0 || prev == 0) return false
            return cur < WeeklyDigestEngine.MIN_DAYS_FOR_FOCUS ||
                prev < WeeklyDigestEngine.MIN_DAYS_FOR_FOCUS
        }
}

/** How this week's Effort sat against this week's Charge. The wording for each lives in the UI. */
enum class BalanceRead { OVERREACHING, BALANCED, UNDERLOADED, INSUFFICIENT }

/**
 * One focal point of the week: which read fired, plus the figures its sentence embeds. The engine
 * picks the read and supplies the numbers; the UI owns every word.
 */
sealed interface FocalPoint {

    /**
     * The week's biggest week-over-week mover. [percent] is set when the change is reportable as a
     * percentage (|pct| >= 1); otherwise [points] carries the change in the metric's own units. Both
     * [thisAvg] / [lastAvg] and [points] are already on the display scale the digest was built with.
     */
    data class Mover(
        val metric: WeeklyMetric,
        /** +1 the metric rose, -1 it fell. A mover is never flat. */
        val direction: Int,
        /** +1 the move is a good sign, -1 it is worth a look. Folds in `higherIsBetter`. */
        val goodness: Int,
        val percent: Int?,
        val points: Double?,
        val thisAvg: Int,
        val lastAvg: Int,
    ) : FocalPoint

    /** The Effort-vs-Charge balance read, surfaced when it is not [BalanceRead.BALANCED]. */
    data class Balance(val read: BalanceRead) : FocalPoint

    /** Too few days into this week ([days] in 1 until MIN_DAYS_FOR_FOCUS) to call a trend. */
    data class TooEarly(val days: Int) : FocalPoint

    /** Last week carried only [days] days, so the comparison is rough rather than a trend. */
    data class RoughPreviousWeek(val days: Int) : FocalPoint

    /** Nothing moved, and Rest held within [restScoreSD] points. */
    data class SteadyWithRest(val restScoreSD: Double) : FocalPoint

    /** Nothing moved meaningfully from last week. */
    data object Steady : FocalPoint
}

/** The complete week-in-review. */
data class WeeklyDigest(
    /** The Monday that anchors "this week" ("yyyy-MM-dd"). */
    val weekStart: String,
    /** The Sunday that ends "this week" ("yyyy-MM-dd"). */
    val weekEnd: String,
    /** Per-metric summaries, in WeeklyMetric.values() order. */
    val metrics: List<WeeklyMetricSummary>,
    /** Distinct days this week that carried at least one reading. */
    val daysWithData: Int,
    /** SD of this week's Rest values (lower = steadier), or null with < 2 Rest nights. */
    val restScoreSD: Double?,
    /** Strain-vs-recovery balance read for the week. */
    val balance: BalanceRead,
    /** 1–2 focal points, most salient first. The UI words each one. */
    val focalPoints: List<FocalPoint>,
) {
    fun summary(metric: WeeklyMetric): WeeklyMetricSummary? = metrics.firstOrNull { it.metric == metric }

    /** True when no metric carried a single reading this week. */
    val isEmpty: Boolean get() = daysWithData == 0
}

object WeeklyDigestEngine {

    /** Complete weeks before "this week" forming the vs-baseline comparison. */
    const val BASELINE_WEEKS = 4
    /** Min days each side before a week-over-week move is "real" enough to surface. */
    const val MIN_DAYS_FOR_FOCUS = 3
    /** Effort−Charge gap (points) inside which the week is "balanced". */
    const val BALANCE_BAND = 10.0
    /** Normalised-move threshold (in "typical spreads") for a focal mover. */
    const val FOCUS_THRESHOLD = 0.5

    // MARK: - Entry point

    /**
     * Build the weekly digest anchored on the Monday of the week containing [anchorDay]
     * ("yyyy-MM-dd", typically today). A non-parseable string yields an all-empty digest.
     *
     * [effortDisplayFactor] rescales the EFFORT averages (and the pts fallback magnitude) carried on
     * [FocalPoint.Mover] ONLY, so a user on a non-0-100 Effort scale never reads a stored 0–100 mean
     * in prose. Percent changes are scale-invariant and untouched.
     * Defaults to 1.0 (stored scale); display-only, no stat/delta/threshold changes.
     */
    fun build(
        byMetric: Map<WeeklyMetric, Map<String, Double>>,
        anchorDay: String,
        effortDisplayFactor: Double = 1.0,
    ): WeeklyDigest {
        val monday = mondayOfWeek(anchorDay) ?: return emptyDigest(anchorDay, anchorDay)
        val sunday = addDays(monday, 6)
        val lastMonday = addDays(monday, -7)
        val lastSunday = addDays(monday, -1)

        // Baseline: BASELINE_WEEKS complete weeks ending the day before last week starts.
        val baselineEnd = addDays(lastMonday, -1)
        val baselineStart = addDays(lastMonday, -7 * BASELINE_WEEKS)

        val summaries = mutableListOf<WeeklyMetricSummary>()
        val daysSeen = mutableSetOf<String>()

        for (metric in WeeklyMetric.values()) {
            val series = byMetric[metric] ?: emptyMap()

            val thisVals = valuesInRange(series, monday, sunday, daysSeen)
            val lastVals = valuesInRange(series, lastMonday, lastSunday, null)
            val baseVals = valuesInRange(series, baselineStart, baselineEnd, null)

            val thisStat = stat(thisVals)
            val wow = compare(thisVals, lastVals)
            val baseMean = if (baseVals.isEmpty()) null else baseVals.sum() / baseVals.size
            val vsBase = baseMean?.let { thisStat.mean - it }

            summaries.add(WeeklyMetricSummary(metric, thisStat, wow, baseMean, vsBase))
        }

        val restStat = summaries.firstOrNull { it.metric == WeeklyMetric.REST }?.thisWeek
        val restScoreSD = if ((restStat?.n ?: 0) >= 2) restStat?.stdev else null

        val balance = balanceRead(summaries)
        val focal = focalPoints(summaries, balance, restScoreSD, effortDisplayFactor)

        return WeeklyDigest(
            weekStart = monday, weekEnd = sunday, metrics = summaries,
            daysWithData = daysSeen.size, restScoreSD = restScoreSD,
            balance = balance, focalPoints = focal,
        )
    }

    // MARK: - Single-slice statistics

    /** Summarise a slice into a SeriesStat. Slope is OLS vs the 0-based index. EMPTY when empty. */
    fun stat(values: List<Double>): SeriesStat {
        val n = values.size
        if (n == 0) return SeriesStat.EMPTY

        val mean = values.sum() / n
        val med = median(values)
        val mn = values.min()
        val mx = values.max()

        val sd = if (n >= 2) {
            var ss = 0.0
            for (v in values) { val dd = v - mean; ss += dd * dd }
            sqrt(ss / (n - 1))
        } else 0.0

        return SeriesStat(mean, med, mn, mx, sd, n, leastSquaresSlope(values))
    }

    /** Compare a current slice to a previous slice (delta/direction on the means). */
    fun compare(current: List<Double>, previous: List<Double>): PeriodComparison {
        val cur = stat(current)
        val prev = stat(previous)
        val delta = cur.mean - prev.mean

        val pct = if (prev.n > 0 && prev.mean != 0.0) (cur.mean - prev.mean) / abs(prev.mean) * 100.0 else null

        val direction = when {
            cur.n == 0 || prev.n == 0 -> 0
            delta > 0 -> 1
            delta < 0 -> -1
            else -> 0
        }
        return PeriodComparison(cur, prev, delta, pct, direction)
    }

    private fun median(values: List<Double>): Double = RustScores.median(values)

    private fun leastSquaresSlope(values: List<Double>): Double = RustScores.slope(values)

    // MARK: - Balance read

    private fun balanceRead(summaries: List<WeeklyMetricSummary>): BalanceRead {
        val effort = summaries.firstOrNull { it.metric == WeeklyMetric.EFFORT }?.thisWeek
        val charge = summaries.firstOrNull { it.metric == WeeklyMetric.CHARGE }?.thisWeek
        if (effort == null || charge == null ||
            effort.n < MIN_DAYS_FOR_FOCUS || charge.n < MIN_DAYS_FOR_FOCUS
        ) {
            return BalanceRead.INSUFFICIENT
        }
        val gap = effort.mean - charge.mean
        return when {
            gap > BALANCE_BAND -> BalanceRead.OVERREACHING
            gap < -BALANCE_BAND -> BalanceRead.UNDERLOADED
            else -> BalanceRead.BALANCED
        }
    }

    // MARK: - Focal points

    private fun focalPoints(
        summaries: List<WeeklyMetricSummary>,
        balance: BalanceRead,
        restScoreSD: Double?,
        effortDisplayFactor: Double = 1.0,
    ): List<FocalPoint> {
        val movers = summaries
            .filter {
                it.weekOverWeek.current.n >= MIN_DAYS_FOR_FOCUS &&
                    it.weekOverWeek.previous.n >= MIN_DAYS_FOR_FOCUS &&
                    abs(it.normalisedMove) >= FOCUS_THRESHOLD
            }
            .sortedByDescending { abs(it.normalisedMove) }

        val points = mutableListOf<FocalPoint>()

        movers.firstOrNull()?.let { points.add(mover(it, effortDisplayFactor)) }

        if (balance == BalanceRead.OVERREACHING || balance == BalanceRead.UNDERLOADED) {
            points.add(FocalPoint.Balance(balance))
        } else if (movers.size >= 2) {
            points.add(mover(movers[1], effortDisplayFactor))
        }

        // Nothing cleared the mover bar. Three causes: a SPARSE CURRENT week (too few days
        // for a trend), a SPARSE PREVIOUS week (movers need previous.n >= MIN_DAYS_FOR_FOCUS,
        // so a new user's raw chip % has no mover behind it), or a genuinely steady week.
        if (points.isEmpty()) {
            val currentDays = summaries.maxOfOrNull { it.weekOverWeek.current.n } ?: 0
            val prevDays = summaries.maxOfOrNull { it.weekOverWeek.previous.n } ?: 0
            points.add(
                when {
                    currentDays in 1 until MIN_DAYS_FOR_FOCUS -> FocalPoint.TooEarly(currentDays)
                    currentDays >= MIN_DAYS_FOR_FOCUS && prevDays in 1 until MIN_DAYS_FOR_FOCUS ->
                        FocalPoint.RoughPreviousWeek(prevDays)
                    restScoreSD != null && restScoreSD <= 6.0 ->
                        FocalPoint.SteadyWithRest(round1(restScoreSD))
                    else -> FocalPoint.Steady
                },
            )
        }

        return points.take(2)
    }

    /**
     * One mover as the figures its sentence embeds. [effortDisplayFactor] rescales the EFFORT averages
     * (and its pts fallback) for display only — % is scale-invariant.
     */
    private fun mover(s: WeeklyMetricSummary, effortDisplayFactor: Double = 1.0): FocalPoint.Mover {
        val f = if (s.metric == WeeklyMetric.EFFORT) effortDisplayFactor else 1.0
        val pct = s.weekOverWeek.pctChange
        val reportablePct = if (pct != null && abs(pct) >= 1) abs(pct).roundToInt() else null
        return FocalPoint.Mover(
            metric = s.metric,
            direction = if (s.wowDelta > 0) 1 else -1,
            goodness = s.wowGoodness,
            percent = reportablePct,
            points = if (reportablePct == null) round1(abs(s.wowDelta) * f) else null,
            thisAvg = (s.thisWeek.mean * f).roundToInt(),
            lastAvg = (s.weekOverWeek.previous.mean * f).roundToInt(),
        )
    }

    // MARK: - Range extraction

    /**
     * Values of [series] whose day is within [start, end] inclusive (ISO string comparison is
     * chronological), ordered chronologically so the SeriesStat slope is meaningful. When
     * [daysSeen] is non-null, the days that carried a value are recorded into it.
     */
    private fun valuesInRange(
        series: Map<String, Double>,
        start: String,
        end: String,
        daysSeen: MutableSet<String>?,
    ): List<Double> {
        val inRange = series.filterKeys { it in start..end }
        daysSeen?.addAll(inRange.keys)
        return inRange.entries.sortedBy { it.key }.map { it.value }
    }

    // MARK: - Pure week math (timezone/locale-free)

    /** The Monday (ISO) of the week containing [day]. null if [day] is not a valid yyyy-MM-dd. */
    fun mondayOfWeek(day: String): String? {
        val ymd = parseYMD(day) ?: return null
        val w = weekday(ymd[0], ymd[1], ymd[2]) ?: return null
        // weekday: 0=Sun … 6=Sat. Days since Monday: Mon=0 … Sun=6.
        val sinceMonday = (w + 6) % 7
        return addDays(day, -sinceMonday)
    }

    /** Add [n] days (may be negative) to a "yyyy-MM-dd" day. Returns the input if unparseable. */
    fun addDays(day: String, n: Int): String = CalendarDay.addDays(day, n)

    /** Sakamoto's day-of-week: 0=Sunday to 6=Saturday. null for an invalid date. */
    fun weekday(y: Int, m: Int, d: Int): Int? {
        if (m !in 1..12 || d < 1 || d > CalendarDay.daysInMonth(y, m)) return null
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        var yy = y
        if (m < 3) yy -= 1
        return (yy + yy / 4 - yy / 100 + yy / 400 + t[m - 1] + d) % 7
    }

    /** Parse "yyyy-MM-dd" into [y, m, d], validating the date is real. null otherwise. */
    fun parseYMD(s: String): IntArray? =
        CalendarDay.parse(s)?.let { (y, m, d) -> intArrayOf(y, m, d) }

    // MARK: - Empty digest

    private fun emptyDigest(weekStart: String, weekEnd: String): WeeklyDigest {
        val summaries = WeeklyMetric.values().map { m ->
            WeeklyMetricSummary(m, SeriesStat.EMPTY, compare(emptyList(), emptyList()), null, null)
        }
        return WeeklyDigest(weekStart, weekEnd, summaries, 0, null, BalanceRead.INSUFFICIENT, emptyList())
    }

    // MARK: - Formatting helpers

    private fun round1(x: Double): Double = (x * 10).roundToInt() / 10.0
}

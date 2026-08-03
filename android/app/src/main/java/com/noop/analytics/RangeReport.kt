package com.noop.analytics

/*
 * RangeReport.kt — data model for a shareable offline "trends report" over a date range.
 * Pure aggregation only, no rendering (the UI builds the PDF/PNG view from this struct).
 *
 * Given each metric's day→value series (sparse ok) and an inclusive [start, end]
 * "yyyy-MM-dd" range, produces per metric with >= 1 in-range value: n, mean, min/max
 * (value + day), first/second-half mean, a rising/falling/flat trend, and the latest
 * value, plus totalDays and a headline set.
 *
 * Day keys are the same "yyyy-MM-dd" strings AnalyticsEngine emits; lexicographic order
 * is chronological for zero-padded ISO days, so days compare as raw strings — no Date,
 * timezone, or locale. Self-contained: does not depend on WeeklyDigest.
 */

/**
 * The metrics a range report can summarise. WORKOUTS and STRESS lead the list so they
 * rank first in the report; the rest keep their established order.
 */
enum class ReportMetric {
    WORKOUTS,      // logged workouts per day, count
    STRESS,        // daily stress score, 0–3 (lower is calmer)
    RECOVERY,      // Charge / recovery, 0–100
    SLEEP_HOURS,   // time asleep, hours
    HRV,           // heart-rate variability, ms
    RESTING_HR,    // resting heart rate, bpm
    STRAIN,        // Effort / strain, 0–100
    RESP_RATE,     // respiratory rate during sleep, breaths/min
    SKIN_TEMP_DEV; // skin-temperature deviation from baseline, °C (signed)

    /** Human label for the metric (matches the rest of the app's naming). */
    val label: String
        get() = when (this) {
            WORKOUTS -> "Workouts"
            STRESS -> "Stress"
            RECOVERY -> "Recovery"
            SLEEP_HOURS -> "Sleep"
            HRV -> "HRV"
            RESTING_HR -> "Resting HR"
            STRAIN -> "Strain"
            RESP_RATE -> "Respiratory rate"
            SKIN_TEMP_DEV -> "Skin temp"
        }

    /** Display unit suffix (empty for the unitless 0–100 scores and the 0–3 stress index). */
    val unit: String
        get() = when (this) {
            RECOVERY, STRAIN, STRESS -> ""
            WORKOUTS -> "/day"
            SLEEP_HOURS -> "h"
            HRV -> "ms"
            RESTING_HR -> "bpm"
            RESP_RATE -> "br/min"
            SKIN_TEMP_DEV -> "°C"
        }

    /**
     * Whether the metric's values are shown to one decimal place (fractional scores /
     * rates) rather than as whole numbers. Workouts is a whole count; stress is a 0–3
     * index shown to one decimal so small moves read.
     */
    val usesOneDecimal: Boolean
        get() = when (this) {
            SLEEP_HOURS, RESP_RATE, SKIN_TEMP_DEV, STRESS, WORKOUTS -> true
            else -> false
        }

    /**
     * True when a HIGHER value is the better outcome. Resting HR, respiratory rate and
     * stress are the metrics where lower is better. (Ignored for valence-free metrics —
     * see [framesGoodBad].)
     */
    val higherIsBetter: Boolean
        get() = when (this) {
            RESTING_HR, RESP_RATE, STRESS -> false
            else -> true
        }

    /**
     * Whether a rising/falling move carries a clear good/bad valence. False for a signed
     * deviation metric (skin-temp) and for workout count (a lifestyle choice, not
     * inherently good/bad) — those show trend direction with no verdict, chip neutral.
     */
    val framesGoodBad: Boolean
        get() = when (this) {
            SKIN_TEMP_DEV, WORKOUTS -> false
            else -> true
        }

    companion object {
        /** Fixed iteration order used when building the report and its headlines. */
        val allCases: List<ReportMetric> =
            listOf(
                WORKOUTS, STRESS, RECOVERY, SLEEP_HOURS, HRV, RESTING_HR, STRAIN,
                RESP_RATE, SKIN_TEMP_DEV,
            )
    }
}

/** Which way a metric moved across the range. FLAT means the trend's interval straddles zero. */
enum class ReportTrend { RISING, FALLING, FLAT }

/** A value paired with the day it fell on ("yyyy-MM-dd"). */
data class DayValue(val day: String, val value: Double)

/**
 * One metric's summary over the report range. Only produced for metrics that carried at
 * least one value in range (so every field is meaningful — no fabricated zeros).
 */
data class MetricRangeStat(
    val metric: ReportMetric,
    /** Days carrying a value inside the range. */
    val n: Int,
    /** Mean of the in-range values. */
    val mean: Double,
    /** The lowest value and the day it fell on. */
    val min: DayValue,
    /** The highest value and the day it fell on. */
    val max: DayValue,
    /** Mean of the first half of the in-range days (by day position). */
    val firstHalfMean: Double,
    /** Mean of the second half of the in-range days (by day position). */
    val secondHalfMean: Double,
    /** Trend direction over the range (rising / falling / flat). */
    val trend: ReportTrend,
    /** The value on the latest day present in range. */
    val latest: DayValue,
    /**
     * How far the trend stands clear of its own noise, 0 (indistinguishable) to 1 (strong). A display
     * and ranking scalar from whoop-rs, not a probability. 0 when no trend could be fitted.
     */
    val trendSignificance: Double = 0.0,
) {
    /** Signed first→second half change (secondHalfMean − firstHalfMean), metric units. */
    val halfDelta: Double get() = secondHalfMean - firstHalfMean
}

/** The complete shareable trends report over a date range. */
data class RangeReport(
    /** Inclusive start day of the range ("yyyy-MM-dd"). */
    val start: String,
    /** Inclusive end day of the range ("yyyy-MM-dd"). */
    val end: String,
    /** Number of calendar days the range spans (inclusive). 0 for an invalid range. */
    val totalDays: Int,
    /**
     * Per-metric stats, in ReportMetric.allCases order, for metrics that had ≥ 1 value in
     * range. Metrics with no in-range data are OMITTED entirely.
     */
    val metrics: List<MetricRangeStat>,
    /**
     * A short headline set the UI can show at the top — one line per present metric,
     * most-notable first, already plain-English.
     */
    val headlines: List<String>,
) {
    /** Look up one metric's stat (null when that metric had no in-range data). */
    fun stat(metric: ReportMetric): MetricRangeStat? = metrics.firstOrNull { it.metric == metric }

    /** True when no metric carried a single reading in range. */
    val isEmpty: Boolean get() = metrics.isEmpty()
}

object RangeReportEngine {

    /**
     * Build a RangeReport over the inclusive [start, end] day range from each metric's
     * day→value series.
     *
     * @param metrics per-metric day→value maps ("yyyy-MM-dd" → value). Missing metrics and
     *   missing days are simply absent; this is robust to sparse data.
     * @param start inclusive range start, "yyyy-MM-dd".
     * @param end inclusive range end, "yyyy-MM-dd".
     *
     * If [end] sorts before [start] the range is treated as empty (no metrics, 0 days).
     */
    fun build(
        metrics: Map<ReportMetric, Map<String, Double>>,
        start: String,
        end: String,
    ): RangeReport {
        // A valid window requires start <= end (ISO string compare == chronological).
        if (start > end) {
            return RangeReport(start = start, end = end, totalDays = 0,
                metrics = emptyList(), headlines = emptyList())
        }
        val totalDays = dayCount(start, end)

        val stats = mutableListOf<MetricRangeStat>()
        for (metric in ReportMetric.allCases) {
            val series = metrics[metric] ?: emptyMap()
            // In-range entries, ordered chronologically by their day string.
            val ordered = series
                .filter { it.key >= start && it.key <= end }
                .toList()
                .sortedBy { it.first }
            if (ordered.isEmpty()) continue   // omit metrics with no data

            val days = ordered.map { it.first }
            val values = ordered.map { it.second }
            val n = values.size

            val mn = mean(values)

            // Min / max carry the day they fell on. On ties the EARLIEST day wins (values
            // are already chronological, so the first hit is earliest).
            var minDV = DayValue(days[0], values[0])
            var maxDV = DayValue(days[0], values[0])
            for (i in 1 until n) {
                if (values[i] < minDV.value) minDV = DayValue(days[i], values[i])
                if (values[i] > maxDV.value) maxDV = DayValue(days[i], values[i])
            }

            // Split down the middle by POSITION. Odd counts give the larger half to the
            // second half (the back of the range), so the "recent" read is never starved.
            val mid = n / 2
            val firstHalf = values.subList(0, mid)
            val secondHalf = values.subList(mid, n)
            // With n == 1 the first half is empty; fall back to the single value so both
            // halves are defined and equal (→ flat, no fabricated movement).
            val firstMean = if (firstHalf.isEmpty()) mn else mean(firstHalf)
            val secondMean = if (secondHalf.isEmpty()) mn else mean(secondHalf)

            // Day offsets from the range start, so gaps count: an unparseable day drops out in Rust.
            val dayOffsets = days.map { CalendarDay.daysBetween(start, it)?.toDouble() ?: Double.NaN }
            val line = RustScores.trendline(dayOffsets, values, windowDays = totalDays.toDouble())

            val latest = DayValue(days[n - 1], values[n - 1])

            stats.add(
                MetricRangeStat(
                    metric = metric, n = n, mean = mn, min = minDV, max = maxDV,
                    firstHalfMean = firstMean, secondHalfMean = secondMean,
                    trend = trendOf(line), latest = latest,
                    trendSignificance = line?.significance ?: 0.0,
                ),
            )
        }

        val headlines = makeHeadlines(stats)
        return RangeReport(start = start, end = end, totalDays = totalDays,
            metrics = stats, headlines = headlines)
    }

    // Headlines

    /**
     * One plain-English line per present metric, ranked most-notable first by [salience].
     * Folds in good/bad framing.
     */
    internal fun makeHeadlines(stats: List<MetricRangeStat>): List<String> =
        stats.sortedByDescending { salience(it) }.map { headline(it) }

    /**
     * Ranking key: the trend's own significance, which is unit-free by construction, so metrics on
     * different scales compare directly. Ties keep [ReportMetric.allCases] order.
     */
    internal fun salience(s: MetricRangeStat): Double = s.trendSignificance

    /** Render one metric's headline. Trend word + good/bad framing + the two half means. */
    internal fun headline(s: MetricRangeStat): String {
        val word = when (s.trend) {
            ReportTrend.RISING -> "trending up"
            ReportTrend.FALLING -> "trending down"
            ReportTrend.FLAT -> "holding steady"
        }
        val frame = if (s.trend == ReportTrend.FLAT || !s.metric.framesGoodBad) {
            // Flat, or a signed-deviation metric with no inherent good/bad direction.
            ""
        } else {
            val up = s.trend == ReportTrend.RISING
            val good = up == s.metric.higherIsBetter
            if (good) " - a good sign" else " - worth a look"
        }
        val unit = if (s.metric.unit.isEmpty()) "" else " ${s.metric.unit}"
        return "${s.metric.label} is $word (avg ${round1(s.firstHalfMean)}$unit → " +
            "${round1(s.secondHalfMean)}$unit)$frame."
    }

    // Trend

    /** Name whoop-rs's trend direction. No trend fitted (too few days, too short a span) reads flat. */
    internal fun trendOf(line: uniffi.whoop_ffi.TrendlineInfo?): ReportTrend = when (line?.direction) {
        uniffi.whoop_ffi.TrendDirectionInfo.RISING -> ReportTrend.RISING
        uniffi.whoop_ffi.TrendDirectionInfo.FALLING -> ReportTrend.FALLING
        else -> ReportTrend.FLAT
    }

    // Day math (timezone/locale-free, ISO string in → integer out)

    /**
     * Inclusive day count between two "yyyy-MM-dd" days. 1 for the same day. 0 when either
     * day is unparseable or end sorts before start.
     */
    internal fun dayCount(start: String, end: String): Int {
        val diff = CalendarDay.daysBetween(start, end) ?: return 0
        return if (diff < 0) 0 else diff + 1
    }

    /** Parse "yyyy-MM-dd" into validated integer components (real calendar date only). */
    internal fun parseYMD(str: String): Triple<Int, Int, Int>? = CalendarDay.parse(str)

    // Stats (self-contained, deterministic)

    internal fun mean(values: List<Double>): Double = RustScores.mean(values)

    /**
     * Round to one decimal place, half-away-from-zero (not banker's rounding), so
     * headline numbers round predictably regardless of sign.
     */
    internal fun round1(x: Double): Double {
        val scaled = x * 10.0
        val rounded = if (scaled < 0) -((-scaled) + 0.5).toLong() else (scaled + 0.5).toLong()
        return rounded / 10.0
    }
}

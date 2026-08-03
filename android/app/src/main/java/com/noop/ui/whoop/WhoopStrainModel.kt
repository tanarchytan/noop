package com.noop.ui.whoop

import com.noop.analytics.RustScores
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import com.noop.ui.EffortScale
import com.noop.ui.UnitFormatter
import com.noop.ui.trendDayLabel
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// The Strain page's data assembly: calendar-day slices of the rows AppViewModel already holds. No
// health figure is derived here — every value is read from a stored column, and the one mean is
// whoop-rs's own (RustScores.mean).

/** Columns the weekly cards plot, matching the reference's seven. */
internal const val STRAIN_WEEK_DAYS = 7

/** The trailing window each metric row's comparison figure averages over. */
internal const val STRAIN_COMPARISON_DAYS = 30

/** One column of the weekly cards: its axis label and that day's already-stored figures. */
internal data class StrainDayColumn(
    val day: String,
    val label: String,
    val strain: Double?,
    val steps: Double?,
    val calories: Double?,
    val zone1to3Min: Double?,
    val zone4to5Min: Double?,
)

/** The trailing week the cards plot, and which column is the day the page is showing. */
internal data class StrainWeek(
    val columns: List<StrainDayColumn>,
    val todayIndex: Int,
) {
    val labels: List<String> get() = columns.map { it.label }
    val strain: List<Double?> get() = columns.map { it.strain }
    val steps: List<Double?> get() = columns.map { it.steps }
    val calories: List<Double?> get() = columns.map { it.calories }
    val zone1to3: List<Double?> get() = columns.map { it.zone1to3Min }
    val zone4to5: List<Double?> get() = columns.map { it.zone4to5Min }
}

/**
 * The trailing [STRAIN_WEEK_DAYS] calendar days ending on [endDay], one column each. A day with no
 * row is a null column, never a zero, so a gap in the history draws nothing at all.
 */
internal fun buildStrainWeek(days: List<DailyMetric>, endDay: String): StrainWeek {
    val end = runCatching { LocalDate.parse(endDay) }.getOrNull() ?: return StrainWeek(emptyList(), -1)
    val byDay = days.associateBy { it.day }
    val columns = (STRAIN_WEEK_DAYS - 1 downTo 0).map { back ->
        val key = end.minusDays(back.toLong()).toString()
        val row = byDay[key]
        StrainDayColumn(
            day = key,
            label = strainColumnLabel(key),
            strain = row?.strain,
            steps = row?.steps?.toDouble(),
            calories = row?.activeKcalEst,
            zone1to3Min = row?.zone1to3Min,
            zone4to5Min = row?.zone4to5Min,
        )
    }
    return StrainWeek(columns, columns.lastIndex)
}

/** "Sat" over "11" — the two-line column label the reference prints under each bar. */
private fun strainColumnLabel(day: String): String = trendDayLabel(day).replace(' ', '\n')

/**
 * The mean of [value] over the trailing [window] calendar days ending on [endDay]. whoop-rs owns the
 * mean; this only chooses the window. Null when the window holds no reading, so the caller shows nothing.
 */
internal fun strainWindowAverage(
    days: List<DailyMetric>,
    endDay: String,
    window: Int = STRAIN_COMPARISON_DAYS,
    value: (DailyMetric) -> Double?,
): Double? {
    val end = runCatching { LocalDate.parse(endDay) }.getOrNull() ?: return null
    val first = end.minusDays((window - 1).toLong()).toString()
    val readings = days
        .filter { it.day >= first && it.day <= endDay }
        .mapNotNull(value)
        .filter { it.isFinite() }
    return if (readings.isEmpty()) null else RustScores.mean(readings)
}

/** Group-separated whole number ("1,423") — steps and calories as the reference prints them. */
internal fun strainCountText(value: Double): String {
    val n = value.roundToInt()
    return if (abs(n) >= 1000) String.format(Locale.US, "%,d", n) else "$n"
}

/** The gauge denominator for the selected Effort scale (21 or 100), read from the one unit helper. */
internal fun effortAxisMax(scale: EffortScale): Double =
    UnitFormatter.effortScaleMax(scale).toDoubleOrNull() ?: StrainScorer.maxStrain

package com.noop.ui.whoop

import com.noop.analytics.BaselineState
import com.noop.analytics.Baselines
import com.noop.analytics.RestScorer
import com.noop.analytics.RustScores
import com.noop.analytics.ScoreConfidence
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import com.noop.ui.ChargeDriver
import com.noop.ui.EffortScale
import com.noop.ui.UnitFormatter
import com.noop.ui.chargeDriverRows
import com.noop.ui.trendDayLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - The trailing week behind the Recovery and Strain pages
//
// Both pages plot the same shape: seven calendar-day slots ending on the day being shown, filled from
// the rows AppViewModel already holds, with a missing day left NULL so a gap in the history draws
// nothing rather than a zero. The two assemblies were written twice and drifted apart in naming while
// staying identical in behaviour, so they now sit together where that is visible.
//
// No health figure is derived in this file. Every value is either read from a stored column or handed
// to whoop-rs through analytics (RestScorer, RustScores.mean, chargeDriverRows); the only arithmetic
// here is choosing which days go in which slot.

// The Recovery page's day-keyed reads. Everything here either buckets already-stored rows by day or
// hands them to analytics; no score, band, threshold or delta is derived in this file.

/** How many day slots the weekly cards plot. */
internal const val RECOVERY_WEEK_SLOTS = 7

/**
 * One week of day slots for the Recovery page: the axis labels, each metric's per-slot value, and
 * which slot the ring's own day sits in. A day with no stored row is a null slot and draws nothing.
 */
internal data class RecoveryWeek(
    val labels: List<String>,
    val recovery: List<Double?>,
    val hrv: List<Double?>,
    val restingHr: List<Double?>,
    val respiratory: List<Double?>,
    val sleepPerformance: List<Double?>,
    val highlightIndex: Int,
) {
    val isEmpty: Boolean get() = labels.isEmpty()
}

/**
 * Bucket [days] into [slots] calendar days ending on [endDay], highlighting [anchorDay]'s slot.
 * Sleep performance is scored per day by whoop-rs through [RestScorer]; the rest are stored fields.
 */
internal fun recoveryWeek(
    days: List<DailyMetric>,
    endDay: String,
    anchorDay: String?,
    slots: Int = RECOVERY_WEEK_SLOTS,
): RecoveryWeek {
    val end = runCatching { LocalDate.parse(endDay) }.getOrNull()
    if (end == null || slots <= 0) return EMPTY_RECOVERY_WEEK
    val keys = (slots - 1 downTo 0).map { end.minusDays(it.toLong()).toString() }
    // recentDays holds one merged row per day; the last row for a key wins, as every other read does.
    val byDay = days.associateBy { it.day }
    val rows = keys.map { byDay[it] }
    val anchorIndex = anchorDay?.let { keys.indexOf(it) } ?: -1
    return RecoveryWeek(
        labels = keys.map { weekSlotLabel(it) },
        recovery = rows.map { it?.recovery },
        hrv = rows.map { it?.avgHrv },
        restingHr = rows.map { it?.restingHr?.toDouble() },
        respiratory = rows.map { it?.respRateBpm },
        sleepPerformance = rows.map { row -> row?.let { RestScorer.restFromDaily(it) } },
        highlightIndex = if (anchorIndex >= 0) anchorIndex else keys.lastIndex,
    )
}

private val EMPTY_RECOVERY_WEEK = RecoveryWeek(
    labels = emptyList(),
    recovery = emptyList(),
    hrv = emptyList(),
    restingHr = emptyList(),
    respiratory = emptyList(),
    sleepPerformance = emptyList(),
    highlightIndex = -1,
)

/** "Sat 11" from the shared trend formatter, broken onto two lines so seven fit across the axis. */
private fun weekSlotLabel(dayKey: String): String = trendDayLabel(dayKey).replace(' ', '\n')

/**
 * The page's read of one day: the analytics driver rows behind its Charge score plus that score's own
 * confidence tier. An empty driver list means the day cannot score, so the card renders nothing.
 */
internal data class RecoveryDayRead(
    val drivers: List<ChargeDriver>,
    val confidence: ScoreConfidence,
)

/** Fold [days] into the personal baselines, then let analytics score the driver rows for [day]. */
internal fun recoveryDayRead(days: List<DailyMetric>, day: DailyMetric?): RecoveryDayRead {
    val ordered = days.sortedBy { it.day }
    val hrvBaseline = Baselines.foldHistory(ordered.map { it.avgHrv }, Baselines.hrvCfg)
    return RecoveryDayRead(
        drivers = chargeDriversFor(ordered, day, hrvBaseline),
        confidence = ScoreConfidence.forCharge(day?.recovery, hrvBaseline),
    )
}

/** Rest arrives as a 0..100 composite and the driver rows take a 0..1 fraction. A unit adapter between
 *  two analytics calls, not a formula: the drivers print the same figure back out at 0..100. */
private const val REST_FULL_SCALE = 100.0

/** Every argument is either a stored field or a folded baseline; whoop-rs scores the rows. */
private fun chargeDriversFor(
    ordered: List<DailyMetric>,
    day: DailyMetric?,
    hrvBaseline: BaselineState,
): List<ChargeDriver> {
    val d = day ?: return emptyList()
    val hrv = d.avgHrv ?: return emptyList()
    val rhr = d.restingHr?.toDouble() ?: return emptyList()
    if (!hrvBaseline.usable) return emptyList()
    return chargeDriverRows(
        hrv = hrv,
        rhr = rhr,
        resp = d.respRateBpm,
        hrvBaseline = hrvBaseline,
        rhrBaseline = Baselines.foldHistory(ordered.map { it.restingHr?.toDouble() }, Baselines.restingHRCfg),
        respBaseline = Baselines.foldHistory(ordered.map { it.respRateBpm }, Baselines.respCfg)
            .takeIf { it.usable },
        sleepPerf = RestScorer.restFromDaily(d)?.let { it / REST_FULL_SCALE } ?: d.efficiency,
        skinTempDev = d.skinTempDevC,
        recoveryIndexSlope = d.recoveryIndexSlope,
        effortBaseline = Baselines.foldHistory(ordered.map { it.strain }, Baselines.strainCfg)
            .takeIf { it.usable },
        priorDayEffort = d.priorDayEffort,
    )
}

/** "17 Jul" for a stored yyyy-MM-dd key, falling back to the key so a stamp is never blank. */
internal fun recoveryDayStamp(dayKey: String): String = runCatching {
    LocalDate.parse(dayKey).format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
}.getOrDefault(dayKey)

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

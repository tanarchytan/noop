package com.noop.ui.whoop

import com.noop.analytics.BaselineState
import com.noop.analytics.Baselines
import com.noop.analytics.RestScorer
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric
import com.noop.ui.ChargeDriver
import com.noop.ui.chargeDriverRows
import com.noop.ui.trendDayLabel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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

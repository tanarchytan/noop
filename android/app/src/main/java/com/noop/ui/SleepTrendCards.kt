package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.RustScores
import com.noop.data.DailyMetric
import java.util.Locale

private val TIME_IN_BED_CHART_HEIGHT = 150.dp
private val SLEEP_STRESS_CHART_HEIGHT = 150.dp

/** Nights the weekly cards show, matching the reference's seven columns. */
internal const val SLEEP_TREND_NIGHTS = 7

// The three stress bands wear the same three tokens the Stress screen's ramp does, so one band means
// one colour across the app. The band boundaries themselves live in whoop-rs. Getters, not stored
// values: a top-level val is built once and would hold whichever scheme was active at class load.
private val sleepStressLow: Color get() = Palette.accent
private val sleepStressMedium: Color get() = Palette.statusPositive
private val sleepStressHigh: Color get() = Palette.statusWarning

/** The legend, highest band first, as the reference prints it. */
private val sleepStressLegend: List<Pair<Int, Color>>
    get() = listOf(
        R.string.sleep_stress_high to sleepStressHigh,
        R.string.sleep_stress_medium to sleepStressMedium,
        R.string.sleep_stress_low to sleepStressLow,
    )

/**
 * One night's stress bar: the label it sits under and the whoop-rs band minutes it stacks. Nothing is
 * derived here — [scoredMinutes] only scales the bars against each other.
 */
internal data class SleepStressNight(
    val label: String,
    val lowMinutes: Long,
    val mediumMinutes: Long,
    val highMinutes: Long,
    /** whoop-rs's own high-band share (0-100) for this night; null when nothing scored. */
    val highSharePct: Double?,
) {
    /** Bottom-up stacking order, each band with the colour it is drawn in. */
    val stack: List<Pair<Long, Color>>
        get() = listOf(
            lowMinutes to sleepStressLow,
            mediumMinutes to sleepStressMedium,
            highMinutes to sleepStressHigh,
        )

    /** Total scored minutes — the bar's own height before the shared scale is applied. */
    val scoredMinutes: Long get() = lowMinutes + mediumMinutes + highMinutes
}

/**
 * TIME IN BED — one bar per night from bed to wake, with each night's clock times printed at the ends
 * of its own bar. The bars share the schedule chart's hour axis, so a late night reads as a bar that
 * starts higher.
 */
@Composable
internal fun SleepTimeInBedCard(nights: List<SleepScheduleNight>, slots: List<NightSlot>) {
    SleepTrendShell(title = stringResource(R.string.sleep_time_in_bed), onOpen = null) {
        // Two nights that HAPPENED; a gap slot holds its place on the axis but is not a night.
        if (nights.count { it.bedHour != null } < 2 || nights.size != slots.size) {
            InsetChartPlaceholder(message = stringResource(R.string.sleep_not_enough_nights))
            return@SleepTrendShell
        }
        val barColor = Palette.restColor
        // The consistency chart's own span, off the same nights, so one bar height means one duration
        // on both cards.
        val span = scheduleHourSpan(nights)
        val chartDescription = stringResource(R.string.sleep_time_in_bed_a11y, nights.size)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TIME_IN_BED_CHART_HEIGHT)
                .semantics { contentDescription = chartDescription }
                .drawBehind {
                    val yMin = span.start
                    val range = span.endInclusive - span.start
                    val step = size.width / nights.size
                    val barW = (step * 0.34f).coerceAtLeast(4f)
                    nights.forEachIndexed { i, night ->
                        // A night that never happened draws nothing; its slot stays on the axis.
                        val bed = night.bedHour ?: return@forEachIndexed
                        val wake = night.wakeHour ?: return@forEachIndexed
                        val cx = step * i + step / 2f
                        val bedY = (size.height * ((bed - yMin) / range)).coerceIn(0f, size.height)
                        val wakeY = (size.height * ((wake - yMin) / range)).coerceIn(0f, size.height)
                        val top = minOf(bedY, wakeY)
                        val barH = (maxOf(bedY, wakeY) - top).coerceAtLeast(4f)
                        drawRoundRect(
                            color = barColor.copy(alpha = 0.75f),
                            topLeft = Offset(cx - barW / 2f, top),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(barW / 4f),
                        )
                    }
                },
        ) {}
        Row(modifier = Modifier.fillMaxWidth()) {
            slots.forEachIndexed { i, slot ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Bed over wake, one clock time per line: at a seventh of the width the two on one
                    // line are wider than the column, and seven clipped labels ran together into a
                    // single unreadable string. A gap slot says so rather than borrowing a neighbour.
                    val clocks = slot.span?.let { (onsetTs, wakeTs) ->
                        listOf(clockTimeLabel(onsetTs), clockTimeLabel(wakeTs))
                    } ?: listOf("—", "")
                    clocks.forEach { label ->
                        Text(
                            label,
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        nights[i].label,
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * SLEEP PERFORMANCE — the week's 0-100 as one bar per night, each labelled above its own bar, with
 * the chevron opening the metric's full-history sheet. A night with no reading keeps its slot and
 * draws no bar, so an unworn night never reads as a zero-scoring one.
 */
@Composable
internal fun SleepPerformanceTrendCard(series: List<Double?>, dates: List<String>, onOpenDetail: () -> Unit) {
    val week = series.takeLast(SLEEP_TREND_NIGHTS)
    val read = week.filterNotNull()
    SleepTrendShell(title = stringResource(R.string.sleep_performance), onOpen = onOpenDetail) {
        if (read.isEmpty()) {
            InsetChartPlaceholder(message = stringResource(R.string.sleep_not_enough_nights))
            return@SleepTrendShell
        }
        WeekBarChart(
            values = week,
            dayLabels = dates.takeLast(week.size).map(::trendDayLabel),
            color = Palette.restColor,
            format = { pctValue(it) },
            axisMax = SLEEP_PERFORMANCE_SCALE_MAX,
            height = Metrics.compactChartHeight,
        )
        // The footer describes the nights that HAPPENED, so a gap neither averages in nor wins "Latest".
        ChartCardFooter(
            listOf(
                stringResource(R.string.sleep_footer_latest) to pctValue(read.lastOrNull()),
                stringResource(R.string.sleep_footer_week_avg) to pctValue(RustScores.mean(read)),
                stringResource(R.string.sleep_footer_best) to pctValue(read.maxOrNull()),
            ),
        )
    }
}

/** The score's own ceiling, so one 92% bar is the same height on every week. */
private const val SLEEP_PERFORMANCE_SCALE_MAX = 100.0

/**
 * SLEEP EFFICIENCY — the trailing week as a line, with the chevron opening the metric's own
 * full-history sheet. A night with no reading arrives as a null and keeps its slot on the axis.
 */
@Composable
internal fun SleepEfficiencyTrendCard(series: List<Double?>, dates: List<String>, onOpenDetail: () -> Unit) {
    val week = series.takeLast(SLEEP_TREND_NIGHTS)
    val read = week.filterNotNull()
    SleepTrendShell(title = stringResource(R.string.sleep_efficiency), onOpen = onOpenDetail) {
        if (read.size < 2) {
            InsetChartPlaceholder(message = stringResource(R.string.sleep_not_enough_nights))
            return@SleepTrendShell
        }
        // The same week primitive HOURS VS. NEEDED draws with: a point sits at the centre of the slot
        // its own day label sits under, instead of running plot edge to plot edge past both of them.
        WeekLineChart(
            series = WeekLineSeries(
                stringResource(R.string.sleep_series_efficiency), week, Palette.statusPositive,
            ),
            dayLabels = dates.takeLast(week.size).map(::trendDayLabel),
            format = { pctValue(it) },
            height = Metrics.compactChartHeight,
        )
        // The footer describes the nights that HAPPENED, so a gap neither averages in nor wins "Latest".
        ChartCardFooter(
            listOf(
                stringResource(R.string.sleep_footer_latest) to pctValue(read.lastOrNull()),
                stringResource(R.string.sleep_footer_week_avg) to pctValue(RustScores.mean(read)),
                stringResource(R.string.sleep_footer_best) to pctValue(read.maxOrNull()),
            ),
        )
    }
}

/**
 * HOURS VS. NEEDED — the week's asleep hours against the personal need, every point labelled. Both
 * series arrive already computed; this card pairs them and names them. A missed night arrives as a null
 * and breaks the hours line at its own slot; the need line stays continuous.
 */
@Composable
internal fun SleepHoursVsNeededCard(hours: List<Double?>, needHours: List<Double>, dates: List<String>) {
    val week = hours.takeLast(SLEEP_TREND_NIGHTS)
    val need = needHours.takeLast(SLEEP_TREND_NIGHTS)
    SleepTrendShell(title = stringResource(R.string.sleep_hours_vs_needed), onOpen = null) {
        if (week.count { it != null } < 2) {
            InsetChartPlaceholder(message = stringResource(R.string.sleep_not_enough_nights))
            return@SleepTrendShell
        }
        WeekDualLineChart(
            primary = WeekLineSeries(
                stringResource(R.string.sleep_series_hours), week, Palette.textSecondary,
            ),
            secondary = WeekLineSeries(
                stringResource(R.string.sleep_series_needed), need, Palette.restColor,
            ),
            dayLabels = dates.takeLast(week.size).map(::trendDayLabel),
            format = { hoursText(it) },
            height = Metrics.compactChartHeight,
        )
    }
}

/**
 * RESTORATIVE SLEEP — each night's REM over its deep sleep, with the total above the bar. Both are the
 * night's stored stage minutes; only the minutes-to-hours conversion happens here.
 */
@Composable
internal fun SleepRestorativeCard(rows: List<DailyMetric>, dates: List<String>) {
    val week = dates.takeLast(SLEEP_TREND_NIGHTS)
    val byDay = rows.associateBy { it.day }
    val stacks = week.map { day ->
        val row = byDay[day]
        listOf((row?.remMin ?: 0.0) / MINUTES_PER_HOUR, (row?.deepMin ?: 0.0) / MINUTES_PER_HOUR)
    }
    SleepTrendShell(title = stringResource(R.string.sleep_restorative), onOpen = null) {
        if (stacks.none { stack -> stack.any { it > 0.0 } }) {
            InsetChartPlaceholder(message = stringResource(R.string.sleep_no_staged_nights))
            return@SleepTrendShell
        }
        WeekStackedBarChart(
            segments = listOf(
                WeekStackSegment(stringResource(R.string.sleep_stage_rem), stageColor("rem")),
                WeekStackSegment(stringResource(R.string.sleep_stage_deep), stageColor("deep")),
            ),
            dayValues = stacks,
            dayLabels = week.map(::trendDayLabel),
            format = { hoursText(it) },
            height = Metrics.compactChartHeight,
        )
    }
}

/** Minutes to hours, the only conversion the two weekly hour charts apply. */
private const val MINUTES_PER_HOUR = 60.0

/** "7.4" — an hours figure to one decimal, the label both weekly hour charts print. */
private fun hoursText(hours: Double): String = String.format(Locale.US, "%.1f", hours)

/**
 * SLEEP STRESS — one stacked bar per night, LOW at the base then MEDIUM then HIGH, with that night's
 * HIGH duration printed above it. Every minute is a whoop-rs `sleep_stress` band total; this card only
 * chooses the colours, the words and the stacking order.
 */
@Composable
internal fun SleepStressCard(nights: List<SleepStressNight>) {
    // No chevron: sleep stress has no metric detail sheet, and a chevron that opens nothing is worse
    // than none.
    SleepTrendShell(title = stringResource(R.string.sleep_stress_title), onOpen = null) {
        val scaleMinutes = nights.maxOfOrNull { it.scoredMinutes } ?: 0L
        if (nights.isEmpty() || scaleMinutes <= 0L) {
            InsetChartPlaceholder(message = stringResource(R.string.sleep_stress_none))
            return@SleepTrendShell
        }
        val chartDescription = sleepStressDescription(
            nights,
            stringResource(R.string.sleep_stress_a11y_header, nights.size),
            stringResource(R.string.sleep_stress_a11y_night),
        )
        SleepStressLegend()
        Row(modifier = Modifier.fillMaxWidth()) {
            nights.forEach { night ->
                Text(
                    durationText(night.highMinutes.toDouble()),
                    style = NoopType.footnote,
                    color = if (night.highMinutes > 0L) sleepStressHigh else Palette.textTertiary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SLEEP_STRESS_CHART_HEIGHT)
                .semantics { contentDescription = chartDescription }
                .drawBehind {
                    val step = size.width / nights.size
                    val barW = (step * 0.34f).coerceAtLeast(4f)
                    nights.forEachIndexed { i, night ->
                        val cx = step * i + step / 2f
                        var base = size.height
                        night.stack.forEach { (minutes, color) ->
                            val h = size.height * (minutes.toFloat() / scaleMinutes.toFloat())
                            if (h > 0f) {
                                drawRect(
                                    color = color,
                                    topLeft = Offset(cx - barW / 2f, base - h),
                                    size = Size(barW, h),
                                )
                            }
                            base -= h
                        }
                    }
                },
        ) {}
        Row(modifier = Modifier.fillMaxWidth()) {
            nights.forEach { night ->
                Text(
                    night.label,
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The three band words over their bar colours, in the reference's order (highest first). */
@Composable
private fun SleepStressLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        sleepStressLegend.forEach { (word, color) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
                modifier = Modifier.padding(horizontal = Metrics.space10),
            ) {
                Box(modifier = Modifier.size(Metrics.legendSwatch).background(color))
                Text(stringResource(word), style = NoopType.footnote, color = Palette.textSecondary)
            }
        }
    }
}

/** The whole card as one sentence, so the chart is not silent to a screen reader. [header] and
 *  [nightFormat] arrive resolved, so this stays callable without a Context. */
internal fun sleepStressDescription(
    nights: List<SleepStressNight>,
    header: String,
    nightFormat: String,
): String = header + " " +
    nights.joinToString("; ") { nightFormat.format(it.label, durationText(it.highMinutes.toDouble())) }

/** The weekly cards' common frame: an uppercase title, an optional chevron, then the body. */
@Composable
private fun SleepTrendShell(title: String, onOpen: (() -> Unit)?, body: @Composable () -> Unit) {
    val openLabel = stringResource(R.string.sleep_open_full_history)
    NoopCard(padding = Metrics.cardPadding, tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
            Row(
                modifier = if (onOpen == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = openLabel, onClick = onOpen)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = NoopType.overline, color = Palette.textPrimary)
                Spacer(Modifier.weight(1f))
                if (onOpen != null) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = openLabel,
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(Metrics.iconSmall),
                    )
                }
            }
            body()
        }
    }
}

/** "Sat 11" from a YYYY-MM-DD day string, falling back to the string itself. */
internal fun trendDayLabel(day: String): String = runCatching {
    java.time.LocalDate.parse(day).format(java.time.format.DateTimeFormatter.ofPattern("EEE d", Locale.US))
}.getOrDefault(day)

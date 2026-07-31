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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

private val TIME_IN_BED_CHART_HEIGHT = 150.dp
private val SLEEP_STRESS_CHART_HEIGHT = 150.dp

/** Nights the weekly cards show, matching the reference's seven columns. */
internal const val SLEEP_TREND_NIGHTS = 7

// The three stress bands wear the same three tokens the Stress screen's ramp does, so one band means
// one colour across the app. The band boundaries themselves live in whoop-rs.
private val SLEEP_STRESS_LOW: Color = Palette.accent
private val SLEEP_STRESS_MEDIUM: Color = Palette.statusPositive
private val SLEEP_STRESS_HIGH: Color = Palette.statusWarning

/** The legend, highest band first, as the reference prints it. */
private val SLEEP_STRESS_LEGEND: List<Pair<String, Color>> = listOf(
    "HIGH" to SLEEP_STRESS_HIGH,
    "MEDIUM" to SLEEP_STRESS_MEDIUM,
    "LOW" to SLEEP_STRESS_LOW,
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
            lowMinutes to SLEEP_STRESS_LOW,
            mediumMinutes to SLEEP_STRESS_MEDIUM,
            highMinutes to SLEEP_STRESS_HIGH,
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
internal fun SleepTimeInBedCard(nights: List<SleepScheduleNight>, spans: List<Pair<Long, Long>>) {
    SleepTrendShell(title = "TIME IN BED", onOpen = null) {
        if (nights.size < 2 || nights.size != spans.size) {
            InsetChartPlaceholder(message = "Not enough nights yet.")
            return@SleepTrendShell
        }
        val barColor = Palette.restColor
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TIME_IN_BED_CHART_HEIGHT)
                .semantics { contentDescription = "Time in bed over the last ${nights.size} nights" }
                .drawBehind {
                    val range = SCHEDULE_Y_MAX - SCHEDULE_Y_MIN
                    val step = size.width / nights.size
                    val barW = (step * 0.34f).coerceAtLeast(4f)
                    nights.forEachIndexed { i, night ->
                        val cx = step * i + step / 2f
                        val bedY = (size.height * ((night.bedHour - SCHEDULE_Y_MIN) / range)).coerceIn(0f, size.height)
                        val wakeY = (size.height * ((night.wakeHour - SCHEDULE_Y_MIN) / range)).coerceIn(0f, size.height)
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
            spans.forEachIndexed { i, (onsetTs, wakeTs) ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "${clockTimeLabel(onsetTs)}-${clockTimeLabel(wakeTs)}",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
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
 * SLEEP EFFICIENCY — the trailing week as a line, with the chevron opening the metric's own
 * full-history sheet.
 */
@Composable
internal fun SleepEfficiencyTrendCard(series: List<Double>, dates: List<String>, onOpenDetail: () -> Unit) {
    val week = series.takeLast(SLEEP_TREND_NIGHTS)
    SleepTrendShell(title = "SLEEP EFFICIENCY", onOpen = onOpenDetail) {
        if (week.size < 2) {
            InsetChartPlaceholder(message = "Not enough nights yet.")
            return@SleepTrendShell
        }
        LineChart(
            values = week,
            modifier = Modifier
                .fillMaxWidth()
                .height(Metrics.compactChartHeight)
                .semantics { contentDescription = "Sleep efficiency over the last ${week.size} nights" },
            color = Palette.statusPositive,
            fill = true,
            selectionEnabled = true,
        )
        SleepTrendDayLabels(dates.takeLast(week.size))
        ChartCardFooter(
            listOf(
                "Latest" to pctValue(week.lastOrNull()),
                "Week avg" to pctValue(week.average()),
                "Best" to pctValue(week.maxOrNull()),
            ),
        )
    }
}

/**
 * SLEEP STRESS — one stacked bar per night, LOW at the base then MEDIUM then HIGH, with that night's
 * HIGH duration printed above it. Every minute is a whoop-rs `sleep_stress` band total; this card only
 * chooses the colours, the words and the stacking order.
 */
@Composable
internal fun SleepStressCard(nights: List<SleepStressNight>) {
    // No chevron: sleep stress has no metric detail sheet, and a chevron that opens nothing is worse
    // than none.
    SleepTrendShell(title = "SLEEP STRESS", onOpen = null) {
        val scaleMinutes = nights.maxOfOrNull { it.scoredMinutes } ?: 0L
        if (nights.isEmpty() || scaleMinutes <= 0L) {
            InsetChartPlaceholder(message = "No scored sleep stress yet.")
            return@SleepTrendShell
        }
        SleepStressLegend()
        Row(modifier = Modifier.fillMaxWidth()) {
            nights.forEach { night ->
                Text(
                    durationText(night.highMinutes.toDouble()),
                    style = NoopType.footnote,
                    color = if (night.highMinutes > 0L) SLEEP_STRESS_HIGH else Palette.textTertiary,
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
                .semantics { contentDescription = sleepStressDescription(nights) }
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
        SLEEP_STRESS_LEGEND.forEach { (word, color) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
                modifier = Modifier.padding(horizontal = Metrics.space10),
            ) {
                Box(modifier = Modifier.size(Metrics.legendSwatch).background(color))
                Text(word, style = NoopType.footnote, color = Palette.textSecondary)
            }
        }
    }
}

/** The whole card as one sentence, so the chart is not silent to a screen reader. */
internal fun sleepStressDescription(nights: List<SleepStressNight>): String =
    "Sleep stress over the last ${nights.size} nights. " +
        nights.joinToString("; ") { "${it.label} ${durationText(it.highMinutes.toDouble())} high" }

/** The weekly cards' common frame: an uppercase title, an optional chevron, then the body. */
@Composable
private fun SleepTrendShell(title: String, onOpen: (() -> Unit)?, body: @Composable () -> Unit) {
    NoopCard(padding = Metrics.cardPadding, tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
            Row(
                modifier = if (onOpen == null) {
                    Modifier.fillMaxWidth()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Open the full history", onClick = onOpen)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = NoopType.overline, color = Palette.textPrimary)
                Spacer(Modifier.weight(1f))
                if (onOpen != null) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = "Open the full history",
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            body()
        }
    }
}

/** "Sat 11" under each column, from the trend's own day strings. */
@Composable
private fun SleepTrendDayLabels(days: List<String>) {
    if (days.isEmpty()) return
    Row(modifier = Modifier.fillMaxWidth().padding(top = Metrics.space2)) {
        days.forEach { day ->
            Text(
                trendDayLabel(day),
                style = NoopType.footnote,
                color = Palette.textTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "Sat 11" from a YYYY-MM-DD day string, falling back to the string itself. */
internal fun trendDayLabel(day: String): String = runCatching {
    java.time.LocalDate.parse(day).format(java.time.format.DateTimeFormatter.ofPattern("EEE d", Locale.US))
}.getOrDefault(day)

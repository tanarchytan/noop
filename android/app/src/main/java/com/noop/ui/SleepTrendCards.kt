package com.noop.ui

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

private val TIME_IN_BED_CHART_HEIGHT = 150.dp

/** Nights the weekly cards show, matching the reference's seven columns. */
internal const val SLEEP_TREND_NIGHTS = 7

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

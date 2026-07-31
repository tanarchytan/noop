package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The chart's vertical span: 20:00 the evening before through 18:00 the next day. The time-in-bed
 *  card shares it so a bar means the same thing on both. */
internal const val SCHEDULE_Y_MIN = -4f
internal const val SCHEDULE_Y_MAX = 18f
private val SCHEDULE_HOUR_LINES = listOf(-4f, 0f, 4f, 8f, 12f, 16f)
private val SCHEDULE_CHART_HEIGHT = 180.dp
private const val SCHEDULE_Y_GUTTER_PX = 52f
private const val SECONDS_PER_HOUR = 3600f

/** One night's bed and wake folded onto the chart's hour axis, with its weekday label. */
internal data class SleepScheduleNight(val label: String, val bedHour: Float, val wakeHour: Float)

/**
 * SLEEP CONSISTENCY — one bar per night from bed to wake against a time-of-day axis, with the
 * habitual sleep window drawn behind them as the dashed band the reference calls Optimal
 * Bed/Waketime.
 *
 * The band is the whoop-rs habitual midsleep with the whoop-rs sleep need laid either side of it, so
 * it positions two learned values rather than reading out a third. The headline percentage is the
 * consistency score the model already carries.
 */
@Composable
internal fun SleepScheduleCard(
    score: Double?,
    typicalScore: Double?,
    nights: List<SleepScheduleNight>,
    habitualMidsleepSec: Long?,
    needMin: Double?,
) {
    if (nights.size < 3) return
    val range = SCHEDULE_Y_MAX - SCHEDULE_Y_MIN
    val band = remember(habitualMidsleepSec, needMin) { optimalSleepBand(habitualMidsleepSec, needMin) }

    val barColor = Palette.restColor
    val bandColor = Palette.metricPurple
    val gridColor = Palette.hairline
    val labelArgb = Palette.textTertiary.toArgb()

    NoopCard(padding = Metrics.cardPadding, tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                    Text("SLEEP CONSISTENCY", style = NoopType.overline, color = Palette.textTertiary)
                    Text(
                        pctValue(score),
                        style = NoopType.tileValueLarge,
                        color = Palette.restColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        typicalScore?.let { "${pctValue(it)} typical" } ?: "no typical yet",
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                }
                if (band != null) {
                    Text(
                        "- - -  Habitual window",
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                        modifier = Modifier.padding(top = Metrics.space6),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SCHEDULE_CHART_HEIGHT)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(Metrics.cornerSm))
                    .semantics { contentDescription = "Nightly bed and wake times over ${nights.size} nights" }
                    .drawBehind {
                        val chartW = size.width - SCHEDULE_Y_GUTTER_PX
                        val chartH = size.height
                        fun y(hour: Float) = (chartH * ((hour - SCHEDULE_Y_MIN) / range)).coerceIn(0f, chartH)

                        val cornerPx = Metrics.cornerSm.toPx()
                        val paint = android.graphics.Paint().apply {
                            color = labelArgb
                            textSize = 20f
                            isAntiAlias = true
                        }
                        val fm = paint.fontMetrics
                        SCHEDULE_HOUR_LINES.forEach { h ->
                            val ly = y(h)
                            drawLine(gridColor, Offset(SCHEDULE_Y_GUTTER_PX, ly), Offset(size.width, ly), strokeWidth = 1f)
                            val baseline = (ly - (fm.ascent + fm.descent) / 2f)
                                .coerceIn(cornerPx - fm.ascent, chartH - fm.descent)
                            drawContext.canvas.nativeCanvas.drawText(scheduleHourLabel(h), 4f, baseline, paint)
                        }

                        // The habitual window behind the bars: a soft fill between its two dashed edges.
                        if (band != null) {
                            val top = y(band.first)
                            val bottom = y(band.second)
                            drawRect(
                                color = bandColor.copy(alpha = 0.14f),
                                topLeft = Offset(SCHEDULE_Y_GUTTER_PX, minOf(top, bottom)),
                                size = Size(chartW, kotlin.math.abs(bottom - top)),
                            )
                            listOf(top, bottom).forEach { edgeY ->
                                var x = SCHEDULE_Y_GUTTER_PX
                                while (x < size.width) {
                                    drawLine(
                                        bandColor.copy(alpha = 0.8f),
                                        Offset(x, edgeY),
                                        Offset(minOf(x + 12f, size.width), edgeY),
                                        strokeWidth = 2f,
                                    )
                                    x += 20f
                                }
                            }
                        }

                        val step = chartW / nights.size
                        val barW = (step * 0.6f).coerceAtLeast(4f)
                        nights.forEachIndexed { i, night ->
                            val cx = SCHEDULE_Y_GUTTER_PX + step * i + step / 2f
                            val bedY = y(night.bedHour)
                            val wakeY = y(night.wakeHour)
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
            Row(modifier = Modifier.fillMaxWidth().padding(start = 26.dp)) {
                nights.forEach { night ->
                    Text(
                        night.label,
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
    }
}

/** "20:00" for a chart hour, which may be negative for the evening before. */
internal fun scheduleHourLabel(hour: Float): String {
    val norm = ((hour % 24f) + 24f) % 24f
    return String.format(Locale.US, "%02d:00", norm.toInt())
}

/**
 * The habitual sleep window as (bedHour, wakeHour) on the chart's axis: the learned midsleep with the
 * personal need laid half either side. Null when either input is missing, which draws no band.
 */
internal fun optimalSleepBand(habitualMidsleepSec: Long?, needMin: Double?): Pair<Float, Float>? {
    if (habitualMidsleepSec == null || needMin == null || needMin <= 0.0) return null
    val midHour = habitualMidsleepSec / SECONDS_PER_HOUR
    // Fold a post-midnight midsleep so it sits between the axis' evening and morning ends.
    val folded = if (midHour > 12f) midHour - 24f else midHour
    val halfNight = (needMin / 120.0).toFloat()
    return (folded - halfNight) to (folded + halfNight)
}

/**
 * Fold each (onset, wake) span onto the chart's hour axis, newest last. An evening bedtime folds to a
 * negative hour so it sorts above the following morning's wake.
 */
internal fun sleepScheduleNights(spans: List<Pair<Long, Long>>): List<SleepScheduleNight> {
    val dayFmt = SimpleDateFormat("EEE", Locale.US)
    return spans.map { (onsetTs, wakeTs) ->
        val bed = Calendar.getInstance().apply { timeInMillis = onsetTs * 1000L }
        val wake = Calendar.getInstance().apply { timeInMillis = wakeTs * 1000L }
        val bedHour = bed.get(Calendar.HOUR_OF_DAY) + bed.get(Calendar.MINUTE) / 60f
        val wakeHour = wake.get(Calendar.HOUR_OF_DAY) + wake.get(Calendar.MINUTE) / 60f
        SleepScheduleNight(
            label = dayFmt.format(Date(wakeTs * 1000L)),
            bedHour = if (bedHour > 12f) bedHour - 24f else bedHour,
            wakeHour = wakeHour,
        )
    }
}

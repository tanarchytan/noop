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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.ceil
import kotlin.math.floor

/** The narrowest window the chart will draw, in hours, so three near-identical nights still get a
 *  plot with room around them rather than three bars filling it. */
private const val SCHEDULE_MIN_HOURS = 8f

/** Hours between gridlines, and the hour the labelled lines are aligned to. */
private const val SCHEDULE_HOUR_STEP = 4f

/**
 * The chart's vertical span in hours for the nights it draws: half an hour outside the earliest bed and
 * the latest wake, then snapped out to the gridline step at both ends so every bar ends between two
 * labelled lines instead of past the last one. The time-in-bed card derives it from the same nights, so
 * a bar means the same thing on both.
 */
internal fun scheduleHourSpan(nights: List<SleepScheduleNight>): ClosedFloatingPointRange<Float> {
    // A gap slot has no hours to bound the axis with, so it neither widens nor narrows it.
    val hours = nights.mapNotNull { n -> n.bedHour?.let { b -> n.wakeHour?.let { w -> b to w } } }
    val lo = hours.minOfOrNull { minOf(it.first, it.second) } ?: 0f
    val hi = hours.maxOfOrNull { maxOf(it.first, it.second) } ?: SCHEDULE_MIN_HOURS
    val pad = SCHEDULE_HOUR_STEP / 8f
    val min = floor((lo - pad) / SCHEDULE_HOUR_STEP) * SCHEDULE_HOUR_STEP
    val max = maxOf(ceil((hi + pad) / SCHEDULE_HOUR_STEP) * SCHEDULE_HOUR_STEP, min + SCHEDULE_MIN_HOURS)
    return min..max
}

/** The labelled gridlines inside [span], every [SCHEDULE_HOUR_STEP] hours on the step's own multiples. */
private fun scheduleHourLines(span: ClosedFloatingPointRange<Float>): List<Float> {
    val first = ceil(span.start / SCHEDULE_HOUR_STEP) * SCHEDULE_HOUR_STEP
    return generateSequence(first) { it + SCHEDULE_HOUR_STEP }
        .takeWhile { it <= span.endInclusive }
        .toList()
}
private val SCHEDULE_CHART_HEIGHT = 180.dp
private const val SCHEDULE_Y_GUTTER_PX = 52f
private const val SECONDS_PER_HOUR = 3600f

/** One night's bed and wake folded onto the chart's hour axis, with its weekday label and the local
 *  calendar day of its midpoint — the key the habitual series is read by. A day with no night keeps its
 *  slot with null hours, so the axis stays the calendar. */
internal data class SleepScheduleNight(
    val label: String,
    val bedHour: Float?,
    val wakeHour: Float?,
    val dayKey: String,
)

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
    habitualByDay: Map<String, Long?>,
    needMin: Double?,
) {
    // Three nights that HAPPENED; gap slots hold the axis open but are not nights.
    if (nights.count { it.bedHour != null } < 3) return
    val span = scheduleHourSpan(nights)
    val yMin = span.start
    val range = span.endInclusive - span.start
    val hourLines = scheduleHourLines(span)
    val bands = remember(habitualByDay, nights, needMin) { optimalSleepBand(habitualByDay, nights, needMin) }

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
                if (bands.any { it != null }) {
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
                        val cornerPx = Metrics.cornerSm.toPx()
                        // The plot is inset by the clip's own corner radius, so the first and last
                        // gridlines and their labels sit clear of the rounded corners that cut them.
                        fun y(hour: Float) =
                            (cornerPx + (chartH - cornerPx * 2f) * ((hour - yMin) / range)).coerceIn(0f, chartH)

                        val paint = android.graphics.Paint().apply {
                            color = labelArgb
                            textSize = 20f
                            isAntiAlias = true
                        }
                        val fm = paint.fontMetrics
                        // Baseline centred on its gridline, then held inside the plot. The floor is also
                        // the ceiling's lower bound, so a squeezed chart clamps instead of throwing.
                        val labelTop = cornerPx - fm.ascent
                        val labelBottom = maxOf(chartH - fm.descent, labelTop)
                        hourLines.forEach { h ->
                            val ly = y(h)
                            drawLine(gridColor, Offset(SCHEDULE_Y_GUTTER_PX, ly), Offset(size.width, ly), strokeWidth = 1f)
                            val baseline = (ly - (fm.ascent + fm.descent) / 2f)
                                .coerceIn(labelTop, labelBottom)
                            drawContext.canvas.nativeCanvas.drawText(scheduleHourLabel(h), 4f, baseline, paint)
                        }

                        val step = chartW / nights.size
                        // The habitual window behind the bars: a soft fill between its two dashed edges,
                        // bending with the per-night series instead of running flat.
                        drawHabitualBand(bands, bandColor, step, size.width) { h -> y(h) }

                        val barW = (step * 0.6f).coerceAtLeast(4f)
                        nights.forEachIndexed { i, night ->
                            // A night that never happened draws nothing; its slot stays on the axis.
                            val bed = night.bedHour ?: return@forEachIndexed
                            val wake = night.wakeHour ?: return@forEachIndexed
                            val cx = SCHEDULE_Y_GUTTER_PX + step * i + step / 2f
                            val bedY = y(bed)
                            val wakeY = y(wake)
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

/**
 * Draw the habitual window behind the bars: a soft fill between two dashed edges that bend from night to
 * night with [bands]. Each run of consecutive non-null nights is one ribbon, stretched to the plot edge
 * at either end of the chart; a null night breaks the ribbon rather than being bridged across.
 */
private fun DrawScope.drawHabitualBand(
    bands: List<Pair<Float, Float>?>,
    color: Color,
    step: Float,
    rightEdge: Float,
    y: (Float) -> Float,
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
    val centerX = { i: Int -> SCHEDULE_Y_GUTTER_PX + step * i + step / 2f }
    var i = 0
    while (i < bands.size) {
        if (bands[i] == null) {
            i++
            continue
        }
        var last = i
        while (last + 1 < bands.size && bands[last + 1] != null) last++
        // One point per night, plus a flat stub out to each end of the run: the plot edge where the run
        // reaches the chart's first or last night, otherwise the column edge the gap starts at.
        val xs = mutableListOf(if (i == 0) SCHEDULE_Y_GUTTER_PX else centerX(i) - step / 2f)
        val tops = mutableListOf<Float>()
        val bottoms = mutableListOf<Float>()
        for (k in i..last) {
            val b = bands[k] ?: continue
            xs.add(centerX(k))
            tops.add(y(b.first))
            bottoms.add(y(b.second))
        }
        xs.add(if (last == bands.lastIndex) rightEdge else centerX(last) + step / 2f)
        tops.add(0, tops.first())
        tops.add(tops.last())
        bottoms.add(0, bottoms.first())
        bottoms.add(bottoms.last())
        val edge = { ys: List<Float> ->
            Path().apply {
                xs.forEachIndexed { k, x -> if (k == 0) moveTo(x, ys[k]) else lineTo(x, ys[k]) }
            }
        }
        val fill = Path().apply {
            xs.forEachIndexed { k, x -> if (k == 0) moveTo(x, tops[k]) else lineTo(x, tops[k]) }
            for (k in xs.indices.reversed()) lineTo(xs[k], bottoms[k])
            close()
        }
        drawPath(fill, color.copy(alpha = 0.14f))
        drawPath(edge(tops), color.copy(alpha = 0.8f), style = Stroke(width = 2f, pathEffect = dash))
        drawPath(edge(bottoms), color.copy(alpha = 0.8f), style = Stroke(width = 2f, pathEffect = dash))
        i = last + 1
    }
}

/** "20:00" for a chart hour, which may be negative for the evening before. */
internal fun scheduleHourLabel(hour: Float): String {
    val norm = ((hour % 24f) + 24f) % 24f
    return String.format(Locale.US, "%02d:00", norm.toInt())
}

/**
 * The habitual sleep window PER NIGHT as (bedHour, wakeHour) on the chart's axis: that night's learned
 * midsleep with the personal need laid half either side. Aligned one-to-one with [nights]; an entry is
 * null where [habitualByDay] carries no value for that night, which leaves a gap rather than guessing.
 */
internal fun optimalSleepBand(
    habitualByDay: Map<String, Long?>,
    nights: List<SleepScheduleNight>,
    needMin: Double?,
): List<Pair<Float, Float>?> {
    if (needMin == null || needMin <= 0.0) return List(nights.size) { null }
    val halfNight = (needMin / 120.0).toFloat()
    return nights.map { night ->
        val midHour = (habitualByDay[night.dayKey] ?: return@map null) / SECONDS_PER_HOUR
        // Fold a post-midnight midsleep so it sits between the axis' evening and morning ends.
        val folded = if (midHour > 12f) midHour - 24f else midHour
        (folded - halfNight) to (folded + halfNight)
    }
}

/**
 * Fold each (onset, wake) span onto the chart's hour axis, newest last. An evening bedtime folds to a
 * negative hour so it sorts above the following morning's wake. [SleepScheduleNight.dayKey] is the local
 * day of the span midpoint, the same key the habitual learner groups a night under.
 */
internal fun sleepScheduleNights(slots: List<NightSlot>): List<SleepScheduleNight> {
    // Dated, not the weekday alone: a week with a missing night printed two Mondays and no Sunday.
    val dayFmt = SimpleDateFormat("EEE d", Locale.US)
    val keyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    return slots.map { slot ->
        val span = slot.span
            // A gap slot still owns its calendar day, so it keeps its own label and place on the axis.
            ?: return@map SleepScheduleNight(
                label = runCatching { dayFmt.format(keyFmt.parse(slot.day)!!) }.getOrDefault(slot.day),
                bedHour = null, wakeHour = null, dayKey = slot.day,
            )
        val (onsetTs, wakeTs) = span
        val bed = Calendar.getInstance().apply { timeInMillis = onsetTs * 1000L }
        val wake = Calendar.getInstance().apply { timeInMillis = wakeTs * 1000L }
        val bedHour = bed.get(Calendar.HOUR_OF_DAY) + bed.get(Calendar.MINUTE) / 60f
        val wakeHour = wake.get(Calendar.HOUR_OF_DAY) + wake.get(Calendar.MINUTE) / 60f
        SleepScheduleNight(
            label = dayFmt.format(Date(wakeTs * 1000L)),
            bedHour = if (bedHour > 12f) bedHour - 24f else bedHour,
            wakeHour = wakeHour,
            dayKey = localDayString(onsetTs + (wakeTs - onsetTs) / 2),
        )
    }
}

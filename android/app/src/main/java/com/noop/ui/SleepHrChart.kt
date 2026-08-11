package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.noop.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// MARK: - Night HR chart tokens

/** Points the night's HR curve is downsampled to; the bucket seconds are chosen to land under it. */
internal const val HR_CHART_TARGET_POINTS = 720

/** Lead-in / run-out around the sleep window, so the dashed onset and wake bounds sit inside the plot. */
private const val HR_CHART_PAD_FRAC = 0.08
private const val HR_CHART_PAD_MIN_SEC = 600L
private const val HR_CHART_PAD_MAX_SEC = 2_700L

/** Gap, in multiples of the point spacing, that breaks the trace instead of drawing a line across it. */
private const val HR_TRACE_GAP_STEPS = 3.0

/** bpm the axis rounds out to, and the tick step it labels. */
private const val HR_AXIS_ROUND_BPM = 10
private const val HR_AXIS_TICK_BPM = 20

/**
 * The chart's time window: the sleep span plus a lead-in and run-out, so the dashed onset / wake bounds
 * are drawn inside the plot with the trace running past them. Returns (start, end) unix seconds.
 */
internal fun hrChartWindow(onsetTs: Long, wakeTs: Long): Pair<Long, Long> {
    val span = (wakeTs - onsetTs).coerceAtLeast(1L)
    val pad = (span * HR_CHART_PAD_FRAC).toLong().coerceIn(HR_CHART_PAD_MIN_SEC, HR_CHART_PAD_MAX_SEC)
    return (onsetTs - pad) to (wakeTs + pad)
}

/** Bucket width for the night's HR read, so a whole night arrives as at most [HR_CHART_TARGET_POINTS]. */
internal fun hrChartBucketSec(windowSpanSec: Long): Long =
    timelineBucketSeconds(windowSpanSec, HR_CHART_TARGET_POINTS)

/** Where [ts] falls across the chart window, 0..1. */
internal fun windowFraction(ts: Long, windowStart: Long, windowSpanSec: Double): Float {
    if (!windowSpanSec.isFinite() || windowSpanSec <= 0.0) return 0f
    return ((ts - windowStart) / windowSpanSec).toFloat().coerceIn(0f, 1f)
}

/**
 * The points the chart can draw: finite, positive bpm inside the window, in time order. Fewer than two
 * leaves nothing to stroke, which is the night-with-no-HR state the caller renders as a note.
 */
internal fun hrChartSeries(
    points: List<TimelinePoint>,
    windowStart: Long,
    windowEnd: Long,
): List<TimelinePoint> = points
    .filter { it.ts in windowStart..windowEnd && it.value.isFinite() && it.value > 0.0 }
    .sortedBy { it.ts }

/**
 * bpm axis bounds for [points], rounded out to [HR_AXIS_ROUND_BPM] and widened to at least one tick
 * step so a flat night still has a readable scale. Empty input yields a neutral resting band.
 */
internal fun hrAxisBounds(points: List<TimelinePoint>): Pair<Int, Int> {
    val values = points.map { it.value }.filter { it.isFinite() }
    if (values.isEmpty()) return 50 to 70
    val lo = floor(values.min() / HR_AXIS_ROUND_BPM) * HR_AXIS_ROUND_BPM
    val hi = ceil(values.max() / HR_AXIS_ROUND_BPM) * HR_AXIS_ROUND_BPM
    val low = lo.roundToInt()
    val high = hi.roundToInt()
    return if (high - low >= HR_AXIS_TICK_BPM) low to high else low to (low + HR_AXIS_TICK_BPM)
}

/**
 * Labelled bpm gridlines inside [lo]..[hi]: every [HR_AXIS_TICK_BPM] on the step's own multiples,
 * falling back to the two bounds when that lands fewer than two lines.
 */
internal fun hrAxisTicks(lo: Int, hi: Int): List<Int> {
    if (hi <= lo) return listOf(lo)
    var first = lo
    if (first % HR_AXIS_TICK_BPM != 0) {
        first = ((floor(lo.toDouble() / HR_AXIS_TICK_BPM) + 1).toInt()) * HR_AXIS_TICK_BPM
    }
    val out = ArrayList<Int>()
    var t = first
    while (t <= hi) { out.add(t); t += HR_AXIS_TICK_BPM }
    return if (out.size >= 2) out else listOf(lo, hi)
}

/**
 * Split [points] into runs the trace can be drawn as one stroke: a gap wider than
 * [HR_TRACE_GAP_STEPS] × the tightest spacing starts a new run, so an unworn stretch is a hole.
 */
internal fun hrTraceRuns(points: List<TimelinePoint>): List<List<TimelinePoint>> {
    if (points.size < 2) return if (points.isEmpty()) emptyList() else listOf(points)
    val deltas = points.zipWithNext { a, b -> b.ts - a.ts }.filter { it > 0L }
    val step = deltas.minOrNull() ?: return listOf(points)
    val maxGap = step * HR_TRACE_GAP_STEPS
    val out = ArrayList<List<TimelinePoint>>()
    var run = ArrayList<TimelinePoint>()
    points.forEach { p ->
        val last = run.lastOrNull()
        if (last != null && (p.ts - last.ts) > maxGap) {
            out.add(run)
            run = ArrayList()
        }
        run.add(p)
    }
    out.add(run)
    return out.filter { it.isNotEmpty() }
}

/**
 * The (startFraction, widthFraction) bands of [stage]'s runs across the CHART window. [intervals] are
 * seconds from [onsetTs]; the chart window is wider than the sleep span, so each run is re-anchored.
 */
internal fun stageBandsInWindow(
    intervals: List<StageInterval>,
    onsetTs: Long,
    windowStart: Long,
    windowSpanSec: Double,
    stage: String,
): List<Pair<Float, Float>> {
    if (!windowSpanSec.isFinite() || windowSpanSec <= 0.0) return emptyList()
    val key = canonicalStage(stage)
    return intervals
        .filter { canonicalStage(it.stage) == key && it.durationSec > 0.0 }
        .map { iv ->
            val from = windowFraction(onsetTs + iv.startSec.toLong(), windowStart, windowSpanSec)
            val to = windowFraction(onsetTs + iv.endSec.toLong(), windowStart, windowSpanSec)
            from to (to - from).coerceAtLeast(0f)
        }
        .filter { (_, width) -> width > 0f }
}

/** Share of the plot height the movement series may use, measured up from the baseline. Keeping it
 *  to the lower band stops a self-normalised curve reading as if it had the bpm axis. */
private const val MOTION_BAND = 0.34f

/** Motion epochs are 30 s wide, counted from onset. */
private const val EPOCH_SEC = 30L

/**
 * The night's heart rate and movement across the sleep window, on one time axis: HR as a rose trace on
 * the bpm axis, movement as a cyan curve self-normalised into the bottom [MOTION_BAND] (it has no bpm),
 * dashed onset / wake bounds with their clock times, and — when a stage row is selected — that stage's
 * runs as bands with the HR trace recoloured inside them. Null bounds or fewer than two HR points
 * render an honest note; movement is simply absent when the night carries too few epochs.
 */
@Composable
internal fun SleepHrChart(
    points: List<TimelinePoint>,
    onsetTs: Long?,
    wakeTs: Long?,
    realSegments: List<Pair<String, Float>>,
    selectedStage: String?,
    motionEpochs: List<Double> = emptyList(),
) {
    if (onsetTs == null || wakeTs == null || wakeTs <= onsetTs) return
    val (windowStart, windowEnd) = remember(onsetTs, wakeTs) { hrChartWindow(onsetTs, wakeTs) }
    val windowSpanSec = (windowEnd - windowStart).toDouble()
    val inWindow = remember(points, windowStart, windowEnd) {
        hrChartSeries(points, windowStart, windowEnd)
    }
    if (inWindow.size < 2) {
        Text(
            stringResource(R.string.sleep_hr_none),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
        return
    }

    val (axisLo, axisHi) = remember(inWindow) { hrAxisBounds(inWindow) }
    val ticks = remember(axisLo, axisHi) { hrAxisTicks(axisLo, axisHi) }
    val runs = remember(inWindow) { hrTraceRuns(inWindow) }
    val spanSec = remember(realSegments, onsetTs, wakeTs) { nightSpanSec(realSegments, onsetTs, wakeTs) }
    val intervals = remember(realSegments, spanSec) { nightStageIntervals(realSegments, spanSec) }
    val bands = remember(intervals, selectedStage, windowStart, windowSpanSec) {
        selectedStage?.let { stageBandsInWindow(intervals, onsetTs, windowStart, windowSpanSec, it) }
            ?: emptyList()
    }
    val bandTint = selectedStage?.let { stageColor(it) } ?: Palette.restColor

    val onsetFrac = windowFraction(onsetTs, windowStart, windowSpanSec)
    val wakeFrac = windowFraction(wakeTs, windowStart, windowSpanSec)
    // Two series on one time axis, so they must not read as one: heart rate rose, movement cyan.
    val traceColor = Palette.metricRose
    val motionColor = Palette.metricCyan
    val gridColor = Palette.hairline
    val boundColor = Palette.textTertiary
    val labelArgb = Palette.textTertiary.toArgb()
    // The selected row's own name, so the read-out says which stage is banded.
    val stageNameRes = when (selectedStage) {
        "Light" -> R.string.sleep_stage_light
        "Deep" -> R.string.sleep_stage_deep
        "REM" -> R.string.sleep_stage_rem
        "Awake" -> R.string.sleep_stage_awake
        else -> null
    }
    val readOut = if (stageNameRes == null) {
        stringResource(
            R.string.sleep_hr_a11y,
            clockTimeLabel(onsetTs), clockTimeLabel(wakeTs), axisLo, axisHi,
        )
    } else {
        stringResource(
            R.string.sleep_hr_a11y_stage,
            clockTimeLabel(onsetTs), clockTimeLabel(wakeTs), axisLo, axisHi,
            stringResource(stageNameRes),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(Metrics.hrChartHeight)
                .semantics { contentDescription = readOut },
        ) {
            val gutter = Metrics.hrChartGutter.toPx()
            val plotW = size.width - gutter
            val h = size.height
            if (plotW <= 0f || h <= 0f) return@Canvas
            fun x(frac: Float) = gutter + plotW * frac
            fun y(bpm: Double): Float {
                val span = (axisHi - axisLo).toDouble().coerceAtLeast(1.0)
                return (h - ((bpm - axisLo) / span).toFloat() * h).coerceIn(0f, h)
            }

            // Selected stage's runs, behind everything, so the trace and the gridlines stay readable.
            bands.forEach { (start, width) ->
                drawRect(
                    color = bandTint.copy(alpha = 0.30f),
                    topLeft = Offset(x(start), 0f),
                    size = Size(plotW * width, h),
                )
            }

            val paint = android.graphics.Paint().apply {
                color = labelArgb
                textSize = 20f
                isAntiAlias = true
            }
            val fm = paint.fontMetrics
            // Baseline centred on its gridline, then held inside the plot. The floor is also the ceiling's
            // lower bound, so a squeezed chart clamps instead of throwing out of a draw scope.
            val labelTop = -fm.ascent
            val labelBottom = maxOf(h - fm.descent, labelTop)
            ticks.forEach { bpm ->
                val ty = y(bpm.toDouble())
                drawLine(gridColor, Offset(gutter, ty), Offset(size.width, ty), strokeWidth = 1f)
                val baseline = (ty - (fm.ascent + fm.descent) / 2f).coerceIn(labelTop, labelBottom)
                drawContext.canvas.nativeCanvas.drawText(bpm.toString(), 0f, baseline, paint)
            }

            // Onset / wake bounds as dashed verticals with a foot dot, the way the sleep window reads.
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
            listOf(onsetFrac, wakeFrac).forEach { frac ->
                val bx = x(frac)
                drawLine(boundColor, Offset(bx, 0f), Offset(bx, h), strokeWidth = 1.5f, pathEffect = dash)
                drawCircle(boundColor, radius = 3f, center = Offset(bx, h - 3f))
            }

            fun pathOf(run: List<TimelinePoint>): Path = Path().apply {
                run.forEachIndexed { i, p ->
                    val px = x(windowFraction(p.ts, windowStart, windowSpanSec))
                    val py = y(p.value)
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            // Movement, on the same clock as the HR trace. It has no bpm, so it is self-normalised to
            // the night's own peak and confined to the bottom MOTION_BAND of the plot — it shares the
            // time axis and nothing else, and never crosses into the HR trace's range.
            if (hasMotionLine(motionEpochs)) {
                val peak = motionEpochs.max()
                if (peak > 0.0) {
                    val bandTop = h * (1f - MOTION_BAND)
                    fun motionPoint(i: Int): Offset {
                        val ts = onsetTs + i.toLong() * EPOCH_SEC
                        val frac = (motionEpochs[i] / peak).coerceIn(0.0, 1.0).toFloat()
                        return Offset(
                            x(windowFraction(ts, windowStart, windowSpanSec)),
                            h - frac * (h - bandTop),
                        )
                    }
                    val last = motionEpochs.lastIndex
                    val area = Path().apply {
                        moveTo(motionPoint(0).x, h)
                        for (i in 0..last) motionPoint(i).let { lineTo(it.x, it.y) }
                        lineTo(motionPoint(last).x, h)
                        close()
                    }
                    drawPath(area, color = motionColor.copy(alpha = 0.18f))
                    val crest = Path().apply {
                        motionPoint(0).let { moveTo(it.x, it.y) }
                        for (i in 1..last) motionPoint(i).let { lineTo(it.x, it.y) }
                    }
                    drawPath(crest, color = motionColor.copy(alpha = 0.75f), style = Stroke(width = 1.2f))
                }
            }

            runs.filter { it.size >= 2 }.forEach { run ->
                drawPath(pathOf(run), color = traceColor, style = Stroke(width = 1.5f))
            }
            // The same trace redrawn inside each band, so the selected stage owns its stretch of the night.
            bands.forEach { (start, width) ->
                val from = windowStart + (start * windowSpanSec).toLong()
                val to = windowStart + ((start + width) * windowSpanSec).toLong()
                runs.forEach { run ->
                    val slice = run.filter { it.ts in from..to }
                    if (slice.size >= 2) drawPath(pathOf(slice), color = bandTint, style = Stroke(width = 2f))
                }
            }
        }
        HrBoundLabels(onsetTs, wakeTs, onsetFrac, wakeFrac)
    }
}

/** Whether a movement line can be drawn at all: a polyline needs two points. One owner, two readers. */
internal fun hasMotionLine(motionEpochs: List<Double>): Boolean = motionEpochs.size >= 2

/**
 * Names the series on the HR chart, so the colours are not the only thing telling them apart. The
 * movement key appears only when a movement line was drawn: [hasMotion] must come from
 * [hasMotionLine], or a night stored before motion capture advertises a line that is not there.
 */
@Composable
internal fun SleepHrMotionLegend(hasMotion: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendKey(Palette.metricRose, stringResource(R.string.sleep_legend_heart_rate))
        if (hasMotion) LegendKey(Palette.metricCyan, stringResource(R.string.sleep_legend_movement))
    }
}

@Composable
private fun LegendKey(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Metrics.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(width = Metrics.space12, height = Metrics.space2)) {
            drawLine(color, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = size.height)
        }
        Text(label, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

/** The onset and wake clock times, each sitting under its dashed bound in the plot above. */
@Composable
private fun HrBoundLabels(onsetTs: Long, wakeTs: Long, onsetFrac: Float, wakeFrac: Float) {
    val inner = (wakeFrac - onsetFrac).coerceAtLeast(0.02f)
    val trailing = (1f - wakeFrac).coerceAtLeast(0.001f)
    Row(modifier = Modifier.fillMaxWidth().padding(start = Metrics.hrChartGutter)) {
        if (onsetFrac > 0f) Spacer(Modifier.weight(onsetFrac))
        Text(
            clockTimeLabel(onsetTs),
            style = NoopType.footnote,
            color = Palette.textTertiary,
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.weight(inner / 2f),
        )
        Text(
            clockTimeLabel(wakeTs),
            style = NoopType.footnote,
            color = Palette.textTertiary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.weight(inner / 2f),
        )
        Spacer(Modifier.weight(trailing))
    }
}

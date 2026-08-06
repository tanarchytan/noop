package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.analytics.RustScores
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Charts (pure Compose Canvas, no external library)
//
// Every chart is null/empty-safe: by default renders a faint baseline so layouts never collapse.
// Each primitive contributes ONE semantics node (clearAndSetSemantics), avoiding per-element a11y trees.

// MARK: - Accessibility summaries

private fun seriesSummary(values: List<Double>, noun: String): String {
    val clean = values.filter { it.isFinite() }
    if (clean.isEmpty()) return "$noun, no data"
    val last = clean.last()
    val lo = clean.min()
    val hi = clean.max()
    return "$noun, ${clean.size} points, latest ${formatLineValue(last)}, " +
        "low ${formatLineValue(lo)}, high ${formatLineValue(hi)}"
}

/**
 * The hypnogram's spoken summary: each stage's share of the night as a whole percentage. whoop-rs
 * apportions the four shares so they sum to exactly 100, and yields nothing for a night with no
 * minutes — so the summary never speaks a total the night never had. A stage with no minutes is left
 * unspoken rather than announced as zero.
 */
internal fun hypnogramSummary(stages: List<Pair<String, Float>>): String {
    val noData = "Sleep stages, no data"
    val order = listOf("deep", "rem", "light", "awake")
    val byStage = LinkedHashMap<String, Float>()
    for (key in order) byStage[key] = 0f
    stages.forEach { (name, w) ->
        val v = if (w.isFinite() && w > 0f) w else 0f
        val key = when (name.trim().lowercase()) {
            "deep" -> "deep"; "rem" -> "rem"; "light" -> "light"; "awake", "wake" -> "awake"; else -> "light"
        }
        byStage[key] = (byStage[key] ?: 0f) + v
    }
    val split = RustScores.wholePercentages(order.map { (byStage[it] ?: 0f).toDouble() }) ?: return noData
    val parts = order.mapIndexedNotNull { i, key ->
        if ((byStage[key] ?: 0f) <= 0f) null else {
            val label = if (key == "rem") "REM" else key.replaceFirstChar { it.uppercase() }
            "${split[i]} percent $label"
        }
    }
    return if (parts.isEmpty()) noData else "Sleep stages, " + parts.joinToString(", ")
}

// MARK: - Shared geometry helpers

private fun pointsFor(
    values: List<Double>,
    width: Float,
    height: Float,
    topPad: Float,
    bottomPad: Float,
): List<Offset> {
    val clean = values.filter { it.isFinite() }
    if (clean.size < 2 || width <= 0f || height <= 0f) return emptyList()
    return pointsFor(clean, width, height, topPad, bottomPad, clean.min(), clean.max())
}

private fun pointsFor(
    values: List<Double>,
    width: Float,
    height: Float,
    topPad: Float,
    bottomPad: Float,
    minV: Double,
    maxV: Double,
): List<Offset> {
    if (values.size < 2 || width <= 0f || height <= 0f) return emptyList()
    val span = (maxV - minV)
    val usableH = (height - topPad - bottomPad).coerceAtLeast(1f)
    val stepX = if (values.size > 1) width / (values.size - 1) else width
    return values.mapIndexed { i, v ->
        val x = stepX * i
        val norm = if (span > 0.0) ((v - minV) / span).toFloat() else 0.5f
        val y = topPad + (1f - norm) * usableH
        Offset(x, y)
    }
}

private fun DrawScope.drawBaseline(color: Color = Palette.hairline) {
    val y = size.height / 2f
    drawLine(
        color = color.copy(alpha = StrandAlpha.subtleLine),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1f,
        cap = StrokeCap.Round,
    )
}

// MARK: - LineChart

@Composable
fun LineChart(
    values: List<Double>,
    modifier: Modifier,
    color: Color = Palette.accent,
    fill: Boolean = true,
    selectionEnabled: Boolean = false,
    dragSelectionEnabled: Boolean = true,
    formatValue: ((Double) -> String)? = null,
    timestamps: List<Long>? = null,
    // Headroom above the curve's own maximum, for a caller that draws over the top of the plot.
    topInset: Dp = 0.dp,
) {
    val topInsetPx = with(LocalDensity.current) { topInset.toPx() }
    val cleanValues = remember(values) { values.filter { it.isFinite() } }
    val cleanTimestamps = remember(values, timestamps) {
        if (timestamps == null || timestamps.size != values.size) null
        else values.indices.filter { values[it].isFinite() }.map { timestamps[it] }
    }
    var selectedIndex by remember(cleanValues) { mutableIntStateOf(-1) }
    val interactiveModifier = if (selectionEnabled) {
        Modifier
            .pointerInput(cleanValues) {
                detectTapGestures(
                    onTap = { offset ->
                        if (cleanValues.size >= 2 && size.width > 0) {
                            selectedIndex = nearestIndexForX(
                                count = cleanValues.size,
                                width = size.width.toFloat(),
                                x = offset.x,
                            )
                        }
                    },
                )
            }
            .then(
                if (dragSelectionEnabled) {
                    Modifier.pointerInput(cleanValues) {
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                if (cleanValues.size < 2 || size.width <= 0f) return@detectHorizontalDragGestures
                                selectedIndex = nearestIndexForX(
                                    count = cleanValues.size,
                                    width = size.width.toFloat(),
                                    x = start.x,
                                )
                            },
                            onHorizontalDrag = { change, _ ->
                                if (cleanValues.size < 2 || size.width <= 0f) return@detectHorizontalDragGestures
                                selectedIndex = nearestIndexForX(
                                    count = cleanValues.size,
                                    width = size.width.toFloat(),
                                    x = change.position.x,
                                )
                                change.consume()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
    } else {
        Modifier
    }

    val axSummary = seriesSummary(cleanValues, "Trend")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .clearAndSetSemantics { contentDescription = axSummary }
            .then(interactiveModifier),
    ) {
        val markerPaint = remember(color) {
            android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 30f
                this.color = color.copy(alpha = StrandAlpha.chartLabel).toArgb()
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val strokePx = 2.5f
                    val topPad = strokePx + 4f + topInsetPx
                    val bottomPad = strokePx + 4f
                    val pts = pointsFor(cleanValues, size.width, size.height, topPad, bottomPad)
                    if (pts.isEmpty()) {
                        onDrawBehind { drawBaseline() }
                    } else {
                        val fillPath = if (fill) {
                            Path().apply {
                                moveTo(pts.first().x, size.height)
                                lineTo(pts.first().x, pts.first().y)
                                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                                lineTo(pts.last().x, size.height)
                                close()
                            }
                        } else {
                            null
                        }
                        val fillBrush = if (fill) {
                            Brush.verticalGradient(
                                colors = listOf(
                                    color.copy(alpha = StrandAlpha.chartFillStrong),
                                    color.copy(alpha = StrandAlpha.chartFillSoft),
                                    Color.Transparent,
                                ),
                                startY = 0f,
                                endY = size.height,
                            )
                        } else {
                            null
                        }
                        val linePath = Path().apply {
                            moveTo(pts.first().x, pts.first().y)
                            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                        }
                        val lineStroke = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        onDrawBehind {
                            if (fillPath != null && fillBrush != null) {
                                drawPath(path = fillPath, brush = fillBrush)
                            }
                            drawPath(path = linePath, color = color, style = lineStroke)
                        }
                    }
                }
                .drawWithContent {
                    drawContent()
                    if (selectionEnabled && selectedIndex >= 0) {
                        val strokePx = 2.5f
                        val topPad = strokePx + 4f + topInsetPx
                        val bottomPad = strokePx + 4f
                        val pts = pointsFor(cleanValues, size.width, size.height, topPad, bottomPad)
                        if (selectedIndex in pts.indices) {
                            val p = pts[selectedIndex]
                            drawLine(
                                color = color.copy(alpha = StrandAlpha.chartMarker),
                                start = Offset(p.x, 0f),
                                end = Offset(p.x, size.height),
                                strokeWidth = 1.5f,
                                cap = StrokeCap.Round,
                            )
                            drawCircle(color = color, radius = 5f, center = p)
                            drawCircle(color = Palette.surfaceBase.copy(alpha = StrandAlpha.chartShadow), radius = 9f, center = p)
                            drawCircle(color = color, radius = 4.5f, center = p)
                            drawContext.canvas.nativeCanvas.apply {
                                val label = lineChartSelectionLabel(
                                    cleanValues[selectedIndex],
                                    formatValue,
                                    cleanTimestamps?.getOrNull(selectedIndex),
                                )
                                drawText(label, 8f, 32f, markerPaint)
                            }
                        }
                    }
                },
        )
    }
}

private fun nearestIndexForX(count: Int, width: Float, x: Float): Int {
    if (count <= 1 || width <= 0f) return 0
    val step = width / (count - 1)
    val clampedX = x.coerceIn(0f, width)
    val raw = (clampedX / step).roundToInt()
    return raw.coerceIn(0, count - 1)
}

internal fun lineChartSelectionLabel(
    value: Double,
    formatValue: ((Double) -> String)?,
    epochSec: Long? = null,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val base = formatValue?.invoke(value) ?: formatLineValue(value)
    if (epochSec == null) return base
    val time = Instant.ofEpochSecond(epochSec).atZone(zone).format(chartTickTimeFormat)
    return "$time · $base"
}

internal fun formatLineValue(value: Double): String {
    if (!value.isFinite()) return "-"
    val rounded = value.roundToInt().toDouble()
    return if (abs(value - rounded) < 0.05) {
        rounded.toInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
}

private fun nearestBarIndexForX(count: Int, width: Float, x: Float): Int {
    if (count <= 1 || width <= 0f) return 0
    val slot = width / count
    val clampedX = x.coerceIn(0f, width)
    return (clampedX / slot).toInt().coerceIn(0, count - 1)
}

// MARK: - BarChart

@Composable
fun BarChart(
    values: List<Double>,
    modifier: Modifier,
    color: Color = Palette.accent,
    selectionEnabled: Boolean = false,
) {
    val cleanValues = remember(values) { values.map { if (it.isFinite() && it > 0.0) it else 0.0 } }
    var selectedIndex by remember(cleanValues) { mutableIntStateOf(-1) }
    val barLabelPaint = remember(color) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 30f
            this.color = color.copy(alpha = StrandAlpha.chartLabel).toArgb()
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD,
            )
        }
    }
    val unselectedColor = remember(color) { color.copy(alpha = StrandAlpha.unselectedBar) }

    val axSummary = seriesSummary(cleanValues, "Bars")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = axSummary }
            .then(
                if (selectionEnabled) {
                    Modifier.pointerInput(cleanValues) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (cleanValues.isNotEmpty() && size.width > 0) {
                                    selectedIndex = nearestBarIndexForX(
                                        count = cleanValues.size,
                                        width = size.width.toFloat(),
                                        x = offset.x,
                                    )
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val w = size.width
                val h = size.height
                val maxBars = w.toInt().coerceAtLeast(1)
                val clean = if (!selectionEnabled && cleanValues.size > maxBars && maxBars >= 1) {
                    meanBucketDownsample(cleanValues, maxBars)
                } else {
                    cleanValues
                }
                val maxV = clean.maxOrNull() ?: 0.0
                if (clean.isEmpty() || maxV <= 0.0 || w <= 0f || h <= 0f) {
                    onDrawBehind { drawBaseline() }
                } else {
                    val topPad = 4f
                    val usableH = (h - topPad).coerceAtLeast(1f)
                    val slot = w / clean.size
                    val barWidth = (slot * 0.64f).coerceAtLeast(1f)
                    data class BarSeg(val cx: Float, val top: Float)
                    val bars = ArrayList<BarSeg>(clean.size)
                    clean.forEachIndexed { i, v ->
                        val norm = (v / maxV).toFloat().coerceIn(0f, 1f)
                        val barHeight = (norm * usableH).coerceAtLeast(if (v > 0.0) 1f else 0f)
                        if (barHeight <= 0f) return@forEachIndexed
                        val cx = slot * i + slot / 2f
                        val top = h - barHeight
                        bars.add(BarSeg(cx, top))
                    }
                    onDrawBehind {
                        bars.forEachIndexed { i, seg ->
                            drawCappedBar(
                                color = if (selectionEnabled && i == selectedIndex) color else unselectedColor,
                                centerX = seg.cx,
                                top = seg.top,
                                baseline = h,
                                width = barWidth,
                            )
                        }
                        if (selectionEnabled && selectedIndex in clean.indices) {
                            drawContext.canvas.nativeCanvas.apply {
                                drawText(formatLineValue(clean[selectedIndex]), 8f, 32f, barLabelPaint)
                            }
                        }
                    }
                }
            },
    )
}

private fun meanBucketDownsample(values: List<Double>, target: Int): List<Double> {
    val n = values.size
    if (target < 1 || n <= target) return values
    val out = ArrayList<Double>(target)
    for (b in 0 until target) {
        val lo = (b.toLong() * n / target).toInt()
        val hi = (((b + 1).toLong() * n / target).toInt()).coerceAtMost(n)
        if (hi <= lo) { out.add(values[lo.coerceIn(0, n - 1)]); continue }
        var sum = 0.0
        for (i in lo until hi) sum += values[i]
        out.add(sum / (hi - lo))
    }
    return out
}

// MARK: - Hypnogram

@Composable
fun Hypnogram(
    stages: List<Pair<String, Float>>,
    modifier: Modifier,
) {
    val axSummary = hypnogramSummary(stages)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Metrics.segmentBarHeight)
            .clearAndSetSemantics { contentDescription = axSummary }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val weights = stages.map { it.second }.map { if (it.isFinite() && it > 0f) it else 0f }
                val total = weights.sum()
                if (w <= 0f || h <= 0f || stages.isEmpty() || total <= 0f) {
                    onDrawBehind {
                        if (w > 0f && h > 0f) drawRoundedTrack(Palette.surfaceInset)
                    }
                } else {
                    val segs = ArrayList<Triple<Color, Float, Float>>(stages.size)
                    var x = 0f
                    val gap = if (stages.size > 1) 1.5f else 0f
                    stages.forEachIndexed { i, (name, _) ->
                        val frac = weights[i] / total
                        val segW = (w * frac)
                        if (segW <= 0f) return@forEachIndexed
                        val drawW = (segW - if (i < stages.size - 1) gap else 0f).coerceAtLeast(0f)
                        if (drawW > 0f) segs.add(Triple(stageColor(name), x, drawW))
                        x += segW
                    }
                    onDrawBehind {
                        drawRoundedTrack(Palette.surfaceInset)
                        segs.forEach { (c, left, width) ->
                            drawSegment(color = c, left = left, width = width, height = h)
                        }
                    }
                }
            },
    )
}

// MARK: - SegmentBar

@Composable
fun SegmentBar(
    segments: List<Pair<Color, Float>>,
    modifier: Modifier,
    height: Dp = Metrics.segmentBarHeight,
) {
    val axSummary = if (segments.isEmpty()) "Breakdown, no data" else "Breakdown, ${segments.size} segments"
    Box(modifier = modifier.fillMaxWidth().height(height).clearAndSetSemantics { contentDescription = axSummary }.drawWithCache {
        val w = size.width
        val h = size.height
        val weights = segments.map { it.second }.map { if (it.isFinite() && it > 0f) it else 0f }
        val total = weights.sum()
        if (w <= 0f || h <= 0f || segments.isEmpty() || total <= 0f) {
            onDrawBehind {
                if (w > 0f && h > 0f) drawRoundedTrack(Palette.surfaceInset)
            }
        } else {
            val segs = ArrayList<Triple<Color, Float, Float>>(segments.size)
            var x = 0f
            val gap = if (segments.size > 1) 1.5f else 0f
            segments.forEachIndexed { i, (color, _) ->
                val frac = weights[i] / total
                val segW = w * frac
                if (segW <= 0f) return@forEachIndexed
                val drawW = (segW - if (i < segments.size - 1) gap else 0f).coerceAtLeast(0f)
                if (drawW > 0f) segs.add(Triple(color, x, drawW))
                x += segW
            }
            onDrawBehind {
                drawRoundedTrack(Palette.surfaceInset)
                segs.forEach { (c, left, width) -> drawSegment(color = c, left = left, width = width, height = h) }
            }
        }
    })
}

private fun DrawScope.drawRoundedTrack(color: Color) {
    drawLine(
        color = color,
        start = Offset(0f, size.height / 2f),
        end = Offset(size.width, size.height / 2f),
        strokeWidth = size.height,
        cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawSegment(color: Color, left: Float, width: Float, height: Float) {
    val cap = (height / 2f).coerceAtMost(width / 2f)
    drawLine(
        color = color,
        start = Offset(left + cap, height / 2f),
        end = Offset((left + width - cap).coerceAtLeast(left + cap), height / 2f),
        strokeWidth = height,
        cap = StrokeCap.Round,
    )
}

internal fun stageColor(name: String): Color = when (name.trim().lowercase()) {
    "deep" -> Palette.sleepDeep
    "rem" -> Palette.sleepREM
    "light" -> Palette.sleepLight
    "awake", "wake" -> Palette.sleepAwake
    else -> Palette.sleepLight
}

@Composable
fun ClockLabelRow(onsetTs: Long, wakeTs: Long) {
    val onset = clockTimeLabel(onsetTs)
    val mid = clockTimeLabel((onsetTs + wakeTs) / 2L)
    val wake = clockTimeLabel(wakeTs)
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            onset,
            style = NoopType.footnote,
            color = Palette.textTertiary,
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            mid,
            style = NoopType.footnote,
            color = Palette.textTertiary,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            wake,
            style = NoopType.footnote,
            color = Palette.textTertiary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

// MARK: - Deep Timeline chart (time-indexed, zoom + pan)

data class TimelinePoint(val ts: Long, val value: Double)

fun timelineBucketSeconds(spanSeconds: Long, targetPoints: Int): Long {
    val span = spanSeconds.coerceAtLeast(1L)
    val target = targetPoints.coerceAtLeast(1)
    val ideal = span / target
    if (ideal <= 1L) return 1L
    val steps = longArrayOf(2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600)
    for (s in steps) if (s >= ideal) return s
    return steps.last()
}

fun zoomedWindow(
    base: LongRange,
    scale: Float,
    anchorFraction: Float,
    bounds: LongRange,
    minSpan: Long = 60L,
): LongRange {
    val span = (base.last - base.first).coerceAtLeast(1L)
    if (scale <= 0f) return base
    val pivot = base.first + (span * anchorFraction.coerceIn(0f, 1f)).toLong()
    val boundsSpan = (bounds.last - bounds.first).coerceAtLeast(minSpan)
    val newSpan = (span / scale).toLong().coerceIn(minSpan, boundsSpan)
    var newLo = pivot - ((pivot - base.first).toDouble() * newSpan / span).toLong()
    var newHi = newLo + newSpan
    if (newLo < bounds.first) { newLo = bounds.first; newHi = newLo + newSpan }
    if (newHi > bounds.last) { newHi = bounds.last; newLo = newHi - newSpan }
    newLo = newLo.coerceAtLeast(bounds.first)
    return newLo..(newLo + newSpan).coerceAtLeast(newLo + 1)
}

// MARK: - Round-time x-axis ticks

private val chartTickTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

// One wall-clock time in the same HH:mm the x-axis ticks carry, so a label placed off-tick
// (a plot edge) reads as one of them.
fun chartTickTime(epochSec: Long, zone: ZoneId): String =
    Instant.ofEpochSecond(epochSec).atZone(zone).format(chartTickTimeFormat)

fun chartTimeTicks(startEpochSec: Long, endEpochSec: Long, zone: ZoneId): List<Pair<Long, String>> {
    if (endEpochSec <= startEpochSec) return emptyList()
    val spanHours = (endEpochSec - startEpochSec) / 3600.0
    val stepMinutes = when {
        spanHours >= 20.0 -> 360L
        spanHours >= 10.0 -> 180L
        spanHours >= 5.0 -> 120L
        spanHours >= 2.0 -> 60L
        else -> 15L
    }
    var tick = Instant.ofEpochSecond(startEpochSec).atZone(zone).toLocalDate().atStartOfDay()
    val out = ArrayList<Pair<Long, String>>()
    var lastEpoch = Long.MIN_VALUE
    var guard = 0
    while (guard++ < 4096) {
        val zoned = tick.atZone(zone)
        val epoch = zoned.toEpochSecond()
        if (epoch > endEpochSec) break
        if (epoch in startEpochSec..endEpochSec && epoch > lastEpoch) {
            out.add(epoch to zoned.format(chartTickTimeFormat))
            lastEpoch = epoch
        }
        tick = tick.plusMinutes(stepMinutes)
    }
    return out
}

fun timestampFraction(timestamps: List<Long>, ts: Long): Float? {
    val n = timestamps.size
    if (n < 2) return null
    if (ts < timestamps.first() || ts > timestamps.last()) return null
    val hi = timestamps.indexOfFirst { it >= ts }
    if (hi <= 0) return 0f
    val lo = hi - 1
    val t0 = timestamps[lo]
    val t1 = timestamps[hi]
    val f = if (t1 > t0) (ts - t0).toFloat() / (t1 - t0).toFloat() else 0f
    return (lo + f) / (n - 1)
}

fun pannedWindow(base: LongRange, deltaSeconds: Long, bounds: LongRange): LongRange {
    val span = base.last - base.first
    var newLo = base.first + deltaSeconds
    newLo = newLo.coerceIn(bounds.first, (bounds.last - span).coerceAtLeast(bounds.first))
    return newLo..(newLo + span)
}

@Composable
fun TimelineChart(
    points: List<TimelinePoint>,
    windowStart: Long,
    windowEnd: Long,
    bounds: LongRange,
    color: Color,
    modifier: Modifier,
    onWindowChange: (LongRange) -> Unit,
) {
    val span = (windowEnd - windowStart).coerceAtLeast(1L)
    val vis = remember(points, windowStart, windowEnd) {
        points.filter { it.ts in windowStart..windowEnd && it.value.isFinite() }
    }
    // The window's own round-hour ticks. Without them the plot said nothing about WHEN, so a day whose
    // readings stop early read as a broken chart rather than a morning of data.
    val ticks = remember(windowStart, windowEnd) {
        chartTimeTicks(windowStart, windowEnd, ZoneId.systemDefault())
    }
    val measurer = rememberTextMeasurer()
    val axisStyle = NoopType.footnote
    val axisColor = Palette.textTertiary
    val gridColor = Palette.hairline.copy(alpha = StrandAlpha.subtleLine)

    val axSummary = seriesSummary(vis.map { it.value }, "Timeline")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .clearAndSetSemantics { contentDescription = axSummary }
            .pointerInput(bounds) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    var window = windowStart..windowEnd
                    if (zoom != 1f) {
                        val frac = (centroid.x / width).coerceIn(0f, 1f)
                        window = zoomedWindow(window, zoom, frac, bounds)
                    }
                    if (pan.x != 0f) {
                        val curSpan = window.last - window.first
                        val secPerPx = curSpan.toDouble() / width
                        window = pannedWindow(window, (-pan.x * secPerPx).toLong(), bounds)
                    }
                    onWindowChange(window)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 2.5f
            val topPad = strokePx + 4f
            val bottomPad = strokePx + 4f
            val gap = Metrics.space4.toPx()
            val labelH = measurer.measure("0", axisStyle).size.height.toFloat()
            val plotBottom = (size.height - labelH - gap).coerceAtLeast(1f)

            fun px(ts: Long): Float = ((ts - windowStart).toFloat() / span) * size.width

            ticks.forEach { (ts, text) ->
                val x = px(ts)
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, plotBottom),
                    strokeWidth = 1f,
                )
                val layout = measurer.measure(text, axisStyle)
                val maxX = (size.width - layout.size.width).coerceAtLeast(0f)
                drawText(
                    textLayoutResult = layout,
                    color = axisColor,
                    topLeft = Offset((x - layout.size.width / 2f).coerceIn(0f, maxX), plotBottom + gap),
                )
            }

            if (vis.size < 2 || size.width <= 0f || size.height <= 0f) {
                drawBaseline()
                return@Canvas
            }
            val minV = vis.minOf { it.value }
            val maxV = vis.maxOf { it.value }
            val range = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
            val usable = (plotBottom - topPad - bottomPad).coerceAtLeast(1f)

            fun py(v: Double): Float = topPad + ((maxV - v) / range).toFloat() * usable

            val linePath = Path().apply {
                moveTo(px(vis.first().ts), py(vis.first().value))
                for (i in 1 until vis.size) lineTo(px(vis[i].ts), py(vis[i].value))
            }
            val fillPath = Path().apply {
                moveTo(px(vis.first().ts), plotBottom)
                lineTo(px(vis.first().ts), py(vis.first().value))
                for (i in 1 until vis.size) lineTo(px(vis[i].ts), py(vis[i].value))
                lineTo(px(vis.last().ts), plotBottom)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = StrandAlpha.chartFillStrong),
                        color.copy(alpha = StrandAlpha.chartFillSoft),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = plotBottom,
                ),
            )
            drawPath(
                path = linePath,
                color = color,
                style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

// MARK: - GlowEndCap (three-layer sparkline end-cap dot)

@Composable
fun GlowEndCap(
    values: List<Double>,
    tipColor: Color,
    modifier: Modifier = Modifier,
) {
    val clean = remember(values) { values.filter { it.isFinite() } }
    if (clean.size < 2) return
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokePx = 2.5f
        val topPad = strokePx + 4f
        val bottomPad = strokePx + 4f
        val minV = clean.min()
        val maxV = clean.max()
        val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
        val usableH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
        val norm = ((clean.last() - minV) / span).toFloat().coerceIn(0f, 1f)
        val center = Offset(size.width, topPad + (1f - norm) * usableH)
        drawCircle(color = tipColor.copy(alpha = 0.30f), radius = 9f, center = center)
        drawCircle(color = tipColor.copy(alpha = 0.65f), radius = 5.5f, center = center)
        drawCircle(color = Palette.tipCore, radius = 2.4f, center = center)
    }
}

// MARK: - TileSparkline (compact filled sparkline with glow end-cap)

@Composable
fun TileSparkline(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.clipToBounds()) {
        if (values.size < 2 || size.width <= 0f || size.height <= 0f) return@Canvas
        val strokePx = 2f
        val pad = strokePx + 2f
        val usableH = (size.height - pad * 2).coerceAtLeast(1f)
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val n = values.size
        fun xFor(i: Int): Float = if (n > 1) size.width * i / (n - 1) else 0f
        fun yFor(v: Double): Float {
            val norm = ((v - lo) / span).toFloat().coerceIn(0f, 1f)
            return pad + (1f - norm) * usableH
        }
        val pts = values.mapIndexed { i, v -> Offset(xFor(i), yFor(v)) }

        val fillPath = Path().apply {
            moveTo(pts.first().x, size.height)
            lineTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            lineTo(pts.last().x, size.height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    color.copy(alpha = StrandAlpha.chartFillSoft),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = size.height,
            ),
        )

        val linePath = Path().apply {
            moveTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        drawPath(
            path = linePath,
            brush = Brush.horizontalGradient(
                colors = listOf(color.copy(alpha = 0.5f), color),
                startX = 0f,
                endX = size.width,
            ),
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val end = pts.last()
        drawCircle(color = color.copy(alpha = 0.30f), radius = 6f, center = end)
        drawCircle(color = color.copy(alpha = 0.65f), radius = 3.5f, center = end)
        drawCircle(color = Palette.tipCore, radius = 1.6f, center = end)
    }
}

// MARK: - Week charts (fixed slots, always-on value labels)
//
// One slot per day label, a highlighted column behind the slot the caller names, and every plotted value
// printed where it is drawn. Nothing here derives a figure: values, axis maxima, formats and the
// colour-by-value lookup all arrive from the caller, so a threshold never lives in this file.

/** Bar width as a fraction of its slot — the slim weekly column the sleep cards already draw. */
private const val WEEK_BAR_SLOT_FRACTION = 0.34f

/** Hollow point marker geometry for the week line charts. */
private const val WEEK_MARKER_RADIUS = 6f
private const val WEEK_MARKER_STROKE = 2.5f

/** One overlaid series of a week line chart: its legend name, its points and its colour. Which side a
 *  point's label sits is decided per slot by [labelTopFor], never per series. */
data class WeekLineSeries(
    val name: String,
    val values: List<Double?>,
    val color: Color,
)

/** The y a point's label is drawn at, above the marker or under it. */
internal fun labelTop(p: Offset, below: Boolean, gap: Float, labelH: Float): Float =
    if (below) p.y + WEEK_MARKER_RADIUS + gap else p.y - WEEK_MARKER_RADIUS - gap - labelH

/**
 * Which side each of two points printed at the same slot labels on, as (a below, b below): the lower
 * point takes the underside so the two never print over each other, ties send [b] down, and a slot
 * only one series reaches labels above.
 */
internal fun labelSides(a: Offset?, b: Offset?): Pair<Boolean, Boolean> = when {
    a == null || b == null -> false to false
    a.y > b.y -> true to false
    else -> false to true
}

/** One band of a stacked week bar: the legend word and the colour the band is drawn in. */
data class WeekStackSegment(
    val name: String,
    val color: Color,
)

/** How a legend entry marks its series: a filled chip, a hollow ring, or a line stub. */
enum class LegendMark { Swatch, Ring, Line }

// MARK: - ChartLegend

/**
 * A chart's legend: one mark plus its name per series, centred over the plot. The one legend row for the
 * charts in this file; screens still holding their own private legend should fold into it.
 */
@Composable
fun ChartLegend(
    items: List<Pair<String, Color>>,
    modifier: Modifier = Modifier,
    mark: LegendMark = LegendMark.Swatch,
) {
    if (items.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (name, color) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
                modifier = Modifier.padding(horizontal = Metrics.space10),
            ) {
                LegendMarkGlyph(mark, color)
                Text(name, style = NoopType.footnote, color = Palette.textSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LegendMarkGlyph(mark: LegendMark, color: Color) {
    when (mark) {
        LegendMark.Swatch -> Box(
            modifier = Modifier
                .size(Metrics.legendSwatch)
                .clip(RoundedCornerShape(Metrics.cornerXs))
                .background(color),
        )
        LegendMark.Ring -> Box(
            modifier = Modifier
                .size(Metrics.legendSwatch)
                .clip(CircleShape)
                .border(Metrics.divider, color, CircleShape),
        )
        LegendMark.Line -> Box(
            modifier = Modifier
                .width(Metrics.legendLineWidth)
                .height(Metrics.legendLineHeight)
                .clip(RoundedCornerShape(Metrics.cornerXs))
                .background(color),
        )
    }
}

// MARK: - Shared week-chart frame

/**
 * The frame every week chart sits in: an optional legend, the plot, the day labels, and the highlight
 * column behind [highlightIndex] spanning both. Gutters keep that column and the labels aligned with a
 * plot that reserves room for axis labels; null means no gutter on that side.
 */
@Composable
private fun WeekChartFrame(
    dayLabels: List<String>,
    highlightIndex: Int,
    description: String,
    modifier: Modifier = Modifier,
    leftGutter: Dp? = null,
    rightGutter: Dp? = null,
    legend: (@Composable () -> Unit)? = null,
    plot: @Composable () -> Unit,
) {
    val slots = dayLabels.size
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        legend?.invoke()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (slots <= 0 || highlightIndex !in 0 until slots) return@drawBehind
                    val left = leftGutter?.toPx() ?: 0f
                    val right = rightGutter?.toPx() ?: 0f
                    val plotW = (size.width - left - right)
                    if (plotW <= 0f) return@drawBehind
                    val slot = plotW / slots
                    drawRoundRect(
                        color = Palette.surfaceOverlay.copy(alpha = StrandAlpha.selectedFill),
                        topLeft = Offset(left + slot * highlightIndex, 0f),
                        size = Size(slot, size.height),
                        cornerRadius = CornerRadius(Metrics.cornerSm.toPx()),
                    )
                },
            verticalArrangement = Arrangement.spacedBy(Metrics.space6),
        ) {
            plot()
            WeekDayLabels(dayLabels, highlightIndex, leftGutter, rightGutter)
        }
    }
}

/** The day label under each slot; the highlighted one takes the primary ink. Two lines fit "Sat\n11". */
@Composable
private fun WeekDayLabels(
    labels: List<String>,
    highlightIndex: Int,
    leftGutter: Dp?,
    rightGutter: Dp?,
) {
    if (labels.isEmpty()) return
    var rowModifier: Modifier = Modifier.fillMaxWidth()
    if (leftGutter != null) rowModifier = rowModifier.padding(start = leftGutter)
    if (rightGutter != null) rowModifier = rowModifier.padding(end = rightGutter)
    Row(modifier = rowModifier) {
        labels.forEachIndexed { i, label ->
            Text(
                label,
                style = NoopType.footnote,
                color = if (i == highlightIndex) Palette.textPrimary else Palette.textTertiary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// MARK: - Shared week-chart draw helpers

/** One bar: a round-capped vertical line from [baseline] up to [top]. The bar idiom of every chart here. */
private fun DrawScope.drawCappedBar(
    color: Color,
    centerX: Float,
    top: Float,
    baseline: Float,
    width: Float,
) {
    val capRadius = width / 2f
    drawLine(
        color = color,
        start = Offset(centerX, baseline),
        end = Offset(centerX, (top + capRadius).coerceAtMost(baseline)),
        strokeWidth = width,
        cap = StrokeCap.Round,
    )
}

/** The hollow marker drawn on every plotted week point. */
private fun DrawScope.drawRingMarker(color: Color, center: Offset) {
    drawCircle(color = Palette.surfaceRaised, radius = WEEK_MARKER_RADIUS, center = center)
    drawCircle(
        color = color,
        radius = WEEK_MARKER_RADIUS,
        center = center,
        style = Stroke(width = WEEK_MARKER_STROKE),
    )
}

/** Stroke [points] as one path per unbroken run, so a missing day is a gap and not a straight line. */
private fun DrawScope.drawRuns(points: List<Offset?>, color: Color) {
    val runs = ArrayList<List<Offset>>()
    var run = ArrayList<Offset>()
    points.forEach { p ->
        if (p == null) {
            if (run.size >= 2) runs.add(run)
            run = ArrayList()
        } else {
            run.add(p)
        }
    }
    if (run.size >= 2) runs.add(run)
    runs.forEach { r ->
        val path = Path().apply {
            moveTo(r.first().x, r.first().y)
            for (k in 1 until r.size) lineTo(r[k].x, r[k].y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = WEEK_MARKER_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** The card-coloured plate behind a slot label: its inset around the glyphs and its own corner. */
private const val SLOT_LABEL_PLATE_PAD = 3f
private const val SLOT_LABEL_PLATE_CORNER = 4f

/**
 * [text] centred on [centerX] with its top at [top], held inside the canvas so a label never clips out,
 * over a card-coloured plate so a number stays readable where another series' line runs under it.
 */
internal fun DrawScope.drawSlotLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    color: Color,
    centerX: Float,
    top: Float,
) {
    val layout = measurer.measure(text, style)
    val maxX = (size.width - layout.size.width).coerceAtLeast(0f)
    val maxY = (size.height - layout.size.height).coerceAtLeast(0f)
    val at = Offset(
        (centerX - layout.size.width / 2f).coerceIn(0f, maxX),
        top.coerceIn(0f, maxY),
    )
    drawRoundRect(
        color = Palette.surfaceRaised.copy(alpha = StrandAlpha.labelPlate),
        topLeft = Offset(at.x - SLOT_LABEL_PLATE_PAD, at.y - SLOT_LABEL_PLATE_PAD),
        size = Size(
            layout.size.width + SLOT_LABEL_PLATE_PAD * 2f,
            layout.size.height + SLOT_LABEL_PLATE_PAD * 2f,
        ),
        cornerRadius = CornerRadius(SLOT_LABEL_PLATE_CORNER, SLOT_LABEL_PLATE_CORNER),
    )
    drawText(textLayoutResult = layout, color = color, topLeft = at)
}

/** The whole week as one sentence, so a chart is never silent to a screen reader. */
private fun weekSummary(noun: String, labels: List<String>, valueAt: (Int) -> String?): String {
    val parts = labels.indices.mapNotNull { i ->
        valueAt(i)?.let { "${labels[i].replace('\n', ' ')} $it" }
    }
    return if (parts.isEmpty()) "$noun, no data" else "$noun, " + parts.joinToString(", ")
}

/** The slot's plotted values, padded/trimmed to the label count so a short series can't shift the axis. */
private fun weekPoints(values: List<Double?>, slots: Int): List<Double?> =
    List(slots) { i -> values.getOrNull(i)?.takeIf { it.isFinite() } }

// MARK: - BandedWeekBarChart

/**
 * A week of bars, each tinted by its own value through [colorFor], with that value printed above it and
 * the slot at [highlightIndex] backed by a highlight column. [axisMax] pins the scale; null scales to the
 * week's own peak. [colorFor] is a Palette ramp lookup — this chart holds no band boundary of its own.
 */
@Composable
fun BandedWeekBarChart(
    values: List<Double?>,
    dayLabels: List<String>,
    colorFor: (Double) -> Color,
    modifier: Modifier = Modifier,
    format: (Double) -> String = { formatLineValue(it) },
    axisMax: Double? = null,
    highlightIndex: Int = -1,
    height: Dp = Metrics.chartHeight,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = NoopType.captionNumber
    val slots = dayLabels.size
    val points = remember(values, slots) { weekPoints(values, slots) }
    val peak = remember(points, axisMax) { axisMax ?: points.filterNotNull().maxOrNull() }
    val summary = remember(points, dayLabels) {
        weekSummary("Weekly bars", dayLabels) { i -> points[i]?.let(format) }
    }
    WeekChartFrame(
        dayLabels = dayLabels,
        highlightIndex = highlightIndex,
        description = summary,
        modifier = modifier,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (slots == 0 || peak == null || peak <= 0.0 || size.width <= 0f || size.height <= 0f) {
                drawBaseline()
                return@Canvas
            }
            val gap = Metrics.space4.toPx()
            val labelH = measurer.measure("0", labelStyle).size.height.toFloat()
            val usableH = (size.height - labelH - gap).coerceAtLeast(1f)
            val slotW = size.width / slots
            val barW = (slotW * WEEK_BAR_SLOT_FRACTION).coerceAtLeast(1f)
            points.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val norm = (v / peak).toFloat().coerceIn(0f, 1f)
                val barH = (norm * usableH).coerceAtLeast(if (v > 0.0) 1f else 0f)
                val cx = slotW * i + slotW / 2f
                val top = size.height - barH
                val tint = colorFor(v)
                if (barH > 0f) drawCappedBar(tint, cx, top, size.height, barW)
                drawSlotLabel(measurer, format(v), labelStyle, tint, cx, top - gap - labelH)
            }
        }
    }
}

// MARK: - WeekBarChart

/**
 * A week of bars in one colour with every value printed above its bar — the single-tint case of
 * [BandedWeekBarChart], which owns the drawing.
 */
@Composable
fun WeekBarChart(
    values: List<Double?>,
    dayLabels: List<String>,
    modifier: Modifier = Modifier,
    color: Color = Palette.accent,
    format: (Double) -> String = { formatLineValue(it) },
    axisMax: Double? = null,
    highlightIndex: Int = -1,
    height: Dp = Metrics.chartHeight,
) {
    BandedWeekBarChart(
        values = values,
        dayLabels = dayLabels,
        colorFor = { color },
        modifier = modifier,
        format = format,
        axisMax = axisMax,
        highlightIndex = highlightIndex,
        height = height,
    )
}

// MARK: - WeekLineChart / WeekDualLineChart

/**
 * One week series as a line, every point marked and labelled. Plotted on the shared slot geometry, so a
 * point sits at the centre of the slot its own day label sits under.
 */
@Composable
fun WeekLineChart(
    series: WeekLineSeries,
    dayLabels: List<String>,
    modifier: Modifier = Modifier,
    format: (Double) -> String = { formatLineValue(it) },
    highlightIndex: Int = -1,
    height: Dp = Metrics.chartHeight,
) {
    WeekLinePlot(listOf(series), dayLabels, modifier, format, highlightIndex, height, showsLegend = false)
}

/**
 * Two overlaid week series on one shared scale, every point marked and labelled, named in a legend row.
 * At a slot both series reach, the higher point labels above and the lower below, so the two never
 * print over each other.
 */
@Composable
fun WeekDualLineChart(
    primary: WeekLineSeries,
    secondary: WeekLineSeries,
    dayLabels: List<String>,
    modifier: Modifier = Modifier,
    format: (Double) -> String = { formatLineValue(it) },
    highlightIndex: Int = -1,
    height: Dp = Metrics.chartHeight,
) {
    WeekLinePlot(
        listOf(primary, secondary), dayLabels, modifier, format, highlightIndex, height, showsLegend = true,
    )
}

/** The plot both week line charts draw: one shared min..max domain, slot-centre x, a label per point. */
@Composable
private fun WeekLinePlot(
    input: List<WeekLineSeries>,
    dayLabels: List<String>,
    modifier: Modifier,
    format: (Double) -> String,
    highlightIndex: Int,
    height: Dp,
    showsLegend: Boolean,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = NoopType.captionNumber
    val slots = dayLabels.size
    val series = remember(input, slots) {
        input.map { s -> s.copy(values = weekPoints(s.values, slots)) }
    }
    val domain = remember(series) {
        val all = series.flatMap { it.values }.filterNotNull()
        if (all.isEmpty()) null else all.min() to all.max()
    }
    val summary = remember(series, dayLabels) {
        series.joinToString(". ") { s -> weekSummary(s.name, dayLabels) { i -> s.values[i]?.let(format) } }
    }
    val legend: (@Composable () -> Unit)? = if (showsLegend) {
        { ChartLegend(series.map { it.name to it.color }, mark = LegendMark.Ring) }
    } else {
        null
    }
    WeekChartFrame(
        dayLabels = dayLabels,
        highlightIndex = highlightIndex,
        description = summary,
        modifier = modifier,
        legend = legend,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (slots == 0 || domain == null || size.width <= 0f || size.height <= 0f) {
                drawBaseline()
                return@Canvas
            }
            val gap = Metrics.space4.toPx()
            val labelH = measurer.measure("0", labelStyle).size.height.toFloat()
            val band = labelH + gap + WEEK_MARKER_RADIUS
            val usableH = (size.height - band * 2f).coerceAtLeast(1f)
            val (lo, hi) = domain
            val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
            val slotW = size.width / slots

            fun offsetsFor(values: List<Double?>): List<Offset?> = values.mapIndexed { i, v ->
                v?.let {
                    Offset(
                        slotW * i + slotW / 2f,
                        band + (1f - ((it - lo) / span).toFloat()) * usableH,
                    )
                }
            }
            val plotted = series.map { offsetsFor(it.values) }
            series.forEachIndexed { si, s ->
                val offsets = plotted[si]
                drawRuns(offsets, s.color.copy(alpha = StrandAlpha.unselectedBar))
                offsets.forEachIndexed { i, p ->
                    val v = s.values[i]
                    if (p == null || v == null) return@forEachIndexed
                    // A lone series always labels above; two decide per slot so they never overprint.
                    val below = plotted.size > 1 &&
                        labelSides(plotted[0][i], plotted[1][i]).let { if (si == 0) it.first else it.second }
                    drawRingMarker(s.color, p)
                    drawSlotLabel(
                        measurer, format(v), labelStyle, s.color, p.x, labelTop(p, below, gap, labelH),
                    )
                }
            }
        }
    }
}

// MARK: - WeekStackedBarChart

/**
 * A week of stacked bars: [dayValues] holds one entry per [segments] band per day, stacked bottom-up in
 * segment order, with the stack's own total printed above it. The total is the sum of what is drawn, so
 * the label always describes the bar; the legend prints top-of-stack first.
 */
@Composable
fun WeekStackedBarChart(
    segments: List<WeekStackSegment>,
    dayValues: List<List<Double>>,
    dayLabels: List<String>,
    modifier: Modifier = Modifier,
    format: (Double) -> String = { formatLineValue(it) },
    totalColor: Color = Palette.textPrimary,
    highlightIndex: Int = -1,
    height: Dp = Metrics.chartHeight,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = NoopType.captionNumber
    val slots = dayLabels.size
    val stacks = remember(dayValues, segments, slots) {
        List(slots) { i ->
            val row = dayValues.getOrNull(i).orEmpty()
            segments.indices.map { s -> row.getOrNull(s)?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0 }
        }
    }
    val totals = remember(stacks) { stacks.map { it.sum() } }
    val peak = remember(totals) { totals.maxOrNull() ?: 0.0 }
    val summary = remember(totals, dayLabels) {
        weekSummary("Weekly totals", dayLabels) { i -> totals[i].takeIf { it > 0.0 }?.let(format) }
    }
    WeekChartFrame(
        dayLabels = dayLabels,
        highlightIndex = highlightIndex,
        description = summary,
        modifier = modifier,
        legend = { ChartLegend(segments.reversed().map { it.name to it.color }) },
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (slots == 0 || peak <= 0.0 || size.width <= 0f || size.height <= 0f) {
                drawBaseline()
                return@Canvas
            }
            val gap = Metrics.space4.toPx()
            val labelH = measurer.measure("0", labelStyle).size.height.toFloat()
            val usableH = (size.height - labelH - gap).coerceAtLeast(1f)
            val slotW = size.width / slots
            val barW = (slotW * WEEK_BAR_SLOT_FRACTION).coerceAtLeast(1f)
            for ((i, stack) in stacks.withIndex()) {
                val total = totals[i]
                if (total <= 0.0) continue
                val cx = slotW * i + slotW / 2f
                var base = size.height
                for ((s, v) in stack.withIndex()) {
                    if (v <= 0.0) continue
                    val segH = ((v / peak).toFloat() * usableH).coerceAtLeast(1f)
                    drawRect(
                        color = segments[s].color,
                        topLeft = Offset(cx - barW / 2f, base - segH),
                        size = Size(barW, segH),
                    )
                    base -= segH
                }
                drawSlotLabel(measurer, format(total), labelStyle, totalColor, cx, base - gap - labelH)
            }
        }
    }
}

// MARK: - DualAxisTrendChart

/**
 * The week on two scales: [leftValues] as a line over 0..[leftMax], [rightValues] as a connected run of
 * points each tinted by [rightColorFor] over 0..[rightMax]. Ticks, maxima, formats and colours are the
 * caller's; the chart maps a value to a pixel and prints what it is handed.
 */
@Composable
fun DualAxisTrendChart(
    dayLabels: List<String>,
    leftName: String,
    leftValues: List<Double?>,
    leftMax: Double,
    rightName: String,
    rightValues: List<Double?>,
    rightMax: Double,
    modifier: Modifier = Modifier,
    leftColor: Color = Palette.effortColor,
    rightColorFor: (Double) -> Color = { Palette.recoveryColor(it) },
    leftFormat: (Double) -> String = { formatLineValue(it) },
    rightFormat: (Double) -> String = { formatLineValue(it) },
    leftTicks: List<Double> = emptyList(),
    rightTicks: List<Double> = emptyList(),
    axisGutter: Dp = Metrics.hrChartGutter + Metrics.space12,
    highlightIndex: Int = -1,
    height: Dp = Metrics.chartHeight,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = NoopType.captionNumber
    val axisStyle = NoopType.footnote
    val slots = dayLabels.size
    val left = remember(leftValues, slots) { weekPoints(leftValues, slots) }
    val right = remember(rightValues, slots) { weekPoints(rightValues, slots) }
    val summary = remember(left, right, dayLabels) {
        weekSummary(leftName, dayLabels) { i -> left[i]?.let(leftFormat) } + ". " +
            weekSummary(rightName, dayLabels) { i -> right[i]?.let(rightFormat) }
    }
    WeekChartFrame(
        dayLabels = dayLabels,
        highlightIndex = highlightIndex,
        description = summary,
        modifier = modifier,
        leftGutter = if (leftTicks.isEmpty()) null else axisGutter,
        rightGutter = if (rightTicks.isEmpty()) null else axisGutter,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            val gutterL = if (leftTicks.isEmpty()) 0f else axisGutter.toPx()
            val gutterR = if (rightTicks.isEmpty()) 0f else axisGutter.toPx()
            val plotW = size.width - gutterL - gutterR
            if (slots == 0 || plotW <= 0f || size.height <= 0f || leftMax <= 0.0 || rightMax <= 0.0) {
                drawBaseline()
                return@Canvas
            }
            val gap = Metrics.space4.toPx()
            val labelH = measurer.measure("0", labelStyle).size.height.toFloat()
            val band = labelH + gap + WEEK_MARKER_RADIUS
            val usableH = (size.height - band * 2f).coerceAtLeast(1f)
            val slotW = plotW / slots
            fun yFor(v: Double, max: Double): Float =
                band + (1f - (v / max).toFloat().coerceIn(0f, 1f)) * usableH
            fun offsetsFor(values: List<Double?>, max: Double): List<Offset?> =
                values.mapIndexed { i, v -> v?.let { Offset(gutterL + slotW * i + slotW / 2f, yFor(it, max)) } }

            // One gridline set only — the two axes are ticked at the same fractions, so ruling both doubles up.
            val gridFractions = if (leftTicks.isNotEmpty()) {
                leftTicks.map { it / leftMax }
            } else {
                rightTicks.map { it / rightMax }
            }
            gridFractions.forEach { frac ->
                val y = band + (1f - frac.toFloat().coerceIn(0f, 1f)) * usableH
                drawLine(
                    color = Palette.hairline.copy(alpha = StrandAlpha.subtleLine),
                    start = Offset(gutterL, y),
                    end = Offset(gutterL + plotW, y),
                    strokeWidth = 1f,
                )
            }
            leftTicks.forEach { t ->
                val layout = measurer.measure(leftFormat(t), axisStyle)
                val maxY = (size.height - layout.size.height).coerceAtLeast(0f)
                drawText(
                    textLayoutResult = layout,
                    color = leftColor,
                    topLeft = Offset(
                        (gutterL - layout.size.width - gap).coerceAtLeast(0f),
                        (yFor(t, leftMax) - layout.size.height / 2f).coerceIn(0f, maxY),
                    ),
                )
            }
            rightTicks.forEach { t ->
                val layout = measurer.measure(rightFormat(t), axisStyle)
                val maxY = (size.height - layout.size.height).coerceAtLeast(0f)
                drawText(
                    textLayoutResult = layout,
                    color = rightColorFor(t),
                    topLeft = Offset(
                        gutterL + plotW + gap,
                        (yFor(t, rightMax) - layout.size.height / 2f).coerceIn(0f, maxY),
                    ),
                )
            }

            // Right axis: a neutral connector so the per-point band colours carry the reading. Each label
            // takes the free side of its own slot, so the two axes' figures never print over each other.
            val rightOffsets = offsetsFor(right, rightMax)
            val leftOffsets = offsetsFor(left, leftMax)
            drawRuns(rightOffsets, Palette.textSecondary)
            rightOffsets.forEachIndexed { i, p ->
                val v = right[i]
                if (p == null || v == null) return@forEachIndexed
                val tint = rightColorFor(v)
                drawRingMarker(tint, p)
                drawSlotLabel(
                    measurer, rightFormat(v), labelStyle, tint, p.x,
                    labelTop(p, labelSides(p, leftOffsets[i]).first, gap, labelH),
                )
            }

            drawRuns(leftOffsets, leftColor)
            leftOffsets.forEachIndexed { i, p ->
                val v = left[i]
                if (p == null || v == null) return@forEachIndexed
                drawRingMarker(leftColor, p)
                drawSlotLabel(
                    measurer, leftFormat(v), labelStyle, leftColor, p.x,
                    labelTop(p, labelSides(rightOffsets[i], p).second, gap, labelH),
                )
            }
        }
    }
}

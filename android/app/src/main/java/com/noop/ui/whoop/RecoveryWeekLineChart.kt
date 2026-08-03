package com.noop.ui.whoop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.noop.ui.Metrics
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.StrandAlpha
import com.noop.ui.drawSlotLabel
import com.noop.ui.formatLineValue

// A ONE-series week line chart. Charts.kt owns the two-series WeekDualLineChart, whose frame, run and
// marker drawing and min..max domain this repeats: a single series cannot use it without its
// two-entry legend, and this pass may not widen that file. Fold the two together there.

/** Hollow point marker geometry, matched to the week charts in Charts.kt. */
private const val MARKER_RADIUS = 6f
private const val MARKER_STROKE = 2.5f

/**
 * One week of [values] plotted over their own min..max span, every point marked and labelled, with
 * the slot at [highlightIndex] backed by a highlight column. A null breaks the line into runs and
 * draws nothing. The domain, the format and the colour all arrive from the caller.
 */
@Composable
internal fun RecoveryWeekLineChart(
    values: List<Double?>,
    dayLabels: List<String>,
    color: Color,
    name: String,
    modifier: Modifier = Modifier,
    format: (Double) -> String = { formatLineValue(it) },
    highlightIndex: Int = -1,
    height: Dp = Metrics.compactChartHeight,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = NoopType.captionNumber
    val slots = dayLabels.size
    val points = remember(values, slots) {
        List(slots) { i -> values.getOrNull(i)?.takeIf { it.isFinite() } }
    }
    val domain = remember(points) {
        val seen = points.filterNotNull()
        if (seen.isEmpty()) null else seen.min() to seen.max()
    }
    val summary = remember(points, dayLabels, name) {
        val parts = dayLabels.indices.mapNotNull { i ->
            points[i]?.let { "${dayLabels[i].replace('\n', ' ')} ${format(it)}" }
        }
        if (parts.isEmpty()) "$name, no data" else "$name, " + parts.joinToString(", ")
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = summary }
            .drawBehind {
                if (slots <= 0 || highlightIndex !in 0 until slots) return@drawBehind
                val slot = size.width / slots
                drawRoundRect(
                    color = Palette.surfaceOverlay.copy(alpha = StrandAlpha.selectedFill),
                    topLeft = Offset(slot * highlightIndex, 0f),
                    size = Size(slot, size.height),
                    cornerRadius = CornerRadius(Metrics.cornerSm.toPx()),
                )
            },
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
            if (slots == 0 || domain == null || size.width <= 0f || size.height <= 0f) {
                drawFlatBaseline()
                return@Canvas
            }
            val gap = Metrics.space4.toPx()
            val labelH = measurer.measure("0", labelStyle).size.height.toFloat()
            val band = labelH + gap + MARKER_RADIUS
            val usableH = (size.height - band * 2f).coerceAtLeast(1f)
            val (lo, hi) = domain
            val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
            val slotW = size.width / slots
            val offsets = points.mapIndexed { i, v ->
                v?.let {
                    Offset(
                        slotW * i + slotW / 2f,
                        band + (1f - ((it - lo) / span).toFloat()) * usableH,
                    )
                }
            }
            drawLineRuns(offsets, color.copy(alpha = StrandAlpha.unselectedBar))
            offsets.forEachIndexed { i, p ->
                val v = points[i]
                if (p == null || v == null) return@forEachIndexed
                drawHollowMarker(color, p)
                drawSlotLabel(
                    measurer, format(v), labelStyle, color, p.x,
                    p.y - MARKER_RADIUS - gap - labelH,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            dayLabels.forEachIndexed { i, label ->
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
}

/** The faint rule an empty chart draws so the card never collapses. */
private fun DrawScope.drawFlatBaseline() {
    val y = size.height / 2f
    drawLine(
        color = Palette.hairline.copy(alpha = StrandAlpha.subtleLine),
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1f,
        cap = StrokeCap.Round,
    )
}

/** Stroke one path per unbroken run, so a missing day is a gap rather than a straight line over it. */
private fun DrawScope.drawLineRuns(points: List<Offset?>, color: Color) {
    var run = ArrayList<Offset>()
    fun flush() {
        if (run.size >= 2) {
            val path = Path().apply {
                moveTo(run.first().x, run.first().y)
                for (k in 1 until run.size) lineTo(run[k].x, run[k].y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = MARKER_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        run = ArrayList()
    }
    for (p in points) {
        if (p == null) flush() else run.add(p)
    }
    flush()
}

/** The hollow marker drawn on every plotted point. */
private fun DrawScope.drawHollowMarker(color: Color, center: Offset) {
    drawCircle(color = Palette.surfaceRaised, radius = MARKER_RADIUS, center = center)
    drawCircle(color = color, radius = MARKER_RADIUS, center = center, style = Stroke(width = MARKER_STROKE))
}

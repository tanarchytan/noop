package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The four WHOOP-style stage rows: a colour swatch, the UPPERCASE stage name, the share-of-night % in the
 * stage colour, a liquid tube in the stage colour, and the right-aligned duration. Data is
 * rem / deep / light / awake over total.
 */
@Composable
internal fun StageBreakdownRows(s: Stages) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        StageBreakdownRow("REM", s.rem, s.total, Palette.sleepREM)
        StageBreakdownRow("Deep", s.deep, s.total, Palette.sleepDeep)
        StageBreakdownRow("Light", s.light, s.total, Palette.sleepLight)
        StageBreakdownRow("Awake", s.awake, s.total, Palette.sleepAwake)
    }
}

/**
 * One WHOOP-style stage row. `fraction = minutes / total` drives both the % and the tube fill, so the
 * coloured percent and the bar always agree.
 */
@Composable
private fun StageBreakdownRow(stage: String, minutes: Double, total: Double, color: Color) {
    val fraction = if (total > 0.0) (minutes / total).coerceIn(0.0, 1.0) else 0.0
    val percent = (fraction * 100.0).roundToInt()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription =
                    "$stage: ${durationText(minutes)}, $percent percent of the night"
            },
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Text(
            stage.uppercase(Locale.getDefault()),
            style = NoopType.overline,
            color = Palette.textPrimary,
            maxLines = 1,
            modifier = Modifier.width(56.dp),
        )
        Text(
            "$percent%",
            style = NoopType.captionNumber,
            color = color,
            maxLines = 1,
            modifier = Modifier.width(38.dp),
        )
        // The stage's share-of-night as a liquid TUBE (minutes / total). Static (animated = false): a
        // per-frame slosh per row across many rows isn't worth the cost. Same fraction the % + duration carry.
        LiquidTube(
            frac = fraction,
            tint = color,
            animated = false,
            height = 8.dp,
            modifier = Modifier.weight(1f),
        )
        Text(
            durationText(minutes),
            style = NoopType.captionNumber,
            color = Palette.textPrimary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(60.dp),
        )
    }
}

/**
 * The hero hypnogram strip plus an optional onset · midpoint · wake time axis. A proportional stage strip
 * with a per-segment WIDTH floor (so a brief stage reads as a rounded block, not a hairline), faint
 * hairlines at frac 0 / 0.5 / 1.0, and a clock-label row. The axis appears only when onset/wake are supplied.
 */
@Composable
internal fun HypnogramWithAxis(
    stages: List<Pair<String, Float>>,
    onsetTs: Long?,
    wakeTs: Long?,
) {
    val showsAxis = onsetTs != null && wakeTs != null
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(Metrics.stageStripHeight)) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // Inset well so the strip reads as a recessed track.
            drawLine(
                color = Palette.surfaceInset,
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = h,
                cap = StrokeCap.Round,
            )

            val weights = stages.map { it.second }.map { if (it.isFinite() && it > 0f) it else 0f }
            val total = weights.sum()
            if (stages.isEmpty() || total <= 0f) return@Canvas

            // WIDTH floor: floor short stages to a legible block so they don't vanish as a hairline. If the
            // floored widths overflow the canvas on a fragmented night, scale them ALL to fit so the strip
            // stays one continuous bar. Rounded RECTS advance by the same width they draw, so `x` stays on-canvas.
            val minSegW = h / 2f
            val floored = weights.map { wt -> if (wt > 0f) maxOf(w * (wt / total), minSegW) else 0f }
            val flooredSum = floored.sum()
            val scale = if (flooredSum > w) w / flooredSum else 1f
            val radius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            var x = 0f
            stages.forEachIndexed { i, (name, _) ->
                val segW = floored[i] * scale
                if (segW <= 0f) return@forEachIndexed
                drawRoundRect(
                    color = stageColorFor(name),
                    topLeft = Offset(x, 0f),
                    size = Size(segW.coerceAtMost(w - x), h),
                    cornerRadius = radius,
                )
                x += segW
            }

            // Time-axis vertical hairlines: onset · midpoint · wake.
            if (showsAxis) {
                listOf(0f, 0.5f, 1f).forEach { frac ->
                    val hx = w * frac
                    drawLine(
                        color = Palette.hairline,
                        start = Offset(hx, 0f),
                        end = Offset(hx, h),
                        strokeWidth = 1f,
                    )
                }
            }
        }
        if (showsAxis && onsetTs != null && wakeTs != null) {
            ClockLabelRow(onsetTs, wakeTs)
        }
    }
}

/**
 * The onset · midpoint · wake clock-label row under a night timeline. Extracted from [HypnogramWithAxis] so
 * the stage-timeline rows share the same axis rendering.
 */
@Composable
private fun ClockLabelRow(onsetTs: Long, wakeTs: Long) {
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

/** 90s display floor for the stage rows — rows tolerate finer texture than the staircase's 300s. */
private const val STAGE_ROW_SMOOTH_SEC = 90.0

/**
 * The WHOOP-style per-stage timeline stack for real-stage nights. Four tappable rows in WHOOP order
 * (AWAKE · LIGHT · DEEP · REM), each a hatched full-night track with solid segments on the shared onset→wake
 * axis; MotionStrip + the clock-label axis sit under the rows on the same timeline. The rows ARE the legend.
 */
@Composable
internal fun StageTimeline(
    realSegments: List<Pair<String, Float>>,
    s: Stages,
    onsetTs: Long?,
    wakeTs: Long?,
    motionEpochs: List<Double>,
) {
    // Night span: the session window when we have one (the clock axis uses the same span), else
    // the segments' own summed minutes — the fractions are identical either way.
    val weightSec = realSegments.sumOf { (_, wt) -> if (wt.isFinite() && wt > 0f) wt.toDouble() * 60.0 else 0.0 }
    val spanSec = if (onsetTs != null && wakeTs != null && wakeTs > onsetTs) {
        (wakeTs - onsetTs).toDouble()
    } else {
        weightSec
    }
    val intervals = remember(realSegments, spanSec) {
        displaySmoothed(stageIntervalsFromWeights(realSegments, spanSec), STAGE_ROW_SMOOTH_SEC)
    }
    // Tap-to-highlight; keyed on the night's segments so navigating nights clears the selection.
    var selectedStage by remember(realSegments) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        listOf(
            Triple("Awake", s.awake, Palette.sleepAwake),
            Triple("Light", s.light, Palette.sleepLight),
            Triple("Deep", s.deep, Palette.sleepDeep),
            Triple("REM", s.rem, Palette.sleepREM),
        ).forEach { (label, minutes, color) ->
            StageTimelineRow(
                label = label,
                minutes = minutes,
                total = s.total,
                color = color,
                spans = stageRowSpans(intervals, label, spanSec),
                selected = selectedStage == label,
                dimmed = selectedStage != null && selectedStage != label,
                onTap = { selectedStage = if (selectedStage == label) null else label },
            )
        }
        // MotionStrip UNDER the rows on the same timeline. Same inner insets as the rows' tracks so epochs
        // don't skew against the segments.
        Box(modifier = Modifier.padding(horizontal = Metrics.stageRowPadH)) {
            MotionStrip(motionEpochs)
        }
        if (onsetTs != null && wakeTs != null) {
            Box(modifier = Modifier.padding(horizontal = Metrics.stageRowPadH)) {
                ClockLabelRow(onsetTs, wakeTs)
            }
        }
        StageInsight(selectedStage, s)
    }
}

/**
 * One per-stage timeline row: STAGE overline + coloured % + right-aligned duration over a hatched full-night
 * track with the stage's solid segments. The selected row gets a hairlineStrong stroke; when another row is
 * selected this row's segments and % dim to tertiary. One collapsed a11y node.
 */
@Composable
private fun StageTimelineRow(
    label: String,
    minutes: Double,
    total: Double,
    color: Color,
    spans: List<Pair<Float, Float>>,
    selected: Boolean,
    dimmed: Boolean,
    onTap: () -> Unit,
) {
    val percent = if (total > 0.0) (minutes / total * 100.0).roundToInt() else 0
    val segColor = if (dimmed) Palette.textTertiary.copy(alpha = 0.55f) else color
    val pctColor = if (dimmed) Palette.textTertiary else color
    val shape = RoundedCornerShape(Metrics.stageRowCorner)
    Column(
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.textPrimary.copy(alpha = 0.045f))
            .then(if (selected) Modifier.border(1.5.dp, Palette.hairlineStrong, shape) else Modifier)
            .clickable(onClickLabel = "Highlights this stage on the sleep chart", onClick = onTap)
            .padding(horizontal = Metrics.stageRowPadH, vertical = Metrics.stageRowPadV)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: ${durationText(minutes)}, $percent percent of the night"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label.uppercase(Locale.getDefault()),
                style = NoopType.overline,
                color = Palette.textPrimary,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(Metrics.space8))
            Text("$percent%", style = NoopType.captionNumber, color = pctColor, maxLines = 1)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                durationText(minutes),
                style = NoopType.captionNumber,
                color = Palette.textPrimary,
                maxLines = 1,
            )
        }
        StageRowTrack(spans = spans, color = segColor)
    }
}

/**
 * The row's track, drawn in a SINGLE Canvas (PERF: a fragmented night must not become hundreds of
 * composables): a recessed full-night base with faint diagonal hatching (so "no segment here" reads as
 * "elsewhere in the night", not missing data), then the stage's solid rounded segments, width-floored on-canvas.
 */
@Composable
private fun StageRowTrack(spans: List<Pair<Float, Float>>, color: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(Metrics.stageRowTrackHeight)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val trackRadius = CornerRadius(Metrics.stageSegCorner.toPx(), Metrics.stageSegCorner.toPx())
        drawRoundRect(color = Palette.surfaceInset, size = Size(w, h), cornerRadius = trackRadius)
        clipRect(0f, 0f, w, h) {
            val step = 6.dp.toPx()
            var x = -h
            while (x < w) {
                drawLine(
                    color = Palette.hairline,
                    start = Offset(x, h),
                    end = Offset(x + h, 0f),
                    strokeWidth = 1f,
                )
                x += step
            }
        }

        val minW = Metrics.stageSegMinWidth.toPx()
        val segRadius = CornerRadius(Metrics.stageSegCorner.toPx(), Metrics.stageSegCorner.toPx())
        spans.forEach { (fracStart, fracWidth) ->
            if (!fracStart.isFinite() || !fracWidth.isFinite() || fracWidth <= 0f) return@forEach
            val segW = maxOf(w * fracWidth, minW).coerceAtMost(w)
            val x0 = (w * fracStart).coerceIn(0f, w - segW)
            drawRoundRect(
                color = color,
                topLeft = Offset(x0, 0f),
                size = Size(segW, h),
                cornerRadius = segRadius,
            )
        }
    }
}

/**
 * Fixed-height per-stage insight slot under the axis: the selected stage tonight, else a quiet "tap a row"
 * hint. Fixed height so selection never reflows the card.
 */
@Composable
private fun StageInsight(selectedStage: String?, s: Stages) {
    val text = when (selectedStage) {
        "Awake" -> stageInsightLine("Awake", s.awake, s.total)
        "Light" -> stageInsightLine("Light", s.light, s.total)
        "Deep" -> stageInsightLine("Deep", s.deep, s.total)
        "REM" -> stageInsightLine("REM", s.rem, s.total)
        else -> "Tap a stage to highlight it across the night."
    }
    Box(
        modifier = Modifier.fillMaxWidth().height(Metrics.stageInsightHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 2)
    }
}

private fun stageInsightLine(label: String, minutes: Double, total: Double): String {
    val percent = if (total > 0.0) (minutes / total * 100.0).roundToInt() else 0
    return "$label tonight: ${durationText(minutes)} — $percent% of the night."
}

/**
 * The per-epoch MOVEMENT / restlessness strip drawn UNDER the hypnogram, on the same timeline. [epochs] is
 * the main-night GROUP's per-epoch motion magnitudes, self-normalised to the night's own peak so it shows the
 * SHAPE of movement, not an absolute scale. An empty series (older rows with no motionJSON) renders an honest note.
 */
@Composable
private fun MotionStrip(epochs: List<Double>) {
    if (epochs.size < 2) {
        Text(
            "No movement detail for this night.",
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
        return
    }
    val tint = Palette.restColor
    Canvas(modifier = Modifier.fillMaxWidth().height(Metrics.motionStripHeight)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        // Faint baseline so the strip reads as a grounded trace even on a calm night.
        drawLine(
            color = Palette.hairline,
            start = Offset(0f, h - 1f),
            end = Offset(w, h - 1f),
            strokeWidth = 1f,
        )
        val peak = epochs.maxOrNull()?.takeIf { it > 0.0 } ?: return@Canvas
        val n = epochs.size
        val usable = h - 2f
        // One screen point per epoch: x spread evenly across the width, y the magnitude normalised to the
        // night's own peak (baseline at the bottom).
        fun pointAt(i: Int): Offset {
            val x = i.toFloat() / (n - 1).toFloat() * w
            val frac = (epochs[i] / peak).coerceIn(0.0, 1.0).toFloat()
            return Offset(x, h - frac * usable)
        }
        // Filled area under the per-epoch magnitude.
        val area = Path().apply {
            moveTo(0f, h)
            for (i in 0 until n) { val p = pointAt(i); lineTo(p.x, p.y) }
            lineTo(w, h)
            close()
        }
        drawPath(area, color = tint.copy(alpha = 0.22f))
        // The crest line on top of the fill for definition.
        val crest = Path().apply {
            val first = pointAt(0)
            moveTo(first.x, first.y)
            for (i in 1 until n) { val p = pointAt(i); lineTo(p.x, p.y) }
        }
        drawPath(crest, color = tint.copy(alpha = 0.8f), style = Stroke(width = 1.5f))
    }
}

/** Map a stage name to its design-system sleep tone (case-insensitive), local to this screen. */
private fun stageColorFor(name: String): Color = when (name.trim().lowercase()) {
    "deep" -> Palette.sleepDeep
    "rem" -> Palette.sleepREM
    "light" -> Palette.sleepLight
    "awake", "wake" -> Palette.sleepAwake
    else -> Palette.sleepLight
}

/**
 * "Asleep / Woke" — the fell-asleep and woke clock times for the navigated night, each with a moon / sun
 * glyph. Sits between the night-nav header and the stage card so the two times people glance for first are
 * always visible, not truncated in the header caption. One combined TalkBack element.
 */

internal data class PersistedSegment(val start: Long, val end: Long, val stage: String)

/**
 * Parse the verbatim per-epoch segments array the on-device stager persists ([{"start","end","stage"}], unix
 * seconds, stage ∈ wake|light|deep|rem). Returns null for the imported minutes shapes and any malformed
 * input, so callers keep the synthesized fallback.
 */
internal fun parsePersistedSegments(json: String?): List<PersistedSegment>? {
    if (json.isNullOrBlank()) return null
    val trimmed = json.trim()
    if (!trimmed.startsWith("[")) return null
    return runCatching {
        val arr = JSONArray(trimmed)
        val out = ArrayList<PersistedSegment>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return@runCatching null
            val start = o.optLong("start", Long.MIN_VALUE)
            val end = o.optLong("end", Long.MIN_VALUE)
            val stage = o.optString("stage", "")
            if (start == Long.MIN_VALUE || end <= start || stage.isEmpty()) return@runCatching null
            out.add(PersistedSegment(start, end, stage))
        }
        out.takeIf { it.size >= 2 }
    }.getOrNull()
}

// MARK: - Stage timeline logic (pure, unit-tested)

/** One contiguous run of a single sleep stage, in seconds from the night's onset. */
internal data class StageInterval(val stage: String, val startSec: Double, val endSec: Double) {
    val durationSec: Double get() = endSec - startSec
}

/**
 * Reconstruct absolute (stage, startSec, endSec) intervals from the hero's ordered `realSegments` weight
 * pairs (name, minutes) by walking cumulative fractions across [spanSec]. Non-finite / non-positive weights
 * are skipped. Returns [] when nothing is drawable.
 */
internal fun stageIntervalsFromWeights(
    segments: List<Pair<String, Float>>,
    spanSec: Double,
): List<StageInterval> {
    if (segments.isEmpty() || !spanSec.isFinite() || spanSec <= 0.0) return emptyList()
    val weights = segments.map { (_, wt) -> if (wt.isFinite() && wt > 0f) wt.toDouble() else 0.0 }
    val total = weights.sum()
    if (total <= 0.0) return emptyList()
    val out = ArrayList<StageInterval>(segments.size)
    var cum = 0.0
    segments.forEachIndexed { i, (name, _) ->
        val w = weights[i]
        if (w <= 0.0) return@forEachIndexed
        val start = spanSec * (cum / total)
        cum += w
        out.add(StageInterval(name, start, spanSec * (cum / total)))
    }
    return out
}

/**
 * Display-time smoothing (WHOOP-style). The on-device stager emits 30s-epoch runs, so a real night arrives as
 * 60–100 fragments; brief flickers are absorbed into their surroundings AT DISPLAY TIME. Render-only: totals
 * and stored data are computed from the raw segments elsewhere. Pass minDurationSec = 0 for raw.
 */
internal fun displaySmoothed(
    intervals: List<StageInterval>,
    minDurationSec: Double,
): List<StageInterval> {
    // Guard ONLY on count. minDurationSec ≤ 0 ("raw") must still fall through to coalesce() — the coalesced
    // timeline, not the un-merged epoch fragments. With minDurationSec = 0 the absorb loop breaks right after
    // the first coalesce.
    if (intervals.size <= 2) return intervals   // guard count > 2

    // Coalesce adjacent same-stage runs (also bridges the zero-length seams between epochs).
    fun coalesce(ivs: List<StageInterval>): MutableList<StageInterval> {
        val out = mutableListOf<StageInterval>()
        for (iv in ivs) {
            val last = out.lastOrNull()
            if (last != null && last.stage == iv.stage && iv.startSec - last.endSec < 1.0) {
                out[out.size - 1] = StageInterval(last.stage, last.startSec, iv.endSec)
            } else {
                out.add(iv)
            }
        }
        return out
    }

    var ivs = coalesce(intervals)
    // Repeatedly absorb the shortest sub-threshold fragment into its longer neighbour,
    // re-coalescing after each pass, until every remaining block clears the threshold.
    while (ivs.size > 1) {
        val idx = ivs.indices
            .filter { ivs[it].durationSec < minDurationSec }
            .minByOrNull { ivs[it].durationSec } ?: break
        val victim = ivs[idx]
        val prev = if (idx > 0) ivs[idx - 1] else null
        val next = if (idx < ivs.size - 1) ivs[idx + 1] else null
        when {
            prev != null && next != null ->
                // Absorb into the longer neighbour so the dominant surrounding stage wins.
                if (prev.durationSec >= next.durationSec) {
                    ivs[idx - 1] = StageInterval(prev.stage, prev.startSec, victim.endSec)
                } else {
                    ivs[idx + 1] = StageInterval(next.stage, victim.startSec, next.endSec)
                }
            prev != null -> ivs[idx - 1] = StageInterval(prev.stage, prev.startSec, victim.endSec)
            next != null -> ivs[idx + 1] = StageInterval(next.stage, victim.startSec, next.endSec)
            else -> break
        }
        ivs.removeAt(idx)
        ivs = coalesce(ivs)
    }
    return ivs
}

/** Canonical stage key: trims, lowercases, and folds the "wake"/"awake" alias (stageColorFor parity). */
internal fun canonicalStage(name: String): String {
    val n = name.trim().lowercase()
    return if (n == "wake") "awake" else n
}

/**
 * The (startFraction, widthFraction) spans of [rowStage]'s intervals within the night — one entry
 * per solid segment in that stage's timeline row track. Fractions of [spanSec]; the draw side
 * applies the min-width floor and canvas clamping.
 */
internal fun stageRowSpans(
    intervals: List<StageInterval>,
    rowStage: String,
    spanSec: Double,
): List<Pair<Float, Float>> {
    if (spanSec <= 0.0 || !spanSec.isFinite()) return emptyList()
    val key = canonicalStage(rowStage)
    return intervals
        .filter { canonicalStage(it.stage) == key }
        .map { iv -> (iv.startSec / spanSec).toFloat() to (iv.durationSec / spanSec).toFloat() }
}

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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.RustScores
import java.util.Locale
import kotlin.math.roundToInt

/** The four stage rows, in the order the reference lists them. */
private val STAGE_ORDER = listOf("Awake", "Light", "Deep", "REM")

/** What a share reads as on a night with nothing to split. */
private const val NO_SHARE = "--"

/**
 * The night's four stages as whole percentages that sum to exactly 100, keyed by row label.
 * whoop-rs apportions them; a night with no minutes yields an empty map, so no row prints a share
 * the night never had.
 */
internal fun stagePercentByLabel(stages: Stages): Map<String, Int> {
    val split = RustScores.wholePercentages(STAGE_ORDER.map { stageMinutes(stages, it) }) ?: return emptyMap()
    return STAGE_ORDER.zip(split).toMap()
}

/**
 * HOURS OF SLEEP — the night's asleep total, its heart-rate trace with the selected stage banded onto
 * it, then the stage breakdown: a duration header, one tappable row per stage over the shared
 * onset-to-wake axis, the movement strip, and the restorative total.
 *
 * [typicalByStage] carries the personal mean minutes per stage; each row marks it on its own track so a
 * night reads against the wearer's own habit. A stage with no learned mean simply has no marker.
 */
@Composable
internal fun SleepStagesCard(
    stages: Stages,
    realSegments: List<Pair<String, Float>>?,
    typicalByStage: Map<String, Double?>,
    typicalAsleepMin: Double?,
    inBedMin: Double,
    efficiencyText: String,
    onsetTs: Long?,
    wakeTs: Long?,
    motionEpochs: List<Double>,
    hrPoints: List<TimelinePoint>,
) {
    // Real per-epoch runs only when there are transitions to draw; a single run has none, and an
    // invented architecture has no genuine timeline to band the heart rate against.
    val real = realSegments?.takeIf { it.size >= 2 }
    var selectedStage by remember(real) { mutableStateOf<String?>(null) }
    // ONE apportionment for the whole card: whoop-rs splits the night into whole percentages that
    // sum to 100, and the rows and the insight line below both read that one result.
    val stagePercents = remember(stages) { stagePercentByLabel(stages) }

    NoopCard(padding = Metrics.cardPadding, tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
            SleepStagesHeadline(stages.asleep, typicalAsleepMin)
            SleepHrChart(
                points = hrPoints,
                onsetTs = onsetTs,
                wakeTs = wakeTs,
                realSegments = real.orEmpty(),
                selectedStage = if (real == null) null else selectedStage,
            )
            CardHairline()
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypicalMarkKey()
                Spacer(Modifier.width(Metrics.space6))
                Text(
                    stringResource(R.string.sleep_stages_typical),
                    style = NoopType.overline,
                    color = Palette.textTertiary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.sleep_stages_duration),
                    style = NoopType.overline,
                    color = Palette.textTertiary,
                )
                Spacer(Modifier.width(Metrics.space8))
                Text(durationText(inBedMin), style = NoopType.captionNumber, color = Palette.textPrimary)
            }
            if (real == null) {
                Text(
                    stringResource(R.string.sleep_stages_no_timeline),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            SleepStageRows(
                stages = stages,
                stagePercents = stagePercents,
                realSegments = real,
                typicalByStage = typicalByStage,
                onsetTs = onsetTs,
                wakeTs = wakeTs,
                motionEpochs = motionEpochs,
                selectedStage = selectedStage,
                onSelectStage = { selectedStage = it },
            )
            CardHairline()
            SleepStagesFooter(stages, efficiencyText)
        }
    }
}

/** The card's lead: the asleep total, and the personal mean under it as the comparison the reference draws. */
@Composable
private fun SleepStagesHeadline(asleepMin: Double, typicalAsleepMin: Double?) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
        Text(
            stringResource(R.string.sleep_stages_hours_of_sleep),
            style = NoopType.overline,
            color = Palette.textTertiary,
        )
        Text(durationText(asleepMin), style = NoopType.tileValueLarge, color = Palette.textPrimary, maxLines = 1)
        Text(
            typicalAsleepMin?.let { stringResource(R.string.sleep_typical_value, durationText(it)) }
                ?: stringResource(R.string.sleep_no_typical_yet),
            style = NoopType.footnote,
            color = Palette.textSecondary,
        )
    }
}

/** The four stage rows over the shared axis, with the movement strip and clock labels under them. */
@Composable
private fun SleepStageRows(
    stages: Stages,
    stagePercents: Map<String, Int>,
    realSegments: List<Pair<String, Float>>?,
    typicalByStage: Map<String, Double?>,
    onsetTs: Long?,
    wakeTs: Long?,
    motionEpochs: List<Double>,
    selectedStage: String?,
    onSelectStage: (String?) -> Unit,
) {
    val spanSec = nightSpanSec(realSegments.orEmpty(), onsetTs, wakeTs)
    val intervals = remember(realSegments, spanSec) {
        nightStageIntervals(realSegments.orEmpty(), spanSec)
    }
    // Display smoothing folds sub-threshold runs into their neighbours, which erases a stage that only
    // ever came in brief bursts. That row falls back to its own unsmoothed runs, so a stage with
    // minutes on its header is never drawn as a night it never happened in.
    val rawIntervals = remember(realSegments, spanSec) {
        stageIntervalsFromWeights(realSegments.orEmpty(), spanSec)
    }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        STAGE_ORDER.forEach { label ->
            val minutes = stageMinutes(stages, label)
            val spans = if (realSegments == null) emptyList() else {
                stageRowSpans(intervals, label, spanSec)
                    .ifEmpty { stageRowSpans(rawIntervals, label, spanSec) }
            }
            // What the track actually draws, in minutes. The header's figure is the day's stored stage
            // total; the runs are the session's own timeline, and the two can disagree.
            val drawnMin = if (realSegments == null) null
            else spans.sumOf { (_, widthFrac) -> widthFrac.toDouble() * spanSec } / 60.0
            SleepStageRow(
                label = label,
                minutes = minutes,
                percent = stagePercents[label],
                total = stages.total,
                color = stageRowColor(label),
                spans = spans,
                drawnMin = drawnMin,
                typicalMin = typicalByStage[label],
                selected = selectedStage == label,
                dimmed = selectedStage != null && selectedStage != label,
                onTap = { onSelectStage(if (selectedStage == label) null else label) },
            )
        }
        Box(modifier = Modifier.padding(horizontal = Metrics.stageRowPadH)) {
            SleepMotionStrip(motionEpochs)
        }
        if (onsetTs != null && wakeTs != null) {
            Box(modifier = Modifier.padding(horizontal = Metrics.stageRowPadH)) {
                ClockLabelRow(onsetTs, wakeTs)
            }
        }
        SleepStageInsight(selectedStage, stages, stagePercents)
    }
}

/**
 * One stage row: a selection dot, the stage name, its share of the night, the duration, and the hatched
 * full-night track carrying that stage's runs plus a dashed mark at the personal typical.
 *
 * [percent] is the card's one apportionment, not this row's own rounding; null means the night had
 * nothing to split and the row says so rather than printing a zero it did not measure.
 */
@Composable
private fun SleepStageRow(
    label: String,
    minutes: Double,
    percent: Int?,
    total: Double,
    color: Color,
    spans: List<Pair<Float, Float>>,
    drawnMin: Double?,
    typicalMin: Double?,
    selected: Boolean,
    dimmed: Boolean,
    onTap: () -> Unit,
) {
    val segColor = if (dimmed) Palette.textTertiary.copy(alpha = 0.55f) else color
    val pctColor = if (dimmed) Palette.textTertiary else color
    val shape = RoundedCornerShape(Metrics.stageRowCorner)
    val typicalFrac = typicalMin
        ?.takeIf { it > 0.0 && total > 0.0 }
        ?.let { (it / total).coerceIn(0.0, 1.0).toFloat() }
    // [label] stays the row's identifier; this is the name it is READ and PRINTED under.
    val stageName = stringResource(
        when (label) {
            "Light" -> R.string.sleep_stage_light
            "Deep" -> R.string.sleep_stage_deep
            "REM" -> R.string.sleep_stage_rem
            else -> R.string.sleep_stage_awake
        },
    )
    val rowClickLabel = stringResource(R.string.sleep_stage_row_click_label)
    val rowDescription = percent
        ?.let { stringResource(R.string.sleep_stage_row_a11y, stageName, durationText(minutes), it) }
        ?: stringResource(R.string.sleep_stage_row_a11y_no_share, stageName, durationText(minutes))
    Column(
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.textPrimary.copy(alpha = 0.045f))
            .then(if (selected) Modifier.border(1.5.dp, Palette.hairlineStrong, shape) else Modifier)
            .clickable(onClickLabel = rowClickLabel, onClick = onTap)
            .padding(horizontal = Metrics.stageRowPadH, vertical = Metrics.stageRowPadV)
            .semantics(mergeDescendants = true) { contentDescription = rowDescription },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SleepStageDot(color = color, filled = selected)
            Spacer(modifier = Modifier.width(Metrics.space8))
            Text(
                stageName.uppercase(Locale.getDefault()),
                style = NoopType.overline,
                color = Palette.textPrimary,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(Metrics.space8))
            Text(
                percent?.let { "$it%" } ?: NO_SHARE,
                style = NoopType.captionNumber,
                color = pctColor,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                durationText(minutes),
                style = NoopType.captionNumber,
                color = Palette.textPrimary,
                maxLines = 1,
            )
        }
        SleepStageTrack(spans = spans, color = segColor, typicalFrac = typicalFrac)
        // An empty-looking track beside a real figure is the two sources disagreeing, so the row says so
        // rather than reading as a stage the night never had.
        if (drawnMin != null && drawnMin.roundToInt() < minutes.roundToInt()) {
            Text(
                stringResource(R.string.sleep_stage_row_timeline_holds, durationText(drawnMin)),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/** The row's selection dot: an outlined circle that fills when the row owns the highlight. */
@Composable
private fun SleepStageDot(color: Color, filled: Boolean) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(50))
            .background(if (filled) color else Color.Transparent)
            .border(1.5.dp, if (filled) color else Palette.textTertiary, RoundedCornerShape(50)),
    )
}

/**
 * The row's track, drawn in a single Canvas so a fragmented night doesn't become hundreds of
 * composables: a recessed full-night base with faint hatching (so "no segment here" reads as
 * "elsewhere in the night"), the stage's solid runs, then a dashed mark at the personal typical.
 */
@Composable
private fun SleepStageTrack(spans: List<Pair<Float, Float>>, color: Color, typicalFrac: Float?) {
    val hatch = Palette.hairline
    val base = Palette.surfaceInset
    val markColor = Palette.textPrimary
    Canvas(modifier = Modifier.fillMaxWidth().height(Metrics.stageRowTrackHeight)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val trackRadius = CornerRadius(Metrics.stageSegCorner.toPx(), Metrics.stageSegCorner.toPx())
        drawRoundRect(color = base, size = Size(w, h), cornerRadius = trackRadius)
        clipRect(0f, 0f, w, h) {
            val step = 6.dp.toPx()
            var x = -h
            while (x < w) {
                drawLine(color = hatch, start = Offset(x, h), end = Offset(x + h, 0f), strokeWidth = 1f)
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

        // The marker only says something where there is track on both sides of it. Pinned to an end it
        // reads as an edge of the track rather than as a comparison.
        val inset = Metrics.space6.toPx()
        val markX = typicalFrac?.takeIf { it.isFinite() }?.let { w * it }
        if (markX != null && markX > inset && markX < w - inset) {
            val dash = Metrics.space4.toPx() / 2f
            drawLine(
                color = markColor,
                start = Offset(markX, 0f),
                end = Offset(markX, h),
                strokeWidth = Metrics.divider.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f),
            )
        }
    }
}

// The dashed swatch beside the word TYPICAL: the same colour, weight and dash the stage tracks mark
// the personal typical with, so the header row reads as that mark's key rather than a missing value.
@Composable
private fun TypicalMarkKey() {
    Canvas(modifier = Modifier.size(width = Metrics.space16, height = Metrics.space8)) {
        val dash = Metrics.space4.toPx() / 2f
        drawLine(
            color = Palette.textPrimary,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = Metrics.divider.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash), 0f),
        )
    }
}

/**
 * The per-epoch movement strip under the rows, on the same timeline. [epochs] is the main-night group's
 * motion magnitudes, self-normalised to the night's own peak so it shows the SHAPE of movement rather
 * than an absolute scale. Too few epochs renders an honest note.
 */
@Composable
private fun SleepMotionStrip(epochs: List<Double>) {
    if (epochs.size < 2) {
        Text(
            stringResource(R.string.sleep_motion_none),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
        return
    }
    val tint = Palette.restColor
    val baselineColor = Palette.hairline
    Canvas(modifier = Modifier.fillMaxWidth().height(Metrics.motionStripHeight)) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        drawLine(color = baselineColor, start = Offset(0f, h - 1f), end = Offset(w, h - 1f), strokeWidth = 1f)
        val peak = epochs.maxOrNull()?.takeIf { it > 0.0 } ?: return@Canvas
        val n = epochs.size
        val usable = h - 2f
        fun pointAt(i: Int): Offset {
            val x = i.toFloat() / (n - 1).toFloat() * w
            val frac = (epochs[i] / peak).coerceIn(0.0, 1.0).toFloat()
            return Offset(x, h - frac * usable)
        }
        val area = Path().apply {
            moveTo(0f, h)
            for (i in 0 until n) { val p = pointAt(i); lineTo(p.x, p.y) }
            lineTo(w, h)
            close()
        }
        drawPath(area, color = tint.copy(alpha = 0.22f))
        val crest = Path().apply {
            val first = pointAt(0)
            moveTo(first.x, first.y)
            for (i in 1 until n) { val p = pointAt(i); lineTo(p.x, p.y) }
        }
        drawPath(crest, color = tint.copy(alpha = 0.8f), style = Stroke(width = 1.5f))
    }
}

/** Fixed-height insight slot under the axis, so selecting a stage never reflows the card. */
@Composable
private fun SleepStageInsight(selectedStage: String?, stages: Stages, stagePercents: Map<String, Int>) {
    val text = if (selectedStage == null) {
        stringResource(R.string.sleep_stage_insight_hint)
    } else {
        // The rows' own apportionment, so the sentence can never disagree with the row above it.
        val minutes = durationText(stageMinutes(stages, selectedStage))
        val stageName = stringResource(
            when (selectedStage) {
                "Light" -> R.string.sleep_stage_light
                "Deep" -> R.string.sleep_stage_deep
                "REM" -> R.string.sleep_stage_rem
                else -> R.string.sleep_stage_awake
            },
        )
        stagePercents[selectedStage]
            ?.let { stringResource(R.string.sleep_stage_insight_pct, stageName, minutes, it) }
            ?: stringResource(R.string.sleep_stage_insight, stageName, minutes)
    }
    Box(
        modifier = Modifier.fillMaxWidth().height(Metrics.stageInsightHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 2)
    }
}

/** RESTORATIVE SLEEP and the night's efficiency, the two summaries the reference closes the card with. */
@Composable
private fun SleepStagesFooter(stages: Stages, efficiencyText: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(Metrics.space12)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.sleepREM),
        )
        Spacer(Modifier.width(Metrics.space10))
        Text(stringResource(R.string.sleep_restorative), style = NoopType.overline, color = Palette.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            durationText(stages.deep + stages.rem),
            style = NoopType.captionNumber,
            color = Palette.textPrimary,
        )
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(Metrics.space12)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.sleepAwake),
        )
        Spacer(Modifier.width(Metrics.space10))
        Text(stringResource(R.string.sleep_efficiency), style = NoopType.overline, color = Palette.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(efficiencyText, style = NoopType.captionNumber, color = Palette.textPrimary)
    }
}

/** The row's minutes for [label], which is one of [STAGE_ORDER]. */
internal fun stageMinutes(stages: Stages, label: String): Double = when (label) {
    "Awake" -> stages.awake
    "Light" -> stages.light
    "Deep" -> stages.deep
    else -> stages.rem
}

/** The row's colour for [label], which is one of [STAGE_ORDER]. */
internal fun stageRowColor(label: String): Color = when (label) {
    "Awake" -> Palette.sleepAwake
    "Light" -> Palette.sleepLight
    "Deep" -> Palette.sleepDeep
    else -> Palette.sleepREM
}

package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.RustScores

private val SLEEP_VESSEL_DIAMETER: Dp = 184.dp

/** How many tiers the driver strip splits 0-100 into, read once from whoop-rs. */
private val DRIVER_TIERS: Int by lazy { RustScores.sleepDriverTiers }

/**
 * One driver under the sleep-performance score. [percent] is 0-100 and may be absent, which draws an
 * empty strip and an em dash rather than a zero. [higherIsBetter] is false for a driver whose 0 is the
 * good end (high sleep stress), which flips only which tier the strip lights, never the value.
 */
internal data class SleepDriver(
    val label: String,
    val percent: Double?,
    val higherIsBetter: Boolean = true,
)

/**
 * SLEEP PERFORMANCE — the night's score in a ring, the drivers behind it as labelled strips, and the
 * tier legend those strips are read against. A [SourceBadge] states whether the score is WHOOP's
 * imported figure or NOOP's own.
 *
 * The strip marks which THIRD of 0-100 a driver falls in; the thirds and their words are a reading
 * scale, not a threshold, so nothing here decides a number.
 */
@Composable
internal fun SleepPerformanceCard(score: Double?, asleepMin: Double?, drivers: List<SleepDriver>, source: String) {
    NoopCard(padding = Metrics.space24, tint = Palette.restColor) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space16),
        ) {
            if (score != null) {
                SleepScoreVessel(score)
                Text(
                    stringResource(R.string.sleep_performance),
                    style = NoopType.overline,
                    color = Palette.textSecondary,
                )
            } else {
                // No 0-100 score: lead with hours slept, the same count-up the scored hero rolls.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Metrics.space4),
                    modifier = Modifier.padding(vertical = Metrics.space16),
                ) {
                    CountUpText(
                        value = asleepMin ?: 0.0,
                        format = { durationText(it) },
                        style = NoopType.number(46f),
                        color = Palette.restBright,
                    )
                    Text(
                        stringResource(R.string.sleep_asleep_last_night),
                        style = NoopType.overline,
                        color = Palette.textSecondary,
                    )
                }
            }
            if (drivers.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.space12),
                ) {
                    drivers.forEachIndexed { i, driver ->
                        if (i > 0) CardHairline()
                        SleepDriverRow(driver)
                    }
                }
                SleepDriverLegend()
            }
            SourceBadge(text = source, tint = Palette.restColor)
        }
    }
}

/** The score as a ring in the rest tint. The ring owns the count-up; the fill key keeps a scroll that
 *  recycles it from replaying the fill. */
@Composable
private fun SleepScoreVessel(score: Double) {
    GlowRing(
        fraction = RustScores.rampPositionScore(score).toFloat(),
        value = score,
        color = Palette.restColor,
        diameter = SLEEP_VESSEL_DIAMETER,
        lineWidth = SLEEP_VESSEL_DIAMETER * RING_STROKE_FRACTION,
        fillKey = "sleep.performance",
    )
}

/** One driver: uppercase label, the tier strip, and the value right-aligned. */
@Composable
private fun SleepDriverRow(driver: SleepDriver) {
    val pct = driver.percent
    val rowDescription = stringResource(R.string.sleep_driver_a11y, driver.label, pctValue(pct))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = rowDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            driver.label.uppercase(),
            style = NoopType.overline,
            color = Palette.textPrimary,
            maxLines = 1,
            // Ellipsis, not the default Clip: a label too long for the row was being cut mid-word with
            // nothing to show it had been, which reads as a shorter label rather than a truncated one.
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        SleepDriverStrip(pct, driver.higherIsBetter)
        Text(
            pctValue(pct),
            style = NoopType.captionNumber,
            color = Palette.textPrimary,
            maxLines = 1,
            modifier = Modifier.width(52.dp).padding(start = Metrics.space8),
        )
    }
}

/**
 * The driver's own 0-100 as a filled length, in the colour of the tier it lands in, over a track the
 * tier boundaries are ticked on. Lighting a whole third told the reader only which third: 92, 90 and 72
 * all lit the same segment and read as three full bars.
 */
@Composable
private fun SleepDriverStrip(percent: Double?, higherIsBetter: Boolean) {
    val fill = percent?.let { RustScores.rampPositionScore(it).toFloat() }
    val tint = percent?.let { driverTierColor(RustScores.sleepDriverTierLit(it, higherIsBetter)) }
        ?: Palette.textTertiary
    val tickColor = Palette.hairlineStrong
    Box(
        modifier = Modifier
            .width(96.dp)
            .height(Metrics.space6)
            .clip(RoundedCornerShape(Metrics.space4))
            .background(Palette.surfaceInset)
            .drawBehind {
                if (fill != null && fill > 0f) {
                    drawRect(color = tint, size = Size(size.width * fill, size.height))
                }
                for (tier in 1 until DRIVER_TIERS) {
                    val x = size.width * tier / DRIVER_TIERS
                    drawLine(
                        color = tickColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = Metrics.divider.toPx(),
                    )
                }
            },
    )
}

/** The tier's colour: the low, middle and high of the app's own 0-100 ramp, so the three swatches read
 *  as one scale. A text token in the middle drew that swatch in the colour of the word beside it. */
private fun driverTierColor(tier: Int): Color =
    Palette.sample(Palette.recoveryStops, RustScores.sleepDriverTierPosition(tier).toFloat())

@Composable
private fun driverTierWord(tier: Int): String = stringResource(
    when (tier) {
        0 -> R.string.sleep_tier_poor
        1 -> R.string.sleep_tier_sufficient
        else -> R.string.sleep_tier_optimal
    },
)

/** The legend the strips are read against: one swatch and word per tier. */
@Composable
private fun SleepDriverLegend() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .background(Palette.surfaceInset)
            .padding(horizontal = Metrics.space12, vertical = Metrics.space8),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(DRIVER_TIERS) { tier ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
            ) {
                Box(
                    modifier = Modifier
                        .width(Metrics.space16)
                        .height(Metrics.space4)
                        .clip(RoundedCornerShape(Metrics.space4))
                        .background(driverTierColor(tier)),
                )
                Text(driverTierWord(tier), style = NoopType.footnote, color = Palette.textSecondary)
            }
        }
    }
}

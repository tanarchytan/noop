package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// The card the sleep-performance vessel floats on: a translucent near-black fill so it reads OVER the
// day-of-sky with the vessel and the white count-up number crisp; radius 26 plus a white hairline give
// the frosted edge.
private val SLEEP_HERO_FILL: Color = Color(red = 13f / 255f, green = 14f / 255f, blue = 20f / 255f, alpha = 0.80f)
private val SLEEP_HERO_RADIUS: Dp = 26.dp
private val SLEEP_VESSEL_DIAMETER: Dp = 184.dp

/** How many tiers the driver strip splits 0-100 into, and the width of one tier. */
private const val DRIVER_TIERS = 3

/**
 * One driver under the sleep-performance score. [percent] is 0-100 and may be absent, which draws an
 * empty strip and an em dash rather than a zero.
 */
internal data class SleepDriver(val label: String, val percent: Double?)

/**
 * SLEEP PERFORMANCE — the night's score in a liquid vessel, the drivers behind it as labelled strips,
 * and the tier legend those strips are read against. A [SourceBadge] states whether the score is
 * WHOOP's imported figure or NOOP's own.
 *
 * The strip marks which THIRD of 0-100 a driver falls in; the thirds and their words are a reading
 * scale, not a threshold, so nothing here decides a number.
 */
@Composable
internal fun SleepPerformanceCard(score: Double?, asleepMin: Double?, drivers: List<SleepDriver>, source: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(SLEEP_HERO_RADIUS))
            .background(SLEEP_HERO_FILL.copy(alpha = SLEEP_HERO_FILL.alpha * CardAppearance.opacity))
            .border(
                1.dp,
                Color.White.copy(alpha = 0.11f * CardAppearance.opacity),
                RoundedCornerShape(SLEEP_HERO_RADIUS),
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Metrics.space24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space16),
        ) {
            if (score != null) {
                SleepScoreVessel(score)
                Text("SLEEP PERFORMANCE", style = NoopType.overline, color = Palette.textSecondary)
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
                    Text("ASLEEP LAST NIGHT", style = NoopType.overline, color = Palette.textSecondary)
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

/** The score as a liquid vessel with the value counting up over it. The number is hit-transparent so a
 *  tap falls through to the vessel, which owns its own splash and haptic. */
@Composable
private fun SleepScoreVessel(score: Double) {
    Box(modifier = Modifier.size(SLEEP_VESSEL_DIAMETER), contentAlignment = Alignment.Center) {
        LiquidVessel(
            value = (score / 100.0).coerceIn(0.0, 1.0),
            tint = Palette.restColor,
            animated = true,
            modifier = Modifier.size(SLEEP_VESSEL_DIAMETER),
        )
        CountUpText(
            value = score,
            format = { it.roundToInt().toString() },
            style = NoopType.number(50f, weight = FontWeight.Bold)
                .copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 1f), blurRadius = 6f)),
            color = Color.White,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** One driver: uppercase label, the tier strip, and the value right-aligned. */
@Composable
private fun SleepDriverRow(driver: SleepDriver) {
    val pct = driver.percent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${driver.label}: ${pctValue(pct)}"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            driver.label.uppercase(),
            style = NoopType.overline,
            color = Palette.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        SleepDriverStrip(pct)
        Text(
            pctValue(pct),
            style = NoopType.captionNumber,
            color = Palette.textPrimary,
            maxLines = 1,
            modifier = Modifier.width(52.dp).padding(start = Metrics.space8),
        )
    }
}

/** Three equal tiers of the 0-100 range; the one the value lands in takes that tier's colour. */
@Composable
private fun SleepDriverStrip(percent: Double?) {
    val filled = percent?.let { driverTierIndex(it) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Metrics.space4),
        modifier = Modifier.width(96.dp),
    ) {
        repeat(DRIVER_TIERS) { tier ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Metrics.space6)
                    .clip(RoundedCornerShape(Metrics.space4))
                    .background(if (tier == filled) driverTierColor(tier) else Palette.surfaceInset),
            )
        }
    }
}

/** Which third of 0-100 [percent] falls in, clamped to the top tier at 100. */
internal fun driverTierIndex(percent: Double): Int =
    ((percent / 100.0) * DRIVER_TIERS).toInt().coerceIn(0, DRIVER_TIERS - 1)

private fun driverTierColor(tier: Int): Color = when (tier) {
    0 -> Palette.statusWarning
    1 -> Palette.textSecondary
    else -> Palette.statusPositive
}

private fun driverTierWord(tier: Int): String = when (tier) {
    0 -> "Poor"
    1 -> "Sufficient"
    else -> "Optimal"
}

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

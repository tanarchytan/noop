package com.noop.ui.whoop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.noop.analytics.RangeReport
import com.noop.analytics.ReportMetric
import com.noop.ui.EffortScale
import com.noop.ui.GlowRing
import com.noop.ui.InsetChartPlaceholder
import com.noop.ui.Metrics
import com.noop.ui.NoopCard
import com.noop.ui.NoopStatRow
import com.noop.ui.NoopType
import com.noop.ui.Overline
import com.noop.ui.Palette
import com.noop.ui.ReportRange
import com.noop.ui.RowDivider
import com.noop.ui.SegmentedPillControl
import com.noop.ui.UnitFormatter
import com.noop.ui.UnitPrefs
import com.noop.ui.durationText
import com.noop.ui.formatLineValue

// MARK: - Data highlights
//
// The range control, the range's peak rings and the notable-stats list. Every number here is an
// extreme RangeReportEngine already computed; this file formats and places them, nothing more.

/** The windows the page offers: trailing 30 / 90 days, or every recorded day. */
private val PROFILE_RANGES = listOf(ReportRange.Days30, ReportRange.Days90, ReportRange.All)

/** The stored 0-100 axis both Charge and Effort sit on. Used ONLY to turn a score into an arc fraction. */
private const val SCORE_AXIS_MAX = 100.0

/** Ring geometry: the tile-height token, with a stroke thin enough that a 4-character value fits. */
private val PROFILE_RING_DIAMETER = Metrics.tileHeight
private const val PROFILE_RING_STROKE_FRACTION = 0.08f

/** One notable-stat line: an extreme the engine computed, already written out for display. */
internal data class WhoopProfileStat(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val unit: String?,
)

/** One hero ring: an extreme, the arc fraction that draws it, its ramp colour and its formatter. */
private data class ProfileRing(
    val title: String,
    val fraction: Float,
    val value: Double,
    val color: Color,
    val format: (Double) -> String,
)

/**
 * The Data Highlights card. [report] is already built for [range]; the caller owns the range so the
 * report is computed once per page rather than once per card.
 */
@Composable
internal fun WhoopProfileHighlightsCard(
    report: RangeReport,
    range: ReportRange,
    onRangeChange: (ReportRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val effortScale = UnitPrefs.effortScale(LocalContext.current)
    val rings = profileRings(report, effortScale)
    val stats = whoopProfileNotableStats(report)

    NoopCard(modifier = modifier, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            SegmentedPillControl(
                items = PROFILE_RANGES,
                selection = range,
                label = { it.label },
                onSelect = onRangeChange,
            )
            Text(range.longName, style = NoopType.footnote, color = Palette.textTertiary)

            if (rings.isEmpty() && stats.isEmpty()) {
                InsetChartPlaceholder("No readings in this range yet")
            } else {
                if (rings.isNotEmpty()) ProfileRingRow(rings)
                if (stats.isNotEmpty()) {
                    ProfileRuleLabel("Notable stats")
                    ProfileStatList(stats)
                }
            }
        }
    }
}

/**
 * The peak rings for the range. A metric with no reading in range has no stat and no ring. Sleep
 * performance carries no engine extreme, so it has no honest ring here and is absent.
 */
private fun profileRings(report: RangeReport, scale: EffortScale): List<ProfileRing> {
    val rings = mutableListOf<ProfileRing>()
    report.stat(ReportMetric.RECOVERY)?.let { stat ->
        val peak = stat.max.value
        rings += ProfileRing(
            title = "Peak ${ReportMetric.RECOVERY.label}",
            fraction = (peak / SCORE_AXIS_MAX).toFloat(),
            value = peak,
            color = Palette.recoveryColor(peak),
            format = { formatLineValue(it) },
        )
    }
    report.stat(ReportMetric.STRAIN)?.let { stat ->
        val peak = stat.max.value
        rings += ProfileRing(
            title = "Max ${ReportMetric.STRAIN.label}",
            fraction = (peak / SCORE_AXIS_MAX).toFloat(),
            value = peak,
            // Stored on the 0-100 axis; the display scale only changes how the number is written.
            color = Palette.strainColor(peak),
            format = { UnitFormatter.effortDisplay(it, scale) },
        )
    }
    return rings
}

/**
 * The notable-stats rows, in WHOOP's order, for the extremes the engine carries. A metric with no
 * reading in range is simply absent.
 */
internal fun whoopProfileNotableStats(report: RangeReport): List<WhoopProfileStat> {
    val stats = mutableListOf<WhoopProfileStat>()
    report.stat(ReportMetric.RESTING_HR)?.let { stat ->
        stats += profileExtremeStat(Icons.Outlined.MonitorHeart, "Lowest", stat.metric, stat.min.value)
        stats += profileExtremeStat(Icons.Outlined.MonitorHeart, "Highest", stat.metric, stat.max.value)
    }
    report.stat(ReportMetric.HRV)?.let { stat ->
        stats += profileExtremeStat(Icons.Filled.GraphicEq, "Lowest", stat.metric, stat.min.value)
        stats += profileExtremeStat(Icons.Filled.GraphicEq, "Highest", stat.metric, stat.max.value)
    }
    report.stat(ReportMetric.SLEEP_HOURS)?.let { stat ->
        stats += WhoopProfileStat(
            icon = Icons.Filled.Bedtime,
            label = "Longest ${stat.metric.label}",
            value = profileSleepText(stat.max.value),
            unit = null,
        )
    }
    report.stat(ReportMetric.RECOVERY)?.let { stat ->
        stats += profileExtremeStat(Icons.Filled.Spa, "Lowest", stat.metric, stat.min.value)
    }
    return stats
}

/** One extreme as a row: the engine's own metric name and unit, its value written out. */
private fun profileExtremeStat(
    icon: ImageVector,
    superlative: String,
    metric: ReportMetric,
    value: Double,
): WhoopProfileStat = WhoopProfileStat(
    icon = icon,
    label = "$superlative ${metric.label}",
    value = formatLineValue(value),
    unit = metric.unit.ifEmpty { null },
)

/** The engine reports sleep in HOURS and [durationText] takes minutes: a unit change, not a new figure. */
private fun profileSleepText(hours: Double): String = durationText(hours * 60.0)

/** The peak rings side by side, each under its own name. */
@Composable
private fun ProfileRingRow(rings: List<ProfileRing>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        rings.forEach { ring ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                GlowRing(
                    fraction = ring.fraction,
                    value = ring.value,
                    color = ring.color,
                    diameter = PROFILE_RING_DIAMETER,
                    lineWidth = PROFILE_RING_DIAMETER * PROFILE_RING_STROKE_FRACTION,
                    fillKey = "profile.${ring.title}",
                    format = ring.format,
                )
                Text(
                    ring.title.uppercase(),
                    style = NoopType.overline,
                    color = Palette.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The stat rows, hairline-separated. */
@Composable
private fun ProfileStatList(stats: List<WhoopProfileStat>) {
    Column {
        stats.forEachIndexed { index, stat ->
            if (index > 0) RowDivider()
            NoopStatRow(icon = stat.icon, label = stat.label, value = stat.value, unit = stat.unit)
        }
    }
}

/** A small uppercase label with a hairline running out to the card edge. */
@Composable
private fun ProfileRuleLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Overline(text, color = Palette.textTertiary)
        Spacer(Modifier.width(Metrics.space12))
        Box(modifier = Modifier.weight(1f).height(Metrics.divider).background(Palette.hairline))
    }
}

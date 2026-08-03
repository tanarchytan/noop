package com.noop.ui.whoop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.noop.analytics.StrainScorer
import com.noop.ui.BandedWeekBarChart
import com.noop.ui.EffortScale
import com.noop.ui.InsetChartPlaceholder
import com.noop.ui.MetricRow
import com.noop.ui.Metrics
import com.noop.ui.NoopCard
import com.noop.ui.NoopCardHeader
import com.noop.ui.Overline
import com.noop.ui.Palette
import com.noop.ui.RowDivider
import com.noop.ui.StrainGauge
import com.noop.ui.UnitFormatter
import com.noop.ui.WeekBarChart
import com.noop.ui.durationText

// The Strain page's cards. Every figure arrives already formatted or already stored; these draw and
// colour it. Colour is a Palette lookup, never a threshold.

/** One line of the day card: everything resolved by the caller, so the row only draws. */
internal data class StrainMetricLine(
    val icon: ImageVector?,
    val label: String,
    val value: String,
    val comparison: String?,
    val iconTint: Color,
)

/**
 * The day's Effort gauge over the page ground, on the user's selected 0-21 / 0-100 scale. A day with
 * no scored Effort shows the empty state rather than a zeroed ring.
 */
@Composable
internal fun StrainHero(
    strain: Double?,
    effortScale: EffortScale,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        if (strain == null) {
            InsetChartPlaceholder(message = "No Effort scored yet today.")
        } else {
            StrainGauge(
                strain = UnitFormatter.effortValue(strain, effortScale),
                outOf = effortAxisMax(effortScale),
                valueText = UnitFormatter.effortDisplay(strain, effortScale),
            )
        }
        Overline("Strain", color = Palette.effortColor)
    }
}

/** The day's metric lines in one card, hairline-divided. Renders nothing when no line has a value. */
@Composable
internal fun StrainTodayCard(lines: List<StrainMetricLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    NoopCard(modifier = modifier, tint = Palette.effortColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            lines.forEachIndexed { i, line ->
                if (i > 0) RowDivider()
                MetricRow(
                    icon = line.icon,
                    label = line.label,
                    value = line.value,
                    comparison = line.comparison,
                    iconTint = line.iconTint,
                )
            }
        }
    }
}

/** The weekly cards' common frame: an uppercase title with a chevron when it opens something. */
@Composable
internal fun StrainWeekCard(
    title: String,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    NoopCard(modifier = modifier, tint = Palette.effortColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
            NoopCardHeader(title, onClick = onOpen)
            body()
        }
    }
}

/**
 * STRAIN — the week's Effort, each bar tinted by its own value along the Effort ramp and labelled on
 * the user's display scale. The axis is pinned to the full Effort scale, so bar heights compare across
 * weeks.
 */
@Composable
internal fun StrainTrendCard(
    week: StrainWeek,
    effortScale: EffortScale,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    StrainWeekCard(title = "Strain", modifier = modifier, onOpen = onOpen) {
        if (week.strain.all { it == null }) {
            InsetChartPlaceholder(message = "No Effort scored this week yet.")
        } else {
            BandedWeekBarChart(
                values = week.strain,
                dayLabels = week.labels,
                colorFor = { Palette.effortTint(it / StrainScorer.maxStrain) },
                format = { UnitFormatter.effortDisplay(it, effortScale) },
                axisMax = StrainScorer.maxStrain,
                highlightIndex = week.todayIndex,
            )
        }
    }
}

/**
 * One weekly HR-zone card: the stored daily minutes in that band, one bar per day. Renders nothing
 * when the week holds no reading — the columns only fill once the day has been scored.
 */
@Composable
internal fun StrainZoneCard(
    title: String,
    minutes: List<Double?>,
    dayLabels: List<String>,
    color: Color,
    highlightIndex: Int,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    if (minutes.all { it == null }) return
    StrainWeekCard(title = title, modifier = modifier, onOpen = onOpen) {
        WeekBarChart(
            values = minutes,
            dayLabels = dayLabels,
            color = color,
            format = { durationText(it) },
            highlightIndex = highlightIndex,
        )
    }
}

/** STEPS — the week's daily step totals. Renders nothing when the week holds no count. */
@Composable
internal fun StrainStepsCard(
    week: StrainWeek,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    if (week.steps.all { it == null }) return
    StrainWeekCard(title = "Steps", modifier = modifier, onOpen = onOpen) {
        WeekBarChart(
            values = week.steps,
            dayLabels = week.labels,
            color = Palette.metricCyan,
            format = { strainCountText(it) },
            highlightIndex = week.todayIndex,
        )
    }
}

/** CALORIES — the week's whole-day energy estimate. Renders nothing when the week holds no estimate. */
@Composable
internal fun StrainCaloriesCard(
    week: StrainWeek,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
) {
    if (week.calories.all { it == null }) return
    StrainWeekCard(title = "Calories", modifier = modifier, onOpen = onOpen) {
        WeekBarChart(
            values = week.calories,
            dayLabels = week.labels,
            color = Palette.metricAmber,
            format = { strainCountText(it) },
            highlightIndex = week.todayIndex,
        )
    }
}

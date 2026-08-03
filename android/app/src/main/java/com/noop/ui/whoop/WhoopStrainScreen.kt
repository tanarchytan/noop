package com.noop.ui.whoop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.data.DailyMetric
import com.noop.ui.AppViewModel
import com.noop.ui.Palette
import com.noop.ui.ScreenScaffold
import com.noop.ui.SectionHeader
import com.noop.ui.StatePill
import com.noop.ui.StrandTone
import com.noop.ui.UnitPrefs
import com.noop.ui.clockLabel
import com.noop.ui.durationText
import java.time.LocalDate

/**
 * The Strain detail page: the day's Effort gauge, its metric lines against their trailing averages,
 * then the trailing week per metric. Display only — it reads [AppViewModel]'s existing state and adds
 * no repository call. [onOpenStrainHistory] opens Effort's own history; null hides that chevron.
 */
@Composable
fun WhoopStrainScreen(
    vm: AppViewModel,
    onOpenStrainHistory: (() -> Unit)? = null,
) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val effortScale = UnitPrefs.effortScale(LocalContext.current)

    // Anchor every window on the row the app already resolved as today (it rolls at 04:00), falling
    // back to the phone's calendar day so a months-old import can never fill the week as if it were now.
    val anchorDay = today?.day ?: LocalDate.now().toString()
    val week = remember(days, anchorDay) { buildStrainWeek(days, anchorDay) }
    val stepsAverage = remember(days, anchorDay) {
        strainWindowAverage(days, anchorDay) { it.steps?.toDouble() }
    }
    val zone1to3Average = remember(days, anchorDay) {
        strainWindowAverage(days, anchorDay) { it.zone1to3Min }
    }
    val zone4to5Average = remember(days, anchorDay) {
        strainWindowAverage(days, anchorDay) { it.zone4to5Min }
    }

    // Built per composition, not remembered: every tint is a Palette getter, so a light/dark flip
    // re-resolves it.
    val lines = strainMetricLines(today, stepsAverage, zone1to3Average, zone4to5Average)

    ScreenScaffold(title = "Strain", subtitle = today?.let { clockLabel(it, null) }) {
        StrainHero(strain = today?.strain, effortScale = effortScale)
        StrainTodayCard(lines)
        if (lines.any { it.comparison != null }) {
            StatePill(
                title = "Today vs. last $STRAIN_COMPARISON_DAYS days",
                tone = StrandTone.Neutral,
                showsDot = false,
                fillsWidth = true,
            )
        }
        SectionHeader(title = "Weekly Trends")
        StrainTrendCard(week = week, effortScale = effortScale, onOpen = onOpenStrainHistory)
        StrainZoneCard(
            title = "HR Zones 1-3",
            minutes = week.zone1to3,
            dayLabels = week.labels,
            color = Palette.hrZoneColor(2),
            highlightIndex = week.todayIndex,
        )
        StrainZoneCard(
            title = "HR Zones 4-5",
            minutes = week.zone4to5,
            dayLabels = week.labels,
            color = Palette.hrZoneColor(5),
            highlightIndex = week.todayIndex,
        )
        StrainStepsCard(week = week)
        StrainCaloriesCard(week = week)
    }
}

/**
 * The day card's lines, in the reference's order. A metric with no reading contributes no line, and a
 * metric with no trailing average carries no comparison caption.
 */
private fun strainMetricLines(
    today: DailyMetric?,
    stepsAverage: Double?,
    zone1to3Average: Double?,
    zone4to5Average: Double?,
): List<StrainMetricLine> {
    val lines = ArrayList<StrainMetricLine>()
    today?.zone1to3Min?.let { v ->
        lines.add(
            StrainMetricLine(
                icon = Icons.Filled.FavoriteBorder,
                label = "Heart rate zones 1-3",
                value = durationText(v),
                comparison = zone1to3Average?.let { averageCaption(durationText(it)) },
                iconTint = Palette.hrZoneColor(2),
            ),
        )
    }
    today?.zone4to5Min?.let { v ->
        lines.add(
            StrainMetricLine(
                icon = Icons.Filled.Favorite,
                label = "Heart rate zones 4-5",
                value = durationText(v),
                comparison = zone4to5Average?.let { averageCaption(durationText(it)) },
                iconTint = Palette.hrZoneColor(5),
            ),
        )
    }
    today?.steps?.let { v ->
        lines.add(
            StrainMetricLine(
                icon = Icons.AutoMirrored.Filled.DirectionsRun,
                label = "Steps",
                value = strainCountText(v.toDouble()),
                comparison = stepsAverage?.let { averageCaption(strainCountText(it)) },
                iconTint = Palette.metricCyan,
            ),
        )
    }
    return lines
}

/** "30-day avg 1,423" — the grey line under a metric's value. */
private fun averageCaption(formatted: String): String = "$STRAIN_COMPARISON_DAYS-day avg $formatted"

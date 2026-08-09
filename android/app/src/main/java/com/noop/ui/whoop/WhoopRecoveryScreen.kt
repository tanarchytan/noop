package com.noop.ui.whoop

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ui.ChargeDriver
import com.noop.ui.UnitPrefs
import com.noop.analytics.RustScores
import com.noop.analytics.ScoreConfidence
import com.noop.ui.AppViewModel
import com.noop.ui.BandedWeekBarChart
import com.noop.ui.DataPendingNote
import com.noop.ui.InsightCard
import com.noop.ui.MetricRow
import com.noop.ui.Metrics
import com.noop.ui.NoopCard
import com.noop.ui.NoopCardHeader
import com.noop.ui.NoopType
import com.noop.ui.Overline
import com.noop.ui.Palette
import com.noop.ui.RecoveryRing
import com.noop.ui.ScreenScaffold
import com.noop.ui.SectionHeader
import com.noop.ui.StatePill
import com.noop.ui.StrandTone
import com.noop.ui.WeekBarChart
import com.noop.ui.WeekLineChart
import com.noop.ui.WeekLineSeries
import com.noop.ui.baselineLabel
import com.noop.ui.logicalDayKeyNow
import com.noop.ui.resolveTodayRow
import com.noop.ui.valueLabel
import com.noop.ui.widgetAnchorRow
import uniffi.whoop_ffi.RecoveryState
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// The Recovery detail page: the Charge ring, the analytics driver rows behind it, then the week's
// Recovery / HRV / resting HR / respiratory / sleep-performance trends. Display only: every figure
// arrives already scored, and a metric with no reading for a day draws nothing.

/** Recovery and sleep performance are percentages, so their bars are pinned to a 0..100 axis. */
private const val RECOVERY_SCALE_MAX = 100.0

/**
 * The Recovery page. Each `onOpen*` is optional; a null one drops that card's chevron, so the screen
 * renders with no navigation, no strap and no data.
 */
@Composable
fun WhoopRecoveryScreen(
    vm: AppViewModel,
    onOpenTrends: (() -> Unit)? = null,
    onOpenVital: ((String) -> Unit)? = null,
    onOpenSleep: (() -> Unit)? = null,
) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    // The day the page is about, and the row its ring reads: today's when it is scored, else the
    // freshest strictly-prior scored day. Both come from the shared logical-day resolvers.
    val logicalKey = logicalDayKeyNow()
    val localKey = LocalDate.now().toString()
    val resolvedToday = remember(days, logicalKey, localKey) {
        resolveTodayRow(days, logicalKey, localKey)
    }
    val anchor = remember(days, logicalKey, localKey) {
        widgetAnchorRow(days, logicalKey, localKey)
    }
    val dayKey = today?.day ?: resolvedToday?.day ?: logicalKey
    val carriedFrom = anchor?.day?.takeIf { it != dayKey }

    val tempUnit = UnitPrefs.temperature(LocalContext.current)
    val read = remember(days, anchor, tempUnit) { recoveryDayRead(days, anchor, tempUnit) }
    val week = remember(days, dayKey, anchor) { recoveryWeek(days, dayKey, anchor?.day) }

    ScreenScaffold(
        title = stringResource(R.string.nav_recovery),
        subtitle = if (carriedFrom != null) {
            stringResource(R.string.whoopskin_recovery_subtitle_carried)
        } else {
            stringResource(R.string.whoopskin_recovery_subtitle)
        },
    ) {
        RecoveryHeroBlock(
            score = anchor?.recovery,
            carriedFrom = carriedFrom,
            topDriver = read.drivers.firstOrNull(),
        )
        if (read.drivers.isNotEmpty()) RecoveryDriversCard(read)
        if (!week.isEmpty) RecoveryWeeklyTrends(week, onOpenTrends, onOpenVital, onOpenSleep)
    }
}

/**
 * The ring, its carried-day stamp, and the readiness read: the score's own whoop-rs band as a Push /
 * Maintain / Rest word over the biggest driver's verdict.
 */
@Composable
private fun RecoveryHeroBlock(
    score: Double?,
    carriedFrom: String?,
    topDriver: ChargeDriver?,
) {
    if (score == null) {
        DataPendingNote(
            title = stringResource(R.string.whoopskin_recovery_empty_title),
            body = stringResource(R.string.whoopskin_recovery_empty_body),
        )
        return
    }
    val word = chargeReadinessWord(RustScores.state(score))
    val verdict = topDriver?.let {
        stringResource(
            R.string.whoopskin_recovery_driver_verdict,
            stringResource(it.labelRes),
            stringResource(it.verdictRes),
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        RecoveryRing(score = score)
        if (carriedFrom != null) {
            Overline(
                stringResource(R.string.whoopskin_recovery_last_scored, recoveryDayStamp(carriedFrom)),
            )
        }
        InsightCard(
            modifier = Modifier.fillMaxWidth(),
            category = stringResource(R.string.whoopskin_recovery_readiness),
            status = word,
            detail = verdict ?: stringResource(R.string.whoopskin_recovery_readiness_detail),
            statusColor = Palette.recoveryColor(score),
            tint = null,
        )
    }
}

/**
 * The action word a Charge score reads as. whoop-rs cuts the band; only the word is chosen here, and
 * it moves with the score so a low day never reads the same as a peak one. PURE.
 */
internal fun chargeReadinessWord(state: RecoveryState): String = when (state) {
    RecoveryState.DEPLETED, RecoveryState.LOW -> "Rest"
    RecoveryState.MODERATE -> "Maintain"
    RecoveryState.PRIMED, RecoveryState.PEAK -> "Push"
}

/** One row per signal the score actually used, biggest mover first, with the score's confidence tier. */
@Composable
private fun RecoveryDriversCard(read: RecoveryDayRead) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            NoopCardHeader(
                title = stringResource(R.string.whoopskin_recovery_what_shaped_it),
                trailing = {
                    StatePill(
                        title = stringResource(confidenceLabel(read.confidence)),
                        tone = confidenceTone(read.confidence),
                    )
                },
            )
            read.drivers.forEach { driver ->
                MetricRow(
                    icon = driverIcon(driver.labelRes),
                    label = stringResource(driver.labelRes),
                    value = driver.valueLabel(),
                    trend = driverTrendText(driver.deltaPoints),
                    trendColor = driverTrendColor(driver.deltaPoints),
                    comparison = driver.baselineLabel().takeIf { it.isNotBlank() },
                )
            }
            Text(
                stringResource(R.string.whoopskin_recovery_drivers_footnote),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/** The five weekly cards. A metric with no reading all week drops its card rather than draw an empty one. */
@Composable
private fun RecoveryWeeklyTrends(
    week: RecoveryWeek,
    onOpenTrends: (() -> Unit)?,
    onOpenVital: ((String) -> Unit)?,
    onOpenSleep: (() -> Unit)?,
) {
    SectionHeader(stringResource(R.string.whoopskin_weekly_trends))
    if (week.recovery.any { it != null }) {
        RecoveryTrendCard("Recovery", onOpenTrends) {
            BandedWeekBarChart(
                values = week.recovery,
                dayLabels = week.labels,
                colorFor = { Palette.recoveryColor(it) },
                format = { percentText(it) },
                axisMax = RECOVERY_SCALE_MAX,
                highlightIndex = week.highlightIndex,
                height = Metrics.compactChartHeight,
            )
        }
    }
    if (week.hrv.any { it != null }) {
        RecoveryTrendCard(
            stringResource(R.string.whoopskin_recovery_card_hrv),
            vitalOpener(onOpenVital, "hrv"),
        ) {
            WeekLineChart(
                series = WeekLineSeries(
                    stringResource(R.string.whoopskin_recovery_series_hrv),
                    week.hrv,
                    Palette.metricCyan,
                ),
                dayLabels = week.labels,
                format = { wholeText(it) },
                highlightIndex = week.highlightIndex,
                height = Metrics.compactChartHeight,
            )
        }
    }
    if (week.restingHr.any { it != null }) {
        RecoveryTrendCard(
            stringResource(R.string.whoopskin_recovery_card_rhr),
            vitalOpener(onOpenVital, "rhr"),
        ) {
            WeekLineChart(
                series = WeekLineSeries(
                    stringResource(R.string.whoopskin_recovery_series_rhr),
                    week.restingHr,
                    Palette.metricRose,
                ),
                dayLabels = week.labels,
                format = { wholeText(it) },
                highlightIndex = week.highlightIndex,
                height = Metrics.compactChartHeight,
            )
        }
    }
    if (week.respiratory.any { it != null }) {
        RecoveryTrendCard(
            stringResource(R.string.whoopskin_recovery_card_resp),
            vitalOpener(onOpenVital, "resp"),
        ) {
            WeekLineChart(
                series = WeekLineSeries(
                    stringResource(R.string.whoopskin_recovery_series_resp),
                    week.respiratory,
                    Palette.accent,
                ),
                dayLabels = week.labels,
                format = { tenthText(it) },
                highlightIndex = week.highlightIndex,
                height = Metrics.compactChartHeight,
            )
        }
    }
    if (week.sleepPerformance.any { it != null }) {
        RecoveryTrendCard(stringResource(R.string.whoopskin_recovery_card_sleep), onOpenSleep) {
            WeekBarChart(
                values = week.sleepPerformance,
                dayLabels = week.labels,
                color = Palette.restColor,
                format = { percentText(it) },
                axisMax = RECOVERY_SCALE_MAX,
                highlightIndex = week.highlightIndex,
                height = Metrics.compactChartHeight,
            )
        }
    }
}

/** A weekly card: the UPPERCASE title with its chevron when tappable, then the chart. */
@Composable
private fun RecoveryTrendCard(title: String, onOpen: (() -> Unit)?, chart: @Composable () -> Unit) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            NoopCardHeader(title = title, onClick = onOpen)
            chart()
        }
    }
}

/** Bind a vital key to the page's optional open-a-vital callback, keeping null as "no chevron". */
private fun vitalOpener(onOpenVital: ((String) -> Unit)?, key: String): (() -> Unit)? {
    if (onOpenVital == null) return null
    return { onOpenVital(key) }
}

private fun percentText(value: Double): String = "${value.roundToInt()}%"

private fun wholeText(value: Double): String = "${value.roundToInt()}"

private fun tenthText(value: Double): String = String.format(Locale.US, "%.1f", value)

/** A leading glyph per driver signal, keyed on the row's resource so it survives translation. */
private fun driverIcon(@StringRes label: Int): ImageVector? = when (label) {
    R.string.charge_driver_hrv -> Icons.Filled.MonitorHeart
    R.string.charge_driver_resting_hr -> Icons.Filled.FavoriteBorder
    R.string.charge_driver_sleep -> Icons.Filled.Bedtime
    R.string.charge_driver_respiratory -> Icons.Filled.Air
    R.string.charge_driver_skin_temp -> Icons.Filled.Thermostat
    R.string.charge_driver_recovery_index -> Icons.AutoMirrored.Filled.ShowChart
    R.string.charge_driver_activity_balance -> Icons.Filled.FitnessCenter
    else -> null
}

/** The chip text for a driver's signed point swing; the sign is what draws its direction triangle. */
@Composable
private fun driverTrendText(points: Int): String {
    val signed = if (points > 0) "+$points" else "$points"
    return stringResource(
        if (points == 1 || points == -1) R.string.whoopskin_recovery_driver_point
        else R.string.whoopskin_recovery_driver_points,
        signed,
    )
}

/** Lifted Recovery reads positive, held it back reads critical, no swing stays neutral. */
private fun driverTrendColor(points: Int): Color = when {
    points > 0 -> Palette.statusPositive
    points < 0 -> Palette.statusCritical
    else -> Palette.textTertiary
}

@StringRes
private fun confidenceLabel(tier: ScoreConfidence): Int = when (tier) {
    ScoreConfidence.SOLID -> R.string.whoopskin_confidence_solid
    ScoreConfidence.BUILDING -> R.string.whoopskin_confidence_building
    ScoreConfidence.CALIBRATING -> R.string.whoopskin_confidence_calibrating
}

private fun confidenceTone(tier: ScoreConfidence): StrandTone = when (tier) {
    ScoreConfidence.SOLID -> StrandTone.Accent
    ScoreConfidence.BUILDING -> StrandTone.Warning
    ScoreConfidence.CALIBRATING -> StrandTone.Neutral
}

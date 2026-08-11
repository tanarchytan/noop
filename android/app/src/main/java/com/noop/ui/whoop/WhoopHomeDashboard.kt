package com.noop.ui.whoop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.analytics.VitalBands
import com.noop.data.DailyMetric
import com.noop.ui.DashboardCard
import com.noop.ui.MetricRow
import com.noop.ui.Metrics
import com.noop.ui.NO_DATA
import com.noop.ui.NoopCard
import com.noop.ui.RowDivider
import com.noop.ui.STRESS_CALIBRATING
import com.noop.ui.durationText
import com.noop.ui.sleepValue
import com.noop.ui.trendDayLabel
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - "My Dashboard"
//
// The user's chosen metric rows, in their saved order, rendered as the dense WHOOP metric line. Every
// value is a stored field or a whoop-rs-scored figure formatted for display; no delta is computed here,
// so the direction triangle WHOOP shows is absent and the grey line beneath carries the PREVIOUS day's
// own number instead of a difference.

/** One row of the dashboard: what to draw and where a tap goes. */
private data class WhoopDashboardRow(
    val icon: ImageVector?,
    val label: String,
    val value: String,
    val comparison: String?,
    val onClick: (() -> Unit)?,
)

/**
 * The dashboard card. [cards] is the persisted selection; the hydration and coupled entries are left
 * out because neither is a metric this page can render honestly (see the report's data gaps).
 */
@Composable
internal fun WhoopDashboardCard(
    state: WhoopHomeState,
    cards: List<DashboardCard>,
    onOpenMetric: (String) -> Unit,
    onOpenStress: () -> Unit,
    onOpenSleep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = buildList {
        cards.filter { it in RENDERED_CARDS }.forEach { card ->
            add(
                WhoopDashboardRow(
                    icon = card.icon,
                    label = stringResource(card.titleRes),
                    value = dashboardValue(card, state),
                    comparison = dashboardComparison(card, state.previousDay),
                    onClick = dashboardDestination(card, onOpenMetric, onOpenStress, onOpenSleep),
                ),
            )
        }
        // The two zone rows sit outside the customisable registry: DashboardCard lives in a shared file
        // this pass does not edit, so they are appended here and only when the day banked them.
        state.metric?.zone1to3Min?.let {
            add(
                WhoopDashboardRow(
                    Icons.Filled.MonitorHeart,
                    stringResource(R.string.whoopskin_dashboard_hr_zones_1_3),
                    durationText(it),
                    zoneComparison(state.previousDay?.zone1to3Min, state.previousDay),
                    null,
                ),
            )
        }
        state.metric?.zone4to5Min?.let {
            add(
                WhoopDashboardRow(
                    Icons.Filled.Favorite,
                    stringResource(R.string.whoopskin_dashboard_hr_zones_4_5),
                    durationText(it),
                    zoneComparison(state.previousDay?.zone4to5Min, state.previousDay),
                    null,
                ),
            )
        }
    }
    if (rows.isEmpty()) return
    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider()
                MetricRow(
                    icon = row.icon,
                    label = row.label,
                    value = row.value,
                    comparison = row.comparison,
                    onClick = row.onClick,
                )
            }
        }
    }
}

/** The cards this page renders as a metric line. */
private val RENDERED_CARDS: Set<DashboardCard> = setOf(
    DashboardCard.HRV,
    DashboardCard.RESTING_HR,
    DashboardCard.RESPIRATORY,
    DashboardCard.STEPS,
    DashboardCard.STRESS,
    DashboardCard.FITNESS_AGE,
    DashboardCard.VITALITY,
    DashboardCard.BLOOD_OXYGEN,
    DashboardCard.SKIN_TEMP,
    DashboardCard.SLEEP,
    DashboardCard.CALORIES,
)

/** Append the card's unit to an already-formatted figure; a missing figure stays the honest dash. */
@Composable
private fun withUnit(card: DashboardCard, formatted: String?): String = when {
    formatted == null -> NO_DATA
    card.unit.isEmpty() -> formatted
    else -> "$formatted ${card.unit}"
}

/**
 * The card's current value. The overnight vitals read per-field today-first with the recovery-independent
 * carry behind them, so a night whose recovery was nulled still shows its own preserved figure.
 */
@Composable
private fun dashboardValue(card: DashboardCard, s: WhoopHomeState): String {
    val carried = s.carriedDay ?: s.metric
    return when (card) {
        DashboardCard.HRV ->
            withUnit(card, (s.metric?.avgHrv ?: s.vitalsDay?.avgHrv)?.let { it.roundToInt().toString() })
        DashboardCard.RESTING_HR ->
            withUnit(card, (s.metric?.restingHr ?: s.vitalsDay?.restingHr)?.toString())
        DashboardCard.RESPIRATORY ->
            withUnit(card, (s.metric?.respRateBpm ?: s.vitalsDay?.respRateBpm)?.let { oneDecimal(it) })
        DashboardCard.BLOOD_OXYGEN ->
            (carried?.spo2Pct ?: s.spo2Day?.spo2Pct)?.let { String.format(Locale.US, "%.0f%%", it) } ?: NO_DATA
        DashboardCard.SKIN_TEMP ->
            VitalBands.skinTempDisplay(
                carried?.skinTempAbsC ?: s.skinTempDay?.skinTempAbsC,
                carried?.skinTempDevC ?: s.skinTempDay?.skinTempDevC,
            )?.let { VitalBands.formatSkinTemp(it) } ?: NO_DATA
        DashboardCard.SLEEP -> sleepValue(carried)
        DashboardCard.STEPS -> {
            val measured = s.metric?.steps ?: s.importedSteps
            measured?.let { strainCountText(it.toDouble()) }
                ?: s.estimatedSteps?.let { strainCountText(it.toDouble()) }
                ?: NO_DATA
        }
        DashboardCard.CALORIES -> withUnit(card, s.activeKcal?.let { strainCountText(it) })
        // Stress is baseline-relative: before the baseline seeds there is no number, and the band word
        // WHOOP prints beside it is decided by a Kotlin threshold, so only the score is shown.
        DashboardCard.STRESS -> s.stress?.let { oneDecimal(it) } ?: STRESS_CALIBRATING
        DashboardCard.FITNESS_AGE -> withUnit(card, s.fitnessAge?.let { it.roundToInt().toString() })
        DashboardCard.VITALITY -> s.vitality?.let { it.roundToInt().toString() } ?: NO_DATA
        else -> NO_DATA
    }
}

/**
 * The grey line beneath the value: the PREVIOUS banked day's own reading for the same metric, stamped
 * with its date. It is never a difference — no delta is computed on this side of the border.
 */
@Composable
private fun dashboardComparison(card: DashboardCard, previous: DailyMetric?): String? {
    val prev = previous ?: return null
    val figure = when (card) {
        DashboardCard.HRV -> prev.avgHrv?.let { withUnit(card, it.roundToInt().toString()) }
        DashboardCard.RESTING_HR -> prev.restingHr?.let { withUnit(card, it.toString()) }
        DashboardCard.RESPIRATORY -> prev.respRateBpm?.let { withUnit(card, oneDecimal(it)) }
        DashboardCard.BLOOD_OXYGEN -> prev.spo2Pct?.let { String.format(Locale.US, "%.0f%%", it) }
        DashboardCard.SLEEP -> prev.totalSleepMin?.let { sleepValue(prev) }
        DashboardCard.STEPS -> prev.steps?.let { strainCountText(it.toDouble()) }
        else -> null
    } ?: return null
    return "${trendDayLabel(prev.day)} · $figure"
}

/** The same stamped-previous-reading caption for the two zone rows. */
private fun zoneComparison(minutes: Double?, previous: DailyMetric?): String? {
    val prev = previous ?: return null
    val m = minutes ?: return null
    return "${trendDayLabel(prev.day)} · ${durationText(m)}"
}

/** Where a dashboard row's tap goes: its own screen, else its focused metric trend. */
private fun dashboardDestination(
    card: DashboardCard,
    onOpenMetric: (String) -> Unit,
    onOpenStress: () -> Unit,
    onOpenSleep: () -> Unit,
): (() -> Unit)? = when (card) {
    DashboardCard.STRESS -> onOpenStress
    DashboardCard.SLEEP -> onOpenSleep
    else -> metricDetailKey(card)?.let { key -> { onOpenMetric(key) } }
}

/** The `vital_detail/<key>` a metric card opens, or null when it has no focused trend. */
private fun metricDetailKey(card: DashboardCard): String? = when (card) {
    DashboardCard.HRV -> "hrv"
    DashboardCard.RESTING_HR -> "rhr"
    DashboardCard.RESPIRATORY -> "resp"
    DashboardCard.BLOOD_OXYGEN -> "spo2"
    DashboardCard.SKIN_TEMP -> "skin"
    DashboardCard.FITNESS_AGE -> "fitness_age"
    DashboardCard.VITALITY -> "vitality"
    DashboardCard.STEPS -> "steps_est"
    DashboardCard.CALORIES -> "active_kcal"
    else -> null
}

private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

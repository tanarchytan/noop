package com.noop.ui.whoop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.noop.data.DailyMetric
import com.noop.ui.DualAxisTrendChart
import com.noop.ui.EffortScale
import com.noop.ui.Metrics
import com.noop.ui.NoopCard
import com.noop.ui.NoopCardHeader
import com.noop.ui.Palette
import com.noop.ui.UnitFormatter
import com.noop.ui.formatLineValue
import kotlin.math.roundToInt

// MARK: - Strain & recovery
//
// The trailing week on two scales. Both series are stored per-day figures; the chart maps a value to a
// pixel and prints what it is handed, and the colour is a Palette ramp lookup, never a band boundary.

/** Charge is a percentage, so its axis is fixed. */
private const val CHARGE_AXIS_MAX = 100.0

/** Gridline fractions both axes tick at — thirds of the plot, matching the reference layout. */
private val AXIS_TICK_FRACTIONS = listOf(1.0 / 3.0, 2.0 / 3.0, 1.0)

/** A tick sits on a whole number, so the two axes label the same gridline at the same precision. */
private fun axisTicks(max: Double): List<Double> =
    AXIS_TICK_FRACTIONS.map { (it * max).roundToInt().toDouble() }

/**
 * The week of Effort against Charge, with the day on screen highlighted. [scale] only changes how the
 * stored 0-100 Effort is PRINTED; the stored value is untouched. The week itself comes from the shared
 * [buildStrainWeek], so this card and the Strain page bucket days identically.
 */
@Composable
internal fun WhoopStrainRecoveryCard(
    days: List<DailyMetric>,
    dayKey: String,
    scale: EffortScale,
    onOpenTrends: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val week = remember(days, dayKey) { buildStrainWeek(days, dayKey) }
    val byDay = remember(days) { days.associateBy { it.day } }
    val effortMax = effortAxisMax(scale)
    if (week.columns.isEmpty()) return
    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            NoopCardHeader("Strain & recovery", onClick = onOpenTrends)
            DualAxisTrendChart(
                dayLabels = week.labels,
                leftName = "Effort",
                leftValues = week.strain.map { v -> v?.let { UnitFormatter.effortValue(it, scale) } },
                leftMax = effortMax,
                rightName = "Charge",
                rightValues = week.columns.map { byDay[it.day]?.recovery },
                rightMax = CHARGE_AXIS_MAX,
                leftColor = Palette.effortColor,
                rightColorFor = { Palette.recoveryColor(it) },
                leftFormat = { formatLineValue(it) },
                rightFormat = { "${it.roundToInt()}%" },
                leftTicks = axisTicks(effortMax),
                rightTicks = axisTicks(CHARGE_AXIS_MAX),
                highlightIndex = week.todayIndex,
            )
        }
    }
}

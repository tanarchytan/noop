package com.noop.ui.whoop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.noop.data.WorkoutRow
import com.noop.ui.Metrics
import com.noop.ui.MetricRow
import com.noop.ui.NoopButton
import com.noop.ui.NoopButtonKind
import com.noop.ui.NoopCard
import com.noop.ui.NoopCardHeader
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.RowDivider
import com.noop.ui.StatePill
import com.noop.ui.StrandTone
import com.noop.ui.STRESS_CALIBRATING
import com.noop.ui.workoutCaption
import com.noop.ui.workoutDuration
import java.util.Locale

// MARK: - "My Day" cards
//
// The monitor pair, tonight's sleep, the day's activities and the journal entry point. Each renders a
// value it is handed; where a figure does not exist yet the element is left out rather than filled in.

/**
 * The health / stress monitor pair. [healthRollUp] is the same in-range result the Health page states,
 * so the two never summarise one day in opposite tones; [healthAlert] is the watch's own message and
 * sits beneath it exactly as it does there.
 */
@Composable
internal fun WhoopMonitorRow(
    healthAlert: String?,
    healthRollUp: HealthRollUp?,
    stress: Double?,
    onOpenHealth: () -> Unit,
    onOpenStress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
    ) {
        NoopCard(modifier = Modifier.weight(1f).fillMaxHeight(), padding = Metrics.space14) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                NoopCardHeader("Health monitor", onClick = onOpenHealth)
                if (healthRollUp != null) {
                    HealthRollUpPill(healthRollUp)
                } else {
                    StatePill(title = "No readings yet", tone = StrandTone.Neutral, fillsWidth = true)
                }
                if (healthAlert != null) {
                    StatePill(title = healthAlert, tone = StrandTone.Warning, fillsWidth = true)
                }
            }
        }
        NoopCard(modifier = Modifier.weight(1f).fillMaxHeight(), padding = Metrics.space14) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                NoopCardHeader("Stress monitor", onClick = onOpenStress)
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                    Text(
                        stress?.let { String.format(Locale.US, "%.1f", it) } ?: STRESS_CALIBRATING,
                        style = NoopType.number(20f),
                        color = if (stress != null) Palette.textPrimary else Palette.textTertiary,
                        maxLines = 1,
                    )
                    Text(
                        "Autonomic load",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Tonight's sleep: the strap wake-alarm state and the way to change it. WHOOP also prints a recommended
 * bedtime here; nothing supplies one, so that half of the row is absent rather than guessed.
 */
@Composable
internal fun WhoopTonightSleepCard(
    alarmEnabled: Boolean,
    alarmMinutes: Int,
    onOpenSleep: () -> Unit,
    onOpenAlarm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            NoopCardHeader("Tonight's sleep", onClick = onOpenSleep)
            MetricRow(
                icon = Icons.Filled.Alarm,
                label = "Wake alarm",
                value = if (alarmEnabled) hourMinuteOfDay(alarmMinutes) else "Off",
                valueColor = if (alarmEnabled) Palette.textPrimary else Palette.textTertiary,
            )
            NoopButton(
                text = if (alarmEnabled) "Change alarm" else "Set alarm",
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = onOpenAlarm,
            )
        }
    }
}

/** The day's logged and imported sessions, already deduped and capped by the shared feed contract. */
@Composable
internal fun WhoopActivitiesCard(
    activities: List<WorkoutRow>,
    onOpenWorkouts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            NoopCardHeader("Today's activities", onClick = onOpenWorkouts)
            if (activities.isEmpty()) {
                Text(
                    "Nothing logged for this day.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            } else {
                activities.forEachIndexed { index, row ->
                    if (index > 0) RowDivider()
                    MetricRow(
                        icon = Icons.Filled.FitnessCenter,
                        label = row.sport,
                        value = workoutDuration(row),
                        comparison = workoutCaption(row),
                        onClick = onOpenWorkouts,
                    )
                }
            }
        }
    }
}

/**
 * The journal entry point. WHOOP shows a week of logged/not-logged ticks above this button; no per-day
 * "was it logged" signal reaches the Home state, so the week row is absent rather than invented.
 */
@Composable
internal fun WhoopJournalCard(
    onOpenJournal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            NoopCardHeader("My journal", onClick = onOpenJournal)
            Text(
                "Log the behaviours behind a night, then read what they moved.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
            NoopButton(
                text = "Behaviour insights",
                leadingIcon = Icons.Filled.Insights,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = onOpenJournal,
            )
        }
    }
}

/** Minutes since local midnight as a 24-hour clock label. */
internal fun hourMinuteOfDay(minutes: Int): String {
    val m = ((minutes % (24 * 60)) + 24 * 60) % (24 * 60)
    return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
}

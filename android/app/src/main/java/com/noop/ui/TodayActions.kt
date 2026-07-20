package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The accent quick-action "+" in the Today header's top-right. Moved off the bottom bar (now four clean
 * tabs) to balance the header and open the existing quick-action sheet. A small CONTAINED accent disc,  * the accented primary among an otherwise-neutral icon set, ~36dp, no float and no glow: a flat reset-blue
 * accent fill with a hairline rim, the "+" glyph in crisp white. Mirrors the iOS quick-action + (a glyph on
 * Circle().fill(StrandPalette.accent)).
 */
/**
 * Today "workout in progress" indicator (iOS parity: ActiveWorkoutIndicatorCard). A metricRose-tinted card
 * with a decorative live dot + "WORKOUT IN PROGRESS" overline, a live H:MM:SS clock, the sport label, and a
 * "Return to workout" button. The whole card is tappable; [onReturn] routes to Live and re-opens the
 * in-exercise overlay. The clock ticks in this card's OWN LaunchedEffect (reading [ActiveWorkout.startMs]),
 * so the per-second update recomposes only this card, never the Today body. Design tokens only, no glow.
 */
@Composable
internal fun WorkoutInProgressCard(
    workout: AppViewModel.ActiveWorkout,
    onReturn: () -> Unit,
) {
    // Live clock: re-read wall-clock every second and recompute elapsed off the workout's start. Keyed on
    // startMs so a fresh session restarts the loop. Mirrors the iOS TimelineView(.periodic(by: 1)).
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(workout.startMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val elapsedS = ((nowMs - workout.startMs) / 1000).coerceAtLeast(0)
    val elapsed = elapsedClock(elapsedS)
    val sportLabel = workout.sport.name

    // liquidPress on the whole tappable "return to workout" card (same interactionSource on clickable + press).
    val interaction = remember { MutableInteractionSource() }
    NoopCard(
        tint = Palette.metricRose,
        // Combine into ONE actionable element so TalkBack reads "Workout in progress, $sport, $elapsed,
        // Return to workout" as a single Button, not five stops; the decorative dot is omitted by clearing
        // child semantics. The whole card is the tap target.
        modifier = Modifier
            .liquidPress(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onReturn)
            .semantics(mergeDescendants = true) {
                contentDescription = "Workout in progress, $sportLabel, $elapsed. Return to workout."
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // Decorative "live" dot, hidden from TalkBack (the merged card reads the full state).
                Box(
                    modifier = Modifier
                        .size(Metrics.space8)
                        .clip(CircleShape)
                        .background(Palette.metricRose)
                        .clearAndSetSemantics {},
                )
                Spacer(Modifier.width(Metrics.space8))
                Text(
                    "WORKOUT IN PROGRESS",
                    style = NoopType.overline,
                    color = Palette.metricRose,
                )
                Spacer(Modifier.weight(1f))
                Text(elapsed, style = NoopType.number(15f), color = Palette.textPrimary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    sportLabel,
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Take the free space (and ellipsize a long sport name) so the button stays its intrinsic
                    // width on the trailing edge, mirroring the iOS ViewThatFits label + trailing button.
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Metrics.space8))
                Button(
                    onClick = onReturn,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.accent, contentColor = Palette.surfaceBase,
                    ),
                ) {
                    Text("Return to workout", style = NoopType.captionNumber)
                    Spacer(Modifier.width(Metrics.space6))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(Metrics.iconSmall),
                    )
                }
            }
        }
    }
}

/**
 * The compact Live Sessions entry under the hero ("Start session · BETA"). Three honest states off the
 * process-wide [LiveSessionRunner.active]: no session → start affordance; session running → the way back
 * into the dismissed session dialog (with a live elapsed clock); session ended but its summary not yet
 * Done-dismissed → "See the summary". The runner's 1 Hz snapshot is collected INSIDE this card only, so
 * the per-second tick recomposes this card, never the Today body (the WorkoutInProgressCard idiom).
 * The whole card is one tap target; [onOpen] begins/re-presents the session dialog.
 */
@Composable
internal fun LiveSessionEntryCard(onOpen: () -> Unit) {
    val active by LiveSessionRunner.active.collectAsStateWithLifecycle()
    val runner = active
    var running = false
    var summaryWaiting = false
    var elapsed = ""
    if (runner != null) {
        val snap by runner.snapshot.collectAsStateWithLifecycle()
        running = !snap.ended
        summaryWaiting = snap.ended
        elapsed = elapsedClock(snap.elapsedSec.toLong())
    }
    val teal = Palette.metricCyan
    val title = when {
        running -> "Session running"
        summaryWaiting -> "Session ended"
        else -> "Start session"
    }
    val detail = when {
        running -> "Guarding — silence means you're on track."
        summaryWaiting -> "See the summary of your last session."
        else -> "Strap-guided effort session. It only buzzes when you drift off today's band."
    }

    // liquidPress on the whole tappable card (same interactionSource on clickable + press), matching the
    // workout-in-progress card above. Merged semantics so TalkBack reads one Button, not four stops.
    val interaction = remember { MutableInteractionSource() }
    NoopCard(
        tint = teal,
        modifier = Modifier
            .liquidPress(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title, beta. $detail"
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                Icons.Filled.TrackChanges,
                contentDescription = null,
                tint = teal,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                ) {
                    Text(title, style = NoopType.headline, color = Palette.textPrimary)
                    StatePill("BETA", tone = StrandTone.Accent, showsDot = false)
                }
                Text(detail, style = NoopType.footnote, color = Palette.textTertiary)
            }
            if (running) {
                Text(elapsed, style = NoopType.number(15f), color = Palette.textPrimary)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(Metrics.iconSmall),
            )
        }
    }
}

/**
 * A small top-trailing × for a Today info-card that has no built-in dismiss control (the shared
 * [DataPendingNote]). Matches the "New here?" card's × styling. Dismisses the card into the inbox.
 */
@Composable
internal fun TodayCardDismissButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(Metrics.iconButton)
            .semantics { contentDescription = "Dismiss to Updates" },
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
internal fun QuickActionDisc(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            // 34dp to sit level with the heart / avatar / battery ring in the liquid header cluster.
            .size(34.dp)
            .liquidPress(interaction)
            .clip(CircleShape)
            // A subtle raised disc so the + reads on the plain themed canvas, in both light and dark.
            .background(Palette.surfaceRaised)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = "Quick actions" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = null,
            tint = Palette.textPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}


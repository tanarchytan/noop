package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// MARK: - Today card actions

/** The Live Session card: start, running elapsed, or the waiting end-of-session summary. */
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
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
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


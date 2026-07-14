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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.noop.ble.LiveState

/**
 * Live/Health fold (stages 3-4): the standalone Live screen was folded into the Health screen and its
 * top-level nav entry removed. What remains here are the live-physiology composables Health now reuses
 * ([PhysiologyStack] + its R-R thread/RMSSD helpers, and [MaxHrZoneCard]) — exposed `internal` so
 * HealthScreen can compose them — plus the pure [relativeAgo] helper still used by DevicesScreen,
 * UpdatesInboxScreen and RelativeAgoTest. The file is intentionally kept (not deleted) for those callers.
 */

/**
 * Read-only Max-HR + top-zone card. Max HR is the age-based value from Settings; the Zone 5 entry
 * (≥ 90% of max) is where HR-zone coaching buzzes. Managing coaching lives in Automations.
 * Reimplemented from @cbarrado's PR #350. Folded into Health (Live/Health merge).
 */
@Composable
internal fun MaxHrZoneCard(hrMax: Int, zone5Bpm: Int, coachingOn: Boolean) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap), modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = "Max HR",
                    value = "$hrMax bpm",
                    accent = Palette.textPrimary,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = "Top zone",
                    value = "≥ $zone5Bpm bpm",
                    accent = if (coachingOn) Palette.accent else Palette.textTertiary,
                )
            }
            Text(
                if (coachingOn)
                    "Strap buzzes when you climb into Zone 5 (≥ $zone5Bpm bpm). Manage it in Automations → Haptic coaching."
                else
                    "Turn on HR-zone coaching in Automations for a wrist buzz when you reach Zone 5 (≥ $zone5Bpm bpm).",
                style = NoopType.footnote,
                color = Palette.textTertiary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - Live physiology (R-R thread + rolling RMSSD + proof tiles)

/**
 * The live R-R physiology block folded into Health: the connection-mode detail line, a rolling RMSSD
 * read-out, the beat-by-beat R-R liquid thread, and R-R / Event proof tiles. Exposed `internal` so the
 * Health screen composes it directly beneath the merged heart-rate hero.
 */
@Composable
internal fun PhysiologyStack(live: LiveState, activeConnection: Boolean) {
    val rmssd = rollingRMSSD(live.rrRecent)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Overline("Live Physiology")
                Text(connectionModeDetail(live, activeConnection), style = NoopType.headline, color = Palette.textPrimary)
            }
            if (rmssd != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("RMSSD", style = NoopType.footnote, color = Palette.textTertiary)
                    Text("${rmssd.roundToInt()} ms", style = NoopType.number(24f), color = Palette.metricCyan)
                }
            }
        }
        RRStrip(rrRecent = live.rrRecent)
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap), modifier = Modifier.fillMaxWidth()) {
            // Offline: show a muted "Offline" word (dimmed to textTertiary) instead of bare accent-
            // coloured em-dashes that read as broken live readouts. Real values + accents return on a
            // stream. Mirrors the macOS liveProofMetric(offline:).
            LiveProofMetric(
                Modifier.weight(1f), "R-R",
                if (activeConnection) (live.rr.lastOrNull()?.let { "$it ms" } ?: "—") else "Offline",
                Palette.metricCyan, offline = !activeConnection,
            )
            LiveProofMetric(
                Modifier.weight(1f), "Event",
                if (activeConnection) (live.lastEvent ?: "—") else "Offline",
                Palette.statusWarning, offline = !activeConnection,
            )
        }
    }
}

/** The recent R-R buffer as a live liquid THREAD — the beat-by-beat trace with a travelling glint +
 *  endpoint pulse (a single HR number can look frozen; a flowing thread can't). R-R intervals ARE the
 *  time between heartbeats, so the buffer is a genuine beat-by-beat series; the thread auto-normalises its
 *  own min/max, so the raw ms values feed it directly. Empty state shows a muted flat thread + the
 *  "Waiting…" caption. Same data binding (live.rrRecent) as the bar strip this replaced. */
@Composable
private fun RRStrip(rrRecent: List<Int>) {
    val values = rrRecent.takeLast(18)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (values.size >= 2) {
            // Live thread — flows (glint + pulse) as new intervals land. Heart-pink (LiquidThread default).
            LiquidThread(
                bpm = values.map { it.toDouble() },
                animated = true,
                height = 58.dp,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Empty / single-sample state: a muted flat hairline placeholder at the same height, so the
            // card doesn't jump when the first pair of intervals arrives and the thread takes over.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.hairline),
                )
            }
        }
        Text(
            if (values.isEmpty()) "Waiting for R-R intervals."
            else "Recent intervals: " + values.takeLast(5).joinToString(" · ") + " ms",
            style = NoopType.footnote,
            color = Palette.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One R-R / Event proof tile. When [offline] the value is dimmed to textTertiary (regardless of the
 *  passed accent) so an idle tile reads as a muted empty state, not a broken live readout in
 *  cyan/amber — matching the rrStrip's "Waiting for R-R intervals." treatment above. */
@Composable
private fun LiveProofMetric(modifier: Modifier, label: String, value: String, tint: Color, offline: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), style = NoopType.footnote, color = Palette.textTertiary)
        Text(
            value,
            style = NoopType.captionNumber,
            color = if (offline) Palette.textTertiary else tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// MARK: - Pure helpers

/** #56: a non-WHOOP live source (the Oura ring, and on Android any external HR source that drives
 *  [LiveState.streamingLiveHR]) that is connected and actively streaming live HR. It streams without a
 *  WHOOP encrypted bond, so `bonded`/`activeConnection` never trip. Twin of the iOS LiveView.ringStreaming. */
private fun ringStreaming(live: LiveState): Boolean = live.connected && live.streamingLiveHR

private fun connectionModeDetail(live: LiveState, activeConnection: Boolean): String = when {
    activeConnection && live.encryptedBond -> "Full strap stream is active."
    activeConnection || ringStreaming(live) -> "Heart rate stream is active."
    live.connected -> "Radio connected, stream not yet trusted."
    else -> "No live stream."
}

/** A "feel" RMSSD over the recent R-R buffer — time-gap-unaware on purpose (a live indicator, not a
 *  clinical figure; blanked on disconnect by clearedBiometrics). null until ≥3 intervals land. */
private fun rollingRMSSD(rrRecent: List<Int>): Double? {
    val values = rrRecent.takeLast(12)
    if (values.size < 3) return null
    val diffs = values.zipWithNext { a, b -> (b - a).toDouble() }
    val meanSquare = diffs.sumOf { it * it } / diffs.size
    return sqrt(meanSquare)
}

/**
 * Coarse relative-time label for the "History synced N ago" sync-status line. Pure + unit-tested
 * (RelativeAgoTest); [nowSec] is injectable for determinism. Buckets to just-now / min / h / d. (PR #85)
 * Shared by DevicesScreen and UpdatesInboxScreen — this is why the file is kept after the Live/Health fold.
 */
internal fun relativeAgo(epochSec: Long, nowSec: Long = System.currentTimeMillis() / 1000L): String {
    val d = (nowSec - epochSec).coerceAtLeast(0)
    return when {
        d < 60L -> "just now"
        d < 3600L -> "${d / 60L} min ago"
        d < 86_400L -> "${d / 3600L} h ago"
        else -> "${d / 86_400L} d ago"
    }
}

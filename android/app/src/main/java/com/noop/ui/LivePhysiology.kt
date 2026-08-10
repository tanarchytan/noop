package com.noop.ui

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.noop.R
import com.noop.ble.LiveState

/**
 * Live-HR physiology composables for the Health screen: [MaxHrZoneCard] and [PhysiologyStack] (its R-R
 * trace, rolling RMSSD and R-R / Event proof tiles). Exposed `internal` so the Health Monitor composes
 * them beneath its heart-rate hero.
 */

/**
 * Read-only Max-HR + top-zone card. Max HR is the age-based value from Settings; the Zone 5 entry
 * (≥ 90% of max) is where HR-zone coaching buzzes. Managing coaching lives in Automations.
 * Folded into Health (Live/Health merge).
 */
@Composable
internal fun MaxHrZoneCard(hrMax: Int, zone5Bpm: Int, coachingOn: Boolean) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap), modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                // The unit sits in the caption on both tiles rather than inside the value: carrying it
                // inline, "≥ 168 bpm" was the one figure too wide for its half and truncated mid-word.
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.vitals_live_max_hr),
                    value = "$hrMax",
                    caption = "bpm",
                    accent = Palette.textPrimary,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.vitals_live_top_zone),
                    value = "≥ $zone5Bpm",
                    caption = "bpm",
                    accent = if (coachingOn) Palette.accent else Palette.textTertiary,
                )
            }
            Text(
                stringResource(
                    if (coachingOn) R.string.vitals_live_coaching_on else R.string.vitals_live_coaching_off,
                    zone5Bpm,
                ),
                style = NoopType.footnote,
                color = Palette.textTertiary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// MARK: - Live physiology (R-R trace + rolling RMSSD + proof tiles)

/**
 * The live R-R physiology block: the connection-mode detail line, a rolling RMSSD read-out, the
 * beat-by-beat R-R trace, and R-R / Event proof tiles. Exposed `internal` so the Health screen composes
 * it directly beneath the heart-rate hero.
 */
@Composable
internal fun PhysiologyStack(live: LiveState, activeConnection: Boolean) {
    val rmssd = rollingRMSSD(live.rrRecent)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Overline(stringResource(R.string.vitals_live_physiology))
                Text(
                    stringResource(connectionModeDetail(live, activeConnection)),
                    style = NoopType.headline, color = Palette.textPrimary,
                )
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
            // stream.
            LiveProofMetric(
                Modifier.weight(1f), "R-R",
                if (activeConnection) (live.rr.lastOrNull()?.let { "$it ms" } ?: "—")
                else stringResource(R.string.vitals_live_offline),
                Palette.metricCyan, offline = !activeConnection,
            )
            LiveProofMetric(
                Modifier.weight(1f), stringResource(R.string.vitals_live_event),
                if (activeConnection) (live.lastEvent ?: "—")
                else stringResource(R.string.vitals_live_offline),
                Palette.statusWarning, offline = !activeConnection,
            )
        }
    }
}

/** Height of the R-R trace and of the flat hairline that stands in for it on a single sample. */
private val RR_TRACE_HEIGHT = 58.dp

/** The recent R-R buffer as a beat-by-beat sparkline. R-R intervals ARE the time between heartbeats, so
 *  the buffer is a genuine series and the trace normalises to its own min/max. Below two samples a muted
 *  flat hairline holds the same height, so the card does not jump when the first pair lands. */
@Composable
private fun RRStrip(rrRecent: List<Int>) {
    val values = rrRecent.takeLast(18)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        if (values.size >= 2) {
            TileSparkline(
                values = values.map { it.toDouble() },
                color = Palette.metricRose,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RR_TRACE_HEIGHT),
            )
        } else if (values.isNotEmpty()) {
            // A stream that has landed one beat holds the trace's height, so the card does not jump when
            // the pair completes. Nothing at all draws nothing: the reserved band and its hairline read as
            // an empty gap under a rule floating outside any card.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RR_TRACE_HEIGHT),
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
            if (values.isEmpty()) stringResource(R.string.vitals_live_waiting_rr)
            else stringResource(
                R.string.vitals_live_recent_intervals,
                values.takeLast(5).joinToString(" · "),
            ),
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
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
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

/** A non-WHOOP live source (the Oura ring, and on Android any external HR source that drives
 *  [LiveState.streamingLiveHR]) that is connected and actively streaming live HR. It streams without a
 *  WHOOP encrypted bond, so `bonded`/`activeConnection` never trip. */
private fun ringStreaming(live: LiveState): Boolean = live.connected && live.streamingLiveHR

@StringRes
private fun connectionModeDetail(live: LiveState, activeConnection: Boolean): Int = when {
    activeConnection && live.encryptedBond -> R.string.vitals_live_mode_full
    activeConnection || ringStreaming(live) -> R.string.vitals_live_mode_hr
    live.connected -> R.string.vitals_live_mode_untrusted
    else -> R.string.vitals_live_mode_none
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

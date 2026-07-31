package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.analytics.SleepStageTotals
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Whether the night's sleep-performance score is WHOOP's imported figure or NOOP's on-device
 * approximation, so the hero is honest about provenance.
 */
internal fun restHeroSource(imported: ImportedSleepSeries, days: List<DailyMetric>): String {
    val lastDay = days.lastOrNull()?.day
    return if (lastDay != null && imported.performance[lastDay] != null) "Whoop" else "On-device"
}

// MARK: - HERO — stage breakdown for the navigated night

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Hero(
    display: HeroDisplay?,
    clock: String?,
    nightOffset: Int,
    lastIndex: Int,
    onNavigate: (Int) -> Unit,
    session: SleepSession? = null,
    onUpdateTimes: (SleepSession, Long, Long, Boolean) -> Unit = { _, _, _, _ -> },
    onDeleteSession: (SleepSession) -> Unit = {},
    onAddNap: (Long, Long) -> Unit = { _, _ -> },
    onPickNightDate: ((LocalDate) -> Unit)? = null,
    napBlocks: List<SleepSession> = emptyList(),
    // The LEARNED habitual midsleep, passed to the main-night selector so the "why this is your main sleep"
    // reason matches the block the hero shows. null = cold-start band.
    habitualMidsleepSec: Long? = null,
    // Per-epoch MOTION for the main-night GROUP, laid in group order by `selectNight`. Empty → honest empty
    // state. Drawn UNDER the hypnogram on the same timeline.
    motionEpochs: List<Double> = emptyList(),
    // Whole-group time-in-bed minutes for a fragmented night: Σ fragment windows, gaps excluded, computed by
    // `selectNight`. Null for single-block days → the session-window / stage-total fallbacks apply.
    groupInBedMin: Double? = null,
    // The whole bridged night's clock window: on a split night `session` is one fragment, so its end is
    // NOT the night's wake — the Asleep/Woke row and the hypnogram axis read these instead. Null (single-block
    // days) falls back to the session window.
    windowOnsetTs: Long? = null,
    windowWakeTs: Long? = null,
    // The night's downsampled heart rate over the padded sleep window, read by SleepScreen. Empty → the
    // chart's honest "no heart-rate detail" note.
    hrPoints: List<TimelinePoint> = emptyList(),
    // The personal mean minutes per stage and asleep total, marked on each stage row so the night reads
    // against the wearer's own habit. An absent mean simply has no marker.
    typicalByStage: Map<String, Double?> = emptyMap(),
    typicalAsleepMin: Double? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        NightNavHeader(nightOffset, lastIndex, clock, onNavigate, session, onUpdateTimes, onDeleteSession, onAddNap, onPickNightDate)
        // The night's clock window (fell-asleep / woke) as its own labelled row — the nav-header caption
        // truncates between the chevrons on a phone, hiding the two times people look for first. Shown for
        // every night with a session. The window is the WHOLE night's, not the edit-anchor fragment's endTs.
        session?.let { SleepWindowRow(windowOnsetTs ?: it.effectiveStartTs, windowWakeTs ?: it.effectiveEndTs) }
        if (display == null) {
            // Honest fallback: no usable stage data for this night — never silently substitute another
            // night's hypnogram.
            NoopCard(tint = Palette.restColor) {
                Text(
                    "No stage data recorded for this night.",
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else {
            val s = display.stages
            // After a bed/wake edit the session window is the source of truth for time-in-bed, so the
            // subtitle tracks the edit via EFFECTIVE onset. A fragmented night prefers the GROUP total —
            // `session` is only the WINNING fragment, so its window alone undershoots the summed minutes.
            val inBedMin = groupInBedMin
                ?: session?.let { (it.effectiveEndTs - it.effectiveStartTs) / 60.0 }
                ?: s.total
            // The axis spans the WHOLE night (to the group's last wake); labelling it off the session
            // fragment's own end cut the clock labels short on a split night. The HR chart shares it.
            val axisOnsetTs = windowOnsetTs ?: session?.effectiveStartTs
            val axisWakeTs = windowWakeTs ?: session?.effectiveEndTs
            SleepStagesCard(
                stages = s,
                realSegments = display.realSegments,
                typicalByStage = typicalByStage,
                typicalAsleepMin = typicalAsleepMin,
                inBedMin = inBedMin,
                efficiencyText = display.efficiencyText,
                onsetTs = axisOnsetTs,
                wakeTs = axisWakeTs,
                motionEpochs = motionEpochs,
                hrPoints = hrPoints,
            )
        }
        // Naps card: the day's blocks OTHER than the main night, each editable / deletable via the same
        // mechanism main sleep uses, plus a Main / Nap(s) / Total split.
        if (session != null) {
            NapsCard(
                main = session,
                naps = napBlocks,
                onEditNapTimes = onUpdateTimes,
                onDeleteNap = onDeleteSession,
                habitualMidsleepSec = habitualMidsleepSec,
            )
        }
    }
}

/**
 * Naps card: the day's MAIN sleep is the hero above; this lists every OTHER block (afternoon naps,
 * split-sleep) as an editable / deletable row, plus — once the day has a nap — a Main / Nap(s) / Total
 * split. Reuses the main-sleep edit/delete callbacks, which key off each row's immutable (deviceId, startTs).
 */
@Composable
private fun NapsCard(
    main: SleepSession,
    naps: List<SleepSession>,
    onEditNapTimes: (SleepSession, Long, Long, Boolean) -> Unit,
    onDeleteNap: (SleepSession) -> Unit,
    // The LEARNED habitual midsleep, fed to the main-night selector so the "why this is your main sleep"
    // reason matches the hero. null = cold-start band.
    habitualMidsleepSec: Long? = null,
) {
    val mainMin = (main.effectiveEndTs - main.effectiveStartTs) / 60.0
    val napMin = naps.sumOf { (it.effectiveEndTs - it.effectiveStartTs) / 60.0 }
    NoopCard(padding = Metrics.space14, tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Text("DAYTIME SLEEP", style = NoopType.overline, color = Palette.textTertiary)
            Text("Naps", style = NoopType.subhead, color = Palette.textPrimary)
            if (naps.isNotEmpty()) {
                // Main / Nap(s) / Total split — only meaningful once a nap exists. Total = main + naps.
                Row(modifier = Modifier.fillMaxWidth()) {
                    NapSummaryCell("Main sleep", durationText(mainMin), Modifier.weight(1f))
                    NapSummaryCell("Nap(s)", durationText(napMin), Modifier.weight(1f))
                    NapSummaryCell("Total", durationText(mainMin + napMin), Modifier.weight(1f))
                }
            }
            if (naps.isEmpty()) {
                Text(
                    "No naps recorded for this day.",
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            } else {
                naps.forEachIndexed { i, nap ->
                    NapRow(nap, onEditNapTimes, onDeleteNap)
                    if (i < naps.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(Metrics.divider).background(Palette.hairline))
                    }
                }
            }
            // Provenance + the "why this is your main sleep" explainer: the badge names the REAL per-day
            // merge winner; the info affordance reveals the reason for the pick.
            Box(Modifier.fillMaxWidth().height(Metrics.divider).background(Palette.hairline))
            MainSleepFooter(main = main, naps = naps, habitualMidsleepSec = habitualMidsleepSec)
        }
    }
}

/** The Naps card footer: the night's provenance badge — the REAL per-day merge winner. */
@Composable
private fun MainSleepFooter(
    main: SleepSession,
    naps: List<SleepSession>,
    habitualMidsleepSec: Long?,
) {
    // The real merge winner, the same wording the By-Day badge uses ("On-device" / "Whoop" / "Apple
    // Health"), keyed on the main block's source.
    val (sourceText, sourceTint) = daySourceBadge(main.deviceId)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SourceBadge(text = sourceText, tint = sourceTint)
    }
}

/**
 * The "why this is your main sleep" reason for the day's [blocks], driven by
 * [SleepStageTotals.MainNightReason] so the explainer states what the selector decided. Resolved via the
 * same [SleepStageTotals.mainNightSelection] API the analytics pick uses. null only when the day has no blocks.
 */
internal fun mainSleepReasonText(blocks: List<SleepSession>, habitualMidsleepSec: Long?): String? {
    val sel = SleepStageTotals.mainNightSelectionScored(
        blocks.map { SleepStageTotals.scoredBlock(it.effectiveStartTs, it.effectiveEndTs, it.stagesJSON) },
        uiTzOffsetSec(),
        habitualMidsleepSec,
    ) ?: return null
    // Round to whole minutes for "Xh Ym"; `asleepSec` is the winner's decoded sleep, not its time in bed.
    val dur = durationText(sel.asleepSec / 60.0)
    return when (sel.reason) {
        SleepStageTotals.MainNightReason.onlyBlock ->
            "This is your only sleep block today."
        SleepStageTotals.MainNightReason.longest ->
            "Picked as your main sleep because it was your longest block ($dur)."
        SleepStageTotals.MainNightReason.longestNearUsual ->
            "Picked as your main sleep because it was your longest block ($dur), near your usual bedtime."
        SleepStageTotals.MainNightReason.alignedToUsual ->
            "Picked as your main sleep because it started near your usual sleep time."
    }
}

/** One Main / Nap(s) / Total cell: an overline label over a duration number. */
@Composable
private fun NapSummaryCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = NoopType.overline, color = Palette.textTertiary)
        Text(value, style = NoopType.captionNumber, color = Palette.textPrimary)
    }
}

@Composable
private fun SleepWindowRow(onsetTs: Long, wakeTs: Long) {
    val asleep = clockTimeLabel(onsetTs)
    val woke = clockTimeLabel(wakeTs)
    // A frosted Rest-tinted card so the window row sits in the same colour world as the rest of the screen.
    NoopCard(
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "Fell asleep at $asleep, woke at $woke"
        },
        padding = Metrics.space14,
        tint = Palette.restColor,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SleepTime(icon = Icons.Filled.Bedtime, label = "Asleep", value = asleep)
            Spacer(Modifier.width(Metrics.space12))
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .width(Metrics.divider)
                    .background(Palette.hairline),
            )
            Spacer(Modifier.width(Metrics.space12))
            SleepTime(icon = Icons.Filled.WbSunny, label = "Woke", value = woke)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SleepTime(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null, // row carries the combined description
            tint = Palette.restColor,
            modifier = Modifier.size(20.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Overline(label, color = Palette.textTertiary)
            Text(value, style = NoopType.number(22f), color = Palette.textPrimary, maxLines = 1)
        }
    }
}

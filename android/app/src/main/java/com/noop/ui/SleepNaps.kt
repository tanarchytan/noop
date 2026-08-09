package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.noop.R
import com.noop.analytics.SleepStageTotals
import com.noop.data.SleepSession

/**
 * The transient UNDO strip after a suppressing sleep delete: a Rest-tinted card stating the window NOOP
 * won't re-detect, plus an Undo button. Undo restores the deleted row into its original namespace and
 * lifts the tombstone.
 */
@Composable
internal fun SleepUndoBanner(session: SleepSession, onUndo: () -> Unit) {
    // Branch the copy on userEdited: a hand-edited or added row writes no tombstone, so the
    // "won't detect again" promise applies only to a DETECTED delete.
    val message = if (session.userEdited) {
        stringResource(R.string.sleep_deleted)
    } else {
        stringResource(
            R.string.sleep_deleted_suppressed,
            clockTimeLabel(session.effectiveStartTs),
            clockTimeLabel(session.effectiveEndTs),
        )
    }
    val undoDescription = stringResource(R.string.sleep_undo_a11y)
    NoopCard(tint = Palette.restColor) {
        Row(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = message },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        ) {
            Text(message, style = NoopType.footnote, color = Palette.textSecondary, modifier = Modifier.weight(1f))
            TextButton(
                onClick = onUndo,
                modifier = Modifier.semantics { contentDescription = undoDescription },
            ) {
                Text(stringResource(R.string.sleep_undo), style = NoopType.subhead, color = Palette.restColor)
            }
        }
    }
}

/**
 * Naps card: the day's MAIN sleep is the stage card above; this lists every OTHER block (afternoon
 * naps, split sleep) as an editable row, plus — once the day has a nap — a Main / Nap(s) / Total split.
 * Editing a nap opens the same sheet the main night uses.
 */
@Composable
internal fun NapsCard(
    main: SleepSession,
    naps: List<SleepSession>,
    onEditNap: (SleepSession) -> Unit,
) {
    val mainMin = (main.effectiveEndTs - main.effectiveStartTs) / 60.0
    val napMin = naps.sumOf { (it.effectiveEndTs - it.effectiveStartTs) / 60.0 }
    NoopCard(padding = Metrics.space14, tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Text(
                stringResource(R.string.sleep_daytime),
                style = NoopType.overline,
                color = Palette.textTertiary,
            )
            if (naps.isEmpty()) {
                Text(
                    stringResource(R.string.sleep_no_naps),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    NapSummaryCell(
                        stringResource(R.string.sleep_nap_main),
                        durationText(mainMin),
                        Modifier.weight(1f),
                    )
                    NapSummaryCell(
                        stringResource(R.string.sleep_nap_naps),
                        durationText(napMin),
                        Modifier.weight(1f),
                    )
                    NapSummaryCell(
                        stringResource(R.string.sleep_nap_total),
                        durationText(mainMin + napMin),
                        Modifier.weight(1f),
                    )
                }
                naps.forEachIndexed { i, nap ->
                    NapRow(nap, onEditNap)
                    if (i < naps.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(Metrics.divider).background(Palette.hairline))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(Metrics.divider).background(Palette.hairline))
            MainSleepFooter(main)
        }
    }
}

/** One nap row: its clock window and duration, with the edit affordance that opens the sheet. */
@Composable
internal fun NapRow(nap: SleepSession, onEditNap: (SleepSession) -> Unit) {
    val window = "${clockTimeLabel(nap.effectiveStartTs)} - ${clockTimeLabel(nap.effectiveEndTs)}"
    val durMin = (nap.effectiveEndTs - nap.effectiveStartTs) / 60.0
    val napDescription = stringResource(R.string.sleep_nap_a11y, window, durationText(durMin))
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // A11Y: the readable label sits on the NON-actionable leading content as one merged node, so the
        // edit button stays individually focusable.
        Row(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { contentDescription = napDescription },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Bedtime, contentDescription = null, tint = Palette.restColor, modifier = Modifier.size(Metrics.iconSmall))
            Spacer(Modifier.width(Metrics.space10))
            Column {
                Text(window, style = NoopType.body, color = Palette.textPrimary)
                Text(durationText(durMin), style = NoopType.overline, color = Palette.textTertiary)
            }
        }
        IconButton(onClick = { onEditNap(nap) }) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = stringResource(
                    if (nap.userEdited) R.string.sleep_nap_edit_edited else R.string.sleep_nap_edit,
                ),
                tint = Palette.restColor,
                modifier = Modifier.size(Metrics.iconSmall),
            )
        }
    }
}

/** The card footer: the night's provenance badge — the REAL per-day merge winner. */
@Composable
private fun MainSleepFooter(main: SleepSession) {
    val (sourceText, sourceTint) = daySourceBadge(main.deviceId)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SourceBadge(text = sourceText, tint = sourceTint)
    }
}

/**
 * The "why this is your main sleep" reason for the day's [blocks], driven by
 * [SleepStageTotals.MainNightReason] so the explainer states what the selector decided. Null only when
 * the day has no blocks.
 */
internal fun mainSleepReasonText(blocks: List<SleepSession>, habitualMidsleepSec: Long?): String? {
    val sel = SleepStageTotals.mainNightSelectionScored(
        blocks.map { SleepStageTotals.scoredBlock(it.effectiveStartTs, it.effectiveEndTs, it.stagesJSON) },
        uiTzOffsetSec(),
        habitualMidsleepSec,
    ) ?: return null
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

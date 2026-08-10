package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.noop.R
import com.noop.analytics.AgreementState
import com.noop.analytics.ContributingSource
import com.noop.analytics.FusedMetricPoint
import com.noop.analytics.FusionSource
import com.noop.analytics.MetricArbitrationPolicy
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - FusedRecordScreen — "Your Data, Fused" (v5 — Local Multi-Device Fusion)
//
// The fused per-metric record. For each core metric
// it shows the BEST-sourced value, a provenance badge naming the source, the plain published reason
// from MetricArbitrationPolicy ("counts directly" / "best stager"), and the inline agreement state
// from FusionResolver (agree / minor delta / conflict). On a conflict it opens a compare detail
// listing EVERY source side by side and which one NOOP is using and why — it NEVER silently merges.
//
// SELF-CONTAINED: the screen takes a fully-resolved [FusedRecord] (the repository adapter that pulls
// today's per-source metrics and runs FusionResolver.resolve lives in Wave 3). It does no I/O and
// never touches AppViewModel directly, so it compiles and previews from a fixture. This file owns only
// PRESENTATION: a value formatter + the row/dialog chrome, built from the locked component set
// (NoopCard / StatePill / SourceBadge / SectionHeader / ScreenScaffold) and tokens (Palette / NoopType
// / Metrics).
//
// Wellness framing only: a source is "higher-trust for this metric" with a plain reason; we never say
// a number is accurate / correct / clinical, never flag a value as concerning.

// MARK: - Presentation model (the read-model this screen consumes)

/**
 * One resolved metric row for the fused record — the engine's [FusedMetricPoint] plus the display
 * [label] and an optional per-metric [accent]. The Wave 3 repository adapter builds these from the
 * rows it already loads.
 */
data class FusedRow(
    val point: FusedMetricPoint,
    val label: String,
    val accent: androidx.compose.ui.graphics.Color? = null,
)

/**
 * The whole fused day-record this screen renders. Built by the Wave 3 repository adapter; passed in so
 * the screen stays pure and previewable. [dayOwner] is the device that owns the day's scores (from
 * DayOwnerResolver) shown as the day badge; [contributingSourceCount] gates the single-source
 * degradation (≤ 1 ⇒ a plain record with no provenance noise).
 */
data class FusedRecord(
    val rows: List<FusedRow>,
    val dayOwner: FusionSource?,
    val contributingSourceCount: Int,
)

// MARK: - Screen

@Composable
fun FusedRecordScreen(
    record: FusedRecord,
    dayLabel: String = stringResource(R.string.common_today),
    modifier: Modifier = Modifier,
) {
    // The metric currently open in the conflict-compare dialog (null = closed).
    var comparing by remember { mutableStateOf<FusedRow?>(null) }

    val isMultiSource = record.contributingSourceCount > 1
    val deviceNoun = stringResource(R.string.fused_this_device)

    val subtitle = if (isMultiSource) {
        stringResource(
            R.string.fused_subtitle_multi,
            dayLabel,
            record.contributingSourceCount,
            deviceNoun,
        )
    } else {
        stringResource(R.string.fused_subtitle_single, dayLabel, deviceNoun)
    }

    ScreenScaffold(
        title = stringResource(R.string.fused_title),
        subtitle = subtitle,
        modifier = modifier,
    ) {
        if (isMultiSource) DayBadgeRow(record.dayOwner)

        if (record.rows.isEmpty()) {
            DataPendingNote(
                title = stringResource(R.string.fused_empty_title),
                body = stringResource(R.string.fused_empty_body),
            )
        } else {
            NoopCard(padding = 0.dp) {
                Column {
                    record.rows.forEachIndexed { index, row ->
                        FusedMetricRow(
                            row = row,
                            showProvenance = isMultiSource,
                            onCompare = { comparing = row },
                        )
                        if (index < record.rows.lastIndex) {
                            HorizontalDivider(
                                color = Palette.hairline,
                                modifier = Modifier.padding(start = Metrics.cardPadding),
                            )
                        }
                    }
                }
            }
        }

        PrivacyNote(deviceNoun)
        DisclaimerNote()
    }

    comparing?.let { row ->
        ConflictCompareDialog(row = row, onDismiss = { comparing = null })
    }
}

/** "Today's scores owned by WHOOP" — the scores' single-owner, made honest. */
@Composable
private fun DayBadgeRow(owner: FusionSource?) {
    val text = if (owner != null) {
        stringResource(R.string.fused_day_owner, owner.displayName)
    } else {
        stringResource(R.string.fused_day_owner_none)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.space4)
            .semantics { contentDescription = text },
    ) {
        Icon(
            Icons.Outlined.VerifiedUser,
            contentDescription = null,
            tint = Palette.accent,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text,
            style = NoopType.footnote,
            color = if (owner != null) Palette.textSecondary else Palette.textTertiary,
        )
    }
}

@Composable
private fun PrivacyNote(deviceNoun: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Metrics.space4),
    ) {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(13.dp),
        )
        Text(
            stringResource(R.string.fused_privacy, deviceNoun),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
    }
}

/** The pillar's standing non-clinical line. Plain, wellness only. */
@Composable
private fun DisclaimerNote() {
    Text(
        stringResource(R.string.fused_disclaimer),
        style = NoopType.footnote,
        color = Palette.textTertiary,
        modifier = Modifier.padding(horizontal = Metrics.space4),
    )
}

// MARK: - One fused metric row

@Composable
private fun FusedMetricRow(
    row: FusedRow,
    showProvenance: Boolean,
    onCompare: () -> Unit,
) {
    val tempUnit = UnitPrefs.temperature(LocalContext.current)
    val point = row.point
    val accent = row.accent ?: Palette.textPrimary
    val isConflict = point.agreement == AgreementState.CONFLICT

    val rowModifier = if (isConflict) {
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.fused_compare_sources),
            ) { onCompare() }
    } else {
        Modifier.fillMaxWidth()
    }

    Column(
        modifier = rowModifier
            .padding(horizontal = Metrics.cardPadding, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        // Top line: metric label + the best-sourced value, right-aligned.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.label,
                style = NoopType.headline,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Metrics.space8))
            AutoSizeValue(
                FusionFormat.value(point.value, point.metric, tempUnit),
                style = NoopType.number(20f),
                color = accent,
            )
        }

        if (showProvenance) {
            // Provenance: a source badge + the published one-line reason.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                SourceBadge(
                    stringResource(R.string.fused_from_source, point.winningSource.displayName),
                    tint = Palette.accent,
                )
                point.contributors.firstOrNull()?.reason?.let { reason ->
                    Text(reason, style = NoopType.footnote, color = Palette.textTertiary)
                }
            }

            AgreementLine(tempUnit = tempUnit, point = point, onCompare = onCompare)
        }
    }
}

@Composable
private fun AgreementLine(point: FusedMetricPoint, tempUnit: TemperatureUnit, onCompare: () -> Unit) {
    val other = point.contributors.drop(1).firstOrNull()
    when (point.agreement) {
        AgreementState.SINGLE -> Unit

        AgreementState.AGREE -> if (other != null) {
            Text(
                stringResource(
                    R.string.fused_agrees,
                    other.source.displayName,
                    FusionFormat.value(other.value, point.metric, tempUnit),
                ),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }

        AgreementState.MINOR_DELTA -> if (other != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
            ) {
                StatePill(
                    stringResource(R.string.fused_differs_slightly),
                    tone = StrandTone.Neutral,
                    showsDot = false,
                )
                Text(
                    stringResource(
                        R.string.fused_source_value,
                        other.source.displayName,
                        FusionFormat.value(other.value, point.metric, tempUnit),
                    ),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
        }

        AgreementState.CONFLICT -> Row(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCompare() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
        ) {
            StatePill(stringResource(R.string.fused_sources_differ), tone = StrandTone.Warning)
            Text(
                conflictSummary(point, tempUnit),
                style = NoopType.footnote,
                color = Palette.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun conflictSummary(point: FusedMetricPoint, tempUnit: TemperatureUnit): String {
    val other = point.contributors.drop(1).firstOrNull()
    return if (other == null) {
        stringResource(R.string.fused_tap_to_compare)
    } else {
        stringResource(
            R.string.fused_conflict_summary,
            other.source.displayName,
            FusionFormat.value(other.value, point.metric, tempUnit),
        )
    }
}

// MARK: - Conflict-compare dialog

/**
 * A small read-only dialog: every source's value for the metric, side by side, with the one NOOP is
 * using marked and its trust reason named. NOOP never adjudicates which is "correct" — it shows the
 * spread and explains its best-signal pick. Transparency, not diagnosis.
 */
@Composable
private fun ConflictCompareDialog(row: FusedRow, onDismiss: () -> Unit) {
    val point = row.point
    Dialog(onDismissRequest = onDismiss) {
        NoopCard(padding = Metrics.cardPadding) {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Metrics.gap),
            ) {
                SectionHeader(
                    title = row.label,
                    overline = stringResource(R.string.fused_sources_differ),
                )
                Text(
                    stringResource(R.string.fused_compare_body),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )

                Column {
                    point.contributors.forEachIndexed { index, contrib ->
                        ContributorRow(
                            contrib = contrib,
                            metricKey = point.metric,
                            isWinner = index == 0,
                        )
                        if (index < point.contributors.lastIndex) {
                            HorizontalDivider(color = Palette.hairline)
                        }
                    }
                }

                point.contributors.firstOrNull()?.let { winner ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = Palette.accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource(
                                R.string.fused_winner_reason,
                                winner.source.displayName,
                                winner.reason,
                            ),
                            style = NoopType.subhead,
                            color = Palette.textSecondary,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            stringResource(R.string.fused_done),
                            style = NoopType.headline,
                            color = Palette.accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributorRow(
    contrib: ContributingSource,
    metricKey: String,
    isWinner: Boolean,
) {
    val tempUnit = UnitPrefs.temperature(LocalContext.current)
    val rowLabel = stringResource(
        if (isWinner) R.string.fused_contributor_a11y_in_use else R.string.fused_contributor_a11y,
        contrib.source.displayName,
        FusionFormat.value(contrib.value, metricKey, tempUnit),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Metrics.space12)
            .semantics { contentDescription = rowLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Metrics.space4),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                SourceBadge(
                    contrib.source.displayName,
                    tint = if (isWinner) Palette.accent else Palette.textTertiary,
                )
                if (isWinner) {
                    StatePill(
                        stringResource(R.string.fused_using),
                        tone = StrandTone.Accent,
                        showsDot = true,
                    )
                }
            }
            Text(contrib.reason, style = NoopType.footnote, color = Palette.textTertiary)
        }
        Text(
            FusionFormat.value(contrib.value, metricKey, tempUnit),
            style = NoopType.number(18f),
            color = if (isWinner) Palette.textPrimary else Palette.textSecondary,
        )
    }
}

// MARK: - Display formatting (presentation only — the engine returns raw Doubles + raw keys)

/**
 * Formats a fused metric's [Double] value for display by its resolver key. Pure + local to this
 * screen: the engine deals in numbers, the UI owns
 * units. Sleep/duration keys read as "7h 12m"; temp as "34.1°C"; HR/HRV/steps as integers + unit.
 */
object FusionFormat {
    fun value(v: Double, metricKey: String, tempUnit: TemperatureUnit): String =
        when (MetricArbitrationPolicy.kind(metricKey)) {
            MetricArbitrationPolicy.MetricKind.RESTING_HR,
            MetricArbitrationPolicy.MetricKind.HEART_RATE -> "${v.roundToInt()} bpm"
            MetricArbitrationPolicy.MetricKind.HRV -> "${v.roundToInt()} ms"
            MetricArbitrationPolicy.MetricKind.SPO2 -> "${v.roundToInt()}%"
            MetricArbitrationPolicy.MetricKind.SKIN_TEMP ->
                UnitFormatter.temperatureDeltaFromCelsius(v, tempUnit)
            MetricArbitrationPolicy.MetricKind.STEPS -> integerGrouped(v)
            MetricArbitrationPolicy.MetricKind.SLEEP -> duration(v)
            MetricArbitrationPolicy.MetricKind.CALORIES -> "${integerGrouped(v)} kcal"
            MetricArbitrationPolicy.MetricKind.OTHER ->
                if (v == Math.floor(v)) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
        }

    /** "8,420" — grouped integer. */
    private fun integerGrouped(v: Double): String =
        NumberFormat.getIntegerInstance().format(v.roundToInt())

    /** "7h 12m" from a minutes value; "52m" under an hour; "0m" for nothing. */
    private fun duration(minutes: Double): String {
        val total = maxOf(0, minutes.roundToInt())
        val h = total / 60
        val m = total % 60
        return if (h == 0) "${m}m" else "${h}h ${m}m"
    }
}

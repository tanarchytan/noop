package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.BalanceRead
import com.noop.analytics.FocalPoint
import com.noop.analytics.RestScorer
import com.noop.analytics.StrainScorer
import com.noop.analytics.WeeklyDigest
import com.noop.analytics.WeeklyDigestEngine
import com.noop.analytics.WeeklyMetric
import com.noop.analytics.WeeklyMetricSummary
import com.noop.data.DailyMetric
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Weekly Digest
//
// A deterministic, offline "week in review".
// Reads the merged daily history from the view model, pulls each
// tracked metric into a "yyyy-MM-dd"→value map, and feeds the pure
// WeeklyDigestEngine to produce a Monday-anchored summary: per-metric this-week
// mean + week-over-week delta + vs-baseline, the biggest movers, a strain-vs-recovery
// balance read, and 1–2 focal points this file words. No AI, no network.
//
// Two surfaces are exposed so navigation can wire whichever it wants:
//   • WeeklyDigestCard  — an embeddable card (drop into Today / Trends).
//   • WeeklyDigestScreen — a full ScreenScaffold screen (for a nav destination).
// Both share WeeklyDigestContent so they never drift. Framing is informational
// (non-clinical), consistent with the app disclaimer.

/**
 * The engine's Effort display factor for the user's scale: a [FocalPoint.Mover] carries stored
 * 0-100 Effort means, so the 0-21 toggle rescales them for display only. 1.0 leaves every figure
 * identical to the pre-toggle output.
 */
internal fun effortDisplayFactor(scale: EffortScale): Double =
    if (scale == EffortScale.WHOOP) StrainScorer.effortToWhoopDayStrain else 1.0

/**
 * Build the weekly digest for the week containing today's logical local day from a
 * [DailyMetric] history. Extracts each metric into a day→value map and hands it to the
 * pure engine. [effortDisplayFactor] follows the Effort display-scale toggle so the
 * engine's focal sentences quote Effort on the scale the user reads everywhere else.
 */
fun buildWeeklyDigest(
    days: List<DailyMetric>,
    anchorDay: String = logicalDayKeyNow(),
    effortDisplayFactor: Double = 1.0,
): WeeklyDigest {
    val charge = HashMap<String, Double>()
    val effort = HashMap<String, Double>()
    val rest = HashMap<String, Double>()
    val rhr = HashMap<String, Double>()
    val hrv = HashMap<String, Double>()
    for (d in days) {
        d.recovery?.let { charge[d.day] = it }
        d.strain?.let { effort[d.day] = it }
        // Rest = the sleep-performance composite recomputed on the persisted day.
        RestScorer.restFromDaily(d)?.let { rest[d.day] = it }
        d.restingHr?.let { rhr[d.day] = it.toDouble() }
        d.avgHrv?.let { hrv[d.day] = it }
    }
    return WeeklyDigestEngine.build(
        byMetric = mapOf(
            WeeklyMetric.CHARGE to charge,
            WeeklyMetric.EFFORT to effort,
            WeeklyMetric.REST to rest,
            WeeklyMetric.RHR to rhr,
            WeeklyMetric.HRV to hrv,
        ),
        anchorDay = anchorDay,
        effortDisplayFactor = effortDisplayFactor,
    )
}

// MARK: - Shared content

private val DISPLAY_ORDER = listOf(
    WeeklyMetric.CHARGE, WeeklyMetric.EFFORT, WeeklyMetric.REST, WeeklyMetric.HRV, WeeklyMetric.RHR,
)

/**
 * The inner content shared by the card and the full screen. [compact] trims the metric
 * grid to the headline rows for the card; the full screen shows everything plus a footer.
 */
@Composable
fun WeeklyDigestContent(digest: WeeklyDigest, compact: Boolean = false) {
    // the Effort row follows the Effort display-scale toggle like every other Effort
    // read-out in the app. Read once here, threaded to the
    // rows, so a 0-21 user can't see "Effort 22" beside a Trends chart reading 4.6.
    val effortScale = UnitPrefs.effortScale(LocalContext.current)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
        // Header.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Overline(stringResource(R.string.trends_week_in_review))
                Text(weekRangeLabel(digest), style = NoopType.title2, color = Palette.textPrimary)
            }
            val daysA11y = stringResource(R.string.digest_a11y_days_with_data, digest.daysWithData)
            Text(
                stringResource(R.string.digest_days_with_data, digest.daysWithData),
                style = NoopType.footnote,
                color = Palette.textSecondary,
                modifier = Modifier.semantics { contentDescription = daysA11y },
            )
        }

        // Focal points — the plain-English read, most salient first.
        if (digest.focalPoints.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                digest.focalPoints.forEach { FocalRow(focalSentence(it)) }
            }
        }

        HorizontalDivider(color = Palette.hairline)

        // Per-metric rows.
        val rows = (if (compact) listOf(WeeklyMetric.CHARGE, WeeklyMetric.EFFORT, WeeklyMetric.REST)
        else DISPLAY_ORDER).mapNotNull { digest.summary(it) }
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            rows.forEach { MetricRow(it, effortScale) }
        }

        if (!compact) {
            HorizontalDivider(color = Palette.hairline)
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                digest.restScoreSD?.let { sd ->
                    Text(
                        stringResource(R.string.digest_sleep_steadiness, fmt1(sd)),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Text(
                    stringResource(balanceSentence(digest.balance)),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
                Text(
                    stringResource(R.string.digest_disclaimer),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun FocalRow(line: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = line },
    ) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Palette.accent,
            modifier = Modifier.size(16.dp),
        )
        Text(line, style = NoopType.subhead, color = Palette.textPrimary)
    }
}

@Composable
private fun MetricRow(s: WeeklyMetricSummary, effortScale: EffortScale) {
    val a11y = rowAccessibility(s, effortScale)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = a11y },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        Text(
            stringResource(metricLabel(s.metric)),
            style = NoopType.subhead,
            color = Palette.textSecondary,
            modifier = Modifier.width(92.dp),
        )
        Text(
            meanText(s, effortScale),
            style = NoopType.bodyNumber,
            color = Palette.textPrimary,
            // 84 (was 64) so the Effort scale read-out ("21.6 / 100") fits on one line.
            modifier = Modifier.width(84.dp),
        )
        Spacer(Modifier.weight(1f))
        DeltaChip(s)
    }
}

@Composable
private fun DeltaChip(s: WeeklyMetricSummary) {
    val tone = chipTone(s)
    val arrow: ImageVector = when {
        s.wowDelta > 0 -> Icons.Filled.ArrowUpward
        s.wowDelta < 0 -> Icons.Filled.ArrowDownward
        else -> Icons.Filled.Remove
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .background(tone.copy(alpha = 0.12f), RoundedCornerShape(Metrics.cornerPill))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clearAndSetSemantics { },
    ) {
        Icon(arrow, contentDescription = null, tint = tone, modifier = Modifier.size(10.dp))
        Text(deltaText(s), style = NoopType.captionNumber, color = tone)
    }
}

// MARK: - Formatting

private fun weekRangeLabel(digest: WeeklyDigest): String =
    "${shortDate(digest.weekStart)}-${shortDate(digest.weekEnd)}"

/** "Jun 8" from "2026-06-08", via the engine's own pure parse (no Calendar). */
private fun shortDate(ymd: String): String {
    val p = WeeklyDigestEngine.parseYMD(ymd) ?: return ymd
    val name = monthAbbreviation(p[1]) ?: p[1].toString()
    return "$name ${p[2]}"
}

internal fun meanText(s: WeeklyMetricSummary, effortScale: EffortScale): String {
    if (s.thisWeek.n == 0) return "—"
    // Effort is STORED 0-100; render it on the user's chosen display scale WITH the denominator
    // ("4.6 / 21", "21.6 / 100") so the card can't read as a different number than the Trends chart.
    if (s.metric == WeeklyMetric.EFFORT) {
        return "${UnitFormatter.effortDisplay(s.thisWeek.mean, effortScale)} / " +
            UnitFormatter.effortScaleMax(effortScale)
    }
    val v = s.thisWeek.mean.roundToInt()
    return if (s.metric.unit.isEmpty()) "$v" else "$v ${s.metric.unit}"
}

internal fun deltaText(s: WeeklyMetricSummary): String {
    if (s.weekOverWeek.current.n == 0 || s.weekOverWeek.previous.n == 0) return "new"
    val pct = s.weekOverWeek.pctChange
    // Sub-1% (or unpercentable) moves read "<1%", never a raw points delta (a bare "0.1", and for
    // Effort a stored 0-100 figure the scale toggle never saw).
    return if (pct != null && abs(pct) >= 1) "${abs(pct).roundToInt()}%" else "<1%"
}

/**
 * Tone: good moves green, bad moves rose, flat/uncomparable grey — folding in each
 * metric's higherIsBetter (so a Resting-HR rise reads as a warning). A ROUGH comparison
 * (either side thin, engine's [WeeklyMetricSummary.isRoughComparison]) keeps its arrow + % but
 * stays grey regardless of direction.
 */
private fun chipTone(s: WeeklyMetricSummary): Color = when {
    s.isRoughComparison -> Palette.textTertiary
    s.wowGoodness == 1 -> Palette.statusPositive
    s.wowGoodness == -1 -> Palette.statusCritical
    else -> Palette.textTertiary
}

@Composable
private fun rowAccessibility(s: WeeklyMetricSummary, effortScale: EffortScale): String {
    val mean = meanText(s, effortScale)
    val dir = when {
        s.wowDelta > 0 -> stringResource(R.string.digest_a11y_up)
        s.wowDelta < 0 -> stringResource(R.string.digest_a11y_down)
        else -> stringResource(R.string.digest_a11y_unchanged)
    }
    // A rough comparison drops the verdict framing too, so VoiceOver/TalkBack matches the neutral chip.
    val frame = when {
        s.isRoughComparison -> ""
        s.wowGoodness == 1 -> stringResource(R.string.digest_a11y_good_sign)
        s.wowGoodness == -1 -> stringResource(R.string.digest_a11y_worth_a_look)
        else -> ""
    }
    val label = stringResource(metricLabel(s.metric))
    return if (s.weekOverWeek.current.n == 0 || s.weekOverWeek.previous.n == 0) {
        stringResource(R.string.digest_a11y_row_no_comparison, label, mean)
    } else {
        stringResource(R.string.digest_a11y_row, label, mean, dir, deltaText(s), frame)
    }
}

private fun fmt1(x: Double): String = ((x * 10).roundToInt() / 10.0).toString()

// MARK: - Engine reads, worded here

@StringRes
private fun metricLabel(metric: WeeklyMetric): Int = when (metric) {
    WeeklyMetric.CHARGE -> R.string.narr_digest_metric_charge
    WeeklyMetric.EFFORT -> R.string.narr_digest_metric_effort
    WeeklyMetric.REST -> R.string.narr_digest_metric_rest
    WeeklyMetric.RHR -> R.string.narr_digest_metric_rhr
    WeeklyMetric.HRV -> R.string.narr_digest_metric_hrv
}

@StringRes
private fun balanceSentence(read: BalanceRead): Int = when (read) {
    BalanceRead.OVERREACHING -> R.string.narr_digest_balance_overreaching
    BalanceRead.BALANCED -> R.string.narr_digest_balance_balanced
    BalanceRead.UNDERLOADED -> R.string.narr_digest_balance_underloaded
    BalanceRead.INSUFFICIENT -> R.string.narr_digest_balance_insufficient
}

/**
 * One focal point as a whole sentence. The engine picked the read and supplied the figures; the
 * direction and the good/bad framing each select their own resource so a translation can reorder
 * the clause rather than glue fragments together.
 */
@Composable
private fun focalSentence(point: FocalPoint): String = when (point) {
    is FocalPoint.Mover -> {
        val res = when {
            point.direction > 0 && point.goodness >= 0 -> R.string.narr_digest_focal_up_good
            point.direction > 0 -> R.string.narr_digest_focal_up_bad
            point.goodness >= 0 -> R.string.narr_digest_focal_down_good
            else -> R.string.narr_digest_focal_down_bad
        }
        stringResource(
            res,
            stringResource(metricLabel(point.metric)),
            moverMagnitude(point),
            point.thisAvg,
            point.lastAvg,
        )
    }
    is FocalPoint.Balance -> stringResource(balanceSentence(point.read))
    is FocalPoint.TooEarly ->
        pluralStringResource(R.plurals.narr_digest_focal_too_early, point.days, point.days)
    is FocalPoint.RoughPreviousWeek ->
        pluralStringResource(R.plurals.narr_digest_focal_rough_last_week, point.days, point.days)
    is FocalPoint.SteadyWithRest ->
        stringResource(R.string.narr_digest_focal_steady_rest, fmt1(point.restScoreSD))
    FocalPoint.Steady -> stringResource(R.string.narr_digest_focal_steady)
}

/** The mover's size: a percentage when the engine could report one, else the metric's own units. */
@Composable
private fun moverMagnitude(point: FocalPoint.Mover): String {
    point.percent?.let { return stringResource(R.string.narr_digest_magnitude_percent, it) }
    val value = fmt1(point.points ?: 0.0)
    val unit = point.metric.unit
    return if (unit.isEmpty()) {
        stringResource(R.string.narr_digest_magnitude_points, value)
    } else {
        stringResource(R.string.narr_digest_magnitude_unit, value, unit)
    }
}

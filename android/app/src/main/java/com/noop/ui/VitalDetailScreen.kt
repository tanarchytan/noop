package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import android.widget.Toast
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.FitnessAgeEngine
import com.noop.analytics.FitnessAgeReadiness
import com.noop.analytics.FitnessReadinessItem
import com.noop.analytics.FitnessReadinessRole
import com.noop.analytics.FitnessReadinessStatus
import com.noop.analytics.RustScores
import com.noop.analytics.VitalBands
import com.noop.data.DailyMetric
import uniffi.whoop_ffi.HrvReadinessInfo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Vital detail
//
// One vital's own screen ([VitalDetailScreen]): its windowed trend, the readings behind it and their
// sources. The helpers above it are shared with the Health Monitor and the Recovery drivers.

/**
 * : whether the cycle-awareness OPT-IN invitation should be offered for a profile with this [sex]
 * value. Cycle phase is read from the menstrual skin-temperature shift, so the invitation is NOT offered
 * for a male profile; "female"/"nonbinary" (and any unrecognised value, default-show rather than hide)
 * qualify. Pure so it's unit-tested directly. ProfileStore.sex is "male" | "female" | "nonbinary".
 */
internal fun cycleOptInApplies(sex: String): Boolean = sex.lowercase(Locale.US) != "male"

/** One labelled contributor bar: a label + right-aligned read-out over the NOOP signature segmented
 * [PipBar] (metric-hue pips that cascade up to the strength on appear/change), mirroring
 * HealthView.swift's `ContributorBar` / `PipBar(value:tint:)`. A null fraction renders an empty
 * (calibrating) bar — no fabricated fill. */
@Composable
private fun ContributorBar(
    label: String,
    readout: String,
    fraction: Double?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // PipBar takes a 0…100 value; map the presentation fraction up onto that span (null → empty bar).
    val strength = fraction?.coerceIn(0.0, 1.0)?.let { (it * 100.0).toFloat() } ?: 0f
    Column(
        modifier = modifier.semantics { contentDescription = "$label $readout" },
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(label, modifier = Modifier.weight(1f))
            Text(readout, style = NoopType.captionNumber, color = Palette.textPrimary)
        }
        PipBar(value = strength, tint = color)
    }
}

// MARK: - Fitness Age
//
// The on-device "Fitness Age": a weekly number (the engine keys it to each week's Saturday) that maps
// resting HR + recent activity against population norms for your age. The AUTHORITATIVE value is the
// latest "fitness_age" the IntelligenceEngine writes into metricSeries under the computed "-noop"
// source — this section only READS it; it never recomputes the headline. Honest framing throughout:
// it's a fitness comparison (± 5 yr band), never a biological age, and weight/height/waist live under
// "Unlocks your VO₂max", never as if they sharpen the age. When no value exists yet we show the
// readiness checklist instead, so the user knows exactly what's still needed.

/** Fitness Age readiness from what a screen can see: RHR coverage over the last 7 merged daily rows
 * (drives the "N more nights" countdown), a scored-strain day as the activity signal, and the profile
 * basics. Shared by the Today card's [VitalDetailScreen] tap-through so ONE gate feeds it
 * (no drift). Returns (rhrDays, readiness) — rhrDays also
 * feeds the not-ready lead. Approximate by design; the weekly value is the authority, this explains gaps. */
@Composable
private fun rememberFitnessReadiness(days: List<DailyMetric>, profile: ProfileStore): Pair<Int, FitnessAgeReadiness> {
    val rhrDays = remember(days) { days.takeLast(7).count { it.restingHr != null } }
    val readiness = remember(days, profile.age, profile.sex, profile.waistCm) {
        val activityDays = days.takeLast(7).count { it.strain != null }
        FitnessAgeEngine.assessReadiness(
            hasAge = profile.age > 0,
            hasSex = profile.sex.isNotBlank(),
            rhrDays = rhrDays,
            activityDays = activityDays,
            hasHeightWeight = profile.heightCm > 0 && profile.weightKg > 0,
            hasWaist = profile.waistCm > 0,
        )
    }
    return rhrDays to readiness
}

/** The not-ready card's lead: a concrete countdown of nights-of-wear still needed (from the shared
 * [FitnessAgeEngine.nightsUntilReady]), noting the profile basics only when actually missing. */
@Composable
private fun fitnessReadyLead(rhrDays: Int, hasAge: Boolean, hasSex: Boolean): String {
    val remaining = FitnessAgeEngine.nightsUntilReady(rhrDays)
    val needsBasics = !hasAge || !hasSex
    return when {
        remaining == 0 && !needsBasics -> stringResource(R.string.vitals_fitness_lead_days)
        remaining == 0 && needsBasics  -> stringResource(R.string.vitals_fitness_lead_basics)
        remaining == 1 && !needsBasics -> stringResource(R.string.vitals_fitness_lead_one_night)
        remaining == 1 && needsBasics  -> stringResource(R.string.vitals_fitness_lead_one_night_basics)
        !needsBasics -> stringResource(R.string.vitals_fitness_lead_nights, remaining)
        else         -> stringResource(R.string.vitals_fitness_lead_nights_basics, remaining)
    }
}

/** The readiness checklist card: each input as a ✓ / ⚠ / ○ glyph + its detail, grouped by role into
 * "Drives your Fitness Age" and "Unlocks your VO₂max". When [headed] (no value yet) it leads with the
 * [lead] countdown and floats the required-missing items to the top of their group. */
@Composable
private fun FitnessReadinessCard(
    readiness: FitnessAgeReadiness,
    headed: Boolean,
    lead: String = "",
    // When set (the headed/not-ready state), a small refresh affordance sits by the lead and forces an
    // immediate Fitness Age recompute; [refreshing] swaps it for a spinner while that runs.
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
) {
    val drivesAge = readiness.items
        .filter { it.role == FitnessReadinessRole.DRIVES_AGE }
        .sortedBy { if (headed) readinessSortKey(it) else 0 }
    val unlocksVo2 = readiness.items
        .filter { it.role == FitnessReadinessRole.UNLOCKS_VO2MAX }
        .sortedBy { if (headed) readinessSortKey(it) else 0 }

    val leadText = if (lead.isBlank()) stringResource(R.string.vitals_fitness_lead_days) else lead
    NoopCard(tint = if (headed) Palette.chargeColor else null) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            if (headed) {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            leadText,
                            style = NoopType.headline,
                            color = Palette.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        // Force-recompute affordance: NOOP scores Fitness Age weekly, so this lets an
                        // impatient user apply it NOW from stored data (no strap needed). Spinner while it runs.
                        if (onRefresh != null) {
                            if (refreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Palette.accent,
                                )
                            } else {
                                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.vitals_fitness_refresh),
                                        tint = Palette.accent,
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.vitals_fitness_explainer),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }
            }

            ReadinessGroup(title = stringResource(R.string.vitals_fitness_drives), items = drivesAge)
            ReadinessGroup(title = stringResource(R.string.vitals_fitness_unlocks), items = unlocksVo2)

            Text(
                stringResource(R.string.vitals_fitness_extras),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/** Sort key for the headed (no-value-yet) state: required-missing first, then partial, then the rest. */
private fun readinessSortKey(item: FitnessReadinessItem): Int = when {
    item.required && item.status == FitnessReadinessStatus.MISSING -> 0
    item.status == FitnessReadinessStatus.MISSING -> 1
    item.status == FitnessReadinessStatus.PARTIAL -> 2
    else -> 3
}

@Composable
private fun ReadinessGroup(title: String, items: List<FitnessReadinessItem>) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Overline(title)
        items.forEach { ReadinessRow(it) }
    }
}

@Composable
private fun ReadinessRow(item: FitnessReadinessItem) {
    val glyph = when (item.status) {
        FitnessReadinessStatus.SATISFIED -> "✓"
        FitnessReadinessStatus.PARTIAL -> "⚠"
        FitnessReadinessStatus.MISSING -> "○"
    }
    val glyphColor = when (item.status) {
        FitnessReadinessStatus.SATISFIED -> Palette.chargeColor
        FitnessReadinessStatus.PARTIAL -> Palette.statusWarning
        FitnessReadinessStatus.MISSING -> Palette.textTertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${item.label}: ${item.detail}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
    ) {
        Text(
            glyph,
            style = NoopType.captionNumber,
            color = glyphColor,
            modifier = Modifier.width(16.dp),
        )
        Text(
            item.label,
            style = NoopType.subhead,
            color = Palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            item.detail,
            style = NoopType.footnote,
            color = Palette.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One windowed reading behind a vital's detail chart: its day ("YYYY-MM-DD"), the value, and the RAW
 * source id it came from (a strap id, the "-noop" computed sibling, "apple-health", or "health-connect").
 * The readings TABLE and the "N readings" header both derive from this ONE list, so they can never
 * disagree; the raw source maps to a human label via [provenanceDisplayLabel] — the SAME resolver Today
 * uses, so we never invent a source vocabulary. */
internal data class VitalReading(
    val day: String,
    val value: Double,
    val source: String,
)

/** merge the three step stores into one per-day series with the SAME precedence as the Today
 * Steps tile — a REAL on-device count ([real], WHOOP 5/MG @57 → DailyMetric.steps) wins, else an
 * [imported] Health Connect / Apple Health count, else the motion-model [est] (`steps_est`). The three
 * are disjoint stores so the `?:` chain never double-counts. Ascending by day. Pure for testability. */
internal fun mergeStepsReadings(
    real: Map<String, VitalReading>,
    imported: Map<String, VitalReading>,
    est: Map<String, VitalReading>,
): List<VitalReading> =
    (real.keys + imported.keys + est.keys).toSortedSet()
        .mapNotNull { d -> real[d] ?: imported[d] ?: est[d] }

private data class VitalDetailModel(
    val key: String,
    @StringRes val title: Int,
    val unit: String,
    val color: Color,
    val readings: List<VitalReading>,
    val format: (Double) -> String,
) {
    /** (day, value) projection the trend chart + range helpers consume — SAME order as [readings], so the
     * chart, the header count, and the table can never drift apart. */
    val points: List<Pair<String, Double>> get() = readings.map { it.day to it.value }
}

/** Metric-detail keys that are NOT plain DailyMetric columns but series the engines/importers persist
 * (Fitness Age + Vitality under the computed strap, Steps estimate, Apple active energy). Each Today
 * dashboard card taps through to ITS OWN focused trend here (2026-07-03), so these load their
 * series from the repo on demand rather than off the cached `days` columns. */
private val SERIES_BACKED_VITAL_KEYS = setOf("fitness_age", "vitality", "steps_est", "active_kcal")

@Composable
fun VitalDetailScreen(vm: AppViewModel, key: String) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val tempUnit = UnitPrefs.temperature(context)
    // Profile drives the Fitness Age readiness/countdown shown when that vital has no value yet.
    val profile = remember { ProfileStore.from(context.applicationContext) }
    val isSeriesBacked = key in SERIES_BACKED_VITAL_KEYS

    // Series-backed metrics are loaded async from metricSeries; the plain daily vitals build synchronously
    // off the cached `days`. `seriesLoaded` guards the empty-state so a still-loading trend doesn't flash
    // "not enough history" before its rows arrive.
    var seriesDetail by remember(key) { mutableStateOf<VitalDetailModel?>(null) }
    var seriesLoaded by remember(key) { mutableStateOf(false) }
    // Manual-refresh plumbing for the Fitness Age not-ready state (readiness branch below): the refresh
    // button recomputes then bumps this tick, re-running the series read so a fresh value shows at once.
    var refreshTick by remember { mutableStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    if (isSeriesBacked) {
        LaunchedEffect(key, refreshTick) {
            seriesDetail = buildSeriesVitalDetail(vm, key)
            seriesLoaded = true
        }
    }
    val detail = if (isSeriesBacked) seriesDetail
    else remember(days, key, tempUnit) { buildVitalDetail(days, key, tempUnit) }
    var range by remember { mutableStateOf(VitalDetailRange.MONTH) }

    // The subtitle tracks how much history the metric has, so we never promise a "historical trend" the
    // view isn't showing: Fitness Age with no reading yet -> what it still needs; ANY metric with a single
    // reading -> that reading (trend to follow); two+ -> the trend. Pre-load falls through to trend.
    val loadedPoints = if (seriesLoaded) (detail?.points?.size ?: 0) else -1
    ScreenScaffold(
        title = if (detail != null) stringResource(detail.title) else stringResource(R.string.vitals_title),
        subtitle = when {
            key == "fitness_age" && loadedPoints == 0 ->
                stringResource(R.string.vitals_subtitle_fitness_age_pending)
            loadedPoints == 1 -> stringResource(R.string.vitals_subtitle_single)
            else -> stringResource(R.string.vitals_subtitle_trend)
        },
    ) {
        if (isSeriesBacked && !seriesLoaded) {
            DataPendingNote(
                title = stringResource(R.string.vitals_loading_title),
                body = stringResource(R.string.vitals_loading_body),
            )
            return@ScreenScaffold
        }
        if (detail == null || detail.points.size < 2) {
            // Fitness Age with NO value yet (zero points): show the readiness checklist + the "N more
            // nights of wear" countdown — what it actually needs — instead of the generic "needs two
            // readings to chart" note, which describes the trend line and left the Today card's tap-through
            // a dead end. (A single reading is handled below, generically, for every metric.)
            if (key == "fitness_age" && (detail?.points?.isEmpty() != false)) {
                val (rhrDays, readiness) = rememberFitnessReadiness(days, profile)
                FitnessReadinessCard(
                    readiness = readiness, headed = true,
                    lead = fitnessReadyLead(rhrDays, profile.age > 0, profile.sex.isNotBlank()),
                    refreshing = refreshing,
                    onRefresh = {
                        refreshing = true
                        vm.refreshFitnessAgeNow { wrote ->
                            refreshing = false
                            refreshTick++
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (wrote) R.string.vitals_fitness_updated
                                    else R.string.vitals_fitness_not_enough_wear,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
                return@ScreenScaffold
            }
            // ANY metric with exactly ONE reading: the Today card already shows this value, so the generic
            // "Not enough history yet" note read as a contradiction on tap-through — only the TREND CHART
            // needs a second point. Show the value + when the chart fills in, never a no-data dead end.
            // First hit on Fitness Age, then Vitality — both weekly-ish computed scores that sit at
            // one reading for a while.
            if (detail != null && detail.points.size == 1) {
                val one = detail.points.last()   // size 1: the single reading (last == the latest)
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                        Overline(stringResource(R.string.vitals_latest))
                        Text(
                            text = "${detail.format(one.second)} ${detail.unit}".trim(),
                            style = NoopType.chartValueLarge,
                            color = detail.color,
                        )
                        Text(
                            text = stringResource(R.string.vitals_as_of, one.first),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                        Text(
                            text = stringResource(R.string.vitals_single_reading_note),
                            style = NoopType.subhead,
                            color = Palette.textSecondary,
                        )
                    }
                }
                return@ScreenScaffold
            }
            DataPendingNote(
                title = stringResource(R.string.vitals_no_history_title),
                body = stringResource(R.string.vitals_no_history_body),
            )
            return@ScreenScaffold
        }

        // Gate the range chips by available history so short history can't draw six
        // byte-identical charts. A locked selection (e.g. the MONTH default during the first week)
        // coerces DOWN to the largest unlocked range so a calibrating user always has a live chart.
        val unlockedRanges = remember(detail) { unlockedVitalRanges(vitalHistorySpanDays(detail.points)) }
        val effectiveRange = coercedVitalRange(range, unlockedRanges)
        // The trend chart, the "N readings" header, AND the readings table all derive from this ONE
        // windowed list, so the count and the rows can never disagree. filteredPoints is just
        // its (day, value) projection for the existing chart/stat code.
        val filteredReadings = remember(detail, effectiveRange) { filterVitalReadings(detail.readings, effectiveRange) }
        val filteredPoints = filteredReadings.map { it.day to it.value }
        if (filteredPoints.size < 2) {
            DataPendingNote(
                title = stringResource(R.string.vitals_no_history_range_title),
                body = stringResource(R.string.vitals_no_history_range_body),
            )
            return@ScreenScaffold
        }

        val values = filteredPoints.map { it.second }
        val latest = filteredPoints.last()
        val min = values.minOrNull()
        val max = values.maxOrNull()
        val avg = RustScores.mean(values)

        SectionHeader(
            stringResource(detail.title),
            overline = stringResource(R.string.vitals_title),
            trailing = stringResource(R.string.vitals_readings_count, filteredReadings.size),
        )
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Overline(stringResource(R.string.vitals_latest))
                        Text(
                            text = UnitFormatter.withUnit(detail.format(latest.second), detail.unit),
                            style = NoopType.chartValueLarge,
                            color = detail.color,
                        )
                        Text(
                            text = stringResource(R.string.vitals_as_of, latest.first),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                }
                SegmentedPillControl(
                    items = VitalDetailRange.entries,
                    selection = effectiveRange,
                    label = { context.getString(it.label) },
                    onSelect = { range = it },
                    enabled = { it in unlockedRanges },
                )
                if (unlockedRanges.size < VitalDetailRange.entries.size) {
                    Text(
                        stringResource(R.string.vitals_ranges_unlock),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                LineChart(
                    values = values,
                    modifier = Modifier.height(Metrics.chartHeight),
                    color = detail.color,
                    fill = true,
                    selectionEnabled = true, // the Vital Signs detail chart is meant to be tappable
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Metrics.divider)
                        .background(Palette.hairline),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        stringResource(R.string.vitals_min) to min,
                        stringResource(R.string.vitals_avg) to avg,
                        stringResource(R.string.vitals_max) to max,
                    ).forEach { (label, metric) ->
                        Column(modifier = Modifier.weight(1f)) {
                            Overline(label, color = Palette.textTertiary)
                            Text(
                                text = metric?.let { UnitFormatter.withUnit(detail.format(it), detail.unit) } ?: "—",
                                style = NoopType.bodyNumber,
                                color = Palette.textPrimary,
                            )
                        }
                    }
                }
                if (detail.key == "spo2") {
                    Text(
                        stringResource(R.string.vitals_spo2_disclaimer),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        }

        // Per-reading breakdown so the provenance behind the trend is visible — whether each reading came
        // from the WHOOP strap, a Health Connect / Apple Health import, or the on-device pipeline — not
        // just the "N readings" count. Rows derive from the SAME [filteredReadings] the header counts,
        // newest first, and reuse [provenanceDisplayLabel] for the source words.
        val strapId = vm.activeStrapId
        val readingRows = remember(filteredReadings, detail, strapId) {
            vitalReadingRows(filteredReadings, detail.unit, strapId, detail.format)
        }
        if (detail.key == "hrv") HrvReadinessCard(days)
        VitalReadingsTable(rows = readingRows)
    }
}

/**
 * Where the nightly HRV baseline sits inside its own normal band, on the HRV vital only. whoop-rs
 * cuts the tier and the band edges; the word, the colour and the sentence are the choices here.
 */
@Composable
private fun HrvReadinessCard(days: List<DailyMetric>) {
    val read = remember(days) { RustScores.hrvReadiness(days.map { it.avgHrv }) } ?: return
    val word = hrvReadinessWord(read.tier) ?: return
    InsightCard(
        modifier = Modifier.fillMaxWidth(),
        category = stringResource(R.string.vitals_hrv_readiness),
        status = stringResource(word),
        detail = hrvReadinessDetail(read),
        statusColor = hrvReadinessColor(read.tier),
        tint = null,
    )
}

/** The band sentence under the readiness word: the 7-night baseline against its normal edges. PURE. */
internal fun hrvReadinessDetail(read: HrvReadinessInfo): String {
    val ms = { v: Double -> "${v.roundToInt()} ms" }
    val band = "Your 7-night baseline is ${ms(read.baseline7Ms)}, against a normal band of " +
        "${ms(read.normalLowMs)} to ${ms(read.normalHighMs)}."
    return if (read.overreachingWatch) {
        band + " It has been drifting down, which is worth watching."
    } else {
        band
    }
}

/** The rows of a vital detail's readings table: each reading's day (localized), its formatted value with
 * unit, and a human source label. Plain strings so the composable is a thin renderer and the projection
 * stays unit-testable. */
internal data class VitalReadingRow(
    val time: String,
    val value: String,
    val source: String,
)

/**
 * Project a vital's windowed [readings] into table rows, NEWEST FIRST — the same list (so the same count)
 * the "N readings" header shows, guaranteeing the two never drift. Each row pairs the reading's DAY (these
 * vital series carry one aggregated reading per night, so a row's "time" is its calendar date, localized;
 * the date always shows since a charted window spans 2+ days) with the model's own [format]ted value +
 * [unit] and the source label resolved by [provenanceDisplayLabel] — no new source vocabulary (a strap id
 * → "Whoop", its "-noop" sibling → "On-device", "apple-health" → "Apple Health", "health-connect" →
 * "Health Connect"). [strapDeviceId] is the active strap id the label resolver needs.
 */
internal fun vitalReadingRows(
    readings: List<VitalReading>,
    unit: String,
    strapDeviceId: String,
    format: (Double) -> String,
): List<VitalReadingRow> =
    readings.asReversed().map { reading ->
        VitalReadingRow(
            time = vitalReadingDateLabel(reading.day),
            value = UnitFormatter.withUnit(format(reading.value), unit),
            source = provenanceDisplayLabel(reading.source, strapDeviceId),
        )
    }

/** "9 Jun" for a "YYYY-MM-DD" reading day (today / yesterday read as words); the verbatim string if it
 * does not parse. Locale.US month. */
internal fun vitalReadingDateLabel(day: String): String {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    return relativeDayLabel(
        date, today = "Today", yesterday = "Yesterday",
        other = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())),
    )
}

/** The readings table below a vital's chart: one row per windowed reading (newest first), each showing
 * its day, formatted value, and source (tinted by [provenanceLabelTint], so the same source reads the
 * same colour as the Today rings). Empty [rows] render nothing. */
@Composable
private fun VitalReadingsTable(rows: List<VitalReadingRow>) {
    if (rows.isEmpty()) return
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Overline(stringResource(R.string.vitals_readings))
            // Slim column header naming the three columns — SAME weights as the data rows below so each
            // label sits over its column.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.vitals_col_date),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.vitals_col_value),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                Text(
                    stringResource(R.string.vitals_col_source),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
            rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.time,
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        row.value,
                        style = NoopType.bodyNumber,
                        color = Palette.textPrimary,
                    )
                    Text(
                        row.source,
                        style = NoopType.footnote,
                        color = provenanceLabelTint(row.source),
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (index < rows.size - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Metrics.divider)
                            .background(Palette.hairline),
                    )
                }
            }
        }
    }
}

internal enum class VitalDetailRange(@StringRes val label: Int, val days: Long?) {
    WEEK(R.string.vitals_range_w, 7),
    MONTH(R.string.vitals_range_m, 30),
    THREE_MONTH(R.string.vitals_range_3m, 90),
    SIX_MONTH(R.string.vitals_range_6m, 180),
    YEAR(R.string.vitals_range_1y, 365),
    ALL(R.string.vitals_range_all, null),
}

/** Days spanned by a vital's history: last point's day minus first point's day in epoch days (0 for
 * a single day or unparseable bounds). Points arrive oldest-first from buildVitalDetail. */
internal fun vitalHistorySpanDays(points: List<Pair<String, Double>>): Long {
    val first = points.firstOrNull()?.first?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return 0L
    val last = points.lastOrNull()?.first?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return 0L
    return (last.toEpochDay() - first.toEpochDay()).coerceAtLeast(0L)
}

/** Which range chips have anything NEW to show. filterVitalPoints windows off the
 * LATEST reading, so with under a week of history every window returned the identical full point set
 * and all six chips drew the same line (a week of data stretched full-width under a "1Y" label). A
 * range only differs from its predecessor once the data span EXCEEDS the predecessor's window, so the
 * unlocked set is a contiguous prefix: W always, M once span > 7 days, 3M once > 30, 6M once > 90,
 * 1Y once > 180, ALL once > 365. Locked chips render disabled rather than hidden so a calibrating
 * user still learns the longer views exist; W staying unconditional means nobody is ever stranded
 * with zero ranges. */
/**
 * The range the chips + caption actually describe, resolved NON-DESTRUCTIVELY.
 * A locked selection renders as the largest unlocked range with
 * a real finite window that is <= the selection, else WEEK. NOT ALL: coercing a locked default to ALL
 * would jump a calibrating user to the everything view. An unlocked selection is used verbatim, so the
 * chip un-coerces on its own once history grows.
 */
internal fun coercedVitalRange(range: VitalDetailRange, unlocked: List<VitalDetailRange>): VitalDetailRange {
    if (range in unlocked) return range
    return VitalDetailRange.entries
        .filter { it.days != null && it.ordinal <= range.ordinal && it in unlocked }
        .maxByOrNull { it.ordinal }
        ?: VitalDetailRange.WEEK
}

internal fun unlockedVitalRanges(spanDays: Long): List<VitalDetailRange> {
    val ranges = VitalDetailRange.entries
    val unlocked = mutableListOf(ranges.first())
    for (i in 1 until ranges.size) {
        val previousWindow = ranges[i - 1].days ?: break
        if (spanDays > previousWindow) unlocked += ranges[i] else break
    }
    // ALL is never gated: a calibrating user can always see their full history,
    // even when it happens to draw the same points as a shorter window.
    val all = ranges.last()
    if (all.days == null && all !in unlocked) unlocked += all
    return unlocked
}

/** The one latest-relative day window, over any list whose element carries a "yyyy-MM-dd" [day]: keep
 * the [windowDays] days ending at the last element, falling back to a plain tail when a day fails to
 * parse. A null window keeps everything. */
private fun <T> filterToDayWindow(items: List<T>, windowDays: Long?, day: (T) -> String): List<T> {
    if (windowDays == null) return items
    val latestDate = items.lastOrNull()?.let { runCatching { LocalDate.parse(day(it)) }.getOrNull() }
        ?: return items.takeLast(windowDays.toInt())
    val cutoff = latestDate.minusDays(windowDays - 1)
    val filtered = items.filter {
        runCatching { LocalDate.parse(day(it)) }.getOrNull()?.let { d -> !d.isBefore(cutoff) } ?: false
    }
    return filtered.ifEmpty { items.takeLast(windowDays.toInt()) }
}

/** The latest-relative day window for a (day, value) series. Every detail chart (vitals, sleep
 * metrics) windows through here. */
internal fun filterPointsToWindow(
    points: List<Pair<String, Double>>,
    windowDays: Long?,
): List<Pair<String, Double>> = filterToDayWindow(points, windowDays) { it.first }

internal fun filterVitalPoints(
    points: List<Pair<String, Double>>,
    range: VitalDetailRange,
): List<Pair<String, Double>> = filterPointsToWindow(points, range.days)

/** [filterVitalPoints] for the source-carrying [VitalReading] list — the SAME latest-relative window,
 * so the readings table and the chart always agree on which readings are in view. */
internal fun filterVitalReadings(
    readings: List<VitalReading>,
    range: VitalDetailRange,
): List<VitalReading> = filterToDayWindow(readings, range.days) { it.day }

private fun buildVitalDetail(
    days: List<DailyMetric>,
    key: String,
    tempUnit: TemperatureUnit,
): VitalDetailModel? {
    return when (key) {
    "resp" -> VitalDetailModel(
        key = key,
        title = R.string.vitals_metric_resp,
        unit = "rpm",
        color = Palette.metricCyan,
        readings = days.mapNotNull { row -> row.respRateBpm?.let { VitalReading(row.day, it, row.deviceId) } },
        format = { String.format(Locale.US, "%.1f", it) },
    )
    "spo2" -> VitalDetailModel(
        key = key,
        title = R.string.vitals_metric_spo2,
        unit = "%",
        color = Palette.metricCyan,
        readings = days.mapNotNull { row -> row.spo2Pct?.let { VitalReading(row.day, it, row.deviceId) } },
        format = { String.format(Locale.US, "%.0f", it) },
    )
    "rhr" -> VitalDetailModel(
        key = key,
        title = R.string.vitals_metric_rhr,
        unit = "bpm",
        color = Palette.metricRose,
        readings = days.mapNotNull { row -> row.restingHr?.toDouble()?.let { VitalReading(row.day, it, row.deviceId) } },
        format = { it.roundToInt().toString() },
    )
    "hrv" -> VitalDetailModel(
        key = key,
        title = R.string.vitals_metric_hrv,
        unit = "ms",
        color = Palette.metricPurple,
        readings = days.mapNotNull { row -> row.avgHrv?.let { VitalReading(row.day, it, row.deviceId) } },
        format = { it.roundToInt().toString() },
    )
    "skin" -> {
        val latest = days.asReversed().asSequence()
            .mapNotNull { it.skinTempAbsC ?: it.skinTempDevC }.firstOrNull() ?: return null
        val absolute = VitalBands.isAbsoluteSkinTemp(latest)
        val unit = UnitFormatter.temperatureUnit(tempUnit)
        val format: (Double) -> String = { c ->
            val full = if (absolute) {
                UnitFormatter.temperatureFromCelsius(c, tempUnit, decimals = 1)
            } else {
                UnitFormatter.temperatureDeltaFromCelsius(c, tempUnit, decimals = 1)
            }
            full.removeSuffix(" $unit")
        }
        VitalDetailModel(
            key = key,
            title = R.string.vitals_metric_skin_temp,
            unit = unit,
            color = Palette.metricAmber,
            readings = days.mapNotNull { row ->
                (row.skinTempAbsC ?: row.skinTempDevC)
                    ?.takeIf { VitalBands.isAbsoluteSkinTemp(it) == absolute }
                    ?.let { value -> VitalReading(row.day, value, row.deviceId) }
            },
            format = format,
        )
    }
    else -> null
    }
}

/** Build a metric-detail trend for a [SERIES_BACKED_VITAL_KEYS] key by reading its persisted series from
 * the repo (async): Fitness Age + Vitality off the computed strap the IntelligenceEngine writes, Steps
 * off the resolved step series (imported ∪ estimated), Active Energy off the Apple-Health import. Colours
 * match each card's dashboard tint. Returns null for an unknown key. */
private suspend fun buildSeriesVitalDetail(vm: AppViewModel, key: String): VitalDetailModel? = when (key) {
    "fitness_age" -> VitalDetailModel(
        key = key,
        title = R.string.vitals_metric_fitness_age,
        unit = "yrs",
        color = Palette.chargeColor,
        readings = vm.repo.metricSeriesComputedUnion("fitness_age", "0000-01-01", "9999-12-31")
            .map { VitalReading(it.day, it.value, it.deviceId) },
        format = { it.roundToInt().toString() },
    )
    "vitality" -> VitalDetailModel(
        key = key,
        title = R.string.vitals_metric_vitality,
        unit = "",
        color = Palette.metricPurple,
        readings = vm.repo.metricSeriesComputedUnion("vitality", "0000-01-01", "9999-12-31")
            .map { VitalReading(it.day, it.value, it.deviceId) },
        format = { it.roundToInt().toString() },
    )
    "steps_est" -> {
        // the Today Steps tile resolves a REAL step count FIRST — the WHOOP 5/MG on-device @57
        // counter (DailyMetric.steps) ?: imported Health Connect / Apple Health ?: the motion-model
        // estimate (`day?.steps ?: importedStepsForDay ?: estimatedStepsForDay`). This
        // detail read the estimate ALONE, so a WHOOP 5.0 with a real count saw the estimate history —
        // clamped flat at StepsEstimateEngine.MAX_DAILY_STEPS = 60,000 when the motion fit over-shoots —
        // instead of its real steps. Resolve per day with the SAME precedence so the graph + Readings
        // match the card. Real strap steps live in DailyMetric.steps; imported
        // steps in AppleDaily; the estimate in the "steps_est" series — three disjoint stores, so the
        // per-day `?:` chain never double-counts.
        val real = vm.repo.resolvedSeries("steps", "my-whoop", "0000-00-00", "9999-99-99")
            .points.associateBy({ it.day }, { VitalReading(it.day, it.value, it.source) })
        val imported = LinkedHashMap<String, VitalReading>()
        for (r in vm.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31") +
            vm.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31")) {
            val s = r.steps
            if (s != null && s > 0) imported.putIfAbsent(r.day, VitalReading(r.day, s.toDouble(), r.deviceId))
        }
        val est = vm.repo.resolvedSeries("steps_est", "my-whoop", "0000-00-00", "9999-99-99")
            .points.associateBy({ it.day }, { VitalReading(it.day, it.value, it.source) })
        VitalDetailModel(
            key = key,
            title = R.string.vitals_metric_steps,
            unit = "steps",
            color = Palette.metricCyan,
            readings = mergeStepsReadings(real, imported, est),
            format = { it.roundToInt().toString() },
        )
    }
    "active_kcal" -> {
        // Imported apple-health/health-connect activeKcal (in the AppleDaily table, apple winning a tie) first,
        // then the strap's own activeKcalEst fills days with no phone import, so a strap-only day still shows.
        val imported = LinkedHashMap<String, VitalReading>()
        for (r in vm.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31") +
            vm.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31")) {
            r.activeKcal?.let { imported.putIfAbsent(r.day, VitalReading(r.day, it, r.deviceId)) }
        }
        val est = vm.repo.resolvedSeries("active_kcal", "my-whoop", "0000-00-00", "9999-99-99")
            .points.associateBy({ it.day }, { VitalReading(it.day, it.value, it.source) })
        VitalDetailModel(
            key = key,
            title = R.string.vitals_metric_active_energy,
            unit = "kcal",
            color = Palette.metricAmber,
            readings = (imported.keys + est.keys).toSortedSet().mapNotNull { imported[it] ?: est[it] },
            format = { it.roundToInt().toString() },
        )
    }
    else -> null
}

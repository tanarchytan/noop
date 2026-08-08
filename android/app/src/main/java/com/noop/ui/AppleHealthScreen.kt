package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.analytics.RustScores
import com.noop.data.AppleDaily
import com.noop.data.MetricSeriesRow
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Apple Health (per-source page)
//
// Faithful port of the macOS AppleHealthView. The macOS screen is a Vitaltrends-style,
// instrument-grade page driven by ONE range control (W / M / 3M / 6M / 1Y / ALL): a
// uniform grid of fixed-height StatTiles over every metric, then four ChartCard
// sections — Heart & Vitals, Activity & Energy, Body Composition, Sleep — each with an
// avg / min / max / points footer.
//
// All history is loaded once; the pill control windows it client-side, RELATIVE TO THE
// LATEST recorded day (not "now"). Per the data contract a series may be SPARSE
// (weight / body-fat are weekly): if the selected window holds >= 1 point we show THAT
// window; only when it holds ZERO do we auto-widen to the smallest larger range that
// does — so switching ranges stays visibly distinct and only sparse windows widen.
//
// Data sources on Android:
//   - The appleDaily table (steps, active_kcal via activeKcal, vo2max, weight) under
//     deviceId "apple-health".
//   - The generic metricSeries long-format table for everything Apple writes that isn't
//     a column on appleDaily (resting_hr, hrv, spo2, resp_rate, asleep_min, body_fat,
//     lean_mass, bmi), also under deviceId "apple-health".
//
// The Apple Health *import* runs on-device now: pick an Apple Health export .zip in
// Data Sources and it backfills these tables. This page just reads the resulting cache.
// When nothing has been imported we say so (no faked data, no generic "coming soon").

private const val APPLE_DEVICE = "apple-health"

// MARK: - Range control (W / M / 3M / 6M / 1Y / ALL) — the ONE pill control.

private enum class AppleRange(
    val days: Int?,
    @StringRes val label: Int,
    @StringRes val caption: Int,
    @StringRes val windowName: Int,
) {
    Week(7, R.string.applehealth_range_w, R.string.applehealth_caption_w, R.string.applehealth_window_week),
    Month(30, R.string.applehealth_range_m, R.string.applehealth_caption_m, R.string.applehealth_window_month),
    Quarter(90, R.string.applehealth_range_3m, R.string.applehealth_caption_3m, R.string.applehealth_window_quarter),
    Half(180, R.string.applehealth_range_6m, R.string.applehealth_caption_6m, R.string.applehealth_window_half),
    Year(365, R.string.applehealth_range_1y, R.string.applehealth_caption_1y, R.string.applehealth_window_year),
    All(null, R.string.applehealth_range_all, R.string.applehealth_caption_all, R.string.applehealth_window_all);

    /** This range plus every larger range, ascending — the auto-widen search order. */
    val widening: List<AppleRange>
        get() = entries.dropWhile { it != this }
}

// MARK: - A loaded series point (day string + value), oldest first.

private data class HealthPoint(val day: String, val value: Double)

/**
 * Slice the (ascending-by-day) series to a window taken RELATIVE TO THE LATEST point.
 * The daily/metricSeries caches hold one row per day, so the trailing N rows are the
 * trailing-N-day window — matching the macOS day-distance windowing closely enough.
 */
private fun List<HealthPoint>.windowFor(range: AppleRange): List<HealthPoint> {
    val n = range.days ?: return this
    if (isEmpty()) return emptyList()
    return takeLast(n)
}

// MARK: - Loaded model: every series this page pulls, keyed by metric.

private class AppleData(
    val rows: List<AppleDaily>,
    val series: Map<String, List<HealthPoint>>,
) {
    fun raw(key: String): List<HealthPoint> = series[key] ?: emptyList()

    /** True if ANY per-day row or any series holds data (drives the empty state). */
    val hasAnyData: Boolean
        get() = rows.isNotEmpty() || series.values.any { it.isNotEmpty() }
}

/** A key's resolved (possibly auto-widened) window in the active range. */
private data class Resolved(
    val requested: AppleRange,
    val effective: AppleRange,
    val rows: List<HealthPoint>,
) {
    val fellBack: Boolean get() = effective != requested
}

/**
 * The range actually shown for a key: the SELECTED range whenever its window holds
 * >= 1 point, otherwise the smallest LARGER range that does. Empty series resolve to
 * the requested range (so the card reads "0 readings · <range>", not a false widen).
 */
private fun resolve(series: List<HealthPoint>, requested: AppleRange): Resolved {
    if (series.isEmpty()) return Resolved(requested, requested, emptyList())
    val eff = requested.widening.firstOrNull { series.windowFor(it).isNotEmpty() } ?: AppleRange.All
    return Resolved(requested, eff, series.windowFor(eff))
}

// MARK: - Screen

@Composable
fun AppleHealthScreen(vm: AppViewModel) {
    var loaded by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf(AppleData(emptyList(), emptyMap())) }
    var range by remember { mutableStateOf(AppleRange.Quarter) }

    // Load ALL history once (range windowing is client-side). The appleDaily table
    // supplies steps / active_kcal / vo2max / weight directly; everything else comes
    // from the generic metricSeries long-format table under the apple-health device.
    LaunchedEffect(Unit) {
        val rows: List<AppleDaily> = runCatching {
            vm.repo.appleDaily(APPLE_DEVICE, "0000-00-00", "9999-99-99")
        }.getOrDefault(emptyList()).sortedBy { it.day }

        // Series we draw straight off appleDaily columns (no metricSeries round-trip).
        val series = linkedMapOf<String, List<HealthPoint>>()
        series["steps"] = rows.mapNotNull { r -> r.steps?.let { HealthPoint(r.day, it.toDouble()) } }
        series["active_kcal"] = rows.mapNotNull { r -> r.activeKcal?.let { HealthPoint(r.day, it) } }
        series["vo2max"] = rows.mapNotNull { r -> r.vo2max?.let { HealthPoint(r.day, it) } }
        series["weight"] = rows.mapNotNull { r -> r.weightKg?.let { HealthPoint(r.day, it) } }

        // Everything Apple writes that isn't an appleDaily column lives in metricSeries.
        val seriesKeys = listOf(
            "resting_hr", "hrv", "spo2", "resp_rate", "asleep_min",
            "body_fat", "lean_mass", "bmi",
        )
        for (key in seriesKeys) {
            val pts = runCatching {
                vm.repo.metricSeries(APPLE_DEVICE, key, "0000-00-00", "9999-99-99")
            }.getOrDefault(emptyList<MetricSeriesRow>())
                .sortedBy { it.day }
                .map { HealthPoint(it.day, it.value) }
            series[key] = pts
        }

        data = AppleData(rows, series)
        loaded = true
    }

    val subtitle = spanSubtitle(loaded, data, range)

    // PERF: lazy scaffold — in the populated `else` branch each chart section is its own `item { }`,
    // so only on-screen sections compose + are accessibility-walked on scroll (this data view is the long,
    // chart-heavy one). The loading/empty branches stay single items. Order + spacing are unchanged
    // (LazyColumn reproduces the eager `spacedBy(20.dp)` between the six sections).
    LazyScreenScaffold(title = stringResource(R.string.nav_apple_health), subtitle = subtitle) {
        when {
            !loaded -> item { LoadingCard() }
            !data.hasAnyData -> item { EmptyState() }
            else -> {
                item { RangeControl(data = data, range = range, onSelect = { range = it }) }
                item { TileGrid(data = data, range = range) }
                item { HeartSection(data = data, range = range) }
                item { ActivitySection(data = data, range = range) }
                item { BodySection(data = data, range = range) }
                item { SleepSection(data = data, range = range) }
            }
        }
    }
}

// MARK: - Header span + range control

/** Header subtitle reflects the windowed (visible) per-day span of the steps series. */
@Composable
private fun spanSubtitle(loaded: Boolean, data: AppleData, range: AppleRange): String {
    val fallback = stringResource(R.string.applehealth_subtitle_default)
    if (!loaded) {
        return fallback
    }
    // Use steps as the canonical per-day series for the span readout.
    val rows = resolve(data.raw("steps"), range).rows
    if (rows.isEmpty()) {
        return fallback
    }
    val lo = rows.first().day
    val hi = rows.last().day
    val span = if (lo == hi) lo else "$lo → $hi"
    return pluralStringResource(R.plurals.applehealth_days_span, rows.size, rows.size, span)
}

@Composable
private fun RangeControl(data: AppleData, range: AppleRange, onSelect: (AppleRange) -> Unit) {
    // Count visible days off the canonical steps series, and flag if any tracked series
    // had to auto-widen because its selected window was empty.
    val context = LocalContext.current
    val stepsRows = resolve(data.raw("steps"), range).rows
    val anyWidened = data.series.any { (_, s) -> s.isNotEmpty() && resolve(s, range).fellBack }
    val n = stepsRows.size
    val base = pluralStringResource(
        R.plurals.applehealth_days_window, n, n, stringResource(range.windowName),
    )
    val caption = if (anyWidened) stringResource(R.string.applehealth_some_widened, base) else base

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedPillControl(
                items = AppleRange.entries.toList(),
                selection = range,
                label = { context.getString(it.label) },
                onSelect = onSelect,
            )
            Spacer(Modifier.weight(1f))
            Overline(stringResource(range.caption), color = Palette.textTertiary)
        }
        Text(
            caption,
            style = NoopType.footnote,
            color = if (anyWidened) Palette.statusWarning else Palette.textTertiary,
        )
    }
}

// MARK: - Loading + empty states

@Composable
private fun LoadingCard() {
    NoopCard(tint = Palette.metricCyan) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            ConnectionDot(tone = StrandTone.Accent, pulsing = true)
            Text(
                stringResource(R.string.applehealth_loading),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    DataPendingNote(
        title = stringResource(R.string.applehealth_empty_title),
        body = stringResource(R.string.applehealth_empty_body),
    )
}

// MARK: - Metric tiles (uniform fixed-height StatTiles, two per row)

private enum class Aggregate { Latest, Mean }

@Composable
private fun TileGrid(data: AppleData, range: AppleRange) {
    // Imperial/Metric display preference. Weight + lean mass (stored kg) re-label to lb; every
    // other Apple Health metric is unit-agnostic. Display-only.
    val unitSystem = UnitPrefs.system(LocalContext.current)
    // Two columns of equal-width fixed-height tiles, mirroring the macOS adaptive grid.
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        TileRow {
            MetricTile(Modifier.weight(1f), data, range, "steps", stringResource(R.string.applehealth_steps), Palette.metricCyan) { intString(it) }
            MetricTile(Modifier.weight(1f), data, range, "resting_hr", stringResource(R.string.applehealth_resting_hr), Palette.metricRose, "bpm") {
                "${it.roundToInt()}"
            }
        }
        TileRow {
            MetricTile(Modifier.weight(1f), data, range, "hrv", stringResource(R.string.applehealth_hrv), Palette.metricPurple, "ms") { "${it.roundToInt()}" }
            MetricTile(Modifier.weight(1f), data, range, "vo2max", stringResource(R.string.applehealth_vo2max), Palette.accent, "ml/kg") {
                String.format(Locale.US, "%.1f", it)
            }
        }
        TileRow {
            MetricTile(Modifier.weight(1f), data, range, "weight", stringResource(R.string.applehealth_weight), Palette.accent) {
                UnitFormatter.massFromKilograms(it, unitSystem)
            }
            MetricTile(Modifier.weight(1f), data, range, "body_fat", stringResource(R.string.applehealth_body_fat_tile), Palette.metricAmber, "%") {
                String.format(Locale.US, "%.1f", it)
            }
        }
        TileRow {
            MetricTile(Modifier.weight(1f), data, range, "lean_mass", stringResource(R.string.applehealth_lean_mass_tile), Palette.accent) {
                UnitFormatter.massFromKilograms(it, unitSystem)
            }
            MetricTile(
                Modifier.weight(1f), data, range, "asleep_min", stringResource(R.string.applehealth_asleep_avg), Palette.metricPurple,
                aggregate = Aggregate.Mean,
            ) { durationString(it) }
        }
    }
}

@Composable
private fun TileRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) { content() }
}

/**
 * One StatTile for a metric. Sparse-safe: the window auto-widens to the smallest larger
 * range that holds data; the hero is the LATEST point ("as of <day>") unless a mean is
 * requested. Empty series render a dashed tile in tertiary ink rather than disappearing.
 */
@Composable
private fun MetricTile(
    modifier: Modifier,
    data: AppleData,
    range: AppleRange,
    key: String,
    label: String,
    accent: Color,
    unit: String = "",
    aggregate: Aggregate = Aggregate.Latest,
    fmt: (Double) -> String,
) {
    val rows = resolve(data.raw(key), range).rows
    val values = rows.map { it.value }
    val empty = values.isEmpty()
    val lastDay = rows.lastOrNull()?.day
    val asOf = if (lastDay != null) stringResource(R.string.applehealth_as_of, lastDay) else null
    val avgOverDays = stringResource(R.string.applehealth_avg_days, values.size)

    val value: String
    val caption: String?
    when {
        empty -> {
            value = "—"
            caption = null
        }
        aggregate == Aggregate.Latest -> {
            val v = values.last()
            value = withUnit(fmt(v), unit)
            caption = asOf
        }
        else -> {
            val m = RustScores.mean(values)
            value = withUnit(fmt(m), unit)
            caption = avgOverDays
        }
    }

    StatTile(
        modifier = modifier,
        label = label,
        value = value,
        caption = caption,
        accent = if (empty) Palette.textTertiary else accent,
    )
}

// MARK: - Chart sections (uniform ChartCard, same height per card)

@Composable
private fun HeartSection(data: AppleData, range: AppleRange) {
    ChartSection(
        stringResource(R.string.applehealth_section_heart),
        stringResource(R.string.applehealth_overline_cardiac),
        range,
    ) {
        MetricChartCard(data, range, "resting_hr", stringResource(R.string.applehealth_resting_hr_full), Palette.metricRose) {
            "${it.roundToInt()} bpm"
        }
        MetricChartCard(data, range, "hrv", stringResource(R.string.applehealth_hrv_full), Palette.metricPurple) {
            "${it.roundToInt()} ms"
        }
        MetricChartCard(data, range, "spo2", stringResource(R.string.applehealth_spo2), Palette.metricCyan) {
            String.format(Locale.US, "%.1f%%", it)
        }
        MetricChartCard(data, range, "resp_rate", stringResource(R.string.applehealth_resp_rate), Palette.accent) {
            String.format(Locale.US, "%.1f rpm", it)
        }
    }
}

@Composable
private fun ActivitySection(data: AppleData, range: AppleRange) {
    ChartSection(
        stringResource(R.string.applehealth_section_activity),
        stringResource(R.string.applehealth_overline_movement),
        range,
    ) {
        MetricChartCard(data, range, "steps", stringResource(R.string.applehealth_steps), Palette.metricCyan) { intString(it) }
        MetricChartCard(data, range, "active_kcal", stringResource(R.string.applehealth_active_energy), Palette.metricAmber) {
            "${intString(it)} kcal"
        }
    }
}

@Composable
private fun BodySection(data: AppleData, range: AppleRange) {
    // Weight + lean mass (stored kg) re-label to lb under the imperial preference.
    val unitSystem = UnitPrefs.system(LocalContext.current)
    ChartSection(
        stringResource(R.string.applehealth_section_body),
        stringResource(R.string.applehealth_overline_slow_threads),
        range,
    ) {
        MetricChartCard(data, range, "weight", stringResource(R.string.applehealth_weight), Palette.accent) {
            UnitFormatter.massFromKilograms(it, unitSystem)
        }
        MetricChartCard(data, range, "body_fat", stringResource(R.string.applehealth_body_fat), Palette.metricAmber) {
            String.format(Locale.US, "%.1f%%", it)
        }
        MetricChartCard(data, range, "lean_mass", stringResource(R.string.applehealth_lean_mass), Palette.accent) {
            UnitFormatter.massFromKilograms(it, unitSystem)
        }
        MetricChartCard(data, range, "bmi", stringResource(R.string.applehealth_bmi), Palette.metricPurple) {
            String.format(Locale.US, "%.1f", it)
        }
    }
}

@Composable
private fun SleepSection(data: AppleData, range: AppleRange) {
    ChartSection(
        stringResource(R.string.applehealth_section_sleep),
        stringResource(R.string.applehealth_overline_rest),
        range,
    ) {
        MetricChartCard(data, range, "asleep_min", stringResource(R.string.applehealth_asleep), Palette.metricPurple) { durationString(it) }
    }
}

@Composable
private fun ChartSection(
    title: String,
    overline: String,
    range: AppleRange,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(title, overline = overline, trailing = stringResource(range.caption))
        content()
    }
}

/**
 * One uniform ChartCard for a metric series: header (title + sparse-aware subtitle +
 * trailing mean), a fixed-height LineChart body, and an avg / min / max / points footer.
 * Sparse-safe via [resolve]: a single point shows a lone read-out instead of a flat line,
 * and an empty window shows "no readings".
 */
@Composable
private fun MetricChartCard(
    data: AppleData,
    range: AppleRange,
    key: String,
    title: String,
    accent: Color,
    fmt: (Double) -> String,
) {
    val resolved = resolve(data.raw(key), range)
    val rows = resolved.rows
    val values = rows.map { it.value }
    val n = values.size
    val mean = if (n > 0) RustScores.mean(values) else null
    val trailing = mean?.let { fmt(it) } ?: "—"

    val subtitle = if (resolved.fellBack) {
        pluralStringResource(
            R.plurals.applehealth_readings_widened, n, n, stringResource(resolved.effective.windowName),
        )
    } else {
        pluralStringResource(R.plurals.applehealth_readings_window, n, n, stringResource(range.windowName))
    }
    val avgLabel = stringResource(R.string.applehealth_avg)
    val minLabel = stringResource(R.string.applehealth_min)
    val maxLabel = stringResource(R.string.applehealth_max)
    val pointsLabel = stringResource(R.string.applehealth_points)

    NoopCard(tint = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(title)
                    Text(
                        subtitle,
                        style = NoopType.footnote,
                        color = if (resolved.fellBack) Palette.statusWarning else Palette.textTertiary,
                    )
                }
                Text(trailing, style = NoopType.number(18f), color = if (n > 0) accent else Palette.textTertiary)
            }

            when {
                n >= 2 -> LineChart(
                    values = values,
                    modifier = Modifier.height(Metrics.chartHeight),
                    color = accent,
                    fill = true,
                )
                n == 1 -> SinglePoint(values.first(), accent, fmt)
                else -> EmptyChart()
            }

            ChartFooterRow(
                items = if (n > 0) {
                    listOf(
                        avgLabel to fmt(RustScores.mean(values)),
                        minLabel to fmt(values.min()),
                        maxLabel to fmt(values.max()),
                        pointsLabel to "$n",
                    )
                } else {
                    listOf(avgLabel to "—", minLabel to "—", maxLabel to "—", pointsLabel to "0")
                },
            )
        }
    }
}

/** Lone-reading body for a series with exactly one point in range. */
@Composable
private fun SinglePoint(value: Double, accent: Color, fmt: (Double) -> String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
            Overline(stringResource(R.string.applehealth_latest_reading))
            Text(fmt(value), style = NoopType.number(34f), color = accent)
        }
    }
}

@Composable
private fun EmptyChart() {
    Box(
        modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.applehealth_no_readings), style = NoopType.subhead, color = Palette.textTertiary)
    }
}

@Composable
private fun ChartFooterRow(items: List<Pair<String, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
        items.forEach { (label, value) ->
            Column {
                Overline(label, color = Palette.textTertiary)
                Text(value, style = NoopType.captionNumber, color = Palette.textSecondary)
            }
        }
    }
}

// MARK: - Formatting helpers

private fun withUnit(value: String, unit: String): String = UnitFormatter.withUnit(value, unit)

private fun intString(v: Double): String {
    val n = v.roundToInt()
    return if (kotlin.math.abs(n) >= 1000) String.format(Locale.US, "%,d", n) else "$n"
}

private fun durationString(minutes: Double): String {
    val total = minutes.roundToInt()
    val h = total / 60
    val m = total % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

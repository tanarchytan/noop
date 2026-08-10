package com.noop.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.data.DailyMetric
import com.noop.data.MoodStore
import com.noop.ingest.NutritionCsvImporter
import uniffi.whoop_ffi.CorrelationStrength
import java.util.Locale
import kotlin.math.abs

// MARK: - Compare
//
// The "overlay metrics & draw conclusions" screen. Pick 2–4 metrics from the catalog, choose a time
// window, and read them on a single normalized overlay chart (each metric min–max
// scaled to 0–1 within the window so different units share an axis). Below, every pair
// of selected metrics gets a live Pearson-r correlation read-out with a plain-English
// conclusion.
//
// Data sourcing: every metric reads from the generic `metricSeries` long-format store via
// repo.series(key, source). And for
// the core daily metrics that also live as columns on the cached `DailyMetric` rows
// (recovery, strain, HRV, RHR, sleep splits, SpO₂, respiration, skin-temp), we fall
// back to deriving the series from repo.days(deviceId) when metricSeries is empty. That
// keeps the screen showing REAL on-device data rather than an empty state when only the
// daily cache (not the generic importer) has populated. Apple-Health body metrics
// (weight/body-fat/etc.) come from metricSeries only — when no importer has run they
// auto-widen to ALL and then show the "no data, widen the range" contract.

// MARK: - Metric catalog

/** One interrogable metric: how to fetch it (key+source), how to label/format it. */
data class CompareMetric(
    val key: String,
    @StringRes val titleRes: Int,
    val category: String,
    val unit: String,
    val source: String,      // "my-whoop" or "apple-health"
    val decimals: Int,
    // Optional honesty note shown in the metric picker (e.g. BMI is derived from the profile height
    // when it comes from Health Connect, since Health Connect carries no measured BMI record). The
    // title is left alone; the caveat lives in this note instead.
    @StringRes val noteRes: Int? = null,
) {
    val id: String get() = "$source:$key"

    fun format(v: Double): String {
        val n = if (decimals == 0) {
            Math.round(v).toString()
        } else {
            java.lang.String.format(Locale.US, "%.${decimals}f", v)
        }
        return UnitFormatter.withUnit(n, unit)
    }

    /** Unit-aware format: weight/lean_mass (kg) and skin_temp (°C) convert + relabel via
     *  [UnitFormatter]; everything else (%, bpm, ms, min, …) is unit-agnostic and falls through. */
    fun format(v: Double, system: UnitSystem, temperature: TemperatureUnit): String = when (unit) {
        "kg" -> UnitFormatter.massFromKilograms(v, system)
        "°C" -> UnitFormatter.temperatureFromCelsius(v, temperature, decimals)
        else -> format(v)
    }

    /** Displayed unit LABEL mapped to the active system (kg→lb, °C→°F); others unchanged. */
    fun displayUnit(system: UnitSystem, temperature: TemperatureUnit): String = when (unit) {
        "kg" -> UnitFormatter.massUnit(system)
        "°C" -> UnitFormatter.temperatureUnit(temperature)
        else -> unit
    }
}

/**
 * Canonical catalog — mirrors MetricCatalog.swift. Keys match exactly what the importers
 * write into metricSeries; [dailyPick], when non-null, is the matching column on
 * [DailyMetric] so a my-whoop metric can be derived from the daily cache as a fallback.
 */
private object CompareCatalog {
    /** Grouping key (also the [CompareMetric.category] match) paired with its display label. */
    val categories = listOf(
        "Heart" to R.string.compare_category_heart,
        "Charge" to R.string.compare_category_charge,
        "Rest" to R.string.compare_category_rest,
        "Effort" to R.string.compare_category_effort,
        "Health" to R.string.compare_category_health,
        "Nutrition" to R.string.compare_category_nutrition,
        "Mind" to R.string.compare_category_mind,
    )

    val all: List<CompareMetric> = listOf(
        // Heart
        CompareMetric("avg_hr", R.string.compare_metric_avg_hr, "Heart", "bpm", "my-whoop", 0),
        CompareMetric("max_hr", R.string.compare_metric_max_hr, "Heart", "bpm", "my-whoop", 0),
        CompareMetric("energy_kcal", R.string.compare_metric_energy_kcal, "Heart", "kcal", "my-whoop", 0),
        CompareMetric("vo2max", R.string.compare_metric_vo2max, "Heart", "", "apple-health", 1),
        CompareMetric("fitness_age", R.string.compare_metric_fitness_age, "Heart", "yrs", "my-whoop", 0),
        CompareMetric("vo2max_est", R.string.compare_metric_vo2max_est, "Heart", "", "my-whoop", 1),
        CompareMetric("vitality", R.string.compare_metric_vitality, "Heart", "", "my-whoop", 0),
        CompareMetric("body_age", R.string.compare_metric_body_age, "Heart", "yrs", "my-whoop", 0),
        // Charge (was Recovery)
        CompareMetric("recovery", R.string.compare_metric_recovery, "Charge", "%", "my-whoop", 0),
        CompareMetric("hrv", R.string.compare_metric_hrv, "Charge", "ms", "my-whoop", 0),
        CompareMetric("rhr", R.string.compare_metric_rhr, "Charge", "bpm", "my-whoop", 0),
        CompareMetric("resp_rate", R.string.compare_metric_resp_rate, "Charge", "rpm", "my-whoop", 1),
        CompareMetric("spo2", R.string.compare_metric_spo2, "Charge", "%", "my-whoop", 0),
        CompareMetric("skin_temp", R.string.compare_metric_skin_temp, "Charge", "°C", "my-whoop", 1),
        // Rest (was Sleep)
        CompareMetric("sleep_performance", R.string.compare_metric_sleep_performance, "Rest", "%", "my-whoop", 0),
        CompareMetric("sleep_total_min", R.string.compare_metric_sleep_total_min, "Rest", "min", "my-whoop", 0),
        CompareMetric("sleep_efficiency", R.string.compare_metric_sleep_efficiency, "Rest", "%", "my-whoop", 0),
        CompareMetric("sleep_deep_min", R.string.compare_metric_sleep_deep_min, "Rest", "min", "my-whoop", 0),
        CompareMetric("sleep_rem_min", R.string.compare_metric_sleep_rem_min, "Rest", "min", "my-whoop", 0),
        CompareMetric("sleep_light_min", R.string.compare_metric_sleep_light_min, "Rest", "min", "my-whoop", 0),
        // Effort (was Strain)
        CompareMetric("strain", R.string.compare_metric_strain, "Effort", "/100", "my-whoop", 1),
        CompareMetric("steps", R.string.compare_metric_steps, "Effort", "", "apple-health", 0),
        // On-device steps ESTIMATE for a WHOOP 4.0 (no real step count over BLE): the strap's daily
        // motion volume scaled by a personal calibration, stored under the computed "-noop" source.
        // Distinct from the real "steps" above — labelled "(estimated)" so it never reads as measured.
        CompareMetric("steps_est", R.string.compare_metric_steps_est, "Effort", "steps", "my-whoop", 0),
        CompareMetric("active_kcal", R.string.compare_metric_active_kcal, "Effort", "kcal", "apple-health", 0),
        // Health / Body
        CompareMetric("weight", R.string.compare_metric_weight, "Health", "kg", "apple-health", 1),
        CompareMetric("body_fat", R.string.compare_metric_body_fat, "Health", "%", "apple-health", 1),
        CompareMetric("lean_mass", R.string.compare_metric_lean_mass, "Health", "kg", "apple-health", 1),
        CompareMetric(
            "bmi", R.string.compare_metric_bmi, "Health", "", "apple-health", 1,
            noteRes = R.string.compare_metric_bmi_note,
        ),
        // Nutrition (imported from a food-tracker CSV — calories-in next to calories-out).
        CompareMetric(
            "calories_in", R.string.compare_metric_calories_in, "Nutrition", "kcal",
            NutritionCsvImporter.SOURCE_ID, 0,
        ),
        CompareMetric(
            "protein_g", R.string.compare_metric_protein_g, "Nutrition", "g",
            NutritionCsvImporter.SOURCE_ID, 0,
        ),
        CompareMetric("carbs_g", R.string.compare_metric_carbs_g, "Nutrition", "g", NutritionCsvImporter.SOURCE_ID, 0),
        CompareMetric("fat_g", R.string.compare_metric_fat_g, "Nutrition", "g", NutritionCsvImporter.SOURCE_ID, 0),
        // Mind (daily mood check-in, 1–5; non-clinical self-tracking).
        CompareMetric("mood", R.string.compare_metric_mood, "Mind", "/5", MoodStore.MOOD_DEVICE_ID, 0),
    )

    fun inCategory(c: String): List<CompareMetric> = all.filter { it.category == c }

    fun byKey(key: String): CompareMetric? = all.firstOrNull { it.key == key }

    fun byId(id: String): CompareMetric? = all.firstOrNull { it.id == id }

    /** Map a my-whoop metric key to the matching DailyMetric column accessor, if any. */
    fun dailyPick(key: String): ((DailyMetric) -> Double?)? = when (key) {
        "recovery" -> { d -> d.recovery }
        "strain" -> { d -> d.strain }
        "hrv" -> { d -> d.avgHrv }
        "rhr" -> { d -> d.restingHr?.toDouble() }
        "resp_rate" -> { d -> d.respRateBpm }
        "spo2" -> { d -> d.spo2Pct }
        // Absolute °C when the row carries it (on-device 5/MG + WHOOP CSV rows), else the ±deviation,
        // so the noop series matches the export's absolute °C it is compared against.
        "skin_temp" -> { d -> d.skinTempAbsC ?: d.skinTempDevC }
        "sleep_total_min" -> { d -> d.totalSleepMin }
        "sleep_efficiency" -> { d -> d.efficiency }
        "sleep_deep_min" -> { d -> d.deepMin }
        "sleep_rem_min" -> { d -> d.remMin }
        "sleep_light_min" -> { d -> d.lightMin }
        else -> null
    }
}

// MARK: - Range control (shared spec — W / M / 3M / 6M / 1Y / ALL)

/** The canonical range window. [days] == null means ALL of history. */
private enum class CompareRange(
    @StringRes val labelRes: Int,
    val days: Int?,
    @StringRes val phraseRes: Int,
) {
    Week(R.string.compare_range_w, 7, R.string.compare_phrase_week),
    Month(R.string.compare_range_m, 30, R.string.compare_phrase_month),
    Quarter(R.string.compare_range_3m, 90, R.string.compare_phrase_quarter),
    Half(R.string.compare_range_6m, 180, R.string.compare_phrase_half),
    Year(R.string.compare_range_1y, 365, R.string.compare_phrase_year),
    All(R.string.compare_range_all, null, R.string.compare_phrase_all);

    /** This range plus every LARGER range, ascending — the auto-expand search order. */
    val widening: List<CompareRange>
        get() = entries.subList(ordinal, entries.size)
}

private val defaultCompareMetricKeys = listOf("recovery", "sleep_performance", "weight")

internal fun parseCompareSelection(raw: String?, minSelection: Int, maxSelection: Int): List<CompareMetric>? {
    if (raw == null) return null

    val tokens = raw.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val parsed = tokens
        .mapNotNull { CompareCatalog.byId(it) }
        .distinctBy { it.id }
        .take(maxSelection)

    return if (parsed.size == tokens.distinct().size || parsed.size >= minSelection) {
        parsed
    } else {
        null
    }
}

private object ComparePrefs {
    private const val KEY_RANGE = "compare.range"
    private const val KEY_SELECTED = "compare.selectedMetrics"

    fun readRange(context: Context): CompareRange {
        val raw = NoopPrefs.of(context).getString(KEY_RANGE, null) ?: return CompareRange.Year
        return CompareRange.entries.firstOrNull { it.name == raw } ?: CompareRange.Year
    }

    fun writeRange(context: Context, range: CompareRange) {
        NoopPrefs.of(context).edit().putString(KEY_RANGE, range.name).apply()
    }

    fun readSelection(context: Context, minSelection: Int, maxSelection: Int): List<CompareMetric> {
        val raw = NoopPrefs.of(context).getString(KEY_SELECTED, null)
        parseCompareSelection(raw, minSelection, maxSelection)?.let { return it }

        val picks = defaultCompareMetricKeys.mapNotNull { CompareCatalog.byKey(it) }
        return (if (picks.isEmpty()) CompareCatalog.all.take(2) else picks).take(maxSelection)
    }

    fun writeSelection(context: Context, selected: List<CompareMetric>) {
        NoopPrefs.of(context).edit()
            .putString(KEY_SELECTED, selected.joinToString(",") { it.id })
            .apply()
    }
}

// MARK: - Per-series model

/**
 * Distinct, high-legibility categorical series colours (avoid the recovery/strain ramps). Four metric
 * tokens, not the chrome accent, which the dark scheme spells the same as [Palette.metricPurple] and so
 * gave the first and third series one dot. A getter, so a theme flip re-resolves it.
 */
private val seriesPalette: List<Color>
    get() = listOf(Palette.metricCyan, Palette.metricPurple, Palette.metricAmber, Palette.metricRose)

/**
 * One selected metric, resolved over the active window: its descriptor, the windowed
 * (day,value) rows, a stable display color, and its real min/max.
 */
private data class CompareSeries(
    val metric: CompareMetric,
    val color: Color,
    val rows: List<Pair<String, Double>>,
) {
    val id: String get() = metric.id
    val values: List<Double> get() = rows.map { it.second }
    val realMin: Double get() = values.minOrNull() ?: 0.0
    val realMax: Double get() = values.maxOrNull() ?: 0.0

    /** Min–max normalize a value into 0…1 within this series' window. Flat → mid-line. */
    fun normalized(v: Double): Double {
        val lo = realMin
        val hi = realMax
        if (hi <= lo) return 0.5
        return ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    }
}

// MARK: - Day arithmetic (fixed UTC, deterministic — mirrors compareDayParser/slice)

/** "yyyy-MM-dd" → ordinal day count (days since a fixed epoch), or null if unparseable. */
private fun dayOrdinal(day: String): Long? {
    val parts = day.split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    if (m !in 1..12 || d < 1) return null
    // Howard Hinnant's days-from-civil — monotonic proleptic Gregorian day number.
    var yy = y
    var mm = m
    if (mm <= 2) { yy -= 1; mm += 12 }
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (mm - 3) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097L + doe - 719468L
}

// MARK: - Root

/**
 * CompareScreen — overlay 2–4 normalized signals and read their pairwise Pearson
 * correlations. Faithful port of CompareView.swift onto the locked Compose components.
 */
@Composable
fun CompareScreen(vm: AppViewModel) {
    val days by vm.recentDays.collectAsStateWithLifecycle()

    val context = LocalContext.current

    val maxSelection = 4
    val minSelection = 2

    var range by remember { mutableStateOf(ComparePrefs.readRange(context)) }
    // Ordered selection (max 4). Drives both the legend order and color mapping.
    val selected = remember {
        mutableStateListOf<CompareMetric>().apply {
            addAll(ComparePrefs.readSelection(context, minSelection, maxSelection))
        }
    }
    // Full-history series per selected metric id (ascending by day).
    val fullSeries = remember { mutableStateMapOf<String, List<Pair<String, Double>>>() }
    var loadedOnce by remember { mutableStateOf(false) }

    // Load the full history for any selected metric not yet fetched, whenever the
    // selection set or the daily cache changes.
    val selectionKey = selected.joinToString("|") { it.id }
    LaunchedEffect(selectionKey, days) {
        for (metric in selected) {
            // Always (re)load derived-from-daily my-whoop series when the cache grew;
            // metricSeries-only metrics are loaded once.
            val pick = if (metric.source == "my-whoop") CompareCatalog.dailyPick(metric.key) else null
            val needsLoad = !fullSeries.containsKey(metric.id) ||
                (pick != null && fullSeries[metric.id].isNullOrEmpty())
            if (needsLoad) {
                fullSeries[metric.id] = loadFullSeries(vm, metric, days)
            }
        }
        loadedOnce = true
    }

    // ── Windowing (RELATIVE to each series' latest point).
    fun slice(full: List<Pair<String, Double>>, r: CompareRange): List<Pair<String, Double>> {
        val n = r.days ?: return full
        val lastDay = full.lastOrNull()?.first ?: return emptyList()
        val lastOrd = dayOrdinal(lastDay) ?: return emptyList()
        val cutoff = lastOrd - (n - 1)
        return full.filter { (day, _) ->
            val o = dayOrdinal(day) ?: return@filter false
            o >= cutoff
        }
    }

    // The range actually used for a series: the SELECTED range when it holds ≥1 point,
    // else the smallest LARGER range that does (so sparse metrics still overlay).
    fun effectiveRange(full: List<Pair<String, Double>>): CompareRange {
        if (full.isEmpty()) return range
        for (r in range.widening) if (slice(full, r).isNotEmpty()) return r
        return CompareRange.All
    }

    // Selected metrics resolved to windowed rows + stable colors, in pick order.
    val activeSeries: List<CompareSeries> = selected.mapIndexed { idx, metric ->
        val full = fullSeries[metric.id] ?: emptyList()
        val rows = slice(full, effectiveRange(full))
        CompareSeries(
            metric = metric,
            color = seriesPalette[idx % seriesPalette.size],
            rows = rows,
        )
    }

    val anyWidened = selected.any { metric ->
        val full = fullSeries[metric.id] ?: emptyList()
        full.isNotEmpty() && effectiveRange(full) != range
    }

    val rangeCaption: String = run {
        val total = activeSeries.sumOf { it.rows.size }
        val unit = stringResource(
            if (total == 1) R.string.compare_reading else R.string.compare_readings,
        )
        val base = stringResource(
            R.string.compare_range_caption,
            total,
            unit,
            activeSeries.size,
            stringResource(range.phraseRes),
        )
        if (anyWidened) stringResource(R.string.compare_range_caption_widened, base) else base
    }

    // No topBackground: the scaffold takes its opaque path and paints Palette.surfaceBase, so the canvas
    // follows the theme in both light and dark.
    LazyScreenScaffold(
        title = stringResource(R.string.compare_title),
        subtitle = stringResource(R.string.compare_subtitle),
    ) {

        // ── Metric picker section (chips + range control)
        item {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SectionHeader(
                stringResource(R.string.compare_metrics),
                overline = stringResource(R.string.compare_metrics_overline),
            )
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    val rangeLabels = HashMap<CompareRange, String>()
                    for (r in CompareRange.entries) rangeLabels[r] = stringResource(r.labelRes)
                    // Six segments need the full row, so the add-metric control sits under the window
                    // picker rather than fighting it for width.
                    SegmentedPillControl(
                        items = CompareRange.entries.toList(),
                        selection = range,
                        label = { rangeLabels.getValue(it) },
                        onSelect = {
                            range = it
                            ComparePrefs.writeRange(context, it)
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AddMetricMenu(
                            selectedCount = selected.size,
                            maxSelection = maxSelection,
                            isSelected = { m -> selected.any { it.id == m.id } },
                            onToggle = { m ->
                                val existing = selected.firstOrNull { it.id == m.id }
                                if (existing != null) {
                                    selected.remove(existing)
                                } else if (selected.size < maxSelection) {
                                    selected.add(m)
                                }
                                ComparePrefs.writeSelection(context, selected)
                            },
                        )
                    }

                    if (selected.size >= minSelection) {
                        Text(
                            rangeCaption,
                            style = NoopType.footnote,
                            color = if (anyWidened) Palette.statusWarning else Palette.textTertiary,
                        )
                    }

                    if (selected.isEmpty()) {
                        Text(
                            stringResource(R.string.compare_nothing_selected),
                            style = NoopType.subhead,
                            color = Palette.textTertiary,
                        )
                    } else {
                        FlowChips(
                            metrics = selected.toList(),
                            colorFor = { m ->
                                val i = selected.indexOfFirst { it.id == m.id }
                                if (i < 0) Palette.textSecondary else seriesPalette[i % seriesPalette.size]
                            },
                            onRemove = { m ->
                                selected.removeAll { it.id == m.id }
                                ComparePrefs.writeSelection(context, selected)
                            },
                        )
                    }
                }
            }
        }
        }

        if (selected.size < minSelection) {
            item {
                EmptyNote(stringResource(R.string.compare_pick_two))
            }
        } else {
            val nonEmpty = activeSeries.filter { it.rows.isNotEmpty() }
            if (nonEmpty.isEmpty()) {
                if (loadedOnce) {
                    item {
                        DataPendingNote(
                            title = stringResource(R.string.compare_needs_history_title),
                            body = stringResource(R.string.compare_needs_history_body),
                        )
                    }
                } else {
                    item { EmptyNote(stringResource(R.string.compare_reading_history)) }
                }
            } else {
                item { OverlaySection(nonEmpty, range, anyWidened) }
                // A picked metric with no reading in the window draws nothing, so it is named rather than
                // left as a chip with no line.
                val silent = activeSeries.filter { it.rows.isEmpty() }
                if (silent.isNotEmpty()) {
                    item {
                        val joiner = stringResource(R.string.compare_silent_joiner)
                        val silentTitles = ArrayList<String>(silent.size)
                        for (s in silent) silentTitles.add(stringResource(s.metric.titleRes))
                        val names = silentTitles.joinToString(joiner)
                        EmptyNote(
                            stringResource(
                                if (silent.size == 1) {
                                    R.string.compare_silent_series_one
                                } else {
                                    R.string.compare_silent_series_many
                                },
                                names,
                            ),
                        )
                    }
                }
                item { CorrelationSection(activeSeries, range) }
            }
        }
    }
}

// MARK: - Series loading

/**
 * Load the full history for [metric] (ascending by day). repo.resolvedSeries(key, source) resolves
 * across compatible sources freshest-wins —
 * imported WHOOP > NOOP-computed > declared-compatible Apple Health — and gap-fills from the
 * DailyMetric columns for the days the long-format metricSeries doesn't carry, so the screen shows
 * real on-device data even when only the daily cache (not the generic importer) has populated.
 */
private suspend fun loadFullSeries(
    vm: AppViewModel,
    metric: CompareMetric,
    @Suppress("UNUSED_PARAMETER") cachedDays: List<DailyMetric>,
): List<Pair<String, Double>> {
    // Wide window covering all of history (4000 days).
    val to = todayDay(1)
    val from = todayDay(-4000)
    return vm.repo.resolvedSeries(metric.key, metric.source, from, to).values
}

/** "yyyy-MM-dd" for today offset by [deltaDays], fixed UTC. */
private fun todayDay(deltaDays: Int): String {
    val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
    cal.add(java.util.Calendar.DAY_OF_YEAR, deltaDays)
    return String.format(
        Locale.US,
        "%04d-%02d-%02d",
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
    )
}

// MARK: - Add-metric menu (grouped by category, ported from CompareView.addMenu)

@Composable
private fun AddMetricMenu(
    selectedCount: Int,
    maxSelection: Int,
    isSelected: (CompareMetric) -> Boolean,
    onToggle: (CompareMetric) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val atMax = selectedCount >= maxSelection
    val tint = if (atMax) Palette.textTertiary else Palette.accent

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickableNoRippleLocal(enabled = !atMax) { expanded = true }
                .padding(horizontal = Metrics.space8, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.compare_add_metric_a11y),
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(
                    if (atMax) R.string.compare_max_four else R.string.compare_add_metric,
                ),
                style = NoopType.subhead,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Palette.surfaceOverlay),
        ) {
            CompareCatalog.categories.forEach { (category, categoryLabelRes) ->
                val metrics = CompareCatalog.inCategory(category)
                if (metrics.isNotEmpty()) {
                    Text(
                        stringResource(categoryLabelRes).uppercase(),
                        style = NoopType.overline,
                        color = Palette.textTertiary,
                        modifier = Modifier.padding(horizontal = Metrics.space12, vertical = 6.dp),
                    )
                    metrics.forEach { metric ->
                        val on = isSelected(metric)
                        val enabled = on || selectedCount < maxSelection
                        DropdownMenuItem(
                            enabled = enabled,
                            onClick = { onToggle(metric) },
                            leadingIcon = {
                                if (on) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Palette.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                } else {
                                    Spacer(Modifier.size(16.dp))
                                }
                            },
                            text = {
                                Column {
                                    Text(
                                        stringResource(metric.titleRes),
                                        style = NoopType.body,
                                        color = if (enabled) Palette.textPrimary else Palette.textTertiary,
                                    )
                                    metric.noteRes?.let {
                                        Text(
                                            stringResource(it),
                                            style = NoopType.footnote,
                                            color = Palette.textTertiary,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Selected-metric chips (wrapping flow layout)

/** Removable chips for the active selection, tinted to each series' color. */
@Composable
private fun FlowChips(
    metrics: List<CompareMetric>,
    colorFor: (CompareMetric) -> Color,
    onRemove: (CompareMetric) -> Unit,
) {
    // Two-up rows keep the chips readable on phone widths (mirrors the adaptive grid).
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        metrics.chunked(2).forEach { rowChips ->
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                rowChips.forEach { metric ->
                    MetricChip(
                        modifier = Modifier.weight(1f),
                        title = stringResource(metric.titleRes),
                        color = colorFor(metric),
                        onRemove = { onRemove(metric) },
                    )
                }
                if (rowChips.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricChip(
    title: String,
    color: Color,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(50)
    // liquidPress on the whole pick chip, driven by the SAME interactionSource that drives its only tap
    // target (the remove ✕) — so pressing to remove settles the chip inward, the pilot's tappable-card feel.
    // The remove gesture is unchanged (still the ✕ tap → onRemove).
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .clip(shape)
            .liquidPress(interaction)
            .background(Palette.surfaceOverlay)
            .border(Metrics.divider, color.copy(alpha = 0.4f), shape)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            title,
            style = NoopType.subhead,
            color = Palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.compare_remove_metric_a11y, title),
            tint = Palette.textTertiary,
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onRemove,
                )
                .padding(3.dp),
        )
    }
}

// MARK: - Overlay chart section

@Composable
private fun OverlaySection(
    series: List<CompareSeries>,
    range: CompareRange,
    anyWidened: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.compare_overlay),
            overline = stringResource(range.phraseRes),
            trailing = stringResource(R.string.compare_series_count, series.size),
        )
        // Anchor the overlay card to the brand-green chrome world; each line keeps its own categorical
        // series colour so the overlaid lines stay distinguishable against the wash.
        NoopCard(tint = Palette.accent) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Overline(stringResource(R.string.compare_normalized_overlay))
                Text(
                    stringResource(
                        if (anyWidened) {
                            R.string.compare_normalized_widened
                        } else {
                            R.string.compare_normalized_within
                        },
                        stringResource(range.phraseRes),
                    ),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )

                OverlayChart(
                    series = series,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Metrics.chartHeight),
                )

                // Endpoint axis labels (low / high) for the normalized y-axis.
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.compare_axis_low),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.compare_axis_high),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }

                HorizontalDivider(color = Palette.hairline)

                // Legend with real per-series min/max.
                Legend(series)
            }
        }
    }
}

/**
 * Multi-line normalized overlay drawn on a Compose Canvas. Each series is min–max
 * scaled into 0…1 within its own window, then placed on a shared day x-axis spanning
 * the union of all days present, so different units share one chart.
 */
@Composable
private fun OverlayChart(series: List<CompareSeries>, modifier: Modifier) {
    // PERF (#scroll-jank — drawing-bound): the old draw lambda re-parsed every day string (dayOrdinal)
    // and re-normalised every value on EVERY frame, then rebuilt each series' Path. Precompute the
    // expensive, size-INDEPENDENT part once per `series` change: the parsed (ordinal, normalized) pairs
    // and the shared x-domain. The math is byte-identical to the old per-point computation; only its
    // timing moves out of the hot draw loop.
    val prepared = remember(series) {
        // Per series: its rows reduced to (ordinal, norm0to1) pairs, dropping unparseable days exactly
        // as the old `mapNotNull { dayOrdinal(...) }` did — same order, same drop rule, same normalize.
        val perSeries = series.map { s ->
            val pairs = s.rows.mapNotNull { (day, value) ->
                val ord = dayOrdinal(day) ?: return@mapNotNull null
                ord to s.normalized(value).toFloat().coerceIn(0f, 1f)
            }
            Triple(s.color, pairs, s)
        }
        val allOrds = perSeries.flatMap { (_, pairs, _) -> pairs.map { it.first } }
        OverlayPrepared(minOrd = allOrds.minOrNull(), maxOrd = allOrds.maxOrNull(), perSeries = perSeries)
    }

    // Build the per-series Paths in drawWithCache — rebuilt only when the prepared pairs or the canvas
    // size change (NOT on unrelated recompositions), instead of allocating a fresh Path every frame.
    Box(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val topPad = 6f
            val usableH = (h - topPad * 2f).coerceAtLeast(1f)
            val minOrd = prepared.minOrd
            val maxOrd = prepared.maxOrd

            // Pre-place each series' pixel points + Path once (size-dependent, so it lives here, keyed
            // on size by drawWithCache). x/y formulas are identical to the old per-frame computation.
            data class Built(val color: Color, val path: Path?, val singleDot: Offset?, val last: Offset?)
            val built = if (minOrd != null && maxOrd != null) {
                val span = (maxOrd - minOrd).coerceAtLeast(1L).toFloat()
                prepared.perSeries.map { (color, pairs, _) ->
                    val pts = pairs.map { (ord, norm) ->
                        val x = if (maxOrd > minOrd) (ord - minOrd).toFloat() / span * w else w / 2f
                        val y = topPad + (1f - norm) * usableH
                        Offset(x, y)
                    }
                    when {
                        pts.size < 2 -> Built(color, null, pts.firstOrNull(), null)
                        else -> {
                            val path = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                            }
                            Built(color, path, null, pts.last())
                        }
                    }
                }
            } else {
                emptyList()
            }

            val gridColor = Palette.hairline.copy(alpha = 0.4f)
            val tipCore = Palette.tipCore

            onDrawBehind {
                if (w <= 0f || h <= 0f) return@onDrawBehind

                // Faint low / mid / high gridlines.
                for (f in listOf(0f, 0.5f, 1f)) {
                    val y = topPad + (1f - f) * usableH
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1f,
                    )
                }

                if (minOrd == null || maxOrd == null) return@onDrawBehind

                built.forEach { b ->
                    if (b.singleDot != null) {
                        // A single point still renders as a dot so the series is visible.
                        drawCircle(b.color, radius = 3.5f, center = b.singleDot)
                        return@forEach
                    }
                    if (b.path != null) {
                        drawPath(
                            path = b.path,
                            color = b.color,
                            style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                    // Bevel "now" end-cap on this series' latest point — soft halo + bright core + white centre.
                    b.last?.let { last ->
                        drawCircle(color = b.color.copy(alpha = 0.30f), radius = 8f, center = last)
                        drawCircle(color = b.color.copy(alpha = 0.65f), radius = 5f, center = last)
                        drawCircle(color = tipCore, radius = 2.2f, center = last)
                    }
                }
            }
        },
    )
}

/** Pre-parsed overlay inputs (size-independent): the shared x-domain + each series' (ordinal, norm) pairs. */
private data class OverlayPrepared(
    val minOrd: Long?,
    val maxOrd: Long?,
    val perSeries: List<Triple<Color, List<Pair<Long, Float>>, CompareSeries>>,
)

@Composable
private fun Legend(series: List<CompareSeries>) {
    // Imperial/Metric display preference. Only weight/lean mass (kg) and skin temp (°C) in the
    // catalog carry a convertible unit; the min–max labels re-label under the toggle. Display-only.
    val context = LocalContext.current
    val unitSystem = UnitPrefs.system(context)
    val tempUnit = UnitPrefs.temperature(context)
    Column {
        series.forEachIndexed { idx, s ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
            ) {
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(s.color),
                )
                Text(
                    stringResource(s.metric.titleRes),
                    style = NoopType.subhead,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${s.metric.format(s.realMin, unitSystem, tempUnit)}-" +
                        s.metric.format(s.realMax, unitSystem, tempUnit),
                    style = NoopType.captionNumber,
                    color = Palette.textSecondary,
                )
            }
            if (idx < series.size - 1) HorizontalDivider(color = Palette.hairline)
        }
    }
}

// MARK: - Pairwise correlation section

private data class PairResult(
    val a: CompareSeries,
    val b: CompareSeries,
    val r: Double,
    val n: Int,
) {
    val id: String get() = "${a.id}~${b.id}"
}

/** The pairwise Pearson scan over the non-empty series — strongest relationships first. */
private fun computePairResults(series: List<CompareSeries>): List<PairResult> {
    val s = series.filter { it.rows.isNotEmpty() }
    if (s.size < 2) return emptyList()
    val out = ArrayList<PairResult>()
    for (i in 0 until s.size - 1) {
        for (j in i + 1 until s.size) {
            val pairs = CorrelationEngine.alignByDay(s[i].rows, s[j].rows)
            val c = CorrelationEngine.pearson(pairs) ?: continue
            out.add(PairResult(a = s[i], b = s[j], r = c.r, n = c.n))
        }
    }
    out.sortByDescending { abs(it.r) }
    return out
}

@Composable
private fun CorrelationSection(series: List<CompareSeries>, range: CompareRange) {
    // A stable fingerprint of the windowed series content; the scan recomputes only when
    // that content changes (selection / range / data updates), never on unrelated recompose.
    val key = series.joinToString("|") { s ->
        "${s.id}:${s.rows.size}:${s.rows.firstOrNull()?.first ?: ""}>${s.rows.lastOrNull()?.first ?: ""}"
    }
    val pairs = remember(key) { computePairResults(series) }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.compare_how_they_move),
            overline = stringResource(R.string.compare_pearson_r, stringResource(range.phraseRes)),
            trailing = if (pairs.isEmpty()) {
                null
            } else {
                stringResource(
                    if (pairs.size == 1) R.string.compare_pair_count_one else R.string.compare_pair_count_many,
                    pairs.size,
                )
            },
        )

        if (pairs.isEmpty()) {
            NoopCard {
                Text(
                    stringResource(R.string.compare_no_overlap, stringResource(range.phraseRes)),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else {
            pairs.forEach { PairCard(it) }
        }
    }
}

/** The correlation ring on a pair card. */
private val PAIR_RING_DIAMETER: Dp = 38.dp

@Composable
private fun PairCard(p: PairResult) {
    val tint = correlationColor(p.r)
    // Frosted card washed by the relationship's own colour (green positive / rose negative), with a
    // TrendChip surfacing the signed direction at a glance — Today's delta idiom, applied to r.
    NoopCard(tint = tint) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(p.a.color))
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(p.b.color))
                }
                Text(
                    stringResource(
                        R.string.compare_pair_title,
                        stringResource(p.a.metric.titleRes),
                        stringResource(p.b.metric.titleRes),
                    ),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TrendChip(text = signedR(p.r), color = tint)
                // |r| as a small ring in the relationship's own tint, beside the chip that prints the
                // signed number. The ring carries the magnitude only: a signed r is five glyphs, wider
                // than this ring's inner circle, and the chip already says it. The fill key is the pair,
                // so a scroll that recycles the row brings the ring back already filled.
                GlowRing(
                    fraction = abs(p.r).coerceIn(0.0, 1.0).toFloat(),
                    value = p.r,
                    color = tint,
                    diameter = PAIR_RING_DIAMETER,
                    lineWidth = PAIR_RING_DIAMETER * RING_STROKE_FRACTION,
                    fillKey = "compare.${p.a.metric}.${p.b.metric}",
                    showsLabel = false,
                )
            }

            Text(insightSentence(p), style = NoopType.subhead, color = Palette.textSecondary)

            Text(
                stringResource(
                    R.string.compare_pair_footnote,
                    p.n,
                    strengthWord(p.r),
                    directionWord(p.r),
                ).replace("  ", " "),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - Insight language (ported from CompareView)

@Composable
private fun insightSentence(p: PairResult): String {
    val aTitle = stringResource(p.a.metric.titleRes)
    val bTitle = stringResource(p.b.metric.titleRes)
    val strength = strengthWord(p.r)
    val direction = directionWord(p.r)
    val head = stringResource(
        R.string.compare_insight_head,
        aTitle, bTitle, signedR(p.r), strength, direction, p.n,
    ).replace("  ", " ").replace(" )", ")")
    if (abs(p.r) < 0.3) {
        return stringResource(R.string.compare_insight_no_relationship, head)
    }
    val verb = stringResource(
        if (p.r < 0) R.string.compare_verb_tends_to_fall else R.string.compare_verb_tends_to_rise,
    )
    return stringResource(
        R.string.compare_insight_link,
        head, aTitle.lowercase(), bTitle.lowercase(), verb, strength, direction,
    )
}

private fun signedR(r: Double): String {
    val sign = if (r >= 0) "+" else "−"
    return sign + java.lang.String.format(Locale.US, "%.2f", abs(r))
}

@Composable
private fun strengthWord(r: Double): String = stringResource(
    when (CorrelationEngine.strength(r)) {
        CorrelationStrength.NEGLIGIBLE -> R.string.compare_strength_negligible
        CorrelationStrength.WEAK -> R.string.compare_strength_weak
        CorrelationStrength.MODERATE -> R.string.compare_strength_moderate
        CorrelationStrength.STRONG -> R.string.compare_strength_strong
        CorrelationStrength.VERY_STRONG -> R.string.compare_strength_very_strong
    },
)

@Composable
private fun directionWord(r: Double): String {
    if (abs(r) < 0.1) return ""
    return stringResource(
        if (r >= 0) R.string.compare_direction_positive else R.string.compare_direction_negative,
    )
}

private fun correlationColor(r: Double): Color {
    val base = if (r >= 0) Palette.statusPositive else Palette.statusCritical
    return base.copy(alpha = 0.55f + 0.45f * abs(r).coerceAtMost(1.0).toFloat())
}

// MARK: - Empty-state note

@Composable
private fun EmptyNote(text: String) {
    NoopCard {
        Text(
            text,
            style = NoopType.subhead,
            color = Palette.textTertiary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// MARK: - Local interaction helper (clickable without ripple, optionally disabled)

@Composable
private fun Modifier.clickableNoRippleLocal(enabled: Boolean, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.then(
        Modifier.clickable(
            enabled = enabled,
            indication = null,
            interactionSource = interaction,
            onClick = onClick,
        ),
    )
}

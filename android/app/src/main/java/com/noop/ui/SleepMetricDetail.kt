package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

// MARK: - Sleep metric detail sheet

private enum class SleepMetricRange(val label: String, val days: Long?) {
    WEEK("W", 7), MONTH("M", 30), THREE_MONTH("3M", 90),
    SIX_MONTH("6M", 180), YEAR("1Y", 365), ALL("ALL", null),
}

private data class SleepMetricSpec(
    val title: String,
    val unit: String,
    val color: Color,
    val format: (Double) -> String,
)

private fun sleepMetricSpec(key: String): SleepMetricSpec = when (key) {
    "performance"     -> SleepMetricSpec("Rest", "%", Palette.restColor) { "${it.roundToInt()}" }
    "efficiency"      -> SleepMetricSpec("Sleep Efficiency", "%", Palette.statusPositive) { "${it.roundToInt()}" }
    "consistency"     -> SleepMetricSpec("Consistency", "%", Palette.metricCyan) { "${it.roundToInt()}" }
    "hours_vs_needed" -> SleepMetricSpec("Hours vs Needed", "%", Palette.restColor) { "${it.roundToInt()}" }
    "restorative"     -> SleepMetricSpec("Restorative", "%", Palette.sleepREM) { "${it.roundToInt()}" }
    "respiratory"     -> SleepMetricSpec("Respiratory Rate", "rpm", Palette.metricPurple) { String.format(Locale.US, "%.1f", it) }
    "sleep_debt"      -> SleepMetricSpec("Sleep Debt", "h", Palette.metricRose) { String.format(Locale.US, "%.1f", it) }
    else              -> SleepMetricSpec(key, "", Palette.accent) { "${it.roundToInt()}" }
}

private fun buildSleepMetricPoints(days: List<DailyMetric>, key: String): List<Pair<String, Double>> {
    val needMin = max(450.0, days.mapNotNull { it.totalSleepMin?.takeIf { m -> m > 0.0 } }.average().let { if (it.isNaN()) 480.0 else it })
    return days.mapNotNull { d ->
        val v: Double? = when (key) {
            // The Rest detail graph reads the REAL resolved Rest composite per day (RestScorer.restFromDaily,
            // the same source the Today Rest score uses), not a local hours-vs-need approximation, so the graph
            // and the score agree.
            "performance" -> com.noop.analytics.RestScorer.restFromDaily(d)?.takeIf { it in 0.0..100.0 }
            "efficiency"  -> d.efficiency?.let { if (it <= 1.0) it * 100.0 else it }
            "consistency" -> {
                val idx = days.indexOf(d)
                val lo = max(0, idx - 13)
                val window = days.subList(lo, idx + 1).mapNotNull { it.totalSleepMin?.takeIf { m -> m > 0.0 } }
                if (window.size < 3) null else {
                    val m = window.average()
                    val sd = kotlin.math.sqrt(window.sumOf { (it - m) * (it - m) } / window.size)
                    (100.0 * (1.0 - sd / 90.0)).coerceIn(0.0, 100.0)
                }
            }
            "hours_vs_needed" -> d.totalSleepMin?.takeIf { it > 0.0 }?.let { minOf(100.0, it / needMin * 100.0) }
            "restorative" -> {
                val dp = d.deepMin ?: return@mapNotNull null
                val rm = d.remMin ?: return@mapNotNull null
                val sl = d.totalSleepMin ?: return@mapNotNull null
                if (sl > 0.0) (dp + rm) / sl * 100.0 else null
            }
            "respiratory" -> d.respRateBpm
            "sleep_debt"  -> d.totalSleepMin?.let { max(0.0, needMin - it) / 60.0 }
            else          -> null
        }
        v?.takeIf { it.isFinite() }?.let { d.day to it }
    }
}

private fun filterSleepMetricPoints(
    points: List<Pair<String, Double>>,
    range: SleepMetricRange,
): List<Pair<String, Double>> {
    val windowDays = range.days ?: return points
    val latestDate = points.lastOrNull()?.first?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return points.takeLast(windowDays.toInt())
    val cutoff = latestDate.minusDays(windowDays - 1)
    val filtered = points.filter { (day, _) ->
        runCatching { LocalDate.parse(day) }.getOrNull()?.let { !it.isBefore(cutoff) } ?: false
    }
    return filtered.ifEmpty { points.takeLast(windowDays.toInt()) }
}

@Composable
internal fun SleepMetricDetailSheetContent(vm: AppViewModel, key: String) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    var range by remember { mutableStateOf(SleepMetricRange.MONTH) }
    val spec = remember(key) { sleepMetricSpec(key) }
    val allPoints = remember(days, key) { buildSleepMetricPoints(days, key) }
    val filteredPoints = remember(allPoints, range) { filterSleepMetricPoints(allPoints, range) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.space24, vertical = Metrics.space8),
        verticalArrangement = Arrangement.spacedBy(Metrics.space16),
    ) {
        if (allPoints.size < 2) {
            Text("Not enough history yet", style = NoopType.headline, color = Palette.textPrimary)
            Text(
                "This metric needs at least two nights of data.",
                style = NoopType.subhead, color = Palette.textSecondary,
            )
            Spacer(Modifier.height(Metrics.space16))
        } else if (filteredPoints.size < 2) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline("Sleep")
                    Text(spec.title, style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            SegmentedPillControl(
                items = SleepMetricRange.entries,
                selection = range,
                label = { it.label },
                onSelect = { range = it },
            )
            Text("Not enough history in this range. Try 3M, 6M, or ALL.", style = NoopType.subhead, color = Palette.textSecondary)
            Spacer(Modifier.height(Metrics.space16))
        } else {
            val values = filteredPoints.map { it.second }
            val dates = filteredPoints.map { it.first }
            val latest = filteredPoints.last()
            val minV = values.minOrNull() ?: 0.0
            val maxV = values.maxOrNull() ?: 0.0
            val avgV = values.average()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline("Sleep · ${filteredPoints.size} nights")
                    Text(spec.title, style = NoopType.title2, color = Palette.textPrimary)
                    Text("as of ${latest.first}", style = NoopType.footnote, color = Palette.textTertiary)
                }
                Text(
                    "${spec.format(latest.second)} ${spec.unit}".trim(),
                    style = NoopType.chartValue,
                    color = spec.color,
                )
            }
            SegmentedPillControl(
                items = SleepMetricRange.entries,
                selection = range,
                label = { it.label },
                onSelect = { range = it },
            )
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Metrics.space4),
            ) {
                Column(
                    modifier = Modifier.height(Metrics.chartHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${spec.format(maxV)} ${spec.unit}".trim(), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                    Text("${spec.format(avgV)} ${spec.unit}".trim(), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                    Text("${spec.format(minV)} ${spec.unit}".trim(), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                }
                LineChart(
                    values = values,
                    modifier = Modifier.weight(1f).height(Metrics.chartHeight)
                        .semantics { contentDescription = "${spec.title} trend chart" },
                    color = spec.color,
                    fill = true,
                    selectionEnabled = true,
                )
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf(dates.first(), dates.getOrNull(dates.lastIndex / 2), dates.last()).forEach { d ->
                    Text(
                        d?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) }.getOrDefault(it) }.orEmpty(),
                        style = NoopType.footnote, color = Palette.textTertiary,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CardHairline()
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("Min" to minV, "Avg" to avgV, "Max" to maxV).forEach { (lbl, v) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Overline(lbl, color = Palette.textTertiary)
                        Text(
                            "${spec.format(v)} ${spec.unit}".trim(),
                            style = NoopType.captionNumber, color = Palette.textPrimary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Metrics.space8))
        }
    }
}

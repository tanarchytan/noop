package com.noop.ui.whoop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.DaytimeStress
import com.noop.ui.AppViewModel
import com.noop.ui.BevelGauge
import com.noop.ui.DataPendingNote
import com.noop.ui.DayPagerBar
import com.noop.ui.GalleryCard
import com.noop.ui.InsetChartPlaceholder
import com.noop.ui.LazyScreenScaffold
import com.noop.ui.LineChart
import com.noop.ui.Metrics
import com.noop.ui.NoopCard
import com.noop.ui.NoopType
import com.noop.ui.Overline
import com.noop.ui.Palette
import com.noop.ui.SectionHeader
import com.noop.ui.SegmentBar
import com.noop.ui.durationText
import java.util.Locale

// MARK: - Stress Monitor detail
//
// One day at a time: the day's 0-3 score on a half-gauge, the hours whoop-rs scored across it, how
// those hours split by band, and the breathing session that answers a high one.

/** The 0-3 stress score to one decimal — the scale whoop-rs's daily and hourly stress both use. */
private fun stressText(score: Double): String = String.format(Locale.US, "%.1f", score)

/**
 * The Stress Monitor. [onBreathe] opens the paced-breathing trainer; it defaults to a no-op so the
 * screen previews with nothing wired.
 */
@Composable
fun WhoopStressMonitorScreen(
    vm: AppViewModel,
    onBreathe: () -> Unit = {},
) {
    val days by vm.recentDays.collectAsStateWithLifecycle()

    val dayKeys = remember(days) { days.map { it.day } }
    // The pager holds a DAY, not an index, so a data refresh cannot slide the selection.
    var pickedDay by remember { mutableStateOf<String?>(null) }
    val index = remember(dayKeys, pickedDay) {
        val picked = pickedDay?.let { dayKeys.indexOf(it) } ?: -1
        if (picked >= 0) picked else dayKeys.lastIndex
    }
    val dayIso = dayKeys.getOrNull(index)

    var stored by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(Unit) { stored = loadStoredStress(vm) }
    val score = remember(days, stored, index) { dailyStressScore(days, stored, index) }

    var intraday by remember { mutableStateOf<DaytimeStress.Result?>(null) }
    LaunchedEffect(dayIso) {
        intraday = null
        intraday = dayIso?.let { runCatching { loadStressDay(vm, it) }.getOrDefault(DaytimeStress.Result.EMPTY) }
    }

    LazyScreenScaffold(
        title = "Stress Monitor",
        subtitle = "Autonomic load from heart rate and HRV.",
    ) {
        if (dayIso == null) {
            item {
                DataPendingNote(
                    title = "No stress history yet",
                    body = "Wear your strap through the day, or import a WHOOP export in Data " +
                        "Sources, and your stress reads here.",
                )
            }
            return@LazyScreenScaffold
        }

        item {
            DayPagerBar(
                label = stressDayLabel(dayIso),
                onPrevious = { pickedDay = dayKeys.getOrNull(index - 1) ?: pickedDay },
                onNext = { pickedDay = dayKeys.getOrNull(index + 1) ?: pickedDay },
                canGoPrevious = index > 0,
                canGoNext = index < dayKeys.lastIndex,
            )
        }
        item { StressHeroCard(score) }
        item { StressDayCard(intraday) }
        item { TimeInBandCard(intraday) }
        item { SessionsSection(onBreathe) }
    }
}

// MARK: - Hero

/** The day's 0-3 score on the half-gauge, or the calibrating note when whoop-rs scored no value. */
@Composable
private fun StressHeroCard(score: Double?) {
    NoopCard(tint = Palette.stressColor) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            Overline("Stress", modifier = Modifier.fillMaxWidth())
            if (score == null) {
                InsetChartPlaceholder(
                    message = "Not enough resting heart rate or HRV to score this day.",
                    height = Metrics.compactChartHeight,
                )
            } else {
                val fraction = (score / STRESS_SCALE_MAX).coerceIn(0.0, 1.0)
                val shown = stressText(score)
                val ceiling = STRESS_SCALE_MAX.toInt()
                BevelGauge(
                    fraction = fraction,
                    stops = Palette.stressGradientStops,
                    tipColor = Palette.sample(Palette.stressGradientStops, fraction.toFloat()),
                    numberText = shown,
                    captionText = "of $ceiling",
                    // A half-gauge from 9 o'clock over the top to 3 o'clock.
                    startDeg = 180f,
                    spanDeg = 180f,
                    modifier = Modifier.semantics {
                        contentDescription = "Stress $shown of $ceiling"
                    },
                )
            }
            Text(
                "Scored from how today's resting heart rate and HRV sit against your own recent " +
                    "baseline. A wellness estimate, not a medical reading.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// MARK: - The day

/** The hours whoop-rs scored across the day, with the first, middle and last of them on the axis. */
@Composable
private fun StressDayCard(read: DaytimeStress.Result?) {
    NoopCard(tint = Palette.stressColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            val scored = read?.scored.orEmpty()
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Overline("Through the day", modifier = Modifier.weight(1f))
                val peak = read?.peak
                val peakLevel = peak?.level
                if (peak != null && peakLevel != null) {
                    Text(
                        "peak ${stressText(peakLevel)} · ${stressHourLabel(peak.hour)}",
                        style = NoopType.captionNumber,
                        color = Palette.stressBright,
                    )
                }
            }
            when {
                read == null -> InsetChartPlaceholder(
                    message = "Reading this day's heart rate…",
                    height = Metrics.compactChartHeight,
                )
                scored.size < 2 -> InsetChartPlaceholder(
                    message = "Not enough scored hours to draw this day.",
                    height = Metrics.compactChartHeight,
                )
                else -> {
                    LineChart(
                        values = scored.mapNotNull { it.level },
                        modifier = Modifier.height(Metrics.compactChartHeight),
                        color = Palette.stressColor,
                        fill = true,
                        selectionEnabled = true,
                        formatValue = { stressText(it) },
                    )
                    HourRuler(scored.map { it.hour })
                }
            }
            Text(
                "Each point is one waking hour, scored against the day's own calm hours. Hours " +
                    "without enough data are left out rather than filled in.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/** First, middle and last scored hour under the day chart. */
@Composable
private fun HourRuler(hours: List<Int>) {
    if (hours.size < 2) return
    Row(modifier = Modifier.fillMaxWidth()) {
        listOf(hours.first(), hours[hours.lastIndex / 2], hours.last()).forEachIndexed { slot, hour ->
            Text(
                stressHourLabel(hour),
                style = NoopType.footnote,
                color = Palette.textTertiary,
                textAlign = when (slot) {
                    0 -> TextAlign.Start
                    1 -> TextAlign.Center
                    else -> TextAlign.End
                },
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// MARK: - Time in band

/** How whoop-rs split the day's scored hours across its low, medium and high bands. */
@Composable
private fun TimeInBandCard(read: DaytimeStress.Result?) {
    val low = read?.lowMinutes ?: 0L
    val medium = read?.mediumMinutes ?: 0L
    val high = read?.highMinutes ?: 0L
    if (low + medium + high <= 0L) return

    NoopCard(tint = Palette.stressColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
            Overline("Time in band")
            SegmentBar(
                segments = listOf(
                    Palette.stressDeep to low.toFloat(),
                    Palette.stressColor to medium.toFloat(),
                    Palette.stressBright to high.toFloat(),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
            ) {
                BandTotal("Low", low, Palette.stressDeep, Modifier.weight(1f))
                BandTotal("Medium", medium, Palette.stressColor, Modifier.weight(1f))
                BandTotal("High", high, Palette.stressBright, Modifier.weight(1f))
            }
            Text(
                "Minutes come from whoop-rs's own banding of each scored hour.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/** One band's total: the duration above its swatch and word. */
@Composable
private fun BandTotal(label: String, minutes: Long, color: Color, modifier: Modifier = Modifier) {
    val spoken = "$label ${durationText(minutes.toDouble())}"
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        Text(
            durationText(minutes.toDouble()),
            style = NoopType.number(18f),
            color = if (minutes > 0L) Palette.textPrimary else Palette.textTertiary,
            maxLines = 1,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
        ) {
            Box(
                modifier = Modifier
                    .size(Metrics.legendSwatch)
                    .clip(CircleShape)
                    .background(if (minutes > 0L) color else Palette.surfaceInset),
            )
            Overline(label, color = if (minutes > 0L) color else Palette.textTertiary)
        }
    }
}

// MARK: - Sessions

/** The breathing session a high-stress day can be answered with. */
@Composable
private fun SessionsSection(onBreathe: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader("Sessions", overline = "Guided")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
        ) {
            GalleryCard(
                title = "Increase relaxation",
                subtitle = "Guided breathing",
                icon = Icons.Filled.Air,
                tint = Palette.restColor,
                onClick = onBreathe,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// The Charge hero card's liquid translucent near-black fill and radius.
private val LIQUID_HERO_FILL: Color = Color(red = 13f / 255f, green = 14f / 255f, blue = 20f / 255f, alpha = 0.80f)
private val LIQUID_HERO_RADIUS: Dp = 26.dp

// MARK: - Range control model (ported from TrendsView.Range)

/** W(7) / M(30) / 3M(90) / 6M(180) / 1Y(365) / ALL. */
internal enum class TrendsRange(val days: Int?, val label: String, val longName: String) {
    Week(7, "W", "week"),
    Month(30, "M", "month"),
    Quarter(90, "3M", "3 months"),
    Half(180, "6M", "6 months"),
    Year(365, "1Y", "year"),
    All(null, "ALL", "all history");

    /** "Trailing 90 days" / "All history" , the card/range subtitle. */
    val subtitle: String get() = days?.let { "Trailing $it days" } ?: "All history"

    /** This range plus every LARGER range, ascending , the auto-expand search order. */
    val widening: List<TrendsRange>
        get() = entries.dropWhile { it != this }
}

// MARK: - Resolved metric (mirrors TrendsView.ResolvedMetric / resolve)

/** A metric's window: its plotted values + the day-string of each point, the range it
 *  resolved to, whether the selection was widened to find data, and the caption to show. */
internal data class ResolvedMetric(
    val values: List<Double>,
    val dates: List<String>,
    val effective: TrendsRange,
    val widened: Boolean,
    val caption: String,
)

/**
 * Walk the widening order once: take the smallest range ≥ selected whose window holds
 * ≥1 non-null point for [value]; if none do, fall back to ALL. Windows are taken
 * relative to the LATEST recorded day, exactly like the macOS `days(for:)`.
 */
internal fun resolveMetric(
    days: List<DailyMetric>,
    selected: TrendsRange,
    value: (DailyMetric) -> Double?,
): ResolvedMetric {
    for (r in selected.widening) {
        val pts = windowPoints(days, r, value)
        if (pts.isNotEmpty()) {
            return ResolvedMetric(
                values = pts.map { it.second },
                dates = pts.map { it.first },
                effective = r,
                widened = r != selected,
                caption = caption(pts.size, r, selected),
            )
        }
    }
    val pts = windowPoints(days, TrendsRange.All, value)
    return ResolvedMetric(
        values = pts.map { it.second },
        dates = pts.map { it.first },
        effective = TrendsRange.All,
        widened = TrendsRange.All != selected,
        caption = caption(pts.size, TrendsRange.All, selected),
    )
}

/**
 * Non-null metric points (day, value) within [range]'s trailing window, taken relative to
 * the latest recorded day (oldest → newest). `days` is the full oldest-first history. A null
 * `range.days` (ALL) returns every non-null point. The day string is carried alongside each
 * value so the chart can draw a real date X-axis.
 */
private fun windowPoints(
    days: List<DailyMetric>,
    range: TrendsRange,
    value: (DailyMetric) -> Double?,
): List<Pair<String, Double>> {
    if (days.isEmpty()) return emptyList()
    val sliced = when (val n = range.days) {
        null -> days
        // Trailing N CALENDAR days ending today , anchored to the phone's date, NOT the last N rows
        // (which on a stale import made months-old data fill the W/M/3M windows, looking current , #23).
        // ISO yyyy-MM-dd sorts chronologically. Empty short windows auto-widen via resolveMetric, so old
        // imports surface under a wider range / All history rather than masquerading as recent.
        else -> {
            val cutoff = LocalDate.now().minusDays((n - 1).toLong()).toString()
            days.filter { it.day >= cutoff }
        }
    }
    return sliced.mapNotNull { d -> value(d)?.let { d.day to it } }
}

/** Caption text, mirroring TrendsView.caption(count:eff:). */
private fun caption(count: Int, eff: TrendsRange, selected: TrendsRange): String {
    val unit = if (count == 1) "reading" else "readings"
    return if (eff != selected) {
        "$count $unit · sparse , widened to ${eff.longName}"
    } else {
        "$count $unit · ${selected.longName}"
    }
}

// MARK: - ChartCard , the uniform fixed-height trend card
//
// A NoopCard holding a header (overline-styled title + caption + trailing read-out), a
// fixed-height LineChart, and a divided footer of labelled stats. Mirrors the macOS
// ChartCard used across Trends so every card is Metrics.chartHeight-class and identical.

@Composable
internal fun ChartCard(
    title: String,
    subtitle: String?,
    trailing: String?,
    color: Color,
    values: List<Double>,
    footer: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    dates: List<String> = emptyList(),
    formatY: (Double) -> String = { "${it.roundToInt()}" },
    // Bevel: a domain card wash, a bright end-cap "now" colour, and an optional window-change TrendChip.
    tint: Color? = null,
    tipColor: Color = color,
    change: Double? = null,
    higherIsBetter: Boolean? = null,
    changeFmt: (Double) -> String = { "${it.roundToInt()}" },
    // Fraction of the plot height left empty above the peak , the Android stand-in for the iOS
    // hero's `valueRange: 0...106` padded ceiling, so the peak + now-cap halo clear the top
    // gridline. 0 keeps the curve filling the full height (the small multiples). (#458/parity)
    chartHeadroom: Float = 0f,
    // LIQUID: the hero card only. When true the card carries the liquid translucent-black frosted wrapper
    // (rgba(13,14,20,.80), radius 26, white@0.11 hairline) instead of the classic NoopCard surface, and the
    // trailing readout becomes a small count-up Charge vessel filled to [headlineValue] (0..100). Every
    // small-multiple card leaves this false → identical classic NoopCard + plain text readout as before.
    liquidHero: Boolean = false,
    headlineValue: Double? = null,
) {
    // The card body — one composable reused by both the classic and the liquid-hero container so the
    // header / chart / footer layout is byte-identical between them; only the surface + the header readout
    // treatment differ.
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header.
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(title)
                    if (subtitle != null) {
                        Text(subtitle, style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
                if (liquidHero && headlineValue != null) {
                    // The one liquid accent on this screen: a small Charge vessel filled to the window
                    // average, the value counting up over it (white, tabular, soft shadow, hit-transparent).
                    // Same value + charge tint as the plain readout it replaces — the chart stays crisp.
                    HeadlineVessel(value = headlineValue, tint = Palette.recoveryColor(headlineValue))
                } else if (trailing != null) {
                    // Neutral 15pt readout (matches iOS TrendsView) , not the 22sp tinted figure.
                    Text(trailing, style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
            }

            // Chart (fixed height) or sparse placeholder. The chart is flanked by a max/avg/min
            // Y-axis column on the left and a first/mid/last date X-axis row underneath, so the
            // line reads against real numbers and dates instead of a bare unlabelled curve.
            if (values.size >= 2) {
                ChartWithAxes(
                    values = values,
                    dates = dates,
                    color = color,
                    tipColor = tipColor,
                    formatY = formatY,
                    headroom = chartHeadroom,
                )
            } else {
                SparsePlaceholder()
            }

            // Footer stats + a window-change chip aligned to the trailing edge.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { ChartFooter(footer) }
                ChangeChip(change, higherIsBetter, changeFmt)
            }
        }
    }

    if (liquidHero) {
        // The liquid hero surface: a translucent near-black that floats over the day-of-sky so the crisp
        // chart + the vessel accent read clean — the card does the contrast work, not a muted sky. Radius 26
        // + a faint white hairline give the frosted-glass edge of the iOS liquid heroCard. Mirrors Today.
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(LIQUID_HERO_RADIUS))
                .background(LIQUID_HERO_FILL.copy(alpha = LIQUID_HERO_FILL.alpha * CardAppearance.opacity))
                .border(1.dp, Color.White.copy(alpha = 0.11f * CardAppearance.opacity), RoundedCornerShape(LIQUID_HERO_RADIUS))
                .padding(Metrics.cardPadding),
        ) {
            body()
        }
    } else {
        NoopCard(modifier = modifier, padding = Metrics.cardPadding, tint = tint) { body() }
    }
}

/**
 * The screen's single liquid accent: a small [LiquidVessel] filled to [value] (0..100 → 0..1) in the
 * charge [tint], the number rolling up over it via [CountUpText] (white, tabular, a soft shadow so it reads
 * on the vessel, hit-transparent so a tap falls through to the vessel's own splash). The Trends echo of the
 * liquid Today `HeroScoreVessel`, sized down to a header readout so it accents the headline value without
 * competing with the crisp chart below.
 */
@Composable
private fun HeadlineVessel(value: Double, tint: Color) {
    val diameter = 44.dp
    Box(modifier = Modifier.size(diameter), contentAlignment = Alignment.Center) {
        LiquidVessel(
            value = (value / 100.0).coerceIn(0.0, 1.0),
            tint = tint,
            animated = true,
            modifier = Modifier.size(diameter),
        )
        CountUpText(
            value = value,
            format = { "${it.roundToInt()}" },
            style = NoopType.number(17f, weight = FontWeight.Bold)
                .copy(shadow = Shadow(color = Color.Black.copy(alpha = 0.5f), offset = Offset(0f, 1f), blurRadius = 6f)),
            color = Color.White,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** A TrendChip for a window's period change , green/rose by whether the move is good for THIS metric. */
@Composable
private fun ChangeChip(change: Double?, higherIsBetter: Boolean?, fmt: (Double) -> String) {
    if (change == null || kotlin.math.abs(change) <= 0.0001) return
    val sign = if (change >= 0) "+" else "−"
    val color = when (higherIsBetter) {
        null -> Palette.textTertiary
        else -> if ((change > 0) == higherIsBetter) Palette.statusPositive else Palette.metricRose
    }
    TrendChip(text = "$sign${fmt(kotlin.math.abs(change))}", color = color)
}

/**
 * A [LineChart] with a max/avg/min Y-axis label column and a first/mid/last date X-axis row.
 * Shared by the hero + small-multiple trend cards so every chart gets the same axis treatment.
 * Date strings (ISO yyyy-MM-dd) are reformatted to "d MMM"; an unparseable string falls back to
 * its raw value so a non-ISO key never blanks a label.
 */
@Composable
private fun ChartWithAxes(
    values: List<Double>,
    dates: List<String>,
    color: Color,
    formatY: (Double) -> String,
    tipColor: Color = color,
    // See ChartCard.chartHeadroom , fraction of the plot left empty above the peak.
    headroom: Float = 0f,
) {
    val maxV = values.max()
    val avgV = values.average()
    val minV = values.min()
    // Trend chart style (line vs bar). Read here at the single chart choke point (every trend card routes
    // through ChartWithAxes); SharedPreferences isn't reactive, but returning from Settings recomposes the
    // Trends screen, which re-reads it — the same read-on-recompose the Effort scale toggle relies on.
    val chartStyle = UnitPrefs.trendChartStyle(LocalContext.current)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(
                modifier = Modifier.height(Metrics.chartHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatY(maxV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                Text(formatY(avgV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                Text(formatY(minV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            }
            // The shared LineChart with a glowing "now" end-cap drawn on top , the Bevel idiom from
            // Today's OverviewHRChart. The cap reproduces LineChart's own point geometry (same
            // strokePx/topPad/bottomPad) so the dot lands exactly on the line's final sample.
            //
            // headroom leaves the top fraction of the card empty and pins the plotting Box to the
            // bottom , the Android stand-in for the iOS hero's `valueRange: 0...106` (LineChart has
            // no value-domain hook, so we shrink its drawing box instead). Both LineChart and the
            // GlowEndCap fill this same Box, so the cap stays on the line.
            val plotHeight = Metrics.chartHeight * (1f - headroom.coerceIn(0f, 0.5f))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Metrics.chartHeight),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(plotHeight)) {
                    if (chartStyle == TrendChartStyle.BAR) {
                        // Bar mode: value-ramp bars from the baseline. No GlowEndCap (the "now" halo is a
                        // line idiom). selectionEnabled is OFF so BarChart mean-bins a dense window (the
                        // multi-year "ALL" span) down to the pixel width — a clean silhouette instead of a
                        // 1000-bar sub-pixel smear. The max/avg/min axis column + footer carry the numbers.
                        BarChart(
                            values = values,
                            modifier = Modifier.fillMaxSize(),
                            color = color,
                            selectionEnabled = false,
                        )
                    } else {
                        LineChart(
                            values = values,
                            modifier = Modifier.fillMaxSize(),
                            color = color,
                            fill = true,
                            selectionEnabled = true,
                            // #463: the pinpoint label goes through the SAME formatter as the axis column,
                            // so a tapped Effort day can't print the stored 0-100 value beside a 0-21 axis.
                            formatValue = formatY,
                        )
                        GlowEndCap(values = values, tipColor = tipColor)
                    }
                }
            }
        }
        if (dates.size >= 2) {
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf(dates.first(), dates.getOrNull(dates.lastIndex / 2), dates.last()).forEach { d ->
                    Text(
                        prettyAxisDate(d),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** ISO "yyyy-MM-dd" → "d MMM"; falls back to the raw string (or "" when null) if it doesn't parse. */
private fun prettyAxisDate(day: String?): String =
    day?.let {
        runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) }
            .getOrDefault(it)
    }.orEmpty()

/** A labelled metric-trend card built from a [ResolvedMetric] with mean / min / max. */
@Composable
internal fun MetricTrendCard(
    title: String,
    unit: String,
    color: Color,
    resolved: ResolvedMetric,
    fmt: (Double) -> String,
    tint: Color? = null,
    tipColor: Color = color,
    higherIsBetter: Boolean? = null,
) {
    val avg = resolved.values.averageOrNull()
    ChartCard(
        title = title,
        subtitle = null,
        trailing = avg?.let { fmt(it) },
        color = color,
        tint = tint,
        tipColor = tipColor,
        values = resolved.values,
        dates = resolved.dates,
        formatY = fmt,
        change = periodChange(resolved.values),
        higherIsBetter = higherIsBetter,
        changeFmt = fmt,
        footer = listOf(
            // Plain "Mean" to match the bare Min/Max columns; the unit moves into the value
            // (e.g. "58 ms") so uppercasing can't render a shouty "MEAN MS".
            stringResource(R.string.trends_mean) to (avg?.let { "${fmt(it)} $unit" } ?: EM_DASH),
            stringResource(R.string.trends_min) to (resolved.values.minOrNull()?.let { fmt(it) } ?: EM_DASH),
            stringResource(R.string.trends_max) to (resolved.values.maxOrNull()?.let { fmt(it) } ?: EM_DASH),
        ),
    )
}

/**
 * The window's trend as a signed mean-of-recent-half minus mean-of-earlier-half , drives the card's
 * TrendChip so a glance reads the direction, like Today's deltas. null for a window too short to split.
 */
internal fun periodChange(values: List<Double>): Double? {
    if (values.size < 4) return null
    val mid = values.size / 2
    val earlier = values.take(mid)
    val recent = values.drop(mid)
    if (earlier.isEmpty() || recent.isEmpty()) return null
    return recent.average() - earlier.average()
}

/** Evenly-spaced labelled stats under a chart, separated by a hairline rule. */
@Composable
private fun ChartFooter(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
        HorizontalDivider(color = Palette.hairline)
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEach { (label, value) ->
                Column(modifier = Modifier.weight(1f)) {
                    Overline(label, color = Palette.textTertiary)
                    Text(value, style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
            }
        }
    }
}

// MARK: - Recovery history strip (stands in for the macOS YearHeatStrip)

/**
 * The recovery history card. macOS shows a YearHeatStrip (a 53-week calendar heat grid);
 * that bespoke component has no Android foundation equivalent, so we plot the real
 * per-day recovery series as a bar strip over the same window and note the difference.
 * Always shows at least a full year of context, like the macOS strip.
 */
@Composable
internal fun RecoveryHistoryCard(days: List<DailyMetric>, range: TrendsRange) {
    // PERF (#scroll-jank): memoise the window slice + recovery extraction on (days, range) so the
    // 800+-day takeLast + mapNotNull don't re-run on every recomposition (e.g. the staggered-appear
    // animation frames that drive this whole strip). Same span rule, same values, same order , purely
    // skips redundant re-slicing. NOTE: the bars are NOT caller-downsampled , BarChart already mean-
    // bucket-downsamples internally to ~one bar per horizontal pixel (pixel-identical), so a second,
    // coarser caller-side bucket (e.g. ≤180) would visibly widen the bars and is deliberately avoided.
    val recovery = remember(days, range) {
        // Always show at least a year; expand to all history on ALL.
        val span = (range.days ?: days.size).coerceAtLeast(365)
        days.takeLast(span).mapNotNull { it.recovery }
    }
    val title = if (range == TrendsRange.All && days.size > 365) {
        "Charge , all history"
    } else {
        "Charge , past year"
    }

    NoopCard(tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(title, overline = stringResource(R.string.trends_calendar), trailing = "${recovery.size} days")
            if (recovery.size >= 2) {
                BarChart(
                    values = recovery,
                    modifier = Modifier.height(Metrics.trendStripHeight),
                    color = Palette.accent,
                )
            } else {
                SparsePlaceholder(height = Metrics.trendStripHeight)
            }
            HorizontalDivider(color = Palette.hairline)
            Text(
                stringResource(R.string.trends_calendar_footnote),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - Shared bits


/** Inset well shown when a window has too few points to plot, mirroring sparsePlaceholder. */
@Composable
private fun SparsePlaceholder(height: Dp = Metrics.chartHeight) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .background(Palette.surfaceInset),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.trends_not_enough_data),
            style = NoopType.subhead,
            color = Palette.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun EmptyTrends() {
    DataPendingNote(
        title = stringResource(R.string.trends_empty_title),
        body = stringResource(R.string.trends_empty_body),
    )
}

// MARK: - Small numeric helpers

internal const val EM_DASH = ","

internal fun List<Double>.averageOrNull(): Double? =
    if (isEmpty()) null else sum() / size

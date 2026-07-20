package com.noop.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.WeeklyDigestEngine
import com.noop.data.DailyMetric
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Trends
//
// The longitudinal view, ported from Strand/Screens/TrendsView.swift onto the locked
// Android component system so every surface, height and gap matches: one
// SegmentedPillControl for the range (W / M / 3M / 6M / 1Y / ALL), a hero Recovery
// ChartCard, and a uniform set of HRV / Resting HR / Day-strain ChartCards (all
// Metrics.chartHeight tall), followed by a recovery history strip.
//
// Windows are taken relative to the phone's actual local day, with the macOS auto-expand
// rule: if the selected window holds zero points for a metric, the smallest larger range
// that does is used and the card caption notes the widening.
//
// Data: full history is loaded once via repo.days("my-whoop"); until it arrives the
// reactive recentDays flow backs the charts, so the screen is never empty when data exists.
//
// Difference from macOS: the macOS Trends footer carries a YearHeatStrip calendar
// (a bespoke 53-week heat grid) that has no Android foundation equivalent. Rather than
// fake it, the "Recovery history" card renders the real per-day recovery series as a
// bar strip over the same window, with a short note pointing at the macOS calendar view.

@Composable
fun TrendsScreen(vm: AppViewModel) {
    // Reactive cache (oldest → newest) as the immediate backing.
    val reactiveDays by vm.recentDays.collectAsStateWithLifecycle()

    // Full history loaded once for the long (1Y / ALL) ranges; falls back to the flow
    // until it lands so the screen is populated on first frame when any data exists.
    var fullHistory by remember { mutableStateOf<List<DailyMetric>?>(null) }
    LaunchedEffect(Unit) {
        // Merged: imported WHOOP days win; on-device computed days gap-fill the trends. Reads the registry's
        // ACTIVE strap id so daysMerged resolves the active-id ∪ canonical "my-whoop" union (SPINE / #814) ,
        // a re-added strap's data and the canonical import both surface; a single-WHOOP install is unchanged.
        fullHistory = vm.repo.daysMerged(vm.activeStrapId)
    }
    val days = fullHistory ?: reactiveDays

    // Effort display scale (#268) , routes the Effort small-multiple's numbers + unit. Display-only.
    val effortScale = UnitPrefs.effortScale(LocalContext.current)

    // Day-cycle sky backdrop (#698). Default ON. When off, Trends drops the liquid sky and the scaffold
    // paints the plain dark surface canvas instead. SharedPreferences isn't reactive, so this is read once
    // into local state (mirrors Today's showDayCycleBackground gate).
    val trendsCtx = LocalContext.current
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(trendsCtx) }

    var range by remember { mutableStateOf(TrendsRange.Quarter) }

    // #710 , browse previous weeks in the Week-in-review digest. 0 = the week containing today; each step
    // back is one Mon–Sun week earlier, clamped so it never runs past the earliest day we hold. The Trends
    // RANGE control above scopes the long charts; this only moves the weekly digest at the top.
    var weekOffset by remember { mutableStateOf(0) }
    // Re-clamp the offset whenever the loaded history changes (e.g. an import lands more weeks), so a
    // stored offset can never point past the new earliest week. Mirrors the iOS minWeekOffset clamp.
    val minWeekOffset = remember(days) { minWeekOffset(days) }
    LaunchedEffect(minWeekOffset) { weekOffset = weekOffset.coerceIn(minWeekOffset, 0) }

    // Resolve each metric's window ONCE per composition and reuse below , mirrors the macOS resolve(_:)
    // so caption / widened / points aren't recomputed per use. HOISTED above the lazy scaffold: these
    // are @Composable `remember` hooks, which can't run inside the LazyListScope content lambda. They're
    // cheap memoized resolves (no-ops over an empty `days`), so the empty branch below simply ignores
    // them , same as Intelligence's hoisted range/filter. Mirrors the eager body's per-composition resolve.
    val recovery = remember(days, range) { resolveMetric(days, range) { it.recovery } }
    val hrv = remember(days, range) { resolveMetric(days, range) { it.avgHrv } }
    val rhr = remember(days, range) { resolveMetric(days, range) { it.restingHr?.toDouble() } }
    val strain = remember(days, range) { resolveMetric(days, range) { it.strain } }
    // Rest = the sleep_performance COMPOSITE (0–100) , the SAME metric the Today Rest score/tile and the
    // Sleep Rest-detail plot (#614 follow-up), NOT raw efficiency, which is a different number under the
    // same "Rest" label and made the Trends Rest graph disagree with the Today Rest score (#732).
    // sleep_performance is a metricSeries (imported-wins resolved), not a DailyMetric column, so fetch the
    // resolved series and key it by day for the existing windowing/widening below. Mirrors the source
    // TodayScreen's restScore reads, so the two screens now plot the same number.
    var sleepPerfByDay by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(days) {
        sleepPerfByDay = runCatching {
            vm.repo.resolvedSeries("sleep_performance", "my-whoop", "0000-00-00", "9999-99-99",
                strapDeviceId = vm.activeStrapId)
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())
    }
    val rest = remember(days, range, sleepPerfByDay) {
        resolveMetric(days, range) { d -> sleepPerfByDay[d.day] }
    }
    val recAvg = recovery.values.averageOrNull()

    LazyScreenScaffold(
        title = stringResource(R.string.nav_trends),
        subtitle = stringResource(R.string.trends_subtitle),
        // LIQUID SKY BACKDROP (the pilot pattern — LiquidScreenSky.kt): the time-of-day liquid sky settles
        // into the theme canvas behind the header + top rows, full-bleed via the scaffold's topBackground
        // plumbing. Static (LiquidSkyStatic, inside the helper) — never an animated sky behind a scrolling
        // list. Gated on the same day-cycle pref as Today; when off, the scaffold paints the flat canvas.
        topBackground = if (showDayCycleBackground) { { LiquidScreenSky() } } else null,
    ) {
        if (days.isEmpty()) {
            item { EmptyTrends() }
            return@LazyScreenScaffold
        }

        // The main card list ripples in once on appear (Reduce-Motion safe), mirroring the iOS
        // staggeredAppear sequence , each top-level section is one staggered child.

        // --- Week-in-review digest (#208) with prev/next week browsing (#710). Past weeks render in the
        // same format; the chevrons stay visible on an empty PAST week so the user can step on. ---
        item {
            Column(modifier = Modifier.staggeredAppear(index = 0)) {
                WeeklyDigestNav(
                    days = days,
                    weekOffset = weekOffset,
                    minWeekOffset = minWeekOffset,
                    onStep = { delta -> weekOffset = (weekOffset + delta).coerceIn(minWeekOffset, 0) },
                )
            }
        }

        // --- Week in review , the Charge / Effort / Rest trio in NOOP's pip language (PipBar +
        // CountUpText), mirroring the iOS TrendsView.weekInReview card. White count-up numbers over
        // segmented count-up bars; self-hides when none of the three carry a window mean. ---
        item {
            WeekInReviewCard(
                charge = recovery,
                effort = strain,
                rest = rest,
                effortScale = effortScale,
                modifier = Modifier.staggeredAppear(index = 1),
            )
        }

        // --- Range control ---
        item {
            Column(
                modifier = Modifier.staggeredAppear(index = 2),
                verticalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SegmentedPillControl(
                        items = TrendsRange.entries.toList(),
                        selection = range,
                        label = { it.label },
                        onSelect = { range = it },
                    )
                    Spacer(Modifier.weight(1f))
                    Overline(range.subtitle, color = Palette.textTertiary)
                }
                Text(
                    recovery.caption,
                    style = NoopType.footnote,
                    color = if (recovery.widened) Palette.statusWarning else Palette.textTertiary,
                )
            }
        }

        // --- Hero , charge over time. Charge (green) world: domain card wash, a crisp flat line with a
        // bright "now" end-cap, and a TrendChip for the window's move. ---
        item {
            ChartCard(
                modifier = Modifier.staggeredAppear(index = 3),
                title = stringResource(R.string.trends_charge),
                // The range bar above already prints the authoritative reading-count caption;
                // the hero only names its window so the count isn't doubled in one card height.
                subtitle = range.subtitle,
                trailing = recAvg?.let { "${it.roundToInt()}" },
                // LIQUID hero: the translucent-black frosted wrapper + a small count-up Charge vessel accent
                // in the header (the screen's one headline single value — the window-average Charge). The
                // line chart below stays crisp. Small multiples pass liquidHero = false → untouched.
                liquidHero = true,
                headlineValue = recAvg,
                color = Palette.chargeColor,
                tipColor = Palette.chargeBright,
                tint = Palette.chargeColor,
                values = recovery.values,
                dates = recovery.dates,
                formatY = { "${it.roundToInt()}" },
                change = periodChange(recovery.values),
                higherIsBetter = true,
                changeFmt = { "${it.roundToInt()}" },
                // Lift the ceiling ~6% so a near-100 peak and the now-cap halo clear the top gridline ,
                // mirrors the iOS hero's `valueRange: 0...106`.
                chartHeadroom = 0.06f,
                footer = listOf(
                    stringResource(R.string.trends_avg) to (recAvg?.let { "${it.roundToInt()}" } ?: EM_DASH),
                    stringResource(R.string.trends_peak) to (recovery.values.maxOrNull()?.let { "${it.roundToInt()}" } ?: EM_DASH),
                    stringResource(R.string.trends_low) to (recovery.values.minOrNull()?.let { "${it.roundToInt()}" } ?: EM_DASH),
                    stringResource(R.string.trends_days) to "${recovery.values.size}",
                ),
            )
        }

        // --- Small multiples , HRV / Resting HR / Effort. HRV/RHR are Charge sub-signals → the green
        // card world (each line keeps its metric hue); Effort is the WHOOP blue strain world. ---
        // No trailing window label , the range bar's overline already states it.
        item {
            Column(
                modifier = Modifier.staggeredAppear(index = 4),
                verticalArrangement = Arrangement.spacedBy(Metrics.gap),
            ) {
                SectionHeader(stringResource(R.string.trends_daily_signals), overline = stringResource(R.string.nav_trends))
                MetricTrendCard(
                    title = stringResource(R.string.trends_hrv_full), unit = "ms",
                    color = Palette.metricPurple,
                    tint = Palette.chargeColor,
                    higherIsBetter = true,
                    resolved = hrv,
                    fmt = { "${it.roundToInt()}" },
                )
                MetricTrendCard(
                    title = stringResource(R.string.trends_resting_hr_full), unit = "bpm",
                    color = Palette.metricRose,
                    tint = Palette.chargeColor,
                    higherIsBetter = false,
                    resolved = rhr,
                    fmt = { "${it.roundToInt()}" },
                )
                MetricTrendCard(
                    // Plotted values stay on the stored 0–100 scale (line shape unchanged); only the displayed
                    // numbers + unit follow the Effort-scale toggle, converted inside `fmt`. (#268)
                    title = stringResource(R.string.trends_effort), unit = "/ ${UnitFormatter.effortScaleMax(effortScale)}",
                    // WHOOP: Effort/Strain is always BLUE , a deep→bright blue line, not the amber ramp.
                    color = Palette.effortColor,
                    tint = Palette.effortColor,
                    tipColor = Palette.effortBright,
                    higherIsBetter = null,
                    resolved = strain,
                    fmt = { UnitFormatter.effortDisplay(it, effortScale) },
                )
            }
        }

        // --- Recovery history strip (stands in for the macOS YearHeatStrip) ---
        item {
            Column(modifier = Modifier.staggeredAppear(index = 5)) {
                RecoveryHistoryCard(days = days, range = range)
            }
        }

        // --- Export trends report (#436) , the shareable offline PDF exporter. Mirrors the iOS
        // TrendsView.exportReportRow footer; the same composable Settings hosts, so both surfaces
        // offer it. Routed through NoopButton like every other CTA (no gold). ---
        item {
            Column(modifier = Modifier.staggeredAppear(index = 6)) {
                TrendsReportExportSection(vm)
            }
        }
    }
}

// MARK: - Week-in-review digest with prev/next week browsing (#710)

/**
 * The most-negative weekOffset allowed: the number of whole Mon–Sun weeks between the earliest day we
 * hold and this week. Beyond it there's no data to digest, so the back chevron disables. 0 when history
 * is empty or unparseable (so we stay on this week). `days` is oldest → newest. Mirrors iOS minWeekOffset.
 */
private fun minWeekOffset(days: List<DailyMetric>): Int {
    val earliest = days.firstOrNull()?.day ?: return 0
    val earliestMon = WeeklyDigestEngine.mondayOfWeek(earliest) ?: return 0
    val thisMon = WeeklyDigestEngine.mondayOfWeek(logicalDayKeyNow()) ?: return 0
    var off = 0
    var mon = thisMon
    // Walk weeks back until we pass the earliest week. Hard cap ~10 years so a bad date can't spin.
    while (mon > earliestMon && off > -520) {
        mon = WeeklyDigestEngine.addDays(mon, -7)
        off -= 1
    }
    return off
}

/**
 * The Week-in-review digest for the selected week, with prev/next chevrons in its header. The digest for
 * the offset week is built straight from the shared [buildWeeklyDigest] (the same builder
 * WeeklyDigestCard uses) so past weeks render in the identical format. The whole block self-hides only
 * when the WHOLE history is empty; an empty PAST week still shows the chevrons so the user can step on.
 * Mirrors iOS TrendsView.weeklyDigestNav.
 */
@Composable
private fun WeeklyDigestNav(
    days: List<DailyMetric>,
    weekOffset: Int,
    minWeekOffset: Int,
    onStep: (Int) -> Unit,
) {
    if (days.isEmpty()) return
    // Anchor day for this offset = today shifted back by weekOffset whole weeks; the engine snaps it to
    // that week's Monday. Memoised so the (cheap but non-trivial) digest rebuild only runs on a real change.
    val anchorDay = remember(weekOffset) {
        WeeklyDigestEngine.addDays(logicalDayKeyNow(), weekOffset * 7)
    }
    // #268/#463: past weeks quote Effort on the user's display scale too, same as the live card.
    val factor = effortDisplayFactor(UnitPrefs.effortScale(LocalContext.current))
    val digest = remember(days, anchorDay, factor) {
        buildWeeklyDigest(days, anchorDay, effortDisplayFactor = factor)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WeekNavBar(weekOffset = weekOffset, minWeekOffset = minWeekOffset, onStep = onStep)
        if (digest.isEmpty) {
            DataPendingNote(
                title = stringResource(R.string.trends_no_readings_this_week),
                body = stringResource(R.string.trends_no_readings_body),
            )
        } else {
            NoopCard { WeeklyDigestContent(digest = digest, compact = true) }
        }
    }
}

/**
 * Prev/next week stepper. Back is clamped at the earliest week we hold; forward at this week (no future
 * weeks). Flat accent chevrons, mirroring the iOS FullDayChart day stepper (#597).
 */
@Composable
private fun WeekNavBar(weekOffset: Int, minWeekOffset: Int, onStep: (Int) -> Unit) {
    val atOldest = weekOffset <= minWeekOffset
    val atNewest = weekOffset >= 0
    val label = when {
        weekOffset == 0 -> stringResource(R.string.trends_this_week)
        weekOffset == -1 -> stringResource(R.string.trends_last_week)
        else -> stringResource(R.string.trends_weeks_ago, -weekOffset)
    }
    // liquidPress on the two week-step chevrons (the screen's tappable controls): each settles inward on
    // press, wired to the SAME interactionSource the IconButton uses for its own ripple, matching the pilot.
    val prevInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onStep(-1) },
            enabled = !atOldest,
            interactionSource = prevInteraction,
            modifier = Modifier.liquidPress(prevInteraction),
        ) {
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = stringResource(R.string.trends_previous_week),
                tint = if (atOldest) Palette.textTertiary else Palette.accent,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = NoopType.headline, color = Palette.textPrimary)
            Overline(stringResource(R.string.trends_week_in_review), color = Palette.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { onStep(1) },
            enabled = !atNewest,
            interactionSource = nextInteraction,
            modifier = Modifier.liquidPress(nextInteraction),
        ) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.trends_next_week),
                tint = if (atNewest) Palette.textTertiary else Palette.accent,
            )
        }
    }
}

// MARK: - Week in review , the Charge / Effort / Rest trio in pip language
//
// The three daily scores as NOOP pip rows over the resolved window: Charge (recovery, 0–100),
// Effort (strain, shown on the WHOOP 0–21 / 0–100 scale per the unit toggle) and Rest (sleep
// efficiency, 0–100). Each value ticks up via CountUpText; the segmented PipBar cascades on appear.
// Self-hides when none of the three carry a window mean. Mirrors iOS TrendsView.weekInReview.

@Composable
private fun WeekInReviewCard(
    charge: ResolvedMetric,
    effort: ResolvedMetric,
    rest: ResolvedMetric,
    effortScale: EffortScale,
    modifier: Modifier = Modifier,
) {
    val chargeAvg = charge.values.averageOrNull()
    val effortAvg = effort.values.averageOrNull() // stored 0–100 internal Effort scale
    val restAvg = rest.values.averageOrNull()
    if (chargeAvg == null && effortAvg == null && restAvg == null) return

    NoopCard(modifier = modifier, tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(stringResource(R.string.trends_week_in_review), overline = stringResource(R.string.trends_charge_effort_rest))
            if (chargeAvg != null) {
                PipScoreRow(
                    label = stringResource(R.string.trends_charge), value = chargeAvg, range = 0f..100f,
                    tint = Palette.chargeColor, format = { "${it.roundToInt()}" },
                )
            }
            if (effortAvg != null) {
                // Effort is stored 0–100 but reads on the user's chosen scale: convert the displayed
                // number AND the bar position so the pip fill and the count-up value agree. On WHOOP's
                // 0–21 scale Effort reads to one decimal; on 0–100 it's a whole number.
                val display = UnitFormatter.effortValue(effortAvg, effortScale)
                val maxV = UnitFormatter.effortValue(100.0, effortScale)
                val oneDecimal = effortScale == EffortScale.WHOOP
                PipScoreRow(
                    label = stringResource(R.string.trends_effort), value = display, range = 0f..maxV.toFloat(),
                    tint = Palette.effortColor,
                    format = { if (oneDecimal) String.format(Locale.US, "%.1f", it) else "${it.roundToInt()}" },
                )
            }
            if (restAvg != null) {
                PipScoreRow(
                    label = stringResource(R.string.trends_rest), value = restAvg, range = 0f..100f,
                    tint = Palette.restColor, format = { "${it.roundToInt()}" },
                )
            }
        }
    }
}

/**
 * One pip row matching PipBarRow's layout, but with the value driven by [CountUpText] so the big
 * number ticks up. UPPERCASE label + big white count-up value over the segmented count-up bar.
 * Mirrors iOS TrendsView.pipScoreRow.
 */
@Composable
private fun PipScoreRow(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    tint: Color,
    format: (Double) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(
            text = label.uppercase(),
            style = NoopType.overline,
            color = Palette.textSecondary,
        )
        CountUpText(
            value = value,
            format = format,
            style = NoopType.number(30f, weight = FontWeight.Bold),
            color = Palette.textPrimary,
        )
        PipBar(value = value.toFloat(), range = range, tint = tint)
    }
}

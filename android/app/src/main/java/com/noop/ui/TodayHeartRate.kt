package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import com.noop.data.HrBucket
import com.noop.data.SleepSession
import com.noop.data.WorkoutRow
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Heart-rate trend (today's continuous HR off the strap's own ~1Hz history)
//
// A full-width 24h HR trend, plotted from 5-minute bucket means of the strap's hrSample history
// (offloaded even while the app was closed, so the day reads continuously). Hidden until there are at
// least two buckets, so a strap-only user with no wear today sees nothing rather than an empty chart.
// Mirrors the macOS TodayView.heartRateTrendSection. LineChart spaces points by index (no time axis),
// so the buckets, being uniform 5-min means in time order, read as an even left-to-right day curve.

/** The Today heart-rate card's visible window (the UX from PR #985, reimplemented). [TODAY] = the full
 *  loaded day since the logical midnight — the unchanged default. The rest are rolling "last N hours"
 *  ending now. VIEW-ONLY, the #829 zoom rule: a window only narrows which of the already-loaded 5-minute
 *  buckets render — it never re-queries the DB and never changes the bucket resolution. (PR #985 proposed
 *  re-reading shorter windows at finer buckets; that adds a DB round-trip per tap and a second read path
 *  for detail that already has a home — the Deep Timeline re-reads down to raw seconds as you zoom.)
 *  Because the loaded extent starts at midnight, a window clips to the day: early in the morning the wider
 *  windows coincide with Today, which reads fine — both mean "everything so far". Only offered on the
 *  CURRENT day: a past day has no "now", so it always shows the full calendar day, exactly as before. */
internal enum class HrWindow(val label: String, val hours: Int) {
    // Declaration order IS the pill order: Today (the whole loaded day) anchors the wide end, then
    // strictly most → least hours. TODAY stays ordinal 0 so the rememberSaveable default is the full day.
    TODAY("Today", 0),
    H24("24h", 24), H12("12h", 12), H6("6h", 6), H3("3h", 3), H1("1h", 1);

    /** Earliest bucket timestamp (unix seconds) this window renders, anchored at `now`. TODAY = no
     *  narrowing. Anchoring at the wall clock (not the newest banked bucket) keeps the card honest: a
     *  strap that hasn't offloaded for two hours shows "no heart rate in the last 1h", never a silently
     *  re-anchored older hour — and the empty state keeps the pills, so it's not a dead end. */
    fun cutoff(now: Long): Long = if (this == TODAY) Long.MIN_VALUE else now - hours * 3600L
}

/** The pure narrowing seam (locked by HrWindowTest): does a loaded bucket survive the window's cut?
 *  The filter only ever drops OLD buckets, so the newest bucket always survives a non-empty cut and the
 *  card's trailing "latest bpm" read-out is window-invariant. */
internal fun hrWindowKeeps(bucketTs: Long, window: HrWindow, now: Long): Boolean =
    bucketTs >= window.cutoff(now)

/** The HR-window selector row, reusing the app's ONE SegmentedPillControl (house chrome, not the PR's
 *  bespoke control). Shared by the empty and populated card branches so the pills stay put whether or
 *  not the chosen window has data. */
@Composable
private fun HrWindowPills(selection: HrWindow, onSelect: (HrWindow) -> Unit) {
    SegmentedPillControl(
        items = HrWindow.entries.toList(),
        selection = selection,
        label = { it.label },
        onSelect = onSelect,
    )
}

@Composable
internal fun HeartRateTrendCard(
    viewModel: AppViewModel,
    days: List<DailyMetric>,
    selectedDay: LocalDate,
    today: LocalDate,
    displayMetric: DailyMetric? = null,
    effortScale: EffortScale = EffortScale.HUNDRED,
) {
    // "Today" here is the LOGICAL day (rolls at 04:00 local), so in the small hours after midnight the
    // trend keeps the evening's curve, window start at the logical day's own midnight, "since midnight"
    // subtitle, "Today" label, rather than blanking to an empty new-calendar-day axis (#144).
    var buckets by remember { mutableStateOf<List<HrBucket>>(emptyList()) }
    // The night's sleep session overlapping the HR window + the day's workouts, the Overview-HR
    // marker layers (sleep band, Charge at wake, sport glyphs at HR peaks). Loaded off the main
    // thread alongside the buckets; each marker self-hides when its data is absent. (PR #285)
    var sleepToday by remember { mutableStateOf<SleepSession?>(null) }
    var workoutsToday by remember { mutableStateOf<List<WorkoutRow>>(emptyList()) }
    // #985: the selected HR window. rememberSaveable ordinal so the choice survives rotation / process
    // death and feels sticky like a preference; 0 = TODAY, the unchanged full-day default. Forced to
    // TODAY on a past day (no "now" to anchor a rolling window — the pills don't render there either).
    // VIEW-ONLY (see HrWindow): it narrows the rendered buckets below; the LaunchedEffect read is untouched.
    var hrWindowOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val hrWindow = if (selectedDay == today) HrWindow.entries[hrWindowOrdinal] else HrWindow.TODAY
    // #829 Android parity - the Today HR pinch/drag zoom window (unix seconds), null = the full loaded
    // day. Mirrors iOS TodayView.hrZoomDomain: VIEW-ONLY (it narrows which of the already-loaded buckets
    // render, never re-queries the DB), keyed on the selected day so stepping days always opens at full
    // scale, while a same-day live reload keeps the window (fresh buckets only ever extend the loaded
    // extent, so an existing window stays valid). Reset by double-tap on the chart or the Reset link.
    // Also keyed on the #985 window: changing the window re-frames the chart, so a pinch-zoom made
    // inside the old frame resets with it rather than surviving as a stale sub-range.
    var hrZoom by remember(selectedDay, hrWindowOrdinal) { mutableStateOf<LongRange?>(null) }
    // #605: a WHOOP-4.0 offload banks raw HR samples straight into the hr-sample store WITHOUT touching
    // any DailyMetric row, so a sync that only adds today's HR curve never changes `days`, and keying the
    // reload on `days` alone left this chart frozen on the pre-sync window until something unrelated
    // recomposed it. Re-key on the live sync tokens too: `lastSyncAt` ticks the moment an offload reaches
    // HISTORY_COMPLETE (the banked samples are now final → reload the buckets), and `syncChunksThisSession`
    // advances through a long backfill so the curve fills in progressively rather than only at the end.
    // (No "show a past day curve" fallback, rejected behaviour change; this only re-queries the SAME
    // selected-day window when fresh samples land.) Mirrors the iOS Today HR lane keying off the sync state.
    val live by viewModel.live.collectAsStateWithLifecycle()
    // Re-load when the day list changes (an import updates it), when the day selector moves, and, via the
    // sync tokens, when a strap offload banks fresh HR samples for the current window. Also on first compose.
    LaunchedEffect(days, selectedDay, today, live.lastSyncAt, live.syncChunksThisSession) {
        val zone = ZoneId.systemDefault()
        val start = selectedDay.atStartOfDay(zone).toEpochSecond()
        val nextStart = selectedDay.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val now = System.currentTimeMillis() / 1000
        val end = if (selectedDay == today) now else (nextStart - 1)
        // #908: the Today HR curve reads the active strap ∪ canonical "my-whoop" union, NOT a hardcoded
        // "my-whoop". A strap re-added via the device manager banks live HR under its own fresh id, so a
        // pinned read showed the "no heart rate banked yet today" empty state. Single-WHOOP ⇒ one id ⇒ same.
        buckets = viewModel.repo.hrBucketsUnion(viewModel.activeStrapId, start, end, 300L)
        // The sleep that ended within the chart window (the night before / this morning), anchors
        // the band + the Charge-at-wake marker. A wide lower bound catches an onset before midnight.
        // Resolves the day's bridged MAIN-night span via `mainSleepSpan` (the SAME resolver the Sleep
        // tab hero and AnalyticsEngine's daily total use), not an ad hoc "freshest-ending block" pick --
        // that could disagree with the Sleep tab and the Coupled view's bed-wake read for a night stored
        // as more than one block (#294).
        sleepToday = runCatching {
            val overlapping = viewModel.repo.sleepSessions("my-whoop", start - 18 * 3600L, end)
                .filter { it.startTs <= end && it.endTs >= start }   // overlaps the window
            val habitualMidsleepSec = viewModel.repo.habitualMidsleepSec("my-whoop")
            mainSleepSpan(overlapping, habitualMidsleepSec)?.let { (spanStart, spanEnd) ->
                SleepSession(deviceId = "my-whoop", startTs = spanStart, endTs = spanEnd)
            }
        }.getOrNull()
        // Workouts overlapping the window, each gets a sport glyph at its in-window HR peak.
        // Union every source (not just "my-whoop"): Health-Connect-imported sessions are stored
        // under their own device id, so a strap-only query left them glyph-less here while the
        // "Last Workouts" feed below showed them (#34/#53). The glyph self-hides when no strap HR
        // overlaps, so an import with no matching strap curve simply draws nothing.
        workoutsToday = runCatching {
            viewModel.repo.workoutsAllSources(viewModel.deviceId, start - 6 * 3600L, end)
                .filter { it.startTs <= end && it.endTs >= start }
        }.getOrDefault(emptyList())
    }
    val selectedLabel = when (selectedDay) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> selectedDay.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
    }

    // #985 view-only narrowing (the #829 rule): the selected window filters the loaded 5-minute buckets,
    // anchored at the wall clock when the inputs change. A live reload refreshes `buckets`, so the anchor
    // tracks the sync cadence — plenty for a card whose buckets are 5 minutes wide.
    val winBuckets = remember(buckets, hrWindow) {
        val now = System.currentTimeMillis() / 1000
        if (hrWindow == HrWindow.TODAY) buckets else buckets.filter { hrWindowKeeps(it.bucket, hrWindow, now) }
    }

    // #863: a sparse/empty selected day used to `return` here and render NOTHING, which read as "the graph
    // froze". Show an explicit calibrating/empty card instead so the user knows the curve is still filling in
    // (a calibrating 4.0 banks HR slowly) rather than that the screen broke. We intentionally do NOT silently
    // swap in a different day's curve here (that day-swap reload behaviour was rejected in #605, see above);
    // the honest empty state is the parity-matched fix. Mirrors the iOS Today HR card's empty branch.
    // #985: the check reads the WINDOWED subset, and the pills stay visible in the empty state, so a
    // too-narrow rolling window (say 1h with no recent offload) is never a dead end — the user widens it
    // or steps back to Today, and the message says which window came up empty.
    if (winBuckets.size < 2) {
        SectionHeader("Heart Rate", overline = selectedLabel)
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Overline("Beats per minute")
                if (selectedDay == today) {
                    HrWindowPills(hrWindow) { hrWindowOrdinal = it.ordinal }
                }
                Text(
                    when {
                        selectedDay != today ->
                            "No heart rate for this day. Step back to a day the strap was worn."
                        hrWindow != HrWindow.TODAY && buckets.size >= 2 ->
                            "No heart rate in the last ${hrWindow.label}. Try a wider window or Today."
                        else ->
                            "Calibrating , no heart rate banked yet today. Your curve fills in as the strap offloads."
                    },
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
        return
    }

    // #985: everything below (read-outs, zoom bounds, chart, footer) renders the WINDOWED subset — for
    // TODAY that is the identical full-buckets list, so the default path is byte-for-byte the old one.
    val bpm = remember(winBuckets) { winBuckets.map { it.avgBpm } }
    val latest = bpm.last().roundToInt()
    val min = bpm.min().roundToInt()
    val max = bpm.max().roundToInt()
    val avg = bpm.average().roundToInt()

    // #829 - the RENDERED subset: the zoom window narrows which of the loaded buckets draw (the gesture
    // handler only commits windows keeping >= 2 buckets, and the full-buckets fallback covers a same-day
    // reload reshaping the data underneath an open window, so the curve always stays drawable). Bounds =
    // the SELECTED window's bucket extent (#985) — the same full view the un-zoomed chart renders — so a
    // pinch-zoom pans within the chosen window, not out into buckets the window has hidden.
    val zoomBounds = winBuckets.first().bucket..winBuckets.last().bucket
    val visBuckets = remember(winBuckets, hrZoom) {
        val sub = hrZoom?.let { w -> winBuckets.filter { it.bucket in w } } ?: winBuckets
        if (sub.size >= 2) sub else winBuckets
    }
    val visBpm = remember(visBuckets) { visBuckets.map { it.avgBpm } }
    // The left y-rail tracks the RENDERED window (LineChart normalises to what it draws, the Deep
    // Timeline idiom), so a zoomed curve keeps honest max/avg/min beside it; the footer Min/Avg/Max row
    // below reads the whole SELECTED window (#985) — the full day for Today, or the rolling last-N-hours
    // span — so it matches the subtitle and stays stable while you pinch around within that window.
    val visMax = visBpm.max().roundToInt()
    val visAvg = visBpm.average().roundToInt()
    val visMin = visBpm.min().roundToInt()

    // Round wall-clock ticks for the RENDERED extent, shared by the gridlines (drawn inside
    // OverviewHRChart) and the axis-label strip below so they align.
    val timeTicks = remember(visBuckets) {
        chartTimeTicks(visBuckets.first().bucket, visBuckets.last().bucket, ZoneId.systemDefault())
    }
    val visTimestamps = remember(visBuckets) { visBuckets.map { it.bucket } }

    SectionHeader("Heart Rate", overline = selectedLabel)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header, mirrors the macOS ChartCard (title + subtitle, trailing read-out).
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline("Beats per minute")
                    // #985: the buckets stay the same 5-minute means whatever the window (view-only
                    // narrowing, no re-read), so the resolution half of the label never changes — only
                    // the span half tells the truth about what's on screen.
                    val subtitle = when {
                        selectedDay != today -> "5-minute average | selected day"
                        hrWindow == HrWindow.TODAY -> "5-minute average | since midnight"
                        else -> "5-minute average | last ${hrWindow.label}"
                    }
                    Text(
                        subtitle,
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Text("$latest bpm", style = NoopType.chartValueLarge, color = Palette.metricRose)
            }
            // #985: the window selector, current day only — Today (since midnight, the default) or a
            // rolling last-N-hours cut of the same loaded buckets. A past day has no "now" → no selector.
            if (selectedDay == today) {
                HrWindowPills(hrWindow) { hrWindowOrdinal = it.ordinal }
            }
            // Chart with a max/avg/min Y-axis label column on the left and an HH:mm X-axis row below.
            // The line spaces points by index, but the X labels read each bucket's REAL timestamp in
            // local time (see below) so the axis reads true wall-clock even when the day has gaps (#544).
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(
                    modifier = Modifier.height(Metrics.chartHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("$visMax", style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                    Text("$visAvg", style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                    Text("$visMin", style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                }
                // The HR line, with the Overview marker layers (sleep band · Charge · Effort · sport
                // glyphs) overlaid on top, markers are positioned by mapping each event's wall-clock
                // time onto the line's index spacing, so they sit on the same curve. (PR #285)
                // #829 - renders the zoom window's subset, with the pinch/pan/double-tap transform
                // detector attached (keyed on the #985-windowed buckets so its captured bounds track
                // both a reload and a window change — the pinch operates INSIDE the selected window).
                // The chart and its axis-label strip share this Column so both span exactly the
                // plot width (not the card width, which includes the y-rail) — a label centred at
                // a tick fraction lands under its gridline.
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OverviewHRChart(
                        buckets = visBuckets,
                        bpm = visBpm,
                        sleep = sleepToday,
                        workouts = workoutsToday,
                        recovery = displayMetric?.recovery,
                        strain = displayMetric?.strain,
                        effortScale = effortScale,
                        timeTicks = timeTicks,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Metrics.chartHeight)
                            .pointerInput(winBuckets) {
                                hrChartTransformGestures(
                                    buckets = winBuckets,
                                    bounds = zoomBounds,
                                    window = { hrZoom },
                                    onWindow = { hrZoom = it },
                                )
                            },
                    )
                    // X-axis: labels use the SAME timestamp interpolation as the line and markers,
                    // so the axis agrees with the curve even when the day has gaps (#544). "Now"
                    // only on the un-zoomed live day — a zoomed window's right edge is wherever
                    // the user panned it (#829).
                    HrTimeAxisLabels(
                        ticks = timeTicks,
                        timestamps = visTimestamps,
                        showNow = selectedDay == today && hrZoom == null,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Metrics.divider)
                    .background(Palette.hairline),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("Min" to min, "Avg" to avg, "Max" to max).forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Overline(label, color = Palette.textTertiary)
                        Text("$value bpm", style = NoopType.bodyNumber, color = Palette.textPrimary)
                    }
                }
            }
            // #829 - the pinch/drag affordance + Reset, mirroring the iOS hrZoomHint row: teaches the
            // gesture, and once zoomed shows a Reset link that mirrors the chart's own double-tap reset.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (hrZoom == null) "Pinch to zoom · drag to pan" else "Zoomed in · drag to pan",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (hrZoom != null) {
                    Text(
                        "Reset",
                        style = NoopType.footnote,
                        color = Palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClickLabel = "Reset the heart rate zoom") { hrZoom = null }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

// The Today HR x-axis label strip: one Text per round-time tick, centred under its gridline via
// the SAME per-bucket timestamp interpolation the chart uses (timestampFraction, Charts.kt) and
// clamped into the strip. "Now" keeps its right-edge slot; a tick label that would collide with
// it (or with its left neighbour) is skipped rather than overlapped.
@Composable
private fun HrTimeAxisLabels(
    ticks: List<Pair<Long, String>>,
    timestamps: List<Long>,
    showNow: Boolean,
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            ticks.forEach { (_, label) ->
                Text(label, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            }
            if (showNow) {
                Text("Now", style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            val nowPlaceable = if (showNow) placeables.last() else null
            val nowLeft = nowPlaceable?.let { width - it.width } ?: Int.MAX_VALUE
            nowPlaceable?.place(width - nowPlaceable.width, 0)
            var lastRight = Int.MIN_VALUE
            ticks.forEachIndexed { i, (ts, _) ->
                val p = placeables[i]
                val frac = timestampFraction(timestamps, ts) ?: return@forEachIndexed
                val x = (frac * width - p.width / 2f).roundToInt().coerceIn(0, (width - p.width).coerceAtLeast(0))
                // Skip a label that would overlap its neighbour or the "Now" marker.
                if (x > lastRight && x + p.width <= nowLeft - 8) {
                    p.place(x, 0)
                    lastRight = x + p.width + 8
                }
            }
        }
    }
}

// #829 Android parity - the Today HR chart's transform detector. The Deep Timeline's own
// detectTransformGestures claims EVERY drag on the chart, fine on its dedicated screen but here it would
// eat the Today feed's vertical scroll AND the LineChart's scrub-to-inspect. This detector reuses the
// Deep Timeline's pure window math (zoomedWindow / pannedWindow, Charts.kt) but watches the INITIAL
// pointer pass and claims only:
//   (a) any multi-finger gesture (pinch zooms about the centroid), always, and
//   (b) a single-finger HORIZONTAL-dominant drag while ZOOMED (pan). Un-zoomed, a horizontal drag stays
//       the LineChart's scrub-to-inspect exactly as before, matching iOS where an un-zoomed pan is a
//       visual no-op anyway.
// A vertical-dominant drag is never claimed, so the feed keeps scrolling over the chart. Claimed events
// are consumed in the Initial pass, which cancels the child scrub AND the page-level day-swipe for that
// gesture, the same chart-owns-its-frame exclusivity the iOS Today chart gets from masking the day-swipe
// over the chart frame. A motionless double tap resets to the full day, mirroring the iOS double-tap
// reset. Windows only commit when they keep >= 2 buckets visible (the curve stays drawable), and a
// window grown back to the full bounds normalises to null (un-zoomed), so the hint/Reset row recovers by
// pinching out too.
private suspend fun PointerInputScope.hrChartTransformGestures(
    buckets: List<HrBucket>,
    bounds: LongRange,
    window: () -> LongRange?,
    onWindow: (LongRange?) -> Unit,
) {
    // Two 5-minute buckets: the tightest window that still draws a line segment.
    val minSpanSeconds = 600L
    var lastTapAtMs = 0L
    var lastTapPos = Offset.Zero
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var claimed = false   // ours: a pinch, or a horizontal pan while zoomed
        var ceded = false     // vertical-dominant: the feed's scroll owns it, just watch for the lift
        var moved = false
        var totalX = 0f
        var totalY = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) {
                // Lift-off. A short motionless single tap feeds the double-tap reset, only meaningful
                // while zoomed (the un-zoomed chart has nothing to reset, mirroring the iOS isZoomed
                // guard), and never consumed, so the LineChart's single-tap inspect keeps working.
                val up = event.changes.first()
                if (!claimed && !ceded && !moved && window() != null) {
                    val upAtMs = up.uptimeMillis
                    val isDoubleTap = upAtMs - lastTapAtMs <= viewConfiguration.doubleTapTimeoutMillis &&
                        (up.position - lastTapPos).getDistance() <= viewConfiguration.touchSlop * 4
                    if (isDoubleTap) {
                        onWindow(null)
                        lastTapAtMs = 0L
                    } else {
                        lastTapAtMs = upAtMs
                        lastTapPos = up.position
                    }
                }
                break
            }
            if (ceded) continue
            if (!claimed) {
                if (event.changes.count { it.pressed } > 1) {
                    claimed = true
                } else {
                    val pan = event.calculatePan()
                    totalX += pan.x
                    totalY += pan.y
                    if (abs(totalY) > viewConfiguration.touchSlop && abs(totalY) > abs(totalX)) {
                        ceded = true
                        moved = true
                        continue
                    }
                    if (abs(totalX) > viewConfiguration.touchSlop) {
                        moved = true
                        if (window() != null) claimed = true
                    }
                }
            }
            if (!claimed) continue
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            val width = size.width.toFloat().coerceAtLeast(1f)
            var w = window() ?: bounds
            if (zoomChange != 1f) {
                val frac = (event.calculateCentroid().x / width).coerceIn(0f, 1f)
                w = zoomedWindow(w, zoomChange, frac, bounds, minSpan = minSpanSeconds)
            }
            if (panChange.x != 0f) {
                val secPerPx = (w.last - w.first).toDouble() / width
                w = pannedWindow(w, (-panChange.x * secPerPx).toLong(), bounds)
            }
            if (buckets.count { it.bucket in w } >= 2) {
                onWindow(if (w.first <= bounds.first && w.last >= bounds.last) null else w)
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    }
}

// MARK: - Overview HR chart (WHOOP-style day-in-review annotations)
//
// The 24h HR line, the shared index-spaced [LineChart], with marker layers drawn ON TOP:
//   (a) a sleep band shading the night's sleep span (indigo, behind the line conceptually but
//       drawn under the marker chrome so labels stay legible),
//   (b) a dashed Charge rule + label at wake time (sleep end), hidden while recovery calibrates,
//   (c) a dashed Effort rule + label at "now" (the latest sample), routed through the SAME
//       UnitFormatter.effortDisplay the Effort tile uses so it honours the 0–100 / 0–21 toggle (#268),
//   (d) a small sport glyph at each workout's in-window HR peak.
//
// LineChart plots points by LIST INDEX (evenly spaced, no time axis), so each marker's wall-clock
// time is mapped to a fractional list index by interpolating against the buckets' own timestamps, // markers then sit exactly on the rendered curve even when the strap history has gaps. Every layer
// self-hides when its data is absent (no sleep, calibrating Charge, no workouts). Mirrors the macOS
// OverviewHRChart (Packages/StrandDesign) in NOOP's own colour language. (PR #285)

@Composable
private fun OverviewHRChart(
    buckets: List<HrBucket>,
    bpm: List<Double>,
    sleep: SleepSession?,
    workouts: List<WorkoutRow>,
    recovery: Double?,
    strain: Double?,
    effortScale: EffortScale,
    modifier: Modifier,
    // Round wall-clock (epochSec, "HH:mm") ticks, each drawn as a dotted gridline under the curve.
    // The matching labels render OUTSIDE this plot-height composable (HrTimeAxisLabels), sharing
    // the same tick list + timestamp mapping so they align. Empty = no gridlines.
    timeTicks: List<Pair<Long, String>> = emptyList(),
) {
    // The line itself stays the existing shared component, unchanged, markers are a sibling overlay.
    val bucketTimestamps = remember(buckets) { buckets.map { it.bucket } }
    val minV = bpm.min()
    val maxV = bpm.max()
    val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
    val n = bpm.size

    // Geometry constants copied verbatim from LineChart/pointsFor so overlay positions land on the curve.
    val strokePx = 2.5f
    val topPad = strokePx + 4f
    val bottomPad = strokePx + 4f

    // Plot pixel size, captured from the Box that wraps both the line and the overlay.
    var plotW by remember { mutableStateOf(0f) }
    var plotH by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    // ── time → x helpers ──
    // Fractional list index for a wall-clock unix-seconds time, interpolating between bucket
    // timestamps; null when the time falls outside the loaded buckets.
    fun fracIndexFor(ts: Long): Float? {
        if (n < 2) return null
        val first = buckets.first().bucket
        val last = buckets.last().bucket
        if (ts <= first) return 0f
        if (ts >= last) return (n - 1).toFloat()
        val hi = buckets.indexOfFirst { it.bucket >= ts }
        if (hi <= 0) return 0f
        val lo = hi - 1
        val t0 = buckets[lo].bucket
        val t1 = buckets[hi].bucket
        val f = if (t1 > t0) (ts - t0).toFloat() / (t1 - t0).toFloat() else 0f
        return lo + f
    }
    fun xFor(ts: Long): Float? {
        val fi = fracIndexFor(ts) ?: return null
        return if (n > 1) plotW * fi / (n - 1) else null
    }
    // Strict variant for POINT markers (charge pill, peak, effort-now rule): null when the time
    // falls outside the RENDERED buckets, so a zoomed window hides out-of-window marks exactly like
    // iOS clips them, instead of pinning them to the window edge. The sleep BAND keeps the clamping
    // xFor: clamping a range to the visible window is the correct behaviour for a span.
    fun xForStrict(ts: Long): Float? {
        if (n < 2) return null
        if (ts < buckets.first().bucket || ts > buckets.last().bucket) return null
        return xFor(ts)
    }
    fun yForBpm(v: Double): Float {
        val usableH = (plotH - topPad - bottomPad).coerceAtLeast(1f)
        val norm = ((v - minV) / span).toFloat().coerceIn(0f, 1f)
        return topPad + (1f - norm) * usableH
    }

    // ── derived marker model (self-hiding) ──
    // Sleep band span clamped to the window; only drawn when it overlaps a visible stretch. Uses the
    // EFFECTIVE onset so a hand-edited bedtime moves the band. (PR #395)
    val sleepStartX = sleep?.let { xFor(it.effectiveStartTs) }
    val sleepEndX = sleep?.let { xFor(it.endTs) }
    // Charge marker sits at wake (sleep end), else the window start; hidden while recovery is null.
    val chargeX = recovery?.let { sleep?.let { s -> xForStrict(s.endTs) } }
    // Effort marker pinned to the latest sample (right edge) when a strain exists.
    val effortX = strain?.let { if (n > 1) plotW else null }

    // One combined TalkBack description for the overlay layers, so the markers (which are otherwise
    // small decorative pills) are announced. Only mentions the layers actually present.
    val markerDescription = remember(sleep, recovery, strain, workouts, effortScale) {
        buildList {
            add("24-hour heart rate")
            if (sleep != null) add("sleep band ${hrHoursMinutes((sleep.endTs - sleep.effectiveStartTs).toInt())}")
            if (recovery != null) add("${recovery.roundToInt()} percent Charge at wake")
            if (strain != null) add("${UnitFormatter.effortDisplay(strain, effortScale)} Effort now")
            if (workouts.isNotEmpty()) add("${workouts.size} workout${if (workouts.size == 1) "" else "s"} marked")
        }.joinToString(", ")
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { plotW = it.width.toFloat(); plotH = it.height.toFloat() }
            .semantics { contentDescription = markerDescription },
    ) {
        // #765 (z-order / background layering): the sleep band must sit BEHIND the HR curve, matching the
        // iOS OverviewHRChart whose RectangleMark is "drawn first so the HR line/area sit on top". Android
        // previously drew the band in the SAME Canvas as the dashed rules, AFTER the LineChart, so the
        // translucent indigo region washed OVER the HR line + its value markers (the reported "text behind
        // the chart" / muddied curve). Splitting the band into its OWN Canvas placed BEFORE the LineChart
        // puts it under the curve, exactly like iOS; the wake divider, Charge/Effort rules and glow end-cap
        // stay in the Canvas AFTER the line (iOS draws those marks after the LineMark too, so they read on
        // top). Only the fill moved; same geometry, same colours.
        // Dotted round-time gridlines, FIRST so everything (band, curve, markers) reads over them.
        if (plotW > 0f && plotH > 0f && timeTicks.isNotEmpty()) {
            val gridDash = remember { PathEffect.dashPathEffect(floatArrayOf(4f, 6f), 0f) }
            Canvas(modifier = Modifier.fillMaxSize()) {
                timeTicks.forEach { (ts, _) ->
                    val frac = timestampFraction(bucketTimestamps, ts) ?: return@forEach
                    val x = frac * size.width
                    drawLine(
                        color = Palette.hairline,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f,
                        pathEffect = gridDash,
                    )
                }
            }
        }
        if (plotW > 0f && plotH > 0f &&
            sleepStartX != null && sleepEndX != null && sleepEndX > sleepStartX) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    color = Palette.sleepDeep.copy(alpha = 0.30f),
                    topLeft = Offset(sleepStartX, 0f),
                    size = Size(sleepEndX - sleepStartX, size.height),
                )
            }
        }

        // 1) The HR line (unchanged shared component, tap-to-inspect intact). Sits OVER the sleep band
        // (above) and UNDER the dashed rules + glow end-cap + marker pills (below), mirroring iOS.
        LineChart(
            values = bpm,
            modifier = Modifier.fillMaxSize(),
            color = Palette.metricRose,
            fill = true,
            selectionEnabled = true,
            // Scrub read-out: the timestamps prefix the sample's local clock time and the #463
            // formatter carries the unit — "14:32 · 87 bpm" instead of a bare "87".
            formatValue = { "${it.roundToInt()} bpm" },
            timestamps = bucketTimestamps,
        )

        // 2) Wake divider + dashed rules + glow end-cap, drawn in one Canvas ON TOP of the line.
        if (plotW > 0f && plotH > 0f) {
            val dash = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f) }
            val wakeDash = remember { PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f) }
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Wake divider: the sleep-to-day boundary, so the band reads even before Charge calibrates.
                // On top of the line (matching iOS's wake RuleMark after the LineMark).
                if (sleepStartX != null && sleepEndX != null && sleepEndX > sleepStartX &&
                    sleepEndX > 0f && sleepEndX < size.width) {
                    drawLine(
                        color = Palette.sleepLight.copy(alpha = 0.5f),
                        start = Offset(sleepEndX, 0f),
                        end = Offset(sleepEndX, size.height),
                        strokeWidth = 1f,
                        pathEffect = wakeDash,
                    )
                }
                // Charge rule at wake.
                if (chargeX != null) {
                    drawLine(
                        color = Palette.recoveryColor(recovery).copy(alpha = 0.85f),
                        start = Offset(chargeX.coerceIn(0f, size.width), 0f),
                        end = Offset(chargeX.coerceIn(0f, size.width), size.height),
                        strokeWidth = 1.5f,
                        cap = StrokeCap.Round,
                        pathEffect = dash,
                    )
                }
                // Effort rule at now.
                if (effortX != null) {
                    val x = (size.width - 1f).coerceIn(0f, size.width)
                    drawLine(
                        color = Palette.effortTint(strain / StrainScorer.maxStrain).copy(alpha = 0.85f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.5f,
                        cap = StrokeCap.Round,
                        pathEffect = dash,
                    )
                }

                // Glowing endpoint at the latest HR sample (right edge), a Bevel chart end-cap:
                // a soft rose halo + white core sitting on the line's final point.
                if (n >= 2) {
                    val lastX = size.width
                    val lastY = yForBpm(bpm.last())
                    val end = Offset(lastX.coerceIn(0f, size.width), lastY)
                    drawCircle(color = Palette.metricRose.copy(alpha = 0.30f), radius = 9f, center = end)
                    drawCircle(color = Palette.metricRose.copy(alpha = 0.65f), radius = 5.5f, center = end)
                    drawCircle(color = Palette.tipCore, radius = 2.4f, center = end)
                }
            }

            // 3) Marker labels + sport glyphs, positioned composables (crisp text/icons vs Canvas).
            val topPadDp = 10.dp
            // Sleep duration pill at the band's leading edge.
            if (sleepStartX != null && (sleepEndX ?: 0f) > (sleepStartX)) {
                val durLabel = hrHoursMinutes((sleep.endTs - sleep.effectiveStartTs).toInt())
                ChartMarkerPill(
                    text = durLabel,
                    color = Palette.sleepLight,
                    leadingIcon = Icons.Filled.Bedtime,
                    modifier = Modifier.markerOffset(sleepStartX, density, topPadDp),
                )
            }
            if (chargeX != null) {
                ChartMarkerPill(
                    text = "${recovery.roundToInt()}% Charge",
                    color = Palette.recoveryColor(recovery),
                    modifier = Modifier.markerOffset(chargeX, density, topPadDp),
                )
            }
            if (effortX != null) {
                ChartMarkerPill(
                    text = "${UnitFormatter.effortDisplay(strain, effortScale)} Effort",
                    color = Palette.effortTint(strain / StrainScorer.maxStrain),
                    modifier = Modifier.markerOffset(plotW, density, topPadDp, alignEnd = true),
                )
            }
            // Sport glyph at each workout's in-window HR peak.
            workouts.forEach { w ->
                val peak = hrPeakIn(buckets, w.startTs, w.endTs)
                if (peak != null) {
                    val px = xForStrict(peak.bucket)
                    if (px != null) {
                        val py = yForBpm(peak.avgBpm)
                        WorkoutGlyph(
                            icon = sportIcon(w.sport),
                            modifier = Modifier.glyphOffset(px, py, plotW, plotH, density),
                        )
                    }
                }
            }
        }
    }
}

/** "H:MM" for a duration in seconds (e.g. a 6h06m night → "6:06"). Mirrors TodayView.hoursMinutes. */
private fun hrHoursMinutes(seconds: Int): String {
    val h = (if (seconds < 0) 0 else seconds) / 3600
    val m = ((if (seconds < 0) 0 else seconds) % 3600) / 60
    return "$h:${m.toString().padStart(2, '0')}"
}

/** The peak HR bucket whose timestamp falls inside [start, end]; null when none overlap. */
private fun hrPeakIn(buckets: List<HrBucket>, start: Long, end: Long): HrBucket? =
    buckets.filter { it.bucket in start..end }.maxByOrNull { it.avgBpm }

/** Offset a marker pill near plot-x [x] (px). End-aligned markers (Effort) tuck under the right
 *  edge; the rest centre roughly on their anchor. Coerced to ≥ 0 so a pill never starts off-screen. */
private fun Modifier.markerOffset(
    x: Float,
    density: androidx.compose.ui.unit.Density,
    topPad: androidx.compose.ui.unit.Dp,
    alignEnd: Boolean = false,
): Modifier = this.offset(
    x = with(density) {
        // Approx pill half-width for edge clamping (footnote ≈ 7px/char + chrome).
        val xDp = x.toDp()
        if (alignEnd) (xDp - 70.dp).coerceAtLeast(0.dp) else (xDp - 36.dp).coerceAtLeast(0.dp)
    },
    y = topPad,
)

/** Position a 22dp sport glyph centred on a plot point (px), clamped inside the plot. */
private fun Modifier.glyphOffset(
    x: Float,
    y: Float,
    plotW: Float,
    plotH: Float,
    density: androidx.compose.ui.unit.Density,
): Modifier = this.offset(
    x = with(density) { (x.toDp() - 11.dp).coerceIn(0.dp, (plotW.toDp() - 22.dp).coerceAtLeast(0.dp)) },
    y = with(density) { (y.toDp() - 26.dp).coerceIn(0.dp, (plotH.toDp() - 22.dp).coerceAtLeast(0.dp)) },
)

/** Small caps read-out pill for the Charge / Effort / sleep-duration markers. */
@Composable
private fun ChartMarkerPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.surfaceOverlay.copy(alpha = 0.92f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = color, modifier = Modifier.size(10.dp))
        }
        Text(text, style = NoopType.footnote, color = color, maxLines = 1)
    }
}

/** Sport glyph in a tinted badge, anchored above a workout's HR peak. */
@Composable
private fun WorkoutGlyph(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.strain033),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Palette.textPrimary,
            modifier = Modifier.size(13.dp),
        )
    }
}


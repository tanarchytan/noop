package com.noop.ui.whoop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.noop.R
import com.noop.analytics.IllnessSignalEngine
import com.noop.analytics.RustScores
import com.noop.analytics.SpotHrvReading
import com.noop.analytics.V5HealthSignals
import com.noop.ble.WhoopModel
import com.noop.ui.AppViewModel
import com.noop.ui.AutoSizeValue
import com.noop.ui.BodyClockCard
import com.noop.ui.CountUpText
import com.noop.ui.CycleAwarenessCard
import com.noop.ui.CycleAwarenessOptInCard
import com.noop.ui.DataPendingNote
import com.noop.ui.HeadsUpCard
import com.noop.ui.HeartRateTrendCard
import com.noop.ui.HrvSnapshotScreen
import com.noop.ui.LazyScreenScaffold
import com.noop.ui.LineChart
import com.noop.ui.LiveHrSample
import com.noop.ui.MaxHrZoneCard
import com.noop.ui.Metrics
import com.noop.ui.NoopCard
import com.noop.ui.NoopCardHeader
import com.noop.ui.NoopType
import com.noop.ui.Overline
import com.noop.ui.Palette
import com.noop.ui.PhysiologyStack
import com.noop.ui.ProfileStore
import com.noop.ui.RealtimeHrOwner
import com.noop.ui.RealtimeHrWhileVisible
import com.noop.ui.RhythmAgeCard
import com.noop.ui.SectionHeader
import com.noop.ui.StatePill
import com.noop.ui.StrandTone
import com.noop.ui.UnitPrefs
import com.noop.ui.appendLiveHrSample
import com.noop.ui.cycleOptInApplies
import com.noop.ui.logicalDayNow
import com.noop.ui.rememberReduceMotion
import kotlinx.coroutines.delay
import uniffi.whoop_ffi.HrZoneSetInfo
import kotlin.math.roundToInt

// MARK: - Health Monitor detail
//
// The live heart rate first, then the day's heart-rate trend, the five vitals as a 2-up grid, the
// live-physiology fold (R-R proof, max HR + top zone, the seated HRV snapshot) and skin temperature.

/**
 * The Health Monitor. [onVitalClick] opens a vital's own trend by its metric key, matching the
 * existing metric-detail route.
 */
@Composable
fun WhoopHealthMonitorScreen(
    vm: AppViewModel,
    onVitalClick: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context.applicationContext) }
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val v5Signals by vm.v5Signals.collectAsStateWithLifecycle()
    val cycleEnabled by vm.cycleTrackingEnabled.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val zoneCoaching by vm.zoneCoaching.collectAsStateWithLifecycle()
    val tempUnit = UnitPrefs.temperature(context)
    val effortScale = UnitPrefs.effortScale(context)
    // The trend card's day anchor is the LOGICAL day (it rolls at 04:00), so the small hours keep the
    // evening's curve instead of opening on an empty new calendar day.
    val logicalToday = logicalDayNow()

    // Holding the live-HR want for as long as this screen is on top of a started app.
    RealtimeHrWhileVisible(vm, RealtimeHrOwner.HEALTH)

    // Zone bounds come from whoop-rs, so no %HRmax ladder is written here. The override pins them to
    // the profile's max HR, which is why age never keys the memo.
    val zoneSet = remember(profile.hrMax) {
        RustScores.hrZonesForAge(profile.ageYears, profile.hrMax.toDouble())
    }
    val zone5Bpm = remember(zoneSet) {
        zoneSet.zones.firstOrNull { it.number.toInt() == 5 }?.lower?.roundToInt() ?: 0
    }
    // The second series Rhythm Age is charted against. Read through the computed union, which resolves
    // its own newest-day/active-strap precedence, so a two-strap day cannot surface an arbitrary value.
    val fitnessAge by produceState<Double?>(initialValue = null) {
        value = runCatching { vm.repo.latestMetricComputedUnion("fitness_age")?.value }.getOrNull()
    }
    val activeConnection by remember { derivedStateOf { live.connected && live.bonded } }
    val vitals = remember(days, tempUnit) { latestHealthVitals(days, tempUnit) }
    var showHrvSnapshot by remember { mutableStateOf(false) }

    LazyScreenScaffold(
        title = stringResource(R.string.whoopskin_health_monitor),
        subtitle = stringResource(R.string.whoopskin_health_monitor_subtitle),
    ) {
        item { HeartRateHero(vm, zoneSet) }
        // The day's banked heart rate with its own window pills, sleep band and workout glyphs; the
        // hero above only holds the last few streamed minutes.
        item {
            HeartRateTrendCard(
                viewModel = vm,
                days = days,
                selectedDay = logicalToday,
                today = logicalToday,
                displayMetric = today,
                effortScale = effortScale,
            )
        }
        item { VitalGrid(vitals, onVitalClick) }
        if (vitals.all { it.value == null }) {
            item {
                DataPendingNote(
                    title = stringResource(R.string.whoopskin_vitals_building_title),
                    body = stringResource(R.string.whoopskin_vitals_building_body),
                )
            }
        }
        item { PhysiologyStack(live = live, activeConnection = activeConnection) }
        item { MaxHrZoneCard(hrMax = profile.hrMax, zone5Bpm = zone5Bpm, coachingOn = zoneCoaching) }
        item { HrvSnapshotButton(enabled = activeConnection) { showHrvSnapshot = true } }
        item {
            SkinTempSuite(
                signals = v5Signals,
                cycleEnabled = cycleEnabled,
                optInApplies = cycleOptInApplies(profile.sex),
                chronologicalAge = profile.ageYears,
                fitnessAge = fitnessAge,
                onEnableCycle = { vm.setCycleTrackingEnabled(true) },
                onTurnOffCycle = { vm.setCycleTrackingEnabled(false) },
            )
        }
    }

    if (showHrvSnapshot) {
        Dialog(
            onDismissRequest = { showHrvSnapshot = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val source = when (selectedModel) {
                WhoopModel.WHOOP5_MG -> SpotHrvReading.Source.OPTICAL_PPG
                WhoopModel.WHOOP4 -> SpotHrvReading.Source.CHEST_STRAP
            }
            HrvSnapshotScreen(
                viewModel = vm,
                source = source,
                onClose = { showHrvSnapshot = false },
            )
        }
    }
}

// MARK: - Heart rate hero

/**
 * The live heart rate: the streamed value, the zone whoop-rs's bounds place it in, and a rolling
 * trace of the last few minutes. Collects the ~1 Hz flows here so a beat never recomposes the page.
 */
@Composable
internal fun HeartRateHero(vm: AppViewModel, zoneSet: HrZoneSetInfo) {
    val live by vm.live.collectAsStateWithLifecycle()
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    // The smoothed value the view-model publishes; the decoded field is the fallback.
    val hr = bpm?.takeIf { it > 0 } ?: live.heartRate?.takeIf { it > 0 }
    val zone = hr?.let { zoneNumberIn(zoneSet, it.toDouble()) }
    val tint = if (zone != null && zone > 0) Palette.hrZoneColor(zone) else Palette.textTertiary

    // A 1 Hz trace of what was actually on screen, banked through the shared append seam so the
    // plausibility guard and the rolling cap stay one contract. Banking pauses with the flows it
    // reads, so a backgrounded app cannot lay down a flat run of the last value it saw.
    val trace = remember { mutableStateListOf<LiveHrSample>() }
    val latest by rememberUpdatedState(hr)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                appendLiveHrSample(trace, latest, System.currentTimeMillis())
                delay(1_000)
            }
        }
    }

    NoopCard(tint = tint) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
            ) {
                Overline(stringResource(R.string.whoopskin_heart_rate), modifier = Modifier.weight(1f))
                // Beats off the same reading the badge reads, so a still heart and "Live" cannot disagree.
                BeatingHeart(bpm = hr, tint = tint)
                StatePill(
                    title = stringResource(
                        if (hr != null) R.string.whoopskin_live else R.string.whoopskin_idle,
                    ),
                    tone = if (hr != null) StrandTone.Accent else StrandTone.Neutral,
                    showsDot = true,
                    pulsing = hr != null,
                )
            }
            if (hr != null) {
                val spokenHr = stringResource(R.string.whoopskin_heart_rate_a11y, hr)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) { contentDescription = spokenHr },
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
                ) {
                    CountUpText(
                        value = hr.toDouble(),
                        format = { it.roundToInt().toString() },
                        style = NoopType.display(56f),
                        color = tint,
                    )
                    Text(
                        "bpm",
                        style = NoopType.subhead,
                        color = Palette.textTertiary,
                        modifier = Modifier.padding(bottom = Metrics.space8),
                    )
                }
            } else {
                // No reading is said in words. A dash set at display size draws as a solid bar, and a
                // unit with nothing in front of it claims a measurement that was never taken.
                val spokenNone = stringResource(R.string.whoopskin_heart_rate_a11y_none)
                Text(
                    stringResource(R.string.whoopskin_vital_no_reading),
                    style = NoopType.title2,
                    color = Palette.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = spokenNone },
                )
            }
            Text(
                when {
                    zone == null -> stringResource(R.string.whoopskin_hr_awaiting_strap)
                    zone > 0 -> stringResource(R.string.whoopskin_hr_zone, zone)
                    else -> stringResource(R.string.whoopskin_hr_below_zone_1)
                },
                style = NoopType.captionNumber,
                color = Palette.textSecondary,
            )
            // The zone scale only means something once a beat has placed the wearer on it.
            if (zone != null) ZonePips(zone)
            // The trace only takes its height once beats exist. Reserved empty it left a card-tall blank
            // between the pips and the caption, with skeletons that never resolved.
            if (trace.size >= 2) {
                LineChart(
                    values = trace.map { it.bpm },
                    modifier = Modifier.height(Metrics.compactChartHeight),
                    color = tint,
                    fill = true,
                )
            }
            Text(
                stringResource(
                    if (trace.size >= 2) R.string.whoopskin_hr_trace_live
                    else R.string.whoopskin_hr_trace_waiting,
                ),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - The beating heart beside the badge

// The beat envelope, as fractions of one cycle: the systolic thump, a smaller second bump, then a
// rest. The rest is what reads as a heart rather than a breath. Scale only, so it stays cheap.
private const val BEAT_PEAK = 1.15f
private const val BEAT_SECOND_PEAK = 1.07f
private const val BEAT_BPM_MIN = 30
private const val BEAT_BPM_MAX = 220

/**
 * The small heart beside the live badge, beating once per 60/[bpm] seconds in [tint]. Without a
 * reading, or under reduce-motion, it holds still — a pulse next to "Idle" would claim a beat the
 * strap never sent.
 */
@Composable
private fun BeatingHeart(bpm: Int?, tint: Color, modifier: Modifier = Modifier) {
    val reduced = rememberReduceMotion()
    if (bpm == null || reduced) {
        HeartGlyph(tint = tint, scale = { 1f }, modifier = modifier)
        return
    }
    // A free-running phase rather than a keyed animation: the reading changes every second, and
    // re-keying would restart the cycle mid-thump.
    val latest by rememberUpdatedState(bpm)
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { frame ->
                if (last != 0L) {
                    val period = 60f / latest.coerceIn(BEAT_BPM_MIN, BEAT_BPM_MAX)
                    phase = (phase + (frame - last) / 1_000_000_000f / period) % 1f
                }
                last = frame
            }
        }
    }
    HeartGlyph(tint = tint, scale = { beatScale(phase) }, modifier = modifier)
}

/** The glyph itself. [scale] is read in the draw phase, so a beat never recomposes the row. */
@Composable
private fun HeartGlyph(tint: Color, scale: () -> Float, modifier: Modifier = Modifier) {
    Icon(
        Icons.Filled.Favorite,
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(Metrics.iconSmall)
            .graphicsLayer {
                val factor = scale()
                scaleX = factor
                scaleY = factor
            },
    )
}

/** The scale at [phase] (0..1 of one beat) along the thump-bump-rest envelope. */
internal fun beatScale(phase: Float): Float = when {
    phase < 0.09f -> smoothed(1f, BEAT_PEAK, phase / 0.09f)
    phase < 0.22f -> smoothed(BEAT_PEAK, 1f, (phase - 0.09f) / 0.13f)
    phase < 0.30f -> 1f
    phase < 0.37f -> smoothed(1f, BEAT_SECOND_PEAK, (phase - 0.30f) / 0.07f)
    phase < 0.50f -> smoothed(BEAT_SECOND_PEAK, 1f, (phase - 0.37f) / 0.13f)
    else -> 1f
}

/** Smoothstep from [from] to [to] over [t] in 0..1, so a thump eases instead of cornering. */
private fun smoothed(from: Float, to: Float, t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return from + (to - from) * x * x * (3f - 2f * x)
}

/** The five zone pips, lit up to [zone]. Zone 0 lights none. */
@Composable
private fun ZonePips(zone: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        for (number in 1..5) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(Metrics.space6)
                    .clip(RoundedCornerShape(Metrics.cornerXs))
                    .background(
                        if (number <= zone) Palette.hrZoneColor(number) else Palette.surfaceInset,
                    ),
            )
        }
    }
}

/**
 * The zone whose whoop-rs-supplied bpm interval contains [bpm]; 0 below the first zone. The bounds
 * are the border's, so no %HRmax cut point is decided here.
 */
private fun zoneNumberIn(set: HrZoneSetInfo, bpm: Double): Int {
    val top = set.zones.maxByOrNull { it.number.toInt() }
    if (top != null && bpm >= top.lower) return top.number.toInt()
    return set.zones.firstOrNull { bpm >= it.lower && bpm < it.upper }?.number?.toInt() ?: 0
}

// MARK: - Vitals grid

/** The five vitals two-up, each tile opening its own trend. */
@Composable
private fun VitalGrid(vitals: List<HealthVital>, onVitalClick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.nav_vital_signs),
            overline = stringResource(R.string.whoopskin_latest_readings),
        )
        vitals.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            ) {
                pair.forEach { vital ->
                    VitalCard(vital, Modifier.weight(1f)) { onVitalClick(vital.key) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One vital card: glyph and name, the reading with its unit, and when it was taken. */
@Composable
private fun VitalCard(vital: HealthVital, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = healthVitalColor(vital.key)
    val label = stringResource(vital.label)
    val spoken = healthVitalSpoken(vital)
    val asOf = healthAsOfLabel(vital.asOfDay) ?: stringResource(R.string.whoopskin_vital_no_reading)
    NoopCard(
        modifier = modifier
            .height(Metrics.tileHeight + Metrics.space24)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        padding = Metrics.space14,
        tint = accent,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
            ) {
                Icon(
                    healthVitalIcon(vital.key),
                    contentDescription = null,
                    tint = Palette.textSecondary,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
                Overline(label, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                AutoSizeValue(
                    text = vital.value?.let(vital.format) ?: HEALTH_NO_READING,
                    style = NoopType.tileValueLarge,
                    color = if (vital.value != null) accent else Palette.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (vital.value != null) {
                    Text(
                        vital.unit,
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                        modifier = Modifier.padding(bottom = Metrics.space4),
                    )
                }
            }
            Text(
                asOf,
                style = NoopType.footnote,
                color = Palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Metrics.space2),
            )
        }
    }
}

// MARK: - Live physiology + skin temperature

/** Opens the seated 60-second R-R reading. Needs a bonded link, so it dims without one. */
@Composable
private fun HrvSnapshotButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = Metrics.space10, vertical = Metrics.space8),
        // Named disabled tokens: Material's default washes the label out against the card and leaves the
        // outline almost invisible, so a dimmed button reads as a broken one.
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Palette.restBright,
            disabledContentColor = Palette.textTertiary,
        ),
        border = BorderStroke(1.dp, if (enabled) Palette.restBright else Palette.hairlineStrong),
    ) {
        Icon(
            Icons.Filled.MonitorHeart,
            contentDescription = null,
            modifier = Modifier.size(Metrics.iconSmall).padding(end = Metrics.space4),
        )
        Text(
            stringResource(R.string.whoopskin_take_hrv_reading),
            style = NoopType.captionNumber, maxLines = 1, softWrap = false,
        )
    }
}

/**
 * The multi-day signal suite, each card rendered only when its engine produced a result: the illness
 * heads-up when it is not quiet, cycle awareness once opted in, the body clock when estimated, and
 * Rhythm Age once the strap has banked any motion at all (below the worn-day floor it counts up
 * instead of hiding, so the wait is visible rather than silent).
 *
 * None of the four is a skin temperature; they read motion, R-R and sleep timing over days, which is
 * what separates them from the live vitals above.
 */
@Composable
private fun SkinTempSuite(
    signals: V5HealthSignals.Snapshot?,
    cycleEnabled: Boolean,
    optInApplies: Boolean,
    chronologicalAge: Double,
    fitnessAge: Double?,
    onEnableCycle: () -> Unit,
    onTurnOffCycle: () -> Unit,
) {
    val heads = signals?.illness?.takeIf { it.level != IllnessSignalEngine.Level.QUIET }
    val cycle = if (cycleEnabled) signals?.cycle else null
    val offersOptIn = !cycleEnabled && optInApplies
    val bodyClock = signals?.bodyClock
    // No motion banked at all (a strap that sends none) leaves the card out entirely: a counter stuck
    // at zero for ever is noise, not a wait.
    val showsRhythmAge = (signals?.restActivityWornDays ?: 0) > 0
    // The header only exists while a card does. Emitted unconditionally it ended the scroll on a
    // section title with nothing under it.
    if (heads == null && cycle == null && !offersOptIn && bodyClock == null && !showsRhythmAge) return
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        NoopCardHeader(
            stringResource(R.string.whoopskin_body_signals),
            color = Palette.textSecondary,
        )
        heads?.let { HeadsUpCard(result = it, distance = signals.illnessDistance) }
        cycle?.let { CycleAwarenessCard(result = it, onTurnOff = onTurnOffCycle) }
        if (offersOptIn) CycleAwarenessOptInCard(onEnable = onEnableCycle)
        bodyClock?.let { BodyClockCard(estimate = it) }
        if (showsRhythmAge && signals != null) {
            RhythmAgeCard(
                info = signals.rhythmAge,
                wornDays = signals.restActivityWornDays,
                chronologicalAge = chronologicalAge,
                confidence = bodyClock?.confidence,
                fitnessAge = fitnessAge,
            )
        }
    }
}

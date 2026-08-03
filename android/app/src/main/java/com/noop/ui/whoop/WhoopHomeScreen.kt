package com.noop.ui.whoop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.Baselines
import com.noop.ui.AppViewModel
import com.noop.ui.AutoWorkoutNudgeCard
import com.noop.ui.DAY_NAV_SWIPE_THRESHOLD_DP
import com.noop.ui.DashboardCardPrefs
import com.noop.ui.EffortScale
import com.noop.ui.IllnessBanner
import com.noop.ui.KeyMetricPrefs
import com.noop.ui.LiveSessionEntryCard
import com.noop.ui.LiveSessionPrefs
import com.noop.ui.LiveSessionRunner
import com.noop.ui.LiveSessionScreen
import com.noop.ui.MetricGrid
import com.noop.ui.Metrics
import com.noop.ui.NO_DATA
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.QuickActionDisc
import com.noop.ui.ScoreState
import com.noop.ui.SectionHeader
import com.noop.ui.SourceBadge
import com.noop.ui.UnitFormatter
import com.noop.ui.UnitPrefs
import com.noop.ui.dayNavCanGoNewer
import com.noop.ui.dayNavNewer
import com.noop.ui.dayNavOlder
import com.noop.ui.dayNavSwipeTarget
import com.noop.ui.launchDayOffset
import com.noop.ui.provenanceLabelTint
import com.noop.ui.scoreStateForToday
import com.noop.ui.startOrResumeLiveSession
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Home
//
// The WHOOP-shaped home page: a sticky bar, three hero rings that collapse into a pinned row on scroll,
// then "My Day" and "My Dashboard". Every number on screen is a stored field or a whoop-rs score; this
// file arranges them and decides nothing about what they are.

/** One value per LAUNCH: the fresh-process day landing runs once and never fights in-session memory. */
private var whoopHomeSnappedToTodayThisLaunch = false

/** The stored Effort axis maximum, mapped to the user's display scale for the ring. */
private const val EFFORT_STORED_MAX = 100.0

/** Charge and Rest are percentages, so their ring fractions divide by this. */
private const val SCORE_MAX = 100.0

/** The mark the two percentage rings carry beside their number. */
private const val PERCENT = "%"

@Composable
fun WhoopHomeScreen(
    viewModel: AppViewModel,
    onQuickActions: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
    onOpenHealth: () -> Unit = {},
    onOpenStress: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
    onOpenWorkouts: () -> Unit = {},
    onOpenJournal: () -> Unit = {},
    onOpenAlarm: () -> Unit = {},
    onOpenTrends: () -> Unit = {},
    onOpenMetric: (String) -> Unit = {},
    onOpenRecovery: () -> Unit = {},
    onOpenStrain: () -> Unit = {},
) {
    val context = LocalContext.current
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    // rememberSaveable rides the saved-instance bundle across a process kill, which would strand a
    // returning user on whatever day they last browsed. A fresh process lands on today, once.
    if (!whoopHomeSnappedToTodayThisLaunch) {
        whoopHomeSnappedToTodayThisLaunch = true
        val landed = launchDayOffset(isFreshLaunch = true, savedOffset = dayOffset)
        if (dayOffset != landed) dayOffset = landed
    }

    val state = rememberWhoopHomeState(viewModel, dayOffset)
    val effortScale = UnitPrefs.effortScale(context)
    val unitSystem = UnitPrefs.system(context)
    var enabledCards by remember { mutableStateOf(DashboardCardPrefs.enabled(context)) }
    var showEditor by remember { mutableStateOf(false) }
    var enabledMetrics by remember { mutableStateOf(KeyMetricPrefs.enabled(context)) }
    var showMetricsEditor by remember { mutableStateOf(false) }
    var metricsExpanded by rememberSaveable { mutableStateOf(false) }

    // Live Sessions (beta): the entry stays visible while a session runs even with the flag off, because
    // the card is the only way back into a running session's dialog.
    val liveSession by LiveSessionRunner.active.collectAsStateWithLifecycle()
    val liveSessionsEnabled = remember { LiveSessionPrefs.enabled(context) }
    var showLiveSession by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val collapsePx = with(LocalDensity.current) { Metrics.tileHeight.toPx() }
    val heroCollapsed by remember(collapsePx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > collapsePx
        }
    }

    // A horizontal drag changes the day; the vertical scroll is untouched because only horizontal
    // gestures are claimed. The day is resolved once on lift.
    val swipeThresholdPx = with(LocalDensity.current) { DAY_NAV_SWIPE_THRESHOLD_DP.dp.toPx() }
    val daySwipe = Modifier.pointerInput(Unit) {
        var accumulated = 0f
        detectHorizontalDragGestures(
            onDragStart = { accumulated = 0f },
            onDragEnd = { dayOffset = dayNavSwipeTarget(dayOffset, accumulated, swipeThresholdPx) },
            onHorizontalDrag = { _, amount -> accumulated += amount },
        )
    }

    val rings = whoopHeroRings(
        state = state,
        effortScale = effortScale,
        onOpenSleep = onOpenSleep,
        onOpenRecovery = onOpenRecovery,
        onOpenStrain = onOpenStrain,
    )

    Column(modifier = Modifier.fillMaxSize().background(Palette.surfaceBase)) {
        WhoopHomeTopBar(
            title = state.title,
            subtitle = state.subtitle,
            date = state.date,
            canGoNext = dayNavCanGoNewer(dayOffset),
            onPrevious = { dayOffset = dayNavOlder(dayOffset) },
            onNext = { dayOffset = dayNavNewer(dayOffset) },
            onPickDay = { dayOffset = it },
            onOpenProfile = onOpenProfile,
            onOpenDevices = onOpenDevices,
            connected = state.connected,
            batteryPct = state.batteryPct,
        )
        AnimatedVisibility(
            visible = heroCollapsed,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            WhoopHeroRingRowCompact(rings)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(daySwipe),
            contentPadding = PaddingValues(
                start = Metrics.screenPadding,
                end = Metrics.screenPadding,
                top = Metrics.space12,
                bottom = Metrics.sectionGap,
            ),
            verticalArrangement = Arrangement.spacedBy(Metrics.gap),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.space12),
                ) {
                    WhoopHeroRingRow(rings)
                    state.sourceLabel?.let { label ->
                        SourceBadge(text = label, tint = provenanceLabelTint(label))
                    }
                    WhoopScoreStateNote(state)
                }
            }
            // The illness heads-up reads full width above the monitors: it is the one thing on this page
            // that asks the reader to act, and the monitor tile's own pill is too small to carry it.
            state.healthAlert?.let { alert -> item { IllnessBanner(alert) } }
            item {
                WhoopMonitorRow(
                    healthAlert = state.healthAlert,
                    stress = state.stress,
                    onOpenHealth = onOpenHealth,
                    onOpenStress = onOpenStress,
                )
            }
            item { WhoopSectionRow("My Day") { QuickActionDisc(onClick = onQuickActions) } }
            // A session is a now-thing, so the entry is today-only; a running one keeps it regardless.
            if (state.isToday && (liveSessionsEnabled || liveSession != null)) {
                item {
                    LiveSessionEntryCard(
                        onOpen = {
                            // Only BEGIN when nothing is in flight: a running session, or one holding an
                            // unseen summary, is re-presented rather than displaced.
                            if (LiveSessionRunner.active.value == null) {
                                startOrResumeLiveSession(viewModel, context)
                            }
                            showLiveSession = true
                        },
                    )
                }
            }
            item {
                WhoopTonightSleepCard(
                    alarmEnabled = state.alarmEnabled,
                    alarmMinutes = state.alarmMinutes,
                    onOpenSleep = onOpenSleep,
                    onOpenAlarm = onOpenAlarm,
                )
            }
            item { WhoopActivitiesCard(state.activities, onOpenWorkouts) }
            // The opt-in "looks like a workout?" suggestion; it renders nothing when the toggle is off
            // or there is nothing to suggest.
            if (state.isToday) {
                item { AutoWorkoutNudgeCard(viewModel = viewModel, days = state.days) }
            }
            item { WhoopJournalCard(onOpenJournal) }
            item {
                WhoopSectionRow("Key metrics") {
                    WhoopCustomiseAction(
                        label = "Customise my key metrics",
                        onClick = { showMetricsEditor = true },
                    )
                }
            }
            item {
                MetricGrid(
                    d = state.metric,
                    recoveryCalibration = state.calibratingNights,
                    carriedDay = state.carriedDay,
                    spo2CarryDay = state.spo2Day,
                    unitSystem = unitSystem,
                    effortScale = effortScale,
                    latestWeightKg = state.latestWeightKg,
                    profileWeightKg = state.profileWeightKg,
                    importedStepsForDay = state.importedSteps,
                    estimatedStepsForDay = state.estimatedSteps,
                    restScore = state.restScore,
                    enabledMetrics = enabledMetrics,
                    metricsExpanded = metricsExpanded,
                    onToggleMetrics = { metricsExpanded = !metricsExpanded },
                )
            }
            item {
                WhoopSectionRow("My Dashboard") {
                    WhoopCustomiseAction(
                        label = "Customise my dashboard",
                        onClick = { showEditor = true },
                    )
                }
            }
            item {
                WhoopDashboardCard(
                    state = state,
                    cards = enabledCards,
                    onOpenMetric = onOpenMetric,
                    onOpenStress = onOpenStress,
                    onOpenSleep = onOpenSleep,
                )
            }
            item {
                WhoopStrainRecoveryCard(
                    days = state.days,
                    dayKey = state.dayKey,
                    scale = effortScale,
                    onOpenTrends = onOpenTrends,
                )
            }
        }
    }

    if (showEditor) {
        Dialog(
            onDismissRequest = { showEditor = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            WhoopDashboardCustomizeSheet(
                initial = enabledCards,
                onClose = { showEditor = false },
                onSaved = { cards ->
                    enabledCards = cards
                    showEditor = false
                },
            )
        }
    }

    if (showMetricsEditor) {
        Dialog(
            onDismissRequest = { showMetricsEditor = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            WhoopKeyMetricsCustomizeSheet(
                initial = enabledMetrics,
                onClose = { showMetricsEditor = false },
                onSaved = { metrics ->
                    enabledMetrics = metrics
                    showMetricsEditor = false
                },
            )
        }
    }

    // The full-screen session surface. Dismissing only HIDES it; the entry card above re-presents a
    // session that is still running or still holding its summary.
    if (showLiveSession) {
        Dialog(
            onDismissRequest = { showLiveSession = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LiveSessionScreen(vm = viewModel, onClose = { showLiveSession = false })
        }
    }
}

/** The "CUSTOMISE" link a section header carries when its content is user-editable. */
@Composable
private fun WhoopCustomiseAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent),
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(Metrics.iconSmall))
        Spacer(Modifier.width(Metrics.space4))
        Text("CUSTOMISE", style = NoopType.overline, color = Palette.accent)
    }
}

/** A section title with its trailing action, the rhythm every "My …" block on this page shares. */
@Composable
private fun WhoopSectionRow(title: String, trailing: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) { SectionHeader(title) }
        trailing()
    }
}

/**
 * The honest word under the rings when today has no score of its own: calibrating, a stamped carry, or
 * needs the strap. The copy is [ScoreState]'s, so this page says exactly what the rest of the app does.
 */
@Composable
private fun WhoopScoreStateNote(state: WhoopHomeState) {
    if (!state.isToday) return
    val score = scoreStateForToday(
        todayRecovery = state.metric?.recovery,
        calibratingNights = state.calibratingNights,
        carriedDay = state.carriedDay,
    )
    if (score is ScoreState.Scored) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Metrics.space2),
    ) {
        Text(score.title, style = NoopType.subhead, color = Palette.textPrimary)
        Text(score.detail, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

/** The three hero rings in WHOOP's order, each already carrying its own scored value. */
@Composable
private fun whoopHeroRings(
    state: WhoopHomeState,
    effortScale: EffortScale,
    onOpenSleep: () -> Unit,
    onOpenRecovery: () -> Unit,
    onOpenStrain: () -> Unit,
): List<WhoopHeroRing> {
    val effortMax = effortAxisMax(effortScale)
    val effort = state.effort?.let { UnitFormatter.effortValue(it, effortScale) }
    // A carried prior night keeps the Charge ring lit; the note under the row stamps it as carried.
    val charge = state.metric?.recovery ?: state.carriedDay?.recovery
    return listOf(
        WhoopHeroRing(
            label = "Rest",
            value = state.restScore,
            fraction = state.restScore?.div(SCORE_MAX),
            tint = Palette.recoveryColor(state.restScore ?: 0.0),
            format = { it.roundToInt().toString() },
            emptyTitle = NO_DATA,
            unit = PERCENT,
            onClick = onOpenSleep,
        ),
        WhoopHeroRing(
            label = "Charge",
            value = charge,
            fraction = charge?.div(SCORE_MAX),
            tint = Palette.recoveryColor(charge ?: 0.0),
            format = { it.roundToInt().toString() },
            emptyTitle = if (state.calibratingNights != null) "Calibrating" else NO_DATA,
            emptyDetail = state.calibratingNights?.let { "$it of ${Baselines.minNightsSeed}" },
            unit = PERCENT,
            onClick = onOpenRecovery,
        ),
        WhoopHeroRing(
            label = "Effort",
            value = effort,
            fraction = if (effortMax > 0.0) effort?.div(effortMax) else null,
            tint = Palette.effortTint((state.effort ?: 0.0) / EFFORT_STORED_MAX),
            format = { value ->
                if (effortScale == EffortScale.WHOOP) {
                    String.format(Locale.US, "%.1f", value)
                } else {
                    value.roundToInt().toString()
                }
            },
            emptyTitle = NO_DATA,
            caption = "of ${effortMax.roundToInt()}",
            onClick = onOpenStrain,
        ),
    )
}

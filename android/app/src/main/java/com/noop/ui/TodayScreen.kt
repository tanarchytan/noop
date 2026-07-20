package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.Baselines
import com.noop.analytics.BatteryEstimator
import com.noop.analytics.CalibrationMilestones
import com.noop.analytics.HydrationGoal
import com.noop.analytics.HydrationStore
import com.noop.analytics.RecoveryScorer
import com.noop.analytics.StrainScorer
import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import com.noop.ingest.HealthConnectImporter
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Control Center, the home dashboard. A recovery ring + plain-English synthesis
 * hero, an illness banner when the watch fires, and a tile grid of the day's key
 * metrics, each tile carrying a 14-day sparkline. Ports the macOS TodayView
 * composition (Strand/Screens/TodayView.swift) with the same locked components.
 *
 * Sparkline series are built off the view model's `recentDays` (oldest → newest,
 * all from the my-whoop source). Missing current-day values render as explicit
 * "No Data" states instead of raw dashes, so old imports do not look like today.
 */

/** Stable Today info-card ids (the dismissed-flag suffix + the inbox `restorePayload`). Match the
 *  iOS card ids so an export/import round-trips. */
private const val CARD_SCORES_BUILDING = "scoresBuilding"
private const val CARD_NEW_HERE = "newHere"
// #827: the "Building your baseline, N more nights" calibrating note is dismissible-into-the-inbox like
// the other Today info-cards, so a returning user who has read it once isn't nagged with it every day
// through the multi-night calibration window. Same id on both platforms so it round-trips an export/import.
private const val CARD_CALIBRATING = "calibratingBaseline"
// The gamified calibration-milestone countdown stack (First Recovery → Sleep → Trusted → 30-day baseline).
// Dismissible-into-the-inbox like the other Today info-cards so a returning user isn't nagged for the full
// 30-night run; a "Restore to Today" tap brings it back. Same id on both platforms so it round-trips an
// export/import. Presentation only — the targets never touch the Baselines math (CalibrationMilestones).
private const val CARD_CALIBRATION_MILESTONES = "calibrationMilestones"
// The "Latest sleep · <date>" / "Last night · <date>" carry-over note (ScoreState.CarriedLastNight). iOS
// has nothing in this slot, so on Android it's dismissible-into-the-inbox like the other Today info-cards:
// a small × tucks it into Updates (restorable), so it never sits permanently between the header and the
// hero throwing off the compact liquid look. Local-only id (iOS has no twin), matching the dismiss plumbing.
private const val CARD_CARRIED_SLEEP = "carriedSleep"

/** #860 item 1: process-lifetime guard for the launch snap-to-today. `selectedDayOffset` is rememberSaveable
 *  so a tab-away keeps the user's chosen day (#614/#739). The same persistence, however, rides the
 *  saved-instance-state bundle across a system-initiated process kill + restore (common after an app UPDATE),
 *  so a user who was browsing an OLD day when the process died - or a calibrating user the now-retired
 *  #605/#739 auto-land would have snapped to an old day - reopened the app pinned to that day instead of
 *  today. A top-level var = one value per LAUNCH (reset only on a genuine fresh process), so we run the pure
 *  `launchDayOffset` policy exactly once per launch (forcing today) and leave in-session tab-away/restore
 *  behaviour untouched. iOS parity: TodayView's selectedDayOffset is plain @State, which is never persisted
 *  and so already re-inits to 0 on every fresh launch, reaching the same offset through the same helper. */
private var todayDidSnapToTodayThisLaunch = false

// MARK: - Liquid hero tokens (the liquid Today restyle)
//
// The hero card the score arc rings sit on, ported from the iOS LiquidTodayView. `LIQUID_HERO_FILL` is
// theme-aware — a cream frosted card in light, a translucent near-black (mock rgba(13,14,20,.80)) in dark —
// sitting on the plain Palette.surfaceBase canvas (the day-of-sky backdrop was removed). The ring centre
// numbers render in the themed text colour. Radius 26 + a white@0.11 hairline give the frosted-glass edge.
private val LIQUID_HERO_FILL: Color
    get() = if (Palette.isLight) {
        // Light: a cream frosted card (≈ surfaceRaised) so the hero doesn't sit as a dark block on the
        // sand canvas. Dark keeps the near-black card on the plain themed canvas.
        Color(red = 0.992f, green = 0.976f, blue = 0.945f, alpha = 0.92f)
    } else {
        Color(red = 13f / 255f, green = 14f / 255f, blue = 20f / 255f, alpha = 0.80f)
    }
private val LIQUID_HERO_RADIUS: Dp = 26.dp

/**
 * The minimal, stable slice of the BLE [com.noop.ble.LiveState] the Today top-level body reads. Pulled out
 * so a per-second heart-rate tick, which the body does not display numerically, produces an EQUAL value
 * and skips recomposing the whole dashboard (the redesign's scroll-jank fix). `hrStreaming` collapses the
 * ticking bpm to "is a live stream present" (the only thing the recording light needs); all other fields
 * change at most every few seconds. A plain data class so [androidx.compose.runtime.derivedStateOf] can
 * structurally-compare successive snapshots and emit only on a real change.
 */
private data class TodayLiveSnapshot(
    val connected: Boolean,
    val hrStreaming: Boolean,
    val lastSyncAt: Long?,
    val backfilling: Boolean,
    val syncChunksThisSession: Int,
    val historySyncExperimental: Boolean,
    val batteryPct: Double?,
    /** True once a WHOOP 5/MG strap has been seen this session, picks the 5/MG rated-life fallback for the
     *  battery runtime estimate (#713). Changes at most once per connection, so it doesn't reintroduce the
     *  per-tick churn the snapshot exists to avoid. */
    val whoop5: Boolean,
    /** Charging hides the runtime estimate (no "X left" while topping up). Rare flips, snapshot-safe. */
    val charging: Boolean?,
)

@Composable
fun TodayScreen(
    viewModel: AppViewModel,
    onQuickActions: () -> Unit = {},
    updateStore: UpdateStore? = null,
    onOpenSettings: () -> Unit = {},
    // The leading avatar in the Today header opens the Profile menu (body profile + units). Defaults to
    // onOpenSettings so an unbound caller keeps a sensible destination.
    onOpenProfile: () -> Unit = onOpenSettings,
    onOpenHydration: () -> Unit = {},
    // #706/#684: the "Your cards" dashboard rows are tappable on iOS but only Hydration navigated on Android.
    // These push each card's detail (Stress card -> Stress; Sleep -> Sleep), matching the iOS pinnedCardRow
    // destinations. Defaulted to no-ops so the call site stays compiling; AppRoot binds them to nav.navigate(...)
    // like onOpenHydration.
    onOpenStress: () -> Unit = {},
    onOpenHealth: () -> Unit = {},
    // Every metric/vital card (HRV, Resting HR, Respiratory, SpO₂, Skin Temp, Fitness age, Vitality, Steps,
    // Calories) opens ITS OWN focused detail trend, not the shared Health hub (2026-07-03: cards were
    // wrongly dumping into the Health monitor). Mirrors the iOS liquidCard `metricDetail(key)`. Takes the
    // vital_detail key; defaults to the Health screen so an unbound caller keeps the old behaviour.
    onOpenMetric: (String) -> Unit = { onOpenHealth() },
    onOpenSleep: () -> Unit = {},
    // Optional Coupled view card (task #43): a tap-through to the WHOOP-style day screen. Defaulted to a
    // no-op so the call site stays compiling; AppRoot binds it to nav.navigate(CoupledView).
    onOpenCoupled: () -> Unit = {},
    // The Effort hero ring opens Workouts (full-page navigation, not a popup).
    onOpenWorkouts: () -> Unit = {},
    // The "workout in progress" indicator card routes to Workouts and re-opens the in-exercise overlay
    // (Live/Health fold). Defaulted to a no-op so the call site stays compiling; AppRoot binds it to
    // openActiveWorkout() + nav.navigateTopLevel(Workouts).
    onOpenActiveWorkout: () -> Unit = {},
    // The liquid header battery ring taps through to Devices (iOS parity: the battery ring → router.openDevices()).
    // Defaulted to fall back to Settings so the call site stays compiling; AppRoot binds it to the Devices route.
    onOpenDevices: () -> Unit = onOpenSettings,
) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val alert by viewModel.healthAlert.collectAsStateWithLifecycle()
    val days by viewModel.recentDays.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    // The in-flight manual workout (single source of truth, survives an app kill via rehydration), so the
    // indicator card auto-appears/clears off this alone. Null↔non-null + the start drive the card; the
    // per-second clock ticks inside the card's own LaunchedEffect, never recomposing the Today body.
    val activeWorkout by viewModel.activeWorkout.collectAsStateWithLifecycle()
    // PERF (#scroll-jank): the BLE live state ticks the heart rate roughly once a second. Reading the raw
    // `live` object directly in this top-level body would recompose the ENTIRE Today tree (rings, cards,
    // scene-positioning) on every bpm change, visible as scroll stutter on real devices. The body only
    // needs a handful of stable, slow-changing fields, and the live HR matters here only as "is a stream
    // present" (null↔non-null), never the bpm number. Funnel those through a `derivedStateOf` snapshot so a
    // 72→73 bpm tick produces an EQUAL snapshot and the body is NOT recomposed; it only recomposes when
    // connection / sync / battery / streaming-presence actually change. The live bpm number is rendered
    // elsewhere (HeartRateTrendCard), which scopes its own collection. Appearance-preserving.
    val liveSnap by remember {
        derivedStateOf {
            val s = live
            TodayLiveSnapshot(
                connected = s.connected,
                hrStreaming = s.heartRate != null,
                lastSyncAt = s.lastSyncAt,
                backfilling = s.backfilling,
                syncChunksThisSession = s.syncChunksThisSession,
                historySyncExperimental = s.historySyncExperimental,
                batteryPct = s.batteryPct,
                whoop5 = s.whoop5Detected,
                charging = s.charging,
            )
        }
    }
    // #849: seed from the ViewModel cache so a re-mount (tab-return / post-import) restores the last footer
    // immediately instead of flashing empty while the heavy reload is (now) skipped for unchanged data.
    var footer by remember { mutableStateOf(viewModel.todayFooterCache ?: TodayFooterState()) }
    // rememberSaveable (not plain remember): the bottom-tab NavHost (AppRoot) navigates with
    // saveState/restoreState, which only restores rememberSaveable-backed state. With plain remember a
    // tab-away wiped the chosen day back to 0, so on return the dashboard "shifted" off the day the user was
    // looking at (#614 follow-up). Persisting it across the save/restore keeps the chosen day put. The
    // launch snap-to-today is a separate process-lifetime flag (todayDidSnapToTodayThisLaunch below).
    var selectedDayOffset by rememberSaveable { mutableIntStateOf(0) }
    // #860 item 1: on a GENUINE fresh process (not a tab-away/recomposition), force the selected day back to
    // today via the pure `launchDayOffset` policy. rememberSaveable restores selectedDayOffset from the
    // saved-instance-state bundle, which the system reuses across a process kill + restore (the after-an-update
    // case in the report); without this, a user who was viewing an old day when the process died - OR a
    // calibrating user the retired auto-land would have snapped to an old day - reopened the app stranded
    // there. The top-level guard is false exactly once per launch, so `launchDayOffset(isFreshLaunch = true)`
    // forces today a single time and never fights the in-session tab-away day-memory (#614/#739) afterwards.
    // Done in composition (not a LaunchedEffect) so the stale restored day never paints for a frame. iOS uses
    // plain @State (re-inits to 0 every launch) and reaches the same offset through the same helper.
    if (!todayDidSnapToTodayThisLaunch) {
        todayDidSnapToTodayThisLaunch = true
        val landed = launchDayOffset(
            isFreshLaunch = true,
            savedOffset = selectedDayOffset,
        )
        if (selectedDayOffset != landed) selectedDayOffset = landed
    }
    // Anchor offset-0 to the LOGICAL day (rolls at 04:00 local), so between midnight and 4am "Today"
    // still resolves to the prior calendar day's banked row instead of an empty new-calendar-day row
    // that blanks the dashboard (#144). Past offsets count back from this anchor. Presentation-only.
    val todayDate = logicalDayNow()
    // #860 item 1: the launch auto-land (#605/#739 "snap to the most recent data day when today is empty")
    // is RETIRED. It fired on a fresh process when today had no row yet, and for a calibrating user whose
    // newest data was a few days back it stranded them on that old day, overriding the snap-to-today above.
    // A fresh launch now lands on today via `launchDayOffset` (the inline guard above), and in-session day
    // memory (#739/#614) is preserved because nothing rewrites `selectedDayOffset` after launch. iOS parity
    // in TodayView (which retired the same block).
    val selectedDay = remember(selectedDayOffset, todayDate) { todayDate.minusDays(selectedDayOffset.toLong()) }
    // The key the day-scoped read-outs (Rest score, HR window, sleep band) key on. At offset 0 it
    // follows the resolver's `today?.day` so it tracks the row actually surfaced, including the non-UTC
    // pre-04:00 case (#304) where Today is the LOCAL-calendar-day row, not the logical-day one. Falls
    // back to the logical key when no row is banked yet. Past offsets use the logical key directly.
    val selectedDayKey = remember(selectedDay, today, selectedDayOffset) {
        if (selectedDayOffset == 0) today?.day ?: selectedDay.toString() else selectedDay.toString()
    }
    val historicalMetric = remember(days, selectedDayKey) { days.lastOrNull { it.day == selectedDayKey } }
    val displayMetric = remember(today, historicalMetric, selectedDayOffset) {
        if (selectedDayOffset == 0) today ?: historicalMetric else historicalMetric
    }
    // Keep the explicit calendar date visible alongside Today/Yesterday so the logical-day remap stays
    // honest, between midnight and 04:00 "Today" still points at the prior calendar date, and showing
    // that date makes it obvious which day's row is on screen (#144).
    val dayLabel = remember(selectedDayOffset, selectedDay, selectedDayKey) {
        // Date the label by the row ACTUALLY on screen, not the raw logical date. `selectedDayKey` already
        // follows the resolver's `today?.day` at offset 0, so when the resolver surfaces yesterday's
        // complete row (today not scored yet) the date now reads that row's day, instead of stamping
        // "Today · <today>" over yesterday's values, which disagreed with the Intelligence History row for
        // the same data (#434). iOS/Mac already label by the shown row's day; this brings Android to parity.
        val keyDate = runCatching { LocalDate.parse(selectedDayKey) }.getOrNull() ?: selectedDay
        val date = keyDate.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.US))
        when (selectedDayOffset) {
            0 -> "Today · $date"
            1 -> "Yesterday · $date"
            else -> date
        }
    }
    // Display-only unit system + the SI profile weight, read once like every other Settings-backed
    // preference (SharedPreferences isn't reactive, a Settings write triggers recomposition).
    val context = LocalContext.current
    val unitSystem = UnitPrefs.system(context)
    // Effort display scale (#268), drives the Effort tile's value + caption. Display-only.
    val effortScale = UnitPrefs.effortScale(context)
    val profileWeightKg = remember { ProfileStore.from(context).weightKg }
    // Body profile for the live Effort computation below, age/sex/HR-max-override drive the same
    // StrainScorer call the daily pass uses. Read once like every other Settings-backed value. (#402)
    val profileStore = remember { ProfileStore.from(context) }

    // Editable Key-Metrics layout (#251), an ordered list of the enabled tiles, persisted display-only.
    // SharedPreferences isn't reactive, so it's mirrored into local state and re-read when the editor saves.
    var showMetricsEditor by remember { mutableStateOf(false) }
    var enabledKeyMetrics by remember { mutableStateOf(KeyMetricPrefs.enabled(context)) }

    // "Your cards" customisable dashboard (WHOOP "My Dashboard"), a persisted, reorderable selection of
    // metric cards. Empty/unset shows the sensible default set (Stress / Fitness age / Vitality + HRV +
    // Resting HR). The "CUSTOMISE" link on the section header opens a local sheet (no new nav destination).
    // Persistence is display-only, these cards read the SAME values the rest of Today already loads.
    // SharedPreferences isn't reactive, so it's mirrored into local state and re-read when the editor saves.
    var showDashboardEditor by remember { mutableStateOf(false) }
    var enabledDashboardCards by remember { mutableStateOf(DashboardCardPrefs.enabled(context)) }

    // The pinned "Your cards" values (Stress / Fitness age / Vitality), surfaced on Today so the buried
    // Explore features sit on the home screen (#582). The same merged resolvedSeries reads their detail
    // screens use; null simply renders a dash on that card. Mirror the iOS Today lane's stressToday /
    // fitnessAgeToday / vitalityToday loads (last resolved value over all history). Loaded off the main
    // thread; re-read as the data grows.
    // #849: seed from the ViewModel cache so a re-mount restores the pinned-card numbers instead of flashing
    // dashes while the heavy history-wide read is (now) skipped for unchanged data.
    var stressToday by remember { mutableStateOf(viewModel.todayStressCache) }
    var fitnessAgeToday by remember { mutableStateOf(viewModel.todayFitnessAgeCache) }
    var vitalityToday by remember { mutableStateOf(viewModel.todayVitalityCache) }
    LaunchedEffect(days) {
        // #849 re-mount guard: skip the whole-history scan when `days` is content-identical to the last load
        // (data class hashCode is a stable structural signature). The marker + cached values live on the
        // long-lived ViewModel, so a tab-return / post-import re-mount restores the numbers without re-reading.
        val sig = days.hashCode()
        if (viewModel.todayCardsLoadedSig == sig) return@LaunchedEffect
        // Read each pinned card from the SAME source its own detail screen reads, the proven path that
        // already shows real numbers there (and the resolution iOS's exploreSeries uses). Stress is derived
        // from the imported strap data (StressScreen reads "my-whoop"); Fitness age + Vitality are
        // NOOP-COMPUTED weekly scores the IntelligenceEngine writes under "<activeStrapId>-noop". Read them
        // through the computed UNION (active strap's sibling + canonical "my-whoop-noop"), the same helper
        // HealthScreen uses — a hardcoded "my-whoop-noop" misses a live-BLE strap's "whoop-<mac>-noop" (#349).
        // Take the latest value (series are day-ascending), null → the card shows a dash, never a fabricated number.
        // #753: build the SAME StressModel the detail screen (StressScreen) shows and take `model.score`,
        // rather than the stress series' last banked row. StressModel.build prefers today's stored stress row
        // but otherwise DERIVES today's score from the live `days` RHR/HRV baseline; the old `.lastOrNull()`
        // read returned the latest *banked* day, so on a day with no stored stress row the pinned card sat on
        // yesterday's number (e.g. "2") while the detail page moved on. Reading the stored series the same way
        // StressScreen does (day → value, clamped 0–3) and feeding the same `days` ties the two together; both
        // recompute off `days`, so the pinned card stays in sync. null (no usable signal) keeps the honest
        // "Calibrating" placeholder, matching StressScreen's empty state.
        stressToday = runCatching {
            val stored = viewModel.repo.metricSeries("my-whoop", "stress", "0000-01-01", "9999-12-31")
                .associate { it.day to it.value.coerceIn(0.0, 3.0) }
            StressModel.build(days, stored)?.score
        }.getOrNull()
        fitnessAgeToday = runCatching {
            viewModel.repo.latestMetricComputedUnion(viewModel.activeStrapId, "fitness_age")?.value
        }.getOrNull()
        vitalityToday = runCatching {
            viewModel.repo.latestMetricComputedUnion(viewModel.activeStrapId, "vitality")?.value
        }.getOrNull()
        // Cache the computed triple + signature so a later re-mount with unchanged data restores them and
        // short-circuits the history-wide read above.
        viewModel.todayStressCache = stressToday
        viewModel.todayFitnessAgeCache = fitnessAgeToday
        viewModel.todayVitalityCache = vitalityToday
        viewModel.todayCardsLoadedSig = sig
    }

    // #713, strap battery runtime estimate ("~X left") for the Data-sources battery row. The battery lane
    // banks a SoC time series; here we read it and run the SHARED BatteryEstimator (the iOS twin computes the
    // same value off LiveState.batteryEstimate). Rated-life fallback is chosen by strap generation: WHOOP 5/MG
    // gets the ~12-day figure, WHOOP 4.0 the ~4.5-day one. Recomputed when the banked series grows (a new
    // reading lands ~every 8 min), when the link comes/goes, or when the strap generation resolves. Charging
    // hides it (no "X left" while topping up); a too-short discharge run returns null and the badge shows just
    // the %. Display rule: hours < 48 -> "~Nh left", else "~N days left"; null hides the estimate.
    var batteryEstimateText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(liveSnap.connected, liveSnap.batteryPct, liveSnap.whoop5, liveSnap.charging) {
        batteryEstimateText = if (!liveSnap.connected || liveSnap.charging == true) {
            null
        } else {
            runCatching {
                val now = System.currentTimeMillis() / 1000
                // A wide window: SoC readings are sparse (~8 min apart), so a few days back is plenty for the
                // estimator to find the trailing discharge run and still cheap to load.
                val from = now - 14L * 86_400
                val samples = viewModel.repo.batterySamples("my-whoop", from, now, limit = 2_000)
                    .mapNotNull { s -> s.soc?.let { s.ts to it } }
                val rated = if (liveSnap.whoop5) BatteryEstimator.ratedLifeHoursWhoop5
                            else BatteryEstimator.ratedLifeHoursWhoop4
                // Battery test mode (Test Centre #713): emit the discharge-run / fitted-slope / gate ANALYSIS
                // trace, not only the per-reading "bank soc=" line. This LaunchedEffect re-runs on a natural
                // throttle (battery% / connection / charging changes), never a tight loop, and reuses the
                // samples + rated just loaded, so there is no extra Room read. estimateTrace returns the SAME
                // Estimate the badge shows, so no displayed number changes. Gated zero-cost when the mode is off
                // (one SharedPreferences bool read) and routed to the .battery-tagged strap log via externalLog.
                if (com.noop.testcentre.TestCentre.from(context)
                        .active(com.noop.testcentre.TestDomain.BATTERY)) {
                    for (line in BatteryEstimator.estimateTrace(samples, rated).second) {
                        viewModel.ble.externalLog(line, com.noop.testcentre.TestDomain.BATTERY)
                    }
                }
                BatteryEstimator.estimate(samples, rated)?.let { est ->
                    val hours = est.hoursRemaining
                    if (!hours.isFinite() || hours <= 0.0) null
                    else if (hours < 48) "~${hours.roundToInt()}h left"
                    else {
                        val daysLeft = (hours / 24).roundToInt()
                        "~$daysLeft day${if (daysLeft == 1) "" else "s"} left"
                    }
                }
            }.getOrNull()
        }
    }

    // The latest active-energy figure (kcal) for the Calories card, the newest non-null activeKcal across
    // the Apple-side daily aggregates, mirroring the Today Calories tile. Null hides the card's value.
    var latestActiveKcal by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days) {
        latestActiveKcal = runCatching {
            (viewModel.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31") +
                viewModel.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31"))
                .filter { it.activeKcal != null }
                .maxByOrNull { it.day }
                ?.activeKcal
        }.getOrNull()
    }

    // HYDRATION (opt-in, default OFF), the Today "Hydration" card + its detail are hidden unless the user
    // turns Hydration tracking on in Settings. When on, the card reads today's logged total (ml, from the
    // local-only HydrationStore series) against the pure HydrationGoal (sex baseline + today's Effort bump).
    // Both are loaded off the main thread and re-read as the day's data grows; SharedPreferences isn't
    // reactive, so the toggle is read once into local state.
    val hydrationEnabled = remember { NoopPrefs.hydrationTracking(context) }
    var hydrationTotalMl by remember { mutableStateOf(0.0) }
    // #989: `days` only changes on a data refresh, which a hydration write never causes, so the card sat
    // stale after logging a drink until an unrelated sync landed. Keying on the store's mutationSeq too
    // re-reads the one metric row the moment a drink is logged / edited / deleted. Mirrors the iOS
    // Repository.hydrationSeq trigger.
    val hydrationSeq by HydrationStore.mutationSeq.collectAsStateWithLifecycle()
    LaunchedEffect(days, hydrationEnabled, hydrationSeq) {
        hydrationTotalMl = if (hydrationEnabled) {
            runCatching { HydrationStore.total(viewModel.repo) }.getOrDefault(0.0)
        } else 0.0
    }
    // The day's Effort/strain (0..100) drives the goal's effort bump. Prefer the live in-progress Effort
    // for today (floored at the stored value, mirroring the Effort gauge) so the goal reflects a hard day
    // as it accrues; null leaves the bump at 0. Computed below where liveTodayStrain is in scope.
    val hydrationGoalMl = remember(displayMetric, profileStore) {
        if (!hydrationEnabled) 0 else HydrationGoal.dailyGoalMl(profileStore.sex, displayMetric?.strain)
    }

    // A1 (#514/#706): the Charge breakdown sheet, opened by tapping the hero Charge ring. Hosts the
    // existing RecoveryDriversSection (gated to the calibration countdown when the night can't score) plus
    // the folded Readiness card (S4). Not persisted, so it reopens closed. Mirrors iOS showChargeBreakdown.
    // LIVE SESSIONS (beta, default ON): the "Start session" entry under the hero + its full-screen Dialog
    // (the same presentation the live-workout overlay / Charge breakdown use — deliberately NOT a nav
    // destination, so dismissing it leaves the session's runner coaching and this entry is the way back
    // in). Gated on the Settings `live_sessions_beta` flag; SharedPreferences isn't reactive, so it's read
    // once into local state like the hydration/day-cycle gates above. The ACTIVE runner is also collected
    // here (null ↔ runner only — the per-second snapshot is scoped inside the entry card) so a running
    // session keeps its way-back-in card even if the beta flag was just switched off.
    var showLiveSession by remember { mutableStateOf(false) }
    val liveSessionsEnabled = remember { LiveSessionPrefs.enabled(context) }
    val activeLiveSession by LiveSessionRunner.active.collectAsStateWithLifecycle()
    // S4: the Synthesis card collapses to a one-liner that expands on tap (default collapsed). Mirrors iOS.
    var synthesisExpanded by remember { mutableStateOf(false) }
    // S5: the Key Metrics grid caps at the first METRICS_COLLAPSED_CAP tiles behind a "Show all metrics"
    // expander, and the Data Sources footer collapses to a single "Synced from: ..." line. Both default
    // collapsed and are NOT persisted, so the home screen reopens compact. Mirrors iOS.
    var metricsExpanded by remember { mutableStateOf(false) }
    var sourcesExpanded by remember { mutableStateOf(false) }
    // Per-card "dismissed into the inbox" flags for the two Today info-cards. A small × on each card
    // sets these (and posts a `.dismissedCard` update); "Restore to Today" in the inbox flips them back
    // via the shared TodayCardDismissal key. Read once (SharedPreferences isn't reactive), driven locally.
    var scoresBuildingDismissed by remember {
        mutableStateOf(TodayCardDismissal.isDismissed(context, CARD_SCORES_BUILDING))
    }
    var newHereDismissed by remember {
        mutableStateOf(TodayCardDismissal.isDismissed(context, CARD_NEW_HERE))
    }
    // #827: the calibrating note's own dismissed flag, read once from the same shared store.
    var calibratingDismissed by remember {
        mutableStateOf(TodayCardDismissal.isDismissed(context, CARD_CALIBRATING))
    }
    // The calibration-milestone countdown stack's dismissed flag, read once from the same shared store.
    var milestonesDismissed by remember {
        mutableStateOf(TodayCardDismissal.isDismissed(context, CARD_CALIBRATION_MILESTONES))
    }
    // The carried "Latest sleep · <date>" note's dismissed flag (iOS has no such card; on Android it's
    // dismissible so it doesn't sit permanently above the hero and break the compact look). Read once.
    var carriedSleepDismissed by remember {
        mutableStateOf(TodayCardDismissal.isDismissed(context, CARD_CARRIED_SLEEP))
    }
    // Dismiss a Today info-card INTO the inbox: persist its flag, hide it, and post a restorable
    // `.dismissedCard` update carrying the card id. Mirrors the iOS `dismissTodayCard`.
    val dismissTodayCard: (String, String, String) -> Unit = { id, title, message ->
        TodayCardDismissal.setDismissed(context, id, true)
        when (id) {
            CARD_SCORES_BUILDING -> scoresBuildingDismissed = true
            CARD_NEW_HERE -> newHereDismissed = true
            CARD_CALIBRATING -> calibratingDismissed = true
            CARD_CALIBRATION_MILESTONES -> milestonesDismissed = true
            CARD_CARRIED_SLEEP -> carriedSleepDismissed = true
        }
        updateStore?.post(
            UpdateItem(
                kind = UpdateKind.DISMISSED_CARD,
                title = title,
                message = message,
                restorePayload = id,
            ),
        )
    }
    // Honour a "Restore to Today" tap from the inbox: flip the matching dismissed flag back so the card
    // reappears (the inbox also cleared the shared pref directly, but this re-reads it into local state
    // for an already-mounted Today). Cleared once handled. Mirrors the iOS restoreRequest observer.
    val restoreSignal = updateStore?.restoreRequest
    LaunchedEffect(restoreSignal) {
        if (updateStore != null && restoreSignal != null) {
            when (restoreSignal) {
                CARD_SCORES_BUILDING -> scoresBuildingDismissed = false
                CARD_NEW_HERE -> newHereDismissed = false
                CARD_CALIBRATING -> calibratingDismissed = false
                CARD_CALIBRATION_MILESTONES -> milestonesDismissed = false
                CARD_CARRIED_SLEEP -> carriedSleepDismissed = false
            }
            updateStore.restoreRequest = null
        }
    }

    // Announce NEW history to the inbox only when the NEWEST day-key (max yyyy-MM-dd) moves strictly
    // forward, not on a count change (#521). A background recompute rebuilds the window via
    // delete-then-reinsert, so the count momentarily dips and recovers while the newest key is unchanged
    //, keying off the count mistook that churn for new history and re-posted "New data added" on a
    // loop. The baseline is PERSISTED in SharedPreferences (not `remember`), so a relaunch over the same
    // history never re-announces. Empty baseline = first sight → record silently, never announce
    // historical data. The "added" count is the distinct days strictly above the old watermark, real,
    // never fabricated. Deep-links to Trends. Mirrors the Swift `announceNewDaysIfNeeded`.
    LaunchedEffect(days, updateStore) {
        val store = updateStore ?: return@LaunchedEffect
        val newestKey = days.maxOfOrNull { it.day } ?: return@LaunchedEffect   // no history yet
        val previousKey = NewDataWatermark.lastAnnouncedKey(context)
        NewDataWatermark.setLastAnnouncedKey(context, newestKey)
        if (previousKey.isEmpty()) return@LaunchedEffect            // first sight → silent baseline
        if (newestKey <= previousKey) return@LaunchedEffect         // recompute churn, not new history
        val added = days.map { it.day }.toSet().count { it > previousKey }
        if (added <= 0) return@LaunchedEffect
        val daysWord = if (added == 1) "day" else "days"
        store.post(
            UpdateItem(
                kind = UpdateKind.READING,
                title = "New data added",
                message = "$added new $daysWord of history is ready in Trends.",
                deepLink = "trends",
            ),
        )
    }

    // The newest Apple Health / Health Connect body weight, loaded off the main thread. Null until the
    // load runs or when neither source carries a weight, the Weight tile then falls back to the profile.
    var weightKg by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days) {
        weightKg = latestWeightKg(
            viewModel.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31"),
            viewModel.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31"),
        )
    }

    // Steps for the selected day from imported Apple Health / Health Connect data, the Today Steps
    // tile's fallback when the strap itself didn't bank an on-device count. A WHOOP 4.0 DOES count
    // steps (in the official WHOOP app), but NOOP can't yet read them off the strap over Bluetooth, so
    // on a 4.0 the tile shows your imported steps instead of "No Data". Reloads as the day selector
    // moves. On-device WHOOP 5/MG steps still take precedence. (#150)
    var importedStepsForDay by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(days, selectedDayKey) {
        // Today's steps keep moving after the manual one-shot HC import, so the stored row goes
        // stale within minutes, top it up with ONE live StepsRecord read before the stored-row
        // read below. Best-effort: any HC hiccup just falls through to whatever is stored. (#150)
        if (selectedDayOffset == 0) {
            try {
                HealthConnectImporter.refreshTodaySteps(context, viewModel.repo)
            } catch (_: Exception) { /* best-effort */ }
        }
        importedStepsForDay = stepsForDay(
            viewModel.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31"),
            viewModel.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31"),
            selectedDayKey,
        )
    }

    // On-device steps ESTIMATE for the selected day (key "steps_est", computed "-noop" source). The
    // Steps tile prefers a REAL step count (strap @57 counter / imported Health Connect); only when a
    // day has NEITHER does it fall back to this estimate, shown with an "est." caption so it's never read
    // as a measured count. resolvedSeries reads the computed source for the my-whoop key, exactly like
    // the Explore "steps_est" metric. Null until loaded / no estimate for the day. (#150)
    var stepsEstForDay by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(days, selectedDayKey) {
        val byDay = runCatching {
            viewModel.repo.resolvedSeries("steps_est", "my-whoop", "0000-00-00", "9999-99-99",
                strapDeviceId = viewModel.activeStrapId)
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())
        stepsEstForDay = byDay[selectedDayKey]?.let { Math.round(it).toInt() }
    }

    // The Rest SCORE (0–100) for the selected day, IntelligenceEngine's Rest composite, written to the
    // `sleep_performance` metric series. The Key-Metrics "Rest" tile shows THIS, with hours-in-bed kept
    // as the caption; the tile previously showed hours where the score belonged (#248). resolvedSeries
    // merges imported + computed sleep_performance (imported-wins), so an importer sees the export's
    // figure and a Bluetooth-only user sees the on-device composite. Null until loaded / no night yet.
    var restScoreForDay by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days, selectedDayKey, selectedDayOffset) {
        val byDay = runCatching {
            viewModel.repo.resolvedSeries("sleep_performance", "my-whoop", "0000-00-00", "9999-99-99",
                strapDeviceId = viewModel.activeStrapId)
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())
        // #977: the tail-fallback (latest scored night) is now freshness-gated. A live 5.0 whose sleep never
        // scores used to pin Rest to the weeks-old series tail forever while Charge advanced; if that tail is
        // stale, fall through to null so the Rest ring shows its needs-a-tracked-night state instead of a
        // frozen number. `selectedDayKey` is today's key at offset 0, so it anchors the freshness check.
        val latest = byDay.entries.maxByOrNull { it.key }
        restScoreForDay = freshRestScore(
            todayValue = byDay[selectedDayKey], lastDay = latest?.key, lastValue = latest?.value,
            isTodaySelected = selectedDayOffset == 0, today = selectedDayKey)
    }

    // Provenance (COMPONENT 4): the REAL per-metric merge winner for the selected day's three hero scores,
    // keyed by metric key ("recovery" / "strain" / "sleep_performance"); each value is the RAW source id the resolver
    // returned (e.g. "my-whoop", "my-whoop-noop", "apple-health"). resolvedSeries applies the SAME
    // imported-WHOOP > NOOP-computed > Apple-Health precedence the dashboard merge uses field-by-field
    // (WhoopRepository.mergeDaily), so the card-level badge names the sources that ACTUALLY supplied
    // that day's scores rather than making a blanket day-level claim. Mirrors the Swift Today lane's
    // `provenanceByMetric` resolution exactly (the winner is the last resolved point on selectedDayKey).
    var provenanceByMetric by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(days, selectedDayKey, viewModel.activeStrapId) {
        val resolved = mutableMapOf<String, String>()
        for (key in listOf("recovery", "strain", "sleep_performance")) {
            val win = runCatching {
                viewModel.repo.resolvedSeries(key, "my-whoop", selectedDayKey, selectedDayKey,
                    strapDeviceId = viewModel.activeStrapId)
                    .points.lastOrNull { it.day == selectedDayKey }?.source
            }.getOrNull()
            if (win != null) resolved[key] = win
        }
        provenanceByMetric = resolved
    }

    // LIVE in-progress Effort for TODAY (#402), mirrors the iOS TodayView live-Effort fix. The stored
    // `day?.strain` lags: early in the day it shows yesterday's completed Effort (or a stale 0.0) until the
    // heavy daily pass re-scores. So for offset 0 only, integrate today's raw HR over the SAME window the
    // HR trend uses (the logical day's local-midnight → now) through StrainScorer with the SAME params the
    // daily pass persists (Tanaka HR-max from age, or the manual override, the day's resting HR else the
    // default, profile sex), and prefer it on the Effort gauge. StrainScorer returns null below
    // `minReadings`, so before there's enough HR the gauge falls back to the stored value and never shows a
    // fabricated number. Any past day → null (the gauge uses the stored strain). Keyed on the same inputs
    // as the day-scoped loads so it reloads as the selector moves and as a sync/import grows the HR window.
    var liveTodayStrain by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(days, selectedDayKey, selectedDayOffset) {
        liveTodayStrain = if (selectedDayOffset == 0) {
            val zone = ZoneId.systemDefault()
            val start = selectedDay.atStartOfDay(zone).toEpochSecond()
            val now = System.currentTimeMillis() / 1000
            // #908: read the active strap ∪ canonical "my-whoop" union, NOT a hardcoded "my-whoop". A strap
            // re-added through the device manager banks its live HR under its own fresh id, so a pinned
            // "my-whoop" read returned nothing and Effort integrated to 0 off an empty series. Single-WHOOP
            // install resolves to "my-whoop" ⇒ one id ⇒ byte-identical read.
            val todayHr = runCatching { viewModel.repo.hrSamplesUnion(viewModel.activeStrapId, start, now) }
                .getOrDefault(emptyList())
            // effMaxHR resolution matches AnalyticsEngine: manual HR-max override first, else Tanaka from age.
            val effMaxHR = profileStore.hrMaxOverride.takeIf { it > 0 }?.toDouble()
                ?: if (profileStore.age > 0) StrainScorer.tanakaHRmax(profileStore.age.toDouble()) else null
            StrainScorer.strain(
                hr = todayHr,
                maxHR = effMaxHR,
                restingHR = displayMetric?.restingHr?.toDouble() ?: StrainScorer.defaultRestingHR,
                sex = profileStore.sex,
            )
        } else {
            null
        }
    }

    // Recovery cold-start: recovery is null until the HRV baseline crosses the seed gate
    // (Baselines.minNightsSeed valid nights). Show honest "calibrating, N of 4 nights" progress
    // instead of a bare "No Data" so a new BLE-only user knows scores are coming, not broken. (PR #85)
    val recoveryCalibration: Int? = if (selectedDayOffset == 0) {
        // Thread the persisted "Recalibrate HRV baseline" epoch (0 = none) so N folds the SAME
        // epoch-aware history the recovery engine folds — otherwise a post-recalibration user's pre-epoch
        // nights inflate the count past the seed gate and the score side wrongly reads NeedsStrap (Bug B).
        val hrvEpoch = NoopPrefs.of(context).getLong(Baselines.hrvBaselineEpochKey, 0L).toDouble()
        recoveryCalibrationNights(days, displayMetric?.recovery != null, hrvEpoch)
    } else {
        null
    }

    // Calibration-milestone gamification: the UNCAPPED lifetime banked-night tally (unlike
    // recoveryCalibration, which nulls out past the 4-night seed) drives the WHOOP-style countdown cards
    // (First Recovery 4 → Sleep 7 → Trusted 14 → 30-day baseline). NB post-#449: the seed-gate "Calibrating
    // N of 4" now reads the epoch-aware baseline nValid, so after a manual HRV recalibration these two can
    // legitimately differ — this milestone card is the lifetime achievement tally by design (it does not
    // reset on recalibration). Today only, and only while at least one milestone is still unreached;
    // presentation-only — the targets never touch the Baselines math.
    val calibrationMilestones: List<CalibrationMilestones.Progress>? =
        if (selectedDayOffset == 0) {
            val banked = RecoveryScorer.bankedNights(days.map { it.avgHrv })
            if (CalibrationMilestones.isCalibrating(banked)) CalibrationMilestones.progress(banked) else null
        } else {
            null
        }

    // The most recent fully-SCORED recovery day to carry over on TODAY while tonight's recovery hasn't
    // been scored yet (#543). Right after the logical-day rollover the new day has no recovery (the new
    // night isn't scored until you wear it tonight), so a baseline-established user, past calibration, so
    // recoveryCalibration is null, saw the WHOLE recovery side blank ("No Data" Charge AND blank HRV /
    // resting-HR / respiratory / SpO₂ tiles + Synthesis + Contributors) while live HR kept ticking, which
    // reads as broken. This is the ONE prior row every recovery-derived read-out carries over from, the
    // way WHOOP keeps showing last recovery until the new one lands, it NEVER fabricates a number for the
    // new day, each carried read shows the REAL prior value labelled as prior, and any metric the prior
    // row genuinely lacks still falls through to "No Data". Non-null only when: it's today, today has no
    // recovery, and we're not mid-calibration (calibration owns its own copy). days is oldest→newest;
    // exclude the (still-null) today key so we never echo "today". Mirrors iOS lastScoredRecoveryDay.
    // #547 carry-over upper bound: the LATER of the logical "today" (rolls at 04:00) and the local
    // calendar day. Using the later key means a legitimate just-after-midnight carry-over of yesterday's
    // logical day is NOT dropped, while any FUTURE-dated row (a bad strap clock) still sorts past it and
    // is excluded. ISO date strings compare chronologically.
    val carryOverTodayKey = remember(todayDate) {
        maxOf(todayDate.toString(), java.time.LocalDate.now().toString())
    }
    val lastScoredRecoveryDay: DailyMetric? = remember(days, selectedDayKey, recoveryCalibration, selectedDayOffset, displayMetric, carryOverTodayKey) {
        lastScoredRecoveryDay(
            days = days,
            selectedDayKey = selectedDayKey,
            isToday = selectedDayOffset == 0,
            todayScored = displayMetric?.recovery != null,
            isCalibrating = recoveryCalibration != null,
            today = carryOverTodayKey,
        )
    }
    // The freshest STRICTLY-PRIOR night carrying a real overnight VITAL (HRV / resting-HR / respiratory),
    // recovery-INDEPENDENT (#543 follow-up). HRV/RHR/resp exist without a recovery score, so a post-update
    // re-analysis that nulls last night's recovery while preserving its avgHrv/restingHr must NOT fall back
    // to an OLDER recovery-scored day for the vitals (that's the tile-vs-card mismatch: the per-field tiles
    // already keep last night's real value; the whole-row card was discarding it). The vitals read PER-FIELD
    // today-first with THIS carry as the fallback, kept separate from lastScoredRecoveryDay (Charge ring /
    // Synthesis / Contributors / Readiness stay recovery-gated). Future-clock-safe: the upper bound is the
    // LATER of the resolved today row's own key and carryOverTodayKey, mirroring lastScoredRecoveryDay's
    // #547 guard. Non-null only on today (offset 0). Mirrors iOS Repository.lastVitalsDay.
    val lastVitalsDay: DailyMetric? = remember(days, carryOverTodayKey, selectedDayOffset, displayMetric) {
        if (selectedDayOffset == 0) lastVitalsRow(days, maxOf(displayMetric?.day ?: "", carryOverTodayKey)) else null
    }
    // PER-FIELD SpO₂ / skin-temp carries, the twin of lastVitalsDay for the two fields its predicate does
    // NOT check. The on-device engine writes spo2Pct = null (only raw spo2Red/spo2Ir), so every computed
    // "-noop" row lacks a percentage; only imported rows carry one. A whole-row carry (lastScoredRecoveryDay
    // or lastVitalsDay) therefore lands on a row with null spo2Pct/skinTempDevC and the Blood Oxygen /
    // Skin Temp cards read "No Data" even though an imported row holds a real reading. Resolving the two
    // fields independently (last strictly-prior row with the field non-null) mirrors iOS
    // TodayView.lastSpo2Day / lastSkinTempDay. Same #547 future-clock bound; non-null only on today.
    val lastSpo2Day: DailyMetric? = remember(days, carryOverTodayKey, selectedDayOffset, displayMetric) {
        if (selectedDayOffset == 0) lastSpo2Row(days, maxOf(displayMetric?.day ?: "", carryOverTodayKey)) else null
    }
    val lastSkinTempDay: DailyMetric? = remember(days, carryOverTodayKey, selectedDayOffset, displayMetric) {
        if (selectedDayOffset == 0) lastSkinTempRow(days, maxOf(displayMetric?.day ?: "", carryOverTodayKey)) else null
    }
    // Carry-over Charge for TODAY, the prior scored row's recovery + its "Last night · <date>" caption.
    // Derived from lastScoredRecoveryDay so Charge and every other recovery tile carry the SAME prior day.
    val lastScoredCharge: LastCharge? = remember(lastScoredRecoveryDay) {
        lastScoredRecoveryDay?.let { prior ->
            prior.recovery?.let { LastCharge(it, carriedCaption(prior.day, carryOverTodayKey)) }
        }
    }
    var carriedRecoverySource by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastScoredRecoveryDay?.day, viewModel.activeStrapId) {
        val carriedDay = lastScoredRecoveryDay?.day
        carriedRecoverySource = if (carriedDay == null) {
            null
        } else {
            runCatching {
                viewModel.repo.resolvedSeries("recovery", "my-whoop", carriedDay, carriedDay,
                    strapDeviceId = viewModel.activeStrapId)
                    .points.lastOrNull { it.day == carriedDay }
                    ?.source
            }.getOrNull()
        }
    }

    // Explainability (COMPONENT 2): the honest state of the score side for TODAY, scored / calibrating /
    // carried-last-night / needs-strap. One state, never a bare blank, and never a fabricated number. Only
    // computed for today (offset 0); a past day shows its own row, not a "needs the strap" prompt.
    val scoreState: ScoreState = remember(displayMetric, recoveryCalibration, lastScoredRecoveryDay, selectedDayOffset, carryOverTodayKey) {
        if (selectedDayOffset == 0) {
            scoreStateForToday(
                todayRecovery = displayMetric?.recovery,
                calibratingNights = recoveryCalibration,
                carriedDay = lastScoredRecoveryDay,
                today = carryOverTodayKey,
            )
        } else {
            ScoreState.Scored(displayMetric?.recovery ?: 0.0)
        }
    }

    // One honest card-level badge, matching LiquidTodayView: identical winners collapse to one label;
    // mixed winners show at most two sources in Charge / Effort / Rest order so the pill stays compact.
    val heroSourceLabel = remember(provenanceByMetric, carriedRecoverySource, displayMetric?.recovery, lastScoredCharge, viewModel.activeStrapId) {
        scoreHeroSourceLabel(
            provenanceByMetric = provenanceByMetric,
            carriedRecoverySource = carriedRecoverySource,
            usesCarriedRecovery = displayMetric?.recovery == null && lastScoredCharge != null,
            deviceId = viewModel.activeStrapId,
        )
    }

    LaunchedEffect(days) {
        // #849: this footer pass is the heavy one. It derives HR per imported workout from raw strap samples
        // (fillWorkoutHrFromStrap = potentially hundreds of raw-HR reads) and counts every workout / Apple /
        // Health-Connect row across ALL history. A bare Today re-mount (tab-away + return, or an Apple-Health
        // import that recreates the screen) re-fires this LaunchedEffect with the screen's `remember` state
        // reset, so it re-ran the full pass for byte-identical data every time: the lag users see returning
        // to Today after an import. The signature is `days` (a `data class` list, so its structural
        // hashCode is a stable content signature) PLUS the 14-day cross-source workout union: `days`
        // alone missed workouts imported without touching the Whoop day summaries (e.g. a Health
        // Connect session recorded today), so the "Last Workouts" feed stayed stale until the next
        // Whoop cycle bumped `days`. The union is a cheap windowed SELECT; only the heavy strap-HR
        // derivation and all-history counts below are skipped on a signature match. The marker lives
        // on the long-lived ViewModel, so it survives the re-mount that reset the screen state. A real
        // data change bumps the signature and re-runs, so no real update is dropped.
        val now = System.currentTimeMillis() / 1000
        val recentCutoff = LocalDate.now()
            .minusDays(13)
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
        val recentUnion = viewModel.repo.workoutsAllSources(viewModel.deviceId, recentCutoff, now)
            .sortedByDescending { it.startTs }
        val sig = 31 * days.hashCode() + recentUnion.hashCode()
        if (viewModel.todayFooterLoadedSig == sig) return@LaunchedEffect
        // Union of the active strap id + legacy "my-whoop" (#814), NOT the literal id alone: after a
        // re-pair the fresh recordings live under "whoop-<id>", and a pinned read undercounted them
        // in the Whoop pill exactly like the feed dropped them from "Latest Workouts".
        val whoopWorkouts = viewModel.repo.workoutsUnion(viewModel.deviceId, 0L, now)
        // Apple Health and Health Connect are separate sources (since #34), keep them separate in the
        // provenance footer too, so Health Connect data isn't mislabelled under the "Apple Health" pill
        // (issue #53). The recent-workouts list below still unions all sources for a combined feed.
        val appleWorkouts = viewModel.repo.workouts("apple-health", 0L, now)
        val hcWorkouts = viewModel.repo.workouts("health-connect", 0L, now)
        val appleDaysCount = viewModel.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31").size
        val hcDaysCount = viewModel.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31").size
        footer = TodayFooterState(
            // fillWorkoutHrFromStrap: imported sessions carry no HR, derive it from strap samples (#77).
            recentWorkouts = viewModel.repo.fillWorkoutHrFromStrap(recentUnion),
            whoopDays = days.size,
            whoopWorkouts = whoopWorkouts.size,
            appleDays = appleDaysCount,
            appleWorkouts = appleWorkouts.size,
            hcDays = hcDaysCount,
            hcWorkouts = hcWorkouts.size,
        )
        // Cache the result + record the signature so a later re-mount with unchanged data restores the footer
        // and short-circuits the heavy reload above.
        viewModel.todayFooterCache = footer
        viewModel.todayFooterLoadedSig = sig
    }

    // #817 - horizontal swipe to change day, alongside the header chevrons. `detectHorizontalDragGestures`
    // only claims HORIZONTAL drags, so the LazyColumn keeps its vertical scroll; we accumulate the drag and
    // resolve the day ONCE on lift via the pure `dayNavSwipeTarget` (rightward = older, leftward = newer,
    // clamped at today). The threshold is density-scaled from DAY_NAV_SWIPE_THRESHOLD_DP so a small wobble
    // during a scroll doesn't flip the day. Mirrors the iOS day-nav swipe lane.
    val swipeThresholdPx = with(LocalDensity.current) { DAY_NAV_SWIPE_THRESHOLD_DP.dp.toPx() }
    val daySwipeModifier = Modifier.pointerInput(Unit) {
        var accumulatedX = 0f
        detectHorizontalDragGestures(
            onDragStart = { accumulatedX = 0f },
            onDragEnd = {
                selectedDayOffset = dayNavSwipeTarget(selectedDayOffset, accumulatedX, swipeThresholdPx)
            },
            onHorizontalDrag = { _, dragAmount -> accumulatedX += dragAmount },
        )
    }

    LazyScreenScaffold(
        modifier = daySwipeModifier,
        // title = null suppresses the big scaffold header (the nullable-title path); the compact
        // WHOOP-style top bar below replaces it, mirroring the iOS Today screen (todayTopBar).
        title = null,
        // Tighten the top inset now the big title is gone (Compose forbids negative padding, so this
        // expresses iOS's `.padding(top: -16)` as a smaller scaffold top padding).
        topPadding = 12.dp,
        // FIX 6 (compactness): the liquid Today matches the iOS Today's tight `VStack(spacing: 12)` section
        // rhythm rather than the app-wide 20dp row gap, so the whole screen reads as compact/slick as iOS.
        // Scoped to this scaffold — no other screen's rhythm changes.
        rowSpacing = 12.dp,
        // The day-of-sky hue backdrop was removed; Today sits on the plain themed surface canvas
        // (topBackground null → the scaffold paints Palette.surfaceBase).
        topBackground = null,
        fullBleedBackground = false,
    ) {
        item {
        // LIQUID Today header (iOS LiquidTodayView.scene parity), a full structural rebuild to mirror the
        // iOS liquid Today element-for-element (NOT the old numeric-date + recording-light + bell header):
        //   LEFT  — a tappable title block: the big rounded-bold day title ("Today" / "Yesterday" / the
        //           weekday) over a human date line ("Friday, 3 July"). Tap opens the day picker.
        //   RIGHT — exactly the iOS four controls, in order: a filled HEART (→ Support), the PROFILE
        //           AVATAR (→ Settings), a "+" ADD button (→ quick actions), and the strap BATTERY RING.
        // The recording-status light and the notifications BELL are GONE from the header (iOS has neither);
        // the Updates inbox is relocated into the "+" quick-actions sheet (AppRoot), so the feature stays one
        // tap away without sitting in the Today header. Staggered in as the first section (index 0).
        val dayTitle = when (selectedDayOffset) {
            0 -> "Today"
            1 -> "Yesterday"
            else -> {
                val keyDate = runCatching { LocalDate.parse(selectedDayKey) }.getOrNull() ?: selectedDay
                keyDate.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))
            }
        }
        // Human date line under the title — "Friday, 3 July" (weekday + day + month), NOT a numeric date.
        // Dated by the row ACTUALLY on screen (selectedDayKey follows the resolver at offset 0), matching
        // the iOS `dateLine` (EEEE, d MMMM). Mirrors iOS's date-under-title block.
        val humanDate = run {
            val keyDate = runCatching { LocalDate.parse(selectedDayKey) }.getOrNull() ?: selectedDay
            keyDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.US))
        }
        Box(modifier = Modifier.fillMaxWidth().staggeredAppear(0)) {
            LiquidTodayHeader(
                dayTitle = dayTitle,
                humanDate = humanDate,
                selectedDay = selectedDay,
                batteryPct = if (liveSnap.connected) liveSnap.batteryPct else null,
                backfilling = liveSnap.backfilling,
                syncChunksThisSession = liveSnap.syncChunksThisSession,
                lastSyncAt = liveSnap.lastSyncAt,
                historySyncExperimental = liveSnap.historySyncExperimental,
                onPickDay = { offset -> selectedDayOffset = offset },
                onQuickActions = onQuickActions,
                onOpenProfile = onOpenProfile,
                onOpenDevices = onOpenDevices,
            )
        }
        }

        // WORDMARK, a subtle centred "N O O P" on the sky between the header and the hero (iOS LiquidWordmark
        // parity). White @ ~50% opacity, letter-spaced, perfectly centred; a tap plays a small random wiggle
        // easter egg. The old Android Today had NO wordmark; this adds it. Staggered in just after the header.
        item {
            Box(modifier = Modifier.fillMaxWidth().staggeredAppear(0)) {
                LiquidWordmark()
            }
        }

        // A "workout in progress" indicator whenever a manual workout is active (iOS parity: the Today
        // ActiveWorkoutIndicator). A tap routes to Live and re-opens the in-exercise overlay. Gated purely on
        // `activeWorkout`, so it auto-appears/clears with no extra lifecycle wiring. Its per-second clock
        // ticks inside the card's own LaunchedEffect, never recomposing the Today body.
        activeWorkout?.let { w ->
            item {
                WorkoutInProgressCard(workout = w, onReturn = onOpenActiveWorkout)
            }
        }

        // Design Reset (iOS parity): the "New here?" first-run card is off the Today dashboard for the
        // clean look, the scoring guide stays reachable from the i on each score and in Settings.

        // When there is no daily score yet (today's recovery is null / no history),
        // lead with the "live now, history one import away" note so the empty tiles
        // below are explained rather than just dashed out. A small × dismisses it INTO
        // the Updates inbox (restorable from there). Only anchored to today (offset 0).
        if (displayMetric?.recovery == null) {
            item {
            // While the strap is mid-offload, say so, empty tiles read as final otherwise (#77).
            if (liveSnap.backfilling) SyncingHistoryNote(chunks = liveSnap.syncChunksThisSession)
            // Explained score state (COMPONENT 2): when there's no own number to show, say WHY and WHAT to
            // do. "Calibrating" (N more nights, no fake number), "Last night · <date>" (#802 carry-over)
            // or "Needs the strap" (no data overnight). The carried Charge now draws a dimmed filled ring on
            // the hero with NO in-ring caption, so its "Last night ..." note renders BELOW the rings here,
            // matching iOS explainedScoreNote. Today only; never a fabricated value.
            //
            // #827: NeedsStrap ALWAYS shows (a today-blocking state, not a recurring nag).
            if (selectedDayOffset == 0 && scoreState is ScoreState.NeedsStrap) {
                ScoreStateNote(scoreState)
            }
            // The carried "Latest sleep · <date>" / "Last night · <date>" note. iOS has NOTHING in this slot,
            // and the maintainer flagged it as breaking the compact liquid look sitting permanently above the
            // hero. So on Android it's dismissible-into-the-inbox (restorable) like the calibrating note: a
            // small × tucks it into Updates so it isn't a fixed fixture between the header and the hero.
            if (selectedDayOffset == 0 && scoreState is ScoreState.CarriedLastNight && !carriedSleepDismissed) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    ScoreStateNote(scoreState)
                    if (updateStore != null) {
                        TodayCardDismissButton(
                            modifier = Modifier.align(Alignment.TopEnd),
                            onClick = {
                                dismissTodayCard(
                                    CARD_CARRIED_SLEEP,
                                    scoreState.title,
                                    scoreState.detail,
                                )
                            },
                        )
                    }
                }
            }
            // #827: the dismissible calibrating note. Hidden once dismissed into the inbox; a "Restore to
            // Today" tap there flips calibratingDismissed back via the shared restore path above.
            if (selectedDayOffset == 0 && scoreState is ScoreState.Calibrating && !calibratingDismissed) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    ScoreStateNote(scoreState)
                    if (updateStore != null) {
                        TodayCardDismissButton(
                            modifier = Modifier.align(Alignment.TopEnd),
                            onClick = {
                                dismissTodayCard(
                                    CARD_CALIBRATING,
                                    "Building your baseline",
                                    "Charge, Effort and Rest become personal after a few nights of wear.",
                                )
                            },
                        )
                    }
                }
            }
            if (selectedDayOffset != 0 || !scoresBuildingDismissed) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    DataPendingNote(
                        title = "Live now. Your scores are building.",
                        body = "Your live heart rate is working from the strap, and recovery, strain " +
                            "and sleep build from it over your next few nights of wear, sharpening as it " +
                            "learns your baseline. Want your full history instantly? Import your WHOOP " +
                            "export in Data Sources and it backfills in about a minute.",
                    )
                    // The × is only meaningful for today's card (a past day's note isn't dismissed).
                    if (selectedDayOffset == 0 && updateStore != null) {
                        TodayCardDismissButton(
                            modifier = Modifier.align(Alignment.TopEnd),
                            onClick = {
                                dismissTodayCard(
                                    CARD_SCORES_BUILDING,
                                    "Live now. Your scores are building.",
                                    "Charge, Effort and Rest build over your next few nights of wear.",
                                )
                            },
                        )
                    }
                }
            }
            }
        }

        if (alert != null) item { IllnessBanner(alert!!) }

        // HERO, three equal Charge / Effort / Rest liquid vessels in the compact pinned-dark card used by
        // LiquidTodayView. Effort prefers today's live in-progress strain and falls back to the stored value
        // (#402); the single floating badge names the real score sources without consuming card spacing.
        // Staggered in as the score hero (index 1, after the header); each number counts up over its vessel.
        // The day-of-sky scene backdrop was removed: the scaffold's `topBackground` is null, so Today paints
        // the plain themed surface canvas (Palette.surfaceBase) and the hero rings float DIRECTLY on that
        // flat surface (no card-clipped scene of their own, no screen-level day-cycle scene).
        item {
        // The hero CARD: a translucent frosted fill (LIQUID_HERO_FILL — cream in light, near-black in dark)
        // that sits on the plain themed surface and keeps the arc rings + count-up numbers crisp; the card
        // does the contrast work. A rounded 26 corner + a faint white hairline give it the frosted-glass
        // edge of the iOS liquid heroCard (stroke white@0.11). Mirrors the iOS LiquidTodayView heroCard.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // The shaped background stays clipped, while the source badge may straddle its top edge.
                .background(
                    LIQUID_HERO_FILL.copy(alpha = LIQUID_HERO_FILL.alpha * CardAppearance.opacity),
                    RoundedCornerShape(LIQUID_HERO_RADIUS),
                )
                .border(
                    1.dp,
                    (if (Palette.isLight) Palette.textPrimary else Color.White)
                        .copy(alpha = 0.11f * CardAppearance.opacity),
                    RoundedCornerShape(LIQUID_HERO_RADIUS),
                )
                .staggeredAppear(1),
        ) {
            ScoreHeroRow(
                day = displayMetric,
                restScore = restScoreForDay,
                recoveryCalibration = recoveryCalibration,
                lastScoredCharge = lastScoredCharge,
                effortScale = effortScale,
                liveTodayStrain = if (selectedDayOffset == 0) liveTodayStrain else null,
                heroSourceLabel = heroSourceLabel,
                onChargeTap = onOpenHealth,
                onEffortTap = onOpenWorkouts,
                onRestTap = onOpenSleep,
            )
        }
        }

        // CALIBRATION MILESTONES (gamification): the WHOOP-style countdown stack, directly under the hero
        // rings so a new user sees their (still-calibrating) score and then exactly how many nights until
        // each milestone unlocks. Today only, only while unreached, and dismissible into the inbox so it
        // never nags across the full 30-night run. Presentation-only; the Baselines math is untouched.
        if (calibrationMilestones != null && !milestonesDismissed) {
            item {
                Box(modifier = Modifier.fillMaxWidth().staggeredAppear(3)) {
                    CalibrationMilestonesCard(progress = calibrationMilestones)
                    if (updateStore != null) {
                        TodayCardDismissButton(
                            modifier = Modifier.align(Alignment.TopEnd),
                            onClick = {
                                dismissTodayCard(
                                    CARD_CALIBRATION_MILESTONES,
                                    "Calibration milestones",
                                    "Your countdown to personal Charge, Sleep and a full 30-day baseline.",
                                )
                            },
                        )
                    }
                }
            }
        }

        // LIVE SESSIONS (beta): the compact "Start session · BETA" entry, directly under the hero. Today
        // only (offset 0 — a session is a now-thing), gated on the Settings beta flag; a RUNNING session
        // keeps the card visible regardless (it is the designed way back into the dismissed session dialog,
        // see LiveSessionRunner's lifetime note). The card swaps itself to "Session running" / "Session
        // ended" (it scopes the runner's per-second snapshot internally, like WorkoutInProgressCard's clock).
        if (selectedDayOffset == 0 && (liveSessionsEnabled || activeLiveSession != null)) {
            item {
                LiveSessionEntryCard(
                    onOpen = {
                        // Only BEGIN when nothing is in flight: an active runner (running, or ended and
                        // holding its unseen summary) is simply re-presented, never displaced — so a tap
                        // can't silently discard a running session or a summary awaiting its "Done".
                        if (LiveSessionRunner.active.value == null) {
                            startOrResumeLiveSession(viewModel, context)
                        }
                        showLiveSession = true
                    },
                )
            }
        }

        // The plain-English read-out, the Charge-tinted Synthesis card with a WHITE headline, carries the
        // SOLID/CALIBRATING data-confidence pill in its top-right. Mirrors the iOS Synthesis
        // InsightCard. Carries the last scored day's read at the rollover (#543) so it doesn't blank to
        // "No Data". Staggered in as index 2.
        item {
        Box(modifier = Modifier.fillMaxWidth().staggeredAppear(2)) {
            SynthesisHeroCard(
                day = displayMetric,
                recoveryCalibration = recoveryCalibration,
                carriedDay = lastScoredRecoveryDay,
                days = days,
                synthesisExpanded = synthesisExpanded,
                onToggleSynthesis = { synthesisExpanded = !synthesisExpanded },
                onOpenReadiness = onOpenHealth,
            )
        }
        }

        // Provenance (COMPONENT 4) now rides UNDER each hero ring as a per-metric badge (Charge names the
        // recovery winner, Rest names the sleep_performance winner), resolved field-by-field per
        // WhoopRepository.mergeDaily, so an imported metric on an otherwise-computed day is labelled
        // honestly rather than under one blanket day-level deviceId. See ScoreHeroRow + HeroRingColumn.
        // Mirrors the iOS Today lane, which badges each ring's real winner and has no separate day badge.

        // Honest "why is Effort 0?" caption (#482/#480), only when today's Effort is a real
        // near-zero (HR present but never crossed the cardio zone), so a calm day reads as explained
        // rather than broken. Mirrors the iOS effortZeroNote. A low-HR day honestly earns ~0.
        // Effort accrues over a day and must never visibly drop: floor the in-progress value at the day's
        // already-earned strain (#489/#506). displayMetric for today is today's row or null, never a prior
        // day, so this can't resurrect a stale day, it only stops the gauge dropping below what's earned.
        item {
        val todayEffort = if (selectedDayOffset == 0) {
            val liveStrain = liveTodayStrain; val stored = displayMetric?.strain
            if (liveStrain != null && stored != null) maxOf(liveStrain, stored) else (liveStrain ?: stored)
        } else null
        if (todayEffort != null && todayEffort < 1.0) {
            Row(
                modifier = Modifier.padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = null,
                    tint = Palette.effortColor,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
                Text(
                    "No cardio load yet. Effort builds once your heart rate climbs into your effort " +
                        "zone (around 50% of your heart-rate reserve). A calm day honestly reads near zero.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
        }

        // A1/S4: the WHAT SHAPED IT breakdown, the Contributors bars and the READINESS card all folded into
        // the Charge-ring TAP (the showChargeBreakdown dialog below), collapsing the home screen. They are
        // NOT deleted, only moved behind a tap; a one-word readiness read (Push / Maintain / Rest, #205)
        // stays on the hero via SynthesisHeroCard. Mirrors the iOS chargeBreakdownSheet + readiness fold.

        // METRICS, uniform tile grid (two columns), each tile with a 14-day sparkline.
        // #765: no ad-hoc Spacer row before this header. The lone `selectorTopUp` spacer here (a device the
        // Health/Sleep screens use to tug a SEGMENTED SELECTOR up toward the section above) had no selector
        // to tug on Today; it just injected an extra gap that, on top of the scaffold's per-row spacing on
        // both sides of the spacer item, made the gap before Key Metrics visibly larger than every other
        // inter-card gap. Removing it lets Key Metrics sit on the SAME shared screenRowSpacing as the rest.
        // Section header + an Edit affordance to open the local layout editor (#251). No new nav
        // destination, a dialog over Today. The Box lets the SectionHeader keep its trailing label while
        // the Edit control sits to its right.
        item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                SectionHeader("Key Metrics", overline = dayLabel, trailing = "14-day trend")
            }
            TextButton(
                onClick = { showMetricsEditor = true },
                colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent),
            ) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = "Edit Key Metrics",
                    modifier = Modifier.size(Metrics.iconSmall),
                )
                Spacer(Modifier.width(4.dp))
                Text("Edit", style = NoopType.footnote)
            }
        }
        }
        // Key Metrics grid (3), Workouts (4), HR trend (5), vitals (6), Your Cards stagger in order.
        item {
        Box(modifier = Modifier.fillMaxWidth().staggeredAppear(3)) {
            MetricGrid(
                d = displayMetric,
                recoveryCalibration = recoveryCalibration,
                lastScoredCharge = lastScoredCharge,
                carriedDay = lastScoredRecoveryDay,
                spo2CarryDay = lastSpo2Day,
                unitSystem = unitSystem,
                effortScale = effortScale,
                latestWeightKg = weightKg,
                profileWeightKg = profileWeightKg,
                importedStepsForDay = importedStepsForDay,
                estimatedStepsForDay = stepsEstForDay,
                restScore = restScoreForDay,
                enabledMetrics = enabledKeyMetrics,
                metricsExpanded = metricsExpanded,
                onToggleMetrics = { metricsExpanded = !metricsExpanded },
            )
        }
        }
        item {
        // #991: same fix as the HR card — TodayWorkoutsSection emits header + card as two siblings, so a
        // Box overlaid them. Stack them in a spaced Column.
        Column(
            modifier = Modifier.fillMaxWidth().staggeredAppear(4),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TodayWorkoutsSection(footer.recentWorkouts)
        }
        }

        // HEART RATE, the live HR thread / trend card. Mirrors iOS heartRateSection below key metrics.
        // Carries its own live-HR thread + the banked 5-minute fallback + the "connect your strap" empty
        // state, all self-contained (its own data loads).
        item {
        // #991: HeartRateTrendCard emits its SectionHeader + card as two siblings; a Box overlaid them
        // (the header showed THROUGH the card in the v8 layout). A spaced Column stacks them instead.
        Column(
            modifier = Modifier.fillMaxWidth().staggeredAppear(5),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeartRateTrendCard(viewModel, days, selectedDay, todayDate, displayMetric, effortScale)
        }
        }

        // The three hero vitals, HRV / Resting HR / Respiratory. Mirrors the iOS recoveryVitalsSection
        // below the HR card. Carries the last scored day's vitals (with a "Last night · <date>" footnote)
        // at the rollover so they don't blank to "No Data" while live HR ticks (#543). Staggered in as index 6.
        item {
        Box(modifier = Modifier.fillMaxWidth().staggeredAppear(6)) {
            HeroMetricRows(day = displayMetric, vitalsDay = lastVitalsDay)
        }
        }

        // YOUR CARDS, the user-customisable dashboard (WHOOP "My Dashboard"). Surfaces a persisted,
        // reorderable selection of metric cards as flat WHOOP metric rows (leading icon + UPPERCASE label +
        // sublabel on the left, big value + unit + chevron on the right). Default = Stress / Fitness age /
        // Vitality + HRV + Resting HR. TODAY only; a card with no value yet renders a dash rather than
        // vanishing. The "CUSTOMISE" link opens a local toggle/reorder dialog. Mirrors iOS yourCardsSection.
        // When Hydration tracking is OFF the card is hidden even if it sits in the saved selection (the
        // editor still offers it, so the choice persists), keeping the opt-in feature fully invisible until
        // enabled. Mirrors the iOS yourCardsSection hydration gate.
        item {
        val visibleDashboardCards = enabledDashboardCards.filter {
            it != DashboardCard.HYDRATION || hydrationEnabled
        }
        if (selectedDayOffset == 0 && visibleDashboardCards.isNotEmpty()) {
            YourCardsSection(
                cards = visibleDashboardCards,
                day = displayMetric,
                // The SAME carried-over last-scored row the OLD hero vital rows + Key-Metrics tiles read
                // (#543): right after the logical-day rollover today's row carries no vitals yet, so without
                // this the HRV / Resting HR / Respiratory / SpO₂ / Sleep cards all blank to "No Data" while
                // the rest of Today shows last night's carried values. Routing the cards through the same
                // per-field fallback used by HeroMetricRows and MetricGrid brings them to parity.
                carriedDay = lastScoredRecoveryDay,
                // The recovery-INDEPENDENT vitals carry (#543 follow-up): the overnight HRV / Resting HR /
                // Respiratory cards read PER-FIELD today-first with THIS fallback, so a night whose recovery
                // was nulled post-update still surfaces its OWN preserved vitals (not an older scored day's).
                vitalsDay = lastVitalsDay,
                // PER-FIELD SpO₂ / skin-temp carries: lastVitalsDay's predicate only checks HRV/RHR/resp,
                // so these two fields resolve independently to the last row that actually has them
                // (imported rows; computed "-noop" rows never carry spo2Pct). Mirrors iOS per-field
                // carry (TodayView.lastSpo2Day / lastSkinTempDay via carriedVital).
                spo2Day = lastSpo2Day,
                skinTempDay = lastSkinTempDay,
                stress = stressToday,
                fitnessAge = fitnessAgeToday,
                vitality = vitalityToday,
                importedStepsForDay = importedStepsForDay,
                estimatedStepsForDay = stepsEstForDay,
                latestActiveKcal = latestActiveKcal,
                hydrationTotalMl = hydrationTotalMl,
                hydrationGoalMl = hydrationGoalMl,
                onOpenHydration = onOpenHydration,
                onOpenStress = onOpenStress,
                onOpenMetric = onOpenMetric,
                onOpenSleep = onOpenSleep,
                onOpenCoupled = onOpenCoupled,
                onCustomise = { showDashboardEditor = true },
            )
        }
        }
        // Auto-detect workouts (MVP, opt-in, default OFF), a NON-DESTRUCTIVE "looks like a workout?"
        // card that suggests logging a detected sustained-elevated-HR bout. Renders nothing when the
        // toggle is off or there's nothing to suggest. Save → a manual "Workout" row; × → dismissed forever.
        if (selectedDayOffset == 0) {
            item { AutoWorkoutNudgeCard(viewModel = viewModel, days = days) }
        }
        // Strap battery only while the link is up AND a real reading exists, a stale % from a
        // dropped connection must not present as live (#159).
        item {
            TodaySourcesSection(
                footer,
                strapBatteryPct = if (liveSnap.connected) liveSnap.batteryPct?.roundToInt() else null,
                strapBatteryEstimate = if (liveSnap.connected) batteryEstimateText else null,
                expanded = sourcesExpanded,
                onToggle = { sourcesExpanded = !sourcesExpanded },
            )
        }
    }

    // LIVE SESSIONS (beta): the full-screen session dialog — the same presentation the live-workout
    // overlay uses on Live (Dialog, usePlatformDefaultWidth = false). Dismissing it only HIDES the
    // screen: the runner (held in LiveSessionRunner.active, ticking on the app-wide viewModelScope)
    // keeps guarding, and the entry card above re-opens the same session. Only "End session" + the
    // summary's "Done" (inside the screen) actually finish and clear it.
    if (showLiveSession) {
        Dialog(
            onDismissRequest = { showLiveSession = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            LiveSessionScreen(vm = viewModel, onClose = { showLiveSession = false })
        }
    }

    // Key-Metrics layout editor (#251), a Today-local dialog (no new nav destination). Saves the layout
    // and re-reads it into local state so the grid updates immediately and survives relaunch.
    if (showMetricsEditor) {
        KeyMetricsEditorDialog(
            initial = enabledKeyMetrics,
            onDismiss = { showMetricsEditor = false },
            onSave = { metrics ->
                KeyMetricPrefs.setEnabled(context, metrics)
                enabledKeyMetrics = metrics
                showMetricsEditor = false
            },
        )
    }

    // "Your cards" dashboard editor (WHOOP "My Dashboard" ✎), a Today-local dialog (no new nav
    // destination): toggle which cards show + reorder them with up/down arrows. Saves the selection and
    // re-reads it into local state so the dashboard updates immediately and survives relaunch. Mirrors the
    // iOS DashboardCardsEditorSheet. (No reorder lib is added, simple arrow buttons, like KeyMetricsEditor.)
    if (showDashboardEditor) {
        DashboardCardsEditorDialog(
            initial = enabledDashboardCards,
            onDismiss = { showDashboardEditor = false },
            onSave = { cards ->
                DashboardCardPrefs.setEnabled(context, cards)
                enabledDashboardCards = cards
                showDashboardEditor = false
            },
        )
    }
}


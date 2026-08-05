package com.noop.ui

import android.widget.Toast
import com.noop.analytics.SleepWindowReclip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.DaytimeStress
import com.noop.analytics.SleepEditGuard
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.max

/**
 * Sleep tab. Sections:
 *   1. HERO — stage breakdown for the navigated night; ◀/▶ chevrons walk every recorded night (0 = last).
 *   2. A grid of StatTiles, each a sparkline + "vs typical" caption.
 *   3. "Stages vs typical" — Deep / REM / Light bars: last-night minutes vs the personal mean.
 *   4. A 14-day asleep-hours trend.
 *
 * "Typical" is the mean across the cached daily metrics. The hero hypnogram prefers the REAL per-epoch
 * segments in sleepSession.stagesJSON when the session is the same night, else a reconstructed architecture.
 * No nights → honest empty state; a night with no stage data says so, never shows another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepNightScreen(
    vm: AppViewModel,
    onOpenJournal: () -> Unit = {},
) {
    val days by vm.recentDays.collectAsStateWithLifecycle()

    // PERF: the BLE live state ticks ~1Hz but this screen reads `live` only for the "syncing history"
    // note, so collapse it to those two fields via a structural-equality snapshot — an HR-only tick then
    // yields an EQUAL snapshot and the body isn't recomposed.
    val live by vm.live.collectAsStateWithLifecycle()
    val backfillNote by remember {
        derivedStateOf {
            val s = live
            if (s.backfilling) s.syncChunksThisSession else null
        }
    }

    // Every recorded sleep BLOCK, oldest→newest — the ◀/▶ chevrons walk this whole list, including
    // same-day naps / split sleep the dashboard's per-night merge collapses. Imported-wins / computed-fills
    // without the per-night collapse. Keyed on `days` so a sync/import reloads it (no Flow here).
    var sleeps by remember { mutableStateOf<List<SleepSession>>(emptyList()) }
    // 0 = latest night, N nights back. Reset to newest only on a REAL data reload (`days` changes); the
    // optimistic bed/wake edit rewrites `sleeps` without touching `days`, so it must not reset the browse
    // — the user stays on the night they just edited.
    var nightOffset by remember { mutableIntStateOf(0) }
    LaunchedEffect(days) {
        sleeps = runCatching { loadSleeps(vm) }.getOrDefault(emptyList())
        nightOffset = 0
    }

    // The transient UNDO banner after a suppressing delete. Holds the deleted SleepSession (still carrying
    // its owning deviceId + userEdited) so Undo restores it into the original namespace and lifts the
    // tombstone. Auto-cleared after ~7s by the keyed LaunchedEffect below.
    var sleepUndo by remember { mutableStateOf<SleepSession?>(null) }
    LaunchedEffect(sleepUndo) {
        if (sleepUndo != null) {
            kotlinx.coroutines.delay(7_000)
            sleepUndo = null
        }
    }

    // The user's LEARNED habitual midsleep (local time-of-day seconds), or null under the cold-start
    // threshold — the same value AnalyticsEngine.analyzeDay uses, fed to the main-night selector so hero,
    // naps split and edit target pick the block the rollup did. null keeps the cold-start band.
    var habitualMidsleep by remember { mutableStateOf<Long?>(null) }
    // The same learned value PER DAY over a trailing window, keyed by the night's local day. The
    // consistency band reads it so its habitual window bends with the habit instead of running flat.
    var habitualByDay by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    LaunchedEffect(days) {
        // Thread the ACTIVE strap id so the learner unions active + canonical nights; it resolves the
        // canonical "my-whoop" sibling internally either way.
        habitualMidsleep = runCatching { vm.repo.habitualMidsleepSec() }.getOrNull()
        habitualByDay = runCatching { vm.repo.habitualMidsleepSeries() }.getOrDefault(emptyMap())
    }

    // Persisted per-epoch MOTION keyed by each session's detected startTs. `selectNight` reads only the
    // resolved main-night GROUP's entries and lays them along the hypnogram timeline; a block with no
    // stored series stays absent (honest empty state for older NULL-motionJSON rows).
    var motionByStart by remember { mutableStateOf<Map<Long, List<Double>>>(emptyMap()) }
    LaunchedEffect(sleeps) {
        motionByStart = runCatching {
            vm.repo.sessionMotions("my-whoop", sleeps.map { it.startTs })
        }.getOrDefault(emptyMap())
    }

    // Export-verbatim sleep figures (sleep_performance / consistency / need / debt) — the headline tiles
    // prefer them over the on-device approximations. Keyed on `days` so a fresh import reloads them.
    var imported by remember { mutableStateOf(ImportedSleepSeries()) }
    LaunchedEffect(days) {
        suspend fun load(key: String) = runCatching {
            vm.repo.metricSeries("my-whoop", key, "0000-00-00", "9999-99-99")
        }.getOrDefault(emptyList()).associate { it.day to it.value }
        imported = ImportedSleepSeries(
            performance = load("sleep_performance"),
            consistency = load("sleep_consistency"),
            needMin = load("sleep_need_min"),
            debtMin = load("sleep_debt_min"),
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Morning-journal nudge: once per calendar day, when the freshest night ended within the last 12h,
    // invite the user to log how they felt. The shown-day is persisted so the sheet never re-pops.
    var showJournalPrompt by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(sleeps) {
        val latestEnd = sleeps.lastOrNull()?.effectiveEndTs ?: return@LaunchedEffect
        val nowS = System.currentTimeMillis() / 1000L
        val hoursAgo = (nowS - latestEnd) / 3600.0
        if (hoursAgo in 0.0..12.0) {
            val today = LocalDate.now().toString()
            val prefs = NoopPrefs.of(context)
            val lastPrompted = prefs.getString(NoopPrefs.KEY_LAST_JOURNAL_PROMPT, "")
            if (lastPrompted != today) {
                prefs.edit().putString(NoopPrefs.KEY_LAST_JOURNAL_PROMPT, today).apply()
                showJournalPrompt = true
            }
        }
    }

    if (showJournalPrompt) {
        ModalBottomSheet(
            onDismissRequest = { showJournalPrompt = false },
            sheetState = sheetState,
            containerColor = Palette.surfaceRaised,
            contentColor = Palette.textPrimary,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Metrics.space24),
                verticalArrangement = Arrangement.spacedBy(Metrics.space16),
            ) {
                Text("Good morning!", style = NoopType.title2, color = Palette.textPrimary)
                Text(
                    "Your night data is in. Logging how you felt helps NOOP learn what drives your best recovery.",
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
                Button(
                    onClick = { showJournalPrompt = false; onOpenJournal() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent),
                ) {
                    Text("Open Journal", style = NoopType.headline, color = Palette.surfaceBase)
                }
                TextButton(
                    onClick = { showJournalPrompt = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Maybe later", style = NoopType.subhead, color = Palette.textTertiary)
                }
            }
        }
    }

    // Tapping a metric tile opens a full-history detail sheet for that one metric.
    val metricSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var detailMetricKey by remember { mutableStateOf<String?>(null) }
    val currentDetailKey = detailMetricKey
    if (currentDetailKey != null) {
        ModalBottomSheet(
            onDismissRequest = { detailMetricKey = null },
            sheetState = metricSheetState,
            containerColor = Palette.surfaceRaised,
            contentColor = Palette.textPrimary,
        ) {
            SleepMetricDetailSheetContent(vm = vm, key = currentDetailKey)
        }
    }

    // The browsable DAY list: blocks grouped by the calendar day they END on (the dashboard's per-night
    // merge key), newest day first, blocks within a day oldest→newest. Each day is ONE ◀/▶ stop, so a
    // split-sleep / nap day reads as one night and a single-night day isn't stuck on dead arrows.
    val navDays = remember(sleeps) {
        sleeps.groupBy { localDayString(it.effectiveEndTs) }
            .toSortedMap(reverseOrder())                       // newest day first
            .map { (_, blocks) -> blocks.sortedBy { it.effectiveStartTs } }
    }

    // The navigated night, decoded once per (offset, data) change so chevron taps re-pick without
    // re-parsing stagesJSON each recomposition. The offset indexes DAYS (navDays).
    val night = remember(nightOffset, navDays, days, habitualMidsleep, motionByStart) {
        selectNight(navDays, days, nightOffset, habitualMidsleep, motionByStart)
    }

    // The HERO follows the selected night (its stage breakdown from that day's row); the TILES, debt
    // ledger, personal need and trend stay full-history. `selectedDay` re-points only the hero. Model is
    // null when the selected day has no stage minutes.
    val model = remember(days, night, imported) {
        buildSleepModel(days, night?.session, imported, selectedDay = night?.dayKey,
            heroStages = night?.groupStages, heroSegments = night?.groupSegments)
    }
    val display = remember(model, night) { heroDisplay(model, night) }

    // The navigated night's HR for the hero chart, downsampled in SQL to at most HR_CHART_TARGET_POINTS
    // buckets over the padded sleep window — a night is up to ~9h of per-second rows. Same active ∪
    // canonical union the rest of the screen reads, so a re-added strap's nights aren't blank.
    var nightHr by remember { mutableStateOf<List<TimelinePoint>>(emptyList()) }
    LaunchedEffect(night) {
        val onset = night?.heroOnsetTs
        val wake = night?.heroWakeTs
        nightHr = if (onset == null || wake == null || wake <= onset) {
            emptyList()
        } else {
            runCatching {
                val (from, to) = hrChartWindow(onset, wake)
                vm.repo.hrBucketsUnion(from, to, hrChartBucketSec(to - from))
                    .map { TimelinePoint(it.bucket, it.avgBpm) }
            }.getOrDefault(emptyList())
        }
    }

    // A stage-less SELECTED day (e.g. the newest after a bad hand-edit) must not hide the tab's history.
    // The tiles / ledger / trends are full-history and independent of the browsed night, so anchor them to
    // the newest stage-bearing day when the selected day's model fails. Null only when NO day has stages.
    val tilesModel = remember(model, days, imported) { model ?: fallbackSleepModel(days, imported) }

    // The trailing week as CALENDAR slots carrying their bridged bed→wake spans: the one derivation the
    // schedule, time-in-bed and stress cards share, so they agree on what counts as one night AND on
    // where a missed night sits.
    val weekSlots = remember(sleeps, habitualMidsleep) {
        consistencyNightSlots(sleeps, habitualMidsleep, limit = SLEEP_TREND_NIGHTS)
    }

    // The same week's sleep stress, one whoop-rs `sleep_stress` read per night. Oldest first, so the
    // last entry is the freshest night and owns the performance card's fourth driver.
    var weekStress by remember { mutableStateOf<List<SleepStressNight>>(emptyList()) }
    LaunchedEffect(weekSlots) {
        weekStress = runCatching { loadSleepStress(vm, weekSlots) }.getOrDefault(emptyList())
    }

    // Jump to a night by its (local) wake-day. navDays is newest-day-first, so the day's index IS its offset.
    val onPickNightDate: (LocalDate) -> Unit = { targetDate ->
        val targetStr = targetDate.toString()
        val dayIdx = navDays.indexOfFirst { day -> day.any { localDayString(it.effectiveEndTs) == targetStr } }
        if (dayIdx >= 0) nightOffset = dayIdx
    }

    // The edit sheet's target: an existing block, or a provisional nap awaiting its first save.
    var editing by remember { mutableStateOf<SleepEditTarget?>(null) }
    val editSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Apply a corrected window. Belt-and-braces: never apply a future-ending or inverted window,
    // whatever the pickers produced. The in-memory copy and the DB write share one safe window, the
    // same rule WhoopRepository.updateSleepSessionTimes applies.
    val applyTimes: (SleepSession, Long, Long, Boolean) -> Unit = { s, start, end, wakeSetByUser ->
        val safe = SleepEditGuard.clampedEditWindow(start, end, System.currentTimeMillis() / 1000L)
        if (safe != null) {
            val (safeStart, safeEnd) = safe
            // Optimistic: rewrite this session in `sleeps` so the cards recompute now, then persist off
            // the UI thread. The detected startTs/endTs stay immutable; the corrected bounds go in
            // startTsAdjusted / endTsAdjusted, the latter only when the user moved the wake. Reclipping
            // stagesJSON updates the timeline instantly.
            sleeps = sleeps.map {
                if (it.deviceId == s.deviceId && it.startTs == s.startTs) {
                    val reclipped = SleepWindowReclip.reclip(
                        it.stagesJSON, it.effectiveStartTs, it.effectiveEndTs, safeStart, safeEnd,
                    )
                    it.copy(
                        startTsAdjusted = safeStart,
                        userEdited = true,
                        endTsAdjusted = SleepEditGuard.frozenWake(it.endTsAdjusted, safeEnd, wakeSetByUser),
                        stagesJSON = reclipped ?: it.stagesJSON,
                    )
                } else {
                    it
                }
            }
            scope.launch { vm.updateSleepSessionTimes(s, safeStart, safeEnd, wakeSetByUser) }
        } else {
            // The clamp refused a future or inverted window. Never drop an edit silently.
            Toast.makeText(
                context,
                "That time can't be saved (it lands in the future or ends before it starts).",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Delete: drop the session from `sleeps` so the cards recompute as if the night were never
    // recorded, then persist off the UI thread. `s` still carries its owning deviceId + userEdited,
    // all Undo needs to restore it into the original namespace.
    val deleteSession: (SleepSession) -> Unit = { s ->
        sleeps = sleeps.filterNot { it.deviceId == s.deviceId && it.startTs == s.startTs }
        sleepUndo = s
        scope.launch { vm.deleteSleepSession(s) }
    }

    // Persist a new nap as its OWN session, then reload so it shows in the browse without waiting for a
    // sync. No optimistic insert - stages are staged from raw off the UI thread.
    val addNap: (Long, Long) -> Unit = { startTs, endTs ->
        scope.launch {
            vm.addManualNap(startTs, endTs)
            sleeps = runCatching { loadSleeps(vm) }.getOrDefault(sleeps)
        }
    }

    val edit = editing
    if (edit != null) {
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = editSheetState,
            containerColor = Palette.surfaceRaised,
            contentColor = Palette.textPrimary,
        ) {
            SleepEditSheetContent(
                session = edit.session,
                isNew = edit.isNew,
                onClose = { editing = null },
                onSave = { start, end, wakeMoved ->
                    if (edit.isNew) addNap(start, end) else applyTimes(edit.session, start, end, wakeMoved)
                },
                onDelete = { deleteSession(edit.session) },
            )
        }
    }

    LazyScreenScaffold(
        title = "Sleep",
        subtitle = "Last night, read in two seconds.",
    ) {
        // The transient UNDO banner after a suppressing delete — restores the deleted row into its original
        // namespace and lifts the tombstone.
        sleepUndo?.let { deleted ->
            item {
                SleepUndoBanner(
                    session = deleted,
                    onUndo = {
                        sleepUndo = null
                        scope.launch {
                            vm.undoDeleteSleepSession(deleted)
                            // Re-read so the restored night reappears in the browse. Same active∪canonical
                            // union as the main loader, so the undo reload can't snap the browse back to a
                            // canonical-only night set.
                            sleeps = runCatching { loadSleeps(vm) }.getOrDefault(sleeps)
                        }
                    },
                )
            }
        }
        // The empty state is ONLY for a truly empty history. A newest day that merely fails to merge keeps
        // the hero (night != null) and the full-history tiles (tilesModel != null), so intact older nights
        // are never hidden behind "no nights".
        if (tilesModel == null && night == null) {
            // While the strap is mid-offload, say so — "No nights" reads as final otherwise.
            item {
                if (backfillNote != null) SyncingHistoryNote(chunks = backfillNote!!)
                SleepEmptyState()
            }
        } else {
            // SLEEP PERFORMANCE — the night's score and the drivers behind it. The score is a
            // full-history latest (series.last), so it reads from `tilesModel` when the selected day's
            // model failed to build: real data over a zeroed gauge.
            item {
                val scored = model ?: tilesModel
                SleepPerformanceCard(
                    score = scored?.performance?.latest,
                    asleepMin = model?.stages?.asleep,
                    // The fourth driver is the freshest night's high-stress share, straight off
                    // whoop-rs `sleep_stress`; its 0 is the good end, so its strip reads mirrored.
                    drivers = listOf(
                        SleepDriver("Hours vs. needed", scored?.hoursVsNeeded?.latest),
                        SleepDriver("Sleep consistency", scored?.consistency?.latest),
                        SleepDriver("Sleep efficiency", scored?.efficiency?.latest),
                        SleepDriver(
                            "High sleep stress",
                            weekStress.lastOrNull()?.highSharePct,
                            higherIsBetter = false,
                        ),
                    ),
                    source = restHeroSource(imported, days),
                )
            }
            item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
            item {
                SleepNightHeader(
                    offset = nightOffset,
                    lastIndex = max(navDays.lastIndex, 0),
                    dateLabel = (night?.clockLabel ?: model?.clockLabel)?.substringBefore(" · "),
                    canEdit = night?.session != null,
                    onNavigate = { nightOffset = it },
                    onEdit = { night?.session?.let { s -> editing = SleepEditTarget(s, isNew = false) } },
                    onAddNap = { night?.let { n -> editing = SleepEditTarget(provisionalNap(n), isNew = true) } },
                    onPickNightDate = onPickNightDate,
                    anchorTs = night?.heroOnsetTs,
                )
            }
            night?.session?.let { s ->
                item {
                    SleepWindowRow(
                        night.heroOnsetTs ?: s.effectiveStartTs,
                        night.heroWakeTs ?: s.effectiveEndTs,
                    )
                }
            }
            item {
                val heroDisplay = display
                if (heroDisplay == null) {
                    // Honest fallback: no usable stage data for this night. Never silently substitute
                    // another night's timeline.
                    NoopCard(tint = Palette.restColor) {
                        Text(
                            "No stage data recorded for this night.",
                            style = NoopType.subhead,
                            color = Palette.textTertiary,
                        )
                    }
                } else {
                    // A fragmented night prefers the GROUP total: `session` is only the winning fragment,
                    // so its own window undershoots the summed stage minutes.
                    val inBedMin = night?.groupInBedMin
                        ?: night?.session?.let { (it.effectiveEndTs - it.effectiveStartTs) / 60.0 }
                        ?: heroDisplay.stages.total
                    SleepStagesCard(
                        stages = heroDisplay.stages,
                        realSegments = heroDisplay.realSegments,
                        typicalByStage = mapOf(
                            "Light" to model?.typicalLightMin,
                            "Deep" to model?.typicalDeepMin,
                            "REM" to model?.typicalRemMin,
                        ),
                        typicalAsleepMin = model?.typicalTotalMin,
                        inBedMin = inBedMin,
                        efficiencyText = heroDisplay.efficiencyText,
                        onsetTs = night?.heroOnsetTs ?: night?.session?.effectiveStartTs,
                        wakeTs = night?.heroWakeTs ?: night?.session?.effectiveEndTs,
                        motionEpochs = night?.groupMotion ?: emptyList(),
                        hrPoints = nightHr,
                    )
                }
            }
            night?.session?.let { s ->
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item {
                    NapsCard(
                        main = s,
                        naps = night.napBlocks,
                        onEditNap = { nap -> editing = SleepEditTarget(nap, isNew = false) },
                    )
                }
            }
            // Tiles / ledger / trends read the FULL-history model: they stay up when only the selected day's
            // model failed to build.
            if (tilesModel != null) {
                // Bind a non-null local so the smart-cast carries into each item {} lambda (a nullable val
                // doesn't smart-cast across a lambda boundary).
                val m = tilesModel
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item {
                    // The percentage and the bar are the SAME pair of figures. Reading the asleep total
                    // off the hero and the need off the trend tail let the card compare two nights.
                    SleepNeedCard(
                        percent = m.hoursVsNeeded.latest,
                        typicalPercent = m.hoursVsNeeded.typical,
                        sleptMin = m.hoursVsNeededSleptMin,
                        neededMin = m.hoursVsNeededNeedMin,
                        ledger = m.sleepDebtLedger,
                    )
                }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item {
                    SleepScheduleCard(
                        score = m.consistency.latest,
                        typicalScore = m.consistency.typical,
                        nights = weekSlots.let(::sleepScheduleNights),
                        habitualByDay = habitualByDay,
                        needMin = m.sleepDebtLedger.needMin,
                    )
                }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item { SectionHeader("Weekly trends", overline = "Sleep", trailing = "Last 7 nights") }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item {
                    SleepPerformanceTrendCard(
                        series = m.trendPerformance,
                        dates = m.trendDates,
                        onOpenDetail = { detailMetricKey = "performance" },
                    )
                }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item { SleepTimeInBedCard(nights = weekSlots.let(::sleepScheduleNights), slots = weekSlots) }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item {
                    SleepEfficiencyTrendCard(
                        // The shared gap-preserving trend window, not the tile's compressed series, so
                        // this chart's points line up with the day labels the cards beside it use.
                        series = m.trendEfficiency,
                        dates = m.trendDates,
                        onOpenDetail = { detailMetricKey = "efficiency" },
                    )
                }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item {
                    SleepHoursVsNeededCard(
                        hours = m.trendHours,
                        needHours = m.trendNeedHours,
                        dates = m.trendDates,
                    )
                }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item { SleepRestorativeCard(rows = days, dates = m.trendDates) }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item { SleepStressCard(nights = weekStress) }
            }
        }
    }
}

/**
 * Every recorded sleep BLOCK for the browse, oldest to newest. Reads the ACTIVE-strap union with the
 * canonical "my-whoop" rather than the canonical id alone: after a strap remove and re-add, live nights
 * land under a fresh id, so a canonical-only read sticks the screen on the last pre-re-add night.
 *
 * Imported wins per LOCAL wake-day with the richness exception (a stage-less import yields to a computed
 * day that has stages), the same rule WhoopRepository.mergeSleep applies. A UTC key would mis-attribute a
 * UTC+ user's early-morning wake to yesterday.
 */
internal suspend fun loadSleeps(vm: AppViewModel): List<SleepSession> {
    val now = System.currentTimeMillis() / 1000L
    val imported = vm.repo.sleepSessionsUnion(0L, now)
    val computed = vm.repo.computedSleepSessionsUnion(0L, now)
    fun localEndDay(ts: Long): String {
        val offsetSec = (java.util.TimeZone.getDefault().getOffset(ts * 1000) / 1000).toLong()
        return AnalyticsEngine.dayString(ts, offsetSec)
    }
    return WhoopRepository.mergeSleepRichness(imported, computed) { localEndDay(it.effectiveEndTs) }
        .sortedBy { it.effectiveStartTs }
}

/** Rows read per night for the stress card — above a 14 h night of 1 Hz HR, so a long night is never
 *  silently truncated into a shorter one. */
private const val SLEEP_STRESS_ROW_LIMIT = 60_000

/**
 * One whoop-rs `sleep_stress` read per night in [spans]: that night's HR + R-R over the registry read
 * scope, bucketed by the same aggregator the Stress screen's day path uses and scored with no
 * hour-of-day filter. A night that scored no bucket keeps its slot with no minutes, so this card's axis
 * is the same calendar week the cards above it draw.
 */
internal suspend fun loadSleepStress(
    vm: AppViewModel,
    slots: List<NightSlot>,
): List<SleepStressNight> {
    val labels = sleepScheduleNights(slots)
    fun empty(i: Int) = SleepStressNight(labels[i].label, 0L, 0L, 0L, null)
    return slots.mapIndexed { i, slot ->
        val (onsetTs, wakeTs) = slot.span ?: return@mapIndexed empty(i)
        if (wakeTs <= onsetTs) return@mapIndexed empty(i)
        val tzOffsetSec = (java.util.TimeZone.getDefault().getOffset(onsetTs * 1000L) / 1000).toLong()
        val hr = vm.repo.hrSamplesUnion(onsetTs, wakeTs, SLEEP_STRESS_ROW_LIMIT)
        val rr = vm.repo.rrIntervalsUnion(onsetTs, wakeTs, SLEEP_STRESS_ROW_LIMIT)
        val info = DaytimeStress.analyzeNight(hr, rr, tzOffsetSec)
        if (info.hours.isEmpty()) {
            empty(i)
        } else {
            SleepStressNight(
                label = labels[i].label,
                lowMinutes = info.lowMinutes,
                mediumMinutes = info.mediumMinutes,
                highMinutes = info.highMinutes,
                highSharePct = info.highSharePct,
            )
        }
    }
}

/** Default bounds for a nap the user is about to log: half an hour, an hour after the night's wake. */
internal fun provisionalNap(night: HeroNight): SleepSession {
    val start = (night.heroWakeTs ?: night.session.effectiveEndTs) + 3_600L
    return SleepSession(deviceId = night.session.deviceId, startTs = start, endTs = start + 1_800L)
}

/**
 * Whether the night's sleep-performance score is WHOOP's imported figure or NOOP's own, so the card is
 * honest about provenance.
 */
internal fun restHeroSource(imported: ImportedSleepSeries, days: List<DailyMetric>): String {
    val lastDay = days.lastOrNull()?.day
    return if (lastDay != null && imported.performance[lastDay] != null) "Whoop" else "On-device"
}

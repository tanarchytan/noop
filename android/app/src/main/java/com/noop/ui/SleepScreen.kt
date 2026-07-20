package com.noop.ui

import android.widget.Toast
import com.noop.analytics.SleepMark
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
import com.noop.analytics.SleepEditGuard
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
fun SleepScreen(
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
        sleeps = runCatching {
            val now = System.currentTimeMillis() / 1000L
            // Read the ACTIVE-strap ∪ canonical "my-whoop" union, not the canonical id alone: after a
            // strap remove+re-add, live nights land under a fresh id, so a canonical-only read would stick
            // this screen on the last pre-re-add night. Exact-duplicate (startTs, endTs) blocks are dropped.
            val imported = vm.repo.sleepSessionsUnion(vm.activeStrapId, 0L, now)
            val computed = vm.repo.computedSleepSessionsUnion(vm.activeStrapId, 0L, now)
            // Key by the LOCAL wake-day, matching WhoopRepository.mergeSleep — a UTC key mis-attributes a
            // UTC+ user's early-morning wake to yesterday. Reuse the existing dayString(ts, offsetSec)
            // overload; a new one clashes on the JVM.
            fun localEndDay(ts: Long): String {
                val offsetSec = (java.util.TimeZone.getDefault().getOffset(ts * 1000) / 1000).toLong()
                return AnalyticsEngine.dayString(ts, offsetSec)
            }
            // Imported wins per local wake-day, with the richness exception (a stage-less import yields to
            // a computed day that has stages) — the same rule WhoopRepository.mergeSleep uses. Sort by
            // EFFECTIVE onset so a hand-edited bedtime orders the night correctly.
            WhoopRepository.mergeSleepRichness(imported, computed) { localEndDay(it.endTs) }
                .sortedBy { it.effectiveStartTs }
        }.getOrDefault(emptyList())
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
    LaunchedEffect(days) {
        // Thread the ACTIVE strap id so the learner unions active + canonical nights; it resolves the
        // canonical "my-whoop" sibling internally either way.
        habitualMidsleep = runCatching { vm.repo.habitualMidsleepSec(vm.activeStrapId) }.getOrNull()
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

    // Day-cycle sky backdrop, default ON. When off, the screen drops the liquid sky for the plain dark
    // canvas. SharedPreferences isn't reactive, so read once into local state.
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(context) }

    // Morning-journal nudge: once per calendar day, when the freshest night ended within the last 12h,
    // invite the user to log how they felt. The shown-day is persisted so the sheet never re-pops.
    var showJournalPrompt by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(sleeps) {
        val latestEnd = sleeps.lastOrNull()?.endTs ?: return@LaunchedEffect
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
        sleeps.groupBy { localDayString(it.endTs) }
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

    // A stage-less SELECTED day (e.g. the newest after a bad hand-edit) must not hide the tab's history.
    // The tiles / ledger / trends are full-history and independent of the browsed night, so anchor them to
    // the newest stage-bearing day when the selected day's model fails. Null only when NO day has stages.
    val tilesModel = remember(model, days, imported) { model ?: fallbackSleepModel(days, imported) }

    // Jump to a night by its (local) wake-day. navDays is newest-day-first, so the day's index IS its offset.
    val onPickNightDate: (LocalDate) -> Unit = { targetDate ->
        val targetStr = targetDate.toString()
        val dayIdx = navDays.indexOfFirst { day -> day.any { localDayString(it.endTs) == targetStr } }
        if (dayIdx >= 0) nightOffset = dayIdx
    }

    LazyScreenScaffold(
        title = "Sleep",
        subtitle = "Last night, read in two seconds.",
        // Liquid sky backdrop: the time-of-day sky settles into the theme canvas behind the header + hero,
        // bled full-width behind the status bar via the scaffold's topBackground. Gated on the day-cycle
        // preference.
        topBackground = if (showDayCycleBackground) { { LiquidScreenSky() } } else null,
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
                            sleeps = runCatching {
                                val now = System.currentTimeMillis() / 1000L
                                vm.repo.sleepSessionsUnion(vm.activeStrapId, 0L, now) +
                                    vm.repo.computedSleepSessionsUnion(vm.activeStrapId, 0L, now)
                            }.getOrDefault(sleeps)
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
            // REST HERO — the night's sleep-performance score, else a big hours-slept headline. The score
            // is a full-history latest (series.last), so it reads from `tilesModel` when the selected day's
            // model failed to build: real data over a zeroed gauge.
            item {
                RestHero(
                    score = (model ?: tilesModel)?.performance?.latest,
                    asleepMin = model?.stages?.asleep,
                    source = restHeroSource(imported, days),
                )
            }
            item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
            // SLEEP MARKS — tap to log "going to sleep" / "I'm awake". LOGGING ONLY: a mark is persisted to
            // the `sleep_mark` series + the shareable strap log; it never changes detected sleep.
            item {
            SleepMarkCard(
                onMark = { type ->
                    val mark = SleepMark.now(type)
                    // The shareable strap log is the human-readable surface in a debug export.
                    vm.ble.externalLog(mark.logLine())
                    scope.launch {
                        runCatching {
                            vm.repo.upsertMetricSeries(listOf(mark.metricPoint("my-whoop")))
                        }
                    }
                    Toast.makeText(context, mark.confirmation(), Toast.LENGTH_SHORT).show()
                },
            )
            }
            item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
            item {
            Hero(
                display = display,
                clock = night?.clockLabel ?: model?.clockLabel,
                nightOffset = nightOffset,
                lastIndex = max(navDays.lastIndex, 0),
                onNavigate = { nightOffset = it },
                session = night?.session,
                onUpdateTimes = { s, start, end ->
                    // Belt-and-braces: never apply a future-ending or inverted window, whatever the pickers
                    // produced. Sharing one safe window here keeps the in-memory copy and the DB write in
                    // lockstep. Same rule as WhoopRepository.updateSleepSessionTimes.
                    val safe = SleepEditGuard.clampedEditWindow(start, end, System.currentTimeMillis() / 1000L)
                    if (safe != null) {
                        val (safeStart, safeEnd) = safe
                        // Optimistic: rewrite this session in `sleeps` so metrics recompute now, then persist
                        // off the UI thread. Keep the IMMUTABLE detected startTs, store the corrected onset in
                        // startTsAdjusted (userEdited=true); reclip stagesJSON so the hypnogram updates instantly.
                        sleeps = sleeps.map {
                            if (it.deviceId == s.deviceId && it.startTs == s.startTs) {
                                val reclipped = SleepWindowReclip.reclip(it.stagesJSON, it.effectiveStartTs, it.endTs, safeStart, safeEnd)
                                it.copy(startTsAdjusted = safeStart, endTs = safeEnd, userEdited = true,
                                        stagesJSON = reclipped ?: it.stagesJSON)
                            } else {
                                it
                            }
                        }
                        scope.launch { vm.updateSleepSessionTimes(s, safeStart, safeEnd) }
                    } else {
                        // The clamp refused a future/inverted window. Never drop an edit silently — tell the
                        // user why nothing changed.
                        Toast.makeText(
                            context,
                            "That time can't be saved (it lands in the future or ends before it starts).",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onDeleteSession = { s ->
                    // Delete: drop this session from `sleeps` so metrics recompute as if the night were never
                    // recorded, then persist the removal off the UI thread. `s` still carries its owning
                    // deviceId + userEdited, all Undo needs to restore it into the original namespace.
                    sleeps = sleeps.filterNot { it.deviceId == s.deviceId && it.startTs == s.startTs }
                    sleepUndo = s
                    scope.launch { vm.deleteSleepSession(s) }
                },
                onAddNap = { startTs, endTs ->
                    // Persist the new nap as its OWN session, then reload `sleeps` so it shows in the browse
                    // without waiting for a sync. No optimistic insert — stages are staged from raw off the
                    // UI thread.
                    scope.launch {
                        vm.addManualNap(startTs, endTs)
                        sleeps = runCatching {
                            val now = System.currentTimeMillis() / 1000L
                            // Same active∪canonical union as the main loader, so the post-nap reload can't
                            // snap the browse back to a canonical-only night set.
                            val importedSessions = vm.repo.sleepSessionsUnion(vm.activeStrapId, 0L, now)
                            val computed = vm.repo.computedSleepSessionsUnion(vm.activeStrapId, 0L, now)
                            fun localEndDay(ts: Long): String {
                                val offsetSec = (java.util.TimeZone.getDefault().getOffset(ts * 1000) / 1000).toLong()
                                return AnalyticsEngine.dayString(ts, offsetSec)
                            }
                            // Same imported-wins + richness merge as the main loader.
                            WhoopRepository.mergeSleepRichness(importedSessions, computed) { localEndDay(it.endTs) }
                                .sortedBy { it.effectiveStartTs }
                        }.getOrDefault(sleeps)
                    }
                },
                onPickNightDate = onPickNightDate,
                napBlocks = night?.napBlocks ?: emptyList(),
                habitualMidsleepSec = habitualMidsleep,
                motionEpochs = night?.groupMotion ?: emptyList(),
                groupInBedMin = night?.groupInBedMin,
                windowOnsetTs = night?.heroOnsetTs,
                windowWakeTs = night?.heroWakeTs,
            )
            }
            // Tiles / ledger / trends read the FULL-history model: they stay up when only the selected day's
            // model failed to build.
            if (tilesModel != null) {
                // Bind a non-null local so the smart-cast carries into each item {} lambda (a nullable val
                // doesn't smart-cast across a lambda boundary).
                val m = tilesModel
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item { MetricGrid(m, onMetricClick = { detailMetricKey = it }) }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item { SleepDebtLedgerCard(m.sleepDebtLedger) }
                // StagesVsTypical describes ONE night's deep/REM/light minutes under "Selected night", so it
                // must read the SELECTED day's model, never the full-history fallback — else it would label
                // another day's stages as this night. Hidden when the selected day has no stage model.
                if (model != null) {
                    // Bind a non-null local so the smart-cast carries into the item {} lambda.
                    val selectedModel = model
                    item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                    item { StagesVsTypical(selectedModel) }
                }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item { DurationTrend(m) }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item { HoursVsNeededCard(m) }
                item { Spacer(Modifier.height(Metrics.selectorTopUp)) }
                item { SleepConsistencyCard(sleeps) }
            }
        }
    }
}

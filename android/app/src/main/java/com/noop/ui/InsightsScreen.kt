package com.noop.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.RustScores
import com.noop.data.DailyMetric
import com.noop.data.JournalEntry
import com.noop.data.WorkoutRow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// MARK: - Insights
//
// The "interrogate what affects what" screen.
// Two halves:
//
//  1. BEHAVIOUR EFFECTS, split logged journal answers (the days each behaviour WAS
//     logged "yes" vs NOT) and compare a chosen outcome metric (Charge / HRV /
//     Rest / RHR) between the two groups. Ranked by effect size (Cohen's d), with
//     significant effects first. Each card carries a plain-English sentence, the
//     with/without means, group counts, a significance pill, and the magnitude word.
//     Tint is sign-aware: a behaviour that moves the outcome the "good" way (respecting
//     higherIsBetter) reads positive/green, the "bad" way reads critical/red.
//
//  2. METRIC RELATIONSHIPS, a curated set of Pearson correlations between daily series
//     (HRV ↔ charge, rest ↔ charge, RHR ↔ charge, charge → next-day charge),
//     each rendered as a one-line insight with r and a plain-English reading.
//
// The outcome series are read straight off the cached DailyMetric rows (vm.recentDays):
// recovery / avgHrv / sleep-efficiency / restingHr. Pearson r comes from the shared
// [CorrelationEngine]; a behaviour or relationship only appears when there is real
// overlapping data, never a fabricated value.

// MARK: - Outcome (segmented selection)

/** One interrogable outcome metric: how to read it off a DailyMetric, its label,
 *  units, and whether higher is the "good" direction (drives sign-aware tint). */
private enum class Outcome(
    @StringRes val labelRes: Int,
    /** The engine + stored-preference spelling; [nameRes] is what the user reads. */
    val outcomeName: String,
    @StringRes val nameRes: Int,
    val higherIsBetter: Boolean,
    /** The Bevel colour world the outcome belongs to, drives the card wash so the
     *  Behaviour Effects section sits in one world (Charge→green, HRV/Rest→indigo,
     *  RHR→Stress teal). */
    val domain: DomainTheme,
    val pick: (DailyMetric) -> Double?,
    val format: (Double) -> String,
) {
    Recovery(
        labelRes = R.string.insights_outcome_charge, outcomeName = "Charge",
        nameRes = R.string.insights_outcome_charge,
        higherIsBetter = true, domain = DomainTheme.Charge,
        pick = { it.recovery }, format = { "${it.roundToInt()}%" },
    ),
    Hrv(
        labelRes = R.string.insights_outcome_hrv, outcomeName = "HRV",
        nameRes = R.string.insights_outcome_hrv,
        higherIsBetter = true, domain = DomainTheme.Rest,
        pick = { it.avgHrv }, format = { "${it.roundToInt()} ms" },
    ),
    Sleep(
        labelRes = R.string.insights_outcome_rest, outcomeName = "Rest",
        nameRes = R.string.insights_outcome_rest,
        higherIsBetter = true, domain = DomainTheme.Rest,
        // Efficiency is stored as a 0..1 fraction; lift it to the 0..100 scale the other outcomes
        // use, so the label, the deltas and Cohen's d are all on one scale.
        pick = { row -> row.efficiency?.let { if (it <= 1.0) it * 100.0 else it } },
        format = { "${it.roundToInt()}%" },
    ),
    Rhr(
        labelRes = R.string.insights_outcome_rhr, outcomeName = "Resting HR",
        nameRes = R.string.insights_outcome_resting_hr,
        higherIsBetter = false, domain = DomainTheme.Stress,
        pick = { it.restingHr?.toDouble() }, format = { "${it.roundToInt()} bpm" },
    ),
}

/** The selected outcome's display name, lowercased the way every Insights sentence uses it. */
@Composable
private fun Outcome.lowerName(): String = stringResource(nameRes).lowercase(Locale.US)

/** Segment captions resolved up front, so SegmentedPillControl's plain label lambda can read them. */
@Composable
private fun outcomeLabels(): Map<Outcome, String> {
    val out = HashMap<Outcome, String>()
    for (o in Outcome.entries) out[o] = stringResource(o.labelRes)
    return out
}

// MARK: - Computed shapes (plain data, no analytics package dependency)

/** One behaviour's effect on the selected outcome: with/without means, counts,
 *  Cohen's d and a crude significance flag. */
private data class BehaviorEffect(
    val behavior: String,
    val meanWith: Double,
    val meanWithout: Double,
    val nWith: Int,
    val nWithout: Int,
    val cohensD: Double,
) {
    val delta: Double get() = meanWith - meanWithout
    /** Crude significance: a non-trivial effect with enough days on both sides.
     *  Honest stand-in for a t-test, |d| ≥ 0.5 ("moderate") with ≥3 days each side. */
    val significant: Boolean get() = abs(cohensD) >= 0.5 && nWith >= 3 && nWithout >= 3
}

/** A curated metric relationship plus its computed Pearson correlation. */
private data class Relationship(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val blurbRes: Int,
    val r: Double,
    val n: Int,
) {
    /** Crude significance flag for |r| with n pairs (rough p < 0.05 threshold). */
    val significant: Boolean get() = n >= 4 && abs(r) >= significanceThreshold(n)
}

/** The fully-computed insight inputs for the current data, recomputed off recentDays. */
private data class InsightModel(
    /** behaviour question → set of days it was answered "yes". */
    val behaviours: Map<String, Set<String>>,
    /** day → value, per outcome. */
    val outcomeByDay: Map<Outcome, Map<String, Double>>,
    /** ordered (day, value) per outcome for correlations. */
    val seriesByOutcome: Map<Outcome, List<Pair<String, Double>>>,
    /**
     * numeric journal item (question) → [day: value]. A numeric series is the same
     * Map<String, Double> shape EffectRanker.rank's `outcomeByDay` takes, so a numeric journal item
     * ("caffeine mg", "alcohol units") is a first-class series the ranker can consume like any metric
     * outcome (dose-response lands in the v5 hub). Empty for a yes/no-only journal.
     */
    val numericJournalSeries: Map<String, Map<String, Double>> = emptyMap(),
)

// MARK: - Screen

/**
 * Insights, behaviour effects + metric relationships over cached history.
 *
 * Loads the journal (all days) and the per-day outcome series from `vm.recentDays`,
 * then presents the ranked behaviour effects for the selected outcome and the curated
 * Pearson relationships. Empty/sparse states explain what's missing rather than faking
 * numbers — the honest data-display contract.
 */
@Composable
fun InsightsScreen(vm: AppViewModel, onOpenInsightsHub: () -> Unit = {}) {
    val days by vm.recentDays.collectAsStateWithLifecycle()

    // Journal answers (all history): imported "my-whoop" rows UNIONED with native "noop-journal"
    // rows (native wins per (day, question)). Keyed on journalSeq so the logging card's saves and
    // clears refresh the effects immediately; re-loaded too when the cached days change underneath.
    var behaviours by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    // numeric journal item (question) -> [day: value]. A numeric journal series is a daily series
    // the effect ranker consumes exactly like a metric series (EffectRanker.effect already takes a
    // Map<String, Double> outcome), so "caffeine mg" / "alcohol units" can rank as a numeric outcome.
    var numericJournalSeries by remember { mutableStateOf<Map<String, Map<String, Double>>>(emptyMap()) }
    var journalLoaded by remember { mutableStateOf(false) }
    var journalSeq by remember { mutableStateOf(0) }
    var dayOffset by remember { mutableStateOf(0L) }
    var importedQuestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var dayAnswers by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // the selected day's native numeric values (question -> value), drives the numeric fields.
    var dayNumeric by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var preFilledFromYesterday by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // the v2 catalog (rename + numeric type + group + order), folding the legacy custom/hidden
    // arrays on first run. Held in state so edits (rename/regroup/convert/add/remove) recompose.
    var catalogItems by remember { mutableStateOf(loadJournalCatalogItems(ctx)) }

    // item 4: today's local calendar-day key. The journal day chips ("Today"/"Yesterday"/"Tomorrow")
    // are relative to the CURRENT date, but the answers (`dayAnswers`) and the resolved key are derived from
    // `LocalDate.now()` only inside the load effect below, which re-keys on `journalSeq`/`dayOffset`. A day
    // can pass with the screen alive and no save (the app simply backgrounded overnight), leaving the
    // previous day's answers pinned under "Today" instead of the new day starting blank. We re-stamp this on
    // every lifecycle RESUME, and fold it into the load effect's keys, so the moment the date rolls over the
    // journal reloads for the new day and prior answers move to their real date.
    var currentDayKey by remember { mutableStateOf(LocalDate.now().toString()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val key = LocalDate.now().toString()
                if (key != currentDayKey) currentDayKey = key
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    androidx.compose.runtime.LaunchedEffect(journalSeq, dayOffset, currentDayKey) {
        val imported = vm.repo.journal("my-whoop", "0000-01-01", "9999-12-31")
        val native = vm.repo.journal(JOURNAL_DEVICE_ID, "0000-01-01", "9999-12-31")
        val entries = mergeJournalEntries(imported, native)
        val byBehaviour = mutableMapOf<String, MutableSet<String>>()
        // a numeric log writes answeredYes=true too, so a numeric item lands in the with/without
        // split here unchanged; its per-day value is captured separately for a numeric series the effect
        // ranker can consume like any metric outcome (dose-response lands in the v5 hub). Additive.
        val numericByBehaviour = mutableMapOf<String, MutableMap<String, Double>>()
        for (e in entries) {
            if (e.answeredYes) byBehaviour.getOrPut(e.question) { mutableSetOf() }.add(e.day)
            e.numericValue?.let { v -> numericByBehaviour.getOrPut(e.question) { mutableMapOf() }[e.day] = v }
        }
        behaviours = byBehaviour.mapValues { it.value.toSet() }
        numericJournalSeries = numericByBehaviour.mapValues { it.value.toMap() }
        importedQuestions = imported.map { it.question }.distinct()
        val key = journalDayKey(dayOffset)
        var answers = native.filter { it.day == key }.associate { it.question to it.answeredYes }
        // the selected day's numeric values (native-only; imported WHOOP rows carry none).
        dayNumeric = native.filter { it.day == key && it.numericValue != null }
            .associate { it.question to it.numericValue!! }
        // Pre-fill from last night when opening today's journal with no entries yet, makes
        // recurring patterns (e.g. no alcohol, read before bed) one tap to confirm instead of re-enter.
        if (answers.isEmpty() && dayOffset == 0L) {
            val yesterdayAnswers = native
                .filter { it.day == journalDayKey(1L) }
                .associate { it.question to it.answeredYes }
            if (yesterdayAnswers.isNotEmpty()) {
                // Upsert real rows for today so the effects engine counts the day as logged
                // and onClear can delete the row it finds. Without this the chips looked
                // pre-filled but no row existed, so "confirm" persisted nothing and
                // "clear" tried to delete a phantom.
                vm.repo.upsertJournal(yesterdayAnswers.map { (q, yes) ->
                    JournalEntry(JOURNAL_DEVICE_ID, key, q, yes)
                })
                answers = yesterdayAnswers
                preFilledFromYesterday = true
            } else {
                preFilledFromYesterday = false
            }
        } else {
            preFilledFromYesterday = false
        }
        dayAnswers = answers
        journalLoaded = true
    }

    // Selected outcome metric for the behaviour-effects half.
    var outcome by remember { mutableStateOf(Outcome.Recovery) }

    // --- Personal-experiment state (LOCAL ONLY, SharedPreferences). `experimentSeq`
    //     bumps after a save so the snapshot and compliance refresh immediately,
    //     matching the journal card's pattern. ---
    var experimentSeq by remember { mutableStateOf(0) }
    var experimentBehaviour by remember { mutableStateOf(loadExperimentString(ctx, EXP_BEHAVIOUR)) }
    var experimentOutcomeName by remember { mutableStateOf(loadExperimentString(ctx, EXP_OUTCOME)) }
    var experimentStartedDay by remember { mutableStateOf(loadExperimentString(ctx, EXP_STARTED)) }
    var experimentDurationDays by remember { mutableStateOf(loadExperimentInt(ctx, EXP_DURATION, ExperimentLength.TwoWeeks.days)) }
    var experimentBaselineDays by remember { mutableStateOf(loadExperimentInt(ctx, EXP_BASELINE, ExperimentLength.TwoWeeks.days)) }

    // Build outcome day-maps + ordered series off the cached daily metrics. Cheap and
    // recomputed only when `days` changes (not on every recomposition).
    val model = remember(days, behaviours, numericJournalSeries) { buildModel(days, behaviours, numericJournalSeries) }

    // Ranked behaviour effects for the current outcome (recomputed when outcome/data change).
    val ranked = remember(model, outcome) { rankEffects(model, outcome) }
    // Curated relationships (independent of the selected outcome).
    val relationships = remember(model) { computeRelationships(model) }

    // --- Activity Cost: the engine is pure + unit-tested; shape its inputs HERE. ---
    // Load the sessions (ALL sources, dismissed-filtered) the same way the Workouts screen does, then
    // build [sport: Set<localDayKey>] + [localDayKey: Charge] and rank the per-sport recovery cost.
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadWorkouts() }
    val activityCosts = remember(workouts, days) { computeActivityCosts(workouts, days) }

    // PERF: migrate to the lazy scaffold so only on-screen sections compose + get
    // accessibility-walked. Each eager child becomes its own `item { }`, INCLUDING the standalone
    // `Spacer(sectionGap - 20)` separators, which are real Column children that already sat inside the
    // eager `spacedBy(20.dp)`; a LazyColumn with the same `spacedBy(20.dp)` flanks each item identically,
    // so the rendered spacing is byte-for-byte unchanged. Conditional sections use `if (cond) { item {} }`
    // (never `item { if (cond) }`) so a hidden section emits NO item, an unconditional empty item would
    // otherwise insert a 0-height row that the 20dp arrangement flanks, shifting layout. The one
    // composable-only block (`run { … remember(snapshot) … }`) moves inside its `item { }` (which is
    // @Composable). Order is preserved exactly.
    // No topBackground: the scaffold paints the theme canvas (Palette.surfaceBase) so the cards read the
    // same in both schemes.
    LazyScreenScaffold(
        title = stringResource(R.string.insights_title),
        subtitle = stringResource(R.string.insights_subtitle),
    ) {

        // --- "What moves you" deep-link into the v5 Insights Hub (ranked, lag-aware ranked-effect feed +
        //     personal alcohol/caffeine dose-response). The honest in-Insights entry point; the hub is its
        //     own destination too. ---
        item { WhatMovesYouLink(onOpen = onOpenInsightsHub) }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Native journal logging (always reachable, the account-free way in) ---
        if (preFilledFromYesterday) {
            item {
            Text(
                stringResource(R.string.insights_prefilled_from_last_night),
                style = NoopType.footnote,
                color = Palette.textTertiary,
                modifier = Modifier.fillMaxWidth(),
            )
            }
        }
        item {
        // Persist a mutated catalog list and refresh state (the pure edit helpers never touch the
        // canonical key, so a rename/regroup/convert keeps history joined).
        fun applyCatalog(next: List<JournalCatalogItem>) {
            saveJournalCatalogItems(ctx, next)
            catalogItems = next
        }
        JournalLogCard(
            items = resolveJournalItems(importedQuestions, catalogItems, includeHidden = false),
            answers = dayAnswers,
            numericAnswers = dayNumeric,
            dayOffset = dayOffset,
            onDayOffset = { dayOffset = it },
            onAnswer = { q, yes ->
                scope.launch {
                    vm.repo.upsertJournal(
                        listOf(JournalEntry(JOURNAL_DEVICE_ID, journalDayKey(dayOffset), q, yes)),
                    )
                    journalSeq++
                }
            },
            onNumeric = { q, value ->
                scope.launch {
                    // A numeric log writes answeredYes=true AND the value, so the effects engine
                    // counts the day as logged and the with/without split is unchanged.
                    vm.repo.upsertJournal(
                        listOf(JournalEntry(JOURNAL_DEVICE_ID, journalDayKey(dayOffset), q,
                            answeredYes = true, numericValue = value)),
                    )
                    journalSeq++
                }
            },
            onClear = { q ->
                scope.launch {
                    vm.repo.deleteJournalEntry(JOURNAL_DEVICE_ID, journalDayKey(dayOffset), q)
                    journalSeq++
                }
            },
            onAddCustom = { q, kind, group -> applyCatalog(addCustomJournalItem(catalogItems, q, kind, group)) },
            onRename = { q, name -> applyCatalog(renameJournalItem(catalogItems, q, name)) },
            onSetGroup = { q, group -> applyCatalog(setJournalItemGroup(catalogItems, q, group)) },
            onSetKind = { q, kind -> applyCatalog(setJournalItemKind(catalogItems, q, kind)) },
            onRemoveQuestion = { q -> applyCatalog(removeJournalItem(catalogItems, q)) },
            onRestoreQuestion = { q -> applyCatalog(restoreJournalItem(catalogItems, q)) },
        )
        }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Mind: daily mood check-in + mood ↔ body correlations ---
        item { MindSection(vm) }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Caffeine window, log an intake + a rough on-device "still active"
        //     hint. Self-contained (owns its own SharedPreferences state). Opt-in: shows
        //     nothing until the user logs one. ---
        item { CaffeineLogCard() }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Personal experiment (LOCAL ONLY n-of-1 protocol) ------------------
        item {
        run {
            // Candidates are gated to behaviours the user actually has data for, 
            // logged journal questions ∪ imported wording, minus hidden, NOT the
            // starter catalog (triage fix a/b). Empty → real empty-state guard.
            // Hidden canonicals come from the v2 catalog now, same triage-fix semantics.
            val hiddenQuestions = catalogItems.filter { it.hidden }.map { it.canonical }
            val candidates = experimentCandidates(behaviours, importedQuestions, hiddenQuestions, experimentBehaviour)
            val expOutcome = Outcome.entries.firstOrNull { it.outcomeName == experimentOutcomeName } ?: Outcome.Recovery
            val resolvedBehaviour = resolveExperimentBehaviour(candidates, experimentBehaviour)
            val snapshot = remember(model, behaviours, experimentStartedDay, experimentOutcomeName, experimentDurationDays, experimentBaselineDays, experimentSeq) {
                buildExperimentSnapshot(
                    model = model,
                    behaviours = behaviours,
                    startedDay = experimentStartedDay,
                    outcome = expOutcome,
                    behaviour = resolvedBehaviour,
                    durationDays = experimentDurationDays,
                    baselineDays = experimentBaselineDays,
                )
            }

            ExperimentSection(
                snapshot = snapshot,
                candidates = candidates,
                resolvedBehaviour = resolvedBehaviour,
                outcome = expOutcome,
                length = ExperimentLength.fromDays(experimentDurationDays),
                onBehaviour = {
                    experimentBehaviour = it
                    saveExperimentString(ctx, EXP_BEHAVIOUR, it)
                },
                onOutcome = {
                    experimentOutcomeName = it.outcomeName
                    saveExperimentString(ctx, EXP_OUTCOME, it.outcomeName)
                },
                onLength = {
                    experimentDurationDays = it.days
                    saveExperimentInt(ctx, EXP_DURATION, it.days)
                },
                onStart = {
                    val behaviour = resolvedBehaviour
                    if (behaviour != null) {
                        experimentBehaviour = behaviour
                        saveExperimentString(ctx, EXP_BEHAVIOUR, behaviour)
                        experimentBaselineDays = experimentDurationDays
                        saveExperimentInt(ctx, EXP_BASELINE, experimentDurationDays)
                        val today = journalDayKey(0L)
                        experimentStartedDay = today
                        saveExperimentString(ctx, EXP_STARTED, today)
                    }
                },
                onEnd = {
                    experimentStartedDay = ""
                    saveExperimentString(ctx, EXP_STARTED, "")
                },
                onMark = { answeredYes ->
                    val behaviour = snapshot?.behavior
                    if (behaviour != null) {
                        scope.launch {
                            vm.repo.upsertJournal(
                                listOf(JournalEntry(JOURNAL_DEVICE_ID, journalDayKey(0L), behaviour, answeredYes)),
                            )
                            journalSeq++       // refresh behaviours map (logged-today, compliance)
                            experimentSeq++    // refresh the snapshot
                        }
                    }
                },
            )
        }
        }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Behaviour effects -------------------------------------------------
        // (Always emits one of three branches → one unconditional item, never empty.)
        item {
        if (!journalLoaded) {
            NoopCard {
                Text(
                    stringResource(R.string.insights_reading_journal),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else if (behaviours.isEmpty()) {
            // No journal yet, explain, without dead-ending on a paid export.
            DataPendingNote(
                title = stringResource(R.string.insights_no_journal_title),
                body = stringResource(R.string.insights_no_journal_body),
            )
        } else {
            BehaviourSection(
                outcome = outcome,
                onOutcome = { outcome = it },
                ranked = ranked,
            )
        }
        }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Activity Cost (what each activity costs your recovery) ------------
        item { ActivityCostSection(activityCosts) }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Metric relationships ---------------------------------------------
        item { RelationshipsSection(relationships) }
    }
}

// MARK: - "What moves you" deep-link
//
// A single NoopCard row into the v5 Insights Hub, the ranked, lag-aware "which of your habits actually
// move your Charge" feed plus the personal alcohol/caffeine dose-response. Charge-world wash (chargeColor
// tint), an accent auto_awesome glyph in a soft rounded chip, a short lag-aware blurb, and a trailing
// chevron. One combined accessibility label so screen readers announce it as a single link.

@Composable
private fun WhatMovesYouLink(onOpen: () -> Unit) {
    // The SAME interactionSource drives the clickable and the press response; indication is nulled so
    // only the settle reads, with no ripple over it.
    val interaction = remember { MutableInteractionSource() }
    val linkDescription = stringResource(R.string.insights_what_moves_you_a11y)
    NoopCard(
        tint = Palette.chargeColor,
        modifier = Modifier
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .liquidPress(interaction)
            .semantics { contentDescription = linkDescription },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Palette.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Palette.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                // WHOOP tappable-card title: UPPERCASE tracked WHITE label + a trailing "›" chevron
                // glyph. The descriptive line sits beneath.
                Overline(stringResource(R.string.insights_what_moves_you_overline), color = Palette.textPrimary)
                Text(
                    stringResource(R.string.insights_what_moves_you_blurb),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// MARK: - Activity Cost section
//
// "What each activity costs your recovery": one ranked NoopCard per sport that cleared the engine's
// minSessions gate, each carrying next-morning Charge vs rest baseline, days-to-baseline, the sample
// count + confidence pill, and the engine's plain-English sentence. Sign-aware tint: a positive cost
// (recovery dipped) reads warm/critical, a recovery-POSITIVE delta reads green.

/**
 * Shape the [ActivityCostEngine] inputs from the loaded sessions + cached daily metrics, then rank.
 * [workouts] → [sport: Set<localDayKey>] (displaySport collapses detected/"Activity" into one bucket
 * and de-camelCases WHOOP names; manual/imported labels pass through), keyed by the LOCAL calendar
 * day the session STARTED via the SAME AnalyticsEngine.dayString path [DailyMetric.day] uses, so the
 * engine's D+1 next-morning alignment is honest. [days] → [localDayKey: Charge] off DailyMetric.recovery.
 */
internal fun computeActivityCosts(
    workouts: List<WorkoutRow>,
    days: List<DailyMetric>,
): List<com.noop.analytics.ActivityCost> {
    // Single "now" offset for every session, the SAME tz-offset basis IntelligenceEngine.kt uses to
    // key DailyMetric.day (getOffset(now)/1000 applied across the run), via the SAME
    // AnalyticsEngine.dayString(ts, offsetSec) path, so the engine's D+1 next-morning lookups align
    // byte-for-byte with the recovery keys.
    val offsetSec = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1_000L
    val activityDaysBySport = HashMap<String, MutableSet<String>>()
    for (w in workouts) {
        val sport = WorkoutEditing.displaySport(w.sport)
        if (sport.isEmpty()) continue
        val day = com.noop.analytics.AnalyticsEngine.dayString(w.startTs, offsetSec)
        activityDaysBySport.getOrPut(sport) { mutableSetOf() }.add(day)
    }
    val recoveryByDay = HashMap<String, Double>()
    for (d in days) {
        d.recovery?.let { recoveryByDay[d.day] = it }
    }
    return com.noop.analytics.ActivityCostEngine.evaluate(
        activityDaysBySport = activityDaysBySport.mapValues { it.value.toSet() },
        recoveryByDay = recoveryByDay,
    )
}

@Composable
private fun ActivityCostSection(costs: List<com.noop.analytics.ActivityCost>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.insights_activity_cost),
            overline = stringResource(R.string.insights_activity_cost_overline),
        )
        if (costs.isEmpty()) {
            NoopCard {
                Text(
                    stringResource(R.string.insights_activity_cost_empty),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        } else {
            // Fade + rise the ranked cost cards in sequence (staggeredAppear).
            costs.forEachIndexed { i, cost ->
                Box(modifier = Modifier.staggeredAppear(i)) { ActivityCostCard(cost) }
            }
        }
    }
}

@Composable
private fun ActivityCostCard(cost: com.noop.analytics.ActivityCost) {
    // Sign-aware accent: a POSITIVE delta means the next morning sat BELOW baseline (it cost you) →
    // warm/critical; a negative delta means you woke higher → green. Near-zero reads neutral gold so
    // "barely moves" doesn't shout either way.
    val costing = cost.delta >= com.noop.analytics.ActivityCostEngine.barelyMovesPoints
    val lifting = cost.delta <= -com.noop.analytics.ActivityCostEngine.barelyMovesPoints
    val accent: Color = when {
        costing -> Palette.statusCritical
        lifting -> Palette.statusPositive
        else -> Palette.chargeColor
    }
    val solid = cost.confidence == com.noop.analytics.ScoreConfidence.SOLID
    val pointsLabel = (if (cost.delta >= 0) "−" else "+") + abs(cost.delta).roundToInt()

    NoopCard(tint = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    sportIcon(cost.sport),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
                Spacer(Modifier.width(Metrics.space8))
                Text(
                    cost.sport,
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatePill(
                    stringResource(if (solid) R.string.insights_pill_solid else R.string.insights_pill_building),
                    tone = if (solid) StrandTone.Positive else StrandTone.Accent,
                    showsDot = false,
                )
            }
            Text(activityCostSentence(cost), style = NoopType.subhead, color = Palette.textSecondary)
            // 2×2 StatTile grid so tile heights stay uniform on phone widths (matches SummarySection).
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.insights_activity_next_morning),
                    value = "${cost.meanNextMorning.roundToInt()}",
                    caption = stringResource(R.string.insights_activity_charge_pts, pointsLabel),
                    accent = accent,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.insights_activity_rest_baseline),
                    value = "${cost.baselineMean.roundToInt()}",
                    caption = stringResource(R.string.insights_activity_untouched_days),
                    accent = Palette.textPrimary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.insights_activity_bounce_back),
                    value = cost.daysToBaseline?.let { "${it}d" } ?: "—",
                    caption = if (cost.daysToBaseline != null) {
                        stringResource(R.string.insights_activity_to_baseline)
                    } else {
                        stringResource(R.string.insights_activity_not_within_7d)
                    },
                    accent = Palette.chargeColor,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.insights_activity_sessions),
                    value = "${cost.n}",
                    caption = stringResource(
                        if (solid) R.string.insights_caption_solid else R.string.insights_caption_building,
                    ),
                    accent = Palette.textPrimary,
                )
            }
        }
    }
}

/**
 * What a sport costs the next morning. The engine measured the delta and the bounce-back; the
 * direction and whether a bounce-back exists each select a whole sentence, with the point and day
 * counts injected as their own plurals.
 */
@Composable
private fun activityCostSentence(cost: com.noop.analytics.ActivityCost): String {
    val magnitude = abs(cost.delta)
    if (magnitude < com.noop.analytics.ActivityCostEngine.barelyMovesPoints) {
        return stringResource(R.string.narr_activity_barely_moves, cost.n)
    }
    val points = magnitude.roundToInt()
    val pointsText = pluralStringResource(R.plurals.narr_activity_points, points, points)
    val costs = cost.delta >= 0
    val days = cost.daysToBaseline
    if (days == null) {
        val res = if (costs) R.string.narr_activity_cost else R.string.narr_activity_lift
        return stringResource(res, pointsText, cost.n)
    }
    val daysText = pluralStringResource(R.plurals.narr_activity_days, days, days)
    val res = if (costs) R.string.narr_activity_cost_bounce else R.string.narr_activity_lift_bounce
    return stringResource(res, pointsText, daysText, cost.n)
}

// MARK: - Behaviour effects section

@Composable
private fun BehaviourSection(
    outcome: Outcome,
    onOutcome: (Outcome) -> Unit,
    ranked: List<BehaviorEffect>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Four segments need the full row, so the heading sits above the control rather than in the
        // sliver a shared row leaves it.
        val labels = outcomeLabels()
        SectionHeader(
            stringResource(R.string.insights_behaviour_effects),
            overline = stringResource(R.string.insights_what_moves_your, outcome.lowerName()),
        )
        SegmentedPillControl(
            items = Outcome.entries.toList(),
            selection = outcome,
            label = { labels.getValue(it) },
            onSelect = onOutcome,
        )

        if (ranked.isEmpty()) {
            NoopCard {
                Text(
                    stringResource(R.string.insights_behaviour_no_overlap, outcome.lowerName()),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else {
            // Fade + rise the ranked cards in sequence (staggeredAppear).
            ranked.forEachIndexed { i, e ->
                Box(modifier = Modifier.staggeredAppear(i)) { EffectCard(e, outcome) }
            }
        }
    }
}

/** One behaviour-effect card: sentence + with/without StatTiles + significance pill. */
@Composable
private fun EffectCard(e: BehaviorEffect, outcome: Outcome) {
    // Sign-aware tint: did this behaviour move the outcome the GOOD way?
    val movedGood: Boolean? = when {
        e.delta == 0.0 -> null
        else -> (e.delta > 0) == outcome.higherIsBetter
    }
    val tone: StrandTone = when (movedGood) {
        null -> StrandTone.Neutral
        true -> StrandTone.Positive
        false -> if (e.significant) StrandTone.Critical else StrandTone.Warning
    }
    val tintColor = tone.color
    val arrow = if (e.delta > 0) "↑" else if (e.delta < 0) "↓" else "→"
    // The delta reads between the two tiles, so it takes their formatter.
    val deltaText = "$arrow ${outcome.format(abs(e.delta))}"
    val sentence = effectSentence(e, outcome)

    // The card wash reads as the OUTCOME's colour world (so the whole Behaviour Effects
    // section sits in one world), while the dot / StatTile accents stay sign-aware.
    NoopCard(tint = outcome.domain.color) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {

            // Header: behaviour name (tinted dot) + significance pill.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .drawBehind { drawCircle(tintColor) },
                    )
                    Text(
                        e.behavior,
                        style = NoopType.headline,
                        color = Palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatePill(
                    stringResource(
                        if (e.significant) {
                            R.string.insights_pill_significant
                        } else {
                            R.string.insights_pill_exploratory
                        },
                    ),
                    tone = if (e.significant) StrandTone.Positive else StrandTone.Neutral,
                    showsDot = false,
                )
            }

            // Plain-English sentence.
            Text(sentence, style = NoopType.body, color = Palette.textSecondary)

            // With / without means as uniform StatTiles.
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.insights_with),
                    value = outcome.format(e.meanWith),
                    caption = stringResource(R.string.insights_n_count, e.nWith),
                    accent = tintColor,
                    delta = deltaText,
                    deltaColor = tintColor,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.insights_without),
                    value = outcome.format(e.meanWithout),
                    caption = stringResource(R.string.insights_n_count, e.nWithout),
                    accent = Palette.textPrimary,
                )
            }

            HorizontalDivider(color = Palette.hairline)

            // Effect-size footer: Cohen's d + magnitude word.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Overline(stringResource(R.string.insights_effect_size), modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.insights_cohens_d, String.format(Locale.US, "%.2f", e.cohensD)),
                    style = NoopType.captionNumber,
                    color = tintColor,
                )
                Spacer(Modifier.width(Metrics.space6))
                Text(
                    effectMagnitudeWord(e.cohensD),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

// MARK: - Personal experiment section
//
// A LOCAL-ONLY n-of-1 protocol:
// pick ONE behaviour you actually log, one outcome, and a short window, then compare
// the outcome on days you logged the behaviour (the intervention) against your
// behaviour-ABSENT days before the start (the baseline). The absent-day baseline
// matches the with/without model used by Behaviour Effects above, so "Baseline" vs
// "Intervention" is an honest present-vs-absent contrast, not a raw pre/post window.
// Nothing leaves the device: state is SharedPreferences and "Mark done" writes a
// normal journal answer.

/** One experiment window length (and the matching baseline span). */
private enum class ExperimentLength(val days: Int, @StringRes val labelRes: Int) {
    OneWeek(7, R.string.insights_window_7d),
    TwoWeeks(14, R.string.insights_window_14d),
    FourWeeks(28, R.string.insights_window_28d);

    companion object {
        fun fromDays(d: Int): ExperimentLength = entries.firstOrNull { it.days == d } ?: TwoWeeks
    }
}

/** A snapshot of the running experiment, computed off the cached outcome day-maps. */
private data class ExperimentSnapshot(
    val behavior: String,
    val outcome: Outcome,
    val startDay: String,
    val durationDays: Int,
    val daysElapsed: Int,
    val baselineMean: Double?,
    val baselineCount: Int,
    val interventionMean: Double?,
    val interventionCount: Int,
    val loggedToday: Boolean,
    /** 0…100 percent. */
    val compliance: Double,
    val confidence: ExperimentConfidence,
) {
    val progress: Float get() = (daysElapsed.toFloat() / durationDays.coerceAtLeast(1)).coerceIn(0f, 1f)
    val complete: Boolean get() = daysElapsed >= durationDays
    val phaseTone: StrandTone get() =
        if (complete) StrandTone.Positive else StrandTone.Accent
    val delta: Double? get() {
        val i = interventionMean ?: return null
        val b = baselineMean ?: return null
        return i - b
    }
}

/** The window chip: "COMPLETE" once the run is over, else the day counter. */
@Composable
private fun ExperimentSnapshot.phaseLabel(): String =
    if (complete) {
        stringResource(R.string.insights_experiment_complete)
    } else {
        stringResource(R.string.insights_experiment_day_of, daysElapsed, durationDays)
    }

private data class ExperimentConfidence(@StringRes val labelRes: Int, val tone: StrandTone)

@Composable
private fun ExperimentSection(
    snapshot: ExperimentSnapshot?,
    candidates: List<String>,
    resolvedBehaviour: String?,
    outcome: Outcome,
    length: ExperimentLength,
    onBehaviour: (String) -> Unit,
    onOutcome: (Outcome) -> Unit,
    onLength: (ExperimentLength) -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onMark: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.insights_personal_experiment),
            overline = stringResource(R.string.insights_experiment_overline),
            trailing = if (snapshot != null) {
                snapshot.phaseLabel()
            } else {
                stringResource(R.string.insights_experiment_setup)
            },
        )
        NoopCard {
            if (snapshot != null) {
                ActiveExperimentCard(snapshot, onMark = onMark, onEnd = onEnd)
            } else {
                ExperimentSetupCard(
                    candidates = candidates,
                    resolvedBehaviour = resolvedBehaviour,
                    outcome = outcome,
                    length = length,
                    onBehaviour = onBehaviour,
                    onOutcome = onOutcome,
                    onLength = onLength,
                    onStart = onStart,
                )
            }
        }
    }
}

@Composable
private fun ExperimentSetupCard(
    candidates: List<String>,
    resolvedBehaviour: String?,
    outcome: Outcome,
    length: ExperimentLength,
    onBehaviour: (String) -> Unit,
    onOutcome: (Outcome) -> Unit,
    onLength: (ExperimentLength) -> Unit,
    onStart: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // The chip shares the TITLE's line only. Wrapped around the paragraph too, it left the body
        // running at half the card's width with a tall empty column beside it.
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.insights_experiment_run_title),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Metrics.space12))
                StatePill(
                    stringResource(R.string.insights_pill_local_only),
                    tone = StrandTone.Neutral,
                    showsDot = false,
                )
            }
            Text(
                stringResource(R.string.insights_experiment_run_blurb),
                style = NoopType.subhead,
                color = Palette.textSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (candidates.isEmpty()) {
            Text(
                stringResource(R.string.insights_experiment_needs_behaviour),
                style = NoopType.subhead,
                color = Palette.textTertiary,
            )
        } else {
            val labels = outcomeLabels()
            val lengthLabels = HashMap<ExperimentLength, String>()
            for (l in ExperimentLength.entries) lengthLabels[l] = stringResource(l.labelRes)
            val startLabel = stringResource(R.string.insights_experiment_start)
            ExperimentField(stringResource(R.string.insights_field_behaviour)) {
                ExperimentBehaviourPicker(
                    candidates = candidates,
                    selection = resolvedBehaviour ?: candidates.first(),
                    onSelect = onBehaviour,
                )
            }
            ExperimentField(stringResource(R.string.insights_field_outcome)) {
                SegmentedPillControl(
                    items = Outcome.entries.toList(),
                    selection = outcome,
                    label = { labels.getValue(it) },
                    onSelect = onOutcome,
                )
            }
            ExperimentField(stringResource(R.string.insights_field_window)) {
                SegmentedPillControl(
                    items = ExperimentLength.entries.toList(),
                    selection = length,
                    label = { lengthLabels.getValue(it) },
                    onSelect = onLength,
                )
            }

            // Unified button system: a primary, full-width NoopButton.
            NoopButton(
                text = startLabel,
                leadingIcon = Icons.Filled.Science,
                kind = NoopButtonKind.Primary,
                fullWidth = true,
                enabled = resolvedBehaviour != null,
                onClick = onStart,
                modifier = Modifier.semantics { contentDescription = startLabel },
            )
        }
    }
}

@Composable
private fun ActiveExperimentCard(
    snapshot: ExperimentSnapshot,
    onMark: (Boolean) -> Unit,
    onEnd: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    snapshot.behavior,
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Metrics.space4))
                Text(
                    stringResource(
                        R.string.insights_experiment_started,
                        snapshot.startDay,
                        snapshot.outcome.lowerName(),
                    ),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            Spacer(Modifier.width(Metrics.space12))
            StatePill(
                snapshot.phaseLabel(),
                tone = snapshot.phaseTone,
                pulsing = snapshot.daysElapsed < snapshot.durationDays,
            )
        }

        Text(
            experimentReading(snapshot),
            style = NoopType.body,
            color = Palette.textSecondary,
        )

        // Baseline / Intervention / Change / Compliance measures.
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            ExperimentMeasure(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.insights_measure_baseline),
                value = snapshot.baselineMean?.let { snapshot.outcome.format(it) } ?: "—",
                caption = stringResource(R.string.insights_days_without_it, snapshot.baselineCount),
                tint = Palette.textSecondary,
            )
            ExperimentMeasure(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.insights_measure_intervention),
                value = snapshot.interventionMean?.let { snapshot.outcome.format(it) } ?: "—",
                caption = stringResource(R.string.insights_logged_days, snapshot.interventionCount),
                tint = Palette.accent,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            ExperimentMeasure(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.insights_measure_change),
                value = formatExperimentDelta(snapshot.delta, snapshot.outcome),
                caption = stringResource(
                    if (snapshot.delta == null) {
                        R.string.insights_delta_needs_baseline
                    } else {
                        R.string.insights_delta_vs_baseline
                    },
                ),
                tint = experimentDeltaColor(snapshot),
            )
            ExperimentMeasure(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.insights_measure_compliance),
                value = "${snapshot.compliance.roundToInt()}%",
                caption = stringResource(
                    if (snapshot.loggedToday) {
                        R.string.insights_logged_today
                    } else {
                        R.string.insights_not_logged_today
                    },
                ),
                tint = if (snapshot.loggedToday) Palette.statusPositive else Palette.statusWarning,
            )
        }

        // Progress bar + day count + confidence pill.
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
            val progressDescription = stringResource(
                R.string.insights_experiment_progress_a11y,
                snapshot.daysElapsed,
                snapshot.durationDays,
            )
            // Day N of the experiment window, filled to `snapshot.progress` in the accent tint.
            LinearProgressIndicator(
                progress = { snapshot.progress },
                color = Palette.accent,
                trackColor = Palette.surfaceInset,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Metrics.progressHeight)
                    .semantics { contentDescription = progressDescription },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        R.string.insights_experiment_days_of,
                        snapshot.daysElapsed,
                        snapshot.durationDays,
                    ),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                StatePill(
                    stringResource(snapshot.confidence.labelRes),
                    tone = snapshot.confidence.tone,
                    showsDot = false,
                )
            }
        }

        // Mark done / Skip / End, all routed through the unified NoopButton (primary /
        // secondary / destructive kinds, with leading icons).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val markDoneA11y = stringResource(R.string.insights_mark_done_a11y)
            val skipA11y = stringResource(R.string.insights_skip_a11y)
            val endA11y = stringResource(R.string.insights_end_a11y)
            NoopButton(
                text = stringResource(R.string.insights_mark_done),
                leadingIcon = Icons.Filled.CheckCircle,
                kind = NoopButtonKind.Primary,
                enabled = !snapshot.loggedToday,
                onClick = { onMark(true) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = markDoneA11y },
            )
            NoopButton(
                text = stringResource(R.string.insights_skip),
                leadingIcon = Icons.Filled.Close,
                kind = NoopButtonKind.Secondary,
                onClick = { onMark(false) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = skipA11y },
            )
            NoopButton(
                text = stringResource(R.string.insights_end),
                leadingIcon = Icons.Filled.Stop,
                kind = NoopButtonKind.Destructive,
                onClick = onEnd,
                modifier = Modifier.semantics { contentDescription = endA11y },
            )
        }
    }
}

/** A labelled inset well wrapping one setup control. */
@Composable
private fun ExperimentField(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.surfaceInset)
            .border(Metrics.divider, Palette.hairline, RoundedCornerShape(8.dp))
            .padding(Metrics.space12),
        verticalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        Overline(title, color = Palette.textTertiary)
        content()
    }
}

/** One inset measure cell: label, big number, caption. */
@Composable
private fun ExperimentMeasure(
    label: String,
    value: String,
    caption: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .heightIn(min = 92.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.surfaceInset)
            .border(Metrics.divider, Palette.hairline, RoundedCornerShape(8.dp))
            .padding(Metrics.space12),
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Text(label, style = NoopType.caption, color = Palette.textTertiary, maxLines = 1)
        Text(
            value,
            style = NoopType.number(22f),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(caption, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 2)
    }
}

/** A menu-style behaviour picker. */
@Composable
private fun ExperimentBehaviourPicker(
    candidates: List<String>,
    selection: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // The tappable picker row shares one interactionSource between the clickable and the press response;
    // indication is nulled so only the settle reads.
    val interaction = remember { MutableInteractionSource() }
    val pickerDescription = stringResource(R.string.insights_experiment_behaviour_a11y, selection)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.surfaceBase)
                .border(Metrics.divider, Palette.hairline, RoundedCornerShape(6.dp))
                .clickable(interactionSource = interaction, indication = null) { expanded = true }
                .liquidPress(interaction)
                .padding(horizontal = Metrics.space12, vertical = 8.dp)
                .semantics { contentDescription = pickerDescription },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selection,
                style = NoopType.subhead,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("▾", style = NoopType.subhead, color = Palette.textTertiary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            candidates.forEach { q ->
                DropdownMenuItem(
                    text = { Text(q, style = NoopType.subhead, color = Palette.textPrimary) },
                    onClick = {
                        onSelect(q)
                        expanded = false
                    },
                )
            }
        }
    }
}

// MARK: - Experiment computation

/**
 * Behaviours the user actually has data for: distinct logged journal questions
 * (`behaviours.keys`) ∪ imported-export questions, minus the catalog's hidden set.
 * Triage fix (a)/(b): we do NOT route this through `mergeJournalCatalog`, which would
 * inject the whole starter catalog (and re-surface hidden behaviours), so only
 * behaviours with real history are eligible, and the empty-state guard is real.
 */
private fun experimentCandidates(
    behaviours: Map<String, Set<String>>,
    importedQuestions: List<String>,
    hidden: List<String>,
    saved: String,
): List<String> {
    val hiddenSet = hidden.map { it.trim().lowercase(Locale.US) }.toHashSet()
    val savedTrim = saved.trim()
    val raw = behaviours.keys.sorted() + importedQuestions +
        (if (savedTrim.isEmpty()) emptyList() else listOf(savedTrim))
    val seen = HashSet<String>()
    val out = mutableListOf<String>()
    for (q in raw) {
        val t = q.trim()
        val key = t.lowercase(Locale.US)
        if (t.isNotEmpty() && key !in hiddenSet && seen.add(key)) out.add(t)
    }
    return out
}

/** The saved behaviour if still eligible, else the first candidate (or null when empty). */
private fun resolveExperimentBehaviour(candidates: List<String>, saved: String): String? {
    val savedTrim = saved.trim()
    if (savedTrim.isNotEmpty() && candidates.contains(savedTrim)) return savedTrim
    return candidates.firstOrNull()
}

private fun buildExperimentSnapshot(
    model: InsightModel,
    behaviours: Map<String, Set<String>>,
    startedDay: String,
    outcome: Outcome,
    behaviour: String?,
    durationDays: Int,
    baselineDays: Int,
): ExperimentSnapshot? {
    if (startedDay.isEmpty() || behaviour == null) return null

    val today = journalDayKey(0L)
    val duration = durationDays.coerceAtLeast(1)
    val outcomeDays = model.outcomeByDay[outcome] ?: emptyMap()
    val loggedDays = behaviours[behaviour] ?: emptySet()

    // Baseline = behaviour-ABSENT days BEFORE the start (with/without model, matching
    // Behaviour Effects). Restricting to absent days is triage fix (c): "Baseline" vs
    // "Intervention" is an honest present-vs-absent contrast, not a raw pre/post window.
    val baselineKeys = outcomeDays.keys
        .filter { it < startedDay && it !in loggedDays }
        .sorted()
        .takeLast(baselineDays.coerceAtLeast(1))
    // Intervention = the first `duration` outcome days from the start where the
    // behaviour WAS logged.
    val interventionWindow = outcomeDays.keys
        .filter { it >= startedDay && it <= today }
        .sorted()
        .take(duration)
    val interventionKeys = interventionWindow.filter { it in loggedDays }

    val baselineValues = baselineKeys.mapNotNull { outcomeDays[it] }
    val interventionValues = interventionKeys.mapNotNull { outcomeDays[it] }
    val daysElapsed = (dayDistance(startedDay, today) + 1).coerceIn(1, duration)
    val complianceFraction = interventionKeys.size.toDouble() / daysElapsed.coerceAtLeast(1)
    val confidence = experimentConfidence(baselineValues.size, interventionValues.size, complianceFraction)

    return ExperimentSnapshot(
        behavior = behaviour,
        outcome = outcome,
        startDay = startedDay,
        durationDays = duration,
        daysElapsed = daysElapsed,
        baselineMean = mean(baselineValues),
        baselineCount = baselineValues.size,
        interventionMean = mean(interventionValues),
        interventionCount = interventionValues.size,
        loggedToday = today in loggedDays,
        compliance = complianceFraction * 100,
        confidence = confidence,
    )
}

private fun experimentConfidence(
    baselineCount: Int,
    interventionCount: Int,
    compliance: Double,
): ExperimentConfidence {
    val paired = minOf(baselineCount, interventionCount)
    return when {
        paired >= 10 && compliance >= 0.65 ->
            ExperimentConfidence(R.string.insights_signal_stronger, StrandTone.Positive)
        paired >= 5 -> ExperimentConfidence(R.string.insights_signal_early, StrandTone.Accent)
        else -> ExperimentConfidence(R.string.insights_signal_low, StrandTone.Warning)
    }
}

@Composable
private fun experimentReading(s: ExperimentSnapshot): String {
    val delta = s.delta ?: return stringResource(R.string.insights_experiment_needs_days)
    val absStr = formatExperimentDelta(abs(delta), s.outcome, includeSign = false)
    val name = stringResource(s.outcome.nameRes)
    if (abs(delta) < 0.05) {
        return stringResource(R.string.insights_experiment_flat, name)
    }
    val movedGood = if (s.outcome.higherIsBetter) delta > 0 else delta < 0
    val direction = stringResource(
        if (movedGood) R.string.insights_direction_better else R.string.insights_direction_worse,
    )
    return stringResource(R.string.insights_experiment_reading, name, absStr, direction)
}

private fun experimentDeltaColor(s: ExperimentSnapshot): Color {
    val delta = s.delta
    if (delta == null || abs(delta) < 0.05) return Palette.textTertiary
    val movedGood = if (s.outcome.higherIsBetter) delta > 0 else delta < 0
    return if (movedGood) Palette.statusPositive else Palette.statusCritical
}

private fun formatExperimentDelta(delta: Double?, outcome: Outcome, includeSign: Boolean = true): String {
    if (delta == null) return "—"
    val prefix = if (!includeSign) "" else if (delta > 0) "+" else if (delta < 0) "−" else ""
    val v = abs(delta).roundToInt()
    return when (outcome) {
        Outcome.Recovery, Outcome.Sleep -> "$prefix$v%"
        Outcome.Hrv -> "$prefix$v ms"
        Outcome.Rhr -> "$prefix$v bpm"
    }
}

/** Whole-day distance between two yyyy-MM-dd keys (0 if either is unparseable). */
private fun dayDistance(start: String, end: String): Int {
    return try {
        ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end)).toInt()
    } catch (_: Exception) {
        0
    }
}

private fun mean(values: List<Double>): Double? =
    if (values.isEmpty()) null else RustScores.mean(values)

// MARK: - Experiment persistence (SharedPreferences)

private const val EXP_PREFS = "noop_prefs"
private const val EXP_BEHAVIOUR = "noop.experiment.behaviour"
private const val EXP_OUTCOME = "noop.experiment.outcome"
private const val EXP_STARTED = "noop.experiment.startedDay"
private const val EXP_DURATION = "noop.experiment.durationDays"
private const val EXP_BASELINE = "noop.experiment.baselineDays"

private fun loadExperimentString(context: Context, key: String): String =
    context.getSharedPreferences(EXP_PREFS, Context.MODE_PRIVATE).getString(key, "") ?: ""

private fun saveExperimentString(context: Context, key: String, value: String) {
    context.getSharedPreferences(EXP_PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
}

private fun loadExperimentInt(context: Context, key: String, default: Int): Int =
    context.getSharedPreferences(EXP_PREFS, Context.MODE_PRIVATE).getInt(key, default)

private fun saveExperimentInt(context: Context, key: String, value: Int) {
    context.getSharedPreferences(EXP_PREFS, Context.MODE_PRIVATE).edit().putInt(key, value).apply()
}

// MARK: - Metric relationships section

@Composable
private fun RelationshipsSection(rels: List<Relationship>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.insights_metric_relationships),
            overline = stringResource(R.string.insights_pearson_r),
        )

        if (rels.isEmpty()) {
            NoopCard {
                Text(
                    stringResource(R.string.insights_relationships_empty),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else {
            // Every curated relationship terminates in Charge, so the card sits in the
            // Charge (green) colour world via a faint wash.
            NoopCard(tint = DomainTheme.Charge.color) {
                Column {
                    rels.forEachIndexed { idx, rel ->
                        RelationshipRow(rel)
                        if (idx < rels.size - 1) {
                            HorizontalDivider(color = Palette.hairline)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationshipRow(rel: Relationship) {
    val strength = correlationColor(rel.r)
    val sentence = relationshipSentence(rel)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp)
            .clearAndSetSemantics { contentDescription = sentence },
        verticalArrangement = Arrangement.spacedBy(Metrics.space10),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        ) {
            Text(
                stringResource(rel.titleRes),
                style = NoopType.headline,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
                // Two lines: the longest pair names do not fit beside the r chip on one, and a
                // truncated metric name is not recoverable from the row.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.insights_r_value, String.format(Locale.US, "%+.2f", rel.r)),
                style = NoopType.number(16f),
                color = strength,
            )
            StatePill(
                stringResource(
                    if (rel.significant) R.string.insights_p_significant else R.string.insights_p_ns,
                ),
                tone = if (rel.significant) StrandTone.Accent else StrandTone.Neutral,
                showsDot = false,
            )
        }

        // r bar, centred zero, fills left (negative) / right (positive) by |r|.
        RBar(r = rel.r, color = strength)

        Text(sentence, style = NoopType.subhead, color = Palette.textSecondary)
        Text(stringResource(rel.blurbRes), style = NoopType.footnote, color = Palette.textTertiary)
    }
}

/**
 * A centred correlation bar: a faint inset track with a centre tick at zero, and a
 * coloured fill that grows left (negative r) or right (positive r) proportional to |r|.
 * There is no hover tooltip: the exact r value is already printed beside the title,
 * so the bar is never an unexplained coloured shape.
 */
@Composable
private fun RBar(r: Double, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(Metrics.space8)
            .clip(CircleShape)
            .drawBehind {
                val half = size.width / 2f
                val mag = (abs(r).coerceAtMost(1.0)).toFloat() * half
                // Inset track.
                drawLine(
                    color = Palette.surfaceInset,
                    start = Offset(size.height / 2f, size.height / 2f),
                    end = Offset(size.width - size.height / 2f, size.height / 2f),
                    strokeWidth = size.height,
                    cap = StrokeCap.Round,
                )
                // Centre tick.
                drawLine(
                    color = Palette.hairlineStrong,
                    start = Offset(half, 0f),
                    end = Offset(half, size.height),
                    strokeWidth = 1f,
                )
                // Value fill from centre outward.
                if (mag > 0f) {
                    val start = if (r >= 0) Offset(half, size.height / 2f)
                    else Offset(half - mag, size.height / 2f)
                    val end = if (r >= 0) Offset(half + mag, size.height / 2f)
                    else Offset(half, size.height / 2f)
                    drawLine(
                        color = color,
                        start = start,
                        end = end,
                        strokeWidth = size.height,
                        cap = StrokeCap.Round,
                    )
                }
            },
    )
}

// MARK: - Model building + math (simple, honest, no external analytics)

/** Build the per-outcome day maps + ordered series from cached daily metrics. */
private fun buildModel(
    days: List<DailyMetric>,
    behaviours: Map<String, Set<String>>,
    numericJournalSeries: Map<String, Map<String, Double>> = emptyMap(),
): InsightModel {
    val outcomeByDay = mutableMapOf<Outcome, Map<String, Double>>()
    val seriesByOutcome = mutableMapOf<Outcome, List<Pair<String, Double>>>()
    for (o in Outcome.entries) {
        // Oldest → newest; one value per day (DailyMetric PK is (deviceId, day)).
        val series = days.mapNotNull { d -> o.pick(d)?.let { d.day to it } }
        seriesByOutcome[o] = series
        outcomeByDay[o] = series.toMap()
    }
    return InsightModel(behaviours, outcomeByDay, seriesByOutcome, numericJournalSeries)
}

/** Rank behaviour effects for one outcome by |Cohen's d|, significant first. */
private fun rankEffects(model: InsightModel, outcome: Outcome): List<BehaviorEffect> {
    val outcomeDays = model.outcomeByDay[outcome] ?: emptyMap()
    if (outcomeDays.isEmpty()) return emptyList()

    val effects = model.behaviours.mapNotNull { (behaviour, yesDays) ->
        val with = mutableListOf<Double>()
        val without = mutableListOf<Double>()
        for ((day, value) in outcomeDays) {
            if (day in yesDays) with.add(value) else without.add(value)
        }
        // Need both groups to compare; require ≥2 each so a mean/SD is meaningful.
        if (with.size < 2 || without.size < 2) return@mapNotNull null
        BehaviorEffect(
            behavior = behaviour,
            meanWith = with.average(),
            meanWithout = without.average(),
            nWith = with.size,
            nWithout = without.size,
            cohensD = cohensD(with, without),
        )
    }
    return effects.sortedWith(
        compareByDescending<BehaviorEffect> { it.significant }
            .thenByDescending { abs(it.cohensD) },
    )
}

/** The curated metric relationships, computed via Pearson r over aligned day pairs. */
private fun computeRelationships(model: InsightModel): List<Relationship> {
    fun series(o: Outcome) = model.seriesByOutcome[o] ?: emptyList()
    val out = mutableListOf<Relationship>()

    pearsonAligned(series(Outcome.Hrv), series(Outcome.Recovery))?.let { (r, n) ->
        out.add(
            Relationship(
                "hrv-rec", R.string.insights_rel_hrv_charge,
                R.string.insights_rel_hrv_charge_blurb, r, n,
            ),
        )
    }
    pearsonAligned(series(Outcome.Sleep), series(Outcome.Recovery))?.let { (r, n) ->
        out.add(
            Relationship(
                "sleep-rec", R.string.insights_rel_rest_charge,
                R.string.insights_rel_rest_charge_blurb, r, n,
            ),
        )
    }
    pearsonAligned(series(Outcome.Rhr), series(Outcome.Recovery))?.let { (r, n) ->
        out.add(
            Relationship(
                "rhr-rec", R.string.insights_rel_rhr_charge,
                R.string.insights_rel_rhr_charge_blurb, r, n,
            ),
        )
    }
    pearsonLagged(series(Outcome.Recovery), lagDays = 1)?.let { (r, n) ->
        out.add(
            Relationship(
                "rec-lag", R.string.insights_rel_charge_next_day,
                R.string.insights_rel_charge_next_day_blurb, r, n,
            ),
        )
    }

    return out
}

// MARK: - Statistics (pooled-SD Cohen's d, Pearson r)

/** Cohen's d using pooled standard deviation. 0 when either side lacks spread. */
private fun cohensD(a: List<Double>, b: List<Double>): Double {
    if (a.size < 2 || b.size < 2) return 0.0
    val ma = a.average()
    val mb = b.average()
    val va = RustScores.sampleSD(a).let { it * it }
    val vb = RustScores.sampleSD(b).let { it * it }
    val pooled = sqrt(((a.size - 1) * va + (b.size - 1) * vb) / (a.size + b.size - 2).toDouble())
    if (pooled <= 0.0 || !pooled.isFinite()) return 0.0
    return (ma - mb) / pooled
}

/** Pearson r over two (day,value) series aligned on shared days. Returns (r, n) or
 *  null if fewer than 3 overlapping pairs or no variance. */
private fun pearsonAligned(
    xs: List<Pair<String, Double>>,
    ys: List<Pair<String, Double>>,
): Pair<Double, Int>? {
    val ym = ys.toMap()
    val pairs = xs.mapNotNull { (day, x) -> ym[day]?.let { x to it } }
    return pearson(pairs)
}

/** Pearson r of a series against itself shifted forward by [lagDays] days.
 *  Uses index offset on the ordered series (days are oldest → newest). */
private fun pearsonLagged(series: List<Pair<String, Double>>, lagDays: Int): Pair<Double, Int>? {
    if (series.size <= lagDays) return null
    val pairs = (0 until series.size - lagDays).map { i ->
        series[i].second to series[i + lagDays].second
    }
    return pearson(pairs)
}

/** Pearson correlation of paired samples, via the shared [CorrelationEngine] gate. */
private fun pearson(pairs: List<Pair<Double, Double>>): Pair<Double, Int>? =
    CorrelationEngine.pearson(pairs)?.let { it.r to it.n }

/** Rough |r| threshold for "p < 0.05" at n pairs (critical r for a two-tailed test,
 *  approximated by 2 / sqrt(n), a standard rule-of-thumb). Honest, not exact. */
private fun significanceThreshold(n: Int): Double =
    if (n < 4) 1.1 else (2.0 / sqrt(n.toDouble())).coerceAtMost(1.0)

// MARK: - Text + colour helpers

@Composable
private fun effectSentence(e: BehaviorEffect, outcome: Outcome): String {
    val name = outcome.lowerName()
    // The behaviour is a journal QUESTION ("Any alcohol?"), so it is quoted verbatim rather than
    // folded into the sentence, where its own question mark lands mid-clause.
    val logged = "‘${e.behavior}’"
    if (e.delta == 0.0) {
        return stringResource(R.string.insights_effect_no_difference, logged, name)
    }
    val dir = stringResource(
        if (e.delta > 0) R.string.insights_direction_higher else R.string.insights_direction_lower,
    )
    val withStr = outcome.format(e.meanWith)
    val withoutStr = outcome.format(e.meanWithout)
    return stringResource(R.string.insights_effect_sentence, logged, name, withStr, dir, withoutStr)
}

@Composable
private fun effectMagnitudeWord(d: Double): String = stringResource(
    when {
        abs(d) < 0.2 -> R.string.insights_magnitude_negligible
        abs(d) < 0.5 -> R.string.insights_magnitude_small
        abs(d) < 0.8 -> R.string.insights_magnitude_moderate
        else -> R.string.insights_magnitude_large
    },
)

@Composable
private fun relationshipSentence(rel: Relationship): String {
    val dir = stringResource(
        when {
            rel.r > 0 -> R.string.insights_direction_positive
            rel.r < 0 -> R.string.insights_direction_negative
            else -> R.string.insights_direction_flat
        },
    )
    return stringResource(
        R.string.insights_relationship_sentence,
        stringResource(CorrelationEngine.strengthPhraseRes(rel.r)),
        dir,
        String.format(Locale.US, "%.2f", rel.r),
        rel.n,
    )
}

/** Tint a correlation on the recovery gradient, so strong positive reads mint and strong negative
 *  reads red. Where an r sits on the ramp is whoop-rs's. */
private fun correlationColor(r: Double): Color =
    Palette.sample(Palette.recoveryStops, RustScores.rampPositionCorrelation(r).toFloat())

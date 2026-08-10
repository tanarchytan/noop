package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.DoseCurvePoint
import com.noop.analytics.DoseResponse
import com.noop.analytics.DoseResponseEngine
import com.noop.analytics.DoseResponsePriors
import com.noop.analytics.DosedBehavior
import com.noop.analytics.EffectRanker
import com.noop.analytics.RankedEffect
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Insights Hub (v5)
//
// The headline n-of-1 "what actually moves YOUR recovery" surface. Two halves, both pure
// association on the user's own logged days, never advice / cause / diagnosis:
//
//  1. WHAT MOVES YOUR CHARGE — the unified lag-aware EffectRanker feed. Each row keeps the
//     strongest honest lag ({0,+1,+2}) so it reads "shows up the next morning"; carries the
//     sign-aware sentence, with/without means, a lead/lag chip, the effect-size word, and a
//     Solid / Building / Calibrating confidence pill (not a bare "significant" stamp).
//
//  2. ALCOHOL / CAFFEINE DOSE-RESPONSE — the personal DoseResponseEngine curve that SHRINKS
//     toward a documented population prior until enough nights accrue. Plots the shrunk curve,
//     states "each extra drink ≈ −N for you" (honest when prior-dominated, or when YOUR data
//     contradicts the prior), and an evening "damage forecast" — "a 2nd drink tonight ≈ −X
//     Charge tomorrow" — driven by a small dose stepper on the latest Charge. Never a nudge
//     to drink or abstain.
//
// SELF-CONTAINED: owns its own InsightsHubViewModel (constructed from vm.repo + cached days);
// does NOT edit AppViewModel / AppRoot / the central nav. Wave 3 surfaces it at the head of the
// Insights hub. All maths is in com.noop.analytics (EffectRanker / DoseResponseEngine).

@Composable
fun InsightsHubScreen(vm: AppViewModel) {
    val days by vm.recentDays.collectAsState()
    val hub = remember { InsightsHubViewModel() }
    val state by hub.state.collectAsState()

    // Re-derive whenever the cached days change underneath (journal + dose are read via repo).
    androidx.compose.runtime.LaunchedEffect(days) { hub.load(vm, days) }

    var outcome by remember { mutableStateOf(InsightsOutcome.Recovery) }
    val ranked = remember(state, outcome) { hub.rankFor(state, outcome) }

    // PERF: lazy scaffold — each section (and its standalone Spacer, a real child of the eager
    // `spacedBy(20.dp)` Column) becomes one `item { }`, so the LazyColumn's matching `spacedBy(20.dp)`
    // reproduces identical spacing and only on-screen sections compose + are semantics-walked.
    LazyScreenScaffold(
        title = stringResource(R.string.insights_title),
        subtitle = stringResource(R.string.insights_hub_subtitle),
    ) {
        if (!state.loaded) {
            item {
            NoopCard {
                Text(
                    stringResource(R.string.insights_reading_journal),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
            }
            return@LazyScreenScaffold
        }

        // --- What moves your Charge -------------------------------------------
        item { MoversSection(outcome = outcome, onOutcome = { outcome = it }, ranked = ranked) }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Dose-response (alcohol / caffeine) -------------------------------
        item { DoseSection(state.doseCards) }

        item { Spacer(Modifier.height(Metrics.sectionGap - 20.dp)) }

        // --- Method / honesty note --------------------------------------------
        item {
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                Overline(stringResource(R.string.insights_how_to_read), color = Palette.textTertiary)
                Text(
                    stringResource(R.string.insights_how_to_read_body),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
        }
    }
}

// MARK: - What moves your Charge

@Composable
private fun MoversSection(
    outcome: InsightsOutcome,
    onOutcome: (InsightsOutcome) -> Unit,
    ranked: List<RankedEffect>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Header then the outcome selector on its own row below it — on a ~360dp phone the pill
        // control can't share a row with the weighted header without compressing.
        val labels = HashMap<InsightsOutcome, String>()
        for (o in InsightsOutcome.entries) labels[o] = stringResource(o.labelRes)
        SectionHeader(
            stringResource(R.string.insights_what_moves_your, outcome.lowerName()),
            overline = stringResource(R.string.insights_ranked_your_data),
        )
        SegmentedPillControl(
            items = InsightsOutcome.entries.toList(),
            selection = outcome,
            label = { labels.getValue(it) },
            onSelect = onOutcome,
        )

        if (ranked.isEmpty()) {
            NoopCard {
                Text(
                    stringResource(R.string.insights_movers_no_overlap, outcome.lowerName()),
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                )
            }
        } else {
            // Fade + rise the ranked mover cards in sequence.
            ranked.forEachIndexed { i, r ->
                Box(modifier = Modifier.staggeredAppear(i)) { MoverCard(r, outcome) }
            }
        }
    }
}

@Composable
private fun MoverCard(r: RankedEffect, outcome: InsightsOutcome) {
    val e = r.effect
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
    // The delta reads between the two tiles, so it takes their formatter: two whole percents and a
    // difference at one decimal cannot be got from each other.
    val deltaText = e.pctChange?.let { "$arrow ${abs(it).roundToInt()}%" }
        ?: "$arrow ${outcome.format(abs(e.delta))}"

    NoopCard(tint = outcome.domain.color) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Header: behaviour name + lead/lag chip + confidence pill.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
                        r.behavior,
                        style = NoopType.headline,
                        color = Palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatePill(r.leadLagText, tone = StrandTone.Accent, showsDot = false)
                Spacer(Modifier.width(Metrics.space6))
                ConfidencePill(r.confidence)
            }

            Text(r.sentence(), style = NoopType.body, color = Palette.textSecondary)

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

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Overline(stringResource(R.string.insights_effect_size), modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.insights_cohens_d, String.format(Locale.US, "%.2f", e.cohensD)),
                    style = NoopType.captionNumber,
                    color = tintColor,
                )
                Spacer(Modifier.width(Metrics.space6))
                Text(effectMagnitudeWord(e.cohensD), style = NoopType.caption, color = Palette.textTertiary)
            }
        }
    }
}

// MARK: - Dose-response

@Composable
private fun DoseSection(cards: List<DoseCardData>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            stringResource(R.string.insights_dose_response),
            overline = stringResource(R.string.insights_dose_response_overline),
        )
        if (cards.isEmpty()) {
            NoopCard {
                Text(
                    stringResource(R.string.insights_dose_response_empty),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
        } else {
            // Fade + rise the dose-response cards in sequence.
            cards.forEachIndexed { i, card ->
                Box(modifier = Modifier.staggeredAppear(i)) { DoseResponseCard(card) }
            }
        }
    }
}

@Composable
private fun DoseResponseCard(card: DoseCardData) {
    val r = card.response
    val domain = if (card.outcomeName == "HRV") DomainTheme.Rest else DomainTheme.Charge
    // The evening preview dose, defaulting to a 2nd drink so the headline reads as a 2nd-drink forecast.
    var previewDose by remember(card.id) { mutableStateOf(2) }

    NoopCard(tint = domain.color) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Header.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(card.icon, contentDescription = null, tint = domain.color, modifier = Modifier.size(Metrics.iconSmall))
                Spacer(Modifier.width(Metrics.space8))
                Text(
                    stringResource(card.titleRes),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                ConfidencePill(r.confidence)
            }

            Text(r.sentence(), style = NoopType.body, color = Palette.textSecondary)

            val curveA11y = curveDescription(card, r)
            // The prior-shrunk curve.
            DoseCurveChart(
                points = r.curve,
                accent = domain.color,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .clearAndSetSemantics { contentDescription = curveA11y },
            )

            if (r.priorDominated) {
                HonestyBanner(
                    stringResource(
                        R.string.insights_dose_prior_dominated,
                        stringResource(card.unitLabelRes).lowercase(Locale.US),
                    ),
                    accent = Palette.textTertiary,
                )
            } else if (r.contradictsPrior) {
                HonestyBanner(
                    stringResource(
                        R.string.insights_dose_contradicts_prior,
                        stringResource(card.outcomeNameRes),
                    ),
                    accent = Palette.statusPositive,
                )
            }

            if (card.timingProxy) {
                Text(
                    stringResource(R.string.insights_dose_timing_proxy),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }

            HorizontalDivider(color = Palette.hairline)

            DamageForecast(card, previewDose = previewDose, onPreviewDose = { previewDose = it }, domain = domain)
        }
    }
}

@Composable
private fun DamageForecast(
    card: DoseCardData,
    previewDose: Int,
    onPreviewDose: (Int) -> Unit,
    domain: DomainTheme,
) {
    val r = card.response
    val fromDose = 1
    val delta = r.delta(fromDose, previewDose)
    val projected = card.latestOutcome?.let { max(0.0, min(card.outcomeCeiling, it + delta)) }
    val stepLabel = doseStepLabel(card, previewDose)
    val choiceLabels = HashMap<Int, String>()
    for (d in card.doseChoices) choiceLabels[d] = doseChoiceLabel(card, d)

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Overline then the dose stepper on its own row — the choices (0…max+) overflow a ~360dp
        // phone if they share a row with the overline.
        Overline(stringResource(card.forecastOverlineRes), modifier = Modifier.fillMaxWidth())
        SegmentedPillControl(
            items = card.doseChoices,
            selection = previewDose,
            label = { choiceLabels.getValue(it) },
            onSelect = onPreviewDose,
        )

        Text(
            forecastSentence(card, previewDose, delta, stepLabel),
            style = NoopType.subhead,
            color = Palette.textSecondary,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.insights_dose_per_extra, stringResource(card.unitNounRes)),
                value = signed(r.perUnit, card.outcomeSuffix),
                caption = stringResource(
                    if (r.priorDominated) {
                        R.string.insights_dose_typical
                    } else {
                        R.string.insights_dose_your_data
                    },
                ),
                accent = if (r.perUnit < 0) Palette.statusCritical else Palette.statusPositive,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.insights_dose_tomorrows, stringResource(card.outcomeNameRes)),
                value = projected?.let { "${it.roundToInt()}${card.outcomeSuffix}" } ?: "—",
                caption = if (projected != null) {
                    stringResource(R.string.insights_dose_projected, stepLabel)
                } else {
                    stringResource(R.string.insights_dose_needs_recent_day)
                },
                accent = domain.color,
            )
        }
    }
}

/** The dose stepper's segment caption: numeric for alcohol, time-of-day for caffeine. */
@Composable
private fun doseChoiceLabel(card: DoseCardData, d: Int): String = when (card.behavior) {
    DosedBehavior.ALCOHOL -> if (d >= DoseResponseEngine.maxCurveDose) "$d+" else "$d"
    DosedBehavior.CAFFEINE -> stringResource(
        when (d) {
            0 -> R.string.insights_dose_time_am
            1 -> R.string.insights_dose_time_noon
            2 -> R.string.insights_dose_time_afternoon
            else -> R.string.insights_dose_time_evening
        },
    )
}

/** The previewed step as the forecast words it ("no extra" / "3+ drinks" / "2"). */
@Composable
private fun doseStepLabel(card: DoseCardData, dose: Int): String = when {
    dose <= 1 -> stringResource(R.string.insights_dose_no_extra)
    card.behavior != DosedBehavior.ALCOHOL -> "$dose"
    dose >= DoseResponseEngine.maxCurveDose -> stringResource(R.string.insights_dose_plus_drinks, dose)
    else -> stringResource(R.string.insights_dose_drinks, dose)
}

@Composable
private fun HonestyBanner(text: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.surfaceInset)
            .padding(Metrics.space10),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).drawBehind { drawCircle(accent) })
        Text(text, style = NoopType.footnote, color = Palette.textSecondary)
    }
}

/** The confidence-lifecycle pill: Solid (positive/gold) / Building (accent) / Calibrating (neutral). */
@Composable
private fun ConfidencePill(c: ScoreConfidence) {
    val (labelRes, tone) = when (c) {
        ScoreConfidence.SOLID -> R.string.insights_confidence_solid to StrandTone.Positive
        ScoreConfidence.BUILDING -> R.string.insights_confidence_building to StrandTone.Accent
        ScoreConfidence.CALIBRATING -> R.string.insights_confidence_calibrating to StrandTone.Neutral
    }
    StatePill(stringResource(labelRes), tone = tone, showsDot = false)
}

// MARK: - Dose curve chart
//
// A compact line+area chart of the prior-shrunk curve: dose on x (0…max), modelled outcome
// DELTA on y, symmetric around a dashed zero line so the sign reads honestly. Drawn with the
// Compose Canvas idiom so it sits in the design system with no extra dependency.

@Composable
private fun DoseCurveChart(points: List<DoseCurvePoint>, accent: Color, modifier: Modifier = Modifier) {
    val zeroColor = Palette.hairlineStrong
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val w = size.width
        val h = size.height
        val maxAbs = max(1.0, points.maxOf { abs(it.outcomeDelta) })
        fun yFor(d: Double): Float {
            val t = (d / maxAbs + 1) / 2          // 0 (most negative) … 1 (most positive)
            return (h - t * h).toFloat()
        }
        val n = max(1, points.size - 1)
        fun xFor(i: Int): Float = i.toFloat() / n * w
        val zeroY = yFor(0.0)

        // Zero baseline (dashed).
        drawLine(
            color = zeroColor,
            start = Offset(0f, zeroY),
            end = Offset(w, zeroY),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )

        // Filled area between the curve and the zero line.
        val area = Path().apply {
            moveTo(xFor(0), zeroY)
            points.forEachIndexed { i, pt -> lineTo(xFor(i), yFor(pt.outcomeDelta)) }
            lineTo(xFor(points.size - 1), zeroY)
            close()
        }
        drawPath(
            area,
            brush = Brush.verticalGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.03f))),
        )

        // The curve line.
        val line = Path().apply {
            points.forEachIndexed { i, pt ->
                val x = xFor(i)
                val y = yFor(pt.outcomeDelta)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(line, color = accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))

        // Dose markers.
        points.forEachIndexed { i, pt ->
            drawCircle(accent, radius = 2.5.dp.toPx(), center = Offset(xFor(i), yFor(pt.outcomeDelta)))
        }
    }
}

// MARK: - Outcome

internal enum class InsightsOutcome(
    @StringRes val labelRes: Int,
    /** The engine spelling; [nameRes] is what the user reads. */
    val outcomeName: String,
    @StringRes val nameRes: Int,
    val key: String,
    val higherIsBetter: Boolean,
    val domain: DomainTheme,
    val pick: (DailyMetric) -> Double?,
    val format: (Double) -> String,
) {
    Recovery(
        R.string.insights_outcome_charge, "Charge", R.string.insights_outcome_charge,
        "recovery", true, DomainTheme.Charge, { it.recovery }, { "${it.roundToInt()}%" },
    ),
    Hrv(
        R.string.insights_outcome_hrv, "HRV", R.string.insights_outcome_hrv,
        "hrv", true, DomainTheme.Rest, { it.avgHrv }, { "${it.roundToInt()} ms" },
    ),
    // Efficiency is stored as a 0..1 fraction; lift it to the 0..100 scale the other outcomes use.
    Sleep(
        R.string.insights_outcome_rest, "Rest", R.string.insights_outcome_rest,
        "sleep_performance", true, DomainTheme.Rest,
        { row -> row.efficiency?.let { if (it <= 1.0) it * 100.0 else it } }, { "${it.roundToInt()}%" },
    ),
    Rhr(
        R.string.insights_outcome_rhr, "Resting HR", R.string.insights_outcome_resting_hr,
        "rhr", false, DomainTheme.Stress, { it.restingHr?.toDouble() }, { "${it.roundToInt()} bpm" },
    ),
}

/** The selected outcome's display name, lowercased the way every hub sentence uses it. */
@Composable
private fun InsightsOutcome.lowerName(): String = stringResource(nameRes).lowercase(Locale.US)

// MARK: - Dose card view-data

internal data class DoseCardData(
    val behavior: DosedBehavior,
    val response: DoseResponse,
    val latestOutcome: Double?,
) {
    val id: String get() = behavior.raw
    /** The engine spelling ("Charge" / "HRV"); [outcomeNameRes] is what the user reads. */
    val outcomeName: String get() = response.outcome
    @get:StringRes
    val outcomeNameRes: Int
        get() = if (outcomeName == "HRV") R.string.insights_outcome_hrv else R.string.insights_outcome_charge
    @get:StringRes
    val titleRes: Int
        get() = if (behavior == DosedBehavior.ALCOHOL) R.string.insights_alcohol else R.string.insights_caffeine
    val icon get() = if (behavior == DosedBehavior.ALCOHOL) Icons.Filled.LocalBar else Icons.Filled.Coffee
    @get:StringRes
    val unitNounRes: Int
        get() = if (behavior == DosedBehavior.ALCOHOL) {
            R.string.insights_dose_unit_drink
        } else {
            R.string.insights_dose_unit_later_step
        }
    @get:StringRes
    val unitLabelRes: Int
        get() = if (behavior == DosedBehavior.ALCOHOL) {
            R.string.insights_dose_label_drink
        } else {
            R.string.insights_dose_label_late_caffeine
        }
    val timingProxy: Boolean get() = behavior == DosedBehavior.CAFFEINE
    val outcomeSuffix: String get() = if (outcomeName == "HRV") " ms" else "%"
    val outcomeCeiling: Double get() = if (outcomeName == "HRV") 400.0 else 100.0
    @get:StringRes
    val forecastOverlineRes: Int
        get() = if (behavior == DosedBehavior.ALCOHOL) {
            R.string.insights_forecast_tonight
        } else {
            R.string.insights_forecast_timing
        }

    val doseChoices: List<Int> get() = (0..DoseResponseEngine.maxCurveDose).toList()
}

// MARK: - View-model
//
// Self-contained: loads the journal (behaviour → days), dose rows (under the dedicated
// noop-journal-dose source), and outcome series (cached DailyMetric rows), then runs
// EffectRanker for the ranked feed and DoseResponseEngine for each dosed behaviour with data.
// No edits to AppViewModel. Holds an immutable snapshot in a StateFlow.

internal class InsightsHubViewModel {

    data class Snapshot(
        val loaded: Boolean = false,
        val behaviours: Map<String, Set<String>> = emptyMap(),
        val outcomeByKey: Map<String, Map<String, Double>> = emptyMap(),
        val doseCards: List<DoseCardData> = emptyList(),
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    companion object {
        const val DOSE_SOURCE = "noop-journal-dose"

        fun doseKey(behavior: DosedBehavior): String = "dose_${behavior.raw}"

        fun matches(behavior: DosedBehavior, question: String): Boolean {
            val q = question.lowercase(Locale.US)
            return when (behavior) {
                DosedBehavior.ALCOHOL -> q.contains("alcohol") || q.contains("drink")
                DosedBehavior.CAFFEINE -> q.contains("caffeine") || q.contains("coffee")
            }
        }

        fun outcomeKeyFor(engineName: String): String = when (engineName) {
            "Charge" -> "recovery"
            "HRV" -> "hrv"
            "Rest" -> "sleep_performance"
            "Resting HR" -> "rhr"
            else -> "recovery"
        }
    }

    suspend fun load(vm: AppViewModel, days: List<DailyMetric>) {
        // Journal → behaviour → days (imported ∪ native, native wins; only "yes" counts).
        val imported = vm.repo.journal("my-whoop", "0000-01-01", "9999-12-31")
        val native = vm.repo.journal(JOURNAL_DEVICE_ID, "0000-01-01", "9999-12-31")
        val entries = mergeJournalEntries(imported, native)
        val byBehaviour = HashMap<String, MutableSet<String>>()
        for (e in entries) if (e.answeredYes) byBehaviour.getOrPut(e.question) { mutableSetOf() }.add(e.day)
        val behaviours = byBehaviour.mapValues { it.value.toSet() }

        // Outcome series straight off the cached DailyMetric rows (the guaranteed Android source).
        val outcomeByKey = HashMap<String, Map<String, Double>>()
        for (o in InsightsOutcome.entries) {
            val dict = HashMap<String, Double>()
            for (d in days) o.pick(d)?.let { dict[d.day] = it }
            outcomeByKey[o.key] = dict
        }

        // Dose rows per dosed behaviour, under the dedicated dose source; logged "yes" days
        // back-fill dose = 1, explicit dose rows override.
        val doseCards = ArrayList<DoseCardData>()
        for (behavior in DosedBehavior.entries) {
            val doses = HashMap<String, Int>()
            for ((question, set) in behaviours) if (matches(behavior, question)) {
                for (day in set) doses[day] = max(doses[day] ?: 0, 1)
            }
            val rows = vm.repo.metricSeries(DOSE_SOURCE, doseKey(behavior), "0000-01-01", "9999-12-31")
            for (row in rows) doses[row.day] = row.value.roundToInt()
            if (doses.isEmpty()) continue

            val outcomeName = DoseResponsePriors.defaultOutcome(behavior)
            val outcomeDays = outcomeByKey[outcomeKeyFor(outcomeName)] ?: emptyMap()
            val response = DoseResponseEngine.estimate(behavior, doses, outcomeDays) ?: continue
            val latest = outcomeDays.keys.maxOrNull()?.let { outcomeDays[it] }
            doseCards.add(DoseCardData(behavior, response, latest))
        }

        _state.value = Snapshot(
            loaded = true,
            behaviours = behaviours,
            outcomeByKey = outcomeByKey,
            doseCards = doseCards,
        )
    }

    /** Re-rank the mover feed for a (possibly new) outcome — cheap, no DB. */
    fun rankFor(snapshot: Snapshot, outcome: InsightsOutcome): List<RankedEffect> {
        if (!snapshot.loaded) return emptyList()
        val outcomeDays = snapshot.outcomeByKey[outcome.key] ?: emptyMap()
        return EffectRanker.rank(snapshot.behaviours, outcomeDays, outcome.outcomeName)
    }
}

// MARK: - Copy helpers

@Composable
private fun forecastSentence(card: DoseCardData, previewDose: Int, delta: Double, stepLabel: String): String {
    val outcomeLower = stringResource(card.outcomeNameRes).lowercase(Locale.US)
    if (previewDose <= 1) {
        return stringResource(R.string.insights_forecast_no_extra, outcomeLower)
    }
    val mag = abs(delta).roundToInt()
    val dir = stringResource(
        if (delta <= 0) R.string.insights_direction_lower else R.string.insights_direction_higher,
    )
    val basis = if (card.response.priorDominated) {
        stringResource(R.string.insights_forecast_basis_typical)
    } else {
        stringResource(
            R.string.insights_forecast_basis_yours,
            card.response.nUser,
            stringResource(card.unitLabelRes).lowercase(Locale.US),
        )
    }
    return stringResource(
        R.string.insights_forecast_sentence,
        stepLabel,
        "$mag${card.outcomeSuffix}",
        dir,
        outcomeLower,
        basis,
    )
}

@Composable
private fun curveDescription(card: DoseCardData, r: DoseResponse): String = stringResource(
    R.string.insights_dose_curve_a11y,
    stringResource(card.unitNounRes),
    signed(r.perUnit, card.outcomeSuffix),
    stringResource(card.outcomeNameRes),
    stringResource(
        if (r.priorDominated) {
            R.string.insights_dose_basis_typical
        } else {
            R.string.insights_dose_basis_yours
        },
    ),
)

private fun signed(v: Double, suffix: String): String {
    val mag = abs(v)
    val rounded = (mag * 10).roundToInt() / 10.0
    val sign = if (v < 0) "−" else if (v > 0) "+" else ""
    val body = if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}" else String.format(Locale.US, "%.1f", rounded)
    return "$sign$body$suffix"
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

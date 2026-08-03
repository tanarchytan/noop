package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.analytics.Baselines
import com.noop.analytics.ReadinessEngine
import com.noop.analytics.RecoveryScorer
import com.noop.analytics.RestScorer
import com.noop.analytics.RustScores
import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Coupled view — Kotlin twin of CoupledView.swift
//
// An optional, default-OFF day view that reads like the classic coupled home: one screen, three numbers,
// Recovery % / Day Strain on 0-21 / Sleep, for users who came across from another band and want the old
// glance back. NOOP's Today stays the default and is untouched.
//
// DISPLAY-ONLY, like the Effort-scale toggle. It reads the SAME values Today already computes (recovery
// / Rest composite / Effort strain / readiness) and re-presents them in the coupled layout. The only new
// mapping is the OPTIMAL strain band, a pure display-only read of today's recovery to a suggested strain
// range (never fed back into scoring) that is byte-identical to the Swift [CoupledView.optimalStrainRange].
//
// Every colour routes through the [Palette] ramps, so the Classic / Titanium appearance carries automatically.
// The brand word never appears in a shipped UI string (legal posture); the screen is called "Coupled view".

/** The 0-21 Day-Strain axis the coupled read always uses, regardless of the user's display toggle. */
private const val COUPLED_STRAIN_OUT_OF = 21.0

/** The missing-value placeholder, matching the app's shipped "No Data" token. */
private const val COUPLED_NO_DATA = "No Data"

/** The Charge hero ring. */
private val HERO_RING_DIAMETER: Dp = 232.dp

/** The Effort gauge beside the coupled stat stack. */
private val EFFORT_RING_DIAMETER: Dp = 148.dp

/** The sleep-performance ring on the Sleep row. */
private val SLEEP_RING_DIAMETER: Dp = 96.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoupledScreen(
    vm: AppViewModel,
    onOpenSleep: () -> Unit = {},
) {
    val today by vm.today.collectAsStateWithLifecycle()
    val days by vm.recentDays.collectAsStateWithLifecycle()

    // Last night's sleep sessions (imported + computed-only), the SAME resolution SleepNightScreen uses, keyed on
    // `days` so a sync/import reloads. Only needed for the bed-wake span footnote.
    var sleeps by remember { mutableStateOf<List<SleepSession>>(emptyList()) }
    LaunchedEffect(days) {
        sleeps = runCatching {
            val now = System.currentTimeMillis() / 1000L
            val imported = vm.repo.sleepSessions("my-whoop", 0L, now)
            val computed = vm.repo.sleepSessions(vm.repo.computedDeviceId("my-whoop"), 0L, now)
            val importedEnds = imported.map { it.effectiveEndTs }.toHashSet()
            (imported + computed.filter { it.effectiveEndTs !in importedEnds }).sortedBy { it.effectiveStartTs }
        }.getOrDefault(emptyList())
    }

    // The learned habitual midsleep the Sleep tab hero threads into its main-night pick, so the bed-wake
    // span below resolves the IDENTICAL block instead of a screen-local heuristic.
    var habitualMidsleepSec by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(days) {
        habitualMidsleepSec = runCatching { vm.repo.habitualMidsleepSec() }.getOrNull()
    }

    // Imported export-verbatim sleep figures (sleep_performance / need), preferred over the on-device
    // approximation, mirroring SleepNightScreen. Keyed on `days` (metricSeries has no Flow).
    var importedPerf by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var importedNeed by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(days) {
        suspend fun load(key: String) = runCatching {
            vm.repo.metricSeries("my-whoop", key, "0000-00-00", "9999-99-99")
        }.getOrDefault(emptyList()).associate { it.day to it.value }
        importedPerf = load("sleep_performance")
        importedNeed = load("sleep_need_min")
    }

    // The day the coupled read describes: today's resolved row, else the carried last-scored prior day, so a
    // just-rolled-over morning carries yesterday's read rather than blanking (mirrors Swift + widgetAnchor).
    val logicalKey = remember { logicalDayKeyNow() }
    val localKey = remember { java.time.LocalDate.now().toString() }
    val todayRow = remember(today, days, logicalKey, localKey) {
        resolveTodayRow(days, logicalKey, localKey) ?: today
    }
    val todayKey = todayRow?.day ?: logicalKey
    val carriedRecoveryDay = remember(days, todayKey) {
        days.lastOrNull { it.recovery != null && it.day < todayKey }
    }
    val recovery = todayRow?.recovery ?: carriedRecoveryDay?.recovery
    val isCarrying = todayRow?.recovery == null && carriedRecoveryDay?.recovery != null

    // Effort strain on NOOP's 0-100 axis (stored row), mapped to the 0-21 coupled axis via the shipped
    // formatter, so the number matches every other Effort read-out's conversion factor.
    val dayStrain21 = todayRow?.strain?.let { UnitFormatter.effortValue(it, EffortScale.WHOOP) }

    // Sleep performance %: the imported figure when the export carried one, else the resolved Rest composite
    // (RestScorer.restFromDaily), the SAME single source of truth the Today Rest score + Sleep graph read.
    val sleepPerformance = todayRow?.let { d ->
        importedPerf[d.day] ?: RestScorer.restFromDaily(d)
    }

    // On-device readiness, computed EXACTLY as Today does, so the one-word pill matches the home screen.
    // The carried anchor is gated on isCarrying (Today's !todayScored gate): on a normal scored day today's
    // own key wins, so Coupled's pill can't diverge from Today's onto yesterday.
    val readinessLevel = remember(days, carriedRecoveryDay, todayRow, isCarrying) {
        val anchor = (if (isCarrying) carriedRecoveryDay?.day else todayRow?.day) ?: logicalKey
        ReadinessEngine.evaluate(days, anchor).level
    }

    // Recovery cold-start nights (the SAME pure helper Today's ring reads), for the honest calibrating
    // caption + accessibility copy while the HRV baseline still seeds. Threads the persisted
    // "Recalibrate HRV baseline" epoch so N folds the SAME epoch-aware history the engine folds (Bug B).
    val context = LocalContext.current
    val hrvEpoch = remember { NoopPrefs.of(context).getLong(Baselines.hrvBaselineEpochKey, 0L).toDouble() }
    val calibrationNights = remember(days, todayRow, hrvEpoch) {
        recoveryCalibrationNights(days, hasRecovery = todayRow?.recovery != null, hrvBaselineEpoch = hrvEpoch)
    }

    // The Charge breakdown (the hero's tap target, the EXISTING Today sheet), built only when shown
    // (lazy). Not persisted, so a return visit reopens closed.
    var showChargeBreakdown by remember { mutableStateOf(false) }

    // No topBackground: the scaffold takes its opaque path and paints Palette.surfaceBase, so the canvas
    // follows the theme in both light and dark.
    ScreenScaffold(
        title = "Day",
        subtitle = subtitleToday(),
    ) {
        HeroCard(
            recovery = recovery,
            isCarrying = isCarrying,
            carriedDay = carriedRecoveryDay,
            todayKey = todayKey,
            calibrationNights = calibrationNights,
            readinessLevel = readinessLevel,
            onTap = { showChargeBreakdown = true },
        )
        StrainCard(
            dayStrain21 = dayStrain21,
            recovery = recovery,
            calories = todayRow?.activeKcalEst,
            workouts = todayRow?.exerciseCount ?: 0,
        )
        SleepCard(
            sleepPerformance = sleepPerformance,
            asleepMin = todayRow?.totalSleepMin,
            needMin = sleepNeedForDay(todayRow, days, importedNeed),
            bedWakeSpan = bedWakeSpan(sleeps, habitualMidsleepSec),
            onOpenSleep = onOpenSleep,
        )
        Text(
            // The brief quotes the footer with the brand word, but the hard legal / anonymity rule wins over
            // the illustrative copy: this keeps the exact intent without the branding word. Byte-identical to
            // the Swift footer caption.
            "A classic one-glance read of NOOP's own scores. Same data, different lens.",
            style = NoopType.footnote,
            color = Palette.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    // The hero's tap target: the EXISTING Today Charge breakdown sheet (What shaped it + Contributors +
    // the folded Readiness card), presented as a ModalBottomSheet (matching Today's presentation).
    if (showChargeBreakdown) {
        ModalBottomSheet(
            onDismissRequest = { showChargeBreakdown = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.surfaceBase,
            contentColor = Palette.textPrimary,
        ) {
            ChargeBreakdownSheet(
                days = days,
                displayDay = todayRow,
                carriedDay = carriedRecoveryDay,
                showReadiness = true,
                onClose = { showChargeBreakdown = false },
            )
        }
    }
}

// MARK: 1. HERO — the recovery ring, coupled read (tap = the Charge breakdown)

@Composable
private fun HeroCard(
    recovery: Double?,
    isCarrying: Boolean,
    carriedDay: DailyMetric?,
    todayKey: String,
    calibrationNights: Int?,
    readinessLevel: ReadinessEngine.Level,
    onTap: () -> Unit,
) {
    val a11y = when {
        recovery != null -> "Recovery ${recovery.roundToInt()} percent. See what shaped your Charge"
        calibrationNights != null ->
            "Recovery calibrating, $calibrationNights of ${Baselines.minNightsSeed} nights"
        else -> "Recovery, no data yet"
    }
    // The whole hero is the breakdown's tap target. The SAME interactionSource drives the clickable and
    // the press, so the card settles inward on press.
    val interaction = remember { MutableInteractionSource() }
    NoopCard(
        padding = Metrics.screenRowSpacing,
        tint = Palette.chargeColor,
        modifier = Modifier
            .liquidPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "See what shaped your Charge",
                onClick = onTap,
            )
            .semantics { contentDescription = a11y },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // The same score on the shared Charge gauge. A carried (not-yet-rescored) morning reads
                // dimmed; no score draws the empty track with the "No Data" token where the number sits.
                RecoveryRing(
                    score = recovery ?: 0.0,
                    diameter = HERO_RING_DIAMETER,
                    showsLabel = recovery != null,
                    valueFormat = { "${it.roundToInt()}%" },
                    modifier = Modifier.alpha(if (isCarrying) 0.8f else 1f),
                )
                if (recovery == null) {
                    Text(COUPLED_NO_DATA, style = NoopType.headline, color = Palette.textSecondary)
                }
            }
            HeroLabels(recovery = recovery, readinessLevel = readinessLevel)
            // The honest state line under the ring: the "Last night · <date>" stamp when carrying a
            // prior score (the SAME caption Today uses), or the calibrating progress while
            // the baseline seeds. Nothing when today's own score is showing.
            if (isCarrying && carriedDay != null) {
                Text(
                    carriedCaption(carriedDay.day, today = todayKey),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            } else if (recovery == null && calibrationNights != null) {
                Text(
                    "Calibrating, $calibrationNights of ${Baselines.minNightsSeed} nights",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

/** The label stack under the ring: the metric's name in its sampled colour, then the readiness word. */
@Composable
private fun HeroLabels(recovery: Double?, readinessLevel: ReadinessEngine.Level) {
    val sampled = recovery?.let { Palette.recoveryColor(it) } ?: Palette.textTertiary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        Text("RECOVERY", style = NoopType.overline, color = sampled)
        val word = readinessWord(readinessLevel)
        if (word != null) ReadinessPill(word = word, level = readinessLevel)
    }
}

@Composable
private fun ReadinessPill(word: String, level: ReadinessEngine.Level) {
    val tint = readinessColor(level)
    Text(
        word.uppercase(),
        style = NoopType.overline,
        color = tint,
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(Metrics.divider, tint.copy(alpha = 0.32f), RoundedCornerShape(999.dp))
            .padding(horizontal = Metrics.space12, vertical = 5.dp),
    )
}

// MARK: 2. STRAIN ROW — the effort gauge + coupled stat stack

@Composable
private fun StrainCard(dayStrain21: Double?, recovery: Double?, calories: Double?, workouts: Int) {
    NoopCard(padding = 20.dp, tint = Palette.effortColor) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
        ) {
            // Left: the effort gauge on the 0-21 axis, under the band word. Same fraction (strain / 21) and
            // the same effortTint sample the shared gauge applies; its own "of 21" caption states the axis.
            Column(
                modifier = Modifier.size(168.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (dayStrain21 != null) {
                    Text(
                        strainBandWord(dayStrain21 / COUPLED_STRAIN_OUT_OF),
                        style = NoopType.overline,
                        color = Palette.effortColor,
                        modifier = Modifier.padding(bottom = Metrics.space4),
                    )
                    StrainGauge(
                        strain = dayStrain21,
                        outOf = COUPLED_STRAIN_OUT_OF,
                        valueText = String.format(Locale.US, "%.1f", dayStrain21),
                        diameter = EFFORT_RING_DIAMETER,
                    )
                } else {
                    // No scored effort yet: the empty track, never a zero, with the honest caption below it.
                    StrainGauge(
                        strain = 0.0,
                        outOf = COUPLED_STRAIN_OUT_OF,
                        diameter = EFFORT_RING_DIAMETER,
                        showsLabel = false,
                    )
                    Text("No effort yet", style = NoopType.footnote, color = Palette.textTertiary, modifier = Modifier.padding(top = Metrics.space6))
                }
            }

            // Right: the coupled stat stack.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Metrics.space14),
            ) {
                HeroStat("Day Strain", dayStrain21?.let { String.format(Locale.US, "%.1f", it) } ?: COUPLED_NO_DATA, Palette.effortColor)
                HeroStat("Optimal", optimalStrainRangeText(recovery), Palette.chargeColor)
                HeroStat("Calories", calories?.let { "${it.roundToInt()} kcal" } ?: COUPLED_NO_DATA, Palette.metricAmber)
                HeroStat("Workouts", workouts.toString(), Palette.textPrimary)
            }
        }
    }
}

/** The heroStat idiom (an UPPERCASE overline over a big tinted number), mirroring WorkoutsScreen/iOS heroStat. */
@Composable
private fun HeroStat(title: String, value: String, tint: Color) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Metrics.space2),
    ) {
        Text(title.uppercase(), style = NoopType.overline, color = Palette.textSecondary)
        Text(value, style = NoopType.number(20f), color = tint)
    }
}

// MARK: 3. SLEEP ROW — the sleep-performance ring + hours-vs-need read (tap = Sleep)

@Composable
private fun SleepCard(
    sleepPerformance: Double?,
    asleepMin: Double?,
    needMin: Double,
    bedWakeSpan: String?,
    onOpenSleep: () -> Unit,
) {
    // The whole card is the tap target; the SAME interactionSource drives the clickable and the press,
    // so it settles inward on press.
    val interaction = remember { MutableInteractionSource() }
    NoopCard(
        padding = 20.dp,
        tint = Palette.restColor,
        modifier = Modifier
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = "Open Sleep",
                onClick = onOpenSleep,
            )
            .liquidPress(interaction),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
        ) {
            // The same performance score in the rest tint. No score draws the empty track and no number;
            // the fill key keeps a scroll that recycles the ring from replaying the fill.
            GlowRing(
                fraction = ((sleepPerformance ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat(),
                value = sleepPerformance ?: 0.0,
                color = Palette.restColor,
                diameter = SLEEP_RING_DIAMETER,
                lineWidth = SLEEP_RING_DIAMETER * RING_STROKE_FRACTION,
                fillKey = "coupled.sleepPerformance",
                showsLabel = sleepPerformance != null,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Metrics.space4),
            ) {
                Text("SLEEP PERFORMANCE", style = NoopType.overline, color = Palette.textSecondary)
                if (asleepMin != null && asleepMin > 0) {
                    Text("${hoursMinutes(asleepMin)} slept", style = NoopType.headline, color = Palette.textPrimary)
                    Text("${hoursMinutes(needMin)} needed", style = NoopType.subhead, color = Palette.textSecondary)
                } else {
                    Text("No sleep tracked last night", style = NoopType.subhead, color = Palette.textSecondary)
                }
                if (bedWakeSpan != null) {
                    Text(bedWakeSpan, style = NoopType.footnote, color = Palette.textTertiary)
                }
            }

            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(Metrics.iconSmall),
            )
        }
    }
}

// MARK: - Pure helpers (byte-identical formatting to the Swift CoupledView)

/** The header subtitle "Today, d MMM". */
private fun subtitleToday(): String =
    "Today, " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date())

/** "6h 42m" from a minutes count, for the slept-vs-needed read. Mirrors CoupledView.hoursMinutes EXACTLY. */
internal fun hoursMinutes(minutes: Double): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    return "${total / 60}h ${total % 60}m"
}

/**
 * The strain band word for a 0..1 fill fraction, byte-identical to the Swift StrandDesign StrainGauge bands
 * (LIGHT/MODERATE/STRENUOUS/HIGH/ALL-OUT at 6/10/14/18 of 21). The Android StrainGauge has no internal state
 * word, so the coupled strain card computes it for the overline.
 */
internal fun strainBandWord(fraction: Double): String = when {
    fraction < 6.0 / 21 -> "LIGHT"
    fraction < 10.0 / 21 -> "MODERATE"
    fraction < 14.0 / 21 -> "STRENUOUS"
    fraction < 18.0 / 21 -> "HIGH"
    else -> "ALL-OUT"
}

/**
 * The night's need (minutes): the imported per-day figure when the export carried one, else the
 * personal need whoop-rs derives from banked asleep minutes.
 */
private fun sleepNeedForDay(day: DailyMetric?, days: List<DailyMetric>, importedNeed: Map<String, Double>): Double {
    day?.day?.let { key -> importedNeed[key]?.takeIf { it > 0 }?.let { return it } }
    return RustScores.personalSleepNeedMinutes(days.mapNotNull { it.totalSleepMin })
}

/**
 * Last night's bed -> wake span, from the day's bridged MAIN-night span ([mainSleepSpan], the SAME
 * resolver the Sleep tab hero and the daily total use), only when that night actually touches the last
 * 36h (a days-old import is not "last night"). Was previously this screen's own "freshest-ending
 * session" pick, which could name a different block -- and so a different span -- than the Sleep tab and
 * Today's HR graph for a night stored as more than one block.
 */
private fun bedWakeSpan(sleeps: List<SleepSession>, habitualMidsleepSec: Long?): String? {
    val windowStart = System.currentTimeMillis() / 1000L - 36 * 3600L // within the last 36h counts as last night
    val candidates = sleeps.filter { it.effectiveEndTs > windowStart }
    val span = mainSleepSpan(candidates, habitualMidsleepSec) ?: return null
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return "${fmt.format(Date(span.first * 1000L))} - ${fmt.format(Date(span.second * 1000L))}"
}

// The coupled read suggests a Day-Strain target BAND from today's recovery: green earns a higher band,
// red a lower one. PRESENTATION ONLY, never fed back into any score. The recovery cut points are the
// whoop-rs band edges ([RecoveryScorer.bandRedMax] / [RecoveryScorer.bandYellowMax]), never a copy.

internal data class OptimalStrainRange(val low: Int, val high: Int)

/** The pure recovery->optimal-strain band, or null when recovery is unknown. */
internal fun optimalStrainRange(recovery: Double?): OptimalStrainRange? {
    val r = recovery ?: return null
    return when {
        r >= RecoveryScorer.bandYellowMax -> OptimalStrainRange(14, 18)
        r >= RecoveryScorer.bandRedMax -> OptimalStrainRange(10, 14)
        else -> OptimalStrainRange(4, 10)
    }
}

/** The optimal band as display text ("14 to 18" / the no-data token). */
internal fun optimalStrainRangeText(recovery: Double?): String {
    val band = optimalStrainRange(recovery) ?: return COUPLED_NO_DATA
    return "${band.low} to ${band.high}"
}

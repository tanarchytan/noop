package com.noop.ui

import androidx.annotation.StringRes
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.Baselines
import com.noop.analytics.ReadinessEngine
import com.noop.analytics.ScoreConfidence
import com.noop.analytics.StrainScorer
import com.noop.data.AppleDaily
import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The full 14-day metric grid, in default order: Charge, Effort, Rest, HRV, Resting HR, SpO₂,
 * Respiratory, Steps, Weight, Calories. Each tile is fixed-height for complete rows.
 */
@Composable
internal fun MetricGrid(
    d: DailyMetric?,
    recoveryCalibration: Int? = null,
    lastScoredCharge: LastCharge? = null,
    carriedDay: DailyMetric? = null,
    // PER-FIELD SpO₂ carry (see lastSpo2Row): carriedDay is recovery-gated and lands on rows whose
    // spo2Pct is null (computed rows never carry one), so the SpO₂ tile falls through to the
    // last row that actually has a reading.
    spo2CarryDay: DailyMetric? = null,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    effortScale: EffortScale = EffortScale.HUNDRED,
    latestWeightKg: Double? = null,
    profileWeightKg: Double = 75.0,
    importedStepsForDay: Int? = null,
    estimatedStepsForDay: Int? = null,
    restScore: Double? = null,
    enabledMetrics: List<KeyMetric> = KeyMetric.defaultOrder,
    // S5: cap the grid to the first METRICS_COLLAPSED_CAP tiles behind a "Show all metrics" expander,
    // collapsing OVERFLOW only (never dropping or reordering a user-selected tile). Defaults keep the
    // grid fully expanded for any caller that doesn't opt into the cap.
    metricsExpanded: Boolean = true,
    onToggleMetrics: () -> Unit = {},
) {
    // A 3-column grid of compact tiles: one descriptor per KeyMetric carrying the tile's label, value,
    // unit, tint and fill fraction. The editor, the enabled order and the collapse expander read the same
    // descriptors, so a tile's presence and its position stay the user's choice.
    val descriptors: Map<KeyMetric, KeyTileData> = mapOf(
        // Each score tile wears its KeyMetric's own title, the word the hero ring above it and the
        // customise sheet beside it both use, so one number carries one name down the page.
        KeyMetric.CHARGE to run {
            val v = d?.recovery ?: lastScoredCharge?.value
            KeyTileData(
                label = KeyMetric.CHARGE.title,
                value = d?.recovery?.let { "${it.roundToInt()}" }
                    ?: recoveryCalibration?.let { "$it/${Baselines.minNightsSeed}" }
                    ?: lastScoredCharge?.let { "${it.value.roundToInt()}" } ?: NO_DATA,
                unit = if (d?.recovery != null || lastScoredCharge != null) "%" else "",
                tint = v?.let { Palette.recoveryColor(it) } ?: Palette.chargeColor,
                frac = v?.let { (it / 100.0).coerceIn(0.0, 1.0) },
            )
        },
        KeyMetric.EFFORT to KeyTileData(
            label = KeyMetric.EFFORT.title,
            value = d?.strain?.let { UnitFormatter.effortDisplay(it, effortScale) } ?: NO_DATA,
            unit = "", // Strain is a 0–21 load index, not a percentage
            tint = d?.strain?.let { Palette.effortTint(it / StrainScorer.maxStrain) } ?: Palette.effortColor,
            frac = d?.strain?.let { (it / 100.0).coerceIn(0.0, 1.0) },
        ),
        KeyMetric.REST to KeyTileData(
            label = KeyMetric.REST.title,
            value = restScore?.let { "${it.roundToInt()}" } ?: NO_DATA,
            unit = if (restScore != null) "%" else "",
            tint = restScore?.let { Palette.recoveryColor(it) } ?: Palette.restColor,
            frac = restScore?.let { (it / 100.0).coerceIn(0.0, 1.0) },
        ),
        KeyMetric.HRV to run {
            val v = d?.avgHrv ?: carriedDay?.avgHrv
            KeyTileData(
                label = stringResource(R.string.today_metric_hrv),
                value = v?.let { "${it.roundToInt()}" } ?: NO_DATA,
                unit = if (v != null) "ms" else "",
                tint = Palette.metricCyan,
                frac = v?.let { (it / 120.0).coerceIn(0.0, 1.0) },
            )
        },
        KeyMetric.RESTING_HR to run {
            val v = d?.restingHr ?: carriedDay?.restingHr
            KeyTileData(
                label = stringResource(R.string.today_metric_rest_hr_short),
                value = v?.toString() ?: NO_DATA,
                unit = if (v != null) "bpm" else "",
                tint = Palette.metricRose,
                frac = v?.let { (it / 100.0).coerceIn(0.0, 1.0) },
            )
        },
        KeyMetric.BLOOD_OXYGEN to run {
            val v = d?.spo2Pct ?: carriedDay?.spo2Pct ?: spo2CarryDay?.spo2Pct
            KeyTileData(
                label = "SpO₂",
                value = v?.let { String.format(Locale.US, "%.0f", it) } ?: NO_DATA,
                unit = if (v != null) "%" else "",
                tint = Palette.metricCyan,
                frac = v?.let { (it / 100.0).coerceIn(0.0, 1.0) },
            )
        },
        KeyMetric.RESPIRATORY to run {
            val v = d?.respRateBpm ?: carriedDay?.respRateBpm
            KeyTileData(
                // A third of the screen holds about nine characters at the overline size, so the
                // full word ellipsises here and nowhere else in the grid. Health already prints this
                // metric as RESP in its five-up row; the grid is the same width problem, so it takes
                // the same short form rather than a truncation.
                label = stringResource(R.string.today_metric_resp_short),
                spokenLabel = stringResource(R.string.today_metric_respiratory),
                value = v?.let { String.format(Locale.US, "%.1f", it) } ?: NO_DATA,
                unit = if (v != null) "rpm" else "",
                tint = Palette.accent,
                frac = v?.let { (it / 24.0).coerceIn(0.0, 1.0) },
            )
        },
        KeyMetric.STEPS to run {
            // Steps precedence (unchanged): on-device count → imported → estimate.
            val realSteps = d?.steps ?: importedStepsForDay
            val steps = realSteps ?: estimatedStepsForDay
            KeyTileData(
                label = stringResource(R.string.today_metric_steps),
                value = steps?.let { intString(it.toDouble()) } ?: NO_DATA,
                unit = "",
                tint = Palette.metricCyan,
                frac = steps?.let { (it / 10000.0).coerceIn(0.0, 1.0) },
            )
        },
        KeyMetric.WEIGHT to run {
            val weight = weightTile(latestWeightKg, profileWeightKg, unitSystem)
            KeyTileData(
                label = stringResource(R.string.today_metric_weight),
                value = weight.value,
                unit = "",
                tint = Palette.accent,
                frac = null,
            )
        },
        KeyMetric.CALORIES to KeyTileData(
            label = stringResource(R.string.today_metric_calories),
            value = d?.activeKcalEst?.let { intString(it) } ?: NO_DATA,
            unit = if (d?.activeKcalEst != null) "kcal" else "",
            tint = Palette.metricAmber,
            frac = d?.activeKcalEst?.let { (it / 800.0).coerceIn(0.0, 1.0) },
        ),
    )

    // Resolve the enabled tiles to their descriptors, dropping any unknown key defensively.
    val allTiles = enabledMetrics.mapNotNull { descriptors[it] }
    // S5: slice from the FRONT of the saved order so a pinned/selected tile is never dropped or reordered
    //; only the tail folds behind the expander.
    val hasOverflow = allTiles.size > METRICS_COLLAPSED_CAP
    val tiles = if (metricsExpanded || !hasOverflow) allTiles else allTiles.take(METRICS_COLLAPSED_CAP)

    // 3 columns, spacing 8. Build from rows so tile heights tile uniformly and a partial last row
    // pads with empty weight so the columns stay aligned.
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        tiles.chunked(3).forEach { rowTiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                rowTiles.forEach { tile -> KeyTile(tile, modifier = Modifier.weight(1f)) }
                repeat(3 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        // S5: the "Show all metrics" / "Show fewer" expander, a centered link. Toggles visibility
        // only, never WHICH tiles are enabled or their order (that stays the editor's job).
        if (hasOverflow) {
            val hidden = allTiles.size - METRICS_COLLAPSED_CAP
            TextButton(
                onClick = onToggleMetrics,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent),
            ) {
                Text(
                    if (metricsExpanded) {
                        stringResource(R.string.today_show_fewer)
                    } else {
                        stringResource(R.string.today_show_all_metrics, hidden)
                    },
                    style = NoopType.subhead,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (metricsExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** One compact Key-Metrics tile's data: label, value, unit, tint and the bar's fill fraction.
 *  [spokenLabel] carries the unabbreviated name where the drawn one is a short form, so a screen
 *  reader never has to expand "RESP" for the listener. [frac] is null when the tile HAS no scale to
 *  read the value against, which is not the same as a value sitting at the bottom of one. */
private data class KeyTileData(
    val label: String,
    val value: String,
    val unit: String,
    val tint: Color,
    val frac: Double?,
    val spokenLabel: String? = null,
)

/** Corner radius of a key-metric tile, tighter than a full card so three sit in a row without crowding. */
private val KEY_TILE_RADIUS = 16.dp

/**
 * One key-metric tile: an overline label, the value + small unit, and a progress bar tinted
 * [KeyTileData.tint] to [KeyTileData.frac]. A No-Data value dims and the bar reads empty.
 */
@Composable
private fun KeyTile(data: KeyTileData, modifier: Modifier = Modifier) {
    val hasValue = data.value != NO_DATA
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(KEY_TILE_RADIUS))
            .frostedCardSurface(cornerRadius = KEY_TILE_RADIUS)
            .padding(horizontal = Metrics.space12, vertical = Metrics.space10)
            .semantics {
                contentDescription = "${data.spokenLabel ?: data.label} ${data.value} ${data.unit}".trim()
            },
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Text(
            data.label.uppercase(),
            style = NoopType.overline,
            color = Palette.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                data.value,
                style = NoopType.number(17f),
                color = if (hasValue) Palette.textPrimary else Palette.textTertiary,
                maxLines = 1,
            )
            if (data.unit.isNotEmpty() && hasValue) {
                Text(
                    // Empty value, so this is the unit with the join's own spacing: "%" binds tight.
                    UnitFormatter.withUnit("", data.unit),
                    style = NoopType.caption,
                    color = Palette.textPrimary,
                    maxLines = 1,
                )
            }
        }
        // A tile only gets a bar when it HAS a scale to fill. Weight has a reading and no range to
        // normalise it against, so `frac` is null there; drawn as 0% it put a real 79.2 kg over an
        // empty track and read as bottom-of-range. The slot is held so the three-up rows still tile
        // to one height.
        if (data.frac != null) {
            LinearProgressIndicator(
                progress = { data.frac.toFloat() },
                color = data.tint,
                trackColor = Palette.surfaceInset,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Metrics.progressHeight),
            )
        } else {
            Spacer(Modifier.height(Metrics.progressHeight))
        }
    }
}

// Workouts across every recorded + imported source over [from, to]. Recorded sessions live under
// the ACTIVE strap id — "whoop-<id>" after a re-pair — unioned with the canonical legacy "my-whoop"
// via [WhoopRepository.workoutsUnion]: this read was pinned to the literal "my-whoop", which
// stranded a re-paired strap's fresh recordings, so the "Latest Workouts" feed and the HR-graph
// glyphs silently dropped the newest sessions while the Workouts screen (already on the union)
// still showed them. Apple Health and Health Connect imports are stored under their own device ids
// of their own. Both the "Last Workouts" feed and the HR-graph sport glyphs need the SAME union,
// or Health-Connect-imported sessions get no glyph on the Today trend, so they share this one seam.
// Deduped here (not per-consumer) with the Workouts screen's semantics: a live strap recording
// and its thin Health Connect import collapse to the richer row, so neither the feed shows a
// duplicate card nor the HR trend a doubled sport glyph. dropDetectedShadows/filterDismissed are
// deliberately absent: `detected` rows live under `<deviceId>-noop`, which this union never queries.
internal suspend fun WhoopRepository.workoutsAllSources(
    activeDeviceId: String,
    from: Long,
    to: Long,
): List<WorkoutRow> =
    WorkoutEditing.dedupCrossSource(
        workoutsUnion(from, to) +
            workouts("apple-health", from, to) +
            workouts("health-connect", from, to)
    )

// MARK: - Readiness card
//
// On-device training-readiness synthesis. Calls the analytics ReadinessEngine over the
// view model's day history and renders: a colored level dot + headline, an optional
// acute:chronic "load X.XX" read-out, the plain-English summary, then one row per driving
// signal (a small flag-colored dot + label + detail). The whole card is suppressed until
// there is enough history (level == INSUFFICIENT).

@Composable
internal fun ReadinessSection(days: List<DailyMetric>, carriedDay: DailyMetric? = null) {
    // Logical day (rolls at 04:00 local), so readiness keeps reading the evening's row in the small
    // hours instead of an empty new-calendar-day row. Mirrors the Today-row resolution.
    //
    // Carry-over: Readiness anchors on the day whose row carries today's vitals. Right after the
    // rollover today has no scored row, so `evaluate` would read INSUFFICIENT and the whole card would
    // VANISH while live HR ticks, the same blank the carried Charge/Synthesis avoid. So when carrying,
    // anchor on the last scored day's key instead, and stamp the overline "Last night · <date>". Honest:
    // it's the real prior read; today's own readiness wins the instant tonight is scored.
    val anchorKey = carriedDay?.day ?: logicalDayKeyNow()
    val readiness = remember(days, anchorKey) { ReadinessEngine.evaluate(days, today = anchorKey) }
    if (readiness.level == ReadinessEngine.Level.INSUFFICIENT) return

    val overline = carriedDay?.let { stringResource(carriedCaption(it.day), lastChargeDateLabel(it.day)) }
        ?: stringResource(R.string.today_readiness_overline)
    SectionHeader(stringResource(R.string.today_readiness), overline = overline)
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            // Headline row: level dot + headline, then the ACWR load read-out.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(readinessColor(readiness.level)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    readiness.headline,
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                readiness.acwr?.let { acwr ->
                    Text(
                        stringResource(
                            R.string.today_readiness_load,
                            String.format(Locale.US, "%.2f", acwr),
                        ),
                        style = NoopType.captionNumber,
                        color = Palette.textTertiary,
                    )
                }
            }

            // Plain-English summary.
            Text(
                readiness.summary,
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )

            // Per-signal rows: flag dot + fixed-width label + detail.
            if (readiness.signals.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Palette.hairline),
                )
                readiness.signals.forEach { signal ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(flagColor(signal.flag)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            signal.label,
                            style = NoopType.caption,
                            color = Palette.textSecondary,
                            modifier = Modifier.width(104.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                signal.detail,
                                style = NoopType.caption,
                                color = Palette.textTertiary,
                            )
                            // The numbers behind the read (e.g. "48 vs 55 ms"), as a small mono
                            // caption, matching the "load X.XX" numeric readout above.
                            signal.evidence?.let { evidence ->
                                Text(
                                    evidence,
                                    style = NoopType.captionNumber,
                                    color = Palette.textTertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one-word readiness read on the hero (Push / Maintain / Rest). PURE mapping of the whoop-rs HRV
 * readiness tier; a null tier (still calibrating) returns null and the hero shows no word.
 */
@StringRes
internal fun hrvReadinessWord(tier: uniffi.whoop_ffi.ReadinessTier?): Int? = when (tier) {
    uniffi.whoop_ffi.ReadinessTier.PRIMED -> R.string.core_readiness_push
    uniffi.whoop_ffi.ReadinessTier.NORMAL -> R.string.core_readiness_maintain
    uniffi.whoop_ffi.ReadinessTier.SUPPRESSED -> R.string.core_readiness_rest
    null -> null
}

/** Tier -> the pill tint, reusing the existing readiness palette. */
internal fun hrvReadinessColor(tier: uniffi.whoop_ffi.ReadinessTier?): Color = when (tier) {
    uniffi.whoop_ffi.ReadinessTier.PRIMED -> Palette.accent
    uniffi.whoop_ffi.ReadinessTier.NORMAL -> Palette.statusPositive
    uniffi.whoop_ffi.ReadinessTier.SUPPRESSED -> Palette.statusWarning
    null -> Palette.textTertiary
}

/**
 * Retained for the Coupled screen's readiness pill, which still reads the multi-signal engine.
 */
@StringRes
internal fun readinessWord(level: ReadinessEngine.Level): Int? = when (level) {
    ReadinessEngine.Level.PRIMED -> R.string.core_readiness_push
    ReadinessEngine.Level.BALANCED -> R.string.core_readiness_maintain
    ReadinessEngine.Level.STRAINED -> R.string.core_readiness_rest
    ReadinessEngine.Level.RUNDOWN -> R.string.core_readiness_rest
    ReadinessEngine.Level.INSUFFICIENT -> null
}

/**
 * S5: the sources with data behind the collapsed Data Sources footer, in display order and named the
 * way the audience knows them (Apple Health reads as "Apple Watch"; Health Connect is named for what it
 * is, never folded under it). Product names, so they read the same in every language; the caller words
 * the sentence around them. PURE + unit-tested.
 */
internal fun syncedFromSources(
    hasWhoop: Boolean,
    hasApple: Boolean,
    hasHealthConnect: Boolean = false,
    hasXiaomi: Boolean,
): List<String> = buildList {
    if (hasWhoop) add("WHOOP")
    if (hasApple) add("Apple Watch")
    if (hasHealthConnect) add("Health Connect")
    if (hasXiaomi) add("Mi Band")
}

/** S5: the Key-Metric overflow cap, mirroring TodayView.metricsCollapsedCap (two columns, three rows). */
internal const val METRICS_COLLAPSED_CAP = 6

/** Level → color, mirroring TodayView.readinessColor. */
internal fun readinessColor(level: ReadinessEngine.Level): Color = when (level) {
    ReadinessEngine.Level.PRIMED -> Palette.accent
    ReadinessEngine.Level.BALANCED -> Palette.statusPositive
    ReadinessEngine.Level.STRAINED -> Palette.statusWarning
    ReadinessEngine.Level.RUNDOWN -> Palette.metricRose
    ReadinessEngine.Level.INSUFFICIENT -> Palette.textTertiary
}

/** Flag → color, mirroring TodayView.flagColor. */
private fun flagColor(flag: ReadinessEngine.Flag): Color = when (flag) {
    ReadinessEngine.Flag.GOOD -> Palette.accent
    ReadinessEngine.Flag.NEUTRAL -> Palette.textTertiary
    ReadinessEngine.Flag.WATCH -> Palette.statusWarning
    ReadinessEngine.Flag.BAD -> Palette.metricRose
}

// MARK: - Illness banner

@Composable
internal fun IllnessBanner(message: String) {
    // Frosted Bevel warning card (amber tint).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .frostedCardSurface(tint = Palette.statusWarning, cornerRadius = Metrics.cardRadius)
            .padding(Metrics.space14),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Palette.statusWarning.copy(alpha = StrandAlpha.warningFill)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.statusWarning)
        }
        Text(message, style = NoopType.subhead, color = Palette.textPrimary)
    }
}

// MARK: - Derived text (ported from TodayView.swift)

internal fun sleepValue(d: DailyMetric?): String {
    val m = d?.totalSleepMin ?: return NO_DATA
    val total = m.roundToInt()
    return "${total / 60}h ${total % 60}m"
}

/**
 * H9, whether THIS night's sleep STAGING is low-confidence, read from the core's existing
 * [ScoreConfidence] rule (never fabricated). True exactly when the night has staged sleep (so the base
 * Rest tier is SOLID) yet the H9 overload DOWNGRADES it, a high-efficiency night whose deep+REM share
 * is implausibly low, far more likely a staging miss (the EEG-free classifier's weak spot) than a real
 * night with almost no restorative sleep. We surface that honestly with a small "Stages estimated" badge
 * rather than faking stages or tanking the Rest score. Reads only the day's banked stage figures
 * (efficiency is the engine's 0..1 fraction; restorative = deep+REM), so it's the SAME decision the
 * daily pass made into `restConfidence`. Returns false for a missing day, a calibrating/building base
 * tier, or any night the core deems SOLID. Pure + unit-tested.
 */
internal fun restStageLowConfidence(d: DailyMetric?): Boolean {
    val asleepMin = d?.totalSleepMin ?: return false
    val efficiency = d.efficiency ?: return false
    val restorativeMin = (d.deepMin ?: 0.0) + (d.remMin ?: 0.0)
    val hasStaged = restorativeMin > 0.0
    // The base (pre-H9) tier: SOLID only when there's staged sleep. If the base isn't SOLID the badge
    // doesn't apply, a calibrating/no-stage night has its own honest treatment, not a "stages off" flag.
    if (ScoreConfidence.forRest(hasSession = true, hasStagedSleep = hasStaged) != ScoreConfidence.SOLID) {
        return false
    }
    // The H9 overload: SOLID stays SOLID unless the high-efficiency / low-restorative staging-miss fires.
    return ScoreConfidence.forRest(
        hasSession = true,
        hasStagedSleep = hasStaged,
        asleepSeconds = asleepMin * 60.0,
        restorativeSeconds = restorativeMin * 60.0,
        efficiency = efficiency,
    ) == ScoreConfidence.BUILDING
}

/**
 * Short "it's coming, not broken" caption for an unscored tile on TODAY only (extended for H10).
 * Rest fills in after a night's sleep; Effort fills in once cardio load is logged; the overnight vitals
 * (SpO₂) and the on-device Steps fill in over the next few nights / today's wear; Charge needs a
 * few nights to learn your baseline. Returns null off-today so a navigated PAST day with no score
 * honestly stays a bare dash (missing data, not mid-calibration), mirrors the recoveryCalibration
 * today-only rule the Charge tile uses. Each call site only reaches here when the value is genuinely
 * absent, so the hint never overwrites a real reading. No em-dashes (house style). Pure + unit-tested.
 */
@StringRes
internal fun buildingHint(metric: KeyMetric, isToday: Boolean): Int? {
    if (!isToday) return null
    return when (metric) {
        KeyMetric.REST -> R.string.core_building_wear_tonight
        KeyMetric.EFFORT -> R.string.core_building_moves_as_you_do
        // H10: an unscored Charge today that ISN'T mid-calibration and has nothing to carry, say what's
        // needed rather than a bare "No Data". (The "Calibrating N of 4" copy still owns the calibrating
        // case at the call site; this only shows once there's genuinely nothing.)
        KeyMetric.CHARGE -> R.string.core_building_wear_tonight
        // H10: the overnight blood-oxygen reading builds from sleep, like the other in-sleep vitals.
        KeyMetric.BLOOD_OXYGEN -> R.string.core_building_wear_tonight
        // H10: on-device steps fill in across today as you move (5/MG counter / imported HC).
        KeyMetric.STEPS -> R.string.core_building_moves_as_you_do
        else -> null
    }
}

// MARK: - Steps / Weight / Calories tile logic
//
// Steps and Calories read straight off today's DailyMetric (the on-device WHOOP5 derivations); the
// pure helpers below back the Weight tile, which has no daily strap source and instead falls back to
// the user's profile weight. Kept pure + file-internal so TodayMetricTilesTest is the oracle.

/** The Weight tile's display string and an honest caption ("from profile" only on fallback). */
internal data class WeightTileText(val value: String, @StringRes val caption: Int?)

/**
 * The newest body weight across the two Apple-side sources (apple-health + health-connect), or null
 * when neither carries one. Days are ISO `yyyy-MM-dd`, which sorts chronologically, so the lexically
 * greatest day with a non-null `weightKg` is the most recent, no date parsing needed.
 */
internal fun latestWeightKg(apple: List<AppleDaily>, healthConnect: List<AppleDaily>): Double? =
    (apple + healthConnect)
        .filter { it.weightKg != null }
        .maxByOrNull { it.day }
        ?.weightKg

/**
 * Steps for [dayKey] from the imported Apple Health / Health Connect daily aggregates, or null when
 * neither source carries a step total for that day. Backs the Today Steps-tile fallback for straps
 * NOOP can't read steps off over Bluetooth, notably the WHOOP 4.0, which DOES count steps (in the
 * official WHOOP app) but doesn't expose them to NOOP, so on a 4.0 the tile shows imported steps
 * rather than "No Data". On-device WHOOP 5/MG steps (DailyMetric.steps) still take precedence at the
 * call site. When both sources report the same day, the larger (most-complete) total wins so we never
 * sum and double-count.
 */
internal fun stepsForDay(apple: List<AppleDaily>, healthConnect: List<AppleDaily>, dayKey: String): Int? =
    (apple + healthConnect)
        .filter { it.day == dayKey }
        .mapNotNull { it.steps }
        .maxOrNull()

/**
 * Resolve the Weight tile text: prefer the latest Apple/Health-Connect weight, else fall back to the
 * SI profile weight with a "from profile" caption so the source stays honest. Both are formatted
 * through the shared [UnitFormatter] so the Imperial/Metric toggle reaches this tile too.
 */
internal fun weightTile(latestWeightKg: Double?, profileWeightKg: Double, system: UnitSystem): WeightTileText =
    if (latestWeightKg != null) {
        WeightTileText(UnitFormatter.massFromKilograms(latestWeightKg, system), R.string.core_weight_latest)
    } else {
        WeightTileText(
            UnitFormatter.massFromKilograms(profileWeightKg, system),
            R.string.core_weight_from_profile,
        )
    }

/** Group-separated integer display from a Double (e.g. 12 345 steps), matching the Apple Health tiles. */
private fun intString(v: Double): String {
    val n = v.roundToInt()
    return if (kotlin.math.abs(n) >= 1000) String.format(Locale.US, "%,d", n) else "$n"
}

internal const val NO_DATA = "No Data"

/** The dashboard-card placeholder for a baseline-relative metric (Stress) that is still seeding its window,  *  an honest "building your baseline" state rather than a bare dash. Rendered dimmed like NO_DATA. */
internal const val STRESS_CALIBRATING = "Calibrating"

private val workoutDateFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.US).withZone(ZoneId.systemDefault())
private val workoutTimeFmt: DateTimeFormatter =
    // Respect the device's 12-/24-hour locale: "7:10 AM" where 12-hour is preferred, "19:10"
    // where 24-hour is, instead of forcing 24-hour on everyone.
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())

/** The two grouped figures for a source card's stored-count line, or null while either is counting. */
internal fun countDetail(days: Int?, workouts: Int?): Pair<String, String>? {
    if (days == null || workouts == null) return null
    return grouped(days) to grouped(workouts)
}

/** Same bands as the Settings Strap battery pill, so the % reads the same colour everywhere. */
internal fun batteryPillTone(pct: Int): StrandTone = when {
    pct <= 15 -> StrandTone.Critical
    pct <= 30 -> StrandTone.Warning
    else -> StrandTone.Positive
}

internal fun workoutDuration(row: WorkoutRow): String {
    val seconds = row.durationS ?: (row.endTs - row.startTs).coerceAtLeast(0L).toDouble()
    if (seconds <= 0.0) return NO_DATA
    val totalMinutes = (seconds / 60.0).roundToInt()
    return if (totalMinutes >= 60) {
        "${totalMinutes / 60}h ${totalMinutes % 60}m"
    } else {
        "${totalMinutes}m"
    }
}

/** "d MMM · HH:mm–HH:mm"; start-only when the end isn't after the start (zero/unknown span). */
internal fun workoutCaption(row: WorkoutRow): String {
    val date = workoutDateFmt.format(Instant.ofEpochSecond(row.startTs))
    val start = workoutTimeFmt.format(Instant.ofEpochSecond(row.startTs))
    return if (row.endTs > row.startTs) {
        "$date · $start - ${workoutTimeFmt.format(Instant.ofEpochSecond(row.endTs))}"
    } else {
        "$date · $start"
    }
}

private fun grouped(value: Int): String =
    String.format(Locale.US, "%,d", value)

// MARK: - Key-Metrics layout editor
//
// A Today-local dialog (no new nav destination, another lane owns the nav graph) for choosing which
// Key-Metric tiles show on the Control Center and in what order. Display-only: it edits the persisted
// `today.keyMetrics` layout, never any stored metric. A switch hides/shows a tile and the up/down arrows
// reorder it, explicit arrows rather than drag so it behaves the same on every device.

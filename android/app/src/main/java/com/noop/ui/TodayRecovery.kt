package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.analytics.BaselineState
import com.noop.analytics.Baselines
import com.noop.analytics.CalibrationMilestones
import com.noop.analytics.ChargeDriver
import com.noop.analytics.RecoveryDrivers
import com.noop.analytics.RecoveryScorer
import com.noop.analytics.RestScorer
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric
import com.noop.data.WhoopRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A1/S4: the Charge breakdown sheet opened by tapping the hero Charge ring. A full-screen surface with a
 * titled top bar (Close) and a scrollable body hosting the existing What-shaped-it breakdown, the
 * Contributors bars and (S4) the folded Readiness card. Built only when shown (the caller gates on
 * showChargeBreakdown), so the heavy rows materialise on tap (#819). Nothing is recomputed here, it reuses
 * the existing sections, which read the SAME carried/today row the ring shows. Mirrors iOS chargeBreakdownSheet.
 * `internal` (not private) so the Coupled view's hero ring (task #43) opens THIS same sheet, one breakdown,
 * never a duplicate.
 */
@Composable
internal fun ChargeBreakdownSheet(
    days: List<DailyMetric>,
    displayDay: DailyMetric?,
    carriedDay: DailyMetric?,
    showReadiness: Boolean,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Palette.surfaceBase) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Metrics.screenPadding, vertical = Metrics.gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "What shaped your Charge",
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Palette.textSecondary)
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Metrics.screenPadding)
                    .padding(bottom = Metrics.sectionGap),
                verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap),
            ) {
                // The breakdown self-gates: a calibrating night (empty drivers) renders nothing here, the
                // Contributors + Readiness below still give an honest read, never a blank sheet.
                RecoveryDriversSection(days = days, displayDay = displayDay, carriedDay = carriedDay)
                RecoveryContributorsSection(day = displayDay, carriedDay = carriedDay)
                // S4: the SEPARATE Readiness block now lives here behind the Charge-ring tap (today-only,
                // matching the old inline gate). A one-word read (Push / Maintain / Rest) stays on the hero.
                if (showReadiness) ReadinessSection(days, carriedDay = carriedDay)
            }
        }
    }
}

// MARK: - "What shaped it" the engine-computed Charge driver breakdown
//
// The SHARED-CONTRACT driver rows under the Charge ring: one row per REAL term the recovery scorer used,
// each carrying its signed point contribution (deltaPoints), the night's value, the personal baseline it
// was scored against, and a short plain-English verdict. Computed by RecoveryDrivers.chargeDrivers from
// the SAME inputs the Charge ring reads, so a row can never describe a term the score did not use; a
// missing input yields NO row (never a faked zero). The confidence dot + tier tag SURFACE the existing
// ScoreConfidence.forCharge: they are read, not recomputed. Hidden entirely when the day can't score
// (cold-start / no drivers). Byte-aligned with the iOS "What shaped it" section. No em-dashes.

@Composable
internal fun RecoveryDriversSection(
    days: List<DailyMetric>,
    displayDay: DailyMetric?,
    carriedDay: DailyMetric? = null,
) {
    // Read the row the Charge ring itself reads: today's own when scored, else the carried last-scored
    // day (#543) so the breakdown matches the carried ring instead of vanishing at the rollover.
    val readDay = carriedDay ?: displayDay
    val drivers = remember(days, readDay) { recoveryChargeDrivers(days, readDay) }
    if (drivers.isEmpty()) return

    val tier = remember(days, readDay) { chargeConfidenceTier(days, readDay) }
    val overline = carriedDay?.let { "Charge · ${carriedCaption(it.day)}" } ?: "Charge"

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // Header row: section title + the SURFACED confidence pill (dot + tier tag) on the right.
        Row(verticalAlignment = Alignment.Top) {
            Box(modifier = Modifier.weight(1f)) {
                SectionHeader("What shaped it", overline = overline, trailing = "vs your baseline")
            }
            ChargeConfidencePill(tier)
        }
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                drivers.forEach { DriverRow(it) }
                Text(
                    "Each line is how many points that signal moved Charge versus sitting at your " +
                        "on-device baseline. Approximate, not medical advice.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

/** The SURFACED Charge confidence pill (dot + tier tag). Reads the existing ScoreConfidence, never
 *  recomputes it. SOLID = gold/accent, BUILDING = the blue warning tone, CALIBRATING = neutral slate. */
@Composable
private fun ChargeConfidencePill(tier: ScoreConfidence) {
    val (label, tone) = when (tier) {
        ScoreConfidence.SOLID -> "SOLID" to StrandTone.Accent
        ScoreConfidence.BUILDING -> "BUILDING" to StrandTone.Warning
        ScoreConfidence.CALIBRATING -> "CALIBRATING" to StrandTone.Neutral
    }
    StatePill(title = label, tone = tone)
}

/** One "What shaped it" driver row: an up/down delta chip (signed points, green up / red down),
 *  the label + verdict, and the value over its baseline. Mirrors the iOS driver row layout. */
@Composable
private fun DriverRow(driver: ChargeDriver) {
    val positive = driver.deltaPoints >= 0
    // A zero delta reads neutral (no green/red), not a misleading "good".
    val tone = when {
        driver.deltaPoints > 0 -> Palette.statusPositive
        driver.deltaPoints < 0 -> Palette.statusCritical
        else -> Palette.textTertiary
    }
    val signed = if (driver.deltaPoints > 0) "+${driver.deltaPoints}" else "${driver.deltaPoints}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics {
            contentDescription =
                "${driver.label}, ${driver.valueText}, ${driver.baselineText}, " +
                    "$signed points, ${driver.verdict}"
        },
    ) {
        // Signed-point delta chip with a direction glyph.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(Metrics.cornerPill))
                .background(tone.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (driver.deltaPoints != 0) {
                Icon(
                    if (positive) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text("$signed pts", style = NoopType.captionNumber, color = tone)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(driver.label, style = NoopType.headline, color = Palette.textPrimary)
            Text(driver.verdict, style = NoopType.footnote, color = Palette.textSecondary)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(driver.valueText, style = NoopType.captionNumber, color = Palette.textPrimary)
            Text(driver.baselineText, style = NoopType.footnote, color = Palette.textTertiary)
        }
    }
}

// MARK: - Recovery contributors (README screen #5), labelled progress bars
//
// "CONTRIBUTORS", what drove today's Charge, each as a labelled progress bar in the shared stage/zone
// bar style (inset track, round-capped metric-hue fill, right-aligned read-out). Design-Reset tokens
// (iOS RecoveryContributorsSection parity): HRV reads teal (metricCyan), Resting HR the recovery/Charge
// world (chargeColor), Sleep and Respiratory the blue sleep world. Each bar's fraction is a
// presentation-only normalisation of the day's value to a typical adult span, no scoring/logic change.
// Suppressed entirely until at least one contributor has a value.

@Composable
internal fun RecoveryContributorsSection(day: DailyMetric?, carriedDay: DailyMetric? = null) {
    // The row the contributors read from: today's own when it carries recovery, else the carried last
    // scored day (#543) so the bars don't all read "No Data" at the rollover while live HR ticks. The
    // overline stamps "Last night · <date>" when carrying so the prior read isn't passed off as today's.
    val cd = carriedDay ?: day
    val hrv = cd?.avgHrv
    val rhr = cd?.restingHr?.toDouble()
    val sleepMin = cd?.totalSleepMin
    val resp = cd?.respRateBpm
    if (hrv == null && rhr == null && sleepMin == null && resp == null) return

    val overline = carriedDay?.let { "Recovery · ${carriedCaption(it.day)}" } ?: "Recovery"
    SectionHeader("Contributors", overline = overline, trailing = "What drove Charge")
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            // HRV, higher is better; map a typical 20–120 ms span. Teal (its biometric hue; iOS metricCyan).
            ContributorBar(
                label = "HRV",
                readout = hrv?.let { "${it.roundToInt()} ms" } ?: NO_DATA,
                fraction = hrv?.let { ((it - 20.0) / 100.0) },
                color = Palette.metricCyan,
            )
            // Resting HR, lower is better, so invert a typical 40–80 bpm span. Charge/recovery world (iOS
            // chargeColor, the recovery contributor reads on the WHOOP-green Charge world, not gold).
            ContributorBar(
                label = "Resting HR",
                readout = rhr?.let { "${it.roundToInt()} bpm" } ?: NO_DATA,
                fraction = rhr?.let { 1.0 - ((it - 40.0) / 40.0) },
                color = Palette.chargeColor,
            )
            // Sleep, hours in bed against an 8h target. Blue (sleep world).
            ContributorBar(
                label = "Sleep",
                readout = sleepMin?.let { sleepValue(cd) } ?: NO_DATA,
                fraction = sleepMin?.let { (it / 60.0) / 8.0 },
                color = Palette.sleepLight,
            )
            // Respiratory, stability around a typical 12–20 rpm span. Deep blue (sleep world).
            ContributorBar(
                label = "Respiratory",
                readout = resp?.let { String.format(Locale.US, "%.1f rpm", it) } ?: NO_DATA,
                fraction = resp?.let { 1.0 - ((it - 12.0) / 8.0) },
                color = Palette.sleepDeep,
            )
            Text(
                "Baselines learned on-device over 14 days. Bars are an approximate read of each " +
                    "signal against a typical adult range, not medical advice.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

/** One labelled contributor bar: a label + right-aligned read-out over a liquid TUBE filled to [fraction].
 *  These ARE genuine single-value progress bars (each signal against a typical adult span), so the liquid
 *  finish reads well here (matching how the iOS liquid Today draws its single-value goal/strain bars as
 *  tubes). Static (not per-frame) — they sit in the tapped-open Charge breakdown, not a live surface, so
 *  `animated = false` keeps the sheet cheap. A null fraction renders an empty tube. */
@Composable
private fun ContributorBar(label: String, readout: String, fraction: Double?, color: Color) {
    val fillFrac = fraction?.coerceIn(0.0, 1.0) ?: 0.0
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Overline(label, modifier = Modifier.weight(1f))
            Text(readout, style = NoopType.captionNumber, color = Palette.textPrimary)
        }
        LiquidTube(
            frac = fillFrac,
            tint = color,
            height = Metrics.progressHeight,
            animated = false,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "$label $readout" },
        )
    }
}

/**
 * The recovery baseline's real seed count while it still cold-starts, the honest "calibrating N of
 * <seed>" progress shown in place of "No Data"; null once recovery exists or the baseline has crossed
 * the seed gate. N is the HRV baseline's `nValid` from folding the SAME day-keyed, epoch-aware history
 * the recovery engine folds ([Baselines.foldHistory] with [hrvBaselineEpoch]), NOT a looser per-night
 * bounds count.
 *
 * The old count advanced on every in-range night, including nights the engine's fold DROPS after a
 * manual "Recalibrate HRV baseline" (each night dated before the epoch is discarded, not skip-and-held).
 * A genuinely-calibrating user who had >= seed old in-range nights therefore read `count >= seed → null`,
 * and the Today score side fell through to [ScoreState.NeedsStrap] while the post-recalibration baseline
 * was still seeding (Bug B, #393 follow-up). `nValid` is the exact count Baselines.computeStatus gates
 * CALIBRATING on, so N now tracks the baseline the Charge ring rides and can never over-state it.
 * [days] is oldest→newest (same order the engine folds). Pure + unit-tested (RecoveryCalibrationTest).
 * (PR #85)
 */
internal fun recoveryCalibrationNights(
    days: List<DailyMetric>,
    hasRecovery: Boolean,
    hrvBaselineEpoch: Double,
    seed: Int = Baselines.minNightsSeed,
): Int? {
    if (hasRecovery) return null
    val n = Baselines.foldHistory(
        days.map { it.avgHrv }, days.map { it.day }, Baselines.hrvCfg, hrvBaselineEpoch,
    ).nValid
    // Include 0: a brand-new user (no banked nights) reads "Calibrating, 0 of N" on Charge, not a
    // bare "No data" that looks broken (#335). Caller gates past days to null; >= seed → null.
    return n.takeIf { it in 0 until seed }
}

/**
 * The ordered "What shaped it" Charge driver rows for [displayDay], rebuilt PURELY from the visible
 * [days] history (the same in-memory rows the dashboard already shows, imports win field-by-field in
 * the merge), so no engine round-trip is needed and the bars match the Charge ring's own inputs. Folds
 * the whole history (oldest first) into the four-plus-one personal baselines with [Baselines.foldHistory]
 * (byte-identical to the engine's whole-history fold when no manual Recalibrate epoch is set, the common
 * case), then defers to [RecoveryDrivers.chargeDrivers], which scores each row against the SAME inputs
 * [RecoveryScorer.recovery] reads. Empty when the displayed day can't score (cold-start / missing input),
 * so the section hides rather than faking rows. Mirrors the iOS chargeDrivers wiring.
 */
internal fun recoveryChargeDrivers(
    days: List<DailyMetric>,
    displayDay: DailyMetric?,
): List<ChargeDriver> {
    val day = displayDay ?: return emptyList()
    val hrv = day.avgHrv ?: return emptyList()
    val rhr = day.restingHr?.toDouble() ?: return emptyList()

    // Whole-history fold (oldest first), exactly as the engine seeds baselines2.
    val ordered = days.sortedBy { it.day }
    val hrvBase = Baselines.foldHistory(ordered.map { it.avgHrv }, Baselines.hrvCfg)
    if (!hrvBase.usable) return emptyList()
    val rhrBase = Baselines.foldHistory(ordered.map { it.restingHr?.toDouble() }, Baselines.restingHRCfg)
    val respBase = Baselines.foldHistory(ordered.map { it.respRateBpm }, Baselines.respCfg).takeIf { it.usable }

    // sleepPerf: the Rest COMPOSITE (÷100) when stages exist, else raw efficiency, the SAME derivation
    // recomputeRecovery uses, so the Sleep driver scores against the headline's own input.
    val sleepPerf = RestScorer.restFromDaily(day)?.let { it / 100.0 } ?: day.efficiency

    return RecoveryDrivers.chargeDrivers(
        hrv = hrv,
        rhr = rhr,
        resp = day.respRateBpm,
        hrvBaseline = hrvBase,
        rhrBaseline = rhrBase,
        respBaseline = respBase,
        sleepPerf = sleepPerf,
        skinTempDev = day.skinTempDevC,
    )
}

/**
 * The Charge (recovery) [ScoreConfidence] tier for [displayDay] against the HRV baseline folded from
 * [days], surfaced as the confidence dot + tier tag under the "What shaped it" rows. SURFACED, never
 * recomputed differently: it calls [ScoreConfidence.forCharge] with the SAME folded HRV baseline the
 * drivers scored against. Mirrors the iOS surfacing of the existing ScoreConfidence on the recovery screen.
 */
internal fun chargeConfidenceTier(
    days: List<DailyMetric>,
    displayDay: DailyMetric?,
): ScoreConfidence {
    val hrvBase: BaselineState =
        Baselines.foldHistory(days.sortedBy { it.day }.map { it.avgHrv }, Baselines.hrvCfg)
    return ScoreConfidence.forCharge(displayDay?.recovery, hrvBase)
}

/**
 * The most recent fully-SCORED recovery day to carry over on TODAY while tonight's recovery hasn't been
 * scored yet (#543), the ONE prior row every recovery-derived read-out (Charge ring, HRV / resting-HR /
 * respiratory / SpO₂ tiles, Synthesis, Contributors, Readiness) carries over from at the rollover. Pure +
 * unit-tested (TodayMetricTilesTest). [days] is oldest→newest; the chosen row is the last with a non-null
 * recovery that isn't today's (still-null) [selectedDayKey]. Returns null unless it's today, today itself
 * isn't scored, and we're not mid-calibration (calibration owns its own copy), so past days / a scored
 * today / a calibrating today carry nothing and live behaviour is unchanged. Mirrors iOS.
 */
internal fun lastScoredRecoveryDay(
    days: List<DailyMetric>,
    selectedDayKey: String,
    isToday: Boolean,
    todayScored: Boolean,
    isCalibrating: Boolean,
    // #547 carry-over guard: the local "today" key ("yyyy-MM-dd"). A stray FUTURE-dated row (a bad strap
    // clock wrote a day past today) must NEVER be picked as "last night", that's how #547's Today header
    // read "12 Jul". Cheap belt-and-suspenders alongside the ingest gate + heal: filter candidates to
    // day <= today so even a future row that slipped through can't surface here. ISO date keys sort
    // chronologically, so a plain string compare is correct. Defaulted to MAX so an un-updated call site
    // keeps the prior behaviour; the Today call site passes the real local today.
    today: String = "9999-12-31",
): DailyMetric? {
    if (!isToday || todayScored || isCalibrating) return null
    return days.lastOrNull { it.recovery != null && it.day != selectedDayKey && it.day <= today }
}

/** A prior day's Charge carried over on TODAY (value + "Last night · <date>" caption) while tonight's
 *  recovery hasn't been scored yet (#543). Mirrors the iOS lastScoredCharge tuple. */
internal data class LastCharge(val value: Double, val caption: String)

/** "d MMM" for a stored `yyyy-MM-dd` day key, used by the carried-over Charge caption (#543). Parses
 *  the key and falls back to the raw key so the caption is never empty. Mirrors iOS lastChargeDateFmt. */
internal fun lastChargeDateLabel(dayKey: String): String =
    runCatching {
        LocalDate.parse(dayKey).format(DateTimeFormatter.ofPattern("d MMM", Locale.US))
    }.getOrDefault(dayKey)

/** Carry-over recency cap (#779): the "Last night" framing only holds when the carried scored day is
 *  within this many days of today. Mirrors iOS TodayView.carryFreshnessDays. */
internal const val CARRY_FRESHNESS_DAYS = 2L

/** True when the carried scored day is OLDER than the freshness cap (#779), which drives the "Latest
 *  sleep" relabel. Pure + unit-testable. Both keys are "yyyy-MM-dd"; an unparseable key (or non-positive gap)
 *  reads as fresh so we never over-claim staleness. [today] is today's key (carry-over is today-only),
 *  defaulted to the device's current date for the composable call sites. Mirrors iOS isCarryStale. */
internal fun isCarryStale(priorDayKey: String, today: String = LocalDate.now().toString()): Boolean =
    runCatching {
        ChronoUnit.DAYS.between(LocalDate.parse(priorDayKey), LocalDate.parse(today)) > CARRY_FRESHNESS_DAYS
    }.getOrDefault(false)

/** #977 — HONEST Rest resolution for the selected day. Today's own scored Rest wins; otherwise, ONLY on
 *  today, tail-fall-back to the last scored night — but ONLY when that night is within the carry-freshness
 *  window ([isCarryStale] == false). A live 5.0 whose sleep never scores (no overnight gravity ⇒ no
 *  `sleep_performance` point ever written) used to pin Rest to a weeks-old scored night while Charge kept
 *  advancing; gating the tail-fallback lets the Rest ring fall through to its needs-a-tracked-night state
 *  instead of freezing on a stale number. The legitimate morning carry of last night's Rest (before today
 *  scores) is preserved unchanged. Pure + unit-testable. Mirrors iOS TodayView.freshRestScore. */
internal fun freshRestScore(
    todayValue: Double?, lastDay: String?, lastValue: Double?,
    isTodaySelected: Boolean, today: String = LocalDate.now().toString(),
): Double? {
    if (todayValue != null) return todayValue
    if (!isTodaySelected || lastDay == null || lastValue == null) return null
    return if (isCarryStale(lastDay, today)) null else lastValue
}

/** The carried recovery caption stamp, keyed on that scored day's own date and its recency. Within the
 *  freshness cap it reads "Last night · <date>"; once the carried day is older than the cap (#779) it reads
 *  "Latest sleep · <date>" so a weeks-old import is never surfaced as "Last night". Shared by every carried
 *  recovery read-out so the prior-day provenance reads identically. Mirrors iOS carriedCaption. */
internal fun carriedCaption(priorDayKey: String, today: String = LocalDate.now().toString()): String {
    val prefix = if (isCarryStale(priorDayKey, today)) "Latest sleep" else "Last night"
    return "$prefix · ${lastChargeDateLabel(priorDayKey)}"
}

// ════════════════════════════════════════════════════════════════════════════════════════════════════
// Explainability layer, COMPONENTS 2, 3, 4 (spec: 2026-06-20-sleep-guidance-explainability.md)
//
// "No bare number without a STATE, a REASON, and a NEXT STEP." Every uncertain or derived read-out on
// Today gets a clear state, a plain-English reason and a next step, and we NEVER fabricate a number:
// calibrating / needs-strap show NO value, carried values are always stamped with their date, and the
// provenance badge reflects the REAL per-day merge winner. The copy here is VERBATIM and must match the
// Swift today lane word-for-word (ScoreState / RecordingState). No em-dashes anywhere.
// ════════════════════════════════════════════════════════════════════════════════════════════════════

// ── COMPONENT 2, explained score states ─────────────────────────────────────────────────────────────

/**
 * The honest state of one score/tile on Today, one state per score, never a bare blank. Derived from
 * baseline readiness + data presence + the #543 carry-over, so a tile that has no own value for the day
 * still says WHY and WHAT to do, and shows no fabricated number. Mirrors Swift `ScoreState` 1:1 (same
 * three cases, same [title] / [detail] copy). [Scored] carries the real value the tile renders normally;
 * the other three are the no-own-number states this layer explains.
 */
sealed class ScoreState {
    /** Today's own value exists, the tile renders the number as usual; this layer adds nothing. */
    data class Scored(val value: Double) : ScoreState()

    /** Baselines still cold-start: [nightsRemaining] more nights of wear until scores get personal.
     *  Shows NO number (calibrating never fakes a value). */
    data class Calibrating(val nightsRemaining: Int) : ScoreState()

    /** A prior scored day shown before tonight is scored (#543 carry-over), stamped with [dateLabel]
     *  ("d MMM") so the prior read is never passed off as today's. [stale] is true when that day is older
     *  than the freshness cap (#779): the carry is still shown so the recovery side isn't a bare blank, but
     *  it's relabelled "Latest sleep" so a weeks-old import is never passed off as "Last night". */
    data class CarriedLastNight(val dateLabel: String, val stale: Boolean = false) : ScoreState()

    /** No data for today at all, strap not worn / not connected / not synced. Shows NO number. */
    object NeedsStrap : ScoreState()

    /** The status title shown in the tile's state slot. VERBATIM, mirror Swift exactly. */
    val title: String
        get() = when (this) {
            is Scored -> ""
            is Calibrating -> "Calibrating"
            is CarriedLastNight -> if (stale) "Latest sleep · $dateLabel" else "Last night · $dateLabel"
            NeedsStrap -> "Needs the strap"
        }

    /** The one-line plain-English what-to-do. VERBATIM, mirror Swift exactly. The night(s) plural in
     *  the calibrating copy follows [nightsRemaining]. */
    val detail: String
        get() = when (this) {
            is Scored -> ""
            is Calibrating -> {
                val nights = if (nightsRemaining == 1) "night" else "nights"
                "Building your baseline. About $nightsRemaining more $nights until your scores are personal."
            }
            is CarriedLastNight ->
                // A fresh post-rollover carry tells you tonight's score is on its way; a stale carry (an
                // older import, #779) instead explains the number is from that earlier session, not today.
                if (stale) "This is your last scored session. Wear the strap overnight for a fresh score."
                else "Tonight's lands after you sleep with the strap on."
            NeedsStrap -> "No data for today. Was your strap worn and connected overnight?"
        }
}

/**
 * Resolve the honest [ScoreState] for the Today score side from the same signals the tiles already use,
 * so the explainer is the EXACT truth on screen (never a separate guess). Pure + unit-tested. Order of
 * precedence mirrors the tile waterfall:
 *   1. [todayRecovery] present                → [ScoreState.Scored] (the tile shows its real number);
 *   2. mid-calibration ([calibratingNights])  → [ScoreState.Calibrating] (N more nights, no number);
 *   3. a prior scored day to carry (#543)     → [ScoreState.CarriedLastNight] (stamped with its date);
 *   4. otherwise                              → [ScoreState.NeedsStrap] (no data, no number).
 * Mirrors Swift `scoreStateForToday`.
 */
internal fun scoreStateForToday(
    todayRecovery: Double?,
    calibratingNights: Int?,
    carriedDay: DailyMetric?,
    seed: Int = Baselines.minNightsSeed,
    today: String = LocalDate.now().toString(),
): ScoreState = when {
    todayRecovery != null -> ScoreState.Scored(todayRecovery)
    // "About N more nights" = the seed gate minus the nights banked so far, floored at 1 (zero would read
    // as "ready" when it isn't). Calibrating never fakes a value.
    calibratingNights != null -> ScoreState.Calibrating((seed - calibratingNights).coerceAtLeast(1))
    // #779: a carry older than the freshness cap is still shown (not a bare blank) but relabelled to
    // "Latest sleep" so a weeks-old import is never passed off as "Last night".
    carriedDay != null -> ScoreState.CarriedLastNight(lastChargeDateLabel(carriedDay.day), isCarryStale(carriedDay.day, today))
    else -> ScoreState.NeedsStrap
}

/**
 * The gamified calibration-milestone countdown stack (WHOOP-style "Calibration Timeline"). Renders one
 * row per milestone: DONE milestones read as a compact "Unlocked" check, the single ACTIVE milestone is
 * the live countdown with an accent liquid progress bar + "N nights to go" + what it unlocks, and LOCKED
 * milestones sit muted below with their own dimmed bar. [progress] is the pure, unit-tested
 * [CalibrationMilestones.progress] output; this composable is presentation only. Design-system tokens
 * only (Palette / Metrics / NoopType). Mirrors the iOS CalibrationMilestonesCard.
 */
@Composable
internal fun CalibrationMilestonesCard(progress: List<CalibrationMilestones.Progress>) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = null,
                    tint = Palette.accent,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                    Text("Calibration milestones", style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        "Wear the strap overnight to unlock each one.",
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }
            }
            progress.forEach { p -> CalibrationMilestoneRow(p) }
        }
    }
}

/** One milestone row inside [CalibrationMilestonesCard], drawn by its [CalibrationMilestones.State]. */
@Composable
private fun CalibrationMilestoneRow(p: CalibrationMilestones.Progress) {
    val m = p.milestone
    val banked = (m.nights - p.remaining).coerceAtLeast(0)
    val nightsWord = if (p.remaining == 1) "night" else "nights"
    when (p.state) {
        // A cleared milestone: a compact green check + "Unlocked", no progress bar (it's full by definition).
        CalibrationMilestones.State.DONE -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "${m.title} unlocked" },
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Palette.statusPositive,
                modifier = Modifier.size(Metrics.iconSmall),
            )
            Text(m.title, style = NoopType.subhead, color = Palette.textSecondary, modifier = Modifier.weight(1f))
            Text("Unlocked", style = NoopType.footnote, color = Palette.statusPositive)
        }

        // The live countdown: accent open-lock, "N nights to go", an accent bar, and what it unlocks.
        CalibrationMilestones.State.ACTIVE -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "${m.title}, $banked of ${m.nights} nights, ${p.remaining} $nightsWord to go. ${m.unlocks}"
                },
            verticalArrangement = Arrangement.spacedBy(Metrics.space6),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = Palette.accent,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
                Text(m.title, style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                Text("${p.remaining} $nightsWord to go", style = NoopType.footnote, color = Palette.accent)
            }
            // Status bar, not a hero surface — posed (animated=false) so it costs nothing per scroll frame.
            LiquidTube(
                frac = p.fraction,
                tint = Palette.accent,
                height = Metrics.progressHeight,
                animated = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("$banked/${m.nights} nights · ${m.unlocks}", style = NoopType.footnote, color = Palette.textSecondary)
        }

        // Still ahead: a muted closed-lock, dimmed bar, and the raw gap — no unlocks copy (keeps it quiet).
        CalibrationMilestones.State.LOCKED -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "${m.title}, $banked of ${m.nights} nights, ${p.remaining} $nightsWord to go"
                },
            verticalArrangement = Arrangement.spacedBy(Metrics.space6),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Palette.textTertiary,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
                Text(m.title, style = NoopType.subhead, color = Palette.textSecondary, modifier = Modifier.weight(1f))
                Text("${p.remaining} $nightsWord to go", style = NoopType.footnote, color = Palette.textTertiary)
            }
            LiquidTube(
                frac = p.fraction,
                tint = Palette.textTertiary,
                height = Metrics.progressHeight,
                animated = false,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The honest score-state note shown in the Today flow when there is no own number to render, the
 *  state title + one what-to-do line, no fabricated value. [ScoreState.Scored] renders nothing (the
 *  tiles carry the real number). The whole card is the spec's "never a bare blank". Mirrors the iOS
 *  ScoreStateNote. */
@Composable
internal fun ScoreStateNote(state: ScoreState) {
    if (state is ScoreState.Scored) return
    val icon = when (state) {
        is ScoreState.Calibrating -> Icons.Filled.Tune
        is ScoreState.CarriedLastNight -> Icons.Filled.History
        ScoreState.NeedsStrap -> Icons.Filled.Warning
        is ScoreState.Scored -> Icons.Filled.Info
    }
    val tint = when (state) {
        ScoreState.NeedsStrap -> Palette.statusWarning
        else -> Palette.textTertiary
    }
    NoopCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "${state.title}. ${state.detail}" },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(Metrics.iconSmall),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.title, style = NoopType.headline, color = Palette.textPrimary)
                Text(state.detail, style = NoopType.subhead, color = Palette.textSecondary)
            }
        }
    }
}

// ── COMPONENT 3, recording status ───────────────────────────────────────────────────────────────────

/**
 * The honest live-recording state of the strap, for the Today/Live chip. Derived from the BLE connection
 * + last-sync timestamp so people always know it's working, or know it isn't and why. Mirrors Swift
 * `RecordingState` 1:1 (same three cases, same [title] / [detail] copy, same [tone]).
 */
sealed class RecordingState {
    /** The strap is connected and saving data live. */
    object Recording : RecordingState()

    /** Not live now, but synced [minutesAgo] minutes ago, an honest "how fresh is it". */
    data class LastSynced(val minutesAgo: Long) : RecordingState()

    /** No connection and nothing recent to fall back on. */
    object NotRecording : RecordingState()

    /** #580, a connected WHOOP 5/MG streaming live HR fine, but its firmware hands over no history
     *  offload yet. NOT the WHOOP-4 "not recording" failure: the link is live, history sync is just
     *  experimental on 5.0. Surfaced from `LiveState.historySyncExperimental`, overriding the resolver. */
    object HistoryExperimental : RecordingState()

    /** The chip's status word. VERBATIM, mirror Swift exactly. */
    val title: String
        get() = when (this) {
            Recording -> "Recording"
            is LastSynced -> "Last synced ${minutesAgo}m ago"
            NotRecording -> "Not recording"
            HistoryExperimental -> "Connected"
        }

    /** The chip's one-line detail. VERBATIM, mirror Swift exactly. */
    val detail: String
        get() = when (this) {
            Recording -> "Your strap is connected and saving data."
            is LastSynced -> "Reconnect to pull the latest."
            NotRecording -> "Strap not connected. Tap to connect."
            HistoryExperimental -> "History sync is experimental on 5.0."
        }

    /** Chip hue: live recording reads positive (gold/green dot), a stale-but-recent sync reads neutral,
     *  not-recording reads critical so a dropped link is obvious; the 5.0 experimental-history state is
     *  connected so it reads accent, not critical. */
    val tone: StrandTone
        get() = when (this) {
            Recording -> StrandTone.Positive
            is LastSynced -> StrandTone.Neutral
            NotRecording -> StrandTone.Critical
            HistoryExperimental -> StrandTone.Accent
        }
}

/**
 * Resolve the honest [RecordingState] from the live BLE state + last-sync timestamp. Pure + unit-tested.
 *   - connected AND a live HR is streaming  → [RecordingState.Recording] (it really is saving data);
 *   - else a [lastSyncAtSec] this session    → [RecordingState.LastSynced] (minutes since, clamped >= 0,
 *                                              ROUNDED UP so a 30s-old sync reads "1m ago" not "0m ago");
 *   - else                                   → [RecordingState.NotRecording].
 * "Recording" requires BOTH a connection AND a live heart-rate sample so a bonded-but-silent link can't
 * claim it's saving data. [nowSec] is unix seconds (injected so the math is testable). Mirrors Swift
 * `recordingStateFor`.
 */
internal fun recordingStateFor(
    connected: Boolean,
    liveHeartRate: Int?,
    lastSyncAtSec: Long?,
    nowSec: Long,
): RecordingState = when {
    connected && liveHeartRate != null -> RecordingState.Recording
    lastSyncAtSec != null -> {
        // Clamp at 0 (a sync stamped slightly in the future from strap-clock skew can't read negative)
        // then ROUND UP so a 30-second-old sync reads "1m ago", never "0m ago", matches the Swift
        // `RecordingState.resolve` ceil. ceil(secs / 60) == (secs + 59) / 60 for non-negative longs.
        val secs = (nowSec - lastSyncAtSec).coerceAtLeast(0L)
        RecordingState.LastSynced((secs + 59L) / 60L)
    }
    else -> RecordingState.NotRecording
}

// ── COMPONENT 4, provenance badge ───────────────────────────────────────────────────────────────────

/**
 * The Today provenance label for the day's REAL merge winner, extends the existing By-Day badge
 * vocabulary consistently. NOOP-computed reads "On-device" (the spec's wording for the By-Day badge,
 * versus the FusedRecord screen's terser "NOOP"), an imported strap day reads "Whoop", and a phone
 * aggregate reads "Apple Health" / "Health Connect". Null when no source owns the day (nothing to
 * stamp). Mirrors the Swift `provenanceBadgeLabel`. */
internal fun dayOwnerSource(deviceId: String?): com.noop.analytics.FusionSource? = when {
    deviceId == null -> null
    deviceId.endsWith("-noop") -> com.noop.analytics.FusionSource.NOOP_COMPUTED
    deviceId == WhoopRepository.APPLE_HEALTH_SOURCE -> com.noop.analytics.FusionSource.APPLE_HEALTH
    deviceId == WhoopRepository.HEALTH_CONNECT_SOURCE -> com.noop.analytics.FusionSource.HEALTH_CONNECT
    // The merged Today rows carry the imported strap deviceId ("my-whoop") on days a real WHOOP import
    // covers, and the "-noop" sibling otherwise; any other strap deviceId is still an imported strap day.
    else -> com.noop.analytics.FusionSource.WHOOP_IMPORT
}

internal fun provenanceBadgeLabel(owner: com.noop.analytics.FusionSource?): String? = when (owner) {
    com.noop.analytics.FusionSource.NOOP_COMPUTED -> "On-device"
    com.noop.analytics.FusionSource.WHOOP_IMPORT -> "Whoop"
    com.noop.analytics.FusionSource.APPLE_HEALTH -> "Apple Health"
    com.noop.analytics.FusionSource.HEALTH_CONNECT -> "Health Connect"
    com.noop.analytics.FusionSource.XIAOMI_BAND -> "Mi Band"
    com.noop.analytics.FusionSource.NUTRITION_CSV -> "Nutrition"
    com.noop.analytics.FusionSource.LOCAL_CACHE -> "Cached"
    null -> null
}

/**
 * PURE mapper (unit-tested), a RAW resolver source id (as returned by [WhoopRepository.resolvedSeries]'s
 * winning point, e.g. "my-whoop", "my-whoop-noop", "apple-health") onto the spec's provenance labels,
 * given the strap's real [deviceId]. ANY NOOP-computed strap sibling (a "-noop"-suffixed id, not just the
 * active strap's) reads "On-device" — matching by suffix rather than "$deviceId-noop" so a computed row
 * from a non-active strap can't fall through to [com.noop.analytics.FusionSource.NOOP_COMPUTED]'s raw
 * "NOOP" displayName (the internal id must never surface); the imported strap source ([deviceId], normally
 * "my-whoop") reads "Whoop"; the Apple-Health source reads "Apple Health". Any other real source (Health
 * Connect, Mi Band, nutrition) keeps its [com.noop.analytics.FusionSource.displayName], still the genuine
 * merge winner, never a blanket claim. Mirrors the Swift `provenanceDisplayLabel` EXACTLY. This is the
 * PER-METRIC mapper the Today rings use; the day-level [dayOwnerSource]/[provenanceBadgeLabel] pair stays
 * for the legacy By-Day vocabulary.
 */
internal fun provenanceDisplayLabel(
    rawSource: String,
    deviceId: String = WhoopRepository.WHOOP_SOURCE,
): String {
    if (rawSource.endsWith("-noop")) return "On-device"
    if (rawSource == deviceId || rawSource == WhoopRepository.WHOOP_SOURCE) return "Whoop"
    if (rawSource == WhoopRepository.APPLE_HEALTH_SOURCE) return "Apple Health"
    // Fall back to the FusionSource display name for any other known source; else the raw id verbatim.
    return com.noop.analytics.FusionSource.entries.firstOrNull { it.id == rawSource }?.displayName ?: rawSource
}

/** Today uses the audience-facing sensor name for Apple Health scores, matching the Swift Today lane. */
internal fun todayProvenanceChipLabel(
    rawSource: String,
    deviceId: String = WhoopRepository.WHOOP_SOURCE,
): String = if (rawSource == WhoopRepository.APPLE_HEALTH_SOURCE) {
    "Apple Watch"
} else {
    provenanceDisplayLabel(rawSource, deviceId)
}

/**
 * One compact source label for the liquid score hero. Raw winners arrive in Charge / Effort / Rest order;
 * identical display names collapse and mixed winners are capped at two so the badge stays readable.
 * Mirrors LiquidTodayView.heroSourceLabel value-for-value.
 */
internal fun heroSourceLabel(
    rawSources: List<String>,
    deviceId: String = WhoopRepository.WHOOP_SOURCE,
): String? {
    val labels = LinkedHashSet<String>()
    for (rawSource in rawSources) {
        labels.add(todayProvenanceChipLabel(rawSource, deviceId))
        if (labels.size == 2) break
    }
    return labels.takeIf { it.isNotEmpty() }?.joinToString(" + ")
}

/**
 * Source label for the three visible hero scores. Today can show a carried Charge from the previous
 * scored night while today's recovery is still absent (#543); in that state the selected-day
 * "recovery" provenance is also absent, so use the carried night's resolved recovery source instead of
 * letting the card badge omit or misrepresent the visible Charge (#390).
 */
internal fun scoreHeroSourceLabel(
    provenanceByMetric: Map<String, String>,
    carriedRecoverySource: String?,
    usesCarriedRecovery: Boolean,
    deviceId: String = WhoopRepository.WHOOP_SOURCE,
): String? {
    val recoverySource = provenanceByMetric["recovery"]
        ?: if (usesCarriedRecovery) carriedRecoverySource else null
    return heroSourceLabel(
        rawSources = listOfNotNull(
            recoverySource,
            provenanceByMetric["strain"],
            provenanceByMetric["sleep_performance"],
        ),
        deviceId = deviceId,
    )
}

/** The tint for a per-metric provenance badge, keyed on the resolved LABEL, gold for Whoop, cyan for
 *  Apple Health, the positive status hue for on-device (and anything else). Matches the Data Sources
 *  footer + the Swift `provenanceTint` so the same source reads the same colour on Today. */
internal fun provenanceLabelTint(label: String): Color = when (label) {
    "Whoop" -> Palette.accent
    "Apple Health" -> Palette.metricCyan
    "Health Connect" -> Palette.metricPurple
    else -> Palette.statusPositive
}

// NOTE: the blanket day-level `TodayProvenanceBadge` was removed. Today provenance now resolves the real
// per-metric field-by-field winners, deduplicates them, and renders one card-level SourceBadge aligned to
// the Rest vessel (see heroSourceLabel + ScoreHeroRow). The pure `dayOwnerSource` /
// `provenanceBadgeLabel` By-Day mappers are kept (Intelligence/Trends + tests still use that vocabulary).

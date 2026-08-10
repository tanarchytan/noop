package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.CircadianEngine
import com.noop.analytics.CyclePhaseEngine
import com.noop.analytics.IllnessDistance
import com.noop.analytics.IllnessSignalEngine
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Skin-temperature suite cards (v5 pillar) — Compose twin
//
// Four self-contained,
// reusable cards driven entirely by a pure com.noop.analytics engine RESULT passed in
// (Wave 3 runs the engines in the analytics pass and mounts these in the Health hub):
//
//   • CycleAwarenessCard   — CyclePhaseEngine.Result. OPT-IN (the host gates on a default-OFF
//                            preference). Awareness only — NOT contraception, NOT a fertility/
//                            ovulation predictor, NOT a diagnosis. Phase + cycle-day RANGE +
//                            probabilistic next-period WINDOW (never a hard date).
//   • BodyClockCard        — CircadianEngine.PhaseEstimate. LIGHT + SLEEP TIMING only, never a
//                            supplement/drug.
//   • RhythmAgeCard        — whoop-rs RhythmAgeInfo off the SAME cosinor fit as the body clock,
//                            charted against real age and Fitness Age. A relative wellness
//                            estimate to watch as a trend — never a diagnosis, never a lifespan.
//   • HeadsUpCard          — IllnessSignalEngine.Result. Confounder-suppressed illness
//                            "heads-up". On-device estimate — not a diagnosis.
//
// DESIGN-SYSTEM ONLY: NoopCard + Palette/DomainTheme tokens, NoopType, Metrics, StatePill.
// No raw hex, no ad-hoc cards. Privacy-forward copy (this data is incapable of leaving the
// device, said on every sensitive surface). Cards take VALUES not stores, so a slip stays
// local and the engines remain testable + I/O-free. Wave-3 wiring noted at the foot.

// MARK: - Shared chrome

/** The standing privacy promise repeated on every sensitive skin-temp surface. */
@Composable
private fun PrivacyNote(text: String = stringResource(R.string.skintemp_privacy)) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
        modifier = Modifier.semantics { contentDescription = text },
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(11.dp),
        )
        Text(text, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

/** A quiet tinted chip (fired signal / confounder / overline-adjacent tag). */
@Composable
private fun WhyChip(label: String, tint: Color) {
    Text(
        label,
        style = NoopType.captionNumber,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// MARK: - 1. Cycle awareness card (OPT-IN)

/**
 * Cycle phase awareness from the nightly skin-temperature shift. OPT-IN by design — the host
 * renders this only after the user enables cycle awareness (default OFF). Awareness only;
 * never contraception / fertility / diagnosis. Calm Rest indigo world — no valence, no red.
 */
@Composable
fun CycleAwarenessCard(
    result: CyclePhaseEngine.Result,
    onLogPeriod: (() -> Unit)? = null,
    onOpenDetail: (() -> Unit)? = null,
    // symmetric off-control. When supplied, the card shows a "Turn off" action so the user can
    // disable cycle awareness from the SAME place they enabled it (Health), not only from Automations.
    onTurnOff: (() -> Unit)? = null,
) {
    val hue = Palette.restColor
    NoopCard(tint = hue) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // Header: overline + confidence pill.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(stringResource(R.string.skintemp_cycle_awareness))
                    Text(
                        stringResource(R.string.skintemp_cycle_from_temp),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                StatePill(
                    stringResource(cycleConfidenceLabel(result.confidence)),
                    tone = cycleConfidenceTone(result.confidence),
                )
            }

            // Headline phase + cycle-day RANGE.
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Text(
                    stringResource(cyclePhaseTitle(result.phase)),
                    style = NoopType.title2,
                    color = Palette.textPrimary,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                cycleDayText(result)?.let {
                    Text(it, style = NoopType.bodyNumber, color = Palette.textSecondary)
                }
            }

            Text(result.note, style = NoopType.subhead, color = Palette.textSecondary)

            // Probabilistic next-period WINDOW, never a single date.
            result.nextPeriodWindow?.let { w ->
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = hue, modifier = Modifier.size(16.dp))
                    Text(
                        stringResource(
                            R.string.skintemp_cycle_next_period,
                            prettyDay(w.earliestDay), prettyDay(w.latestDay),
                        ),
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }
            }

            // Actions.
            if (onLogPeriod != null || onOpenDetail != null || onTurnOff != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    if (onLogPeriod != null) {
                        OutlinedButton(onClick = onLogPeriod) {
                            Text(stringResource(R.string.skintemp_cycle_log_period))
                        }
                    }
                    if (onOpenDetail != null) {
                        OutlinedButton(
                            onClick = onOpenDetail,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
                        ) { Text(stringResource(R.string.skintemp_view_detail)) }
                    }
                    // symmetric off-control (turn cycle awareness off where it was turned on).
                    if (onTurnOff != null) {
                        OutlinedButton(onClick = onTurnOff) {
                            Text(stringResource(R.string.skintemp_turn_off))
                        }
                    }
                }
            }

            HorizontalDivider(color = Palette.hairline)

            // Standing awareness-only legal line (verbatim from the engine) + privacy promise.
            Text(CyclePhaseEngine.awarenessLine, style = NoopType.footnote, color = Palette.textTertiary)
            PrivacyNote()
        }
    }
}

/**
 * Shown in place of the card when the user has NOT opted in. A single calm opt-in card restating
 * the privacy promise at the point of consent (manual-first; default OFF).
 */
@Composable
fun CycleAwarenessOptInCard(onEnable: () -> Unit) {
    NoopCard(tint = Palette.restColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Icon(Icons.Filled.Thermostat, contentDescription = null, tint = Palette.restColor, modifier = Modifier.size(Metrics.iconSmall))
                Text(
                    stringResource(R.string.skintemp_cycle_awareness),
                    style = NoopType.headline, color = Palette.textPrimary,
                )
            }
            Text(
                stringResource(R.string.skintemp_cycle_optin_body),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
            PrivacyNote()
            OutlinedButton(onClick = onEnable) {
                Text(stringResource(R.string.skintemp_cycle_optin_enable))
            }
        }
    }
}

// MARK: - 2. Body Clock card

/**
 * Estimated body-clock phase. LIGHT + SLEEP TIMING only — never a supplement. Behavioural
 * awareness, approximate.
 */
@Composable
fun BodyClockCard(estimate: CircadianEngine.PhaseEstimate) {
    val hue = Palette.restColor
    NoopCard(tint = hue) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(stringResource(R.string.skintemp_body_clock))
                    Text(
                        stringResource(R.string.skintemp_body_clock_sub),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
                StatePill(
                    stringResource(bodyClockConfidenceLabel(estimate.confidence)),
                    tone = bodyClockConfidenceTone(estimate.confidence),
                )
            }

            Text(bodyClockOffsetTitle(estimate), style = NoopType.title2, color = Palette.textPrimary)

            Text(estimate.note, style = NoopType.subhead, color = Palette.textSecondary)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                Icon(Icons.Filled.NightsStay, contentDescription = null, tint = hue, modifier = Modifier.size(14.dp))
                Text(
                    stringResource(R.string.skintemp_body_clock_low, clockString(estimate.tempMinHour)),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

// MARK: - 3. Rhythm Age card

/**
 * Circadian Rhythm Age: the body-clock age whoop-rs reads from the same rest-activity cosinor
 * [BodyClockCard] renders, charted against real age and Fitness Age. A relative wellness estimate,
 * approximate — never a diagnosis, never a life expectancy.
 *
 * [wornDays] and [confidence] come from that one shared fit, so the card can only ever say what the
 * fit supports: below [CircadianEngine.MIN_WORN_DAYS] it counts up instead of showing a number, and
 * a rhythm too flat to read says so rather than naming a year.
 */
@Composable
fun RhythmAgeCard(
    info: uniffi.whoop_ffi.RhythmAgeInfo?,
    wornDays: Int,
    chronologicalAge: Double,
    confidence: CircadianEngine.PhaseConfidence?,
    fitnessAge: Double? = null,
) {
    val hue = Palette.restColor
    NoopCard(tint = hue) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(stringResource(R.string.skintemp_rhythm_age))
                    Text(
                        stringResource(R.string.skintemp_rhythm_age_sub),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
                StatePill(
                    stringResource(rhythmAgeStateLabel(info, confidence)),
                    tone = rhythmAgeStateTone(info, confidence),
                )
            }

            Text(rhythmAgeTitle(info, wornDays), style = NoopType.title2, color = Palette.textPrimary)
            Text(rhythmAgeNote(info, wornDays), style = NoopType.subhead, color = Palette.textSecondary)

            // The chart the number is read against: your real age always, your Fitness Age when the
            // weekly pass has one. Both are years, so they sit on one scale.
            if (info != null) {
                Column {
                    NoopStatRow(
                        icon = Icons.Filled.CalendarMonth,
                        label = stringResource(R.string.skintemp_your_age),
                        value = yearsString(chronologicalAge),
                        unit = "yrs",
                        iconTint = hue,
                    )
                    if (fitnessAge != null) {
                        RowDivider()
                        NoopStatRow(
                            icon = Icons.AutoMirrored.Filled.DirectionsRun,
                            label = stringResource(R.string.skintemp_fitness_age),
                            value = yearsString(fitnessAge),
                            unit = "yrs",
                            iconTint = hue,
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.skintemp_rhythm_disclaimer),
                style = NoopType.caption,
                color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - 4. Heads-Up card (illness early-warning, confounder-suppressed)

/**
 * The confounder-suppressed illness "heads-up". Renders the engine's already-decided level +
 * copy; the host only mounts it when the engine returns a non-quiet level. On-device estimate
 * — not a diagnosis. Mirrors the existing amber alert treatment.
 */
@Composable
fun HeadsUpCard(
    result: IllnessSignalEngine.Result,
    // Optional parallel Mahalanobis distance (IllnessDistance), computed on the SAME z-vector. It does
    // NOT gate this card (the engine's level already did); when the level is raised and a distance is
    // present we append a subtle "Confidence" line so the user can gauge how strong the signal is.
    distance: IllnessDistance.Result? = null,
) {
    val hue = headsUpHue(result.level)
    NoopCard(padding = 14.dp, tint = hue) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(hue.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(headsUpGlyph(result.level), contentDescription = null, tint = hue, modifier = Modifier.size(15.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                    Text(
                        stringResource(headsUpTitle(result.level)),
                        style = NoopType.headline, color = Palette.textPrimary,
                    )
                    Text(result.copy, style = NoopType.subhead, color = Palette.textSecondary)
                }
            }

            // The visible "why": which signals fired.
            if (result.firedSignals.isNotEmpty()) {
                WhyRow(stringResource(R.string.skintemp_signals_up), result.firedSignals, hue)
            }
            // ...and what was ruled out (the differentiating part vs a black-box warning).
            if (result.suppressedBy.isNotEmpty()) {
                WhyRow(stringResource(R.string.skintemp_explained_by), result.suppressedBy, Palette.textTertiary)
            }
            // Optional confidence read from the parallel Mahalanobis distance, only when the level is
            // raised. Subtle by design: it augments, never gates (the engine already decided to raise).
            headsUpConfidenceLine(result.level, distance)?.let { line ->
                Text(line, style = NoopType.caption, color = Palette.textTertiary)
            }
        }
    }
}

/**
 * A subtle confidence read from the parallel Mahalanobis distance, surfaced ONLY on the RAISED state (and
 * when a distance is present). null otherwise. The already-unwell state is driven purely by the user's own
 * log and can have a near-zero distance (0-1 present features), giving a misleading "Confidence: slight
 * (distance 0.0)", so it's excluded. The raised path always has >= 2 present features, so its distance is
 * meaningful. Augment-only, never gates.
 */
@Composable
private fun headsUpConfidenceLine(
    level: IllnessSignalEngine.Level,
    distance: IllnessDistance.Result?,
): String? {
    if (level != IllnessSignalEngine.Level.RAISED) return null
    val d = distance ?: return null
    return stringResource(
        R.string.skintemp_confidence,
        stringResource(illnessConfidenceBand(d.distance)),
        illnessConfidenceFormatted(d.distance),
    )
}

/**
 * Maps the parallel Mahalanobis distance to a plain confidence word. Presentation-only: NEVER decides
 * whether the Heads-Up card shows (the engine's level already did). Bands: >= 3.5 strong, >= 2.5
 * moderate, else slight.
 */
@StringRes
private fun illnessConfidenceBand(distance: Double): Int = when {
    distance >= 3.5 -> R.string.skintemp_conf_strong
    distance >= 2.5 -> R.string.skintemp_conf_moderate
    else -> R.string.skintemp_conf_slight
}

/** One-decimal display value for the distance, locale-independent. */
private fun illnessConfidenceFormatted(distance: Double): String =
    String.format(java.util.Locale.US, "%.1f", distance)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhyRow(label: String, values: List<String>, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        modifier = Modifier.semantics { contentDescription = "$label: ${values.joinToString(", ")}" },
    ) {
        Overline(label)
        // Chips wrap to the next line rather than overflowing the card on a long confounder list.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
            verticalArrangement = Arrangement.spacedBy(Metrics.space6),
        ) {
            values.forEach { WhyChip(it, tint) }
        }
    }
}

// MARK: - Derived copy / presentation

@StringRes
private fun cyclePhaseTitle(phase: CyclePhaseEngine.Phase): Int = when (phase) {
    CyclePhaseEngine.Phase.FOLLICULAR -> R.string.skintemp_phase_follicular
    CyclePhaseEngine.Phase.PERI_OVULATORY -> R.string.skintemp_phase_periovulatory
    CyclePhaseEngine.Phase.LUTEAL -> R.string.skintemp_phase_luteal
    CyclePhaseEngine.Phase.UNKNOWN -> R.string.skintemp_phase_unknown
    CyclePhaseEngine.Phase.LEARNING -> R.string.skintemp_phase_learning
}

/** "~day 18–22" — always a RANGE, never a single point. */
@Composable
private fun cycleDayText(r: CyclePhaseEngine.Result): String? {
    val lo = r.cycleDayLow ?: return null
    val hi = r.cycleDayHigh ?: return null
    return if (lo == hi) stringResource(R.string.skintemp_cycle_day, lo)
    else stringResource(R.string.skintemp_cycle_day_range, lo, hi)
}

@StringRes
private fun cycleConfidenceLabel(c: CyclePhaseEngine.Confidence): Int = when (c) {
    CyclePhaseEngine.Confidence.LEARNING -> R.string.skintemp_conf_learning
    CyclePhaseEngine.Confidence.BUILDING -> R.string.skintemp_conf_building
    CyclePhaseEngine.Confidence.SOLID -> R.string.skintemp_conf_solid
}

private fun cycleConfidenceTone(c: CyclePhaseEngine.Confidence): StrandTone = when (c) {
    CyclePhaseEngine.Confidence.LEARNING -> StrandTone.Neutral
    CyclePhaseEngine.Confidence.BUILDING -> StrandTone.Accent
    CyclePhaseEngine.Confidence.SOLID -> StrandTone.Accent
}

/** "About 25 min later than your schedule" — a plain, skimmable headline. */
@Composable
private fun bodyClockOffsetTitle(e: CircadianEngine.PhaseEstimate): String {
    if (e.confidence == CircadianEngine.PhaseConfidence.UNREADABLE) {
        return stringResource(R.string.skintemp_hard_to_read)
    }
    val mins = abs(e.offsetVsScheduleMinutes).roundToInt()
    if (mins <= 20) return stringResource(R.string.skintemp_body_clock_in_sync)
    val dir = stringResource(
        if (e.offsetVsScheduleMinutes > 0) R.string.skintemp_dir_later else R.string.skintemp_dir_earlier,
    )
    return stringResource(R.string.skintemp_body_clock_offset, mins, dir)
}

@StringRes
private fun bodyClockConfidenceLabel(c: CircadianEngine.PhaseConfidence): Int = when (c) {
    CircadianEngine.PhaseConfidence.UNREADABLE -> R.string.skintemp_conf_calibrating
    CircadianEngine.PhaseConfidence.WIDE -> R.string.skintemp_conf_building
    CircadianEngine.PhaseConfidence.SOLID -> R.string.skintemp_conf_solid
}

private fun bodyClockConfidenceTone(c: CircadianEngine.PhaseConfidence): StrandTone = when (c) {
    CircadianEngine.PhaseConfidence.UNREADABLE -> StrandTone.Neutral
    CircadianEngine.PhaseConfidence.WIDE -> StrandTone.Accent
    CircadianEngine.PhaseConfidence.SOLID -> StrandTone.Accent
}

/** The headline: the age itself once the fit supports one, otherwise the honest reason it doesn't. */
@Composable
private fun rhythmAgeTitle(info: uniffi.whoop_ffi.RhythmAgeInfo?, wornDays: Int): String = when {
    info != null -> stringResource(R.string.skintemp_rhythm_age_years, yearsString(info.cosinorAgeYears))
    wornDays < CircadianEngine.MIN_WORN_DAYS -> stringResource(R.string.skintemp_rhythm_still_building)
    else -> stringResource(R.string.skintemp_hard_to_read)
}

/**
 * The sentence under the headline. Below the worn-day floor it repeats the promise the changelog
 * makes and shows how far along the wear is; above it, how the estimate sits against real age.
 */
@Composable
private fun rhythmAgeNote(info: uniffi.whoop_ffi.RhythmAgeInfo?, wornDays: Int): String {
    if (info == null) {
        if (wornDays >= CircadianEngine.MIN_WORN_DAYS) {
            return stringResource(R.string.skintemp_rhythm_too_flat)
        }
        val days = wornDays.coerceAtLeast(0)
        return stringResource(R.string.skintemp_rhythm_needs_wear, days, CircadianEngine.MIN_WORN_DAYS)
    }
    val years = abs(info.advanceYears).roundToInt()
    if (years < 1) return stringResource(R.string.skintemp_rhythm_level)
    val plural = stringResource(if (years == 1) R.string.skintemp_year else R.string.skintemp_years)
    // POSITIVE advance = older than chronological, matching the whoop-rs convention.
    val dir = stringResource(if (info.advanceYears > 0) R.string.skintemp_older else R.string.skintemp_younger)
    return stringResource(R.string.skintemp_rhythm_vs_age, years, plural, dir)
}

/** The pill reuses the SHARED fit's confidence, so it can never claim more than the body clock does. */
@StringRes
private fun rhythmAgeStateLabel(
    info: uniffi.whoop_ffi.RhythmAgeInfo?,
    confidence: CircadianEngine.PhaseConfidence?,
): Int = if (info == null || confidence == null) {
    R.string.skintemp_conf_calibrating
} else {
    bodyClockConfidenceLabel(confidence)
}

private fun rhythmAgeStateTone(
    info: uniffi.whoop_ffi.RhythmAgeInfo?,
    confidence: CircadianEngine.PhaseConfidence?,
): StrandTone = if (info == null || confidence == null) {
    StrandTone.Neutral
} else {
    bodyClockConfidenceTone(confidence)
}

/** Whole years, locale-free — the unit every age on this card is shown in. */
private fun yearsString(years: Double): String = years.roundToInt().toString()

/** Card hue follows the level: raised / already-unwell = amber warning (matches the shipped
 *  banner); suppressed / mild = a calmer neutral so it never scares. */
private fun headsUpHue(level: IllnessSignalEngine.Level): Color = when (level) {
    IllnessSignalEngine.Level.RAISED, IllnessSignalEngine.Level.ALREADY_UNWELL -> Palette.statusWarning
    else -> Palette.restColor
}

private fun headsUpGlyph(level: IllnessSignalEngine.Level): ImageVector = when (level) {
    IllnessSignalEngine.Level.RAISED -> Icons.Filled.Warning
    IllnessSignalEngine.Level.ALREADY_UNWELL -> Icons.Filled.NightsStay
    IllnessSignalEngine.Level.SUPPRESSED -> Icons.Filled.Info
    IllnessSignalEngine.Level.MILD -> Icons.Filled.MonitorHeart
    IllnessSignalEngine.Level.QUIET -> Icons.Filled.CheckCircle
}

@StringRes
private fun headsUpTitle(level: IllnessSignalEngine.Level): Int = when (level) {
    IllnessSignalEngine.Level.RAISED -> R.string.skintemp_headsup_raised
    IllnessSignalEngine.Level.ALREADY_UNWELL -> R.string.skintemp_headsup_unwell
    IllnessSignalEngine.Level.SUPPRESSED -> R.string.skintemp_headsup_suppressed
    IllnessSignalEngine.Level.MILD -> R.string.skintemp_headsup_mild
    IllnessSignalEngine.Level.QUIET -> R.string.skintemp_headsup_quiet
}

// MARK: - Formatting helpers (locale-free, matching the engine's own helpers)

/** Render a fractional clock hour as "HH:MM". */
private fun clockString(hour: Double): String {
    var h = hour % 24.0
    if (h < 0) h += 24.0
    var hh = h.toInt()
    var mm = ((h - hh) * 60.0).roundToInt()
    if (mm == 60) { mm = 0; hh = (hh + 1) % 24 }
    return "%02d:%02d".format(hh, mm)
}

/** "12 Jun" from a "yyyy-MM-dd" key (display only; the engine math stays UTC). */
private fun prettyDay(key: String): String {
    val parts = key.split("-")
    if (parts.size != 3) return key
    val m = parts[1].toIntOrNull() ?: return key
    val d = parts[2].toIntOrNull() ?: return key
    val month = monthAbbreviation(m) ?: return key
    return "$d $month"
}

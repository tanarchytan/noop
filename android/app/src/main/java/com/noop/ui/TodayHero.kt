package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.analytics.Baselines
import com.noop.analytics.ReadinessEngine
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Score hero row, three Charge / Effort / Rest score vessels
//
// The liquid Today hero: three equal daily-score vessels in Charge / Effort / Rest order, each tappable.
// The RING opens the detail (Charge breakdown / Workouts / Sleep); labels are plain text underneath.

@Composable
internal fun ScoreHeroRow(
    day: DailyMetric?,
    restScore: Double?,
    recoveryCalibration: Int?,
    lastScoredCharge: LastCharge? = null,
    effortScale: EffortScale,
    liveTodayStrain: Double? = null,
    // One card-level provenance label derived from the three REAL per-metric merge winners upstream.
    heroSourceLabel: String? = null,
    // Charge ring tap opens the breakdown sheet.
    onChargeTap: (() -> Unit)? = null,
    // Effort ring tap opens Workouts.
    onEffortTap: (() -> Unit)? = null,
    // Rest ring tap opens Sleep.
    onRestTap: (() -> Unit)? = null,
) {
    val recovery = day?.recovery
    // Prefer the live in-progress Effort for today, but never BELOW the day's already-earned strain
    // (#489/#506: a live under-read replaced today's real Effort with 0). The effective value drives the
    // gauge number AND the has-data / "No Data" branch, so the ring only reads "No Data" when neither
    // exists. Mirrors the iOS live-Effort gauge. (#402)
    val strain = run {
        val live = liveTodayStrain; val stored = day?.strain
        if (live != null && stored != null) maxOf(live, stored) else (live ?: stored)
    }
    // Effort honours the 0–100 / WHOOP-0–21 toggle (#313). The stored strain is on NOOP's 0–100 Effort
    // axis; render it on the user's selected scale so the arc and centre number match the app's Effort.
    val effortOutOf = if (effortScale == EffortScale.WHOOP) 21.0 else 100.0
    val effortVal = strain?.let { UnitFormatter.effortValue(it, effortScale) } ?: 0.0

    // The hero column owns the vertical padding so the source label (below) sits INSIDE the card's
    // breathing room, centred under the circles, in normal flow — the pre-#409 placement, no longer an
    // overlay straddling the top border over the vessels.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Metrics.space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        // The hero rings float DIRECTLY on the plain themed surface canvas (the scaffold paints
        // Palette.surfaceBase; topBackground is null), not on any per-hero atmosphere, day-cycle scene, or
        // the old scenic indigo gradient.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Metrics.gap),
        ) {
            // iOS parity (TodayView.scoreHeroRow): three EQUAL rings in CHARGE · EFFORT · REST order, no
            // enlarged centre, filling the width as one balanced row. Ring stroke 0.10 (WHOOP weight).
            val ringGap = 14.dp
            val ring = ((maxWidth - ringGap * 2) / 3.1f).coerceIn(90.dp, 112.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ringGap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top,
            ) {
                // CHARGE ring — tap opens the breakdown sheet. Ring is clickable, label is plain text.
                HeroRingColumn(
                    domain = DomainTheme.Charge,
                    onRingTap = onChargeTap,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // #802: when today has no Charge yet but a prior night's value is carried, draw a
                        // DIMMED (0.8 opacity) REAL ring filled to the carried value, matching the Rest
                        // ring, rather than a bare number in an empty ring (which read as broken). Same
                        // diameter so the self-sizing hero row is untouched; the dim + the carried "Last
                        // night · <date>" caption mark it as carried, not today's fresh score. Mirrors iOS.
                        val carried = if (recovery == null && recoveryCalibration == null) lastScoredCharge else null
                        if (carried != null) {
                            HeroScoreVessel(
                                modifier = Modifier.alpha(0.8f),
                                fraction = carried.value / 100.0,
                                value = carried.value,
                                tint = Palette.recoveryColor(carried.value),
                                diameter = ring,
                                showsValue = true,
                            )
                        } else {
                            HeroScoreVessel(
                                fraction = (recovery ?: 0.0) / 100.0,
                                value = recovery ?: 0.0,
                                tint = Palette.recoveryColor(recovery ?: 0.0),
                                diameter = ring,
                                showsValue = recovery != null,
                            )
                            // Empty vessel + calibrating / no-data overlay (the carried case is above).
                            if (recovery == null) RingEmptyOverlay(recoveryCalibration)
                        }
                        // No in-vessel tap cue: the single tap affordance is the CHARGE-label chevron below
                        // the vessel (HeroRingColumn), matching iOS where the in-ring cue was removed.
                    }
                }
                // EFFORT ring — tap opens Workouts. Ring is clickable, label is plain text.
                HeroRingColumn(domain = DomainTheme.Effort, onRingTap = onEffortTap) {
                    Box(contentAlignment = Alignment.Center) {
                        HeroScoreVessel(
                            fraction = if (effortOutOf > 0) effortVal / effortOutOf else 0.0,
                            value = effortVal,
                            tint = Palette.effortTint((strain ?: 0.0) / 100.0),
                            diameter = ring,
                            showsValue = strain != null,
                            format = { if (effortScale == EffortScale.WHOOP) String.format(Locale.US, "%.1f", it) else it.toInt().toString() },
                        )
                        if (strain == null) RingNoData()
                    }
                }
                // REST ring — tap opens Sleep. Ring is clickable, label is plain text.
                HeroRingColumn(
                    domain = DomainTheme.Rest,
                    onRingTap = onRestTap,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        HeroScoreVessel(
                            fraction = (restScore ?: 0.0) / 100.0,
                            value = restScore ?: 0.0,
                            tint = Palette.recoveryColor(restScore ?: 0.0),
                            diameter = ring,
                            showsValue = restScore != null,
                        )
                        // #898: an aggregate-import user (a daily HRV/RHR import, no in-bed session) gets a
                        // Charge from WatchRecovery but NO sleep_performance, so Rest used to read a bare
                        // "No Data" next to a lit Charge , reading as broken. When a Charge IS present for the
                        // day but Rest is absent, say WHY honestly ("Needs a tracked night") instead. We do
                        // NOT fabricate a Rest number , an aggregate genuinely has no scored night. A day with
                        // no Charge either (truly empty) keeps the plain "No Data". Mirrors iOS restRing.
                        if (restScore == null) {
                            if (recovery != null) RingNeedsTrackedNight() else RingNoData()
                        }
                    }
                }
            }
        }
        // ONE consolidated source label BENEATH the circles, centred + in normal flow (the pre-#409
        // "aligned with the rings" placement). It still names the real per-metric merge winners
        // (recovery/strain/sleep + a carried Charge's source, #412) — just no longer floating over
        // the vessels. Hidden when no score has a resolved source.
        if (heroSourceLabel != null) {
            SourceBadge(
                text = heroSourceLabel,
                tint = Palette.onDarkSecondary,
                modifier = Modifier
                    .padding(horizontal = Metrics.gap)
                    .semantics { contentDescription = "Source: $heroSourceLabel" },
            )
        }
    }
}

/**
 * One hero ring column: the (optionally tappable) ring, with a plain UPPERCASE domain label beneath.
 * No ⓘ, no scoring guide — the ring is the tap target. Provenance belongs to the whole hero card and
 * is rendered once by [ScoreHeroRow], so this column only owns score content.
 */
@Composable
private fun HeroRingColumn(
    domain: DomainTheme,
    onRingTap: (() -> Unit)? = null,
    ring: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onRingTap != null) {
            val ringInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .liquidPress(ringInteraction)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = ringInteraction,
                        indication = null,
                        onClickLabel = domain.label,
                        onClick = onRingTap,
                    ),
            ) { ring() }
        } else {
            ring()
        }
        Text(
            domain.label.uppercase(),
            style = NoopType.overline,
            color = Palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * One hero score as a WHOOP-style ARC RING with the value counting up in the centre. [GlowRing]
 * (Components.kt) draws a full-circle track plus a crisp [tint] arc to [fraction] (0..1), sized to
 * [diameter], and rolls its own centre number up to [value] in the themed text colour. When [showsValue]
 * is false (no score yet) the ring draws just the track and the caller overlays the calibrating / No-Data
 * text. Values/bindings are unchanged from the liquid vessel this replaced: same fraction, value, tint.
 */
@Composable
private fun HeroScoreVessel(
    fraction: Double,
    value: Double,
    tint: Color,
    diameter: Dp,
    modifier: Modifier = Modifier,
    showsValue: Boolean = true,
    format: (Double) -> String = { it.roundToInt().toString() },
) {
    Box(modifier = modifier.size(diameter), contentAlignment = Alignment.Center) {
        GlowRing(
            fraction = fraction.coerceIn(0.0, 1.0).toFloat(),
            value = value,
            color = tint,
            diameter = diameter,
            lineWidth = diameter * 0.10f,
            showsLabel = showsValue,
            format = format,
        )
    }
}

/**
 * The plain-English Synthesis card, the Charge-tinted [InsightCard] read-out under the ring hero, with a
 * WHITE headline (the key iOS Design-Reset change, `statusColor: textPrimary`, not the recovery/charge
 * colour), carrying the SOLID / CALIBRATING data-confidence pill in its top-right. Mirrors
 * the iOS Synthesis InsightCard (which moved here when the big RecoveryRing hero that owned the pill went).
 */
@Composable
internal fun SynthesisHeroCard(
    day: DailyMetric?,
    recoveryCalibration: Int?,
    carriedDay: DailyMetric? = null,
    // S4: the day history (for the one-word readiness read), whether the Synthesis card is expanded, and the
    // taps to toggle it / open the Charge breakdown (where the full Readiness card lives). Defaults keep old
    // call sites compiling; the Today call site supplies them.
    days: List<DailyMetric> = emptyList(),
    synthesisExpanded: Boolean = true,
    onToggleSynthesis: () -> Unit = {},
    onOpenReadiness: () -> Unit = {},
) {
    // The row the synthesis reads from: today's own when it carries recovery, else the carried-over last
    // scored day (#543) so the card mirrors the carried Charge ring instead of blanking to "No Data". When
    // carrying, the detail line gets a "Last night · <date>" provenance so the prior read isn't passed off
    // as today's. today's own read wins the instant tonight is scored.
    val readDay = carriedDay ?: day
    val recovery = readDay?.recovery
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        // The SOLID/CALIBRATING data-confidence pill rides in its OWN header row ABOVE the
        // card, not as a top-end overlay over it (#527). The old overlay sat over the card's "SYNTHESIS"
        // overline + big status word and, on a narrow phone, collided with them, and squeezing the
        // status into the leftover width force-broke a single word ("Calibrating" → "Calibrati/ng").
        // A separate row CAN'T overlap, and the card keeps its FULL width so the status stays one line.
        // Mirrors the iOS Synthesis header-row layout (TodayView heroSection).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            // S4 (#205): the one-word readiness read kept on the hero now the full Readiness card folded
            // into the Charge-ring tap. Push / Maintain / Rest; hidden when there isn't enough history.
            // Tapping it opens the Charge breakdown, where the full Readiness card now lives.
            val readinessLevel = remember(days) {
                if (days.isEmpty()) ReadinessEngine.Level.INSUFFICIENT
                else ReadinessEngine.evaluate(days, today = logicalDayKeyNow()).level
            }
            readinessWord(readinessLevel)?.let { word ->
                ReadinessHeroPill(word = word, level = readinessLevel, onTap = onOpenReadiness)
            }
            // SOLID only when TODAY's own row carries a settled recovery, a carried prior-day read is
            // honestly still CALIBRATING for today, matching the iOS pill (keyed on displayDay.recovery).
            val todayRecovery = day?.recovery
            StatePill(
                title = if (todayRecovery != null) "SOLID" else "CALIBRATING",
                tone = if (todayRecovery != null) StrandTone.Accent else StrandTone.Neutral,
            )
        }
        // S4: the Synthesis card collapses to a one-liner that expands on tap. The headline (the status) is
        // the SAME in both states, only the detail body and chrome fold, never the read (#506).
        val status = if (recoveryCalibration != null) "Calibrating" else synthesisWord(recovery)
        val detail = if (recoveryCalibration != null) {
            // Comma (not the old em-dash) to match the Swift canonical synthesis copy VERBATIM
            // (TodayView "Learning your baseline, N of M nights.") and the no-em-dash standing rule.
            "Learning your baseline, $recoveryCalibration of ${Baselines.minNightsSeed} nights."
        } else if (carriedDay != null) {
            // Carried prior-day read, summarise that day + stamp it so it isn't passed off as today's.
            synthesisDetail(carriedDay) + " ${carriedCaption(carriedDay.day)}."
        } else {
            synthesisDetail(day)
        }
        if (synthesisExpanded) {
            val expandedInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .liquidPress(expandedInteraction)
                    .clickable(
                        interactionSource = expandedInteraction,
                        indication = null,
                        onClickLabel = "Collapse",
                        onClick = onToggleSynthesis,
                    ),
            ) {
                InsightCard(
                    modifier = Modifier.fillMaxWidth(),
                    category = "Synthesis",
                    status = status,
                    detail = detail,
                    // The SYNTHESIS headline reads WHITE (textPrimary), not the recovery/charge colour, the
                    // key iOS Design-Reset change (TodayView.synthesisSection passes statusColor textPrimary).
                    statusColor = Palette.textPrimary,
                    // FLAT card to match iOS (no navy-bevel gradient / border): identity comes from the white
                    // headline alone. tint = null routes to the neutral FLAT surfaceRaised + hairline path.
                    tint = null,
                )
            }
        } else {
            // Collapsed: a one-liner with the SYNTHESIS overline, the status headline and a down-chevron.
            val collapsedInteraction = remember { MutableInteractionSource() }
            NoopCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidPress(collapsedInteraction)
                    .clickable(
                        interactionSource = collapsedInteraction,
                        indication = null,
                        onClickLabel = "Expand for the full read",
                        onClick = onToggleSynthesis,
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("SYNTHESIS", style = NoopType.overline, color = Palette.textTertiary)
                        Text(
                            status,
                            style = NoopType.headline,
                            color = Palette.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * S4 (#205): the one-word readiness pill on the hero (Push / Maintain / Rest). A small tinted capsule
 * matching the score-pill chrome, coloured by the readiness level; tapping opens the Charge breakdown sheet
 * where the full Readiness card lives. Mirrors the iOS readinessHeroPill.
 */
@Composable
private fun ReadinessHeroPill(word: String, level: ReadinessEngine.Level, onTap: () -> Unit) {
    val tone = readinessColor(level)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tone.copy(alpha = 0.12f))
            .border(1.dp, tone.copy(alpha = 0.32f), RoundedCornerShape(50))
            .clickable(onClickLabel = "See your full readiness", onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = "Readiness: $word" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(word.uppercase(), style = NoopType.overline, color = tone)
    }
}

/** Honest overlay shown over the Charge ring when today's recovery is null: either the calibrating count
 *  or No data. The carried last-scored Charge case is NOT handled here anymore: it's intercepted earlier
 *  and drawn as a dimmed FILLED ring in the carried branch (matching iOS chargeRing), so this overlay only
 *  covers the calibrating and no-data cases. Mirrors iOS TodayView.ringEmptyOverlay. */
@Composable
private fun RingEmptyOverlay(
    calibratingNights: Int?,
) {
    if (calibratingNights != null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Calibrating", style = NoopType.headline, color = Palette.textTertiary, maxLines = 1)
            Text(
                "$calibratingNights of ${Baselines.minNightsSeed}",
                style = NoopType.footnote,
                color = Palette.textSecondary,
                maxLines = 1,
            )
        }
    } else {
        RingNoData()
    }
}

@Composable
private fun RingNoData() {
    Text(NO_DATA, style = NoopType.headline, color = Palette.textTertiary, maxLines = 1)
}

/** #898: the Rest ring's overlay when a Charge exists for the day but there's no scored sleep (the
 *  aggregate-import case , a daily HRV/RHR import carries no in-bed session). Says WHY Rest is blank
 *  instead of a bare "No Data", without fabricating a number. Mirrors iOS restRing's needs-a-night branch. */
@Composable
private fun RingNeedsTrackedNight() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Calibrating", style = NoopType.headline, color = Palette.textTertiary, maxLines = 1)
        Text(
            "needs a tracked night",
            style = NoopType.footnote,
            color = Palette.textSecondary,
            maxLines = 1,
        )
    }
}

// MARK: - Hero vitals metric rows, HRV / Resting HR / Respiratory, re-homed below the ring hero
//
// The WHOOP-style redesign (#23) dropped the big gold RecoveryRing hero that used to carry these; the
// three vitals now read directly below the three-ring hero + Synthesis card. [HeroMetricRows] is the
// README "Metric row" card; the SOLID/CALIBRATING pill + Synthesis insight moved into [SynthesisHeroCard].

/** The three hero vitals as README metric rows, HRV (teal) · Resting HR (rose) · Respiratory (blue).
 *  Reads PER-FIELD today-first with a recovery-INDEPENDENT vitals carry ([vitalsDay]) as the fallback
 *  (#543 follow-up), so a night whose recovery was nulled post-update still shows its OWN preserved HRV /
 *  RHR / respiratory rather than an older recovery-scored day's numbers (or "No Data"). This aligns the
 *  card to the Key-Metrics tiles, which already read per-field. Each row still falls through to "No Data"
 *  for a vital neither today nor the carry supplies. */
@Composable
internal fun HeroMetricRows(day: DailyMetric?, vitalsDay: DailyMetric? = null) {
    // Per-field, today-first: today's own value wins; the vitals carry only fills a field today lacks.
    val hrv = day?.avgHrv ?: vitalsDay?.avgHrv
    val rhr = day?.restingHr ?: vitalsDay?.restingHr
    val resp = day?.respRateBpm ?: vitalsDay?.respRateBpm
    // The caption reflects the row the shown vitals actually came from: if today supplied ANY of them the
    // values are today's own, so don't stamp them as a prior "Last night · <date>"; only when EVERY shown
    // vital is carried do we stamp the carry's date (relabelled "Latest sleep · <date>" when weeks-old).
    val carriedFromVitals = day?.avgHrv == null && day?.restingHr == null && day?.respRateBpm == null &&
        (hrv != null || rhr != null || resp != null) && vitalsDay != null
    // iOS `recoveryVitalsSection`: a frosted card with a "RECOVERY VITALS" header + a "last night · <date>"
    // on the right, then three `vitalRow`s (26dp mini LIQUID VESSEL + label + value). NoopCard supplies the
    // same neutral surfaceRaised + hairline as iOS's frosted card. Inner spacing 12, matching iOS.
    NoopCard(padding = Metrics.space16) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Overline("Recovery vitals", modifier = Modifier.weight(1f))
                // iOS `lastNightLine` — today's own "Last night · <date>" unless the shown vitals are a carry.
                Text(
                    if (carriedFromVitals) carriedCaption(vitalsDay!!.day) else heroVitalsLastNightLine(),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            }
            HeroVitalRow(
                label = "Heart-rate variability",
                value = hrv?.let { "${it.roundToInt()} ms" } ?: NO_DATA,
                tint = Palette.metricCyan,
                fraction = hrv?.let { (it / 120.0).coerceIn(0.0, 1.0) },
            )
            HeroVitalRow(
                label = "Resting heart rate",
                value = rhr?.let { "$it bpm" } ?: NO_DATA,
                tint = Palette.metricRose,
                fraction = rhr?.let { (it / 100.0).coerceIn(0.0, 1.0) },
            )
            HeroVitalRow(
                label = "Breaths per minute",
                value = resp?.let { String.format(Locale.US, "%.1f rpm", it) } ?: NO_DATA,
                tint = Palette.accent,
                fraction = resp?.let { (it / 24.0).coerceIn(0.0, 1.0) },
            )
        }
    }
}

/** iOS `lastNightLine` — "Last night · <date>" where <date> is yesterday in "d MMM" form. */
private fun heroVitalsLastNightLine(): String {
    val d = LocalDate.now().minusDays(1)
    return "Last night · ${d.format(DateTimeFormatter.ofPattern("d MMM", Locale.US))}"
}

/** One iOS `vitalRow`: a 26dp mini liquid VESSEL filled to [fraction] in [tint], the label (subhead,
 *  secondary), a spacer, and the value (number 15, primary). Replaces the old flat-Material-icon row. */
@Composable
private fun HeroVitalRow(label: String, value: String, tint: Color, fraction: Double?) {
    val hasValue = value != NO_DATA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        LiquidVessel(
            value = fraction,
            tint = tint,
            animated = false,
            modifier = Modifier.size(26.dp),
        )
        Text(label, style = NoopType.subhead, color = Palette.textSecondary, modifier = Modifier.weight(1f))
        Text(
            value,
            style = NoopType.number(15f),
            color = if (hasValue) Palette.textPrimary else Palette.textTertiary,
        )
    }
}


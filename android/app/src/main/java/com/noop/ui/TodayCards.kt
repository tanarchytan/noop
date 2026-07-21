package com.noop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.noop.data.DailyMetric
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// The Vitality vessel purple (b7bff) — no exact Palette token in this theme, so a fixed brand literal
// matching the iOS liquid Today's `liquidPurple` (Color(.sRGB, red:0x9b, green:0x7b, blue:0xff)). Used by
// the mini "Your cards" vessel so Vitality reads the same purple as iOS.
private val LIQUID_PURPLE: Color = Color(red = 0x9b / 255f, green = 0x7b / 255f, blue = 0xff / 255f, alpha = 1f)

// MARK: - "Your cards" dashboard (WHOOP "My Dashboard"), iOS yourCardsSection parity
//
// A persisted, reorderable selection of metric cards surfaced on Today as flat WHOOP metric ROWS. The
// section header carries the "Your cards" overline + a right-aligned BLUE "CUSTOMISE" text action; each row
// is a leading tinted icon tile + UPPERCASE tracked label over a grey baseline caption on the left, and the
// big white value + small unit + chevron on the right. A card with no value yet renders a dash rather than
// vanishing. Mirrors iOS TodayView.yourCardsSection / pinnedCardRow / dashboardValue / dashboardTint.

@Composable
internal fun YourCardsSection(
    cards: List<DashboardCard>,
    day: DailyMetric?,
    carriedDay: DailyMetric?,
    vitalsDay: DailyMetric?,
    spo2Day: DailyMetric?,
    skinTempDay: DailyMetric?,
    stress: Double?,
    fitnessAge: Double?,
    vitality: Double?,
    importedStepsForDay: Int?,
    estimatedStepsForDay: Int?,
    latestActiveKcal: Double?,
    hydrationTotalMl: Double,
    hydrationGoalMl: Int,
    onOpenHydration: () -> Unit,
    onOpenStress: () -> Unit,
    onOpenMetric: (String) -> Unit,
    onOpenSleep: () -> Unit,
    onOpenCoupled: () -> Unit,
    onCustomise: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().staggeredAppear(2)) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
 // Header: "YOUR CARDS" overline + a right-aligned blue CUSTOMISE action (the WHOOP ✎ affordance).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Overline("Your cards", modifier = Modifier.weight(1f))
                TextButton(
                    onClick = onCustomise,
                    colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent),
                    modifier = Modifier.semantics { contentDescription = "Customise your cards" },
                ) {
                    Icon(
                        Icons.Filled.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "CUSTOMISE",
                        style = NoopType.overline.copy(letterSpacing = 0.4.sp),
                        color = Palette.accent,
                    )
                }
            }
            cards.forEach { card ->
                DashboardCardRow(
                    card = card,
                    value = dashboardCardValue(
                        card = card,
                        day = day,
                        carriedDay = carriedDay,
                        vitalsDay = vitalsDay,
                        spo2Day = spo2Day,
                        skinTempDay = skinTempDay,
                        stress = stress,
                        fitnessAge = fitnessAge,
                        vitality = vitality,
                        importedStepsForDay = importedStepsForDay,
                        estimatedStepsForDay = estimatedStepsForDay,
                        latestActiveKcal = latestActiveKcal,
                        hydrationTotalMl = hydrationTotalMl,
                        hydrationGoalMl = hydrationGoalMl,
                    ),
 // The mini liquid vessel's fill — the SAME per-card fraction iOS `liquidCard` uses.
                    fraction = dashboardCardFraction(
                        card = card,
                        day = day,
                        carriedDay = carriedDay,
                        vitalsDay = vitalsDay,
                        stress = stress,
                        fitnessAge = fitnessAge,
                        vitality = vitality,
                        importedStepsForDay = importedStepsForDay,
                        estimatedStepsForDay = estimatedStepsForDay,
                    ),
                    tint = dashboardCardTint(card),
 // : label the sleep row with its source + night (this section renders at offset 0
 // only, so it IS last night), so a WHOOP-imported figure is never silently shown as
 // "last night" with no provenance. iOS TodayView.sleepSourceSubtitle twin.
                    subtitleOverride = sleepSourceSubtitle(card, day),
 // /: every card now opens its OWN detail, matching iOS. The Stress card -> Stress;
 // the overnight vitals (HRV / Resting HR / Respiratory / SpO₂ / Skin Temp) + Fitness age /
 // Vitality / Steps / Calories -> each metric's focused trend (vital_detail/<key>, the iOS
 // metricDetail twin); Sleep -> Sleep; Hydration -> Hydration. Whole row is the button.
                    onClick = dashboardCardDestination(
                        card = card,
                        onOpenStress = onOpenStress,
                        onOpenMetric = onOpenMetric,
                        onOpenSleep = onOpenSleep,
                        onOpenHydration = onOpenHydration,
                        onOpenCoupled = onOpenCoupled,
                    ),
                )
            }
        }
    }
}

/** : the sleep row's value is `totalSleepMin` — WHOOP's imported TST, which can legitimately differ
 * from the Sleep tab's on-device re-staged night (WHOOP CSV + Apple Health both imported). Label the row
 * with its source (the SAME `daySourceBadge` winner the Sleep tab's `MainSleepFooter` uses) + "last
 * night" — `YourCardsSection` renders at offset 0 only, so the row IS last night — so a WHOOP figure is
 * never silently shown as "last night" with no provenance. null → the card keeps its static subtitle
 * (not the sleep card, or no banked sleep). Twin of iOS `TodayView.sleepSourceSubtitle`; the source
 * mechanism differs per platform (Android keys on the day's session source, iOS on `importedSleep`),
 * exactly as the two Sleep-tab badges already do, so the label — not the wiring — is what stays in parity. */
private fun sleepSourceSubtitle(card: DashboardCard, day: DailyMetric?): String? {
    if (card != DashboardCard.SLEEP) return null
    val d = day ?: return null
    if (d.totalSleepMin == null) return null
    val source = daySourceBadge(d.deviceId).first
    return "$source · last night"
}

/** The `vital_detail/<key>` key a metric/vital card opens, or null when the card has its OWN dedicated
 * screen (Stress / Sleep / Hydration / Coupled) rather than a metric-detail trend. Mirrors the iOS
 * `liquidCard` switch, where every metric/vital card opens `metricDetail(key)` (its own focused trend),
 * NOT the shared Health hub (2026-07-03). Keys are the Android VitalDetailScreen keys. */
private fun dashboardCardMetricKey(card: DashboardCard): String? = when (card) {
    DashboardCard.HRV -> "hrv"
    DashboardCard.RESTING_HR -> "rhr"
    DashboardCard.RESPIRATORY -> "resp"
    DashboardCard.BLOOD_OXYGEN -> "spo2"
    DashboardCard.SKIN_TEMP -> "skin"
    DashboardCard.FITNESS_AGE -> "fitness_age"
    DashboardCard.VITALITY -> "vitality"
    DashboardCard.STEPS -> "steps_est"
    DashboardCard.CALORIES -> "active_kcal"
 // These carry their own full screen, not a per-metric trend.
    DashboardCard.STRESS, DashboardCard.SLEEP, DashboardCard.HYDRATION, DashboardCard.COUPLED -> null
}

/** The destination callback a dashboard card opens when tapped. Mirrors the iOS dashboardCardRow switch:
 * Stress -> Stress; Sleep -> Sleep; Hydration -> Hydration; Coupled -> the WHOOP-style day screen; every
 * metric/vital card -> its OWN focused trend (`vital_detail/<key>` via [onOpenMetric]), matching the iOS
 * `metricDetail(key)`. Every card resolves to a destination, so the chevron is always honest. */
private fun dashboardCardDestination(
    card: DashboardCard,
    onOpenStress: () -> Unit,
    onOpenMetric: (String) -> Unit,
    onOpenSleep: () -> Unit,
    onOpenHydration: () -> Unit,
    onOpenCoupled: () -> Unit,
): () -> Unit = when (card) {
    DashboardCard.STRESS -> onOpenStress
    DashboardCard.SLEEP -> onOpenSleep
    DashboardCard.HYDRATION -> onOpenHydration
 // The Coupled view card taps through to the full WHOOP-style day screen.
    DashboardCard.COUPLED -> onOpenCoupled
 // Every overnight vital + Fitness age / Vitality / Steps / Calories opens its own metric-detail trend.
    else -> {
        val key = dashboardCardMetricKey(card)
        if (key != null) ({ onOpenMetric(key) }) else ({})
    }
}

/** A dashboard card's WHOOP-token tint (icon + accent). Score cards take their domain colour; vitals take
 * their biometric hue; everything else the blue accent. No gold (WHOOP), tokens only. Mirrors iOS
 * dashboardTint. This drives the mini liquid vessel's tint on each row, so it follows the iOS `liquidCard`
 * per-card tints exactly: Stress=accent, Fitness age=charge-green, Vitality=liquid-purple, HRV=cyan,
 * Resting HR=rose, Respiratory=accent, Steps=cyan, Sleep=rest, Coupled=charge. */
private fun dashboardCardTint(card: DashboardCard): Color = when (card) {
 // iOS `liquidCard`: stress → StrandPalette.accent (blue), not the Effort orange.
    DashboardCard.STRESS -> Palette.accent
    DashboardCard.FITNESS_AGE -> Palette.chargeColor
 // iOS vitality → liquidPurple (b7bff).
    DashboardCard.VITALITY -> LIQUID_PURPLE
 // iOS hrv → metricCyan (this theme's metricPurple is a blue, cyan reads as the iOS HRV teal).
    DashboardCard.HRV -> Palette.metricCyan
    DashboardCard.RESTING_HR -> Palette.metricRose
    DashboardCard.RESPIRATORY -> Palette.accent
    DashboardCard.BLOOD_OXYGEN -> Palette.metricCyan
    DashboardCard.SKIN_TEMP -> Palette.metricAmber
    DashboardCard.SLEEP -> Palette.restColor
    DashboardCard.STEPS -> Palette.metricCyan
    DashboardCard.CALORIES -> Palette.metricAmber
    DashboardCard.HYDRATION -> Palette.metricCyan
    DashboardCard.COUPLED -> Palette.chargeColor
}

/**
 * A dashboard card's mini-vessel fill fraction (0..1), or null for an empty (no-reading) vessel. Mirrors the
 * iOS `liquidCard` `frac:` argument exactly, per card:
 * Stress = stress/3 · Fitness age = 0.5 (fixed) · Vitality = vitality/100 · HRV = avgHrv/120 ·
 * Resting HR = restingHr/100 · Respiratory = respRate/24 · Steps = steps/10000 · Sleep = totalSleepMin/480 ·
 * Coupled = 0.6 (fixed) · Blood oxygen / Skin temp / Calories / Hydration = null (empty, not half-full).
 * The three overnight vitals (HRV / Resting HR / Respiratory) read PER-FIELD today-first with the
 * recovery-INDEPENDENT [vitalsDay] carry, matching the row VALUE, so the vessel fill and the number agree
 * (and a recovery-nulled night keeps its OWN preserved vitals). Sleep keeps the recovery-gated
 * `carriedDay ?: day` carry.
 */
private fun dashboardCardFraction(
    card: DashboardCard,
    day: DailyMetric?,
    carriedDay: DailyMetric?,
    vitalsDay: DailyMetric?,
    stress: Double?,
    fitnessAge: Double?,
    vitality: Double?,
    importedStepsForDay: Int?,
    estimatedStepsForDay: Int?,
): Double? {
    fun over(v: Double?, ceiling: Double): Double? = v?.let { (it / ceiling).coerceIn(0.0, 1.0) }
    val vd = carriedDay ?: day
    return when (card) {
        DashboardCard.STRESS -> over(stress, 3.0)
        DashboardCard.FITNESS_AGE -> if (fitnessAge != null) 0.5 else null
        DashboardCard.VITALITY -> over(vitality, 100.0)
        DashboardCard.HRV -> over(day?.avgHrv ?: vitalsDay?.avgHrv, 120.0)
        DashboardCard.RESTING_HR -> over((day?.restingHr ?: vitalsDay?.restingHr)?.toDouble(), 100.0)
        DashboardCard.RESPIRATORY -> over(day?.respRateBpm ?: vitalsDay?.respRateBpm, 24.0)
        DashboardCard.STEPS -> {
            val steps = (day?.steps ?: importedStepsForDay ?: estimatedStepsForDay)?.toDouble()
            over(steps, 10000.0)
        }
        DashboardCard.SLEEP -> over(vd?.totalSleepMin, 480.0)
        DashboardCard.COUPLED -> 0.6
 // Not wired to a real read yet — an EMPTY vessel (not half-full) so it doesn't imply a reading.
        DashboardCard.BLOOD_OXYGEN, DashboardCard.SKIN_TEMP, DashboardCard.CALORIES,
        DashboardCard.HYDRATION -> null
    }
}

/**
 * Resolve a dashboard card's CURRENT display value from the values Today already loads, with its unit
 * suffix appended. Returns a dash when the value isn't available yet, never a fabricated number. Reuses
 * the SAME reads the rest of Today uses (displayMetric vitals, the pinned Stress / Fitness age / Vitality,
 * steps, calories, sleep duration). Mirrors iOS dashboardValue.
 *
 * The three overnight vitals (HRV / Resting HR / Respiratory) read PER-FIELD today-first with the
 * recovery-INDEPENDENT [vitalsDay] carry ( follow-up), so a night whose recovery was nulled post-update
 * still shows its OWN preserved value rather than an older recovery-scored day's (the tile-vs-card fix).
 * SpO₂ / Skin Temp / Sleep keep the recovery-gated `carriedDay ?: day` carry. Steps / Calories stay on
 * today's own row (they accrue through the day, never a carry). Stress / Fitness age / Vitality come from
 * their own resolved loads.
 */
private fun dashboardCardValue(
    card: DashboardCard,
    day: DailyMetric?,
    carriedDay: DailyMetric?,
    vitalsDay: DailyMetric?,
    spo2Day: DailyMetric?,
    skinTempDay: DailyMetric?,
    stress: Double?,
    fitnessAge: Double?,
    vitality: Double?,
    importedStepsForDay: Int?,
    estimatedStepsForDay: Int?,
    latestActiveKcal: Double?,
    hydrationTotalMl: Double,
    hydrationGoalMl: Int,
): String {
    fun withUnit(s: String): String =
        if (s == NO_DATA) NO_DATA else if (card.unit.isEmpty()) s else "$s ${card.unit}"

 // SpO₂ / Skin Temp / Sleep carry over from the last scored night; today's accruing totals do not.
    val vd = carriedDay ?: day

    return when (card) {
        DashboardCard.HRV ->
            withUnit((day?.avgHrv ?: vitalsDay?.avgHrv)?.let { it.roundToInt().toString() } ?: NO_DATA)
        DashboardCard.RESTING_HR ->
            withUnit((day?.restingHr ?: vitalsDay?.restingHr)?.toString() ?: NO_DATA)
        DashboardCard.RESPIRATORY ->
            withUnit((day?.respRateBpm ?: vitalsDay?.respRateBpm)?.let { String.format(Locale.US, "%.1f", it) } ?: NO_DATA)
        DashboardCard.BLOOD_OXYGEN ->
 // PER-FIELD carry: the whole-row carries (vd) land on rows whose spo2Pct is null (the engine
 // writes spo2Pct = null on computed rows), so fall through to the last row that HAS one.
            (vd?.spo2Pct ?: spo2Day?.spo2Pct)?.let { String.format(Locale.US, "%.0f%%", it) } ?: NO_DATA
        DashboardCard.SKIN_TEMP ->
 // Stored as a deviation from baseline (°C); show it signed so +/- reads honestly.
 // Same per-field carry as Blood Oxygen.
            (vd?.skinTempDevC ?: skinTempDay?.skinTempDevC)?.let { String.format(Locale.US, "%+.1f°", it) } ?: NO_DATA
        DashboardCard.SLEEP -> sleepValue(vd)
        DashboardCard.STEPS -> {
            val real = day?.steps?.let { intStringGrouped(it.toDouble()) }
                ?: importedStepsForDay?.let { intStringGrouped(it.toDouble()) }
            val est = estimatedStepsForDay?.let { intStringGrouped(it.toDouble()) }
            real ?: est ?: NO_DATA
        }
        DashboardCard.CALORIES ->
            withUnit(latestActiveKcal?.let { intStringGrouped(it) } ?: NO_DATA)
        DashboardCard.STRESS ->
 // /: Stress is baseline-relative, so until the strap has banked enough worn nights to
 // seed the 30-day RHR/HRV baseline StressScreen reads, the front card has no number to show. The
 // old `?: NO_DATA` rendered a bare dash that read like a broken card; show the honest calibrating
 // state instead, matching the owner's reply on and the StressScreen empty/calibrating copy.
            stress?.let { it.roundToInt().toString() } ?: STRESS_CALIBRATING
        DashboardCard.FITNESS_AGE ->
            withUnit(fitnessAge?.let { it.roundToInt().toString() } ?: NO_DATA)
        DashboardCard.VITALITY ->
            vitality?.let { it.roundToInt().toString() } ?: NO_DATA
        DashboardCard.HYDRATION ->
 // "<total> / <goal> L" in litres to 1 dp, e.g. "1.2 / 3.2 L". Always shows a value (a fresh
 // day reads "0.0 / 3.2 L"), since the goal is always derivable from the profile.
            String.format(
                Locale.US, "%.1f / %.1f L",
                hydrationTotalMl / 1000.0, hydrationGoalMl / 1000.0,
            )
        DashboardCard.COUPLED ->
 // A tap-through row with no metric value of its own, the row shows just the chevron. An empty
 // string (not NO_DATA) renders no number and leaves it un-dimmed. Mirrors iOS dashboardValue.
            ""
    }
}

/**
 * One WHOOP "My Dashboard" metric row: a thin-line tinted icon tile, an UPPERCASE tracked label over a grey
 * baseline caption, the big white value + small unit, and a chevron, on the flat frosted card surface (no
 * glow), tokens only. Mirrors iOS pinnedCardRow. The whole row is the tap target: when [onClick] is set it
 * pushes that card's detail (the chevron is the hint), matching iOS.
 */
@Composable
private fun DashboardCardRow(
    card: DashboardCard,
    value: String,
    fraction: Double?,
    tint: Color,
 // : a per-card dynamic subtitle (currently the sleep row's source + night); null keeps the
 // card's static description.
    subtitleOverride: String? = null,
    onClick: (() -> Unit)? = null,
) {
 // A real number renders white; a placeholder (No Data, or the Stress calibrating state) renders dimmed.
    val hasValue = value != NO_DATA && value != STRESS_CALIBRATING
 // iOS `cardLink` corner is 20 (a touch rounder than the app-wide 18dp card), with the SAME neutral
 // surfaceRaised fill + plain hairline the frosted neutral surface already draws.
    val rowShape = RoundedCornerShape(20.dp)
 // liquidPress: the tappable card settles inward on press (the iOS LiquidPressStyle feel). The SAME
 // interactionSource feeds the clickable and the press modifier, so it responds to the actual touch.
 // It is applied OUTSIDE the frosted surface so the whole card (surface + content) scales/dims as one.
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.liquidPress(interaction) else it }
            .clip(rowShape)
            .frostedCardSurface(cornerRadius = 20.dp)
            .let {
                if (onClick != null) {
                    it.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else it
            }
 // iOS row padding: 14h / 11v (tighter than the old 13/11 icon-box row).
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .semantics { contentDescription = "${card.title}: $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
 // THE fix: a 30dp mini LIQUID VESSEL filled to this card's fraction, tinted its domain colour — the
 // "small liquid circle per icon" iOS shows and Android was missing (a flat Material-icon square).
 // Static (animated=false) so the many small gauges cost nothing per frame, matching iOS `cardLink`.
        LiquidVessel(
            value = fraction,
            tint = tint,
            animated = false,
            modifier = Modifier.size(30.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
 // iOS: overline 11 / +1.0 tracking, textPrimary.
            Text(
                card.title.uppercase(),
                style = NoopType.overline.copy(fontSize = 11.sp, letterSpacing = 1.0.sp),
                color = Palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitleOverride ?: card.subtitle,
                style = NoopType.caption,
                color = Palette.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
 // iOS value = number(17), textPrimary.
        Text(
            value,
            style = NoopType.number(17f),
            color = if (hasValue) Palette.textPrimary else Palette.textTertiary,
            maxLines = 1,
        )
 // iOS chevron = 12.
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Group-separated integer display from a Double (e.g. 12 345 steps), matching the Apple Health tiles. A
 * file-internal twin of the private [intString] so the dashboard rows format steps/calories identically. */
private fun intStringGrouped(v: Double): String {
    val n = v.roundToInt()
    return if (kotlin.math.abs(n) >= 1000) String.format(Locale.US, "%,d", n) else "$n"
}

// MARK: - "Your cards" dashboard editor (WHOOP "My Dashboard" ✎)
//
// A Today-local dialog for choosing WHICH dashboard cards show and in what order. Display-only: it edits the
// persisted selection, never any stored metric. Enabled cards first (saved order), then the disabled
// remainder in canonical order, so toggling one on drops it at the end of the visible set and every known
// card is listed once. Toggle hides/shows a card; up/down arrows reorder it (no reorder lib, simple arrow
// buttons, matching KeyMetricsEditorDialog). Mirrors iOS DashboardCardsEditorSheet. At least one card must
// stay enabled (an empty dashboard reads as a bug).

@Composable
internal fun DashboardCardsEditorDialog(
    initial: List<DashboardCard>,
    onDismiss: () -> Unit,
    onSave: (List<DashboardCard>) -> Unit,
) {
    val items = remember {
        val enabledSet = initial.toHashSet()
        mutableStateListOf<EditableDashboardCard>().apply {
            initial.forEach { add(EditableDashboardCard(it, true)) }
            DashboardCard.canonicalOrder.filter { it !in enabledSet }.forEach { add(EditableDashboardCard(it, false)) }
        }
    }

    fun move(from: Int, to: Int) {
        if (from in items.indices && to in items.indices) {
            val item = items.removeAt(from)
            items.add(to, item)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Palette.surfaceOverlay,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("My Dashboard", style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        "Choose which cards show on Today and reorder them with the arrows. " +
                            "Cards with no value yet show a dash.",
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }

                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    items.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Switch(
                                checked = item.enabled,
                                onCheckedChange = { items[index] = item.copy(enabled = it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Palette.surfaceBase,
                                    checkedTrackColor = Palette.accent,
                                    uncheckedThumbColor = Palette.textSecondary,
                                    uncheckedTrackColor = Palette.surfaceInset,
                                    uncheckedBorderColor = Palette.hairline,
                                ),
                                modifier = Modifier.semantics { contentDescription = "Show ${item.card.title}" },
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                item.card.title,
                                style = NoopType.body,
                                color = if (item.enabled) Palette.textPrimary else Palette.textTertiary,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { move(index, index - 1) },
                                enabled = index > 0,
                                modifier = Modifier.size(Metrics.iconButton),
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "Move ${item.card.title} up",
                                    tint = if (index > 0) Palette.textSecondary else Palette.textTertiary,
                                    modifier = Modifier.size(Metrics.iconSmall),
                                )
                            }
                            IconButton(
                                onClick = { move(index, index + 1) },
                                enabled = index < items.lastIndex,
                                modifier = Modifier.size(Metrics.iconButton),
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Move ${item.card.title} down",
                                    tint = if (index < items.lastIndex) Palette.textSecondary else Palette.textTertiary,
                                    modifier = Modifier.size(Metrics.iconSmall),
                                )
                            }
                        }
                        if (index < items.lastIndex) {
                            HorizontalDivider(color = Palette.hairline, thickness = 1.dp)
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
 // Reset to the canonical default: the default selection enabled, rest disabled.
                            items.clear()
                            val enabledSet = DashboardCard.defaultSelection.toHashSet()
                            DashboardCard.defaultSelection.forEach { items.add(EditableDashboardCard(it, true)) }
                            DashboardCard.canonicalOrder.filter { it !in enabledSet }
                                .forEach { items.add(EditableDashboardCard(it, false)) }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Palette.textSecondary),
                    ) { Text("Reset", style = NoopType.body) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onSave(items.filter { it.enabled }.map { it.card }) },
 // At least one card must stay visible, an empty dashboard reads as a bug, not a choice.
                        enabled = items.any { it.enabled },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Palette.accent,
                            contentColor = Palette.surfaceBase,
                        ),
                    ) { Text("Done", style = NoopType.captionNumber) }
                }
            }
        }
    }
}

/** One row's working state in the dashboard editor: the card + whether it's currently enabled. */
private data class EditableDashboardCard(val card: DashboardCard, val enabled: Boolean)


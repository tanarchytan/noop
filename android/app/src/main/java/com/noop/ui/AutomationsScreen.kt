package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.HrZones
import com.noop.analytics.NapCandidate
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Automations — turn the strap's physical inputs (double-tap, wrist on/off) and live
 * biometrics into on-device actions and haptic coaching. HR-zone coaching, the smart alarm
 * and the illness watch are real + persisted (ViewModel-backed).
 */
@Composable
fun AutomationsScreen(viewModel: AppViewModel) {
    val live by viewModel.live.collectAsStateWithLifecycle()

    // Double-tap action (parity since 4.2.8) — real + persisted via the ViewModel (NoopPrefs). The
    // dispatch runs in the ViewModel on a fresh strap DOUBLE_TAP event; this card just edits the choice.
    val doubleTapAction by viewModel.doubleTapAction.collectAsStateWithLifecycle()

    // The strap firmware wake-alarm state used to be read here; it moved to SmartAlarmScreen with
    // the rest of the alarm UI.
    // Illness watch is real + persisted (opt-OUT — the watch has always run on Android).
    val illnessWatch by viewModel.illnessWatchEnabled.collectAsStateWithLifecycle()
    // Battery alerts are real + persisted (opt-OUT, default ON; , thanks @ujix).
    val batteryAlerts by viewModel.batteryAlertsEnabled.collectAsStateWithLifecycle()
    val predictiveBatteryAlerts by viewModel.predictiveBatteryAlertsEnabled.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // HR-zone coaching is real + persisted (zone-based, mirrors macOS): the ViewModel owns the toggle +
    // recovery option and buzzes the strap on entering the top zone (and Zone 1 if recovery is on).
    val profile = remember { ProfileStore.from(ctx.applicationContext) }
    val zoneCoaching by viewModel.zoneCoaching.collectAsStateWithLifecycle()
    val zoneCoachRecovery by viewModel.zoneCoachRecovery.collectAsStateWithLifecycle()
    // The Zone 5 entry threshold (≥ 90% of HR-max), from the same HrZones model used everywhere.
    val zone5Bpm = remember(profile.hrMax) {
        HrZones.zones(maxHR = profile.hrMax.toDouble()).zones.firstOrNull { it.number == 5 }?.lower?.roundToInt() ?: 0
    }

    // Inactivity reminder — real + persisted via InactivityPrefs (opt-in, default OFF). Seeded
    // once, written through on change (SharedPreferences isn't reactive). The buzz itself fires from the
    // BLE offload path (WhoopBleClient.maybeBuzzInactivity → the shipped SedentaryDetector engine); this
    // screen only edits the prefs the engine reads.
    var inactivityEnabled by remember { mutableStateOf(InactivityPrefs.enabled(ctx)) }
    var inactivityThreshold by remember { mutableStateOf(InactivityPrefs.thresholdMinutes(ctx)) }
    var inactivityReNudge by remember { mutableStateOf(InactivityPrefs.reNudgeMinutes(ctx)) }
    var inactivityBuzzLoops by remember { mutableStateOf(InactivityPrefs.buzzLoops(ctx)) }
    var inactivityActiveHours by remember { mutableStateOf(InactivityPrefs.activeHoursEnabled(ctx)) }
    var inactivityActiveStart by remember { mutableStateOf(InactivityPrefs.activeStartMinutes(ctx)) }
    var inactivityActiveEnd by remember { mutableStateOf(InactivityPrefs.activeEndMinutes(ctx)) }
    // The engine also requires the global notification master (default OFF); surface that dependency so
    // enabling the reminder while master is off isn't silently inert.
    val notifMasterOn = NotifPrefs.getBool(ctx, NotifPrefs.MASTER, false)

    // PERF : lazy scaffold — each settings section is an unconditional top-level child, so each
    // becomes one `item { }` in the same order. No standalone Spacers (the eager `spacedBy(20.dp)` is
    // reproduced by the LazyColumn), so spacing is byte-identical; only on-screen sections compose + get
    // accessibility-walked on scroll.
    LazyScreenScaffold(
        title = stringResource(R.string.nav_automations),
        subtitle = stringResource(R.string.automations_subtitle),
    ) {
        // Double-tap (parity since 4.2.8): a real, persisted action picker bound to the ViewModel, with a
        // Test action button. Mirrors AutomationsView.swift's Picker (Apple-applicable subset only; no
        // lockScreen / runShortcut on Android).
        item {
        NoopSettingsSection(
            icon = Icons.Filled.TouchApp,
            title = stringResource(R.string.automations_double_tap_title),
            blurb = stringResource(R.string.automations_double_tap_blurb),
            overline = stringResource(R.string.automations_overline),
            active = doubleTapAction != DoubleTapAction.NONE,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.automations_when_i_double_tap), style = NoopType.body, color = Palette.textPrimary)
                Spacer(Modifier.weight(1f))
                DoubleTapActionPicker(
                    selected = doubleTapAction,
                    onSelect = { viewModel.setDoubleTapAction(it) },
                )
            }
            RowDivider()
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { viewModel.testDoubleTapAction() },
                    enabled = doubleTapAction != DoubleTapAction.NONE,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.accent),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(Metrics.iconSmall))
                    Spacer(Modifier.width(Metrics.space8))
                    Text(stringResource(R.string.automations_test_action), style = NoopType.body)
                }
                Spacer(Modifier.weight(1f))
                StatePill(
                    stringResource(
                        if (live.bonded) R.string.automations_strap_bonded
                        else R.string.automations_not_connected,
                    ),
                    tone = if (live.bonded) StrandTone.Positive else StrandTone.Warning,
                )
            }
        }
        }

        // Haptic coaching.
        item {
        NoopSettingsSection(
            icon = Icons.Filled.Bolt,
            title = stringResource(R.string.automations_haptic_title),
            blurb = stringResource(R.string.automations_haptic_blurb),
            overline = stringResource(R.string.automations_overline),
            active = zoneCoaching,
        ) {
            NoopToggleRow(
                title = stringResource(R.string.automations_zone_coaching),
                detail = stringResource(R.string.automations_zone_coaching_detail, zone5Bpm),
                checked = zoneCoaching,
                onCheckedChange = { viewModel.setZoneCoaching(it) },
            )
            if (zoneCoaching) {
                RowDivider()
                NoopToggleRow(
                    title = stringResource(R.string.automations_recovery_buzz),
                    detail = stringResource(R.string.automations_recovery_buzz_detail),
                    checked = zoneCoachRecovery,
                    onCheckedChange = { viewModel.setZoneCoachRecovery(it) },
                )
            }
        }
        }

        // the strap's silent wake-alarm card used to sit here, which let users conflate it with the
        // Wake Window + Wind-Down reminder over on the Alarms screen. It's moved to SmartAlarmScreen so
        // every wake/alarm control lives in one place. Automations is just inputs-to-actions now.

        // Inactivity reminder — real + persisted via InactivityPrefs; opt-in, default OFF.
        item {
        NoopSettingsSection(
            icon = Icons.Filled.Timer,
            title = stringResource(R.string.automations_inactivity_title),
            blurb = stringResource(R.string.automations_inactivity_blurb),
            overline = stringResource(R.string.automations_overline),
            active = inactivityEnabled,
        ) {
            NoopToggleRow(
                title = stringResource(R.string.automations_inactivity_enable),
                detail = stringResource(R.string.automations_inactivity_enable_detail),
                checked = inactivityEnabled,
                onCheckedChange = {
                    inactivityEnabled = it
                    InactivityPrefs.setBool(ctx, InactivityPrefs.ENABLED, it)
                },
            )
            if (inactivityEnabled) {
                if (!notifMasterOn) {
                    RowDivider()
                    Text(
                        stringResource(R.string.automations_inactivity_notifications_off),
                        style = NoopType.footnote, color = Palette.statusWarning,
                    )
                }
                RowDivider()
                StepperRow(
                    label = stringResource(R.string.automations_inactivity_sitting_for),
                    help = stringResource(R.string.automations_inactivity_sitting_for_help),
                    value = inactivityThreshold, suffix = "min", range = 15..120, step = 15,
                    onChange = {
                        inactivityThreshold = it
                        InactivityPrefs.setInt(ctx, InactivityPrefs.THRESHOLD_MIN, it)
                    },
                )
                RowDivider()
                StepperRow(
                    label = stringResource(R.string.automations_inactivity_renudge),
                    help = stringResource(R.string.automations_inactivity_renudge_help),
                    value = inactivityReNudge, suffix = "min", range = 15..120, step = 15,
                    onChange = {
                        inactivityReNudge = it
                        InactivityPrefs.setInt(ctx, InactivityPrefs.RENUDGE_MIN, it)
                    },
                )
                RowDivider()
                StepperRow(
                    label = stringResource(R.string.automations_inactivity_buzz_strength),
                    help = stringResource(R.string.automations_inactivity_buzz_strength_help),
                    value = inactivityBuzzLoops, suffix = "×", range = 1..4, step = 1,
                    onChange = {
                        inactivityBuzzLoops = it
                        InactivityPrefs.setInt(ctx, InactivityPrefs.BUZZ_LOOPS, it)
                    },
                )
                RowDivider()
                NoopToggleRow(
                    title = stringResource(R.string.automations_inactivity_active_hours),
                    detail = stringResource(R.string.automations_inactivity_active_hours_help),
                    checked = inactivityActiveHours,
                    onCheckedChange = {
                        inactivityActiveHours = it
                        InactivityPrefs.setBool(ctx, InactivityPrefs.ACTIVE_HOURS_ENABLED, it)
                    },
                )
                if (inactivityActiveHours) {
                    RowDivider()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.automations_from), style = NoopType.body, color = Palette.textPrimary)
                        Spacer(Modifier.weight(1f))
                        TimeChip(
                            minutes = inactivityActiveStart,
                            accessibilityLabel = stringResource(R.string.automations_active_hours_start),
                            onPicked = {
                                inactivityActiveStart = it
                                InactivityPrefs.setInt(ctx, InactivityPrefs.ACTIVE_START_MIN, it)
                            },
                        )
                        Spacer(Modifier.width(Metrics.space8))
                        Text(stringResource(R.string.automations_to), style = NoopType.body, color = Palette.textSecondary)
                        Spacer(Modifier.width(Metrics.space8))
                        TimeChip(
                            minutes = inactivityActiveEnd,
                            accessibilityLabel = stringResource(R.string.automations_active_hours_end),
                            onPicked = {
                                inactivityActiveEnd = it
                                InactivityPrefs.setInt(ctx, InactivityPrefs.ACTIVE_END_MIN, it)
                            },
                        )
                    }
                }
            }
        }
        }

        // On-device short-nap detection — opt-in, default OFF. Detected on the offload
        // hook; a confident nap is offered as a review card you accept (it becomes a nap session) or
        // dismiss. NEVER auto-written.
        item { NapDetectionSection(viewModel) }

        // Illness early-warning (real + persisted; opt-OUT — the watch has always run on Android).
        item {
        NoopSettingsSection(
            icon = Icons.Filled.MonitorHeart,
            title = stringResource(R.string.automations_illness_title),
            blurb = stringResource(R.string.automations_illness_blurb),
            overline = stringResource(R.string.automations_overline),
            active = illnessWatch,
        ) {
            NoopToggleRow(
                title = stringResource(R.string.automations_illness_toggle),
                detail = stringResource(R.string.automations_illness_detail),
                checked = illnessWatch,
                onCheckedChange = { viewModel.setIllnessWatchEnabled(it) },
            )
        }
        }

        // Battery alerts (real + persisted; opt-OUT, default ON — , thanks @ujix).
        item {
        NoopSettingsSection(
            icon = Icons.Filled.BatteryStd,
            title = stringResource(R.string.automations_battery_title),
            blurb = stringResource(R.string.automations_battery_blurb),
            overline = stringResource(R.string.automations_overline),
            active = batteryAlerts,
        ) {
            NoopToggleRow(
                title = stringResource(R.string.automations_battery_toggle),
                detail = stringResource(R.string.automations_battery_detail),
                checked = batteryAlerts,
                onCheckedChange = { viewModel.setBatteryAlertsEnabled(it) },
            )
            if (batteryAlerts) {
                NoopToggleRow(
                    title = stringResource(R.string.automations_battery_predictive),
                    detail = stringResource(R.string.automations_battery_predictive_detail),
                    checked = predictiveBatteryAlerts,
                    onCheckedChange = { viewModel.setPredictiveBatteryAlertsEnabled(it) },
                )
            }
        }
        }
    }
}

// MARK: - On-device nap detection (under NoopApp)

/**
 * The nap-detection automation: a toggle plus the REVIEW queue. Detection runs on the offload hook
 * (WhoopBleClient.maybeDetectNaps → the pure NapDetector); a confident NAP is queued in NapStore and shown
 * here as a card the user ACCEPTS (→ a manual nap session, the path) or DISMISSES. The engine never
 * auto-writes a session, and an INCONCLUSIVE window queues nothing — honest by construction.
 */
@Composable
private fun NapDetectionSection(viewModel: AppViewModel) {
    val scope = rememberCoroutineScope()
    val enabled by viewModel.napDetectionEnabled.collectAsStateWithLifecycle()
    // The queue isn't a reactive flow (it's written from the BLE layer); re-read it on each toggle/action.
    var pending by remember { mutableStateOf(viewModel.pendingNaps()) }

    NoopSettingsSection(
        icon = Icons.Filled.Bedtime,
        title = stringResource(R.string.automations_nap_title),
        blurb = stringResource(R.string.automations_nap_blurb),
        overline = stringResource(R.string.automations_overline),
        active = enabled,
    ) {
        NoopToggleRow(
            title = stringResource(R.string.automations_nap_detect),
            detail = stringResource(R.string.automations_nap_detect_detail),
            checked = enabled,
            onCheckedChange = {
                viewModel.setNapDetectionEnabled(it)
                if (it) pending = viewModel.pendingNaps()
            },
        )
        if (enabled) {
            if (pending.isEmpty()) {
                RowDivider()
                Text(
                    stringResource(R.string.automations_nap_empty),
                    style = NoopType.footnote, color = Palette.textTertiary,
                )
            } else {
                pending.forEach { nap ->
                    RowDivider()
                    NapReviewRow(
                        nap = nap,
                        onAccept = { scope.launch { pending = viewModel.acceptDetectedNap(nap) } },
                        onDismiss = { pending = viewModel.dismissDetectedNap(nap) },
                    )
                }
            }
        }
    }
}

/** One pending nap candidate: an honest "HH:mm–HH:mm · ~N min" line (+ mean HR when known) with Keep /
 * Skip controls. Keep persists it as a nap session; Skip forgets it (and won't re-queue the window). */
@Composable
private fun NapReviewRow(nap: NapCandidate, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(napWindowLabel(nap, ctx), style = NoopType.body, color = Palette.textPrimary)
            Text(napDetailLabel(nap, ctx), style = NoopType.footnote, color = Palette.textTertiary)
        }
        Spacer(Modifier.width(Metrics.space8))
        NapActionButton(
            Icons.Filled.Check,
            stringResource(R.string.automations_nap_keep),
            Palette.statusPositive,
            onAccept,
        )
        Spacer(Modifier.width(Metrics.space8))
        NapActionButton(
            Icons.Filled.Close,
            stringResource(R.string.automations_nap_skip),
            Palette.textTertiary,
            onDismiss,
        )
    }
}

@Composable
private fun NapActionButton(icon: ImageVector, contentDescription: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Palette.surfaceInset)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(Metrics.iconSmall))
    }
}

/** "HH:mm–HH:mm · ~N min", local time. Pure-ish (reads the device clock format only). */
private fun napWindowLabel(nap: NapCandidate, ctx: android.content.Context): String {
    val fmt = android.text.format.DateFormat.getTimeFormat(ctx)
    val start = fmt.format(java.util.Date(nap.start * 1000L))
    val end = fmt.format(java.util.Date(nap.end * 1000L))
    val mins = nap.durationS / 60
    return ctx.getString(R.string.automations_nap_window, start, end, mins)
}

private fun napDetailLabel(nap: NapCandidate, ctx: android.content.Context): String {
    val meanHr = nap.meanHr ?: return ctx.getString(R.string.automations_nap_detail)
    return ctx.getString(R.string.automations_nap_detail_hr, meanHr)
}

// MARK: - Per-weekday wake-time overrides (under NoopApp)

/**
 * Per-weekday wake-time OVERRIDES for the smart alarm. For each day the alarm fires on, shows the
 * effective wake time (the day's override, else the default) as a [TimeChip]; picking a time sets that
 * day's override, and a "Reset" affordance clears it back to the default. Days the alarm doesn't fire on
 * aren't shown (no point overriding a day it won't ring). Empty enabledDays = every day, so all seven show.
 */
// internal (not private) so the consolidated Alarms screen (SmartAlarmScreen, ) can reuse the
// exact same picker. The strap wake-alarm card moved there but its weekday/override UI is unchanged.
@Composable
internal fun AlarmDayOverridePicker(
    defaultMinutes: Int,
    enabledDays: Set<Int>,
    overrides: Map<Int, Int>,
    onSetOverride: (Int, Int?) -> Unit,
) {
    val fireDays = SMART_ALARM_WEEKDAY_ORDER.filter { smartAlarmWeekdayIsSelected(it, enabledDays) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(stringResource(R.string.alarm_per_day_wake_time), style = NoopType.caption, color = Palette.textTertiary)
        fireDays.forEach { dow ->
            val effective = overrides[dow] ?: defaultMinutes
            val hasOverride = overrides.containsKey(dow)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(smartAlarmWeekdayName(dow), style = NoopType.body, color = Palette.textPrimary)
                Spacer(Modifier.weight(1f))
                if (hasOverride) {
                    Text(
                        stringResource(R.string.alarm_reset),
                        style = NoopType.caption,
                        color = Palette.accent,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onSetOverride(dow, null) }
                            .padding(horizontal = Metrics.space10, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(Metrics.space8))
                }
                TimeChip(
                    minutes = effective,
                    accessibilityLabel = stringResource(R.string.alarm_day_wake_time, smartAlarmWeekdayName(dow)),
                    onPicked = { onSetOverride(dow, it) },
                )
            }
        }
        Text(
            stringResource(R.string.alarm_per_day_help),
            style = NoopType.footnote, color = Palette.textTertiary,
        )
    }
}

/** A compact dropdown that mirrors the iOS double-tap Picker: a tappable label + chevron that opens a
 * menu of [DoubleTapAction]s. Labels come from [DoubleTapAction.label] so both clients read the same. */
@Composable
private fun DoubleTapActionPicker(
    selected: DoubleTapAction,
    onSelect: (DoubleTapAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .clickable { expanded = true }
                .background(Palette.surfaceInset)
                .padding(horizontal = Metrics.space12, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(selected.label, style = NoopType.body, color = Palette.textPrimary)
            Spacer(Modifier.width(Metrics.space4))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.automations_choose_double_tap),
                tint = Palette.textSecondary,
                modifier = Modifier.size(Metrics.iconSmall),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (action in DoubleTapAction.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            style = NoopType.body,
                            color = if (action == selected) Palette.accent else Palette.textPrimary,
                        )
                    },
                    onClick = { onSelect(action); expanded = false },
                )
            }
        }
    }
}

/**
 * Weekday selector for the smart alarm. One tappable circle per weekday, Monday-first. An empty
 * [selected] set means "every day" (all circles read as on). Mirrors the macOS AutomationsView picker.
 */
// internal (not private) so SmartAlarmScreen (the consolidated Alarms surface, ) can reuse it.
@Composable
internal fun AlarmWeekdayPicker(selected: Set<Int>, onToggle: (Int) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Metrics.space6),
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space6)) {
            for (dow in SMART_ALARM_WEEKDAY_ORDER) {
                val on = smartAlarmWeekdayIsSelected(dow, selected)
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (on) Palette.accent else Palette.surfaceInset)
                        .clickable { onToggle(dow) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        smartAlarmWeekdayInitial(dow),
                        style = NoopType.caption,
                        color = if (on) Palette.surfaceBase else Palette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(smartAlarmWeekdaySummary(selected), style = NoopType.caption, color = Palette.textTertiary)
    }
}

/** Calendar.DAY_OF_WEEK numbers laid out Monday-first (Mon…Sun → 2,3,4,5,6,7,1). */
private val SMART_ALARM_WEEKDAY_ORDER = intArrayOf(2, 3, 4, 5, 6, 7, 1)

/** A day reads as "on" when the set is empty (= every day) or explicitly contains it. Pure for tests. */
internal fun smartAlarmWeekdayIsSelected(dow: Int, days: Set<Int>): Boolean =
    days.isEmpty() || days.contains(dow)

/**
 * Toggle one weekday, normalising "every day" at both ends so the empty set always means every day.
 * Pure + side-effect-free for unit tests. Pulling a day out of the implicit "every day" expands to the
 * explicit other six; selecting the seventh collapses back to the empty "every day" set. Mirrors macOS
 * `AutomationsView.toggledWeekday`.
 */
internal fun toggledSmartAlarmWeekday(dow: Int, days: Set<Int>): Set<Int> {
    val next: MutableSet<Int> = when {
        days.isEmpty() -> (1..7).toMutableSet().also { it.remove(dow) }
        days.contains(dow) -> days.toMutableSet().also { it.remove(dow) }
        else -> days.toMutableSet().also { it.add(dow) }
    }
    return if (next.size == 7) emptySet() else next
}

/** Human-readable summary of the selection. Pure for tests. Mirrors macOS `weekdaySummary`. */
internal fun smartAlarmWeekdaySummary(days: Set<Int>): String = when {
    days.isEmpty() || days.size == 7 -> "Every day"
    days == setOf(2, 3, 4, 5, 6) -> "Weekdays"
    days == setOf(1, 7) -> "Weekends"
    else -> SMART_ALARM_WEEKDAY_ORDER.filter { days.contains(it) }
        .joinToString(", ") { smartAlarmWeekdayName(it) }
}

private fun smartAlarmWeekdayInitial(dow: Int): String = when (dow) {
    1 -> "S"; 2 -> "M"; 3 -> "T"; 4 -> "W"; 5 -> "T"; 6 -> "F"; 7 -> "S"; else -> "?"
}

private fun smartAlarmWeekdayName(dow: Int): String = when (dow) {
    1 -> "Sun"; 2 -> "Mon"; 3 -> "Tue"; 4 -> "Wed"; 5 -> "Thu"; 6 -> "Fri"; 7 -> "Sat"; else -> "?"
}

/** A label/help row with a −[value]+ stepper, clamped to [range] and moved by [step]. */
@Composable
private fun StepperRow(
    label: String,
    help: String,
    value: Int,
    suffix: String,
    range: IntRange,
    step: Int,
    onChange: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(label, style = NoopType.body, color = Palette.textPrimary)
            Text(help, style = NoopType.footnote, color = Palette.textTertiary)
        }
        Spacer(Modifier.width(Metrics.space12))
        val steps = remember(range, step) { range.step(step).toList() }
        val options = remember(steps, suffix) { steps.map { "$it $suffix" } }
        WheelPickerField(
            value = "$value",
            unit = suffix,
            accessibility = label,
            options = options,
            selectedIndex = steps.indexOf(value).coerceAtLeast(0),
            dialogTitle = label,
            onSelected = { onChange(steps[it]) },
        )
    }
}


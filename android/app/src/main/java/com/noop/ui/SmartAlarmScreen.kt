package com.noop.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.ble.PuffinExperiment

/**
 * Smart alarm — Android phone-based wake, with a guaranteed hard-deadline fallback.
 *
 * The user picks the EARLIEST acceptable wake time and a window length. NOOP watches the overnight
 * strap stream and, if it spots a lighter sleep phase inside the window, wakes you then — but a
 * GUARANTEED exact OS alarm is always scheduled at the window's END (via AlarmManager), independent
 * of Bluetooth, the strap, or the app being alive. The smart logic can only ever move the alarm
 * EARLIER; it can never cancel or skip the fallback. So you're woken by the window's end no matter
 * what. This screen is explicit about that safety guarantee.
 *
 * This is the ONE alarm surface. It hosts the phone-based Wake Window above, the strap's own
 * standalone firmware wake-alarm (moved here from Automations), and the cross-platform WIND-DOWN nudge,
 * so every wake/alarm control lives together instead of being split across two screens.
 */
@Composable
fun SmartAlarmScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val enabled by vm.phoneAlarmEnabled.collectAsStateWithLifecycle()
    val targetMinutes by vm.phoneAlarmTargetMinutes.collectAsStateWithLifecycle()
    val windowMinutes by vm.phoneAlarmWindowMinutes.collectAsStateWithLifecycle()
    val buzzWhoop4 by vm.buzzWhoop4Enabled.collectAsStateWithLifecycle()
    // the hint adapts to bond state — the strap can only be armed when a WHOOP 4.0 is connected.
    val liveState = vm.live.collectAsStateWithLifecycle().value
    val bonded = liveState.bonded
    // A hardcoded "WHOOP 4" reads wrong on a connected 5/MG, so name the actual strap generation: a detected 5/MG says "WHOOP 5/MG", anything
    // else (a 4.0, or nothing connected yet) keeps "WHOOP 4.0", so the label never claims the wrong device.
    val strapName = if (liveState.whoop5Detected) "WHOOP 5/MG" else "WHOOP 4.0"

    // True when exact alarms are permitted. Re-read on each (re)composition because the user can grant
    // it in Settings and come back — there's no result callback for this special-access permission.
    var canSchedule by remember { mutableStateOf(vm.canScheduleExactAlarms()) }

    // PERF: lazy scaffold — each of the four cards is one `item { }` (all unconditional). Order +
    // spacing unchanged (LazyColumn reproduces the eager `spacedBy(20.dp)`); only on-screen cards compose +
    // are accessibility-walked.
    LazyScreenScaffold(
        // "Alarms" because this screen now holds the phone Wake Window, the strap's firmware
        // wake-alarm (moved here from Automations), and the wind-down reminder, so the broader title fits.
        title = stringResource(R.string.nav_alarms),
        subtitle = stringResource(R.string.alarm_subtitle),
    ) {
        // The guaranteed-wake card always shows so the safety promise is the first thing read.
        item { WindowCard(enabled = enabled, targetMinutes = targetMinutes, windowMinutes = windowMinutes) }

        item {
        AlarmSettingsCard {
            ToggleRowLocal(
                label = stringResource(R.string.alarm_wake_smart),
                help = stringResource(R.string.alarm_wake_smart_help),
                checked = enabled,
                onChange = { want ->
                    if (want && !vm.canScheduleExactAlarms()) {
                        // No callback for this special-access grant — send the user to the system page,
                        // and re-read the state when they return (canSchedule recomputes on recompose).
                        requestExactAlarmAccess(context)
                        canSchedule = vm.canScheduleExactAlarms()
                    } else {
                        val ok = vm.setPhoneAlarmEnabled(want)
                        canSchedule = vm.canScheduleExactAlarms()
                        if (!ok) requestExactAlarmAccess(context)
                    }
                },
            )

            if (enabled && !canSchedule) {
                RowDividerLocal()
                Text(
                    stringResource(R.string.alarm_no_exact_permission),
                    style = NoopType.footnote,
                    color = Palette.statusWarning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            requestExactAlarmAccess(context)
                            canSchedule = vm.canScheduleExactAlarms()
                        },
                )
            }

            if (enabled) {
                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                        Text(stringResource(R.string.alarm_wake_no_earlier), style = NoopType.body, color = Palette.textPrimary)
                        Text(
                            stringResource(R.string.alarm_wake_no_earlier_help),
                            style = NoopType.footnote, color = Palette.textTertiary,
                        )
                    }
                    Spacer(Modifier.width(Metrics.space16))
                    TimeChip(
                        minutes = targetMinutes,
                        accessibilityLabel = stringResource(R.string.alarm_earliest_wake_a11y),
                        onPicked = { vm.setPhoneAlarmTargetMinutes(it) },
                    )
                }

                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                        Text(stringResource(R.string.alarm_window_length), style = NoopType.body, color = Palette.textPrimary)
                        Text(
                            stringResource(R.string.alarm_window_length_help),
                            style = NoopType.footnote, color = Palette.textTertiary,
                        )
                    }
                    Spacer(Modifier.width(Metrics.space16))
                    WindowWheel(
                        windowMinutes = windowMinutes,
                        onChange = { vm.setPhoneAlarmWindowMinutes(it) },
                    )
                }
            }

            // companion strap-buzz, always visible so it's discoverable. Arms the strap's own firmware
            // alarm at the earliest wake time, so the strap buzzes first and the OS alarm backs it up.
            // label + copy name the CONNECTED strap generation (strapName), not a hardcoded "WHOOP 4".
            RowDividerLocal()
            ToggleRowLocal(
                label = stringResource(R.string.alarm_buzz_strap, strapName),
                help = if (bonded)
                    stringResource(R.string.alarm_buzz_strap_help_bonded, strapName)
                else
                    stringResource(R.string.alarm_buzz_strap_help_unbonded),
                checked = buzzWhoop4,
                onChange = { vm.setBuzzWhoop4Enabled(it) },
            )
        }
        }

        // the strap's own firmware wake-alarm (its own time + weekdays + per-day overrides). Moved
        // here from Automations so every wake/alarm control sits on the one Alarms screen instead of being
        // conflated with the wind-down reminder. Distinct from "Buzz WHOOP 4" above, which arms the strap
        // at the PHONE alarm's time; this card is the strap's standalone schedule.
        item { StrapAlarmCard(vm) }

        // The cross-platform wind-down nudge lives here too.
        item { WindDownCard(vm) }

        // the "how the smart wake works" explainer sat in the MIDDLE of the page (between the wake-alarm
        // settings and the strap alarm), which read as an interruption. It's reference detail, not a control,
        // so it belongs at the BOTTOM after every alarm/reminder control, moved here.
        item { ExplanationCard() }
    }
}

/**
 * The strap's standalone silent wake-alarm (moved from AutomationsScreen). Arms the strap's own
 * firmware alarm at the chosen time/weekdays over BLE, so it buzzes even if NOOP is closed. Reuses the
 * shared [AlarmWeekdayPicker] / [AlarmDayOverridePicker] from AutomationsScreen (same behaviour, just a
 * new home). Functions are untouched: it drives the same `viewModel.setSmartAlarm*` calls as before.
 */
@Composable
private fun StrapAlarmCard(vm: AppViewModel) {
    val context = LocalContext.current
    val smartAlarm by vm.smartAlarmEnabled.collectAsStateWithLifecycle()
    val alarmMinutes by vm.smartAlarmMinutes.collectAsStateWithLifecycle()
    val alarmWeekdays by vm.smartAlarmWeekdays.collectAsStateWithLifecycle()
    val alarmDayOverrides by vm.smartAlarmDayOverrides.collectAsStateWithLifecycle()
    val live = vm.live.collectAsStateWithLifecycle().value
    // The firmware alarm is EXPERIMENTAL on a WHOOP 5/MG: it only arms when Experimental probes are on,
    // otherwise enabling it silently arms nothing, so the UI says so instead of promising a wake.
    val experimentalOn = PuffinExperiment.from(context).isEnabled

    NoopCard(padding = 20.dp, tint = if (smartAlarm) Palette.accent else null) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Overline(stringResource(R.string.alarm_overline_morning))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Alarm, contentDescription = null, tint = Palette.accent)
                    Spacer(Modifier.width(Metrics.space10))
                    Text(stringResource(R.string.alarm_strap_title), style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            // Truth-sync: the WHOOP 4.0 alarm payload was captured from the official app and
            // confirmed buzzing on a real 4.0 by the capture author, so the copy no longer calls the
            // 4.0 path experimental. The 5/MG Experimental-gate branch below is deliberately untouched.
            ToggleRowLocal(
                label = stringResource(R.string.alarm_strap_toggle),
                help = stringResource(R.string.alarm_strap_toggle_help),
                checked = smartAlarm,
                onChange = { vm.setSmartAlarmEnabled(it) },
            )
            if (smartAlarm) {
                RowDividerLocal()
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.alarm_wake_at), style = NoopType.body, color = Palette.textPrimary)
                    Spacer(Modifier.weight(1f))
                    TimeChip(
                        minutes = alarmMinutes,
                        accessibilityLabel = stringResource(R.string.alarm_strap_wake_time_a11y),
                        onPicked = { vm.setSmartAlarmMinutes(it) },
                    )
                }
                RowDividerLocal()
                AlarmWeekdayPicker(
                    selected = alarmWeekdays,
                    onToggle = { dow -> vm.setSmartAlarmWeekdays(toggledSmartAlarmWeekday(dow, alarmWeekdays)) },
                )
                RowDividerLocal()
                // Per-weekday wake-time OVERRIDES: a different time for any day the alarm fires on.
                AlarmDayOverridePicker(
                    defaultMinutes = alarmMinutes,
                    enabledDays = alarmWeekdays,
                    overrides = alarmDayOverrides,
                    onSetOverride = { dow, minutes -> vm.setSmartAlarmDayOverride(dow, minutes) },
                )
                RowDividerLocal()
                if (live.whoop5Detected && !experimentalOn) {
                    Text(
                        stringResource(R.string.alarm_strap_5mg_experimental_off),
                        style = NoopType.footnote, color = Palette.statusWarning,
                    )
                } else if (live.whoop5Detected) {
                    // 5/MG with Experimental ON: the strap IS armed (experimental rev-4 payload) but a
                    // strap-driven wake has NEVER been captured on 5/MG, so the "confirmed on 4.0" copy must
                    // NOT show here (honesty).
                    Text(
                        stringResource(
                            if (live.bonded) R.string.alarm_strap_5mg_armed
                            else R.string.alarm_strap_connect_hint,
                        ),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                } else {
                    Text(
                        stringResource(
                            if (live.bonded) R.string.alarm_strap_armed
                            else R.string.alarm_strap_connect_hint,
                        ),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

// MARK: - Cards

/**
 * The always-visible "you WILL be woken by" guarantee card — a small Rest-world frosted hero. The
 * wake window reads as a clean earliest→deadline time pairing in big rounded numerals over a scenic
 * Rest backdrop (it's about waking, so it lives in the indigo world, not the brand-green chrome).
 */
@Composable
private fun WindowCard(enabled: Boolean, targetMinutes: Int, windowMinutes: Int) {
    val deadline = (targetMinutes + windowMinutes) % (24 * 60)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.cardRadius)),
    ) {
        ScenicHeroBackground(modifier = Modifier.matchParentSize(), domain = DomainTheme.Rest)
        Row(modifier = Modifier.padding(Metrics.screenRowSpacing), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = DomainTheme.Rest.color)
            Spacer(Modifier.width(Metrics.space12))
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                Overline(stringResource(R.string.alarm_guaranteed_wake))
                if (enabled) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                        Text(hhmm(targetMinutes), style = NoopType.number(28f), color = DomainTheme.Rest.color)
                        Text("→", style = NoopType.title2, color = Palette.textTertiary)
                        Text(hhmm(deadline), style = NoopType.number(28f), color = DomainTheme.Rest.bright)
                    }
                    Text(
                        stringResource(R.string.alarm_backup_set_for, hhmm(deadline)),
                        style = NoopType.footnote, color = Palette.textSecondary,
                    )
                } else {
                    Text(stringResource(R.string.alarm_off), style = NoopType.title2, color = Palette.textSecondary)
                    Text(
                        stringResource(R.string.alarm_off_help),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AlarmSettingsCard(content: @Composable () -> Unit) {
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Alarm, contentDescription = null, tint = Palette.accent)
                Spacer(Modifier.width(Metrics.space10))
                Text(stringResource(R.string.alarm_card_title), style = NoopType.headline, color = Palette.textPrimary)
            }
            content()
        }
    }
}

/** The cross-platform evening wind-down nudge — a gentle reminder, not an alarm. Rest-tinted when on. */
@Composable
private fun WindDownCard(vm: AppViewModel) {
    val enabled by vm.windDownEnabled.collectAsStateWithLifecycle()
    NoopCard(padding = 20.dp, tint = if (enabled) DomainTheme.Rest.color else null) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Overline(stringResource(R.string.alarm_overline_evening))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bedtime, contentDescription = null, tint = DomainTheme.Rest.color)
                    Spacer(Modifier.width(Metrics.space10))
                    Text(stringResource(R.string.alarm_wind_down_title), style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            ToggleRowLocal(
                label = stringResource(R.string.alarm_wind_down_toggle),
                help = stringResource(R.string.alarm_wind_down_help),
                checked = enabled,
                onChange = { vm.setWindDownEnabled(it) },
            )
        }
    }
}

@Composable
private fun ExplanationCard() {
    NoopCard(padding = 20.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = Palette.accent)
                Spacer(Modifier.width(Metrics.space10))
                Text(stringResource(R.string.alarm_explain_title), style = NoopType.headline, color = Palette.textPrimary)
            }
            Text(
                stringResource(R.string.alarm_explain_body_1),
                style = NoopType.footnote, color = Palette.textSecondary,
            )
            Text(
                stringResource(R.string.alarm_explain_body_2),
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
    }
}

// MARK: - Window stepper (5–60 min in 5-min steps)

/** Smart-alarm wake window: 5..60 min in 5-minute steps. */
private const val ALARM_WINDOW_MIN = 5
private const val ALARM_WINDOW_MAX = 60
private const val ALARM_WINDOW_STEP = 5

@Composable
private fun WindowWheel(windowMinutes: Int, onChange: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        val steps = remember { (ALARM_WINDOW_MIN..ALARM_WINDOW_MAX step ALARM_WINDOW_STEP).toList() }
        val options = steps.map { stringResource(R.string.alarm_window_minutes, it) }
        WheelPickerField(
            value = stringResource(R.string.alarm_window_minutes, windowMinutes),
            accessibility = stringResource(R.string.alarm_window_a11y),
            options = options,
            selectedIndex = steps.indexOf(windowMinutes).coerceAtLeast(0),
            dialogTitle = stringResource(R.string.alarm_window_a11y),
            onSelected = { onChange(steps[it]) },
        )
    }
}

// MARK: - Local toggle / divider (mirror the AutomationsScreen idiom, kept local to this lane's file)

@Composable
private fun ToggleRowLocal(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(label, style = NoopType.body, color = Palette.textPrimary)
            Text(help, style = NoopType.footnote, color = Palette.textTertiary)
        }
        Spacer(Modifier.width(Metrics.space16))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.surfaceBase,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.textSecondary,
                uncheckedTrackColor = Palette.surfaceInset,
                uncheckedBorderColor = Palette.hairline,
            ),
        )
    }
}

@Composable
private fun RowDividerLocal() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(Metrics.divider)
            .background(Palette.hairline),
    )
}

// MARK: - Helpers

private fun hhmm(minutes: Int): String {
    val m = ((minutes % (24 * 60)) + 24 * 60) % (24 * 60)
    return "%02d:%02d".format(m / 60, m % 60)
}

/** Open the system page where the user grants the exact-alarm special-access permission (API 31+).
 *  There's no runtime dialog for this; the user toggles it in Settings and returns. */
private fun requestExactAlarmAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        // Fall back to the app-details page if the OEM lacks the specific action.
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

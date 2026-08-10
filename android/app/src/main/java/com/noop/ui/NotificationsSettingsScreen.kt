package com.noop.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.notif.CallAlertController
import com.noop.notif.CallAlertSource
import java.util.Calendar

// MARK: - NotificationsSettingsScreen
//
// Choose which apps tap your wrist and
// how (per-app buzz pattern), with a master switch and overnight quiet hours.
//
// Android restricts package visibility (API 30+) and offers no "notification-capable app"
// query, so we ship a curated catalog of common notification apps grouped by category.
// Preferences persist in SharedPreferences; when the background bridge ships it reads the
// same prefs.
//
// Delivery requires a NotificationListenerService with Notification Access granted — the
// behaviour card deep-links to Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS for that.

// MARK: - Domain model (mirrors NotificationSettingsStore.swift)

/** Haptic pattern fired on the strap; only the repeat count varies. */
internal enum class BuzzPattern(@StringRes val label: Int, val loops: Int) {
    Single(R.string.notifications_pattern_single, 1),
    Double(R.string.notifications_pattern_double, 2),
    Triple(R.string.notifications_pattern_triple, 3),
    Long(R.string.notifications_pattern_long, 5),
}

/** Grouping for the settings screen, with its header icon + default pattern. */
internal enum class NotifCategory(
    @StringRes val title: Int,
    val icon: ImageVector,
    val defaultPattern: BuzzPattern,
) {
    Email(R.string.notifications_category_email, Icons.Filled.Email, BuzzPattern.Double),
    Messaging(R.string.notifications_category_messaging, Icons.AutoMirrored.Filled.Chat, BuzzPattern.Single),
    Meetings(R.string.notifications_category_meetings, Icons.Filled.Videocam, BuzzPattern.Triple),
    Calendar(R.string.notifications_category_calendar, Icons.Filled.CalendarMonth, BuzzPattern.Double),
}

/** A notification-capable app NOOP can mirror to the wrist. `id` is the persistence key. */
internal data class NotifApp(
    val id: String,
    val name: String,
    val category: NotifCategory,
    val glyph: ImageVector,
)

/**
 * Curated catalog of common Android notification apps, grouped by category.
 * We cannot enumerate which are actually installed (restricted package
 * visibility), so we present the full set as configurable examples.
 */
private val notifCatalog: List<NotifApp> = listOf(
    NotifApp("com.google.android.gm", "Gmail", NotifCategory.Email, Icons.Filled.Email),
    NotifApp("com.microsoft.office.outlook", "Outlook", NotifCategory.Email, Icons.Filled.Email),
    NotifApp("com.whatsapp", "WhatsApp", NotifCategory.Messaging, Icons.AutoMirrored.Filled.Chat),
    NotifApp("com.google.android.apps.messaging", "Messages", NotifCategory.Messaging, Icons.AutoMirrored.Filled.Chat),
    NotifApp("com.Slack", "Slack", NotifCategory.Messaging, Icons.AutoMirrored.Filled.Chat),
    NotifApp("org.telegram.messenger", "Telegram", NotifCategory.Messaging, Icons.AutoMirrored.Filled.Chat),
    // Teams' ringing-call notifications are handled by the Calls card below (VoIP path). This
    // per-app row covers everything else Teams sends to the shade (chats, @-mentions, channel
    // posts), which read as messages, so it lives under Messaging with the chat glyph.
    NotifApp("com.microsoft.teams", "Microsoft Teams", NotifCategory.Messaging, Icons.AutoMirrored.Filled.Chat),
    NotifApp("us.zoom.videomeetings", "Zoom", NotifCategory.Meetings, Icons.Filled.Videocam),
    NotifApp("com.google.android.calendar", "Calendar", NotifCategory.Calendar, Icons.Filled.CalendarMonth),
)

private fun appsIn(category: NotifCategory): List<NotifApp> =
    notifCatalog.filter { it.category == category }

private val activeCategories: List<NotifCategory> =
    NotifCategory.entries.filter { appsIn(it).isNotEmpty() }

// MARK: - SharedPreferences store

/**
 * Plain-prefs store for wrist-alert settings (the AI key uses encrypted prefs; these are
 * non-secret toggles). Per-app prefs are flattened to `app.<id>.enabled` / `app.<id>.pattern`
 * keys so no JSON dependency is needed.
 */
internal object NotifPrefs {
    private const val FILE = "noop_notif_prefs"
    const val MASTER = "notif.masterEnabled"
    /** Catch-all: buzz for any app NOT in the curated catalog (Android can't enumerate installed
     *  apps, so this is how a user covers BeReal/etc. that aren't listed). Opt-in, default OFF. */
    const val ALL_OTHER = "notif.allOtherApps"
    const val WORN = "notif.onlyWhenWorn"
    const val QUIET = "notif.quietHoursEnabled"
    const val QUIET_START = "notif.quietStartMinutes"
    const val QUIET_END = "notif.quietEndMinutes"
    const val CALLS_MASTER = "notif.calls.masterEnabled"
    const val CALLS_PHONE = "notif.calls.phoneEnabled"
    const val CALLS_VOIP = "notif.calls.voipEnabled"
    const val CALLS_PATTERN = "notif.calls.pattern"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getBool(ctx: Context, key: String, default: Boolean) =
        prefs(ctx).getBoolean(key, default)

    fun setBool(ctx: Context, key: String, value: Boolean) =
        prefs(ctx).edit().putBoolean(key, value).apply()

    fun getInt(ctx: Context, key: String, default: Int) =
        prefs(ctx).getInt(key, default)

    fun setInt(ctx: Context, key: String, value: Int) =
        prefs(ctx).edit().putInt(key, value).apply()

    fun appEnabled(ctx: Context, id: String): Boolean =
        prefs(ctx).getBoolean("app.$id.enabled", false) // opt-in, default OFF

    fun setAppEnabled(ctx: Context, id: String, value: Boolean) =
        prefs(ctx).edit().putBoolean("app.$id.enabled", value).apply()

    fun appPattern(ctx: Context, app: NotifApp): BuzzPattern {
        val name = prefs(ctx).getString("app.${app.id}.pattern", null)
        return BuzzPattern.entries.firstOrNull { it.name == name } ?: app.category.defaultPattern
    }

    fun setAppPattern(ctx: Context, id: String, pattern: BuzzPattern) =
        prefs(ctx).edit().putString("app.$id.pattern", pattern.name).apply()

    /** Buzz loop-count for [pkg] (for the notification listener; no NotifApp needed). Defaults to
     *  Double if no per-app pattern was chosen. */
    fun appLoops(ctx: Context, pkg: String): Int {
        val name = prefs(ctx).getString("app.$pkg.pattern", null)
        return BuzzPattern.entries.firstOrNull { it.name == name }?.loops ?: BuzzPattern.Double.loops
    }

    fun callPattern(ctx: Context): BuzzPattern {
        val name = prefs(ctx).getString(CALLS_PATTERN, null)
        return BuzzPattern.entries.firstOrNull { it.name == name } ?: BuzzPattern.Triple
    }

    fun setCallPattern(ctx: Context, pattern: BuzzPattern) =
        prefs(ctx).edit().putString(CALLS_PATTERN, pattern.name).apply()

    fun callLoops(ctx: Context): Int = callPattern(ctx).loops

    fun inQuietHours(ctx: Context): Boolean {
        if (!getBool(ctx, QUIET, false)) return false
        val start = getInt(ctx, QUIET_START, 22 * 60)
        val end = getInt(ctx, QUIET_END, 7 * 60)
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Quiet window may wrap midnight (e.g. 22:00 -> 07:00).
        return if (start <= end) now in start until end else (now >= start || now < end)
    }
}

// MARK: - Screen

@Composable
fun NotificationsSettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()

    // Header settings, seeded from prefs once and written through on change.
    var masterEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.MASTER, false)) }
    var onlyWhenWorn by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.WORN, true)) }
    var allOtherApps by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.ALL_OTHER, false)) }
    var quietHoursEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.QUIET, false)) }
    var quietStartMinutes by remember { mutableStateOf(NotifPrefs.getInt(context, NotifPrefs.QUIET_START, 22 * 60)) }
    var quietEndMinutes by remember { mutableStateOf(NotifPrefs.getInt(context, NotifPrefs.QUIET_END, 7 * 60)) }
    var callsEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.CALLS_MASTER, false)) }
    var phoneCallsEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.CALLS_PHONE, false)) }
    var voipCallsEnabled by remember { mutableStateOf(NotifPrefs.getBool(context, NotifPrefs.CALLS_VOIP, false)) }
    var callsPattern by remember { mutableStateOf(NotifPrefs.callPattern(context)) }
    // Scheduled report notifications — opt-in, default OFF. SharedPreferences isn't reactive, so
    // each Switch mirrors into local state and writes straight through to NoopPrefs.
    var morningReport by remember { mutableStateOf(NoopPrefs.morningReportEnabled(context)) }
    var postWorkoutReport by remember { mutableStateOf(NoopPrefs.postWorkoutReportEnabled(context)) }
    var phonePermissionDenied by remember { mutableStateOf(false) }
    val phonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        phoneCallsEnabled = granted
        phonePermissionDenied = !granted
        NotifPrefs.setBool(context, NotifPrefs.CALLS_PHONE, granted)
    }

    // Per-app enabled state, seeded from prefs so the UI is reactive within the session.
    val enabledState: SnapshotStateMap<String, Boolean> = remember {
        mutableStateMapOf<String, Boolean>().apply {
            notifCatalog.forEach { put(it.id, NotifPrefs.appEnabled(context, it.id)) }
        }
    }
    val patternState: SnapshotStateMap<String, BuzzPattern> = remember {
        mutableStateMapOf<String, BuzzPattern>().apply {
            notifCatalog.forEach { put(it.id, NotifPrefs.appPattern(context, it)) }
        }
    }
    val enabledCount = enabledState.values.count { it }

    ScreenScaffold(
        title = stringResource(R.string.nav_notifications),
        subtitle = stringResource(R.string.notifications_subtitle),
    ) {
        // MARK: Master card
        AlertSection(
            icon = Icons.Filled.NotificationsActive,
            title = stringResource(R.string.notifications_wrist_alerts),
            blurb = stringResource(R.string.notifications_wrist_alerts_blurb),
        ) {
            val enableLabel = stringResource(R.string.notifications_enable)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(enableLabel, style = NoopType.body, color = Palette.textPrimary)
                Spacer(Modifier.weight(1f))
                NoopSwitch(
                    checked = masterEnabled,
                    onChange = {
                        masterEnabled = it
                        NotifPrefs.setBool(context, NotifPrefs.MASTER, it)
                    },
                    label = enableLabel,
                )
            }

            // Flows so the pills and the action each keep their own width: a long strap state pushes
            // the button onto the next line instead of squeezing its label.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                verticalArrangement = Arrangement.spacedBy(Metrics.space10),
            ) {
                StatePill(stringResource(strapPillTitle(live)), tone = strapPillTone(live), pulsing = live.connected)
                StatePill(
                    pluralStringResource(R.plurals.notifications_apps_on, enabledCount, enabledCount),
                    tone = if (enabledCount > 0) StrandTone.Positive else StrandTone.Neutral,
                    showsDot = false,
                )
                PillButton(
                    label = stringResource(R.string.notifications_test_buzz),
                    icon = Icons.Filled.GraphicEq,
                    enabled = live.bonded,
                    onClick = { vm.buzz(loops = 2) },
                )
            }

            DeliveryNote()
        }

        CallsCard(
            masterEnabled = masterEnabled,
            callsEnabled = callsEnabled,
            phoneCallsEnabled = phoneCallsEnabled,
            voipCallsEnabled = voipCallsEnabled,
            pattern = callsPattern,
            bonded = live.bonded,
            permissionDenied = phonePermissionDenied,
            onCallsEnabled = {
                callsEnabled = it
                NotifPrefs.setBool(context, NotifPrefs.CALLS_MASTER, it)
                if (!it) CallAlertController.stopAll()
            },
            onPhoneCallsEnabled = { value ->
                if (!value) {
                    phoneCallsEnabled = false
                    phonePermissionDenied = false
                    NotifPrefs.setBool(context, NotifPrefs.CALLS_PHONE, false)
                    CallAlertController.stopSource(CallAlertSource.PHONE)
                    return@CallsCard
                }
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_PHONE_STATE,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    phoneCallsEnabled = true
                    phonePermissionDenied = false
                    NotifPrefs.setBool(context, NotifPrefs.CALLS_PHONE, true)
                } else {
                    phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                }
            },
            onVoipCallsEnabled = {
                voipCallsEnabled = it
                NotifPrefs.setBool(context, NotifPrefs.CALLS_VOIP, it)
                if (!it) CallAlertController.stopSource(CallAlertSource.VOIP)
            },
            onPattern = {
                callsPattern = it
                NotifPrefs.setCallPattern(context, it)
            },
            onTest = { vm.buzz(loops = callsPattern.loops) },
        )

        // MARK: Category cards
        activeCategories.forEach { cat ->
            CategoryCard(
                category = cat,
                apps = appsIn(cat),
                masterEnabled = masterEnabled,
                bonded = live.bonded,
                enabledState = enabledState,
                patternState = patternState,
                onToggle = { app, value ->
                    enabledState[app.id] = value
                    NotifPrefs.setAppEnabled(context, app.id, value)
                },
                onPattern = { app, pattern ->
                    patternState[app.id] = pattern
                    NotifPrefs.setAppPattern(context, app.id, pattern)
                },
                onTest = { app -> vm.buzz(loops = (patternState[app.id] ?: app.category.defaultPattern).loops) },
            )
        }

        // MARK: Behaviour card
        AlertSection(
            icon = Icons.Filled.Tune,
            title = stringResource(R.string.notifications_behaviour),
            blurb = stringResource(R.string.notifications_behaviour_blurb),
        ) {
            FormToggleRow(
                label = stringResource(R.string.notifications_only_when_worn),
                help = stringResource(R.string.notifications_only_when_worn_help),
                checked = onlyWhenWorn,
                onChange = {
                    onlyWhenWorn = it
                    NotifPrefs.setBool(context, NotifPrefs.WORN, it)
                },
            )
            RowDivider()
            FormToggleRow(
                label = stringResource(R.string.notifications_all_other_apps),
                help = stringResource(R.string.notifications_all_other_apps_help),
                checked = allOtherApps,
                onChange = {
                    allOtherApps = it
                    NotifPrefs.setBool(context, NotifPrefs.ALL_OTHER, it)
                },
            )
            RowDivider()
            FormToggleRow(
                label = stringResource(R.string.notifications_quiet_hours),
                help = stringResource(R.string.notifications_quiet_hours_help),
                checked = quietHoursEnabled,
                onChange = {
                    quietHoursEnabled = it
                    NotifPrefs.setBool(context, NotifPrefs.QUIET, it)
                },
            )
            if (quietHoursEnabled) {
                RowDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
                ) {
                    Text(stringResource(R.string.notifications_from), style = NoopType.body, color = Palette.textPrimary)
                    TimeChip(
                        minutes = quietStartMinutes,
                        accessibilityLabel = stringResource(R.string.notifications_quiet_start),
                        onPicked = {
                            quietStartMinutes = it
                            NotifPrefs.setInt(context, NotifPrefs.QUIET_START, it)
                        },
                    )
                    Text(stringResource(R.string.notifications_to), style = NoopType.body, color = Palette.textSecondary)
                    TimeChip(
                        minutes = quietEndMinutes,
                        accessibilityLabel = stringResource(R.string.notifications_quiet_end),
                        onPicked = {
                            quietEndMinutes = it
                            NotifPrefs.setInt(context, NotifPrefs.QUIET_END, it)
                        },
                    )
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        // MARK: Daily reports — phone notifications, not wrist buzzes. Opt-in, default OFF, no AI.
        AlertSection(
            icon = Icons.Filled.NotificationsActive,
            title = stringResource(R.string.notifications_daily_reports),
            blurb = stringResource(R.string.notifications_daily_reports_blurb),
        ) {
            FormToggleRow(
                label = stringResource(R.string.notifications_morning_recap),
                help = stringResource(R.string.notifications_morning_recap_help),
                checked = morningReport,
                onChange = {
                    morningReport = it
                    NoopPrefs.setMorningReportEnabled(context, it)
                },
            )
            RowDivider()
            FormToggleRow(
                label = stringResource(R.string.notifications_post_workout),
                help = stringResource(R.string.notifications_post_workout_help),
                checked = postWorkoutReport,
                onChange = {
                    postWorkoutReport = it
                    NoopPrefs.setPostWorkoutReportEnabled(context, it)
                    // Seed the frontier to the newest existing workout when turning ON, so enabling it
                    // doesn't immediately fire a summary for a session already in history.
                    if (it) vm.seedWorkoutReportFrontier()
                },
            )
        }
    }
}

// MARK: - Strap status (mirrors the three-state mapping from the Mac screen)

@StringRes
private fun strapPillTitle(live: com.noop.ble.LiveState): Int = when {
    live.connected -> R.string.notifications_strap_connected
    live.bonded -> R.string.notifications_strap_idle
    else -> R.string.notifications_strap_not_connected
}

private fun strapPillTone(live: com.noop.ble.LiveState): StrandTone = when {
    live.connected -> StrandTone.Positive
    live.bonded -> StrandTone.Warning
    else -> StrandTone.Critical
}

@Composable
private fun CallsCard(
    masterEnabled: Boolean,
    callsEnabled: Boolean,
    phoneCallsEnabled: Boolean,
    voipCallsEnabled: Boolean,
    pattern: BuzzPattern,
    bonded: Boolean,
    permissionDenied: Boolean,
    onCallsEnabled: (Boolean) -> Unit,
    onPhoneCallsEnabled: (Boolean) -> Unit,
    onVoipCallsEnabled: (Boolean) -> Unit,
    onPattern: (BuzzPattern) -> Unit,
    onTest: () -> Unit,
) {
    val contentAlpha = if (masterEnabled) 1f else Palette.disabledOpacity
    AlertSection(
        icon = Icons.Filled.Call,
        title = stringResource(R.string.notifications_calls),
        blurb = stringResource(R.string.notifications_calls_blurb),
    ) {
        val callsTarget = stringResource(R.string.notifications_calls_target)
        val buzzCallsLabel = stringResource(R.string.notifications_buzz_on_calls)
        Column(modifier = Modifier.alphaIf(contentAlpha)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                    Text(buzzCallsLabel, style = NoopType.body, color = Palette.textPrimary)
                    Text(
                        stringResource(R.string.notifications_buzz_on_calls_help),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                if (callsEnabled) {
                    PatternMenu(pattern = pattern, enabled = masterEnabled, appName = callsTarget, onSelect = onPattern)
                    TestIconButton(enabled = masterEnabled && bonded, appName = callsTarget, onClick = onTest)
                }
                NoopSwitch(
                    checked = callsEnabled,
                    onChange = onCallsEnabled,
                    enabled = masterEnabled,
                    label = buzzCallsLabel,
                )
            }
            if (callsEnabled) {
                RowDivider()
                FormToggleRow(
                    label = stringResource(R.string.notifications_phone_calls),
                    help = stringResource(R.string.notifications_phone_calls_help),
                    checked = phoneCallsEnabled,
                    enabled = masterEnabled,
                    onChange = onPhoneCallsEnabled,
                )
                if (permissionDenied) {
                    Text(
                        stringResource(R.string.notifications_phone_permission_denied),
                        style = NoopType.footnote,
                        color = Palette.statusCritical,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                }
                RowDivider()
                FormToggleRow(
                    label = stringResource(R.string.notifications_voip_calls),
                    help = stringResource(R.string.notifications_voip_calls_help),
                    checked = voipCallsEnabled,
                    enabled = masterEnabled,
                    onChange = onVoipCallsEnabled,
                )
            }
        }
    }
}

// MARK: - Delivery note (Notification Access requirement + deep link)

@Composable
private fun DeliveryNote() {
    val context = LocalContext.current
    val openAccessCd = stringResource(R.string.notifications_open_access_cd)
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(Metrics.divider, Palette.accent.copy(alpha = 0.22f), shape)
            .padding(Metrics.space12),
        verticalArrangement = Arrangement.spacedBy(Metrics.space10),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.notifications_delivery_note),
                style = NoopType.footnote,
                color = Palette.textSecondary,
            )
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                .padding(horizontal = Metrics.space2, vertical = 2.dp)
                .semantics { contentDescription = openAccessCd },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(14.dp),
            )
            Text(stringResource(R.string.notifications_open_access), style = NoopType.caption, color = Palette.accent)
        }
    }
}

// MARK: - Category card (rows of apps, dimmed/disabled when master is off)

@Composable
private fun CategoryCard(
    category: NotifCategory,
    apps: List<NotifApp>,
    masterEnabled: Boolean,
    bonded: Boolean,
    enabledState: SnapshotStateMap<String, Boolean>,
    patternState: SnapshotStateMap<String, BuzzPattern>,
    onToggle: (NotifApp, Boolean) -> Unit,
    onPattern: (NotifApp, BuzzPattern) -> Unit,
    onTest: (NotifApp) -> Unit,
) {
    val contentAlpha = if (masterEnabled) 1f else Palette.disabledOpacity
    AlertSection(icon = category.icon, title = stringResource(category.title)) {
        Column(modifier = Modifier.alphaIf(contentAlpha)) {
            apps.forEachIndexed { idx, app ->
                AppRow(
                    app = app,
                    enabled = enabledState[app.id] ?: false,
                    pattern = patternState[app.id] ?: app.category.defaultPattern,
                    interactive = masterEnabled,
                    bonded = bonded,
                    onToggle = { onToggle(app, it) },
                    onPattern = { onPattern(app, it) },
                    onTest = { onTest(app) },
                )
                if (idx < apps.size - 1) RowDivider()
            }
        }
    }
}

@Composable
private fun AppRow(
    app: NotifApp,
    enabled: Boolean,
    pattern: BuzzPattern,
    interactive: Boolean,
    bonded: Boolean,
    onToggle: (Boolean) -> Unit,
    onPattern: (BuzzPattern) -> Unit,
    onTest: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            // An enabled app reads as a selected row: a soft accentMuted wash behind it.
            .clip(RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.background(Palette.accentMuted) else Modifier)
            .padding(horizontal = Metrics.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        // App glyph in a rounded inset tile (a stand-in: the real app icon is not resolvable here).
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.surfaceInset),
            contentAlignment = Alignment.Center,
        ) {
            Icon(app.glyph, contentDescription = null, tint = Palette.textSecondary, modifier = Modifier.size(Metrics.iconSmall))
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(app.name, style = NoopType.body, color = Palette.textPrimary)
            Text(
                if (enabled) stringResource(R.string.notifications_app_on) else stringResource(R.string.notifications_app_off),
                style = NoopType.footnote,
                color = if (enabled) Palette.accent else Palette.textTertiary,
            )
        }

        if (enabled) {
            PatternMenu(
                pattern = pattern,
                enabled = interactive,
                appName = app.name,
                onSelect = onPattern,
            )
            TestIconButton(enabled = interactive && bonded, appName = app.name, onClick = onTest)
        }

        NoopSwitch(
            checked = enabled,
            onChange = onToggle,
            enabled = interactive,
            label = stringResource(R.string.notifications_app_wrist_alerts, app.name),
        )
    }
}

// MARK: - Pattern menu

@Composable
private fun PatternMenu(
    pattern: BuzzPattern,
    enabled: Boolean,
    appName: String,
    onSelect: (BuzzPattern) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val patternCd = stringResource(R.string.notifications_pattern_for, appName)
    val shape = RoundedCornerShape(50)
    Box {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(Palette.surfaceInset)
                .border(Metrics.divider, Palette.hairline, shape)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = Metrics.space10, vertical = 5.dp)
                .semantics { contentDescription = patternCd },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = Palette.textSecondary,
                modifier = Modifier.size(12.dp),
            )
            Text(stringResource(pattern.label), style = NoopType.caption, color = Palette.textSecondary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Palette.surfaceOverlay),
        ) {
            BuzzPattern.entries.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(p.label),
                            style = NoopType.body,
                            color = if (p == pattern) Palette.accent else Palette.textPrimary,
                        )
                    },
                    onClick = {
                        onSelect(p)
                        expanded = false
                    },
                )
            }
        }
    }
}

// MARK: - Test buttons

@Composable
private fun TestIconButton(enabled: Boolean, appName: String, onClick: () -> Unit) {
    val testCd = stringResource(R.string.notifications_test_buzz_for, appName)
    val shape = RoundedCornerShape(8.dp)
    val tint = if (enabled) Palette.accent else Palette.textTertiary
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .background(Palette.accent.copy(alpha = if (enabled) 0.12f else 0.04f))
            .border(Metrics.divider, tint.copy(alpha = 0.30f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = testCd },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun PillButton(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val tint = if (enabled) Palette.accent else Palette.textTertiary
    Row(
        modifier = Modifier
            .clip(shape)
            .background(Palette.accent.copy(alpha = if (enabled) 0.12f else 0.04f))
            .border(Metrics.divider, tint.copy(alpha = 0.30f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Metrics.space12, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(label, style = NoopType.caption, color = tint, maxLines = 1)
    }
}

// MARK: - Time chip (TimePickerDialog → HH:mm). Reused by the Automations smart-alarm time too.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeChip(
    minutes: Int,
    accessibilityLabel: String,
    onPicked: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val hour = minutes / 60
    val minute = minutes % 60
    Text(
        text = "%02d:%02d".format(hour, minute),
        style = NoopType.number(15f),
        color = Palette.accent,
        modifier = Modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(Metrics.divider, Palette.hairline, shape)
            .clickable { showPicker = true }
            .padding(horizontal = Metrics.space12, vertical = 6.dp)
            .semantics { contentDescription = accessibilityLabel },
    )

    if (showPicker) {
        // Material3 1.2.x has TimePicker + rememberTimePickerState but not a packaged
        // TimePickerDialog, so we wrap the picker in a plain Dialog ourselves.
        val state = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        Dialog(onDismissRequest = { showPicker = false }) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.surfaceOverlay)
                    .border(Metrics.divider, Palette.hairline, RoundedCornerShape(20.dp))
                    .padding(Metrics.screenRowSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Metrics.space16),
            ) {
                Text(accessibilityLabel, style = NoopType.headline, color = Palette.textPrimary)
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Palette.surfaceInset,
                        clockDialSelectedContentColor = Palette.surfaceBase,
                        clockDialUnselectedContentColor = Palette.textPrimary,
                        selectorColor = Palette.accent,
                        periodSelectorBorderColor = Palette.hairline,
                        timeSelectorSelectedContainerColor = Palette.accentMuted,
                        timeSelectorUnselectedContainerColor = Palette.surfaceInset,
                        timeSelectorSelectedContentColor = Palette.accent,
                        timeSelectorUnselectedContentColor = Palette.textPrimary,
                    ),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    Text(
                        stringResource(R.string.common_cancel),
                        style = NoopType.body,
                        color = Palette.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { showPicker = false }
                            .padding(horizontal = Metrics.space16, vertical = 8.dp),
                    )
                    Text(
                        stringResource(R.string.notifications_set),
                        style = NoopType.body,
                        color = Palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable {
                                onPicked(state.hour * 60 + state.minute)
                                showPicker = false
                            }
                            .padding(horizontal = Metrics.space16, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

// MARK: - Section card (icon + title header, optional blurb, content)

@Composable
private fun AlertSection(
    icon: ImageVector,
    title: String,
    blurb: String? = null,
    overline: String = stringResource(R.string.notifications_overline_alerts),
    content: @Composable () -> Unit,
) {
    NoopCard(padding = 20.dp, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space16)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Overline(overline)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                    Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(Metrics.iconSmall))
                    Text(title, style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            if (blurb != null) {
                Text(blurb, style = NoopType.subhead, color = Palette.textSecondary)
            }
            content()
        }
    }
}

// MARK: - Label + help + switch row (mirrors FormToggleRow)

@Composable
private fun FormToggleRow(
    label: String,
    help: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(label, style = NoopType.body, color = Palette.textPrimary)
            Text(help, style = NoopType.footnote, color = Palette.textTertiary)
        }
        Spacer(Modifier.width(Metrics.space16))
        NoopSwitch(checked = checked, onChange = onChange, enabled = enabled, label = label)
    }
}

// MARK: - Shared bits

@Composable
private fun NoopSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    label: String,
) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Palette.surfaceBase,
            checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.textSecondary,
            uncheckedTrackColor = Palette.surfaceInset,
            uncheckedBorderColor = Palette.hairline,
        ),
        modifier = Modifier.semantics { contentDescription = label },
    )
}


/** Apply a uniform alpha to a subtree (dims disabled category content). */
private fun Modifier.alphaIf(value: Float): Modifier = this.alpha(value)

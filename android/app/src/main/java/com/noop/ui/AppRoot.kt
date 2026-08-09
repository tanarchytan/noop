package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.noop.R
import com.noop.analytics.FusionSource
import com.noop.ui.whoop.WhoopHealthMonitorScreen
import com.noop.ui.whoop.WhoopHealthScreen
import com.noop.ui.whoop.WhoopHomeScreen
import com.noop.ui.whoop.WhoopMoreScreen
import com.noop.ui.whoop.WhoopProfileScreen
import com.noop.ui.whoop.WhoopRecoveryScreen
import com.noop.ui.whoop.WhoopStrainScreen
import com.noop.ui.whoop.WhoopStressMonitorScreen
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

// MARK: - Navigation model
//
// The macOS app's sidebar holds many sections; on Android (mirroring the iOS RootTabView) we surface
// them through a unified floating "glass" bottom bar (Home · Health · Sleep · More) for the everyday
// screens, with a "More" sheet that lists the full grouped set — so every destination is one tap away
// without a global hamburger/drawer. Destinations are grouped exactly as the sidebar groups them.
// Routes whose screens belong to later waves point at a ComingSoon placeholder so the app compiles today.

/** A single drawer destination: stable route, display title (localized via [titleRes]), sidebar icon. */
internal enum class Destination(
    val route: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    // Group: Home
    Today("today", R.string.nav_today, Icons.Filled.Home),
    Intelligence("intelligence", R.string.nav_intelligence, Icons.Filled.Psychology),
    // Optional, default-OFF : the Coupled view (WHOOP-style day read). Home is no longer a door to
    // it and it sits in no [DrawerGroup], so nothing reaches this route today.
    CoupledView("coupled_view", R.string.nav_coupled_view, Icons.Filled.Hexagon),
    // The two Home hero-ring drill-ins (Charge and Effort). Pushed from the Home rings only, so like
    // [CoupledView] they are deliberately NOT in any More-page group.
    RecoveryDetail("recovery_detail", R.string.nav_recovery, Icons.Filled.HealthAndSafety),
    StrainDetail("strain_detail", R.string.nav_strain, Icons.Filled.Bolt),

    // Group: Intervals (the standalone Live entry was removed in the Live/Health fold — its live HR /
    // physiology folded into Health, its workout controls into Workouts).
    Intervals("intervals", R.string.nav_intervals, Icons.Filled.Timeline),

    // Group: Recovery
    Sleep("sleep", R.string.nav_sleep, Icons.Filled.Bedtime),
    Breathe("breathe", R.string.nav_breathe, Icons.Filled.Air),
    Stress("stress", R.string.nav_stress, Icons.Filled.Spa),

    // Group: Activity
    Workouts("workouts", R.string.nav_workouts, Icons.Filled.FitnessCenter),
    Trends("trends", R.string.nav_trends, Icons.AutoMirrored.Filled.TrendingUp),

    // Group: Insight
    Coach("coach", R.string.nav_coach, Icons.Filled.AutoAwesome),
    InsightsHub("insights_hub", R.string.nav_insights_hub, Icons.Filled.Insights),
    Insights("insights", R.string.nav_insights, Icons.Filled.Insights),
    Explore("explore", R.string.nav_explore, Icons.Filled.Explore),
    Compare("compare", R.string.nav_compare, Icons.AutoMirrored.Filled.CompareArrows),

    // Group: Health
    Health("health", R.string.nav_health, Icons.Filled.MonitorHeart),
    Hydration("hydration", R.string.nav_hydration, Icons.Filled.WaterDrop),
    VitalSignsDetail("vital_detail/{key}", R.string.nav_vital_signs, Icons.Filled.HealthAndSafety),
    // The live-HR + vitals page, pushed from the Health summary rather than listed on the More page.
    HealthMonitor("health_monitor", R.string.nav_vital_signs, Icons.Filled.MonitorHeart),
    AppleHealth("apple_health", R.string.nav_apple_health, Icons.Filled.HealthAndSafety),

    // Group: System
    Automations("automations", R.string.nav_automations, Icons.Filled.Bolt),
    // "Alarms" is the ONE alarm surface : the phone-based Wake Window (light-sleep detection with a
    // guaranteed OS backup), the strap's own firmware wake-alarm, and the wind-down reminder, all in one
    // place. Previously "Wake Window" , but the strap alarm moved in from Automations so the broader
    // name fits. Route id stays "smart_alarm" (display string only).
    SmartAlarm("smart_alarm", R.string.nav_alarms, Icons.Filled.Alarm),
    Devices("devices", R.string.nav_devices, Icons.Filled.Sensors),
    DataSources("data_sources", R.string.nav_data_sources, Icons.Filled.Storage),
    BackupSync("backup_sync", R.string.nav_backup_sync, Icons.Filled.CloudSync),
    FusedRecord("fused_record", R.string.nav_fused_record, Icons.AutoMirrored.Filled.CompareArrows),
    Notifications("notifications", R.string.nav_notifications, Icons.Filled.Notifications),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
    TestCentre("test_centre", R.string.nav_test_centre, Icons.Filled.BugReport),
    // Minimal About (name + version + GitHub link), listed in the More page's "App" group.
    About("about", R.string.nav_about, Icons.Filled.Info),

    // Profile menu (body profile + units) — reached ONLY from the Today header's leading avatar, so
    // like [CoupledView] it is deliberately NOT listed on the More page.
    ProfileMenu("profile_menu", R.string.nav_profile, Icons.Filled.Person),
    // The body-profile + units editor, pushed from the Profile page own row.
    BodyProfile("body_profile", R.string.nav_profile, Icons.Filled.Person),

    // The "More" tab: its own navigated page (mirroring the iOS More tab) that hosts the full
    // grouped destination list. It is NOT itself one of those rows — it is the door to them.
    More("more", R.string.nav_more, Icons.Filled.MoreHoriz);

    companion object {
        /** Resolve the destination owning the current back-stack route (defaults to Today). */
        fun forRoute(route: String?): Destination =
            entries.firstOrNull {
                // Match parameterised routes (e.g. "vital_detail/rhr" vs "vital_detail/{key}") by
                // base path so the top-bar title resolves correctly on a detail screen, not "Today".
                it.route == route || it.route.substringBefore('/') == route?.substringBefore('/')
            } ?: Today
    }
}

/**
 * App shell: a single [Scaffold] with a floating [GlassBottomBar] (Home · Health · Sleep · More)
 * driving one [NavHost], mirroring the iOS RootTabView. There is NO global toolbar and no nav drawer
 * — every screen self-titles via [ScreenScaffold], and the "More" sheet (opened from the bar) reaches
 * every destination the bar does not carry. A single [AppViewModel] is created here and
 * shared with every screen, so the BLE connection and cached metrics stay app-wide singletons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val nav = rememberNavController()

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val current = Destination.forRoute(currentRoute)
    var showQuickActions by remember { mutableStateOf(false) }
    // The Updates inbox sheet (opened by the Today header bell). The store is a process singleton so
    // the Today cards and the import path post to the same inbox this sheet renders.
    val context = androidx.compose.ui.platform.LocalContext.current
    val updateStore = remember { UpdateStore.from(context) }
    var showUpdatesInbox by remember { mutableStateOf(false) }

    run {
        Scaffold(
            containerColor = Palette.surfaceBase,
            bottomBar = {
                // One unified "glass" bar: four evenly-spaced tabs — Home · Health · Sleep · More
                // (matches the iOS FloatingTabBar). The quick-action "+" lives in the Today header's
                // top-right (balancing the avatar), so the bar is clean tabs only. "More" navigates to
                // its own page (mirroring the iOS More tab) that reaches every grouped destination, so no
                // destination is lost without the drawer.
                GlassBottomBar(
                    current = current,
                    onTabSelected = { dest ->
                        if (dest.route != currentRoute) nav.navigateTopLevel(dest.route)
                    },
                )
            },
        ) { inner ->
            NavHost(
                navController = nav,
                startDestination = Destination.Today.route,
                modifier = Modifier.padding(inner),
                // Motion: top-level destinations crossfade (~240ms) on the calm,
                // decelerating global easing — nothing slides or bounces between tabs. The
                // same fade is used for back (pop) so the bar never feels jerky. Drill-ins
                // (e.g. vital_detail) are pushed by the same NavHost, so they inherit the
                // same restrained crossfade rather than a hard cut.
                enterTransition = { fadeIn(navFadeSpec) },
                exitTransition = { fadeOut(navFadeSpec) },
                popEnterTransition = { fadeIn(navFadeSpec) },
                popExitTransition = { fadeOut(navFadeSpec) },
            ) {
                // --- Live, working screens (existing waves) ---
                composable(Destination.Today.route) {
                    WhoopHomeScreen(
                        viewModel = viewModel,
                        // The quick-action disc beside the "My Day" heading opens the same sheet the bar used to.
                        onQuickActions = { showQuickActions = true },
                        onOpenProfile = { nav.navigateTopLevel(Destination.ProfileMenu.route) },
                        // The header strap chip taps through to Devices.
                        onOpenDevices = { nav.navigateTopLevel(Destination.Devices.route) },
                        // The monitor tiles and dashboard rows push their own detail; Sleep is a tab switch.
                        onOpenHealth = { nav.navigate(Destination.Health.route) },
                        onOpenStress = { nav.navigate(Destination.Stress.route) },
                        onOpenSleep = { nav.navigateTopLevel(Destination.Sleep.route) },
                        // Raise the one-shot WorkoutStartSection consumes (it only fires while a workout is
                        // actually running), then route to Workouts.
                        onOpenWorkouts = {
                            viewModel.openActiveWorkout()
                            nav.navigateTopLevel(Destination.Workouts.route)
                        },
                        onOpenJournal = { nav.navigateTopLevel(Destination.Insights.route) },
                        onOpenAlarm = { nav.navigate(Destination.SmartAlarm.route) },
                        onOpenTrends = { nav.navigate(Destination.Trends.route) },
                        // Every metric row opens its OWN focused detail trend (vital_detail/<key>).
                        onOpenMetric = { key -> nav.navigate("vital_detail/$key") },
                        // The Charge and Effort hero rings drill into their detail pages.
                        onOpenRecovery = { nav.navigate(Destination.RecoveryDetail.route) },
                        onOpenStrain = { nav.navigate(Destination.StrainDetail.route) },
                    )
                }
                composable(Destination.RecoveryDetail.route) {
                    WhoopRecoveryScreen(
                        vm = viewModel,
                        onOpenTrends = { nav.navigate(Destination.Trends.route) },
                        onOpenVital = { nav.navigate("vital_detail/$it") },
                        onOpenSleep = { nav.navigateTopLevel(Destination.Sleep.route) },
                    )
                }
                composable(Destination.StrainDetail.route) {
                    WhoopStrainScreen(
                        vm = viewModel,
                        onOpenStrainHistory = { nav.navigate(Destination.Explore.route) },
                    )
                }
                composable(Destination.Sleep.route) {
                    SleepNightScreen(
                        vm = viewModel,
                        onOpenJournal = { nav.navigateTopLevel(Destination.Insights.route) },
                    )
                }
                composable(Destination.CoupledView.route) {
                    CoupledScreen(
                        vm = viewModel,
                        // Tapping Sleep in the coupled read opens the full Sleep screen (iOS parity).
                        onOpenSleep = { nav.navigateTopLevel(Destination.Sleep.route) },
                    )
                }
                composable(Destination.Intervals.route) { IntervalsScreen(viewModel) }
                composable(Destination.Breathe.route) { BreatheScreen(viewModel) }
                composable(Destination.Coach.route) { CoachScreen() }
                composable(Destination.Explore.route) { TrendsExploreScreen(viewModel) }
                composable(Destination.Automations.route) { AutomationsScreen(viewModel) }
                composable(Destination.SmartAlarm.route) { SmartAlarmScreen(viewModel) }
                composable(Destination.Workouts.route) { WorkoutsScreen(viewModel) }
                composable(Destination.Intelligence.route) { IntelligenceScreen(viewModel) }

                // --- Placeholder routes (later waves fill these in) ---
                composable(Destination.Stress.route) {
                    WhoopStressMonitorScreen(
                        vm = viewModel,
                        onBreathe = { nav.navigateTopLevel(Destination.Breathe.route) },
                    )
                }
                composable(Destination.Trends.route) { TrendsScreen(viewModel) }
                // "What Moves You" (InsightsHub) is folded into Insights: no longer a top-level More-page row,
                // reached from the Insights screen's WhatMovesYouLink as a sub-page (push, back → Insights).
                composable(Destination.Insights.route) { InsightsScreen(viewModel, onOpenInsightsHub = { nav.navigate(Destination.InsightsHub.route) }) }
                composable(Destination.Compare.route) { CompareScreen(viewModel) }
                composable(Destination.Health.route) {
                    WhoopHealthScreen(
                        vm = viewModel,
                        onOpenHealthMonitor = { nav.navigate(Destination.HealthMonitor.route) },
                        onOpenStressMonitor = { nav.navigate(Destination.Stress.route) },
                    )
                }
                composable(Destination.HealthMonitor.route) {
                    WhoopHealthMonitorScreen(
                        vm = viewModel,
                        onVitalClick = { nav.navigate("vital_detail/$it") },
                    )
                }
                composable(Destination.Hydration.route) { HydrationScreen(viewModel) }
                composable(Destination.VitalSignsDetail.route) { backStackEntry ->
                    VitalDetailScreen(
                        vm = viewModel,
                        key = backStackEntry.arguments?.getString("key").orEmpty(),
                    )
                }
                // --- v5 pillar screens (Wave 3 wiring) ---
                composable(Destination.InsightsHub.route) { InsightsHubScreen(viewModel) }
                composable(Destination.FusedRecord.route) { FusedRecordRoute(viewModel) }
                composable(Destination.AppleHealth.route) { AppleHealthScreen(viewModel) }
                composable(Destination.Devices.route) {
                    DevicesScreen(
                        viewModel,
                        onUseFileImport = { nav.navigateTopLevel(Destination.DataSources.route) },
                    )
                }
                composable(Destination.DataSources.route) {
                    DataSourcesScreen(
                        viewModel,
                        onOpenAppleHealth = { nav.navigate(Destination.AppleHealth.route) },
                    )
                }
                composable(Destination.BackupSync.route) { BackupSyncScreen(viewModel.repo) }
                composable(Destination.Notifications.route) { NotificationsSettingsScreen(viewModel) }
                composable(Destination.Settings.route) {
                    SettingsScreen(viewModel)
                }
                composable(Destination.TestCentre.route) { TestCentreScreen(viewModel) }
                composable(Destination.About.route) { AboutScreen() }
                composable(Destination.ProfileMenu.route) {
                    WhoopProfileScreen(
                        vm = viewModel,
                        onOpenBodyProfile = { nav.navigate(Destination.BodyProfile.route) },
                    )
                }
                // The body-profile + units editor keeps its own route: the Profile page is its only door.
                composable(Destination.BodyProfile.route) { ProfileMenuScreen(viewModel) }
                // The "More" page — the iOS More tab's twin: a navigated ScreenScaffold page hosting the
                // full grouped destination list (was a pull-up sheet). A row navigates top-level.
                composable(Destination.More.route) {
                    WhoopMoreScreen(onNavigate = { route ->
                        if (route in tabRoutes) nav.navigateTopLevel(route) else nav.navigate(route)
                    })
                }
            }
        }

        // Quick-actions sheet, opened by the raised gold centre FAB. Each row routes to an
        // existing destination — nothing new is built here, the FAB is just a faster door in.
        if (showQuickActions) {
            ModalBottomSheet(
                onDismissRequest = { showQuickActions = false },
                containerColor = Palette.surfaceRaised,
                contentColor = Palette.textPrimary,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 24.dp),
                ) {
                    Overline(
                        "Quick actions",
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp),
                        color = Palette.textTertiary,
                    )
                    // Updates inbox — relocated here off the Today header (the liquid Today header mirrors iOS,
                    // which has no notifications bell). The feature is fully intact and one tap away: this row
                    // opens the same inbox sheet, showing the unread count as a trailing badge.
                    NavigationDrawerItem(
                        selected = false,
                        onClick = {
                            showQuickActions = false
                            showUpdatesInbox = true
                        },
                        icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                        label = { Text(stringResource(R.string.updates_title), style = NoopType.body) },
                        badge = {
                            val unread = updateStore.unreadCount
                            if (unread > 0) {
                                Text(
                                    if (unread > 99) "99+" else unread.toString(),
                                    style = NoopType.captionNumber,
                                    color = Palette.statusCritical,
                                )
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Palette.surfaceRaised,
                            unselectedIconColor = Palette.accent,
                            unselectedTextColor = Palette.textPrimary,
                        ),
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                    quickActions.forEach { action ->
                        NavigationDrawerItem(
                            selected = false,
                            onClick = {
                                showQuickActions = false
                                if (action.route != currentRoute) {
                                    nav.navigateTopLevel(action.route)
                                }
                            },
                            icon = { Icon(action.icon, contentDescription = null) },
                            label = { Text(stringResource(action.titleRes), style = NoopType.body) },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = Palette.surfaceRaised,
                                unselectedIconColor = Palette.accent,
                                unselectedTextColor = Palette.textPrimary,
                            ),
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            }
        }

        // The Updates inbox (opened by the Today header bell). Presented here so it has the nav for
        // deep-links — a row's "trends" key switches the bottom tab, mirroring the iOS NavRouter route.
        if (showUpdatesInbox) {
            ModalBottomSheet(
                onDismissRequest = { showUpdatesInbox = false },
                // Open full-height (no half-pull) so it reads like the iOS Updates sheet, and use the
                // BEIGE surfaceBase so the white NoopCards POP — surfaceRaised made white cards sit on a
                // white sheet (no contrast), which is why the Android inbox looked flat vs iOS.
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Palette.surfaceBase,
                contentColor = Palette.textPrimary,
            ) {
                UpdatesInboxScreen(
                    store = updateStore,
                    onClose = { showUpdatesInbox = false },
                    onDeepLink = { key ->
                        // Map the inbox deep-link key to a route (only known keys route). "trends" is
                        // the one real poster's target today; unknown keys just close the sheet.
                        val route = when (key) {
                            "trends" -> Destination.Trends.route
                            else -> null
                        }
                        if (route != null && route != currentRoute) nav.navigateTopLevel(route)
                    },
                    onRestore = { cardId ->
                        // Flip the shared dismissed flag back off so the card reappears, and signal a
                        // mounted Today to re-read it immediately (SharedPreferences isn't reactive).
                        TodayCardDismissal.setDismissed(context, cardId, false)
                        updateStore.restoreRequest = cardId
                    },
                )
            }
        }
    }
}

// MARK: - Glass bottom bar
//
// The signature bar, ported from iOS's FloatingTabBar: ONE rounded "glass" island holding four
// evenly-spaced inline slots — Home · Health · Sleep · More. The quick-action "+" now lives in the
// Today header's top-right (it left the bar to balance the avatar), so the bar is clean tabs only.
// The "glass" feel is a translucent raised surface with a low elevation and a subtle hairline border
// — frosted, not a hard opaque slab and not a glow. Each nav slot is an icon over a small label;
// active = gold accent, inactive = textSecondary. All routing is unchanged: the four tabs switch the
// same destinations.

/** A single bottom-bar nav slot: the destination it switches to, plus the bar-specific icon/label. */
private data class BarTab(val dest: Destination, val icon: ImageVector, @StringRes val labelRes: Int)

/**
 * Which bar tab a screen reads as. A drill-in belongs to the tab whose page pushed it — the Charge
 * and Effort rings live on Home, so Recovery and Strain are Home, not More.
 *
 * The previous rule was "anything that is not Today/Health/Sleep is More", which lit More while the
 * user stood on a screen More cannot reach: [Destination.RecoveryDetail] and
 * [Destination.StrainDetail] are pushed from the Home rings only and sit in no More group.
 * Everything genuinely reached through the More page still falls through to More.
 */
private fun barTabFor(dest: Destination): Destination = when (dest) {
    Destination.Today, Destination.RecoveryDetail, Destination.StrainDetail -> Destination.Today
    Destination.Health, Destination.HealthMonitor, Destination.VitalSignsDetail -> Destination.Health
    Destination.Sleep -> Destination.Sleep
    else -> Destination.More
}

/** The nav slots: Home · Health · Sleep · More.
 * More is special-cased (it opens the sheet rather than a route), so it is appended at the call site. */
private val barLeadingTabs = listOf(
    BarTab(Destination.Today, Icons.Outlined.GridView, R.string.nav_today),
    BarTab(Destination.Health, Icons.Filled.MonitorHeart, R.string.nav_health),
)
private val barTrailingTabs = listOf(
    BarTab(Destination.Sleep, Icons.Filled.Bedtime, R.string.nav_sleep),
)

@Composable
private fun GlassBottomBar(
    current: Destination,
    onTabSelected: (Destination) -> Unit,
) {
    val barShape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Clear the gesture-nav bar (home indicator) first, then add breathing room so the capsule
            // floats free of the bottom edge rather than jamming against it — iOS clears the home-indicator
            // safe area + 4pt; here navigationBarsPadding + 12dp gives the same lift.
            .navigationBarsPadding()
            .padding(horizontal = 22.dp)
            .padding(top = 4.dp, bottom = Metrics.space12),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = barShape,
            // "Glass": a translucent raised surface — a frosted island, not a hard slab. Compose has no
            // cheap blur, so translucency (≈0.80) + a hairline rim is the Liquid-Glass stand-in. A soft,
            // low drop shadow reads as floating without a glow.
            color = Palette.surfaceRaised.copy(alpha = 0.80f),
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                // Cap the width so the pill stays a centred floating island on tablets, not a full-bleed bar.
                .widthIn(max = 480.dp)
                .border(0.5.dp, Palette.hairline.copy(alpha = 0.6f), barShape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space2),
            ) {
                barLeadingTabs.forEach { tab ->
                    BarSlot(
                        icon = tab.icon,
                        label = stringResource(tab.labelRes),
                        active = barTabFor(current) == tab.dest,
                        modifier = Modifier.weight(1f),
                        onClick = { onTabSelected(tab.dest) },
                    )
                }
                barTrailingTabs.forEach { tab ->
                    BarSlot(
                        icon = tab.icon,
                        label = stringResource(tab.labelRes),
                        active = barTabFor(current) == tab.dest,
                        modifier = Modifier.weight(1f),
                        onClick = { onTabSelected(tab.dest) },
                    )
                }
                BarSlot(
                    icon = Icons.Filled.MoreHoriz,
                    label = stringResource(R.string.nav_more),
                    // Selected on the More page itself, and kept lit while the current screen is one
                    // reached THROUGH More — so drilling into a grouped destination still reads as
                    // "you're in More", never "nowhere". A drill-in owned by another tab (the Home
                    // rings' Recovery and Strain) reads as that tab instead; see [barTabFor].
                    active = barTabFor(current) == Destination.More,
                    modifier = Modifier.weight(1f),
                    onClick = { onTabSelected(Destination.More) },
                )
            }
        }
    }
}

/** One nav slot: an icon over a small label. Active = gold accent (semibold), inactive = textSecondary.
 * No selection pill, no glow — just the colour swap, matching the iOS bar. */
@Composable
private fun BarSlot(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (active) Palette.accent else Palette.textSecondary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 3.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Metrics.iconSmall))
        Text(
            label,
            style = NoopType.tabLabel.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = tint,
        )
    }
}

/** A centre-FAB quick action: a display title, an icon and the destination route it opens. */
private data class QuickAction(@StringRes val titleRes: Int, val icon: ImageVector, val route: String)

/** The quick actions on the gold centre FAB, each routing to an existing destination. Live HR leads —
 * after the Live/Health fold it opens Health (which now hosts the live HR hero + physiology). */
private val quickActions: List<QuickAction> = listOf(
    QuickAction(R.string.action_live_hr, Icons.Filled.FavoriteBorder, Destination.Health.route),
    QuickAction(R.string.action_start_workout, Icons.Filled.FitnessCenter, Destination.Workouts.route),
    QuickAction(R.string.action_log_journal, Icons.Filled.Edit, Destination.Insights.route),
    QuickAction(R.string.action_breathe, Icons.Filled.Air, Destination.Breathe.route),
)

// MARK: - Navigation motion
//
// The global easing is the calm, decelerating cubic-bezier(0.22, 1, 0.36, 1) — nothing
// bounces or overshoots. Top-level destination switches crossfade over ~240ms, and back
// navigation uses the same fade so the bar never feels jerky.

/** The calm global easing curve (cubic-bezier 0.22, 1, 0.36, 1). */
internal val NavEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** ~240ms crossfade on the calm easing — the tab crossfade between roots. */
private val navFadeSpec = tween<Float>(durationMillis = 240, easing = NavEasing)

/** The bottom-bar tabs. A More row pointing at one of these switches tab instead of stacking under
 *  More, so the saved More stack can never restore to another tab's screen. */
private val tabRoutes: Set<String> = setOf(
    Destination.Today.route, Destination.Health.route, Destination.Sleep.route, Destination.More.route,
)

/** Navigate to a top-level destination with single-top + state save/restore. */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        // Pop to the graph root, discarding whatever drill-in was open, and land on the tab's ROOT.
        //
        // `saveState`/`restoreState` are deliberately NOT used. Drill-ins (recovery_detail,
        // health_monitor, ...) are pushed by this same NavHost rather than by a nested per-tab graph,
        // so a saved stack is keyed by the START destination — which meant tapping Home saved the
        // open drill-in and then restored it in the same transaction. Measured on device: from the
        // Charge ring, Home left you on Recovery no matter how often you tapped it, and the same
        // held for Health while Health Monitor was open. A bottom-bar tap must reach the tab.
        //
        // Cost: a tab's scroll position no longer survives a tab switch. Restoring it properly needs
        // one nested graph per tab so each tab owns its own saved stack; that is a structural change,
        // not a flag.
        popUpTo(graph.findStartDestination().id) { inclusive = false }
        launchSingleTop = true
    }
}

/**
 * Loader for the v5 "Your Data, Fused" screen: assembles today's [FusedRecord] off the repository via
 * [AppViewModel.fusedRecordForToday] (the pure FusionResolver per metric) and hands the pure
 * [FusedRecordScreen] its read-model. Keeps the screen itself I/O-free + previewable. Re-loads on entry.
 */
@Composable
private fun FusedRecordRoute(viewModel: AppViewModel) {
    var record by remember {
        mutableStateOf(FusedRecord(rows = emptyList(), dayOwner = null as FusionSource?, contributingSourceCount = 0))
    }
    LaunchedEffect(Unit) {
        record = runCatching { viewModel.fusedRecordForToday() }.getOrDefault(record)
    }
    FusedRecordScreen(record = record)
}

package com.noop.ui.whoop

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noop.BuildConfig
import com.noop.R
import com.noop.ui.Destination
import com.noop.ui.Metrics
import com.noop.ui.NoopCard
import com.noop.ui.NoopPrefs
import com.noop.ui.NoopType
import com.noop.ui.Overline
import com.noop.ui.Palette
import com.noop.ui.ScreenScaffold

// MARK: - More — account & settings
//
// The door to every destination the bottom bar does not carry: flat, always-visible sections of
// one-tap rows over a build-version footnote. Presentation only — no state, no reads, no arithmetic.

/** One section of the page: its localized header and the destinations listed under it. */
private data class MoreSection(@StringRes val headerRes: Int, val items: List<Destination>)

/**
 * Every destination this page is the only door to, grouped as the page presents them. A row removed
 * here is a screen nothing else reaches, so entries leave only with their screen. [hydration] is the
 * Settings opt-in: its row exists only while that toggle is on.
 */
private fun moreSections(hydration: Boolean): List<MoreSection> = listOf(
    MoreSection(
        R.string.more_group_analysis,
        listOf(Destination.Insights, Destination.Intelligence, Destination.Coach),
    ),
    MoreSection(
        R.string.more_group_trends,
        listOf(Destination.Explore, Destination.Compare),
    ),
    MoreSection(
        R.string.more_group_body,
        listOfNotNull(
            Destination.Workouts, Destination.Stress,
            Destination.Breathe, Destination.Intervals,
            Destination.Hydration.takeIf { hydration },
        ),
    ),
    MoreSection(
        R.string.more_group_data,
        listOf(
            Destination.FusedRecord, Destination.DataSources,
            Destination.BackupSync, Destination.Devices,
        ),
    ),
    MoreSection(
        R.string.more_group_app,
        listOf(
            Destination.Automations, Destination.SmartAlarm, Destination.Notifications,
            Destination.TestCentre, Destination.Settings, Destination.About,
        ),
    ),
)

/**
 * The More page. [onNavigate] receives a [Destination.route]; the caller decides whether that is a
 * tab switch or a push.
 */
@Composable
internal fun WhoopMoreScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    // Read on every composition rather than remembered: SharedPreferences is not reactive, so this is
    // what makes the row appear on the way back from the Settings toggle.
    val sections = moreSections(hydration = NoopPrefs.hydrationTracking(context))
    ScreenScaffold(title = stringResource(R.string.nav_more)) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
            sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Overline(stringResource(section.headerRes))
                    section.items.forEach { destination ->
                        MoreEntryRow(
                            icon = destination.icon,
                            title = stringResource(destination.titleRes),
                            onClick = { onNavigate(destination.route) },
                        )
                    }
                }
            }
        }
        MoreVersionNote()
    }
}

/** One destination row: a full-width card of leading icon + UPPERCASE title, tappable end to end. */
@Composable
private fun MoreEntryRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    NoopCard(padding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = title, onClick = onClick)
                .padding(horizontal = Metrics.space18, vertical = Metrics.space16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space16),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(Metrics.space24),
            )
            Text(
                title.uppercase(),
                style = NoopType.subhead.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = NoopType.overlineTracking.sp,
                ),
                color = Palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The build the app is running, as the page's closing line. */
@Composable
private fun MoreVersionNote() {
    Text(
        "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
        style = NoopType.footnote,
        color = Palette.textTertiary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

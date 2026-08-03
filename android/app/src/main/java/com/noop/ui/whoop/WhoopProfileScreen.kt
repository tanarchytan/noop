package com.noop.ui.whoop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.ui.AppViewModel
import com.noop.ui.Metrics
import com.noop.ui.NoopCard
import com.noop.ui.NoopCardHeader
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.ProfileAvatar
import com.noop.ui.ReportRange
import com.noop.ui.ScreenScaffold
import com.noop.ui.SectionHeader
import com.noop.ui.TrendsReportData
import java.time.LocalDate

// MARK: - Profile
//
// The avatar destination: the Data Highlights for a chosen window, plus the door back to the body
// profile. Every figure comes from RangeReportEngine through TrendsReportData.

/** The header avatar, sized off the icon-button token. */
private val PROFILE_AVATAR_SIZE = Metrics.iconButton + Metrics.space16

/**
 * The profile page. [onOpenBodyProfile] opens the body-profile / units editor, which lives on its own
 * screen; pass null where that destination is not reachable and the row is left out.
 */
@Composable
internal fun WhoopProfileScreen(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    onOpenBodyProfile: (() -> Unit)? = null,
) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    var range by remember { mutableStateOf(ReportRange.All) }
    // The window is anchored to today's local day, the same rule the trends export uses.
    val today = remember { LocalDate.now().toString() }
    val report = remember(days, range, today) { TrendsReportData.report(range, days, today) }

    ScreenScaffold(
        title = "Profile",
        subtitle = "Your highs and lows across every day this phone has recorded.",
        modifier = modifier,
        leading = { ProfileAvatar(size = PROFILE_AVATAR_SIZE, contentDescription = "Profile photo") },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            SectionHeader(title = "Data highlights")
            WhoopProfileHighlightsCard(
                report = report,
                range = range,
                onRangeChange = { range = it },
            )
        }
        if (onOpenBodyProfile != null) BodyProfileCard(onOpen = onOpenBodyProfile)
    }
}

/** The tap-through to the body-profile and units editor. */
@Composable
private fun BodyProfileCard(onOpen: () -> Unit) {
    NoopCard(tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            NoopCardHeader(title = "Body profile & units", onClick = onOpen)
            Text(
                "Birthday, sex, weight, height, max heart rate and how NOOP shows units. " +
                    "All of it stays on this phone.",
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
        }
    }
}

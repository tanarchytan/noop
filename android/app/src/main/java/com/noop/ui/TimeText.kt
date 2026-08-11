package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.noop.R
import java.time.LocalDate

// MARK: - Time text
//
// Turning an instant or a date into the words a screen prints. Both helpers here decide only the
// WORDING of a time; neither derives a health figure. The caller supplies the vocabulary and the
// fallback format, so a screen can say "Today" or "today" or nothing at all without a second copy of
// the decision drifting into it. Clocks are injectable so both are pinned by JVM tests
// (RelativeAgoTest, DayLabelTest).

/** The coarse bucket a gap falls into; [relativeAgoBucket] picks it, the resources word it. */
internal enum class AgoUnit { NOW, MINUTES, HOURS, DAYS }

/**
 * The bucket a gap falls into and the count inside it: just-now / min / h / d. Pure + unit-tested
 * (RelativeAgoTest); [nowSec] is injectable for determinism, and a future timestamp clamps to NOW.
 */
internal fun relativeAgoBucket(
    epochSec: Long,
    nowSec: Long = System.currentTimeMillis() / 1000L,
): Pair<AgoUnit, Int> {
    val d = (nowSec - epochSec).coerceAtLeast(0)
    return when {
        d < 60L -> AgoUnit.NOW to 0
        d < 3600L -> AgoUnit.MINUTES to (d / 60L).toInt()
        d < 86_400L -> AgoUnit.HOURS to (d / 3600L).toInt()
        else -> AgoUnit.DAYS to (d / 86_400L).toInt()
    }
}

/**
 * Coarse relative-time label for the "History synced N ago" sync-status line. Words the bucket
 * [relativeAgoBucket] picked. Used by DevicesScreen and UpdatesInboxScreen.
 */
@Composable
internal fun relativeAgo(epochSec: Long, nowSec: Long = System.currentTimeMillis() / 1000L): String {
    val (unit, n) = relativeAgoBucket(epochSec, nowSec)
    return when (unit) {
        AgoUnit.NOW -> stringResource(R.string.uicore_ago_just_now)
        AgoUnit.MINUTES -> pluralStringResource(R.plurals.uicore_ago_minutes, n, n)
        AgoUnit.HOURS -> pluralStringResource(R.plurals.uicore_ago_hours, n, n)
        AgoUnit.DAYS -> pluralStringResource(R.plurals.uicore_ago_days, n, n)
    }
}

// MARK: - Relative day label
//
// The ONE Today/Yesterday/other-date decision. Every screen words it differently ("Today", "today",
// "" for the current day) and formats the fallback date its own way, so the wording comes in and only
// the decision lives here — which is what kept drifting when each screen carried its own copy.

/**
 * [today] when [date] is the local calendar day [now], [yesterday] for the day before, else [other].
 *
 * [other] is evaluated by the caller, so it owns the date format; [now] is injectable for a caller that
 * already holds one (and for tests).
 */
internal fun relativeDayLabel(
    date: LocalDate,
    today: String,
    yesterday: String,
    other: String,
    now: LocalDate = LocalDate.now(),
): String = when (date) {
    now -> today
    now.minusDays(1) -> yesterday
    else -> other
}

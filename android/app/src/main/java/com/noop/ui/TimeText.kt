package com.noop.ui

import java.time.LocalDate

// MARK: - Time text
//
// Turning an instant or a date into the words a screen prints. Both helpers here decide only the
// WORDING of a time; neither derives a health figure. The caller supplies the vocabulary and the
// fallback format, so a screen can say "Today" or "today" or nothing at all without a second copy of
// the decision drifting into it. Clocks are injectable so both are pinned by JVM tests
// (RelativeAgoTest, DayLabelTest).

/**
 * Coarse relative-time label for the "History synced N ago" sync-status line. Pure + unit-tested
 * (RelativeAgoTest); [nowSec] is injectable for determinism. Buckets to just-now / min / h / d.
 * Used by DevicesScreen and UpdatesInboxScreen. (Lived in the old LiveScreen.kt until the Live/Health
 * fold; split out here as a standalone helper since it has nothing to do with live physiology.)
 */
internal fun relativeAgo(epochSec: Long, nowSec: Long = System.currentTimeMillis() / 1000L): String {
    val d = (nowSec - epochSec).coerceAtLeast(0)
    return when {
        d < 60L -> "just now"
        d < 3600L -> "${d / 60L} min ago"
        d < 86_400L -> "${d / 3600L} h ago"
        else -> "${d / 86_400L} d ago"
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

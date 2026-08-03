package com.noop.ui

import java.time.LocalDate

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

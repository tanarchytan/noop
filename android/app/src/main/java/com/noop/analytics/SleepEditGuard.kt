package com.noop.analytics

import java.time.Instant
import java.time.ZoneId

/**
 * Pure guards for the hand-edit sleep-time pickers, all pure and unit-tested:
 *   1. [autoCorrectedBed]: a time-only roll that lands the bed in the future, or at/after the
 *      night's wake, almost always means the PREVIOUS evening; auto-decrement the date.
 *   2. [isDisjoint]: a corrected window with no overlap of the night's recorded coverage needs an
 *      explicit confirm ("this moves the night to a time with no recorded data"), never silent
 *      acceptance.
 *   3. [clampedEditWindow]: the repository belt-and-braces; no code path may persist a future or
 *      inverted window even if a client UI misbehaves.
 *   4. [frozenWake]: which bound an edit actually froze, so the bed picker's passed-through wake
 *      stays re-detectable while a wake the user picked does not.
 */
object SleepEditGuard {

    /**
     * Longest night span (seconds) the at/after-wake auto-correct will manufacture. A genuine
     * evening correction yields ~6h; a session moved later past its own wake would yield ~23h if
     * decremented, which isn't a plausible night, so those candidates are left verbatim.
     */
    const val MAX_AUTO_CORRECT_NIGHT_SEC: Long = 16L * 3600L

    /**
     * Rule 1: cross-midnight bed auto-correct. A same-day (time-only) candidate that lands in the
     * FUTURE, or at/after [originalWakeTs] where decrementing forms a plausible night (bed before
     * wake, within [MAX_AUTO_CORRECT_NIGHT_SEC]), is rolled back one day — but ONLY if that lands in
     * the past. A deliberate cross-day change is always respected verbatim. A null [originalWakeTs]
     * is the add-a-nap case, whose anchor sits after the night's wake.
     */
    fun autoCorrectedBed(
        previousBedTs: Long,
        candidateBedTs: Long,
        originalWakeTs: Long?,
        nowTs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val prevDay = Instant.ofEpochSecond(previousBedTs).atZone(zone).toLocalDate()
        val candZoned = Instant.ofEpochSecond(candidateBedTs).atZone(zone)
        if (candZoned.toLocalDate() != prevDay) return candidateBedTs
        // minusDays is DST-correct: "the same wall-clock time one calendar day earlier".
        val decremented = candZoned.minusDays(1).toEpochSecond()
        if (decremented > nowTs) return candidateBedTs
        val futureViolation = candidateBedTs > nowTs
        val wakeViolation = originalWakeTs != null && candidateBedTs >= originalWakeTs &&
            decremented < originalWakeTs &&
            (originalWakeTs - decremented) <= MAX_AUTO_CORRECT_NIGHT_SEC
        if (!futureViolation && !wakeViolation) return candidateBedTs
        return decremented
    }

    /**
     * Rule 2: true when the corrected window `[newStart, newEnd)` shares nothing with the night's
     * recorded coverage `[coverageStart, coverageEnd)` (unix seconds). Accepting a disjoint window
     * silently fabricates an all-awake phantom night, so the UI must confirm the move instead.
     */
    fun isDisjoint(newStart: Long, newEnd: Long, coverageStart: Long, coverageEnd: Long): Boolean =
        newEnd <= coverageStart || newStart >= coverageEnd

    /**
     * Rule 3: persistence belt-and-braces. Caps the corrected wake at `nowTs + slackSec` (sleep
     * cannot end in the future; the slack absorbs clock skew) and refuses (null) an inverted or
     * fully-future window once capped, so no path can write a phantom night the display can't render.
     */
    fun clampedEditWindow(start: Long, end: Long, nowTs: Long, slackSec: Long = 300L): Pair<Long, Long>? {
        val cappedEnd = minOf(end, nowTs + slackSec)
        if (cappedEnd <= start) return null
        return start to cappedEnd
    }

    /**
     * Rule 4: the `endTsAdjusted` an edit leaves behind. A wake the user PICKED is banked and frozen;
     * a bed-only edit passes the wake through, so [previous] survives — null stays null and the end
     * stays re-detectable. One owner for the rule the repository write and the optimistic UI copy
     * must agree on.
     */
    fun frozenWake(previous: Long?, newEndTs: Long, wakeSetByUser: Boolean): Long? =
        if (wakeSetByUser) newEndTs else previous
}

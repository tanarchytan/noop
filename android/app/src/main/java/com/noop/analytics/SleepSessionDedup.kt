package com.noop.analytics

import com.noop.data.SleepSession

/**
 * Overlap-aware de-duplication of banked sleep sessions.
 *
 * An unstable strap clock can re-bank the same night's raw data under a shifted timebase, so
 * successive analyze passes detect it at shifted bounds and the sleepSession table accumulates
 * overlapping copies under different [SleepSession.startTs] keys. The (deviceId, startTs)
 * primary key can't collapse them, so day assignment keys the stale copy to the wrong wake day.
 * Applied wherever banked sessions are assembled before day assignment or scoring. Pure +
 * deterministic, unit-tested directly.
 */
object SleepSessionDedup {

    /**
     * Absolute overlap (seconds) at/above which two sessions are the same night. Two real sleeps
     * never overlap at all, so material overlap means re-detected drift or a re-bank; 30 min keeps
     * the rule conservative so sub-30-min boundary jitter is never collapsed.
     */
    const val MIN_OVERLAP_SECONDS: Long = 30L * 60L

    /**
     * Fractional overlap of the SHORTER session at/above which two sessions are duplicates.
     * Catches a short duplicate fragment swallowed by a longer copy even when the absolute
     * overlap is under the 30 min bar (e.g. a 40 min fragment 60% inside the night).
     */
    const val MIN_OVERLAP_FRACTION_OF_SHORTER: Double = 0.5

    /** The collapse outcome: canonical survivors + the duplicates dropped, both sorted by startTs. */
    data class Result(val kept: List<SleepSession>, val dropped: List<SleepSession>)

    /** Seconds of overlap between the two sessions' EFFECTIVE spans (edited onsets honoured, the
     *  same spans display / day assignment place the block by). 0 when disjoint. */
    internal fun overlapSeconds(a: SleepSession, b: SleepSession): Long =
        maxOf(0L, minOf(a.endTs, b.endTs) - maxOf(a.effectiveStartTs, b.effectiveStartTs))

    /**
     * True when [a] and [b] are overlapping copies of the same night: overlap at least
     * [MIN_OVERLAP_SECONDS] absolute, or at least [MIN_OVERLAP_FRACTION_OF_SHORTER] of the shorter
     * session. Uses only (effectiveStartTs, endTs) — the model has no banked-at column to compare.
     */
    fun isDuplicate(a: SleepSession, b: SleepSession): Boolean {
        val overlap = overlapSeconds(a, b)
        if (overlap <= 0L) return false
        if (overlap >= MIN_OVERLAP_SECONDS) return true
        val shorter = minOf(
            maxOf(a.endTs - a.effectiveStartTs, 0L),
            maxOf(b.endTs - b.effectiveStartTs, 0L),
        )
        return shorter > 0L && overlap.toDouble() >= MIN_OVERLAP_FRACTION_OF_SHORTER * shorter.toDouble()
    }

    /**
     * Collapse overlapping duplicates to one canonical survivor per night, deterministically.
     *
     * Canonical preference, highest first:
     *   1. [SleepSession.userEdited]: a hand-corrected night is never dropped.
     *   2. Bank recency: startTs in [freshStarts] (the caller passes the keys it banked this
     *      pass, since the row model has no banked-at column of its own).
     *   3. Longest effective duration: the fullest capture of the night.
     *   4. Latest endTs, then latest startTs: a stable tie-break so results are reproducible.
     *
     * Greedy sweep in preference order: a session is kept unless it overlap-duplicates an
     * already-kept one (edited rows are exempt and always kept). Both outputs are sorted by
     * startTs; callers with no bank witness pass no [freshStarts].
     */
    fun dedupe(sessions: List<SleepSession>, freshStarts: Set<Long> = emptySet()): Result {
        if (sessions.size < 2) return Result(sessions, emptyList())
        val ordered = sessions.sortedWith(
            compareByDescending<SleepSession> { it.userEdited }
                .thenByDescending { it.startTs in freshStarts }
                .thenByDescending { it.endTs - it.effectiveStartTs }
                .thenByDescending { it.endTs }
                .thenByDescending { it.startTs },
        )
        val kept = ArrayList<SleepSession>()
        val dropped = ArrayList<SleepSession>()
        for (s in ordered) {
            if (!s.userEdited && kept.any { isDuplicate(it, s) }) dropped.add(s) else kept.add(s)
        }
        return Result(kept.sortedBy { it.startTs }, dropped.sortedBy { it.startTs })
    }
}

package com.noop.analytics

/**
 * Pure logic behind a deleted sleep night's suppression tombstone.
 *
 * Deleting a DETECTED sleep suppresses its re-detection so the night does not silently reappear on
 * the next analyze pass, with undo support. Tombstones live in the `dismissedSleep` Room table under
 * the deleted row's own `deviceId` (`"my-whoop"` for an imported night, `"my-whoop-noop"` for a
 * computed one). [WhoopRepository.dismissedSleeps] reads both ids, so a tombstone written under
 * either id suppresses re-detection regardless of which path re-detects it.
 */
object DismissedSleepGuard {

    /** True when `[sessionStart, sessionEnd)` overlaps any dismissed `(start, end)` window: the
     *  engine's re-detection guard. Uses overlap rather than exact start time because a re-detected
     *  onset drifts as more raw data arrives; half-open `<` test throughout. */
    fun isSuppressed(
        sessionStart: Long,
        sessionEnd: Long,
        dismissedWindows: List<Pair<Long, Long>>,
    ): Boolean = dismissedWindows.any { (start, end) -> sessionStart < end && start < sessionEnd }

    /** Drop every session in [sessions] that overlaps a dismissed window: the engine's `sleepKept`
     *  filter, as a pure function so a JVM test can pin it. [windowOf] projects a session to its
     *  `(start, end)` detected window. */
    fun <T> keeping(
        sessions: List<T>,
        dismissedWindows: List<Pair<Long, Long>>,
        windowOf: (T) -> Pair<Long, Long>,
    ): List<T> = sessions.filterNot {
        val (s, e) = windowOf(it)
        isSuppressed(s, e, dismissedWindows)
    }

    /** Whether deleting a night writes a suppression tombstone. A DETECTED night is tombstoned so the
     *  recompute does not regenerate it; a user-edited night (hand-corrected or a manually-added nap)
     *  is deleted without one, since it is never re-detected. */
    fun writesTombstoneOnDelete(userEdited: Boolean): Boolean = !userEdited
}

package com.noop.analytics

import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dismissed sleep window stays dismissed across a dedup/heal + rescore: [DismissedSleepGuard.keeping]
 * filters a re-detected overlapping session before it's banked, so [SleepSessionDedup]'s heal (banked
 * rows only) can never resurrect a suppressed night.
 */
class DismissedSleepDedupInteractionTest {

    private fun session(start: Long, end: Long, edited: Boolean = false) =
        SleepSession(deviceId = "my-whoop-noop", startTs = start, endTs = end, userEdited = edited)

    @Test fun dismissedWindowIsDroppedBeforeBankingAndStaysDroppedAfterDedup() {
        // Night A kept; Night B deleted. B re-detects with a drifted onset but still overlaps its tombstone.
        val nightA = session(100_000, 128_000)
        val nightBReDetected = session(200_500, 228_000) // drifted 500s from the deleted onset
        val tombstones = listOf(200_000L to 228_000L)

        // STEP 1 (engine guard): B is suppressed, A survives.
        val survivors = DismissedSleepGuard.keeping(listOf(nightA, nightBReDetected), tombstones) { it.startTs to it.endTs }
        assertEquals(
            "the re-detected deleted night is filtered before it is ever banked",
            listOf(nightA.startTs), survivors.map { it.startTs },
        )

        // STEP 2: the heal runs over the BANKED set. A stale timebase-shifted duplicate of night A
        // is also banked; the heal collapses it.
        val staleADuplicate = session(100_500, 128_500) // overlaps A -> same night, drop it
        val banked = survivors + staleADuplicate
        val result = SleepSessionDedup.dedupe(banked, freshStarts = setOf(nightA.startTs))
        assertEquals(listOf(nightA.startTs), result.kept.map { it.startTs })

        // STEP 3 (invariant): the dedup heal NEVER re-introduces the suppressed window.
        assertFalse(
            "no kept row overlaps the dismissed window after a dedup+rescore",
            result.kept.any { DismissedSleepGuard.isSuppressed(it.startTs, it.endTs, tombstones) },
        )
    }

    @Test fun removingTheTombstoneReAdmitsTheNightOnTheNextPass() {
        val nightBReDetected = session(200_500, 228_000)
        val tombstoned = listOf(200_000L to 228_000L)
        // While tombstoned: suppressed.
        assertTrue(
            DismissedSleepGuard.keeping(listOf(nightBReDetected), tombstoned) { it.startTs to it.endTs }.isEmpty(),
        )
        // Remove the tombstone (undo / allow re-detection) -> re-admitted.
        val lifted = emptyList<Pair<Long, Long>>()
        assertEquals(
            listOf(nightBReDetected.startTs),
            DismissedSleepGuard.keeping(listOf(nightBReDetected), lifted) { it.startTs to it.endTs }.map { it.startTs },
        )
    }

    @Test fun editedNightIsNeverDroppedByTheDedupHeal() {
        // A userEdited night is exempt from dedup drops and gets no tombstone, so it's never suppressed here.
        val edited = session(300_000, 328_000, edited = true)
        val overlappingDetected = session(300_200, 328_000)
        val result = SleepSessionDedup.dedupe(listOf(edited, overlappingDetected), freshStarts = setOf(overlappingDetected.startTs))
        assertTrue("the edited night is never dropped by the heal", result.kept.any { it.userEdited })
    }
}

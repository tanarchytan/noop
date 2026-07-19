package com.noop.analytics

import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the recompute overlap guard (a detected session overlapping a user-edited window is dropped,
 * non-overlapping kept) and [SleepStageTotals.dailyAggregateHonoringEdits] (an edited block's stages
 * replace its detected twin, matched on startTs, in the daily aggregate).
 */
class SleepEditDurabilityTest {

    /** Re-encode a single-stage span to the on-device `[{start,end,stage}]` stagesJSON shape. */
    private fun stages(start: Long, end: Long, stage: String): String =
        AnalyticsEngine.encodeStages(listOf(StageSegment(start = start, end = end, stage = stage)))!!

    private fun computedSleep(
        start: Long,
        end: Long,
        userEdited: Boolean = false,
        startTsAdjusted: Long? = null,
    ) = SleepSession(
        deviceId = "my-whoop-noop",
        startTs = start,
        endTs = end,
        userEdited = userEdited,
        startTsAdjusted = startTsAdjusted,
    )

    /** The EXACT overlap predicate from IntelligenceEngine.analyzeRecent (sleepKept). */
    private fun keptAfterGuard(
        detected: List<SleepSession>,
        edited: List<SleepSession>,
    ): List<SleepSession> {
        val editedWindows = edited.map { it.effectiveStartTs to it.endTs }
        return detected.filterNot { s ->
            editedWindows.any { (start, end) -> s.startTs < end && start < s.endTs }
        }
    }

    @Test
    fun overlappingDetectedSessionIsDropped() {
        // Edited window [1000, 5000]; re-detect drifts to [1050, 4980] but still overlaps → drop.
        val edited = computedSleep(start = 1000, end = 5000, userEdited = true)
        val reDetected = computedSleep(start = 1050, end = 4980)

        val kept = keptAfterGuard(detected = listOf(reDetected), edited = listOf(edited))
        assertTrue("a detected session overlapping an edited window must be dropped", kept.isEmpty())
    }

    @Test
    fun nonOverlappingDetectedSessionIsKept() {
        // An edit on last night [1000, 5000] must NOT suppress a genuinely separate later night.
        val edited = computedSleep(start = 1000, end = 5000, userEdited = true)
        val otherNight = computedSleep(start = 90_000, end = 120_000) // hours later, no overlap

        val kept = keptAfterGuard(detected = listOf(otherNight), edited = listOf(edited))
        assertEquals("a non-overlapping detected session must be kept", listOf(otherNight), kept)
    }

    @Test
    fun guardUsesEffectiveStartForOverlap() {
        // Edit moves bedtime earlier via startTsAdjusted (effectiveStartTs = 800, detected = 2000).
        // Re-detect [820, 1900] overlaps the EFFECTIVE window [800,5000] but not the detected key —
        // the guard must use effectiveStartTs and drop it.
        val edited = computedSleep(start = 2000, end = 5000, userEdited = true, startTsAdjusted = 800)
        assertEquals(800L, edited.effectiveStartTs)
        val reDetected = computedSleep(start = 820, end = 1900)

        val kept = keptAfterGuard(detected = listOf(reDetected), edited = listOf(edited))
        assertTrue("overlap must be tested against the EFFECTIVE edited window", kept.isEmpty())
    }

    @Test
    fun noEditsKeepsEverything() {
        val a = computedSleep(start = 1000, end = 5000)
        val b = computedSleep(start = 90_000, end = 120_000)
        val kept = keptAfterGuard(detected = listOf(a, b), edited = emptyList())
        assertEquals(listOf(a, b), kept)
    }

    // ── Durable DELETE tombstone ─────────────────────────────────────────────────────────────────
    //
    // A delete records a dismissedSleep marker; the recompute OR-s it into the overlap filter
    // (skipWindows = editedWindows + dismissedWindows), so a re-detected night overlapping a
    // tombstone is dropped and the delete stays durable.

    /** (startTs, endTs) tombstone span — the shape recorded by deleteSleepSession → dismissedSleeps. */
    private fun dismissedWindow(start: Long, end: Long): Pair<Long, Long> = start to end

    /** The EXACT sleepKept predicate: edited + dismissed windows both suppress. */
    private fun keptAfterGuardWithDismissed(
        detected: List<SleepSession>,
        edited: List<SleepSession>,
        dismissed: List<Pair<Long, Long>>,
    ): List<SleepSession> {
        val editedWindows = edited.map { it.effectiveStartTs to it.endTs }
        val skipWindows = editedWindows + dismissed
        return detected.filterNot { s ->
            skipWindows.any { (start, end) -> s.startTs < end && start < s.endTs }
        }
    }

    @Test
    fun deletedNightStaysGoneAfterRecompute() {
        // Deleted [1000, 5000] → tombstone recorded. Recompute re-detects the same night and must
        // drop it, not re-upsert.
        val tombstone = dismissedWindow(1000, 5000)
        val reDetected = computedSleep(start = 1000, end = 5000)

        val kept = keptAfterGuardWithDismissed(
            detected = listOf(reDetected),
            edited = emptyList(),
            dismissed = listOf(tombstone),
        )
        assertTrue("a re-detected night that was deleted must stay gone", kept.isEmpty())
    }

    @Test
    fun deletedNightStaysGoneWhenReDetectedOnsetDrifts() {
        // Re-detected onset drifts ([1050, 4980] vs deleted [1000, 5000]); overlap (not exact startTs)
        // still suppresses it.
        val tombstone = dismissedWindow(1000, 5000)
        val reDetected = computedSleep(start = 1050, end = 4980)

        val kept = keptAfterGuardWithDismissed(
            detected = listOf(reDetected),
            edited = emptyList(),
            dismissed = listOf(tombstone),
        )
        assertTrue("a drifted re-detect of a deleted night must still be dropped", kept.isEmpty())
    }

    @Test
    fun deleteTombstoneDoesNotSuppressOtherNights() {
        // Deleting one night must not erase a genuinely separate later night.
        val tombstone = dismissedWindow(1000, 5000)
        val otherNight = computedSleep(start = 90_000, end = 120_000)

        val kept = keptAfterGuardWithDismissed(
            detected = listOf(otherNight),
            edited = emptyList(),
            dismissed = listOf(tombstone),
        )
        assertEquals("a non-overlapping night must survive an unrelated delete", listOf(otherNight), kept)
    }

    @Test
    fun editAndDeleteWindowsBothSuppress() {
        // Both guards compose in one pass: edited + deleted nights suppressed, untouched one kept.
        val edited = computedSleep(start = 1000, end = 5000, userEdited = true)
        val editedReDetect = computedSleep(start = 1040, end = 4990)
        val deletedReDetect = computedSleep(start = 200_000, end = 230_000)
        val freshNight = computedSleep(start = 400_000, end = 430_000)
        val tombstone = dismissedWindow(200_000, 230_000)

        val kept = keptAfterGuardWithDismissed(
            detected = listOf(editedReDetect, deletedReDetect, freshNight),
            edited = listOf(edited),
            dismissed = listOf(tombstone),
        )
        assertEquals("only the untouched night survives", listOf(freshNight), kept)
    }

    // ── Daily-aggregate substitution (SleepStageTotals.dailyAggregateHonoringEdits) ──────────────

    @Test
    fun dailyAggregateSubstitutesEditedStagesForDetectedTwin() {
        // Detected twin: 6h light at startTs 1000 (match key). Edited: reshaped to 8h asleep.
        // Aggregate must reflect the EDITED stages.
        val detectedStart = 1000L
        val detected = listOf(detectedStart to stages(detectedStart, detectedStart + 6 * 3600, "light"))
        val edited = mapOf(detectedStart to stages(detectedStart, detectedStart + 8 * 3600, "deep"))

        val r = SleepStageTotals.dailyAggregateHonoringEdits(detected, edited)
        assertNotNull(r)
        assertTrue("an edit on a detected twin must report editApplied", r!!.editApplied)
        // 8h of deep == 480 min asleep, 0 awake → efficiency 1.0; deep=480, light=0.
        assertEquals(480.0, r.sleep.totalSleepMin, 1e-6)
        assertEquals(480.0, r.sleep.deepMin, 1e-6)
        assertEquals(0.0, r.sleep.lightMin, 1e-6)
        assertEquals(1.0, r.sleep.efficiency, 1e-6)
    }

    @Test
    fun dailyAggregateUnchangedWhenNoEditMatches() {
        // Detected twin at 1000; the edit is keyed on a DIFFERENT startTs (2000) → no substitution.
        val detected = listOf(1000L to stages(1000, 1000 + 6 * 3600, "light"))
        val edited = mapOf(2000L to stages(2000, 2000 + 8 * 3600, "deep"))

        val r = SleepStageTotals.dailyAggregateHonoringEdits(detected, edited)
        assertNotNull(r)
        assertFalse("a non-matching edit must NOT set editApplied", r!!.editApplied)
        // Falls back to the 6h detected light block.
        assertEquals(360.0, r.sleep.totalSleepMin, 1e-6)
        assertEquals(360.0, r.sleep.lightMin, 1e-6)
    }

    @Test
    fun editedToNullStagesFallsBackToDetected() {
        // Edit reshaped to null stages must NOT drop the block (would collapse the sleep total);
        // falls back to detected stages, does NOT set editApplied.
        val detected = listOf(1000L to stages(1000, 1000 + 6 * 3600, "light"))
        val edited = mapOf<Long, String?>(1000L to null)

        val r = SleepStageTotals.dailyAggregateHonoringEdits(detected, edited)
        assertNotNull(r)
        assertFalse("an edit reshaped to null must fall back, not substitute", r!!.editApplied)
        assertEquals(360.0, r.sleep.totalSleepMin, 1e-6)
    }

    @Test
    fun emptyDetectedDecodesToNull() {
        assertNull(SleepStageTotals.dailyAggregateHonoringEdits(emptyList(), emptyMap()))
    }

    // ── minutes() decoder handles both Android stagesJSON shapes ─────────────────────────────────

    @Test
    fun minutesParsesSegmentSecondsArray() {
        val m = SleepStageTotals.minutes(stages(0, 3600, "deep")) // 1h deep
        assertNotNull(m)
        assertEquals(60.0, m!!.deep, 1e-6)
        assertEquals(60.0, m.asleep, 1e-6)
    }

    @Test
    fun minutesParsesImportedMinuteArray() {
        // Imported WhoopCsvImporter shape: [{stage,min}].
        val json = """[{"stage":"light","min":210.0},{"stage":"deep","min":80.0},{"stage":"awake","min":25.0}]"""
        val m = SleepStageTotals.minutes(json)
        assertNotNull(m)
        assertEquals(210.0, m!!.light, 1e-6)
        assertEquals(80.0, m.deep, 1e-6)
        assertEquals(25.0, m.awake, 1e-6)
        assertEquals(290.0, m.asleep, 1e-6)
        assertEquals(315.0, m.inBed, 1e-6)
    }

    @Test
    fun minutesReturnsNullForGarbage() {
        assertNull(SleepStageTotals.minutes(null))
        assertNull(SleepStageTotals.minutes("not json"))
        assertNull(SleepStageTotals.minutes("[]"))
    }
}

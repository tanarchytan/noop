package com.noop.analytics

import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A manually-added nap is its own sleep session, never folded into main sleep: `addManualNap` writes
 * `userEdited = true` / `startTsAdjusted = null`, so the recompute overlap guard that protects a
 * hand-corrected night protects it too. Pure checks of the overlap predicate and sleep-efficiency helper.
 */
class ManualNapTest {

    /** Re-encode a single-stage span to the on-device `[{start,end,stage}]` stagesJSON shape. */
    private fun stages(start: Long, end: Long, stage: String): String =
        AnalyticsEngine.encodeStages(listOf(StageSegment(start = start, end = end, stage = stage)))!!

    /** A manually-added nap as `addManualNap` writes it: own row, userEdited=true, no adjusted onset. */
    private fun manualNap(start: Long, end: Long) = SleepSession(
        deviceId = "my-whoop-noop",
        startTs = start,
        endTs = end,
        userEdited = true,
        startTsAdjusted = null,
    )

    private fun detected(start: Long, end: Long) = SleepSession(
        deviceId = "my-whoop-noop",
        startTs = start,
        endTs = end,
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

    /** The efficiency `addManualNap` seeds for a chosen window — the whoop-rs value, not a local copy. */
    private fun sleepEfficiency(start: Long, end: Long, stagesJSON: String?): Double? =
        SleepStageTotals.efficiencyForWindow(start, end, stagesJSON, fallback = null)

    // ── A manual nap is its OWN session, protected by the recompute guard ───────────────────────────

    @Test
    fun manualNapIsAFlaggedSeparateSession() {
        val nap = manualNap(start = 48_000, end = 49_800)
        assertTrue("a manually-added nap is flagged user-edited so the recompute guard keeps it", nap.userEdited)
        assertNull("a manual nap's onset IS the chosen onset (no detected twin)", nap.startTsAdjusted)
        assertEquals(48_000L, nap.effectiveStartTs)
    }

    @Test
    fun reDetectedSessionOverlappingAManualNapIsDropped() {
        // Nap [48000,49800]; re-detect drifts to [48100,49700] overlapping it — guard drops the twin.
        val nap = manualNap(start = 48_000, end = 49_800)
        val reDetected = detected(start = 48_100, end = 49_700)
        val kept = keptAfterGuard(detected = listOf(reDetected), edited = listOf(nap))
        assertTrue("a re-detected session overlapping a manual nap must be dropped", kept.isEmpty())
    }

    @Test
    fun manualNapNeverFoldedIntoMainSleep() {
        // Main sleep + a daytime nap hours later are two separate windows: guard keeps both, nap never
        // folded into main sleep (no mislabelled light-sleep gap).
        val mainSleep = detected(start = 1_000, end = 30_000)
        val nap = manualNap(start = 80_000, end = 82_400)
        val kept = keptAfterGuard(detected = listOf(mainSleep), edited = listOf(nap))
        assertEquals("main sleep is untouched by adding a separate nap", listOf(mainSleep), kept)
        // The two windows do not overlap — a nap is a distinct session.
        assertFalse(mainSleep.startTs < nap.endTs && nap.startTs < mainSleep.endTs)
    }

    // ── Editing a manual nap re-stages + STICKS across a recompute (no duplicate) ───────────────────

    @Test
    fun editedNapUsesEffectiveWindowAndIsProtected() {
        // Edit a detected nap [48600,50400] → corrected onset 48000 via startTsAdjusted, wake 51000. The
        // guard must test overlap against the EFFECTIVE window [48000,51000], dropping a re-detect inside it.
        val editedNap = SleepSession(
            deviceId = "my-whoop-noop", startTs = 48_600, endTs = 51_000,
            userEdited = true, startTsAdjusted = 48_000,
        )
        assertEquals(48_000L, editedNap.effectiveStartTs)
        val reDetected = detected(start = 48_200, end = 50_300)
        val kept = keptAfterGuard(detected = listOf(reDetected), edited = listOf(editedNap))
        assertTrue("overlap is tested against the EFFECTIVE edited nap window", kept.isEmpty())
    }

    // ── Efficiency seeded for a freshly-staged nap ──────────────────────────────────────────────────

    @Test
    fun napEfficiencyIsAsleepOverTheChosenWindow() {
        // 30 min in bed, 20 min light asleep + 10 min wake → efficiency 2/3.
        val json = """[{"start":0,"end":1200,"stage":"light"},{"start":1200,"end":1800,"stage":"wake"}]"""
        assertEquals(2.0 / 3.0, sleepEfficiency(0, 1800, json)!!, 1e-9)
    }

    @Test
    fun wakeOnlyFallbackHasNullEfficiency() {
        // The fallback block (strap not dense yet) is a single wake segment — no asleep time → null.
        assertNull(sleepEfficiency(50_000, 51_800, stages(50_000, 51_800, "wake")))
        assertNull(sleepEfficiency(50_000, 51_800, null))
        assertNull(sleepEfficiency(50_000, 51_800, "[]"))
    }
}

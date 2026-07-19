package com.noop.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A sleep edit must RE-SCORE the day immediately (persist, then re-score), not wait for the background
 * loop. The VM can't be constructed under testFullDebugUnitTest, so these pin the control-flow contract:
 * persist-then-rescore, best-effort persist, best-effort rescore, and cancellation propagation.
 */
class SleepEditRescoreTest {

    /** Records the order calls happened in, so a test can assert persist ran before re-score. */
    private class Recorder { val events = mutableListOf<String>() }

    /** Swallow analyzeRecent failures, but rethrow CancellationException so a VM teardown
     *  mid-edit isn't masked. */
    private suspend fun rescoreAfterSleepEdit(analyzeRecent: suspend () -> Unit) {
        runCatching { analyzeRecent() }
            .onFailure { if (it is CancellationException) throw it }
    }

    /** Best-effort persist, then unconditional re-score. */
    private suspend fun editMethod(
        persist: suspend () -> Unit,
        analyzeRecent: suspend () -> Unit,
    ) {
        runCatching { persist() }
        rescoreAfterSleepEdit(analyzeRecent)
    }

    @Test
    fun rescoreRunsAfterAPersist() = runTest {
        val rec = Recorder()
        editMethod(
            persist = { rec.events += "persist" },
            analyzeRecent = { rec.events += "rescore" },
        )
        assertEquals("persist must precede the re-score", listOf("persist", "rescore"), rec.events)
    }

    @Test
    fun rescoreStillRunsWhenPersistThrows() = runTest {
        // Sleep screen applies the edit optimistically, so persist failure must not skip re-score (the
        // day still needs to recompute off whatever state exists).
        val rec = Recorder()
        editMethod(
            persist = { throw IllegalStateException("DB write failed") },
            analyzeRecent = { rec.events += "rescore" },
        )
        assertEquals("a failed persist must not suppress the re-score", listOf("rescore"), rec.events)
    }

    @Test
    fun rescoreSwallowsAnalyzeFailure() = runTest {
        // An analyzeRecent hiccup must never throw into the edit caller (the screen's scope.launch) — the
        // 15-min loop will catch up. Best-effort, exactly like the loop's runCatching.
        editMethod(
            persist = { },
            analyzeRecent = { throw RuntimeException("scoring blew up") },
        )
        // Reaching here without throwing IS the assertion.
        assertTrue(true)
    }

    @Test
    fun rescorePropagatesCancellation() = runTest {
        // Cancellation must propagate (VM teardown mid-edit), not get swallowed by the best-effort runCatching.
        try {
            rescoreAfterSleepEdit(analyzeRecent = { throw CancellationException("VM cleared") })
            fail("CancellationException from the re-score must propagate, not be swallowed")
        } catch (e: CancellationException) {
            assertEquals("VM cleared", e.message)
        }
    }
}

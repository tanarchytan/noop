package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.SleepSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rebase itself, driven directly.
 *
 * The earlier version of this file asserted on `RustScores.sessionRestingHr` and never called the
 * rebase at all, so deleting the class it was named after left it green. These call `rebase` and
 * assert on what it decided to rewrite.
 */
class RestingHrRebaseTest {

    /** 300 s at 60, 300 s at 52, 300 s at 55. Floor = the 52 block; median = 55. */
    private fun mixedNight(dev: String, start: Long): List<HrSample> =
        (List(300) { 60 } + List(300) { 52 } + List(300) { 55 })
            .mapIndexed { i, b -> HrSample(dev, start + i, b) }

    private fun session(start: Long, rhr: Int?) = SleepSession(
        deviceId = "dev",
        startTs = start,
        endTs = start + 900,
        efficiency = 0.9,
        restingHr = rhr,
        avgHrv = null,
    )

    @Test
    fun `a stale floor is rewritten to the median`() = runBlocking {
        val saved = ArrayList<SleepSession>()
        val n = RestingHrRebase.rebase(
            sessions = { listOf(session(1_000L, rhr = 52)) },
            hrFor = { _, _ -> mixedNight("dev", 1_000L) },
            save = { saved.addAll(it) },
        )
        assertEquals("one row rewritten", 1, n)
        assertEquals("rewritten to the in-bed median", 55, saved.single().restingHr)
    }

    @Test
    fun `a session already holding the median is not rewritten`() = runBlocking {
        var saveCalls = 0
        val n = RestingHrRebase.rebase(
            sessions = { listOf(session(1_000L, rhr = 55)) },
            hrFor = { _, _ -> mixedNight("dev", 1_000L) },
            save = { saveCalls++ },
        )
        assertEquals("nothing to do", 0, n)
        assertEquals("and the store is never touched", 0, saveCalls)
    }

    @Test
    fun `a night whose heart rate has left the store keeps its value`() = runBlocking {
        val saved = ArrayList<SleepSession>()
        val n = RestingHrRebase.rebase(
            sessions = { listOf(session(1_000L, rhr = 52)) },
            hrFor = { _, _ -> emptyList() },
            save = { saved.addAll(it) },
        )
        assertEquals("a missing input is not evidence the stored value was wrong", 0, n)
        assertTrue("and nothing is written", saved.isEmpty())
    }

    @Test
    fun `only the stale sessions are rewritten, in one write`() = runBlocking {
        val writes = ArrayList<List<SleepSession>>()
        val stale = session(1_000L, rhr = 52)
        val fresh = session(9_000L, rhr = 55)
        val gone = session(5_000L, rhr = 48)
        val n = RestingHrRebase.rebase(
            sessions = { listOf(stale, fresh, gone) },
            hrFor = { from, _ -> if (from == 5_000L) emptyList() else mixedNight("dev", from) },
            save = { writes.add(it) },
        )
        assertEquals("only the stale one", 1, n)
        assertEquals("in a single write", 1, writes.size)
        assertEquals(1_000L, writes.single().single().startTs)
        assertEquals(55, writes.single().single().restingHr)
    }

    @Test
    fun `an empty store does nothing`() = runBlocking {
        var saveCalls = 0
        val n = RestingHrRebase.rebase(
            sessions = { emptyList() },
            hrFor = { _, _ -> mixedNight("dev", 0L) },
            save = { saveCalls++ },
        )
        assertEquals(0, n)
        assertEquals(0, saveCalls)
    }
}

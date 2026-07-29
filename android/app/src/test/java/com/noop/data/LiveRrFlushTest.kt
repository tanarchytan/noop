package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live 0x2A37 batching contract: [LIVE_FLUSH_ROWS] arms a flush, [liveFlushCutoff] decides how much
 * of the buffer it may take, and [assignRrSeq] numbers what it takes. A wall-second handed over in two
 * batches restarts `seq` and `ord` from zero, so an equal-rrMs beat in it collides on the R-R primary key
 * and INSERT-OR-IGNORE drops it. Replays the client's buffer rule against that.
 */
class LiveRrFlushTest {

    /** One standard-profile reading: the wall-second it was received in and the beats it carried. */
    private data class Reading(val ts: Long, val rr: List<Int>)

    /** Rows surviving INSERT-OR-IGNORE, keyed on the on-disk primary key. */
    private fun replay(readings: List<Reading>, cutoff: (List<HrRow>, List<RrRow>, Boolean) -> Long):
        Map<List<Any>, RrInterval> {
        val hr = ArrayList<HrRow>()
        val rr = ArrayList<RrRow>()
        val stored = LinkedHashMap<List<Any>, RrInterval>()
        fun flush(closing: Boolean) {
            val upTo = cutoff(hr, rr, closing)
            val batch = rr.filter { it.ts < upTo }
            if (batch.isEmpty() && hr.none { it.ts < upTo }) return
            hr.removeAll { it.ts < upTo }
            rr.removeAll { it.ts < upTo }
            for (row in assignRrSeq("d", batch)) {
                stored.putIfAbsent(listOf(row.deviceId, row.ts, row.rrMs, row.seq), row)
            }
        }
        for (r in readings) {
            hr.add(HrRow(r.ts, 60))
            for (ms in r.rr) rr.add(RrRow(r.ts, ms))
            if (hr.size + rr.size >= LIVE_FLUSH_ROWS) flush(closing = false)
        }
        flush(closing = true)
        return stored
    }

    /**
     * Fourteen one-beat seconds fill the buffer to 28 rows; the fifteenth reading arms the flush and the
     * sixteenth repeats its wall-second AND its interval. Sixteen beats, one loss-capable second.
     */
    private fun straddlingStream(): List<Reading> {
        val head = (0 until 14).map { Reading(1_000L + it, listOf(800 + it)) }
        return head + Reading(1_014L, listOf(700)) + Reading(1_014L, listOf(700))
    }

    @Test
    fun cutoff_holdsBackTheNewestBufferedSecond() {
        val hr = listOf(HrRow(100L, 60), HrRow(101L, 61))
        val rr = listOf(RrRow(100L, 800), RrRow(101L, 810))
        assertEquals(101L, liveFlushCutoff(hr, rr, closing = false))
        assertEquals(Long.MAX_VALUE, liveFlushCutoff(hr, rr, closing = true))
    }

    @Test
    fun cutoff_overAnEmptyBufferEmitsNothing() {
        assertEquals(Long.MIN_VALUE, liveFlushCutoff(emptyList(), emptyList(), closing = false))
    }

    /** THE fix: every beat reaches storage even when the flush lands inside a wall-second. */
    @Test
    fun equalBeatsStraddlingAFlush_allReachStorage() {
        val stream = straddlingStream()
        val stored = replay(stream, ::liveFlushCutoff)
        assertEquals("no beat dropped", stream.sumOf { it.rr.size }, stored.size)
        val split = stored.values.filter { it.ts == 1_014L }
        assertEquals("both 700 ms beats kept", 2, split.size)
        assertEquals("distinct seq within the second", listOf(0, 1), split.map { it.seq })
        assertEquals("distinct ord within the second", 2, split.mapNotNull { it.ord }.toSet().size)
    }

    /**
     * Discrimination guard: the same stream flushed on the row count alone really does lose that beat, so
     * a stream that stopped being loss-capable would fail here instead of passing everywhere silently.
     */
    @Test
    fun withoutTheCutoff_theSameStreamLosesTheRepeatedBeat() {
        val stream = straddlingStream()
        val stored = replay(stream) { _, _, _ -> Long.MAX_VALUE }
        assertEquals("one beat dropped", stream.sumOf { it.rr.size } - 1, stored.size)
        assertTrue("the loss is the repeated same-second beat", stored.values.count { it.ts == 1_014L } == 1)
    }

    /** A second whose readings all arrive before the flush is unaffected: the cutoff only holds the tail. */
    @Test
    fun aWholeSecondBeforeTheFlush_isEmittedIntact() {
        val stream = (0 until 20).map { Reading(2_000L + it, listOf(900, 900)) }
        val stored = replay(stream, ::liveFlushCutoff)
        assertEquals(stream.sumOf { it.rr.size }, stored.size)
    }
}

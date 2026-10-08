package com.noop.analytics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sliced single read must equal the two per-range reads (inclusive ends, ts ASC, LIMIT). */
class StreamWindowsTest {
    private val rows = (0L..1000L step 3).toList() // ts-ascending fixture, gaps of 3

    private var calls = 0
    private val read: suspend (Long, Long, Int) -> List<Long> = { f, t, lim ->
        calls++
        rows.filter { it in f..t }.take(lim)
    }

    private fun check(a: LongRange, b: LongRange, limit: Int) = runBlocking {
        calls = 0
        val (sa, sb) = StreamWindows.readTwo(a, b, limit, { it }, read)
        assertEquals(rows.filter { it in a }.take(limit), sa)
        assertEquals(rows.filter { it in b }.take(limit), sb)
    }

    @Test fun overlappingRangesMatchPerRangeReadsWithOneQuery() {
        check(100L..700L, 400L..900L, 10_000)
        assertEquals(1, calls)
    }

    @Test fun nestedAndDisjointRangesMatch() {
        check(0L..1000L, 300L..301L, 10_000)
        check(0L..100L, 500L..600L, 10_000)
        check(2L..3L, 3L..3L, 10_000) // shared inclusive endpoint
    }

    @Test fun truncatedSupersetFallsBackToPerRangeReads() {
        check(100L..700L, 400L..900L, 50)
        assertEquals(3, calls)
    }
}

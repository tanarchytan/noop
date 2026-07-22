package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-side gate for routing daytime stress scoring through whoop-rs. `DaytimeStress.analyze` keeps its
 * Kotlin bucketing, then scores each hour in physio-algo (via [RustScores.daytimeStress] -> uniffi ->
 * whoop-rs `daytime_stress`) and reassembles the timeline. [DaytimeStress.scoreKotlin] is retained as the
 * bit-exact oracle; this replays the SAME hourly aggregates through both scorers and asserts an identical
 * [DaytimeStress.Result] (levels, day mean, sustained run, and the full unscored-hour timeline).
 *
 * One INTENTIONAL divergence (adopted whoop-rs semantics): on a peak-score tie the Kotlin oracle picks the
 * FIRST hour, whoop-rs picks the LAST. The fixtures below give every scored hour a distinct level so the
 * peak is unambiguous; a dedicated test documents the tie behaviour. Loads the host libwhoop_ffi via JNA.
 */
class RustDaytimeStressParityTest {

    private fun agg(hour: Int, meanHr: Double?, rmssd: Double?) =
        DaytimeStress.HourAgg(hour * 3600L, meanHr, rmssd)

    @Test
    fun `scored day matches the Kotlin oracle leg-for-leg`() {
        // Waking hours 6..20, each a distinct (meanHr, rmssd) so no two hours tie on stress.
        val aggs = (6..20).map { h ->
            agg(h, meanHr = 62.0 + (h - 6) * 1.7, rmssd = 55.0 - (h - 6) * 1.3)
        }
        assertEquals(DaytimeStress.scoreKotlin(aggs, 0L), DaytimeStress.scoreRust(aggs, 0L))
    }

    @Test
    fun `unscored hours and non-waking hours reassemble identically`() {
        val aggs = listOf(
            agg(2, 60.0, 50.0),        // non-waking (2am) -> excluded on both
            agg(7, null, 48.0),        // waking but below HR gate -> level null, kept in timeline
            agg(8, 68.0, 44.0),
            agg(9, 91.0, 26.0),        // tense hour
            agg(10, 72.0, null),       // scored on HR alone (no RMSSD)
            agg(23, 58.0, 60.0),       // non-waking (11pm) -> excluded
        )
        val k = DaytimeStress.scoreKotlin(aggs, 0L)
        val r = DaytimeStress.scoreRust(aggs, 0L)
        assertEquals(k, r)
        // Timeline keeps the waking hours only (7,8,9,10), including the unscored 7am.
        assertEquals(listOf(7, 8, 9, 10), r.hours.map { it.hour })
        assertNull("7am had no HR -> unscored", r.hours.first { it.hour == 7 }.level)
        assertEquals("wall start undoes the tz shift", 8 * 3600L, r.hours.first { it.hour == 8 }.startTs)
    }

    @Test
    fun `sustained afternoon high matches`() {
        val aggs = (6..13).map { agg(it, 66.0, 46.0) } +
            (14..17).map { agg(it, 92.0 + (it - 14) * 0.5, 24.0 - (it - 14) * 0.4) } // 4 tense hours, distinct
        val k = DaytimeStress.scoreKotlin(aggs, 0L)
        val r = DaytimeStress.scoreRust(aggs, 0L)
        assertEquals(k, r)
        assertTrue("afternoon spike should read sustained-high", r.sustainedHigh)
    }

    @Test
    fun `empty and all-unscored days match`() {
        assertEquals(DaytimeStress.Result.EMPTY, DaytimeStress.scoreRust(emptyList(), 0L))
        val allNull = (6..12).map { agg(it, null, null) }
        assertEquals(DaytimeStress.scoreKotlin(allNull, 0L), DaytimeStress.scoreRust(allNull, 0L))
    }
}

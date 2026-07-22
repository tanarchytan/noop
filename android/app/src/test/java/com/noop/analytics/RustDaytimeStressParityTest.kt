package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FFI smoke test for daytime stress scoring. `DaytimeStress.scoreRust` keeps the Kotlin bucketing, scores
 * each waking hour in physio-algo (via [RustScores.daytimeStress] -> uniffi -> whoop-rs `daytime_stress`)
 * and reassembles the timeline (unscored + non-waking hours dropped). This pins that end-to-end behaviour;
 * the calm-quartile z + logistic math and its exact levels are frozen by the Rust golden tests. Loads the
 * host libwhoop_ffi via JNA (buildRustHostDll).
 */
class RustDaytimeStressParityTest {

    private fun agg(hour: Int, meanHr: Double?, rmssd: Double?) =
        DaytimeStress.HourAgg(hour * 3600L, meanHr, rmssd)

    @Test
    fun `a scored day places levels on the shared 0-3 scale`() {
        val aggs = (6..20).map { h -> agg(h, meanHr = 62.0 + (h - 6) * 1.7, rmssd = 55.0 - (h - 6) * 1.3) }
        val r = DaytimeStress.scoreRust(aggs, 0L)
        assertEquals("all waking hours kept", 15, r.hours.size)
        assertTrue("every level is in [0,3]", r.hours.mapNotNull { it.level }.all { it in 0.0..3.0 })
        assertNotNull("scored day has a mean", r.dayMean)
        assertNotNull("scored day has a peak", r.peak)
    }

    @Test
    fun `unscored and non-waking hours reassemble out of the timeline`() {
        val aggs = listOf(
            agg(2, 60.0, 50.0),   // non-waking (2am) -> excluded
            agg(7, null, 48.0),   // waking but no HR -> kept, level null
            agg(8, 68.0, 44.0),
            agg(9, 91.0, 26.0),   // tense hour
            agg(10, 72.0, null),  // scored on HR alone
            agg(23, 58.0, 60.0),  // non-waking (11pm) -> excluded
        )
        val r = DaytimeStress.scoreRust(aggs, 0L)
        assertEquals("only waking hours 7..10 survive", listOf(7, 8, 9, 10), r.hours.map { it.hour })
        assertNull("7am had no HR -> unscored", r.hours.first { it.hour == 7 }.level)
        assertEquals("wall start undoes the tz shift", 8 * 3600L, r.hours.first { it.hour == 8 }.startTs)
    }

    @Test
    fun `a sustained afternoon high is flagged`() {
        val aggs = (6..13).map { agg(it, 66.0, 46.0) } +
            (14..17).map { agg(it, 92.0 + (it - 14) * 0.5, 24.0 - (it - 14) * 0.4) } // 4 tense hours
        val r = DaytimeStress.scoreRust(aggs, 0L)
        assertTrue("afternoon spike reads sustained-high", r.sustainedHigh)
        assertTrue("run backs the flag", r.sustainedRun >= DaytimeStress.sustainedHours)
    }

    @Test
    fun `empty and all-unscored days are empty`() {
        assertEquals(DaytimeStress.Result.EMPTY, DaytimeStress.scoreRust(emptyList(), 0L))
        val allNull = (6..12).map { agg(it, null, null) }
        val r = DaytimeStress.scoreRust(allNull, 0L)
        assertNull("no scorable hour -> no mean", r.dayMean)
        assertTrue("no hour reads as high", !r.sustainedHigh)
    }
}

package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FFI smoke test for the night derivation. [DaytimeStress.analyzeNight] reuses the day path's own
 * bucketing and scores it through [RustScores.sleepStress] -> uniffi -> whoop-rs `sleep_stress`, which
 * applies no hour-of-day filter. Pins that a night crossing midnight survives, and that the band
 * minutes and high share come back from whoop-rs rather than being re-derived here.
 */
class RustSleepStressParityTest {

    /** One hour of 1 Hz HR from [hour]:00, above the mean-HR sample gate, with a beat every second. */
    private fun hourSamples(hour: Int, bpm: Int, rrMs: Int): Pair<List<HrSample>, List<RrInterval>> {
        val base = hour * 3600L
        val hr = (0 until 3600).map { HrSample("d", base + it, bpm) }
        val rr = (0 until 3600).map { RrInterval("d", base + it, rrMs + (it % 5)) }
        return hr to rr
    }

    private fun night(hours: List<Int>, bpm: (Int) -> Int, rr: (Int) -> Int): Pair<List<HrSample>, List<RrInterval>> {
        val hr = ArrayList<HrSample>()
        val beats = ArrayList<RrInterval>()
        hours.forEach { h ->
            val (a, b) = hourSamples(h, bpm(h), rr(h))
            hr += a
            beats += b
        }
        return hr to beats
    }

    @Test
    fun `a night crossing midnight keeps every hour the day window would drop`() {
        val hours = listOf(22, 23, 0, 1, 2, 3, 4, 5)
        val (hr, rr) = night(hours, bpm = { 58 + it % 4 }, rr = { 980 - (it % 4) * 7 })
        val info = DaytimeStress.analyzeNight(hr, rr, 0L)
        assertEquals("every night hour scored", hours.size, info.hours.size)
        assertEquals("hours come back in bucket order", listOf(0, 1, 2, 3, 4, 5, 22, 23), info.hours.map { it.hour })
        assertTrue("every level is on the shared 0-3 scale", info.hours.all { it.stress in 0.0..3.0 })
        assertNotNull("a scored night has a mean", info.dayMean)
    }

    @Test
    fun `the same night through the day derivation scores nothing`() {
        val hours = listOf(0, 1, 2, 3)
        val (hr, rr) = night(hours, bpm = { 60 }, rr = { 1000 })
        assertTrue("2am is outside the waking window", DaytimeStress.analyze(hr, rr, 0L).scored.isEmpty())
        assertEquals("the night derivation scores it", hours.size, DaytimeStress.analyzeNight(hr, rr, 0L).hours.size)
    }

    @Test
    fun `band minutes tally to the scored hours and back the high share`() {
        val hours = listOf(23, 0, 1, 2, 3, 4)
        val (hr, rr) = night(hours, bpm = { 55 + it * 3 }, rr = { 1020 - it * 40 })
        val info = DaytimeStress.analyzeNight(hr, rr, 0L)
        assertEquals(
            "one hour of band time per scored hour",
            info.hours.size * 60L,
            info.lowMinutes + info.mediumMinutes + info.highMinutes,
        )
        val total = info.lowMinutes + info.mediumMinutes + info.highMinutes
        assertEquals(
            "the share whoop-rs returns is the high band's",
            info.highMinutes * 100.0 / total,
            info.highSharePct!!,
            1e-9,
        )
    }

    @Test
    fun `a night with too little HR scores nothing and has no share`() {
        val hr = (0 until 100).map { HrSample("d", 3600L + it, 60) }
        val info = DaytimeStress.analyzeNight(hr, emptyList(), 0L)
        assertTrue("under the per-hour HR gate, nothing scores", info.hours.isEmpty())
        assertEquals(0L, info.lowMinutes + info.mediumMinutes + info.highMinutes)
        assertNull("an unmeasured night has no share, which is not zero", info.highSharePct)
    }
}

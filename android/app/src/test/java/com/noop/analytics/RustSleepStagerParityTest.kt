package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-side golden + wiring guard for the sleep-engine swap. The 4-class hypnogram now lives only in
 * whoop-rs; this pins the app path ([RustSleepStager] bridge → uniffi → physio-algo) by replaying the
 * canonical crafted night (the same integer-only fixture the Rust `frozen_golden_hypnogram_v2` freezes)
 * through the bridge and asserting the frozen V2 segments, plus that V1 tiles the window contiguously.
 * Segment-for-segment parity with the (now deleted) Kotlin stagers was proven green over the DREAMT
 * fixtures before deletion. Loads the host libwhoop_ffi via JNA (see jna.library.path / buildRustHostDll).
 */
class RustSleepStagerParityTest {

    private val dev = "d"
    private val refMidnight = 1_749_513_600L
    private val start = refMidnight + 3_600L
    private val phase = 90 * 60
    private val dur = phase * 4

    private fun rsaWave(ph: Int, i: Int): Int {
        val amp = intArrayOf(12, 60, 30, 20)[ph]
        return intArrayOf(0, amp, 0, -amp)[i % 4]
    }

    private data class Night(val grav: List<GravitySample>, val hr: List<HrSample>, val rr: List<RrInterval>)

    private fun goldenNight(): Night {
        val grav = ArrayList<GravitySample>(dur)
        val hr = ArrayList<HrSample>(dur)
        val rr = ArrayList<RrInterval>(dur)
        for (i in 0 until dur) {
            val ts = start + i
            val ph = i / phase
            val restless = ph == 3 && (i % 20) < 6
            grav.add(if (restless) GravitySample(dev, ts, 0.2, 0.15, 0.96) else GravitySample(dev, ts, 0.0, 0.0, 1.0))
            val bpm = when (ph) {
                0 -> 50
                1 -> 54 + intArrayOf(0, 1, 2, 3, 2, 1)[(i / 20) % 6]
                2 -> 56 + (i / 60) % 4
                else -> 66 + (i / 30) % 6
            }
            hr.add(HrSample(dev, ts, bpm))
            rr.add(RrInterval(dev, ts, 60_000 / bpm + rsaWave(ph, i)))
        }
        return Night(grav, hr, rr)
    }

    @Test
    fun `rust v2 reproduces the frozen golden hypnogram through the bridge`() {
        val n = goldenNight()
        val end = start + dur
        val segs = RustSleepStager.stage(v2 = true, start = start, end = end,
            grav = n.grav, hr = n.hr, rr = n.rr, resp = emptyList())
        val golden = listOf(
            Triple(0L, 5070L, "deep"),
            Triple(5070L, 5280L, "light"),
            Triple(5280L, 5550L, "rem"),
            Triple(5550L, 10740L, "light"),
            Triple(10740L, 16290L, "rem"),
            Triple(16290L, 21600L, "wake"),
        )
        assertEquals("segment count", golden.size, segs.size)
        for (i in golden.indices) {
            assertEquals("seg $i start", start + golden[i].first, segs[i].start)
            assertEquals("seg $i end", start + golden[i].second, segs[i].end)
            assertEquals("seg $i stage", golden[i].third, segs[i].stage)
        }
    }

    @Test
    fun `rust v1 tiles the window contiguously through the bridge`() {
        val n = goldenNight()
        val end = start + dur
        val segs = RustSleepStager.stage(v2 = false, start = start, end = end,
            grav = n.grav, hr = n.hr, rr = n.rr, resp = emptyList())
        assertTrue("V1 produced segments", segs.isNotEmpty())
        assertEquals("V1 tiles from start", start, segs.first().start)
        assertEquals("V1 tiles to end", end, segs.last().end)
        for (i in 1 until segs.size) assertEquals("contiguous at $i", segs[i - 1].end, segs[i].start)
    }
}

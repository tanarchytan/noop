package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the app sleep-staging path ([RustSleepStager] bridge -> uniffi -> physio-algo): replays a
 * canonical crafted night and asserts the frozen V2 segments. Loads the host libwhoop_ffi via JNA.
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
        val segs = RustSleepStager.stage(start = start, end = end,
            grav = n.grav, hr = n.hr, rr = n.rr, resp = emptyList(), steps = emptyList())
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

}

package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FFI smoke test for short-nap detection ([NapDetector.evaluate] -> [RustScores.napEvaluate] -> uniffi ->
 * whoop-rs `nap`). The dense-gravity gate, longest-quiet-run and the length + HR-settled gates live in
 * physio-algo; this pins the marshalled tri-state behaviour end-to-end (verdict, candidate bounds, mean HR,
 * bounded confidence, the config knobs). Exact values are frozen by the Rust golden tests. Loads the host
 * libwhoop_ffi via JNA (buildRustHostDll).
 */
class RustNapParityTest {

    private val cfg = NapConfig(enabled = true)

    /** Gravity every `step` s over `[t0, t0+dur)`, alternating x by `moveG` so each record's L2 delta = moveG. */
    private fun grav(t0: Long, dur: Long, moveG: Double, step: Long = 30): List<GravitySample> {
        val out = ArrayList<GravitySample>()
        var t = t0; var x = 0.0; var flip = 1.0
        while (t < t0 + dur) {
            x += flip * moveG; flip = -flip
            out.add(GravitySample("d", t, x, 0.0, 0.0))
            t += step
        }
        return out
    }

    private fun hrFlat(t0: Long, dur: Long, bpm: Int): List<HrRow> =
        (0 until dur step 30).map { HrRow(t0 + it, bpm) }

    @Test
    fun `still settled window is a nap with a bounded candidate`() {
        val d = NapDetector.evaluate(grav(1000, 40 * 60, 0.0), hrFlat(1000, 40 * 60, 57), 55, cfg)
        assertEquals(NapVerdict.NAP, d.verdict)
        assertNotNull("candidate present", d.candidate)
        val c = d.candidate!!
        assertEquals(57, c.meanHr)
        assertTrue("confidence in [0,1]", c.confidence in 0.0..1.0)
        assertTrue("candidate spans the quiet run", c.durationS >= 20 * 60)
    }

    @Test
    fun `still but elevated HR is none`() {
        assertEquals(NapVerdict.NONE, NapDetector.evaluate(grav(1000, 40 * 60, 0.0), hrFlat(1000, 40 * 60, 80), 55, cfg).verdict)
    }

    @Test
    fun `a moving window is none`() {
        assertEquals(NapVerdict.NONE, NapDetector.evaluate(grav(1000, 40 * 60, 0.3), emptyList(), 55, cfg).verdict)
    }

    @Test
    fun `a too-long run is inconclusive`() {
        assertEquals(NapVerdict.INCONCLUSIVE, NapDetector.evaluate(grav(1000, 120 * 60, 0.0), emptyList(), 55, cfg).verdict)
    }

    @Test
    fun `a sparse window is inconclusive, never none`() {
        assertEquals(NapVerdict.INCONCLUSIVE, NapDetector.evaluate(grav(0, 40 * 300, 0.0, step = 300), emptyList(), 55, cfg).verdict)
    }

    @Test
    fun `disabled is inconclusive with no candidate`() {
        val d = NapDetector.evaluate(grav(1000, 40 * 60, 0.0), emptyList(), 55, NapConfig(enabled = false))
        assertEquals(NapVerdict.INCONCLUSIVE, d.verdict)
        assertNull(d.candidate)
    }

    @Test
    fun `unknown resting HR still naps, at a capped confidence`() {
        val d = NapDetector.evaluate(grav(1000, 40 * 60, 0.0), emptyList(), null, cfg)
        assertEquals(NapVerdict.NAP, d.verdict)
        assertTrue("no HR band -> confidence capped at 0.7", d.candidate!!.confidence <= 0.7)
    }

    @Test
    fun `the HR-settle margin knob flips the verdict`() {
        val g = grav(1000, 40 * 60, 0.0)
        val hr = hrFlat(1000, 40 * 60, 68) // resting 55; default margin 8 -> gate 63 -> awake
        assertEquals(NapVerdict.NONE, NapDetector.evaluate(g, hr, 55, cfg).verdict)
        assertEquals(NapVerdict.NAP, NapDetector.evaluate(g, hr, 55, cfg.copy(hrSettleMarginBpm = 20)).verdict)
    }
}

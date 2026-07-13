package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Spo2Estimate] — the OPT-IN wellness-tier (uncalibrated, non-clinical) SpO₂ estimate from raw red/IR PPG
 * (ratio-of-ratios). Twin of the Swift `Spo2EstimateTests`.
 *
 * Per the derived-biosignal standard: a synthetic method must recover MULTIPLE injected values, not one
 * coincidental match. Each window is engineered so the ratio-of-ratios R yields a KNOWN SpO₂; the tests
 * recover ~97 / ~94 / ~90 % (and the median of several), plus the honest nil on a flat / absent signal.
 */
class Spo2EstimateTest {

    /**
     * Build ONE 30-sample window (parallel red/ir) engineered so the estimate recovers [targetSpo2]:
     * both channels are half at DC−AC/2, half at DC+AC/2, so mean = DC and p95−p5 = AC exactly. The IR
     * channel is fixed (DC 1000, AC 20 → ratio 0.02); the red AC is chosen so
     * R = (AC_red/DC_red)/(AC_ir/DC_ir) = (110 − targetSpo2)/25, i.e. SpO₂ = 110 − 25·R = targetSpo2.
     */
    private fun window(targetSpo2: Double, samples: Int = 30): Pair<List<Double>, List<Double>> {
        val r = (Spo2Estimate.CURVE_A - targetSpo2) / Spo2Estimate.CURVE_B
        val dcIr = 1000.0
        val acIr = 20.0
        val dcRed = 1000.0
        val acRed = r * (acIr / dcIr) * dcRed
        val half = samples / 2
        val red = ArrayList<Double>(samples)
        val ir = ArrayList<Double>(samples)
        repeat(half) { red.add(dcRed - acRed / 2.0); ir.add(dcIr - acIr / 2.0) }
        repeat(samples - half) { red.add(dcRed + acRed / 2.0); ir.add(dcIr + acIr / 2.0) }
        return red to ir
    }

    @Test
    fun recoversMultipleInjectedValues() {
        for (target in listOf(97.0, 94.0, 90.0)) {
            val (red, ir) = window(target)
            val got = Spo2Estimate.compute(red, ir)!!
            assertEquals("SpO₂ for injected $target", target, got, 1.0)
        }
    }

    @Test
    fun nightValueIsMedianOfPerWindowEstimates_oddCount() {
        // Three back-to-back windows engineered to 97 / 94 / 90 → median 94.
        val (r0, i0) = window(97.0)
        val (r1, i1) = window(94.0)
        val (r2, i2) = window(90.0)
        val got = Spo2Estimate.compute(r0 + r1 + r2, i0 + i1 + i2)!!
        assertEquals(94.0, got, 1.0)
    }

    @Test
    fun nightValueIsMedianOfPerWindowEstimates_evenCount() {
        // Two windows engineered to 96 / 92 → median = mean of the two middles = 94.
        val (r0, i0) = window(96.0)
        val (r1, i1) = window(92.0)
        val got = Spo2Estimate.compute(r0 + r1, i0 + i1)!!
        assertEquals(94.0, got, 1.0)
    }

    @Test
    fun clampsToWellnessBand() {
        // A window whose raw curve would give 105 % clamps to 100; one that would give 60 % clamps to 70.
        assertEquals(100.0, Spo2Estimate.compute(window(105.0).first, window(105.0).second)!!, 1e-6)
        assertEquals(70.0, Spo2Estimate.compute(window(60.0).first, window(60.0).second)!!, 1e-6)
    }

    @Test
    fun flatSignal_returnsNull() {
        // No pulsatile amplitude (AC = 0) on either channel → every window is skipped → null (never faked).
        val flat = List(30) { 1000.0 }
        assertNull(Spo2Estimate.compute(flat, flat))
    }

    @Test
    fun emptyInput_returnsNull() {
        // The WHOOP 5.0/MG v18 case: no red/IR banked at all → null → the Blood O₂ card stays No Data.
        assertNull(Spo2Estimate.compute(emptyList(), emptyList()))
    }

    @Test
    fun tooFewSamplesForAWindow_returnsNull() {
        // Below MIN_SAMPLES_PER_WINDOW the only (short) window is skipped → null.
        val (red, ir) = window(97.0, samples = Spo2Estimate.MIN_SAMPLES_PER_WINDOW - 1)
        assertNull(Spo2Estimate.compute(red, ir))
    }
}

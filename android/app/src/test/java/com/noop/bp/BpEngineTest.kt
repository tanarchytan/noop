package com.noop.bp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-function tests for the on-device blood-pressure DSP ([BpEngine]). No Android, no DB: synthetic
 * PPG in, features/estimate out.
 */
class BpEngineTest {

    /** A clean synthetic PPG: fundamental at [hr] bpm + a harmonic, integer ADC counts. */
    private fun syntheticPpg(hr: Double, fs: Int, secs: Int): List<Int> {
        val f = hr / 60.0
        val n = fs * secs
        return (0 until n).map { i ->
            val t = i.toDouble() / fs
            (2048.0 + 400.0 * sin(2 * PI * f * t) + 150.0 * sin(4 * PI * f * t)).roundToInt()
        }
    }

    @Test
    fun extractFeatures_recoversHeartRate() {
        val f = BpEngine.extractFeatures(syntheticPpg(hr = 72.0, fs = 24, secs = 12), 24)
        assertNotNull("clean synthetic PPG must yield features", f)
        assertEquals("HR should recover the fundamental within autocorrelation resolution", 72.0, f!!.hr, 6.0)
        assertTrue("should detect several clean pulses", f.pulses >= 2)
    }

    @Test
    fun estimate_matchesCuffForTheCalibrationSignal() {
        val ppg = syntheticPpg(hr = 72.0, fs = 24, secs = 12)
        val f = BpEngine.extractFeatures(ppg, 24)!!
        // Calibrate to the features of this exact window, then estimate the same window → no delta.
        val cal = BpStore.Calibration(120, 80, f.hr, f.upstrokeMs, f.amplitude, f.widthMs, 0L)
        val e = BpEngine.estimate(cal, f)
        assertEquals("same signal as calibration → systolic == cuff", 120, e.systolic)
        assertEquals("same signal as calibration → diastolic == cuff", 80, e.diastolic)
    }

    @Test
    fun extractFeatures_rejectsTooShortOrFlatSignal() {
        assertNull("under 3 s of signal is untrustworthy", BpEngine.extractFeatures(List(24) { 2048 }, 24))
        assertNull("a flat (DC) window has no pulse", BpEngine.extractFeatures(List(24 * 12) { 2048 }, 24))
    }
}

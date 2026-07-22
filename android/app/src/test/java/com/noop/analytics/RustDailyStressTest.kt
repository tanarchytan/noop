package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * App-side gate for routing the daily autonomic-stress score through whoop-rs. `StressModel.build` now
 * calls [RustScores.dailyStress] for the hero number and every trend point; whoop-rs owns the baseline
 * mean/SD, the z-sum, the logistic squash, AND the 14-day cold-start gate. This checks the bridge against
 * the same closed-form formula and pins the two ADOPTED whoop-rs semantics that differ from the old Kotlin
 * StressModel: (1) a hard 14-day baseline floor (Kotlin scored on any baseline), (2) null under that floor.
 * Loads the host libwhoop_ffi via JNA.
 */
class RustDailyStressTest {

    private fun mean(xs: List<Double>) = xs.sum() / xs.size
    private fun popStd(xs: List<Double>): Double {
        val m = mean(xs)
        return sqrt(xs.sumOf { (it - m) * (it - m) } / xs.size)
    }

    /** The closed form whoop-rs implements, computed here as the reference. */
    private fun expected(rhrT: Double?, hrvT: Double?, baseline: List<Pair<Double?, Double?>>): Double {
        val rhrB = baseline.mapNotNull { it.first }
        val hrvB = baseline.mapNotNull { it.second }
        var raw = 0.0
        if (rhrT != null && rhrB.isNotEmpty()) {
            val sd = popStd(rhrB); if (sd > 0.0001) raw += (rhrT - mean(rhrB)) / sd
        }
        if (hrvT != null && hrvB.isNotEmpty()) {
            val sd = popStd(hrvB); if (sd > 0.0001) raw += (mean(hrvB) - hrvT) / sd
        }
        return (3.0 / (1.0 + exp(-raw))).coerceIn(0.0, 3.0)
    }

    // A 20-day baseline with realistic spread (RHR ~50-59, HRV ~55-64).
    private val baseline: List<Pair<Double?, Double?>> =
        (0 until 20).map { (50.0 + (it % 10)) as Double? to (55.0 + (it % 10)) as Double? }

    @Test
    fun `elevated day matches the closed form`() {
        val rhrT = 65.0; val hrvT = 45.0 // RHR well above, HRV well below -> high stress
        assertEquals(expected(rhrT, hrvT, baseline), RustScores.dailyStress(rhrT, hrvT, baseline)!!, 1e-9)
    }

    @Test
    fun `rhr-only and hrv-only days match`() {
        assertEquals(expected(64.0, null, baseline), RustScores.dailyStress(64.0, null, baseline)!!, 1e-9)
        assertEquals(expected(null, 44.0, baseline), RustScores.dailyStress(null, 44.0, baseline)!!, 1e-9)
    }

    @Test
    fun `zero-spread baseline is neutral 1_5`() {
        val flat = (0 until 20).map { 55.0 as Double? to 60.0 as Double? }
        assertEquals(1.5, RustScores.dailyStress(65.0, 45.0, flat)!!, 1e-9)
    }

    @Test
    fun `under the 14-day floor returns null (adopted cold-start gate)`() {
        val thirteen = baseline.take(13)
        assertNull(RustScores.dailyStress(65.0, 45.0, thirteen))
        // Exactly 14 scores.
        assertEquals(expected(65.0, 45.0, baseline.take(14)), RustScores.dailyStress(65.0, 45.0, baseline.take(14))!!, 1e-9)
    }

    @Test
    fun `no signal today returns null`() {
        assertNull(RustScores.dailyStress(null, null, baseline))
    }
}

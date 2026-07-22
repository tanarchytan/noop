package com.noop.analytics

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FFI smoke test for the Rest (sleep performance) composite ([RustScores.rest] -> uniffi -> whoop-rs
 * `rest_score`). The 0-100 weighted composite (duration vs need, efficiency, restorative share with the
 * deep-adequacy factor, consistency) lives in physio-algo; this pins the marshalled behaviour (bounded
 * 0..100, null on no sleep, the default-need + neutral-consistency fallbacks, more sleep scores higher).
 * Exact values are frozen by the Rust golden tests. Loads the host libwhoop_ffi via JNA (buildRustHostDll).
 */
class RustRestParityTest {

    private val h = 3600.0

    private fun rest(asleepH: Double, eff: Double, deepH: Double, remH: Double, need: Double?, cons: Double?) =
        RustScores.rest(asleepH * h, eff, deepH * h, remH * h, need, cons)

    @Test
    fun `a full night lands in the 0-100 band`() {
        val r = rest(7.5, 0.92, 1.4, 1.8, 8.0, 0.8)
        assertNotNull(r)
        assertTrue("composite in [0,100]", r!! in 0.0..100.0)
    }

    @Test
    fun `default need and neutral consistency resolve without error`() {
        val r = rest(6.5, 0.88, 1.0, 1.5, null, null)
        assertNotNull("null need -> 8 h, null consistency -> neutral 0.5", r)
        assertTrue(r!! in 0.0..100.0)
    }

    @Test
    fun `more sleep toward need scores higher`() {
        val short = rest(4.0, 0.90, 0.6, 0.7, 8.0, 0.6)!!
        val full = rest(8.0, 0.90, 1.4, 1.8, 8.0, 0.6)!!
        assertTrue("a fuller, better-structured night outscores a short one", full > short)
    }

    @Test
    fun `over-need duration clamps, so it cannot exceed 100`() {
        val r = rest(10.0, 0.95, 2.0, 2.0, 8.0, 0.6)!!
        assertTrue("duration credit is capped", r <= 100.0)
    }

    @Test
    fun `no asleep time is null`() {
        assertNull(RustScores.rest(0.0, 0.9, h, h, 8.0, 0.5))
        assertNull(RustScores.rest(-1.0, 0.9, h, h, null, null))
    }
}

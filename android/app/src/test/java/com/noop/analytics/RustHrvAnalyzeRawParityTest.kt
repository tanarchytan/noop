package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * FFI smoke test for the full clean-and-analyze pipeline ([HrvAnalyzer.analyzeRaw] ->
 * [RustScores.analyzeRaw] -> uniffi -> whoop-rs `hrv_analyze_raw`). The range filter, Malik ectopic,
 * gap-aware RMSSD/pNN50, SDNN/meanNN, the 20-beat floor and the spot rejected-fraction gate all live in
 * physio-algo; this pins the observable behaviour end-to-end (the widest-blast-radius swap: DaytimeStress,
 * SpotHrv, sleep HRV and the HRV snapshot all read analyzeRaw), catching a bad binding/.so. Exact values
 * are frozen by the Rust golden tests. Loads the host libwhoop_ffi via JNA (buildRustHostDll).
 */
class RustHrvAnalyzeRawParityTest {

    /** Dense clean night (RSA + slow drift), every beat in range, no ectopics. Integer ms (the stored type). */
    private fun goldenNight(n: Int = 240): List<Double> = (0 until n).map { i ->
        val rsa = 35.0 * sin(2.0 * PI * i / 12.0)
        val drift = 10.0 * sin(2.0 * PI * i / 300.0)
        (900.0 + rsa + drift).roundToInt().toDouble()
    }

    @Test
    fun `clean night yields a full populated result`() {
        val r = RustScores.analyzeRaw(goldenNight(), null)
        assertEquals("every beat clean", 240, r.nInput)
        assertEquals("no artifacts dropped", 240, r.nClean)
        assertNotNull("rmssd present", r.rmssd)
        assertNotNull("sdnn present", r.sdnn)
        assertNotNull("pnn50 present", r.pnn50)
        assertNotNull("meanNN present", r.meanNN)
        assertTrue("meanNN near the 900 ms base", r.meanNN!! in 880.0..920.0)
        assertTrue("rmssd positive on an RSA night", r.rmssd!! > 0.0)
    }

    @Test
    fun `ectopic and out-of-range beats are dropped, clean count falls`() {
        val base = goldenNight(60).toMutableList()
        base[10] = 5.0     // out of range low
        base[20] = 1300.0  // Malik-ectopic spike
        base[30] = 2500.0  // out of range high
        base[45] = 1250.0  // second ectopic spike
        val r = RustScores.analyzeRaw(base, null)
        assertEquals("raw count is the input length", 60, r.nInput)
        assertTrue("clean count dropped below input", r.nClean < 60)
        assertTrue("still enough clean beats to score", r.nClean >= HrvAnalyzer.MIN_BEATS)
        assertNotNull(r.rmssd)
    }

    @Test
    fun `below the 20-beat floor is empty`() {
        for (rr in listOf(listOf(800.0, 810.0, 820.0), goldenNight(19), emptyList())) {
            val r = RustScores.analyzeRaw(rr, null)
            assertNull("rmssd empty under the floor", r.rmssd)
            assertEquals("no clean beats scored", 0, r.nClean)
            assertEquals("nInput preserved", rr.size, r.nInput)
        }
    }

    @Test
    fun `spot rejected-fraction gate refuses a too-noisy capture`() {
        // 25 clean + 20 out-of-range = 45 input, ~0.44 rejected.
        val rr = goldenNight(25) + List(20) { 100.0 }
        assertNotNull("open gate scores", RustScores.analyzeRaw(rr, null).rmssd)
        assertNull("0.35 ceiling refuses", RustScores.analyzeRaw(rr, 0.35).rmssd)
        assertNotNull("0.50 ceiling admits", RustScores.analyzeRaw(rr, 0.50).rmssd)
    }
}

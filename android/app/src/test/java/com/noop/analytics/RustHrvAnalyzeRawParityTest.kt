package com.noop.analytics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Continuity gate for routing [HrvAnalyzer.analyzeRaw] through whoop-rs. The full clean-and-analyze
 * pipeline (range filter -> Malik ectopic -> gap-aware RMSSD/pNN50 + SDNN/meanNN + the 20-beat floor +
 * the spot rejected-fraction gate) moved to physio-algo (via [RustScores.analyzeRaw] -> uniffi ->
 * whoop-rs `hrv_analyze_raw`). [HrvAnalyzer.analyzeRawKotlin] is retained as the ORACLE; this replays the
 * SAME raw R-R through both and asserts the whole [HrvAnalyzer.HrvResult] is identical -- the data-class
 * equality compares each `Double?` (rmssd, sdnn, meanNN, pnn50) by raw bits, plus nInput / nClean. This is
 * the widest-blast-radius swap in the migration (DaytimeStress bucketing, SpotHrv, sleep HRV, the HRV
 * snapshot + trace all read analyzeRaw), so the whole HrvResult must match, not just the RMSSD headline.
 * Loads the host libwhoop_ffi via JNA (see jna.library.path / buildRustHostDll).
 */
class RustHrvAnalyzeRawParityTest {

    private fun parity(label: String, rr: List<Double>, maxRej: Double? = null) {
        assertEquals(label, HrvAnalyzer.analyzeRawKotlin(rr, maxRej), RustScores.analyzeRaw(rr, maxRej))
    }

    /** Dense clean night (RSA + slow drift), every beat in range, no ectopics. Integer ms (the stored type). */
    private fun goldenNight(n: Int = 240): List<Double> = (0 until n).map { i ->
        val rsa = 35.0 * sin(2.0 * PI * i / 12.0)
        val drift = 10.0 * sin(2.0 * PI * i / 300.0)
        (900.0 + rsa + drift).roundToInt().toDouble()
    }

    @Test
    fun `clean golden night is bit-identical`() = parity("golden", goldenNight())

    @Test
    fun `ectopic and out-of-range beats splice identically`() {
        val base = goldenNight(60).toMutableList()
        base[10] = 5.0 // out of range low -> dropped
        base[20] = 1300.0 // Malik-ectopic spike -> dropped (splice)
        base[30] = 2500.0 // out of range high -> dropped
        base[45] = 1250.0 // second ectopic spike
        parity("mixed-artifacts", base)
    }

    @Test
    fun `below the 20-beat floor is empty on both`() {
        parity("three-beats", listOf(800.0, 810.0, 820.0))
        parity("nineteen-clean", goldenNight(19))
        parity("empty", emptyList())
    }

    @Test
    fun `spot rejected-fraction gate matches at every threshold`() {
        // 25 clean + 20 out-of-range (100 ms) = 45 input; ~0.44 rejected.
        val rr = goldenNight(25) + List(20) { 100.0 }
        parity("gate-open", rr, null)
        parity("gate-strict", rr, 0.35)
        parity("gate-exact-boundary", rr, 20.0 / 45.0)
        parity("gate-loose", rr, 0.50)
    }

    @Test
    fun `real backup nights are bit-identical (whole HrvResult)`() {
        val path = System.getProperty("noop.rrFixture")
            ?: "C:/Users/DavidGillot/Projects/whoop/whoop-data/harnesses/rr-real-fixture.json"
        val f = File(path)
        assumeTrue("real R-R fixture absent (local-only), skipping: $path", f.exists())

        val nights = JSONObject(f.readText()).getJSONArray("nights")
        var scored = 0
        for (i in 0 until nights.length()) {
            val rrJson = nights.getJSONObject(i).getJSONArray("rr")
            val rr = ArrayList<Double>(rrJson.length())
            for (j in 0 until rrJson.length()) rr.add(rrJson.getJSONArray(j).getInt(1).toDouble())
            if (rr.isEmpty()) continue
            parity("real night $i (${rr.size} beats)", rr)
            parity("real night $i spot-gated", rr, HrvAnalyzer.DEFAULT_SPOT_MAX_REJECTED_FRACTION)
            scored++
        }
        assertTrue("fixture present but no night scored", scored > 0)
    }
}

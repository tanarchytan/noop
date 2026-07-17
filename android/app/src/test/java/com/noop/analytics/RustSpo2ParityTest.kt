package com.noop.analytics

import com.noop.data.Spo2Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpO2 parity harness (Tier-1 analytics cutover) — VERDICT: **BLOCKED (mapping mismatch)**.
 *
 * The two sides compute DIFFERENT QUANTITIES, so there is no bit-for-bit route and the Kotlin scorer
 * MUST NOT be deleted:
 *  - Kotlin [AnalyticsEngine.nightlySpo2RawMeans] returns `Pair<Int,Int>` = the integer-truncated RAW
 *    red/IR PPG ADC means over the in-bed span. Those two ints are stored verbatim as the Room columns
 *    `DailyMetric.spo2Red` / `DailyMetric.spo2Ir`. The scorer deliberately does NOT derive a blood-oxygen
 *    percent ("computing one needs WHOOP's proprietary curve, so we surface the RAW means only").
 *  - FFI [RustScores.spo2FromPaired] (`physio-algo` `Spo2::from_paired`) returns a single `Double?` = an
 *    SpO2 PERCENT via ratio-of-ratios (`110 − 25·R`, clamped 70–100) off an uncalibrated wellness curve.
 *
 * A raw-ADC mean (tens of thousands) can never equal a clamped 70–100 percent, and one FFI value can never
 * reconstruct the two stored ADC columns. Asserting parity here would require faking it — so this test
 * instead PROVES the mismatch on shared inputs and pins the stored Kotlin value, documenting the block.
 *
 * FIX REQUEST (whoop-rs / parallel-agent lane): to route SpO2, physio-algo needs a `nightly_spo2_raw_means`
 * FFI that returns the `(red_mean_i32, ir_mean_i32)` pair with the SAME in-span filter + `sum/kept` integer
 * truncation the Kotlin uses — NOT the ratio-of-ratios percent. Until that door exists (or the store seam
 * is intentionally re-mapped, which would touch a Room column and is out of scope here), SpO2 stays Kotlin.
 *
 * No real red/IR fixture exists in-tree (real_frames.json carries v26 PPG/HR, not the 4.0 v24 red/IR pair),
 * so the shared input is a pulsatile red/IR ADC waveform — enough to make from_paired yield a concrete %
 * and expose the domain gap. Loads the host libwhoop_ffi via JNA (jna.library.path / buildRustHostDll).
 */
class RustSpo2ParityTest {

    private val dev = "my-whoop"

    private fun sleep(start: Long, end: Long) =
        DetectedSleep(start = start, end = end, efficiency = 0.9, stages = emptyList(),
            restingHR = 55, avgHRV = 60.0)

    /**
     * One in-bed span with a pulsatile red/IR ADC stream: 20 per-second samples, all inside [1000,2000].
     * red alternates 29000/31000 (mean 30000, AC ≈ 2000), ir alternates 19000/21000 (mean 20000, AC ≈ 2000),
     * so R = (2000/30000)/(2000/20000) ≈ 0.667 → a real, in-band SpO2 %. Deterministic and integer-clean.
     */
    private fun sleepSamples(): Pair<List<DetectedSleep>, List<Spo2Sample>> {
        val sessions = listOf(sleep(1000, 2000))
        val samples = (0 until 20).map { i ->
            Spo2Sample(
                deviceId = dev,
                ts = 1000L + i,
                red = if (i % 2 == 0) 29000 else 31000,
                ir = if (i % 2 == 0) 19000 else 21000,
            )
        }
        return sessions to samples
    }

    @Test
    fun `kotlin stores raw ADC means, rust returns a percent - BLOCKED mapping mismatch`() {
        val (sessions, samples) = sleepSamples()

        // Kotlin side: the exact Room column values (integer-truncated raw ADC means).
        val stored = AnalyticsEngine.nightlySpo2RawMeans(sessions, samples)
        assertNotNull("Kotlin scorer should produce raw means for the in-span samples", stored)
        val (storedRed, storedIr) = stored!!
        assertEquals("stored spo2Red (raw ADC mean)", 30000, storedRed)
        assertEquals("stored spo2Ir (raw ADC mean)", 20000, storedIr)

        // Rust FFI side: feed the SAME kept samples' red/IR streams (ts order) as Doubles.
        val kept = samples.filter { s -> sessions.any { s.ts in it.start..it.end } }.sortedBy { it.ts }
        val reds = kept.map { it.red.toDouble() }
        val irs = kept.map { it.ir.toDouble() }
        val pct = RustScores.spo2FromPaired(reds, irs)

        // The FFI computes a wellness PERCENT (clamped 70–100), a different quantity from the stored ADC means.
        assertNotNull("from_paired should yield a percent for the pulsatile window", pct)
        assertTrue("FFI returns an SpO2 percent in [70,100], not an ADC value: $pct",
            pct!! in 70.0..100.0)

        // The core BLOCKED proof: the stored raw ADC means are far outside the percent domain, so no
        // bit-for-bit mapping exists in either direction. Do NOT assert equality — it would be fake.
        assertTrue("stored spo2Red $storedRed is a raw ADC, never a 70–100 percent",
            storedRed.toDouble() !in 70.0..100.0)
        assertTrue("stored spo2Ir $storedIr is a raw ADC, never a 70–100 percent",
            storedIr.toDouble() !in 70.0..100.0)
        assertTrue("percent $pct cannot reconstruct the (red,ir) ADC pair ($storedRed,$storedIr)",
            pct != storedRed.toDouble() && pct != storedIr.toDouble())
    }
}

package com.noop.analytics

import com.noop.data.Spo2Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * SpO2 raw-means parity harness (Tier-1 analytics cutover) — VERDICT: **ROUTED**.
 *
 * The stored `DailyMetric.spo2Red` / `DailyMetric.spo2Ir` are the integer-truncated RAW red/IR PPG ADC
 * means over the in-bed span (never a calibrated percent — that needs WHOOP's proprietary curve). Those
 * two ints are now scored in whoop-rs physio-algo via [RustScores.nightlySpo2RawMeans], and the Kotlin
 * `AnalyticsEngine.nightlySpo2RawMeans` scorer has been deleted.
 *
 * This test pins the Rust FFI bit-for-bit against a Kotlin reference of the exact deleted loop (`sum/kept`
 * integer truncation, any-span inclusive `[start, end]` filter, i64 accumulation). Two ints, so equality
 * IS the exact-bits contract. No real 4.0 v24 red/IR fixture exists in-tree (real_frames.json carries v26
 * PPG/HR), so the shared inputs are deterministic crafted red/IR ADC streams exercising truncation,
 * inclusive boundaries, multi-session union, and the no-in-span/empty null paths. Loads the host
 * libwhoop_ffi via JNA (jna.library.path / buildRustHostDll).
 */
class RustSpo2ParityTest {

    private val dev = "my-whoop"

    private fun sleep(start: Long, end: Long) =
        DetectedSleep(start = start, end = end, efficiency = 0.9, stages = emptyList(),
            restingHR = 55, avgHRV = 60.0)

    private fun spo2(ts: Long, red: Int, ir: Int) = Spo2Sample(deviceId = dev, ts = ts, red = red, ir = ir)

    /** Kotlin reference = the exact deleted `AnalyticsEngine.nightlySpo2RawMeans` loop: i64 accumulation,
     *  any-span inclusive filter, `sum/kept` truncation toward zero. The value the app stored. */
    private fun kotlinRawMeans(sessions: List<DetectedSleep>, spo2: List<Spo2Sample>): Pair<Int, Int>? {
        if (sessions.isEmpty() || spo2.isEmpty()) return null
        var redSum = 0L
        var irSum = 0L
        var kept = 0
        for (s in spo2) {
            if (sessions.none { s.ts in it.start..it.end }) continue
            redSum += s.red
            irSum += s.ir
            kept++
        }
        if (kept == 0) return null
        return (redSum / kept).toInt() to (irSum / kept).toInt()
    }

    private fun assertParity(msg: String, sessions: List<DetectedSleep>, spo2: List<Spo2Sample>) {
        val kotlin = kotlinRawMeans(sessions, spo2)
        val rust = RustScores.nightlySpo2RawMeans(sessions, spo2)
        assertEquals("$msg raw means (Kotlin=$kotlin Rust=$rust)", kotlin, rust)
    }

    @Test
    fun `rust raw means match the kotlin stored value bit-for-bit`() {
        // Pulsatile in-span window: 20 per-second samples, red mean 30000, ir mean 20000 (clean division).
        val pulsatile = (0 until 20).map { i ->
            spo2(1000L + i, red = if (i % 2 == 0) 29000 else 31000, ir = if (i % 2 == 0) 19000 else 21000)
        }
        assertParity("pulsatile window", listOf(sleep(1000, 2000)), pulsatile)

        // Truncation toward zero: three reds summing to 91001 / 3 = 30333.67 → 30333.
        val trunc = listOf(spo2(1100, 30000, 20000), spo2(1200, 30500, 20500), spo2(1300, 30501, 20999))
        assertParity("truncation", listOf(sleep(1000, 2000)), trunc)

        // Inclusive boundaries + out-of-span drops.
        val boundaries = listOf(
            spo2(999, 9, 9), spo2(1000, 100, 200), spo2(2000, 300, 400), spo2(2001, 9, 9),
        )
        assertParity("inclusive boundaries", listOf(sleep(1000, 2000)), boundaries)

        // Multi-session union with an inter-session gap sample dropped.
        val multi = listOf(spo2(1200, 10, 20), spo2(2000, 99, 99), spo2(3200, 30, 40))
        assertParity("multi-session union", listOf(sleep(1000, 1500), sleep(3000, 3500)), multi)

        // Sanity: the routed value is a concrete raw-ADC pair, not null, on the pulsatile window.
        val stored = RustScores.nightlySpo2RawMeans(listOf(sleep(1000, 2000)), pulsatile)
        assertNotNull("pulsatile window should produce raw means", stored)
        assertEquals(30000 to 20000, stored)
    }

    @Test
    fun `rust matches the kotlin null paths`() {
        assertParity("empty sessions", emptyList(), listOf(spo2(100, 1, 1)))
        assertParity("empty samples", listOf(sleep(0, 1000)), emptyList())
        assertParity("no in-span sample", listOf(sleep(1000, 2000)), listOf(spo2(500, 1, 1), spo2(2500, 2, 2)))
    }
}

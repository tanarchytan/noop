package com.noop.analytics

import com.noop.data.RrInterval
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * Post-cutover regression guard for the respiratory-rate route (analytics cutover, Tier 1). The Kotlin
 * scorer `SleepStager.respRateFromRR` has been DELETED; respiratory rate now scores only in whoop-rs
 * physio-algo, reached via [RustScores.respRateFromRr]. Its bit-for-bit parity with the stored Kotlin
 * value was PROVEN before deletion (the empirical analog of the decode cutover's 869/869); this guard
 * keeps the Rust leg pinned to that frozen spec so a later whoop-rs change can't silently drift it.
 *
 * The stored column is [com.noop.data.DailyMetric.respRateBpm], produced at AnalyticsEngine.kt as
 * `median(per-session respRateFromRr that .isFinite())`. The cross-session median (HrvAnalyzer.median) is
 * app-side and STAYS Kotlin, so the FFI only reproduces the per-session estimate; on a one-session night
 * `median(listOf(x)) == x`, so the per-session value IS the stored value. This test pins that per-session
 * value bit-for-bit.
 *
 * The Kotlin twin is gone, so the reference here is [respRateReference]: a self-contained restatement of the
 * stored RSA spec — range-filter → cumulative beat times → 4 Hz linear resample → centered-mean detrend →
 * per-5-min findPeaks → `60.0 / median(intervals)` → median across windows → plausible-band clamp. It reuses
 * the surviving primitives ([SleepStager.findPeaks], [SleepStager.standardDeviation], [HrvAnalyzer.rangeFilter],
 * [HrvAnalyzer.median], [SleepStager.respPlausibleRangeBpm]) so it stays byte-identical to the deleted scorer.
 *
 * The gate is EXACT (delta 0.0). The pipeline is float-accumulation-order sensitive, and trap #1 (the
 * averaging-of-middles median tie-break in both the per-window `60.0/median` and the across-window median) is
 * the likely divergence. A nonzero delta is a FAIL to report as a whoop-rs fix request, NOT a tolerance to
 * widen: any drift means the port isn't bit-identical, which is exactly what this gate exists to catch.
 *
 * No-data equivalence: the reference returns `Double.NaN` for a session that yields no finite estimate; the
 * FFI returns `null` (Rust `Option<f64>::None`). The store seam treats both as "no value" (Kotlin drops NaN
 * via `.isFinite()`; a null session contributes nothing), so NaN ⟺ null is the correct stored mapping and both
 * are normalized to `null` before comparison.
 *
 * Fixture is LOCAL-only (personal R-R from a noop backup, not committed); the test SKIPS when absent so CI
 * stays green. Override the path with -Dnoop.rrFixture=<file>. Loads the host libwhoop_ffi via JNA (see
 * jna.library.path / buildRustHostDll), so the Rust leg decodes+scores through real physio-algo.
 */
class RustRespRateParityTest {

    private val fixturePath: String = System.getProperty("noop.rrFixture")
        ?: "C:/Users/DavidGillot/Projects/whoop/whoop-data/harnesses/rr-real-fixture.json"

    /** Kotlin NaN (no finite estimate) → the FFI's null domain, so the two paths compare like-for-like. */
    private fun kotlinStored(v: Double): Double? = if (v.isFinite()) v else null

    /**
     * The stored per-session respiratory-rate SPEC, restated locally now that `SleepStager.respRateFromRR`
     * is deleted. Byte-identical to that scorer: it inlines the RSA constants and orchestrates the surviving
     * primitives in the exact same order (so float accumulation matches). Returns NaN on honest no-data.
     */
    private fun respRateReference(rr: List<RrInterval>, start: Long, end: Long): Double {
        val nan = Double.NaN
        if (end <= start) return nan

        // RSA constants (were private SleepStager consts, inlined here as the frozen reference).
        val rsaResampleHz = 4.0
        val rsaDetrendWindowS = 8.0
        val rsaMinPeakDistanceS = 2.5
        val rsaWindowS = 300.0
        val rsaMinBreathIntervalS = 2.5
        val rsaMaxBreathIntervalS = 10.0

        // 1. In-bed RR rows in chronological order, range-filtered.
        val inBed = rr.asSequence()
            .filter { it.ts in start..end }
            .sortedBy { it.ts }
            .map { it.rrMs.toDouble() }
            .toList()
        val filtered = HrvAnalyzer.rangeFilter(inBed)
        if (filtered.size < 30) return nan

        // 2. Reconstruct beat times (seconds from session start) by cumulative sum.
        val beatTimes = DoubleArray(filtered.size)
        var acc = 0.0
        for (i in filtered.indices) {
            acc += filtered[i] / 1000.0
            beatTimes[i] = acc
        }
        val totalSpanS = beatTimes[beatTimes.size - 1]
        if (totalSpanS < rsaWindowS / 2.0) return nan

        // 3. Resample onto a uniform grid by linear interpolation.
        val dt = 1.0 / rsaResampleHz
        val nGrid = (totalSpanS / dt).toInt() + 1
        if (nGrid < 8) return nan
        val grid = DoubleArray(nGrid)
        var seg = 0
        for (g in 0 until nGrid) {
            val t = g * dt
            while (seg < beatTimes.size - 2 && beatTimes[seg + 1] < t) seg += 1
            val t0 = beatTimes[seg]
            val t1 = beatTimes[seg + 1]
            val v0 = filtered[seg]
            val v1 = filtered[seg + 1]
            grid[g] = if (t1 <= t0) v0 else {
                val frac = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
                v0 + frac * (v1 - v0)
            }
        }

        // 4. Detrend: subtract a centered moving mean.
        val halfW = maxOf(1, (rsaDetrendWindowS * rsaResampleHz / 2.0).roundToInt())
        val detrended = DoubleArray(nGrid)
        for (i in 0 until nGrid) {
            val lo = maxOf(0, i - halfW)
            val hi = minOf(nGrid - 1, i + halfW)
            var sum = 0.0
            for (j in lo..hi) sum += grid[j]
            val mean = sum / (hi - lo + 1).toDouble()
            detrended[i] = grid[i] - mean
        }
        if (SleepStager.standardDeviation(detrended.toList()) <= 1e-9) return nan

        // 5. Per ~5-min window peak-pick → 60/median(breath interval); median across.
        val minDistSamples = maxOf(2, (rsaMinPeakDistanceS * rsaResampleHz).roundToInt())
        val windowSamples = maxOf(minDistSamples * 3, (rsaWindowS * rsaResampleHz).roundToInt())
        val perWindowRates = ArrayList<Double>()
        var w = 0
        while (w < nGrid) {
            val wEnd = minOf(nGrid, w + windowSamples)
            if (wEnd - w >= minDistSamples * 3) {
                val winSeg = ArrayList<Double>(wEnd - w)
                for (k in w until wEnd) winSeg.add(detrended[k])
                val peaks = SleepStager.findPeaks(winSeg, distance = minDistSamples, height = 0.0)
                if (peaks.size >= 3) {
                    val intervals = ArrayList<Double>(peaks.size - 1)
                    for (i in 1 until peaks.size) {
                        val ivS = (peaks[i] - peaks[i - 1]).toDouble() * dt
                        if (ivS in rsaMinBreathIntervalS..rsaMaxBreathIntervalS) intervals.add(ivS)
                    }
                    if (intervals.size >= 2) {
                        val med = HrvAnalyzer.median(intervals)
                        if (med > 0.0) perWindowRates.add(60.0 / med)
                    }
                }
            }
            w += windowSamples
        }
        if (perWindowRates.isEmpty()) return nan
        val median = HrvAnalyzer.median(perWindowRates)
        return if (median in SleepStager.respPlausibleRangeBpm) median else nan
    }

    @Test
    fun `rust resp-rate matches the frozen kotlin spec bit-for-bit on real nights`() {
        val f = File(fixturePath)
        assumeTrue("real R-R fixture absent (local-only), skipping: $fixturePath", f.exists())

        val nights = JSONObject(f.readText()).getJSONArray("nights")
        assertTrue("fixture carries no nights", nights.length() > 0)

        var scored = 0
        for (i in 0 until nights.length()) {
            val o = nights.getJSONObject(i)
            val date = o.optString("date", "night$i")
            val start = o.getLong("onset")
            val end = o.getLong("wake")

            val rrJson = o.getJSONArray("rr")
            val rr = ArrayList<RrInterval>(rrJson.length())
            for (j in 0 until rrJson.length()) {
                val p = rrJson.getJSONArray(j)
                rr.add(RrInterval(deviceId = "fixture", ts = p.getLong(0), rrMs = p.getInt(1)))
            }

            val kotlin = kotlinStored(respRateReference(rr, start, end))
            val rust = RustScores.respRateFromRr(rr, start, end)

            if (kotlin == null) {
                assertNull("$date: reference no-data (NaN) but rust returned $rust", rust)
            } else {
                assertNotNull("$date: reference=$kotlin but rust returned null", rust)
                // Exact: the stored respRateBpm Double must be identical, not merely close.
                assertEquals("$date: stored respRateBpm drift (trap #1 median tie-break?)", kotlin, rust!!, 0.0)
                scored++
            }
        }

        // Guard against a vacuous pass where every night was no-data on both sides.
        assertTrue("no night produced a finite resp-rate on either path (fixture too thin?)", scored > 0)
        println("[parity] resp-rate: $scored/${nights.length()} nights matched reference==Rust bit-for-bit")
    }
}

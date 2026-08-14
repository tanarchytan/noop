package com.noop.analytics

import com.noop.data.HrSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

/**
 * Post-cutover regression guard for the resting-HR route (analytics cutover, Tier 1). The two Kotlin
 * resting-HR twins have been DELETED: the live per-session producer (`SleepStager.sessionRestingHR`, which
 * wrote `SleepSession.restingHr`) and the dormant `RecoveryScorer.restingHR`. Resting HR now scores only in
 * whoop-rs physio-algo, reached via [RustScores.sessionRestingHr] (the exact twin of the deleted live
 * function) and [RustScores.dailyRestingHr]. Its bit-for-bit parity with the stored Kotlin value was PROVEN
 * before deletion, the empirical analog of the decode cutover's 869/869.
 *
 * The stored columns this pins:
 *   - per-session floor -> `SleepSession.restingHr` (SleepStager.kt:1088 now calls the FFI).
 *   - min of the per-session floors -> `DailyMetric.restingHr` (AnalyticsEngine.kt:345 now folds via
 *     [RustScores.dailyRestingHr]).
 *
 * The Kotlin twins are gone, so the reference here is [medianReference]: a self-contained restatement of the
 * shipped spec — the MEDIAN bpm over `[start, end]` (inclusive), even counts averaging the two middles,
 * rounded half-UP.
 *
 * The spec MOVED, deliberately: resting HR used to be the lowest 5-min tumbling-bin mean, and read about
 * 10 bpm low against WHOOP's own figure. The median matches it (MAE 1.06), so it is what ships. The floor
 * survives in whoop-rs as `session_resting_hr_floor`, a comparison instrument, and is deliberately NOT
 * exported — so it cannot be pinned from here. Do not read this as the drift the earlier wording forbade:
 * a drift is the FFI disagreeing with the spec, and re-pointing at a spec that changed on purpose is not
 * that. An unexplained disagreement is still FAIL / BLOCKED and is still never weakened.
 *
 * Loads the host libwhoop_ffi via JNA (jna.library.path / buildRustHostDll).
 */
class RustRestingHrParityTest {

    private val dev = "parity"

    /** Real captured-night R-R fixtures (nights[]: onset/wake + rr[[ts,ms]]). Not committed (personal). */
    private val candidates: List<String> = listOfNotNull(
        System.getProperty("noop.restingHrFixture"),
        "C:/Users/DavidGillot/Projects/whoop/whoop-data/harnesses/rr-real-fixture.json",
    )

    private fun fixture(): File? = candidates.map { File(it) }.firstOrNull { it.exists() }

    private data class Night(val onset: Long, val wake: Long, val hr: List<HrSample>)

    /**
     * The stored resting-HR floor SPEC, restated locally now that both Kotlin scorers are deleted: minimum
     * of the 5-min tumbling-bin means over `[start, end]` (inclusive filter, non-overlapping bins), else the
     * whole-segment mean, rounded half-UP; null on an empty window. Byte-identical to the deleted live
     * `SleepStager.sessionRestingHR`, which is what the FFI [RustScores.sessionRestingHr] must reproduce.
     */
    /** The shipped spec: the median bpm in `[start, end]`, even counts averaging the two middle
     *  values, rounded half-up. Null on an empty window (the never-fabricate contract). */
    private fun medianReference(hr: List<HrSample>, start: Long, end: Long): Int? {
        val bpms = hr.filter { it.ts in start..end }.map { it.bpm }.sorted()
        if (bpms.isEmpty()) return null
        val n = bpms.size
        val mid = if (n % 2 == 1) bpms[n / 2].toDouble() else (bpms[n / 2 - 1] + bpms[n / 2]) / 2.0
        return mid.roundToInt()
    }

    /**
     * Derive a dense per-beat HR series from a night's captured R-R: `bpm = round(60000 / rrMs)` at each
     * beat's `ts`. The SAME list feeds both legs, so the gate isolates the floor math (bin anchoring /
     * fallback / rounding), not the derivation.
     */
    private fun loadNights(f: File): List<Night> {
        val arr = JSONObject(f.readText()).getJSONArray("nights")
        val out = ArrayList<Night>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val rr = o.getJSONArray("rr")
            val hr = ArrayList<HrSample>(rr.length())
            for (j in 0 until rr.length()) {
                val p = rr.getJSONArray(j)
                val ms = p.getInt(1)
                if (ms <= 0) continue
                hr.add(HrSample(deviceId = dev, ts = p.getLong(0), bpm = (60_000.0 / ms).roundToInt()))
            }
            out.add(Night(onset = o.getLong("onset"), wake = o.getLong("wake"), hr = hr))
        }
        return out
    }

    @Test
    fun `session and daily resting HR match the stored floor spec on real nights`() {
        val f = fixture()
        assumeTrue("resting-HR fixture absent (local-only), skipping: $candidates", f != null)
        val nights = loadNights(f!!)
        assertTrue("fixture present but no nights loaded", nights.isNotEmpty())

        val refFloors = ArrayList<Int?>(nights.size)
        val rustFloors = ArrayList<Int?>(nights.size)
        for ((idx, n) in nights.withIndex()) {
            // SleepSession.restingHr: the per-session floor (Room-stored Int).
            val kFloor = medianReference(n.hr, n.onset, n.wake)
            val rFloor = RustScores.sessionRestingHr(n.hr, n.onset, n.wake)
            assertEquals("night $idx session median (stored SleepSession.restingHr)", kFloor, rFloor)
            refFloors.add(kFloor)
            rustFloors.add(rFloor)
        }

        // DailyMetric.restingHr: min of the per-session floors, nulls dropped.
        val kDaily = refFloors.filterNotNull().minOrNull()
        val rDaily = RustScores.dailyRestingHr(rustFloors)
        assertEquals("daily resting HR (stored DailyMetric.restingHr)", kDaily, rDaily)
    }

    @Test
    fun `empty window yields null on both legs`() {
        // No HR samples in window -> null (never-fabricate contract), identical on both paths.
        val kFloor = medianReference(emptyList(), start = 0L, end = 1_000L)
        val rFloor = RustScores.sessionRestingHr(emptyList(), start = 0L, end = 1_000L)
        assertEquals("empty-window session floor", null, kFloor)
        assertEquals("empty-window session floor parity", kFloor, rFloor)
    }

    @Test
    fun `session floor matches the spec on a crafted multi-bin window`() {
        // A deterministic in-bed window of three equal 5-min blocks (60, 52, 55 bpm). 900 samples, so the
        // median is the mean of the two middle values, both in the 55 block — isolates ordering and half-up
        // rounding from a fixture. The old floor spec answered 52 here; the shipped median answers 55.
        val start = 1_000L
        val hr = ArrayList<HrSample>()
        for (i in 0 until 300) hr.add(HrSample(dev, start + i, 60))
        for (i in 0 until 300) hr.add(HrSample(dev, start + 300 + i, 52))
        for (i in 0 until 300) hr.add(HrSample(dev, start + 600 + i, 55))
        val end = start + 900
        val kFloor = medianReference(hr, start, end)
        val rFloor = RustScores.sessionRestingHr(hr, start, end)
        assertEquals("crafted session median spec", 55, kFloor)
        assertEquals("crafted session median parity", kFloor, rFloor)
    }

    @Test
    fun `daily fold drops null session floors identically`() {
        // The exact store-seam fold: a day with a missing (null) session floor is skipped, min of the rest.
        val floors = listOf<Int?>(71, null, 66, null, 69)
        val kDaily = floors.filterNotNull().minOrNull()
        val rDaily = RustScores.dailyRestingHr(floors)
        assertEquals("daily fold (mapNotNull.minOrNull) parity", kDaily, rDaily)
    }
}

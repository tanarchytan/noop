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
 *   - per-session MEDIAN -> `SleepSession.restingHr`.
 *   - min of the per-session values -> `DailyMetric.restingHr`.
 *
 * The Kotlin twins are gone, so the reference here is [medianReference]: a self-contained restatement of the
 * stored SPEC - the median of the in-bed samples, even counts averaged, rounded half-UP.
 * [floorReference] restates the lowest-sustained floor this column used to hold, and stays so the gate can
 * SHOW the two statistics disagreeing rather than assume it is reading the new one. The old floor SPEC — the minimum of fixed 5-min tumbling-bin means over `[start, end]` (inclusive), falling
 * back to the whole-segment mean when no bin holds a sample, rounded half-UP. This is exactly the live math
 * that was stored (the #686 win-qualification lived only in the dormant `RecoveryScorer.restingHR` and was
 * NEVER applied to stored data, so the FFI must NOT reproduce it). A drift here is FAIL / BLOCKED, to be
 * reported as a whoop-rs fix request (per-bin rounding / min tie-break / inclusive-window / whole-segment
 * fallback traps), NEVER weakened. Loads the host libwhoop_ffi via JNA (jna.library.path / buildRustHostDll).
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
    private fun floorReference(hr: List<HrSample>, start: Long, end: Long): Int? {
        val seg = hr.filter { it.ts in start..end }
        if (seg.isEmpty()) return null
        val windowS = 5 * 60L
        val means = ArrayList<Double>()
        var t = start
        while (t < end) {
            val win = seg.filter { it.ts >= t && it.ts < t + windowS }
            if (win.isNotEmpty()) means.add(win.sumOf { it.bpm }.toDouble() / win.size.toDouble())
            t += windowS
        }
        val m = means.minOrNull()
        if (m != null) return m.roundToInt()
        return (seg.sumOf { it.bpm }.toDouble() / seg.size.toDouble()).roundToInt()
    }

    /** The stored SPEC: median of the in-bed samples, even counts averaged, rounded half-up. */
    private fun medianReference(hr: List<HrSample>, start: Long, end: Long): Int? {
        val seg = hr.filter { it.ts in start..end }.map { it.bpm }.sorted()
        if (seg.isEmpty()) return null
        val n = seg.size
        val mid = if (n % 2 == 1) seg[n / 2].toDouble() else (seg[n / 2 - 1] + seg[n / 2]) / 2.0
        return kotlin.math.floor(mid + 0.5).toInt()
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
    fun `session and daily resting HR match the stored median spec on real nights`() {
        val f = fixture()
        assumeTrue("resting-HR fixture absent (local-only), skipping: $candidates", f != null)
        val nights = loadNights(f!!)
        assertTrue("fixture present but no nights loaded", nights.isNotEmpty())

        val refFloors = ArrayList<Int?>(nights.size)
        val rustFloors = ArrayList<Int?>(nights.size)
        for ((idx, n) in nights.withIndex()) {
            // SleepSession.restingHr: the per-session floor (Room-stored Int).
            val kMedian = medianReference(n.hr, n.onset, n.wake)
            val rMedian = RustScores.sessionRestingHr(n.hr, n.onset, n.wake)
            assertEquals("night $idx session median (stored SleepSession.restingHr)", kMedian, rMedian)
            // The floor is what this column used to hold; the median must never sit below it, or the
            // gate cannot tell which of the two statistics it is reading.
            val kFloor = floorReference(n.hr, n.onset, n.wake)
            if (kFloor != null && kMedian != null) {
                assertTrue("night $idx median $kMedian below floor $kFloor", kMedian >= kFloor)
            }
            refFloors.add(kMedian)
            rustFloors.add(rMedian)
        }

        // DailyMetric.restingHr: min of the per-session floors, nulls dropped.
        val kDaily = refFloors.filterNotNull().minOrNull()
        val rDaily = RustScores.dailyRestingHr(rustFloors)
        assertEquals("daily resting HR (stored DailyMetric.restingHr)", kDaily, rDaily)
    }

    @Test
    fun `empty window yields null on both legs`() {
        // No HR samples in window -> null (never-fabricate contract), identical on both paths.
        val kMedian = medianReference(emptyList(), start = 0L, end = 1_000L)
        val rMedian = RustScores.sessionRestingHr(emptyList(), start = 0L, end = 1_000L)
        assertEquals("empty-window session value", null, kMedian)
        assertEquals("empty-window session parity", kMedian, rMedian)
    }

    @Test
    fun `session resting HR matches the spec on a crafted multi-bin window`() {
        // A deterministic in-bed window with distinct 5-min bins (60, 52, 55 bpm) so the floor is the real
        // 52-bpm bin on both legs — isolates bin anchoring, min selection and half-up rounding from a fixture.
        val start = 1_000L
        val hr = ArrayList<HrSample>()
        for (i in 0 until 300) hr.add(HrSample(dev, start + i, 60))
        for (i in 0 until 300) hr.add(HrSample(dev, start + 300 + i, 52))
        for (i in 0 until 300) hr.add(HrSample(dev, start + 600 + i, 55))
        val end = start + 900
        val kMedian = medianReference(hr, start, end)
        val rMedian = RustScores.sessionRestingHr(hr, start, end)
        assertEquals("crafted session median spec", 55, kMedian)
        assertEquals("crafted session median parity", kMedian, rMedian)
        assertEquals("the floor this column used to hold", 52, floorReference(hr, start, end))
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

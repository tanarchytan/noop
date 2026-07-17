package com.noop.analytics

import com.noop.data.RrInterval
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.floor

/**
 * Post-cutover regression guard for the Baevsky Stress Index (the "stress" family's stored/display value).
 *
 * The Kotlin scorer `StressIndex.stressIndex` / `StressIndex.components` (the histogram math) has been
 * DELETED; the daily/intraday Stress surfaces (AiCoach `stressIndexLine`, StressScreen) now score in
 * whoop-rs physio-algo (`stress_index` / `stress_components`) via the [RustScores] bridge. Their bit-for-bit
 * parity with the old Kotlin twin was PROVEN before deletion (the empirical analog of the decode cutover's
 * 869/869); this guard keeps the Rust leg pinned to that frozen spec so a later whoop-rs change can't
 * silently drift it.
 *
 * The Kotlin twin is gone, so the reference is restated locally: [refComponents] is a byte-identical copy of
 * the deleted `StressIndex.componentsRaw` (50 ms Baevsky grid, lowest-index modal tie-break, degenerate-range
 * null), reusing the SURVIVING [HrvAnalyzer.cleanRR] range+Malik pipeline and [StressIndex.MIN_BEATS] gate so
 * the reference stays identical to the deleted scorer. SI is pure f64 histogram arithmetic, so both sides
 * must land on the identical Double — a nonzero delta is a FAIL to report as a whoop-rs fix request, NOT a
 * tolerance to widen. Two legs:
 *   1. A deterministic crafted R-R series — always runs (CI-safe), pins the bridge wiring + the four
 *      component terms (Mo / AMo / MxDMn / SI) exactly, and covers the too-few-beats / degenerate-range
 *      null semantics.
 *   2. The real WHOOP 5.0 nightly R-R fixture (whoop5_real_rr.json) — skips when the local-only dataset
 *      is absent, mirroring the other agreement tests' fixture convention.
 *
 * DaytimeStress (the intraday hourly timeline) has no FFI twin exposed in [RustScores] (physio-algo only
 * ships the Baevsky SI door), so it is NOT routed or asserted here — its Kotlin stays until a twin lands.
 *
 * Loads the host libwhoop_ffi via JNA (see jna.library.path / buildRustHostDll), so the Rust leg decodes
 * + scores through the real physio-algo, not a stub.
 */
class RustStressParityTest {

    private val dev = "d"

    /** Exact tolerance: SI is deterministic f64 arithmetic, so the two paths must produce identical bits. */
    private val exact = 0.0

    /** Baevsky's canonical 50 ms cardiointervalography grid (the deleted `StressIndex.BIN_WIDTH_SEC`). */
    private val binWidthSec = 0.05

    /** The four SI histogram terms, mirroring the deleted `StressIndex.Components`, for the frozen reference. */
    private class RefComp(val moSec: Double, val aMoPercent: Double, val mxDMnSec: Double, val si: Double)

    /**
     * Frozen local copy of the deleted `StressIndex.componentsRaw`: clean the R-R (range + Malik) via the
     * surviving [HrvAnalyzer.cleanRR], histogram on the 50 ms grid, modal bin (lowest-index tie-break), then
     * SI = AMo / (2·Mo·MxDMn). Null on too-few clean beats or a degenerate (all-equal) range.
     */
    private fun refComponents(series: List<RrInterval>): RefComp? {
        val clean = HrvAnalyzer.cleanRR(series.map { it.rrMs.toDouble() })
        if (clean.size < StressIndex.MIN_BEATS) return null

        val sec = clean.map { it / 1000.0 }
        val minV = sec.min()
        val maxV = sec.max()
        val mxDMn = maxV - minV
        if (mxDMn <= 0) return null

        val binCount = maxOf(1, floor(mxDMn / binWidthSec).toInt() + 1)
        val counts = IntArray(binCount)
        for (v in sec) {
            var idx = floor((v - minV) / binWidthSec).toInt()
            if (idx < 0) idx = 0
            if (idx >= binCount) idx = binCount - 1
            counts[idx]++
        }
        var modeIdx = 0
        var modeCount = counts[0]
        for (i in 1 until binCount) {
            if (counts[i] > modeCount) {
                modeCount = counts[i]
                modeIdx = i
            }
        }
        val mo = minV + (modeIdx + 0.5) * binWidthSec
        val aMo = modeCount.toDouble() / sec.size.toDouble() * 100.0
        if (mo <= 0) return null
        return RefComp(mo, aMo, mxDMn, aMo / (2.0 * mo * mxDMn))
    }

    private fun refSi(series: List<RrInterval>): Double? = refComponents(series)?.si

    private fun rr(msSeries: List<Int>): List<RrInterval> =
        msSeries.mapIndexed { i, ms -> RrInterval(dev, i.toLong(), ms) }

    /** Assert the SI scalar AND every histogram component match bit-for-bit (or both null). */
    private fun assertParity(label: String, series: List<RrInterval>) {
        val kSi = refSi(series)
        val rSi = RustScores.stressIndex(series)
        val kComp = refComponents(series)
        val rComp = RustScores.stressComponents(series)

        if (kSi == null) {
            assertNull("$label: reference null SI but Rust non-null", rSi)
            assertNull("$label: reference null components but Rust non-null", rComp)
            return
        }
        assertNotNull("$label: reference scored SI $kSi but Rust returned null", rSi)
        assertNotNull("$label: reference scored components but Rust returned null", rComp)
        requireNotNull(kComp)
        requireNotNull(rComp)

        assertEquals("$label: SI scalar", kSi, rSi!!, exact)
        assertEquals("$label: Mo (s)", kComp.moSec, rComp.moSec, exact)
        assertEquals("$label: AMo (%)", kComp.aMoPercent, rComp.amoPercent, exact)
        assertEquals("$label: MxDMn (s)", kComp.mxDMnSec, rComp.mxdmnSec, exact)
        assertEquals("$label: SI (component)", kComp.si, rComp.si, exact)
        // The scalar door and the components door must agree with each other on both sides.
        assertEquals("$label: reference scalar == component SI", kComp.si, kSi, exact)
        assertEquals("$label: Rust scalar == component SI", rComp.si, rSi, exact)
    }

    @Test
    fun `rust reproduces the Baevsky SI + components bit-for-bit on crafted R-R`() {
        // A scorable series with real spread across several 50 ms bins and a clear modal cluster around
        // 800 ms — exercises Mo, AMo (modal share), MxDMn (range) and the SI ratio all at once. This is
        // the same shape the physio-algo hand-computed golden uses, so it must land non-null on both sides.
        val golden = listOf(
            700, 720, 740, 760, 780, 800, 820, 840, 860, 800, 800, 800, 800,
            820, 780, 800, 810, 790, 800, 800, 805, 795,
        )
        val g = rr(golden)
        // Anchor the frozen reference to the hand-computed golden (from the deleted StressIndexTest):
        // MxDMn 0.16, Mo 0.825, AMo 59.0909.., SI 223.829201.. — so a drift in EITHER leg is caught.
        val gc = refComponents(g)
        assertNotNull("crafted golden must be scorable (>= MIN_BEATS clean, non-degenerate range)", gc)
        assertEquals("golden MxDMn", 0.16, gc!!.mxDMnSec, 1e-9)
        assertEquals("golden Mo", 0.825, gc.moSec, 1e-9)
        assertEquals("golden AMo", 59.09090909090909, gc.aMoPercent, 1e-9)
        assertEquals("golden SI", 223.82920110192836, gc.si, 1e-9)
        assertParity("crafted-golden", g)

        // A tighter, more rigid cluster (higher AMo, lower MxDMn) → higher SI; must still match exactly.
        val rigid = (0 until 30).map { if (it % 6 == 0) 810 else 800 }
        assertParity("crafted-rigid", rr(rigid))

        // A broad, flat spread → low SI; different histogram shape, still bit-identical.
        val broad = (0 until 30).map { 700 + (it % 11) * 18 }
        assertParity("crafted-broad", rr(broad))
    }

    @Test
    fun `rust matches Kotlin null semantics on degenerate R-R`() {
        // Too few clean beats (< MIN_BEATS) → honest null on both paths.
        assertParity("too-few-beats", rr(List(StressIndex.MIN_BEATS - 1) { 800 }))
        // All-equal beats → MxDMn 0 → degenerate range → null (not Infinity) on both paths.
        assertParity("degenerate-range", rr(List(30) { 800 }))
    }

    @Test
    fun `rust matches Kotlin SI on the real WHOOP 5_0 nightly R-R fixture`() {
        // Same fixture + resolution convention as the other agreement tests (local-only dataset).
        val dir = System.getProperty("noop.hrvGoldFixtures")
            ?: "C:/Users/DavidGillot/Projects/whoop/whoop data/datasets/agreement-fixtures"
        val f = File("$dir/whoop5_real_rr.json")
        assumeTrue("whoop5 real R-R fixture absent (local-only), skipping: ${f.path}", f.exists())

        val obj = JSONObject(f.readText())
        val windows = obj.getJSONArray("windows")
        var scored = 0
        for (i in 0 until windows.length()) {
            val w = windows.getJSONObject(i)
            val nnArr = w.getJSONArray("nn")
            if (nnArr.length() < 2) continue
            val series = ArrayList<RrInterval>(nnArr.length())
            for (j in 0 until nnArr.length()) series.add(RrInterval(dev, j.toLong(), nnArr.getInt(j)))
            val label = "night ${w.optString("subject", i.toString())}"
            assertParity(label, series)
            if (refSi(series) != null) scored++
        }
        assertTrue("no real nightly windows produced a scorable SI (fixture present but empty)", scored > 0)
        println("[stress] Baevsky SI parity: ${windows.length()} real windows, $scored scored, bit-identical")
    }
}

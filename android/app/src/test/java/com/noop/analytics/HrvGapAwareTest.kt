package com.noop.analytics

import com.noop.data.RrInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gap-aware RMSSD/pNN50 as the app sees it, through [RustScores].
 *
 * When cleaning drops a beat (out-of-range or ectopic), its two neighbours become adjacent in the
 * cleaned list. A plain successive-difference RMSSD counts the difference ACROSS that splice as a real
 * beat-to-beat delta, and because RMSSD squares each delta one splice can dominate a window. These pin
 * the behaviour reachable from Kotlin: no drops leaves the result identical to plain, an interior drop
 * is not spliced, and a series with no usable pair yields null rather than a fabricated zero.
 *
 * The contiguity mask itself is not exposed over the seam; its per-index assertions live beside the
 * implementation in whoop-rs (`clean_rr_gap_aware_drops_ectopic_and_splices`).
 */
class HrvGapAwareTest {

    private fun rows(rr: List<Double>): List<RrInterval> =
        rr.mapIndexed { i, ms -> RrInterval("d", 1_749_513_600L + i, ms.toInt()) }

    // 1. No drops -> gap-aware equals plain, byte for byte.
    @Test
    fun cleanSeriesIsUnchanged() {
        val rr = (0 until 30).map { 800.0 + (it % 5) * 20.0 } // 800..880, all in range, non-ectopic
        val clean = RustScores.cleanRR(rr)
        assertEquals("fixture must have no drops", rr.size, clean.size)

        val plain = RustScores.rmssdRaw(clean)!!
        val gapAware = RustScores.analyzeRaw(rr).rmssd!!
        assertEquals(plain, gapAware, 1e-9)
    }

    // 2. A dropped middle beat must not splice its neighbours (the core fix).
    @Test
    fun midSeriesDropDoesNotSplice() {
        // 12x1000, one out-of-range 5000 (dropped by the range filter), 12x1100. The two flat runs stay
        // within 20% of each other so the ectopic filter keeps them; only the 5000 is removed.
        val rr = List(12) { 1000.0 } + listOf(5000.0) + List(12) { 1100.0 }

        val result = RustScores.analyzeRaw(rr)
        assertEquals(25, result.nInput)
        assertEquals(24, result.nClean)
        // Every kept successive difference is zero (within each flat run); the only nonzero delta is the
        // 1000->1100 step across the removed beat, which is skipped -> RMSSD and pNN50 are 0.
        assertNotNull(result.rmssd)
        assertEquals(0.0, result.rmssd!!, 1e-9)
        assertEquals(0.0, result.pnn50!!, 1e-9)

        // The plain (splicing) RMSSD over the SAME cleaned beats is clearly nonzero: proves divergence.
        val plainSpliced = RustScores.rmssdRaw(RustScores.cleanRR(rr))!!
        assertTrue("plain RMSSD splices the 100 ms jump: $plainSpliced", plainSpliced > 10.0)
    }

    // 3. End-only drops leave the interior untouched, so gap-aware matches plain over the survivors.
    @Test
    fun endDropsLeaveTheInteriorIntact() {
        val rr = List(24) { 800.0 } + List(6) { 100.0 } // 100 ms tail is out of range
        val clean = RustScores.cleanRR(rr)
        assertEquals(24, clean.size)
        assertEquals(RustScores.rmssdRaw(clean), RustScores.analyzeRaw(rr).rmssd)
    }

    // 4. Boundary cases: empty, all-dropped, and a lone survivor between two drops.
    @Test
    fun boundaryCases() {
        assertTrue(RustScores.cleanRR(emptyList()).isEmpty())
        assertNull(RustScores.analyzeRaw(emptyList()).rmssd)

        assertTrue("all out of range", RustScores.cleanRR(List(10) { 5000.0 }).isEmpty())
        assertNull(RustScores.analyzeRaw(List(10) { 5000.0 }).rmssd)

        // Series shorter than the ectopic window is kept verbatim.
        assertEquals(listOf(800.0, 810.0), RustScores.cleanRR(listOf(800.0, 810.0)))

        // One survivor between two dropped beats: no successive pair at all.
        assertEquals(listOf(800.0), RustScores.cleanRR(listOf(5000.0, 800.0, 5000.0)))
        assertNull(RustScores.rmssdGapAware(rows(listOf(5000.0, 800.0, 5000.0))))
    }
}

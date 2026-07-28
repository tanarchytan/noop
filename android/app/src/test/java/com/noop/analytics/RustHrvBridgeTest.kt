package com.noop.analytics

import com.noop.data.RrInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin-to-whoop-rs bridge for nightly HRV. whoop-rs breaks contiguity at a REPORT whose cumulative
 * beat-time has run ahead of the wall clock, so it needs the beats grouped one run per second, the way the
 * strap emitted them. If the bridge handed over one run per beat instead, every beat would look like a
 * report start and the seam test would be applied to the wrong boundaries.
 *
 * The seam rule itself is pinned beside its implementation in whoop-rs; these assert only what Rust cannot
 * see, which is the shape the Kotlin side passes across.
 *
 * This file replaces a Kotlin-vs-Rust golden-value gate. That gate compared `HrvAnalyzer.analyzeRaw`
 * against `hrv_rmssd_gap_aware`, and once the Kotlin HRV maths moved into whoop-rs both of its sides were
 * Rust: `analyze_raw` flattens every beat into one chain and knows no report boundaries, so asserting the
 * two equal asserted that the seam rule does NOT exist. It had no referent left, and is retired rather
 * than weakened.
 */
class RustHrvBridgeTest {

    private fun rows(reports: List<Pair<Long, List<Int>>>): List<RrInterval> =
        reports.flatMap { (ts, rr) -> rr.map { RrInterval("d", ts, it) } }

    /** Twelve reports, each a second apart carrying 3.56 s of beat-time: the strap is re-reporting. */
    private fun overlapping(): List<RrInterval> =
        rows((1L..12L).map { it to listOf(800, 860, 920, 980) })

    /** One beat a second at ~1 s: beat-time tracks the clock, nothing is re-reported. Long enough to
     *  clear the clean-beat floor, so the seam-blind comparison leg returns a value. */
    private fun tracking(): List<RrInterval> =
        rows((1L..24L).map { it to listOf(if (it % 2 == 0L) 1020 else 980) })

    @Test
    fun `the bridge hands whoop-rs one report run per second`() {
        // 48 beats across 12 seconds must arrive as 12 runs. One run per beat would make every beat a
        // report start and move every seam test onto the wrong boundary.
        val (overlappingReports, total) = RustScores.overlappingReports(overlapping())
        assertEquals("one run per distinct second", 12, total)
        assertTrue("a sustained overrun must be counted: $overlappingReports of $total", overlappingReports > 0)

        // A strap that tracks the clock re-reports nothing, so no run may be flagged.
        assertEquals(24 to 0, RustScores.overlappingReports(tracking()).let { it.second to it.first })
    }

    @Test
    fun `grouping by second lets the seam rule fire on a re-reporting strap`() {
        // Every seam is dropped, so only the 60 ms in-report steps count.
        val got = RustScores.rmssdGapAware(overlapping())
        assertNotNull("a dense stream must score", got)
        assertEquals(60.0, got!!, 1e-9)

        val flat = RustScores.analyzeRaw(overlapping().map { it.rrMs.toDouble() }).rmssd!!
        assertTrue("the seam-blind chain must read higher: flat=$flat gapAware=$got", flat > got * 1.5)
    }

    @Test
    fun `grouping leaves a strap that tracks the clock untouched`() {
        // Nothing is re-reported, so no seam may be dropped and every successive pair counts: the bridge
        // must not invent a break. 40 ms is the alternation.
        val got = RustScores.rmssdGapAware(tracking())
        assertNotNull(got)
        assertEquals(40.0, got!!, 1e-9)
        // The same beats through the seam-blind chain agree exactly, which is the selectivity claim.
        assertEquals(RustScores.analyzeRaw(tracking().map { it.rrMs.toDouble() }).rmssd, got)
    }

    @Test
    fun `the windowed session path carries the beats in time order`() {
        // The stored session avgHrv goes through a second bridge, which re-groups by second on the Rust
        // side; this pins that the flattening preserves order, so one bucket reaches the seam-aware answer.
        val got = RustScores.windowedAvgHrv(1, 13, overlapping())
        assertNotNull("a dense bucket must score", got)
        assertEquals(60.0, got!!, 1e-9)
    }
}

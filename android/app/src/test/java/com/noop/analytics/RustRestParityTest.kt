package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * App-side parity gate for routing the Rest (sleep performance) composite through whoop-rs. The 0-100
 * weighted composite is moving from the Kotlin [RestScorer.rest] to physio-algo (via [RustScores.rest] ->
 * uniffi -> whoop-rs `rest_score`); this pins the swap by replaying the SAME night aggregates through BOTH
 * paths and asserting the returned value is bit-for-bit identical (the exact Double [AnalyticsEngine]
 * stores under `sleep_performance`). Loads the host libwhoop_ffi via JNA.
 *
 * `weighted` is a convex combination of non-negative sub-scores, so it is always >= 0; Kotlin's
 * `roundToInt` (half up) and whoop-rs' `f64::round` (half away from zero) agree on every non-negative tie,
 * so the final 2-dp round is identical.
 */
class RustRestParityTest {

    private fun assertBitEq(msg: String, kotlin: Double?, rust: Double?) {
        if (kotlin == null || rust == null) {
            assertEquals(msg, kotlin as Any?, rust as Any?)
            return
        }
        assertEquals(
            "$msg (kotlin=$kotlin rust=$rust)",
            java.lang.Double.doubleToLongBits(kotlin),
            java.lang.Double.doubleToLongBits(rust),
        )
    }

    private fun assertRestParity(
        label: String,
        asleepSeconds: Double,
        efficiency: Double,
        deepSeconds: Double,
        remSeconds: Double,
        sleepNeedHours: Double?,
        consistency: Double?,
    ) {
        val k = RestScorer.rest(asleepSeconds, efficiency, deepSeconds, remSeconds, sleepNeedHours, consistency)
        val r = RustScores.rest(asleepSeconds, efficiency, deepSeconds, remSeconds, sleepNeedHours, consistency)
        assertBitEq("$label", k, r)
    }

    private val h = 3600.0

    @Test
    fun `store-site shaped night matches`() {
        assertRestParity("full-night", 7.5 * h, 0.92, 1.4 * h, 1.8 * h, 8.0, 0.8)
    }

    @Test
    fun `default sleep-need and neutral consistency fall back identically`() {
        assertRestParity("null-need-and-consistency", 6.5 * h, 0.88, 1.0 * h, 1.5 * h, null, null)
    }

    @Test
    fun `zero-deep deep-adequacy factor matches`() {
        assertRestParity("zero-deep", 8.0 * h, 0.90, 0.0, 2.0 * h, 8.0, 1.0)
    }

    @Test
    fun `over-need duration clamps identically`() {
        assertRestParity("over-need", 10.0 * h, 0.95, 2.0 * h, 2.0 * h, 8.0, 0.6)
    }

    @Test
    fun `short rough night matches`() {
        assertRestParity("short", 4.0 * h, 0.80, 0.6 * h, 0.7 * h, 8.0, 0.4)
    }

    @Test
    fun `no asleep time is null on both paths`() {
        assertRestParity("zero-asleep", 0.0, 0.9, h, h, 8.0, 0.5)
        assertRestParity("negative-asleep", -1.0, 0.9, h, h, null, null)
    }
}

package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #585 — the spot honesty gate ([HrvAnalyzer.analyzeRaw]'s optional `maxRejectedFraction`).
 *
 * Swift parity twin of the gate tests in `StrandAnalytics/Tests/.../HRVAnalyzerTests.swift`: a spot
 * reading is REFUSED when too large a fraction of input beats was rejected as noise, even though
 * >= MIN_BEATS clean intervals survive.
 */
class HrvAnalyzerGateTest {

    @Test
    fun spotGateRefusesWhenTooManyBeatsRejected() {
        // 40 input: 24 valid 800 ms + 16 out-of-range 100 ms (dropped by the range filter).
        // 24 clean survive (>= MIN_BEATS 20), but 16/40 = 0.40 rejected > 0.35 gate -> refused (empty).
        val rr = List(24) { 800.0 } + List(16) { 100.0 }
        val gated = HrvAnalyzer.analyzeRaw(rr, maxRejectedFraction = 0.35)
        assertNull("0.40 rejected > 0.35 gate must refuse the spot reading", gated.rmssd)
        assertNull(gated.sdnn)
        assertEquals(40, gated.nInput)
        assertEquals(0, gated.nClean)

        // SAME beats with NO gate (null) still produce a value — 24 clean >= MIN_BEATS.
        val ungated = HrvAnalyzer.analyzeRaw(rr)
        assertEquals(24, ungated.nClean)
        assertEquals(0.0, ungated.rmssd!!, 1e-9)   // all-800 survivors -> no successive diffs
    }

    @Test
    fun spotGateAllowsWhenRejectionUnderCeiling() {
        // 40 input: 30 valid 800 ms + 10 out-of-range -> 10/40 = 0.25 rejected < 0.35 gate -> allowed.
        val rr = List(30) { 800.0 } + List(10) { 100.0 }
        val gated = HrvAnalyzer.analyzeRaw(rr, maxRejectedFraction = 0.35)
        assertEquals(30, gated.nClean)
        assertEquals(0.0, gated.rmssd!!, 1e-9)
    }
}

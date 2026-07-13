package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Spo2Estimate.rollingReading] — the WHOOP-style Blood O₂ readout: a 30-day-smoothed, soft-anchored
 * guesstimate that only appears after a short calibration period. Twin of the Swift `Spo2RollingTests`,
 * value-for-value.
 *
 * Per the derived-biosignal standard: multi-value coverage, not one coincidental point. We check that a
 * stable healthy series reads ~96–97, that a recent dip reads clearly below the anchor with relative order
 * preserved, that the soft-anchor removes an uncalibrated DC offset while preserving spread, and that the
 * calibration gate returns the right N (and nil pct) below MIN_NIGHTS.
 */
class Spo2RollingTest {

    private fun pctOf(series: List<Double>): Double =
        Spo2Estimate.rollingReading(series).pct!!

    @Test
    fun stableHealthySeries_readsAround96to97() {
        // A tight, stable series around 95–97 → the smoothed readout sits in the healthy 96–97 band.
        val series = List(10) { listOf(95.0, 96.0, 97.0) }.flatten() // 30 nights
        val r = Spo2Estimate.rollingReading(series)
        assertNull("calibrated (>= MIN_NIGHTS)", r.calibratingNights)
        assertTrue("pct ${r.pct} in 96..97", r.pct!! in 96.0..97.0)
    }

    @Test
    fun recentNightsBelowBaseline_readBelowAnchor_orderPreserved() {
        // 23 nights at 97 then a recent week at 92: recent < 30-day baseline → pct clearly < ANCHOR.
        val dipping = List(23) { 97.0 } + List(7) { 92.0 }
        val steady = List(30) { 97.0 }
        val dipPct = pctOf(dipping)
        val steadyPct = pctOf(steady)
        assertTrue("dip pct $dipPct < ANCHOR ${Spo2Estimate.ANCHOR}", dipPct < Spo2Estimate.ANCHOR)
        // Relative order preserved: a recent dip reads lower than a flat baseline of the same nights.
        assertTrue("dip $dipPct < steady $steadyPct", dipPct < steadyPct)
    }

    @Test
    fun softAnchor_removesUncalibratedDcOffset_preservesSpread() {
        // An uncalibrated sensor sitting ~11 points low (all ~85): the soft-anchor shifts the 30-day
        // median to ~ANCHOR, so a flat-low series reads plausible/healthy…
        val flatLow = List(30) { 85.0 }
        assertTrue("flat-low anchors near ANCHOR", pctOf(flatLow) in 96.0..97.0)
        // …while a genuine recent dip below that same baseline still reads lower (spread preserved).
        val dipLow = List(23) { 85.0 } + List(7) { 80.0 }
        assertTrue("recent dip reads lower than the flat baseline", pctOf(dipLow) < pctOf(flatLow))
    }

    @Test
    fun exactlyMinNights_isCalibratedNotGated() {
        // Boundary: exactly MIN_NIGHTS produces a value (not a calibrating result).
        val series = List(Spo2Estimate.MIN_NIGHTS) { 96.0 }
        val r = Spo2Estimate.rollingReading(series)
        assertNull(r.calibratingNights)
        assertEquals(96.0, r.pct!!, 1.0)
    }

    @Test
    fun belowMinNights_returnsCalibratingWithCount() {
        // 1 and 2 nights → calibrating, carrying the accrued count; pct nil (never a fabricated readout).
        val one = Spo2Estimate.rollingReading(listOf(96.0))
        assertNull(one.pct)
        assertEquals(1, one.calibratingNights)

        val two = Spo2Estimate.rollingReading(listOf(96.0, 95.0))
        assertNull(two.pct)
        assertEquals(2, two.calibratingNights)
    }

    @Test
    fun emptySeries_returnsCalibratingZero() {
        // No estimates yet → calibrating with N = 0, pct nil (the UI renders this as No Data).
        val r = Spo2Estimate.rollingReading(emptyList())
        assertNull(r.pct)
        assertEquals(0, r.calibratingNights)
    }

    @Test
    fun onlyLastWindowNightsAreUsed() {
        // More than WINDOW nights: only the last WINDOW feed the reading. A long-ago block of lows is
        // ignored once WINDOW fresh healthy nights have accrued.
        val oldLows = List(40) { 80.0 }
        val recentHealthy = List(Spo2Estimate.WINDOW) { 96.0 }
        val r = Spo2Estimate.rollingReading(oldLows + recentHealthy)
        assertNull(r.calibratingNights)
        assertEquals(96.0, r.pct!!, 1.0)
    }
}

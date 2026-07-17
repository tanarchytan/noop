package com.noop.analytics

import com.noop.data.Spo2PctSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure unit test for [AnalyticsEngine.nightlySpo2PctMedian] — the WHOOP 5.0/MG sleep SpO2 percent (v18
 * @frame-82) median over detected sleep that feeds [com.noop.data.DailyMetric.spo2Pct]. A sample counts
 * when its ts lands inside a detected in-bed span AND its value is in the physiological band; whoop-rs
 * already sleep-gates + drops sentinels, so a stored sample is a real reading. A wellness estimate.
 */
class NightlySpo2PctTest {

    private fun sleep(start: Long, end: Long) =
        DetectedSleep(start = start, end = end, efficiency = 0.9, stages = emptyList(),
            restingHR = 55, avgHRV = 60.0)

    private fun pct(ts: Long, v: Int) = Spo2PctSample(deviceId = "my-whoop", ts = ts, pct = v)

    @Test
    fun emptyInputs_returnNull() {
        assertNull(AnalyticsEngine.nightlySpo2PctMedian(emptyList(), listOf(pct(100, 98))))
        assertNull(AnalyticsEngine.nightlySpo2PctMedian(listOf(sleep(0, 1000)), emptyList()))
    }

    @Test
    fun singleReadingDuringSleep_isTheMedian() {
        // A decoded record with spo2_pct = 98 during sleep contributes to DailyMetric.spo2Pct.
        val median = AnalyticsEngine.nightlySpo2PctMedian(listOf(sleep(1000, 2000)), listOf(pct(1500, 98)))
        assertEquals(98.0, median!!, 0.0)
    }

    @Test
    fun oddCount_takesMiddleValue_robustToTail() {
        val sessions = listOf(sleep(1000, 2000))
        // The low outlier (85) is a tail; the median (97) ignores it, unlike a mean.
        val samples = listOf(pct(1100, 85), pct(1200, 97), pct(1300, 98))
        assertEquals(97.0, AnalyticsEngine.nightlySpo2PctMedian(sessions, samples)!!, 0.0)
    }

    @Test
    fun evenCount_averagesTheTwoMiddle() {
        val sessions = listOf(sleep(1000, 2000))
        val samples = listOf(pct(1100, 96), pct(1200, 97), pct(1300, 98), pct(1400, 99))
        assertEquals(97.5, AnalyticsEngine.nightlySpo2PctMedian(sessions, samples)!!, 0.0)
    }

    @Test
    fun samplesOutsideEveryWindow_returnNull() {
        val sessions = listOf(sleep(1000, 2000))
        val samples = listOf(pct(500, 98), pct(2500, 97)) // both outside [1000, 2000]
        assertNull(AnalyticsEngine.nightlySpo2PctMedian(sessions, samples))
    }

    @Test
    fun boundariesInclusive_onlyInWindowCount() {
        val sessions = listOf(sleep(1000, 2000))
        val samples = listOf(
            pct(999, 80),    // just before → dropped
            pct(1000, 95),   // inclusive start → kept
            pct(2000, 99),   // inclusive end → kept
            pct(2001, 80),   // just after → dropped
        )
        assertEquals(97.0, AnalyticsEngine.nightlySpo2PctMedian(sessions, samples)!!, 0.0) // (95 + 99) / 2
    }

    @Test
    fun outOfBandValues_dropped() {
        val sessions = listOf(sleep(1000, 2000))
        // 0 (an unmasked sentinel that somehow slipped through) + 120 (impossible) are both dropped.
        val samples = listOf(pct(1100, 0), pct(1200, 97), pct(1300, 120))
        assertEquals(97.0, AnalyticsEngine.nightlySpo2PctMedian(sessions, samples)!!, 0.0)
        assertNull(AnalyticsEngine.nightlySpo2PctMedian(sessions, listOf(pct(1100, 0), pct(1300, 120))))
    }
}

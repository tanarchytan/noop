package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gap rule, pinned in the series builder rather than per chart: a night with no sleep keeps its slot
 * on the trend axis and carries null there. Dropping those rows made a chart captioned "Last 7 nights"
 * span eight calendar days with the missing night nowhere on it, while Home's chart showed it.
 */
class SleepTrendGapTest {

    private fun night(d: String, asleep: Double) = DailyMetric(
        deviceId = "my-whoop", day = d, totalSleepMin = asleep,
        deepMin = asleep * 0.20, remMin = asleep * 0.22, lightMin = asleep * 0.58, efficiency = 90.0,
    )

    /** A day on record that carries no sleep — the unslept day the mock seeds. */
    private fun unslept(d: String) = DailyMetric(deviceId = "my-whoop", day = d)

    private fun week() = listOf(
        night("2026-07-28", 420.0), night("2026-07-29", 430.0), night("2026-07-30", 400.0),
        night("2026-07-31", 460.0), night("2026-08-01", 440.0), unslept("2026-08-02"),
        night("2026-08-03", 416.0),
    )

    @Test
    fun `an unslept night keeps its slot and reads as a gap`() {
        val m = buildSleepModel(week(), session = null)!!
        assertEquals("seven days on record are seven slots", 7, m.trendDates.size)
        assertEquals("the axis is the calendar", "2026-08-02", m.trendDates[5])
        assertNull("the missed night has no hours", m.trendHours[5])
        assertNull("nor an efficiency", m.trendEfficiency[5])
        assertNotNull("the nights around it are untouched", m.trendHours[4])
        assertNotNull(m.trendHours[6])
    }

    /** Every weekly card reads one window, so their day labels line up slot for slot. */
    @Test
    fun `the trend series are all the same length as the axis`() {
        val m = buildSleepModel(week(), session = null)!!
        assertEquals(m.trendDates.size, m.trendHours.size)
        assertEquals(m.trendDates.size, m.trendEfficiency.size)
        assertEquals(m.trendDates.size, m.trendNeedHours.size)
    }

    /** A day with no row AT ALL is still a slot: the axis is calendar days, not stored rows. */
    @Test
    fun `a day the store never wrote is a slot, not a day the axis skips`() {
        val days = listOf(night("2026-07-28", 420.0), night("2026-07-30", 400.0))
        val m = buildSleepModel(days, session = null)!!
        assertEquals(listOf("2026-07-28", "2026-07-29", "2026-07-30"), m.trendDates)
        assertNull("the day between them is drawn as missing", m.trendHours[1])
    }

    /** The window never runs off the front of the history: a short history is short, not padded. */
    @Test
    fun `the window never invents days before the history starts`() {
        val days = listOf(night("2026-07-28", 420.0), night("2026-07-29", 430.0))
        val m = buildSleepModel(days, session = null)!!
        assertEquals(listOf("2026-07-28", "2026-07-29"), m.trendDates)
    }

    /** The need line stays continuous across a gap: a personal need exists on a night that was missed. */
    @Test
    fun `the need line has no gap`() {
        val m = buildSleepModel(week(), session = null)!!
        m.trendNeedHours.forEach { assertEquals("need is drawn on every slot", true, it > 0.0) }
    }
}

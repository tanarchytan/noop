package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The presentation half of sleep stress: which tier a driver strip lights, the order the bands stack
 * in, and the sentence a screen reader gets. Every minute and every share is whoop-rs's; nothing here
 * decides a value, so these pin the mapping only.
 */
class SleepStressCardTest {

    private fun night(low: Long, medium: Long, high: Long, share: Double?) =
        SleepStressNight("Sat", low, medium, high, share)

    @Test
    fun `a mirrored driver lights the opposite tier`() {
        // 0% high stress is the GOOD end, so it lights the tier a 100% score would.
        assertEquals(driverTierIndex(100.0), driverTierLit(0.0, higherIsBetter = false))
        assertEquals(driverTierIndex(0.0), driverTierLit(100.0, higherIsBetter = false))
        assertEquals(driverTierIndex(50.0), driverTierLit(50.0, higherIsBetter = false))
    }

    @Test
    fun `an ordinary driver is not mirrored`() {
        listOf(0.0, 34.0, 61.0, 90.0, 100.0).forEach {
            assertEquals("$it% unmirrored", driverTierIndex(it), driverTierLit(it, higherIsBetter = true))
        }
    }

    @Test
    fun `bands stack low first and total the scored minutes`() {
        val n = night(low = 180, medium = 120, high = 60, share = 16.6)
        assertEquals(listOf(180L, 120L, 60L), n.stack.map { it.first })
        assertEquals(360L, n.scoredMinutes)
    }

    @Test
    fun `an unscored night has no minutes to scale`() {
        assertEquals(0L, night(0, 0, 0, share = null).scoredMinutes)
    }

    @Test
    fun `the chart describes every night by its high time`() {
        val text = sleepStressDescription(
            listOf(night(180, 120, 60, 16.6), night(300, 60, 0, 0.0).copy(label = "Sun")),
        )
        assertTrue("names the span", text.startsWith("Sleep stress over the last 2 nights."))
        assertTrue("carries Saturday's high time", text.contains("Sat 1h 0m high"))
        assertTrue("a night with no high time still reads", text.contains("Sun 0m high"))
    }
}

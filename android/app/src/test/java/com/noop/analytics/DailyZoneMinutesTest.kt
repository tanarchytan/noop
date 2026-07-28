package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily heart-rate zone split in AnalyticsEngine.analyzeDay: minutes are binned from the day's
 * own samples against the age-derived %HRmax bands, grouped low (zones 1-3) and high (zones 4-5).
 * A day with no heart rate carries null rather than a fabricated zero. Pure, no database.
 */
class DailyZoneMinutesTest {

    // age 30 -> Tanaka HRmax 208 - 0.7*30 = 187. Zone 1 opens at 50% (93.5), zone 4 at 80% (149.6).
    private val profile = UserProfile(age = 30.0)

    private val dayUtc = "2026-01-02"
    private val noonUtc = 1_767_355_200L

    private fun hr(offsetSec: Long, bpm: Int) =
        HrSample(deviceId = "my-whoop", ts = noonUtc + offsetSec, bpm = bpm)

    /** One sample per second at [bpm], for [seconds] seconds. */
    private fun steady(bpm: Int, seconds: Int, from: Long = 0) =
        (0 until seconds).map { hr(from + it, bpm) }

    private fun daily(samples: List<HrSample>) =
        AnalyticsEngine.analyzeDay(day = dayUtc, hr = samples, profile = profile).daily

    @Test
    fun aDayWithNoHeartRateCarriesNoSplit() {
        val d = daily(emptyList())
        assertNull(d.zone1to3Min)
        assertNull(d.zone4to5Min)
    }

    @Test
    fun restingAllDayStaysBelowZoneOneAndScoresNothing() {
        // 55 bpm is under the zone-1 floor of 93.5, so it belongs to no band: null, not zero.
        val d = daily(steady(55, 600))
        assertNull(d.zone1to3Min)
        assertNull(d.zone4to5Min)
    }

    @Test
    fun aModerateHourLandsInTheLowBand() {
        // 120 bpm is 64% of 187 — zone 2, so it counts low and leaves the high band empty.
        val d = daily(steady(120, 600))
        assertEquals(10.0, d.zone1to3Min!!, 0.5)
        assertEquals(0.0, d.zone4to5Min!!, 0.001)
    }

    @Test
    fun aHardEffortLandsInTheHighBand() {
        // 170 bpm is 91% of 187 — zone 5.
        val d = daily(steady(170, 300))
        assertEquals(0.0, d.zone1to3Min!!, 0.001)
        assertEquals(5.0, d.zone4to5Min!!, 0.5)
    }

    @Test
    fun aMixedDaySplitsAcrossBothBands() {
        // Contiguous blocks: 600 s at zone 2, then 300 s at zone 5.
        val d = daily(steady(120, 600) + steady(170, 300, from = 600))
        assertEquals(10.0, d.zone1to3Min!!, 0.5)
        assertEquals(5.0, d.zone4to5Min!!, 0.5)
        // The bands partition the samples, so together they cannot exceed the wall time.
        assertTrue(d.zone1to3Min!! + d.zone4to5Min!! <= 900.0 / 60.0 + 0.5)
    }

    @Test
    fun aGapBetweenSamplesIsCreditedToTheZoneThatPrecedesIt() {
        // A 100 s gap sits between the last zone-2 sample and the first zone-5 one. Each sample holds
        // until the next arrives, so the gap belongs to the earlier zone rather than to neither.
        val d = daily(steady(120, 600) + steady(170, 300, from = 700))
        assertEquals((600.0 + 100.0) / 60.0, d.zone1to3Min!!, 0.5)
        assertEquals(5.0, d.zone4to5Min!!, 0.5)
    }

    @Test
    fun samplesOutsideTheDayDoNotCount() {
        // Two days earlier: filtered out with the rest of the day's totals, leaving nothing to score.
        val d = daily(steady(120, 600, from = -2 * 86_400L))
        assertNull(d.zone1to3Min)
        assertNull(d.zone4to5Min)
    }
}

package com.noop.ui

import com.noop.data.MockSeeder
import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The rebuilt cards on the two mock fixtures that break geometry: the day with no session at all, and
 * the night that starts at 20:41 and therefore ends on a different calendar day. A screen correct on
 * 118 of 120 seeded days has broken the two that matter.
 */
class SleepCardFixturesTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now()

    private fun span(bedDaysAgo: Long, bedHour: Int, bedMin: Int, wakeHour: Int, wakeMin: Int): Pair<Long, Long> =
        today.minusDays(bedDaysAgo).atTime(bedHour, bedMin).atZone(zone).toEpochSecond() to
            today.minusDays(bedDaysAgo - 1).atTime(wakeHour, wakeMin).atZone(zone).toEpochSecond()

    private fun session(span: Pair<Long, Long>) =
        SleepSession(deviceId = "my-whoop", startTs = span.first, endTs = span.second)

    /** Spans as the calendar slots the cards read, each keyed by the day its night woke on. */
    private fun slots(vararg spans: Pair<Long, Long>): List<NightSlot> =
        spans.map { NightSlot(localDayString(it.second), it) }

    // MARK: - The cross-midnight night

    /**
     * The evening bedtime folds to a NEGATIVE hour so the bar's bed edge sits above its wake edge on an
     * axis that runs 20:00 through 18:00. Folding it the other way would draw a bar the height of a day.
     */
    @Test
    fun anEveningBedtimeFoldsAboveTheFollowingWake() {
        val crossMidnight = span(1, MockSeeder.CRASH_OUT_HOUR, MockSeeder.CRASH_OUT_MINUTE, 5, 12)
        assertNotEquals(
            "the fixture must cross midnight",
            localDayString(crossMidnight.first),
            localDayString(crossMidnight.second),
        )
        val night = sleepScheduleNights(slots(crossMidnight)).single()
        assertTrue("an evening bedtime folds negative", night.bedHour!! < 0f)
        assertTrue("the bar runs down the axis to the wake", night.bedHour!! < night.wakeHour!!)
        val axis = scheduleHourSpan(listOf(night))
        assertTrue(
            "and the axis it derives contains it",
            night.bedHour!! >= axis.start && night.wakeHour!! <= axis.endInclusive,
        )
    }

    /** A usual ~23:10 night folds the same way, so the two shapes sit on one axis. */
    @Test
    fun aUsualNightFoldsOntoTheSameAxis() {
        val usual = span(1, 23, 10, 6, 50)
        val night = sleepScheduleNights(slots(usual)).single()
        assertTrue(night.bedHour!! < 0f)
        assertTrue(night.bedHour!! < night.wakeHour!!)
        val axis = scheduleHourSpan(listOf(night))
        assertTrue(night.bedHour!! >= axis.start && night.wakeHour!! <= axis.endInclusive)
    }

    /** Each fold keeps its own night's clock times, so the time-in-bed labels can't drift a column. */
    @Test
    fun everyNightKeepsItsOwnLabelAndOrder() {
        val spans = listOf(span(3, 23, 10, 6, 50), span(2, 22, 40, 7, 5), span(1, MockSeeder.CRASH_OUT_HOUR, MockSeeder.CRASH_OUT_MINUTE, 5, 12))
        val nights = sleepScheduleNights(slots(*spans.toTypedArray()))
        assertEquals(spans.size, nights.size)
        assertEquals("20:41", clockTimeLabel(spans.last().first))
        assertEquals("05:12", clockTimeLabel(spans.last().second))
    }

    // MARK: - The unslept day

    /**
     * A day with no session keeps its calendar column and draws NOTHING in it — never a zero-height bar
     * at midnight, and never closed over so a week silently spans an extra day. The nights either side of
     * the gap still fold, and the cards read the same list.
     */
    @Test
    fun aDayWithNoSessionKeepsItsColumnAndDrawsNothingInIt() {
        assertEquals("the fixture is the day before the last", MockSeeder.DAYS - 2, MockSeeder.UNSLEPT_DAY_INDEX)
        val before = span(3, 23, 10, 6, 50)
        val after = span(1, MockSeeder.CRASH_OUT_HOUR, MockSeeder.CRASH_OUT_MINUTE, 5, 12)
        // Day 2 back has no block at all, which is what the seeder writes.
        val sleeps = listOf(session(before), session(after))
        val got = consistencyNightSlots(sleeps, habitualMidsleepSec = null, limit = SLEEP_TREND_NIGHTS)
        assertEquals("the unslept day keeps its slot", 3, got.size)
        assertNull("and carries no night", got[1].span)
        val nights = sleepScheduleNights(got)
        assertEquals(3, nights.size)
        assertNull("no zero-height bar at midnight", nights[1].bedHour)
        assertNull(nights[1].wakeHour)
        // The axis is bounded by the nights that happened, so the gap neither stretches nor flattens it.
        val axis = scheduleHourSpan(nights)
        assertTrue(nights[0].bedHour!! >= axis.start && nights[2].wakeHour!! <= axis.endInclusive)
    }

    /** With no nights at all the cards get an empty list, never a fabricated one. */
    @Test
    fun noNightsFoldToNoBars() {
        assertEquals(emptyList<SleepScheduleNight>(), sleepScheduleNights(emptyList()))
    }

    // MARK: - The band, the tiers and the labels

    /** The habitual band needs both learned values; either one missing draws no band. */
    @Test
    fun theHabitualBandNeedsBothItsInputs() {
        val nights = sleepScheduleNights(slots(span(1, 23, 10, 6, 50)))
        val key = nights.single().dayKey
        assertNull(optimalSleepBand(emptyMap(), nights, 480.0).single())
        assertNull(optimalSleepBand(mapOf(key to null), nights, 480.0).single())
        assertNull(optimalSleepBand(mapOf(key to 3 * 3600L), nights, null).single())
        assertNull(optimalSleepBand(mapOf(key to 3 * 3600L), nights, 0.0).single())
    }

    /** A post-midnight midsleep centres the band, and the need sets its height. */
    @Test
    fun theHabitualBandCentresOnTheMidsleep() {
        val nights = sleepScheduleNights(slots(span(1, 23, 10, 6, 50)))
        val key = nights.single().dayKey
        val (bed, wake) = optimalSleepBand(mapOf(key to 3 * 3600L), nights, 480.0).single()!!
        assertEquals(-1f, bed, 0.01f)
        assertEquals(7f, wake, 0.01f)
        // An evening midsleep folds the same way the bars do, so the band can't jump the axis.
        val (lateBed, lateWake) = optimalSleepBand(mapOf(key to 23 * 3600L), nights, 480.0).single()!!
        assertTrue(lateBed < 0f && lateWake < lateBed + 9f)
    }

    /**
     * The band is one entry per night, so a midsleep that walks night to night bends it. A day the
     * series carries no value for leaves a gap rather than a straight-line guess across it.
     */
    @Test
    fun theHabitualBandBendsPerNightAndGapsWhereItHasNoValue() {
        val nights = sleepScheduleNights(slots(span(3, 23, 10, 6, 50), span(2, 22, 40, 7, 5), span(1, 23, 30, 6, 30)))
        assertEquals("the three nights must key apart", 3, nights.map { it.dayKey }.toSet().size)
        val series = mapOf(nights[0].dayKey to 2 * 3600L, nights[2].dayKey to 3 * 3600L)
        val bands = optimalSleepBand(series, nights, 480.0)
        assertEquals(nights.size, bands.size)
        assertNull("the night the series skips draws no band", bands[1])
        assertEquals("an hour of habit is an hour of band", 1f, bands[2]!!.first - bands[0]!!.first, 0.01f)
        assertEquals(1f, bands[2]!!.second - bands[0]!!.second, 0.01f)
    }

    /** The driver strip marks which third of 0-100 a value lands in, top tier inclusive at 100. */
    @Test
    fun driverTiersSplitTheRangeInThirds() {
        assertEquals(0, driverTierIndex(0.0))
        assertEquals(0, driverTierIndex(33.0))
        assertEquals(1, driverTierIndex(34.0))
        assertEquals(1, driverTierIndex(66.0))
        assertEquals(2, driverTierIndex(67.0))
        assertEquals(2, driverTierIndex(100.0))
        assertEquals("above the scale still reads as the top tier", 2, driverTierIndex(140.0))
    }

    /** Every stage row reads its own minutes, so a row can't print another stage's total. */
    @Test
    fun eachStageRowReadsItsOwnMinutes() {
        val stages = Stages(awake = 36.0, light = 176.0, deep = 89.0, rem = 46.0)
        assertEquals(36.0, stageMinutes(stages, "Awake"), 0.0)
        assertEquals(176.0, stageMinutes(stages, "Light"), 0.0)
        assertEquals(89.0, stageMinutes(stages, "Deep"), 0.0)
        assertEquals(46.0, stageMinutes(stages, "REM"), 0.0)
    }

    /** The axis gutter labels a negative hour as its evening clock time. */
    @Test
    fun theAxisLabelsNegativeHoursAsEveningTimes() {
        assertEquals("20:00", scheduleHourLabel(-4f))
        assertEquals("00:00", scheduleHourLabel(0f))
        assertEquals("08:00", scheduleHourLabel(8f))
    }

    /** A trend column is labelled from its own day string, and a malformed one falls back to itself. */
    @Test
    fun trendColumnsLabelTheirOwnDay() {
        assertEquals("Sat 11", trendDayLabel("2026-07-11"))
        assertEquals("not-a-day", trendDayLabel("not-a-day"))
    }
}

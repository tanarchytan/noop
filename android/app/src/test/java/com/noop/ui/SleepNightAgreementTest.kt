package com.noop.ui

import com.noop.analytics.SleepStageTotals
import com.noop.data.DailyMetric
import com.noop.data.MockSeeder
import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One night, one set of figures. The hero card decodes the session's segments; "Hours vs Needed",
 * Intelligence, the weekly Restorative chart and the fused record all read the daily columns. They
 * disagreed by ~9% because the two shapes were written from different arithmetic, and the card also
 * carried a third figure for AWAKE that its own timeline contradicted.
 */
class SleepNightAgreementTest {

    private val onset = 1_785_695_786L
    private val deep = 94.1
    private val rem = 112.4
    private val light = 209.7
    private val asleep = deep + rem + light          // 416.2 — the totalSleepMin column
    private val effPct = 90.2
    private val span = MockSeeder.inBedSecFor(asleep, effPct)

    private fun day() = DailyMetric(
        deviceId = "my-whoop", day = localDayString(onset + span),
        totalSleepMin = asleep, deepMin = deep, remMin = rem, lightMin = light, efficiency = effPct,
    )

    private fun session() = SleepSession(
        deviceId = "my-whoop", startTs = onset, endTs = onset + span, efficiency = effPct,
        stagesJSON = MockSeeder.stagesJson(deep, rem, light, onset, onset + span),
    )

    private fun model() = buildSleepModel(listOf(day()), session())!!

    /** The hero card's stage rows are the day's columns, which is what the other four screens print. */
    @Test
    fun `the hero card and the daily columns describe the same night`() {
        val m = model()
        assertEquals("HOURS OF SLEEP is the totalSleepMin column", asleep, m.stages.asleep, 0.05)
        assertEquals("the deep row is the deepMin column", deep, m.stages.deep, 0.05)
        assertEquals("the REM row is the remMin column", rem, m.stages.rem, 0.05)
        assertEquals("the light row is the lightMin column", light, m.stages.light, 0.05)
        assertEquals(
            "the restorative total is deep + REM, the same pair the weekly chart stacks",
            deep + rem, m.stages.deep + m.stages.rem, 0.05,
        )
    }

    /** The asleep total the need card prints is the asleep total the hero prints. */
    @Test
    fun `hours versus needed reads the same asleep total the hero shows`() {
        val m = model()
        assertEquals(m.stages.asleep, m.hoursVsNeededSleptMin!!, 0.05)
    }

    /**
     * AWAKE has ONE definition: the wake the night's own stages recorded. The header, the bar under it
     * and DURATION minus HOURS OF SLEEP were three derivations of one quantity.
     */
    @Test
    fun `awake is the night's own wake, and the card's own arithmetic lands on it`() {
        val m = model()
        val drawn = SleepStageTotals.minutes(session().stagesJSON)!!.awake
        assertEquals("the header prints what the bar draws", drawn, m.stages.awake, 0.05)
        val inBedMin = span / 60.0
        assertEquals(
            "DURATION minus HOURS OF SLEEP is that same awake",
            m.stages.awake, inBedMin - m.stages.asleep, 0.05,
        )
    }

    /** A night with no stored segments still has ONE asleep total: its own columns, not a second shape. */
    @Test
    fun `a column-only night reads its asleep total off its own stage columns`() {
        val m = buildSleepModel(listOf(day()), session = null)!!
        assertEquals(asleep, m.stages.asleep, 0.05)
        assertEquals("its awake is what its efficiency implies, over that same asleep",
            asleep / (effPct / 100.0) - asleep, m.stages.awake, 0.05)
    }
}

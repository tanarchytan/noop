package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The mock dataset holds one day the wearer did not sleep, so the state is reachable on a device and
 * not only in a backup: a real `dailyMetric` row with no sleep signal, no `sleepSession`, and a
 * following night that starts in the evening and therefore ends on a different calendar day.
 */
class MockSeederUnsleptDayTest {

    /** A row with every metric column set, so [MockSeeder.withoutSleep] has something to clear. */
    private val full = DailyMetric(
        deviceId = "my-whoop", day = "2026-07-30",
        totalSleepMin = 430.0, efficiency = 0.89, deepMin = 86.0, remMin = 99.0, lightMin = 245.0,
        disturbances = 6, restingHr = 56, avgHrv = 78.0, recovery = 61.0, strain = 17.4,
        exerciseCount = 1, spo2Pct = 96.5, skinTempDevC = 0.1, skinTempAbsC = 34.1,
        respRateBpm = 14.6, steps = 8500, activeKcalEst = 2049.8, zone1to3Min = 120.0,
        zone4to5Min = 12.0, spo2Red = 567, spo2Ir = 608, sleepNeedHours = 9.0,
        sleepConsistency = 0.81, recoveryIndexSlope = -0.17, priorDayEffort = 47.9,
    )

    /** Every metric column on the entity, off its backing fields — the natural key is not a metric. */
    private fun metricColumns(): List<String> =
        DailyMetric::class.java.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filter { it != "deviceId" && it != "day" }

    private fun nullColumns(d: DailyMetric): Set<String> {
        val out = mutableSetOf<String>()
        for (f in DailyMetric::class.java.declaredFields) {
            if (f.isSynthetic || Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            if (f.get(d) == null) out += f.name
        }
        return out
    }

    /**
     * The split is named, not inferred, so a column added to the entity lands on neither side and fails
     * here rather than silently picking one. The keep side is what a WORN day produces without a night.
     */
    private val keptOnAnUnsleptDay = setOf(
        "strain", "exerciseCount", "steps", "activeKcalEst",
        "zone1to3Min", "zone4to5Min", "sleepNeedHours", "sleepConsistency", "priorDayEffort",
    )

    /**
     * The partition below only proves anything while the fixture sets EVERY metric column: a new entity
     * column left unset here reads back null after the strip and lands on the cleared side for free,
     * which is the answer nobody decided. This is the assertion that makes adding one fail.
     */
    @Test
    fun theFixtureSetsEveryMetricColumn() {
        val unset = nullColumns(full)
        assertEquals("a metric column this fixture never sets: $unset", emptySet<String>(), unset)
    }

    @Test
    fun everyNightProducedColumnIsClearedAndTheDaysOwnColumnsSurvive() {
        val stripped = MockSeeder.withoutSleep(full)
        val cleared = nullColumns(stripped)
        assertEquals(
            "a column belongs to the night or to the day, and this one is on neither list",
            metricColumns().toSet(), cleared + keptOnAnUnsleptDay,
        )
        assertTrue("a kept column was cleared: ${cleared intersect keptOnAnUnsleptDay}",
            (cleared intersect keptOnAnUnsleptDay).isEmpty())
        // The four the strap actually banks on such a day, spelled out so the intent cannot drift.
        assertNull(stripped.totalSleepMin)
        assertNull(stripped.avgHrv)
        assertNull(stripped.restingHr)
        assertNull(stripped.recovery)
        assertNotNull(stripped.strain)
    }

    /** An absent day and a day with no sleep are different states, and this is the second one. */
    @Test
    fun theRowStillExistsAndKeepsItsKey() {
        val stripped = MockSeeder.withoutSleep(full)
        assertEquals("my-whoop", stripped.deviceId)
        assertEquals("2026-07-30", stripped.day)
    }

    /** Placement: inside the window, and never the last day, or the night that follows it would fall
     *  outside the seeded run and the evening onset would never be written. */
    @Test
    fun theUnsleptDayLeavesRoomForTheNightThatFollowsIt() {
        assertTrue(MockSeeder.UNSLEPT_DAY_INDEX in 1 until MockSeeder.DAYS - 1)
        assertEquals(MockSeeder.DAYS - 1, MockSeeder.UNSLEPT_DAY_INDEX + 1)
    }

    /** The onset that follows it is an EVENING one, which is what makes a night whose start day differs
     *  from its end day. The seeder shifts it by at most half an hour, so it cannot cross midnight. */
    @Test
    fun theNightAfterTheGapStartsBeforeMidnight() {
        assertTrue("onset must be in the evening", MockSeeder.CRASH_OUT_HOUR in 18..22)
        assertTrue(MockSeeder.CRASH_OUT_MINUTE in 0..59)
        val earliestEndOfDayGap = (24 - MockSeeder.CRASH_OUT_HOUR) * 60 - MockSeeder.CRASH_OUT_MINUTE
        assertTrue("a +30 min jitter must not push the onset past midnight", earliestEndOfDayGap > 30)
    }
}

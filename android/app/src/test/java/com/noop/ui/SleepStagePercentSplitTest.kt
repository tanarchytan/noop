package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The stage-percentage split the stages card prints. whoop-rs apportions the night, so the four
 * shares add up to exactly 100 by construction rather than by luck on one dataset. Every case here
 * is a property of the split, not a recorded example.
 */
class SleepStagePercentSplitTest {

    private fun split(awake: Double, light: Double, deep: Double, rem: Double): Map<String, Int> =
        stagePercentByLabel(Stages(awake = awake, light = light, deep = deep, rem = rem))

    /** Each share rounded on its own — what the card used to do at both of its call sites. */
    private fun naiveSum(vararg minutes: Double): Int {
        val total = minutes.sum()
        return minutes.sumOf { (it / total * 100.0).roundToInt() }
    }

    @Test
    fun aNightWithNoMinutesHasNoShares() {
        assertEquals(emptyMap<String, Int>(), split(0.0, 0.0, 0.0, 0.0))
    }

    @Test
    fun aSingleStageNightGivesThatStageTheWholeNightAndTheRestNothing() {
        val only = split(0.0, 430.0, 0.0, 0.0)
        assertEquals(100, only["Light"])
        assertEquals(0, only["Awake"])
        assertEquals(0, only["Deep"])
        assertEquals(0, only["REM"])
    }

    @Test
    fun aThreeWayEvenSplitSumsToAHundredWhereRoundingSumsToNinetyNine() {
        assertEquals(99, naiveSum(1.0, 1.0, 1.0))
        val even = split(0.0, 90.0, 90.0, 90.0)
        assertEquals(0, even["Awake"])
        assertEquals(100, even.values.sum())
    }

    @Test
    fun aSplitRoundingPushesToAHundredAndOneSumsToAHundred() {
        assertEquals(101, naiveSum(60.0, 60.0, 60.0, 15.0))
        assertEquals(100, split(60.0, 60.0, 60.0, 15.0).values.sum())
    }

    @Test
    fun aStageWithNoMinutesNeverTakesAPointFromTheOthers() {
        for (zeroed in 0 until 4) {
            val minutes = DoubleArray(4) { if (it == zeroed) 0.0 else 71.0 }
            val s = split(minutes[0], minutes[1], minutes[2], minutes[3])
            assertEquals(0, s[STAGE_LABELS[zeroed]])
            assertEquals(100, s.values.sum())
        }
    }

    @Test
    fun everyNightOfASweepSumsToExactlyAHundred() {
        var roundingWrong = 0
        var nights = 0
        // Quarter-hour steps across a plausible night, every combination including the empty one.
        val steps = listOf(0.0, 15.0, 22.5, 45.0, 90.0, 127.5, 200.0)
        for (awake in steps) for (light in steps) for (deep in steps) for (rem in steps) {
            val s = split(awake, light, deep, rem)
            if (awake + light + deep + rem <= 0.0) {
                assertTrue(s.isEmpty())
                continue
            }
            nights++
            assertEquals("$awake/$light/$deep/$rem -> $s", 100, s.values.sum())
            listOf(awake, light, deep, rem).forEachIndexed { i, m ->
                if (m <= 0.0) assertEquals("$awake/$light/$deep/$rem -> $s", 0, s[STAGE_LABELS[i]])
            }
            if (naiveSum(awake, light, deep, rem) != 100) roundingWrong++
        }
        // The defect is ordinary, not rare: plain rounding misses on a large share of these nights.
        assertTrue("$roundingWrong of $nights", roundingWrong * 4 > nights)
    }

    private companion object {
        val STAGE_LABELS = listOf("Awake", "Light", "Deep", "REM")
    }
}

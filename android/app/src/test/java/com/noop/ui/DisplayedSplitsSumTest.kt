package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The two remaining places a split is printed as whole percentages: the hypnogram's spoken summary
 * and the workout HR-zone strip. Both route through whoop-rs's apportionment, so the shares sum to
 * exactly 100 by construction rather than by luck on one dataset. Every case is a property of the
 * split, not a recorded example.
 */
class DisplayedSplitsSumTest {

    /** Each share rounded on its own — what both call sites used to do. */
    private fun naiveSum(parts: List<Double>): Int {
        val total = parts.sum()
        return parts.sumOf { (it / total * 100.0).roundToInt() }
    }

    /** The percentages the spoken summary actually says, in the order it says them. */
    private fun spoken(vararg stages: Pair<String, Float>): List<Int> =
        Regex("(\\d+) percent").findAll(hypnogramSummary(stages.toList()))
            .map { it.groupValues[1].toInt() }
            .toList()

    // ── the hypnogram summary ────────────────────────────────────────────────

    @Test
    fun aNightWithNoStagesIsSpokenAsNoDataRatherThanAsAHundred() {
        assertEquals("Sleep stages, no data", hypnogramSummary(emptyList()))
        assertEquals(
            "Sleep stages, no data",
            hypnogramSummary(listOf("deep" to 0f, "rem" to 0f, "light" to 0f, "awake" to 0f)),
        )
    }

    @Test
    fun aNightOfNonFiniteWeightsIsSpokenAsNoDataRatherThanAsAHundred() {
        assertEquals(
            "Sleep stages, no data",
            hypnogramSummary(listOf("deep" to Float.NaN, "rem" to -3f, "light" to Float.POSITIVE_INFINITY)),
        )
    }

    @Test
    fun aThreeWayEvenNightIsSpokenAsAHundredWhereRoundingSpeaksNinetyNine() {
        assertEquals(99, naiveSum(listOf(1.0, 1.0, 1.0)))
        val said = spoken("deep" to 90f, "rem" to 90f, "light" to 90f)
        assertEquals(3, said.size)
        assertEquals(100, said.sum())
    }

    @Test
    fun aNightRoundingPushesToAHundredAndOneIsSpokenAsAHundred() {
        assertEquals(101, naiveSum(listOf(60.0, 60.0, 60.0, 15.0)))
        val said = spoken("deep" to 60f, "rem" to 60f, "light" to 60f, "awake" to 15f)
        assertEquals(4, said.size)
        assertEquals(100, said.sum())
    }

    @Test
    fun aStageWithNoMinutesIsLeftUnspokenRatherThanAnnouncedAsZero() {
        val said = hypnogramSummary(listOf("deep" to 71f, "rem" to 71f, "light" to 71f, "awake" to 0f))
        assertTrue(said, !said.contains("Awake"))
        assertEquals(100, spoken("deep" to 71f, "rem" to 71f, "light" to 71f, "awake" to 0f).sum())
    }

    @Test
    fun everyNightOfASweepIsSpokenAsExactlyAHundred() {
        var roundingWrong = 0
        var nights = 0
        val steps = listOf(0.0, 15.0, 22.5, 45.0, 90.0, 127.5, 200.0)
        for (deep in steps) for (rem in steps) for (light in steps) for (awake in steps) {
            val minutes = listOf(deep, rem, light, awake)
            val said = spoken(
                "deep" to deep.toFloat(), "rem" to rem.toFloat(),
                "light" to light.toFloat(), "awake" to awake.toFloat(),
            )
            if (minutes.sum() <= 0.0) {
                assertTrue("$minutes", said.isEmpty())
                continue
            }
            nights++
            assertEquals("$minutes -> $said", 100, said.sum())
            assertEquals("$minutes -> $said", minutes.count { it > 0.0 }, said.size)
            if (naiveSum(minutes) != 100) roundingWrong++
        }
        // The defect is ordinary, not rare: plain rounding misses on a large share of these nights.
        assertTrue("$roundingWrong of $nights", roundingWrong * 4 > nights)
    }

    // ── the workout HR-zone strip ────────────────────────────────────────────

    @Test
    fun aSessionWithNoZoneTimeHasNoSharesRatherThanAFabricatedHundred() {
        assertEquals(null, zonePercents(listOf(0.0, 0.0, 0.0, 0.0, 0.0)))
        assertEquals(null, zonePercents(emptyList()))
        assertEquals(null, zonePercents(listOf(Double.NaN, -4.0, Double.POSITIVE_INFINITY)))
    }

    @Test
    fun aSingleZoneSessionGivesThatZoneTheWholeSessionAndTheRestNothing() {
        assertEquals(listOf(0, 0, 100, 0, 0), zonePercents(listOf(0.0, 0.0, 37.5, 0.0, 0.0)))
    }

    @Test
    fun fiveZonesRoundingPushesToAHundredAndTwoSumToAHundred() {
        val minutes = listOf(50.0, 12.5, 12.5, 12.5, 12.5)
        assertEquals(102, naiveSum(minutes))
        assertEquals(100, zonePercents(minutes)!!.sum())
    }

    @Test
    fun aZoneWithNoTimeNeverTakesAPointFromTheOthers() {
        for (zeroed in 0 until 5) {
            val minutes = List(5) { if (it == zeroed) 0.0 else 13.0 }
            val split = zonePercents(minutes)!!
            assertEquals("$minutes -> $split", 0, split[zeroed])
            assertEquals("$minutes -> $split", 100, split.sum())
        }
    }

    @Test
    fun everyZoneSplitOfASweepSumsToExactlyAHundred() {
        var roundingWrong = 0
        var sessions = 0
        val steps = listOf(0.0, 3.0, 7.5, 18.0, 44.0)
        for (a in steps) for (b in steps) for (c in steps) for (d in steps) for (e in steps) {
            val minutes = listOf(a, b, c, d, e)
            val split = zonePercents(minutes)
            if (minutes.sum() <= 0.0) {
                assertEquals("$minutes", null, split)
                continue
            }
            sessions++
            assertEquals("$minutes -> $split", 100, split!!.sum())
            minutes.forEachIndexed { i, m ->
                if (m <= 0.0) assertEquals("$minutes -> $split", 0, split[i])
            }
            if (naiveSum(minutes) != 100) roundingWrong++
        }
        assertTrue("$roundingWrong of $sessions", roundingWrong * 4 > sessions)
    }
}

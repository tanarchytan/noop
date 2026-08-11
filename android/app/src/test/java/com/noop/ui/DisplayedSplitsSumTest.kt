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

    /** The percentages the spoken summary carries, in the order it says them. */
    private fun spoken(vararg stages: Pair<String, Float>): List<Int> =
        hypnogramShares(stages.toList()).map { it.second }

    // ── the hypnogram summary ────────────────────────────────────────────────
    // An EMPTY share list is what the screen reads as "Sleep stages, no data"; the wording itself is a
    // resource, so what is pinned here is that the night yields nothing to speak.

    @Test
    fun aNightWithNoStagesIsSpokenAsNoDataRatherThanAsAHundred() {
        assertTrue(hypnogramShares(emptyList()).isEmpty())
        assertTrue(
            hypnogramShares(listOf("deep" to 0f, "rem" to 0f, "light" to 0f, "awake" to 0f)).isEmpty(),
        )
    }

    @Test
    fun aNightOfNonFiniteWeightsIsSpokenAsNoDataRatherThanAsAHundred() {
        assertTrue(
            hypnogramShares(
                listOf("deep" to Float.NaN, "rem" to -3f, "light" to Float.POSITIVE_INFINITY),
            ).isEmpty(),
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
        val said = hypnogramShares(listOf("deep" to 71f, "rem" to 71f, "light" to 71f, "awake" to 0f))
        assertTrue("$said", said.none { it.first == "awake" })
        assertEquals(100, said.sumOf { it.second })
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

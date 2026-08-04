package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.roundToInt

/**
 * A chart's max / avg / min rail has ONE owner.
 *
 * The middle number came from whoop-rs while the two extremes were computed here, so one row read from
 * two sources. Both extremes now come from `stats::min` / `stats::max` through [RustScores].
 *
 * These tests pin the two properties that make it one owner: the Rust reduction answers exactly what
 * the Kotlin operator it replaced answered, and neither axis writes a reduction down again.
 */
class SeriesExtremesOneOwnerTest {

    /** Raw bits, so a ±0.0 disagreement cannot pass the way an `assertEquals` delta lets it. */
    private fun bits(v: Double) = java.lang.Double.doubleToRawLongBits(v)

    /**
     * The parity oracle: over 5,000 seeded series the Rust reduction and `List<Double>.max()` /
     * `.min()` return the same bits. A port that is behaviour-preserving has nothing left to argue.
     */
    @Test
    fun theRustExtremesAnswerBitForBitWhatTheKotlinOperatorAnswered() {
        val rng = Random(20260804L)
        repeat(5_000) {
            val series = List(1 + rng.nextInt(64)) { rng.nextDouble() * 400.0 - 200.0 }
            assertEquals("max disagreed on $series", bits(series.max()), bits(RustScores.max(series)))
            assertEquals("min disagreed on $series", bits(series.min()), bits(RustScores.min(series)))
        }
    }

    /**
     * The two axis series as their screens hand them over: 5-minute HR bucket means, and daily trend
     * points. These are the labels that were on screen before the extremes moved, frozen.
     */
    @Test
    fun theFrozenAxisLabelsStillRead() {
        val bpm = listOf(72.4, 58.2, 61.0, 143.8, 99.5, 58.2, 84.1)
        assertEquals(58.2, RustScores.min(bpm), 0.0)
        assertEquals(143.8, RustScores.max(bpm), 0.0)
        // The rail rounds for display, and only for display: 144 / 82 / 58 bpm.
        assertEquals(144, RustScores.max(bpm).roundToInt())
        assertEquals(82, RustScores.mean(bpm).roundToInt())
        assertEquals(58, RustScores.min(bpm).roundToInt())

        val recovery = listOf(64.0, 71.0, 48.0, 88.0, 55.0)
        assertEquals(48.0, RustScores.min(recovery), 0.0)
        assertEquals(88.0, RustScores.max(recovery), 0.0)
        assertEquals(65.2, RustScores.mean(recovery), 1e-12)
    }

    /** NaN propagates and a ±0.0 tie resolves by sign, both the way the Kotlin operator does. */
    @Test
    fun theEdgesTheOperatorHasAreTheEdgesTheReductionHas() {
        val withNan = listOf(1.0, Double.NaN, 3.0)
        assertTrue(RustScores.max(withNan).isNaN() && withNan.max().isNaN())
        assertTrue(RustScores.min(withNan).isNaN() && withNan.min().isNaN())
        val zeros = listOf(-0.0, 0.0)
        assertEquals(bits(zeros.max()), bits(RustScores.max(zeros)))
        assertEquals(bits(zeros.min()), bits(RustScores.min(zeros)))
    }

    /**
     * The one place the reduction deliberately differs: an empty series answers 0.0 like every other
     * reduction in the module rather than throwing. Both axis sites gate on a non-empty series before
     * they reach it, so nothing on screen moves.
     */
    @Test
    fun anEmptySeriesIsZeroRatherThanAThrow() {
        assertEquals(0.0, RustScores.min(emptyList()), 0.0)
        assertEquals(0.0, RustScores.max(emptyList()), 0.0)
        assertEquals(42.0, RustScores.min(listOf(42.0)), 0.0)
        assertEquals(42.0, RustScores.max(listOf(42.0)), 0.0)
    }

    /**
     * Neither axis restates the reduction. `OverviewHRChart` keeps its own `bpm.min()`/`bpm.max()`:
     * that pair normalises marker positions to plot pixels beside the line's own geometry and never
     * reaches a label, so it is drawing, not a number.
     */
    @Test
    fun neitherAxisSiteComputesItsOwnExtreme() {
        val dir = uiSourceDir()
        assumeTrue("UI sources unavailable", dir != null)
        val axisRails = mapOf(
            "TrendsCharts.kt" to listOf(
                "val maxV = RustScores.max(values)",
                "val minV = RustScores.min(values)",
            ),
            "TodayHeartRate.kt" to listOf(
                "val min = RustScores.min(bpm).roundToInt()",
                "val max = RustScores.max(bpm).roundToInt()",
                "val visMax = RustScores.max(visBpm).roundToInt()",
                "val visMin = RustScores.min(visBpm).roundToInt()",
            ),
        )
        axisRails.forEach { (name, rails) ->
            val source = File(dir, name).readText()
            rails.forEach { assertTrue("$name no longer reads whoop-rs for: $it", source.contains(it)) }
        }
        // The only surviving operator call in either file is the plot-pixel one.
        val trends = File(dir, "TrendsCharts.kt").readText()
        assertEquals("TrendsCharts.kt computes an extreme", 0, EXTREME_CALL.findAll(trends).count())
        val today = File(dir, "TodayHeartRate.kt").readText()
        assertEquals("TodayHeartRate.kt extremes beyond the plot geometry", 2, EXTREME_CALL.findAll(today).count())
    }

    private fun uiSourceDir(): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            File(userDir, "src/main/java/com/noop/ui"),
            File(userDir, "app/src/main/java/com/noop/ui"),
            File(userDir, "android/app/src/main/java/com/noop/ui"),
        ).firstOrNull { it.isDirectory }
    }

    private companion object {
        /** A `something.max()` / `something.min()` call — the operator whoop-rs now owns. */
        val EXTREME_CALL = Regex("""\.(?:max|min)\(\)""")
    }
}

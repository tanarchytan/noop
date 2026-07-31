package com.noop.ui

import com.noop.data.MockSeeder
import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * The night HR chart's geometry: the padded window, the SQL bucket that keeps a night under the point
 * budget, the bpm axis, the gap-broken trace, and the selected stage's runs re-anchored onto the wider
 * chart window. Both mock fixtures — the day with no session, and the night that crosses midnight — are
 * driven here, because they are the two a screen-level change breaks without failing anything else.
 */
class SleepHrChartTest {

    private val hour = 3_600L

    private fun series(from: Long, to: Long, stepSec: Long, bpm: Double = 60.0): List<TimelinePoint> =
        generateSequence(from) { it + stepSec }.takeWhile { it <= to }
            .map { TimelinePoint(it, bpm) }.toList()

    // MARK: - Window

    @Test
    fun theWindowPadsBothEndsSoTheBoundsSitInsideThePlot() {
        val onset = 1_700_000_000L
        val wake = onset + 9 * hour
        val (start, end) = hrChartWindow(onset, wake)
        assertTrue("the lead-in must precede onset", start < onset)
        assertTrue("the run-out must follow wake", end > wake)
        assertEquals("padding is symmetric", onset - start, end - wake)
    }

    @Test
    fun theWindowPaddingIsClampedAtBothEnds() {
        val onset = 1_700_000_000L
        // A 20-minute nap: 8% is 96 s, held up to the floor so the bounds are not on the plot edge.
        assertEquals(600L, onset - hrChartWindow(onset, onset + 20 * 60).first)
        // A 20-hour window: 8% is 5,760 s, held down to the ceiling so the night keeps the plot.
        assertEquals(2_700L, onset - hrChartWindow(onset, onset + 20 * hour).first)
    }

    @Test
    fun aWholeNightStaysUnderThePointBudget() {
        listOf(4, 6, 8, 9, 12).forEach { hours ->
            val onset = 1_700_000_000L
            val (start, end) = hrChartWindow(onset, onset + hours * hour)
            val points = (end - start) / hrChartBucketSec(end - start)
            assertTrue("$hours h yields $points points", points <= HR_CHART_TARGET_POINTS)
            assertTrue("$hours h yields only $points points", points >= 100)
        }
    }

    @Test
    fun aFractionIsClampedToThePlotAndOrderedInTime() {
        val start = 1_700_000_000L
        val span = 36_000.0
        assertEquals(0f, windowFraction(start, start, span), 1e-6f)
        assertEquals(1f, windowFraction(start + 36_000, start, span), 1e-6f)
        assertEquals(0.5f, windowFraction(start + 18_000, start, span), 1e-6f)
        assertEquals("before the window clamps", 0f, windowFraction(start - 99, start, span), 1e-6f)
        assertEquals("a zero span cannot divide", 0f, windowFraction(start, start, 0.0), 1e-6f)
    }

    // MARK: - Series and axis

    @Test
    fun theSeriesDropsWhatCannotBeDrawnAndOrdersTheRest() {
        val start = 1_700_000_000L
        val raw = listOf(
            TimelinePoint(start + 60, 62.0),
            TimelinePoint(start + 30, 58.0),
            TimelinePoint(start - 30, 61.0),          // before the window
            TimelinePoint(start + 90, 0.0),           // a bucket with no beats
            TimelinePoint(start + 95, Double.NaN),
        )
        assertEquals(listOf(start + 30, start + 60), hrChartSeries(raw, start, start + 100).map { it.ts })
    }

    @Test
    fun theAxisRoundsOutAndKeepsAReadableSpan() {
        assertEquals(50 to 110, hrAxisBounds(listOf(52.0, 91.0, 104.0).map { TimelinePoint(0, it) }))
        // A flat night still gets a full tick step rather than a zero-height plot.
        assertEquals(60 to 80, hrAxisBounds(listOf(61.0, 62.0).map { TimelinePoint(0, it) }))
        assertEquals("no points is a neutral resting band", 50 to 70, hrAxisBounds(emptyList()))
    }

    @Test
    fun theTicksAreLabelledOnTheirOwnMultiples() {
        assertEquals(listOf(60, 80, 100), hrAxisTicks(50, 110))
        assertEquals(listOf(60, 80), hrAxisTicks(60, 80))
        // Bounds too narrow to hold two multiples fall back to the bounds themselves.
        assertEquals(listOf(41, 59), hrAxisTicks(41, 59))
    }

    // MARK: - Trace

    @Test
    fun anUnwornStretchBreaksTheTraceInsteadOfDrawingAcrossIt() {
        val start = 1_700_000_000L
        val runs = hrTraceRuns(series(start, start + 600, 60) + series(start + 3_000, start + 3_600, 60))
        assertEquals(2, runs.size)
        assertEquals(11, runs[0].size)
        assertEquals(11, runs[1].size)
    }

    @Test
    fun anUnbrokenNightIsOneStroke() {
        val start = 1_700_000_000L
        val runs = hrTraceRuns(series(start, start + 6 * hour, 60))
        assertEquals(1, runs.size)
        assertEquals(361, runs[0].size)
    }

    // MARK: - Stage bands

    /** Four equal quarters — light, deep, rem, awake — on a four-hour night, banded onto the chart. */
    private fun bandsFor(stage: String, onset: Long, wake: Long): List<Pair<Float, Float>> {
        val segments = listOf("light" to 60f, "deep" to 60f, "rem" to 60f, "awake" to 60f)
        val (start, end) = hrChartWindow(onset, wake)
        val intervals = nightStageIntervals(segments, nightSpanSec(segments, onset, wake))
        return stageBandsInWindow(intervals, onset, start, (end - start).toDouble(), stage)
    }

    @Test
    fun theSelectedStagesRunsLandInsideThePaddedWindow() {
        val onset = 1_700_000_000L
        val bands = bandsFor("rem", onset, onset + 4 * hour)
        assertEquals(1, bands.size)
        val (from, width) = bands.first()
        assertTrue("the band starts after the lead-in", from > 0f)
        assertTrue("the band ends before the run-out", from + width < 1f)
        // The third of four quarters, in a window padded 1,152 s either side of 14,400 s.
        assertEquals(0.5f, from, 0.002f)
        assertEquals(3_600f / 16_704f, width, 0.002f)
    }

    @Test
    fun eachStageBandsItsOwnQuarterAndTheWakeSpellingIsFolded() {
        val onset = 1_700_000_000L
        val wake = onset + 4 * hour
        val starts = listOf("light", "deep", "rem", "awake").map { bandsFor(it, onset, wake).first().first }
        assertEquals("the quarters run in order", starts.sorted(), starts)
        assertNotEquals(starts[0], starts[1])
        // "wake" is the stored spelling and "Awake" the row label; both must select the same run.
        assertEquals(bandsFor("awake", onset, wake), bandsFor("Wake", onset, wake))
    }

    @Test
    fun noStagesMeansNoBands() {
        val onset = 1_700_000_000L
        val (start, end) = hrChartWindow(onset, onset + 4 * hour)
        assertEquals(
            emptyList<Pair<Float, Float>>(),
            stageBandsInWindow(emptyList(), onset, start, (end - start).toDouble(), "rem"),
        )
    }

    // MARK: - The two mock fixtures

    /**
     * The unslept day seeds no session, so it is not a night the browse stops on and the chart is handed
     * no window. Handed one anyway, a day the strap banked nothing has nothing to stroke and no axis to
     * divide by — the note, never an empty plot.
     */
    @Test
    fun aDayWithNoSessionHasNoTraceAndStillHasAnAxis() {
        assertEquals("the fixture is the day before the last", MockSeeder.DAYS - 2, MockSeeder.UNSLEPT_DAY_INDEX)
        val onset = 1_700_000_000L
        val (start, end) = hrChartWindow(onset, onset + 8 * hour)
        val drawable = hrChartSeries(emptyList(), start, end)
        assertEquals(emptyList<TimelinePoint>(), drawable)
        assertEquals(emptyList<List<TimelinePoint>>(), hrTraceRuns(drawable))
        val (lo, hi) = hrAxisBounds(drawable)
        assertTrue("an empty night still has a scale", hi > lo)
    }

    /** The night after it starts in the evening, so its start and end fall on different calendar days. */
    @Test
    fun theCrossMidnightFixtureIsOneMonotonicWindowNotTwoDays() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val onset = today.minusDays(1)
            .atTime(MockSeeder.CRASH_OUT_HOUR, MockSeeder.CRASH_OUT_MINUTE).atZone(zone).toEpochSecond()
        val wake = today.atTime(5, 12).atZone(zone).toEpochSecond()
        assertNotEquals("the fixture must cross midnight", localDayString(onset), localDayString(wake))

        val (start, end) = hrChartWindow(onset, wake)
        val span = (end - start).toDouble()
        val midnight = today.atStartOfDay(zone).toEpochSecond()
        val onsetFrac = windowFraction(onset, start, span)
        val midnightFrac = windowFraction(midnight, start, span)
        val wakeFrac = windowFraction(wake, start, span)
        assertTrue("the axis runs forward through midnight", onsetFrac < midnightFrac)
        assertTrue("and on to the wake bound", midnightFrac < wakeFrac)
        // The bound labels are the two ends of one span, not two dates.
        assertEquals("20:41", clockTimeLabel(onset))
        assertEquals("05:12", clockTimeLabel(wake))
    }

    /** That night's seeded HR reaches the chart: inside the padded window, one unbroken trace, an axis. */
    @Test
    fun theCrossMidnightFixturesSeededHeartRateDrawsATrace() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val session = SleepSession(
            deviceId = "my-whoop",
            startTs = today.minusDays(1)
                .atTime(MockSeeder.CRASH_OUT_HOUR, MockSeeder.CRASH_OUT_MINUTE).atZone(zone).toEpochSecond(),
            endTs = today.atTime(5, 12).atZone(zone).toEpochSecond(),
            restingHr = 54,
        )
        val samples = MockSeeder.nightHrSamples(Random(1), session)
        assertEquals("the lead-in is seeded", session.startTs - MockSeeder.HR_EDGE_PAD_SEC, samples.first().ts)
        assertTrue("the run-out is seeded", samples.last().ts >= session.endTs)
        assertTrue(
            "ascending, one per cadence step",
            samples.zipWithNext().all { (a, b) -> b.ts - a.ts == MockSeeder.HR_STEP_SEC },
        )
        assertTrue("plausible sleeping bpm", samples.all { it.bpm in 40..110 })

        val (start, end) = hrChartWindow(session.startTs, session.endTs)
        val drawable = hrChartSeries(samples.map { TimelinePoint(it.ts, it.bpm.toDouble()) }, start, end)
        assertTrue("the padded window is covered, ${drawable.size} points", drawable.size > 500)
        assertEquals("no hole in a seeded night", 1, hrTraceRuns(drawable).size)
        val (lo, hi) = hrAxisBounds(drawable)
        assertTrue("the axis brackets the trace", lo <= drawable.minOf { it.value })
        assertTrue("the axis brackets the trace", hi >= drawable.maxOf { it.value })
    }
}

package com.noop.analytics

import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exact wire strings [RestScorer] emits for the Sleep & Rest test-mode export: the
 * motion-coverage/staging line and the onset trace.
 */
class SleepMotionLineTest {

    @Test
    fun `sparse WHOOP4 night`() {
        assertEquals(
            "sleep-motion day=2026-07-12 grav=118 hr=590 sparse=true stager=V2 family=whoop4",
            RestScorer.sleepMotionLine(
                day = "2026-07-12", grav = 118, hr = 590, sparse = true,
                family = DeviceFamily.WHOOP4))
    }

    @Test
    fun `dense 5MG night`() {
        assertEquals(
            "sleep-motion day=2026-07-12 grav=800 hr=590 sparse=false stager=V2 family=whoop5",
            RestScorer.sleepMotionLine(
                day = "2026-07-12", grav = 800, hr = 590, sparse = false,
                family = DeviceFamily.WHOOP5))
    }

    // onset trace

    @Test
    fun `medianBpm sorted middle`() {
        assertEquals(null, RestScorer.medianBpm(emptyList()))
        assertEquals(60, RestScorer.medianBpm(listOf(60)))
        assertEquals(60, RestScorer.medianBpm(listOf(50, 70, 60)))        // sorted 50,60,70 -> idx 1
        assertEquals(70, RestScorer.medianBpm(listOf(50, 60, 70, 80)))    // count 4 -> idx 2 (upper-middle)
    }

    @Test
    fun `onset line HR not dipped is suspected pre-onset-awake`() {
        assertEquals(
            "sleep-onset onsetTs=1700000000 hrAtOnset=58 baselineHr=60 hrRatio=0.97",
            RestScorer.sleepOnsetLine(onsetTs = 1_700_000_000L, hrAtOnsetBpm = 58, baselineHrBpm = 60))
    }

    @Test
    fun `onset line HR dipped is a real onset`() {
        assertEquals(
            "sleep-onset onsetTs=1700000000 hrAtOnset=48 baselineHr=64 hrRatio=0.75",
            RestScorer.sleepOnsetLine(onsetTs = 1_700_000_000L, hrAtOnsetBpm = 48, baselineHrBpm = 64))
    }

    @Test
    fun `onset line zero baseline is safe`() {
        assertEquals(
            "sleep-onset onsetTs=1 hrAtOnset=50 baselineHr=0 hrRatio=0.0",
            RestScorer.sleepOnsetLine(onsetTs = 1L, hrAtOnsetBpm = 50, baselineHrBpm = 0))
    }

    // sub-score decomposition

    /**
     * The one line that carries BOTH halves of the Rest composite: `composite=` comes from whoop-rs,
     * the four sub-scores are recomputed in Kotlin off its own copy of the weights and shape constants.
     * Nothing else keeps those two copies in step, so re-weight the printed sub-scores and require the
     * sum to be the composite. A drift in either side's `rest.rs` constants breaks this line in half.
     */
    private fun assertSubScoresSumToComposite(line: String, expected: Double) {
        fun field(name: String): Double =
            Regex("""\b$name=(-?[0-9.]+)""").find(line)!!.groupValues[1].toDouble()
        assertEquals(expected, field("composite"), 0.005)
        val summed = 100.0 * (
            field("dur") * field("wDur") + field("eff") * field("wEff") +
                field("restor") * field("wRestor") + field("consist") * field("wConsist"))
        assertEquals(field("composite"), summed, 0.01)
    }

    @Test
    fun `rest sub-scores re-weight to the composite whoop-rs returned`() {
        // 8 h slept against an 8 h need, 90% efficient, deep exactly at the full-credit share, and
        // restorative exactly at target: every sub-score lands on a 2 dp value, so no rounding slack.
        assertSubScoresSumToComposite(
            RestScorer.subScoreLine(
                tstSeconds = 28_800.0, inBedSeconds = 32_000.0, efficiency = 0.90,
                restorativeSeconds = 14_400.0, needHours = 8.0, consistency = 0.80,
                deepSeconds = 3_744.0, groupFragments = 1, groupInBedSeconds = 32_000.0),
            expected = 96.0)
    }

    @Test
    fun `a half-adequate deep share scales the restorative term, not the composite arithmetic`() {
        assertSubScoresSumToComposite(
            RestScorer.subScoreLine(
                tstSeconds = 28_800.0, inBedSeconds = 32_000.0, efficiency = 0.90,
                restorativeSeconds = 14_400.0, needHours = 8.0, consistency = 0.80,
                deepSeconds = 1_872.0, groupFragments = 1, groupInBedSeconds = 32_000.0),
            expected = 91.0)
    }

    @Test
    fun `absent consistency scores the neutral centre on both sides`() {
        assertSubScoresSumToComposite(
            RestScorer.subScoreLine(
                tstSeconds = 28_800.0, inBedSeconds = 32_000.0, efficiency = 0.90,
                restorativeSeconds = 14_400.0, needHours = 8.0, consistency = null,
                deepSeconds = 3_744.0, groupFragments = 1, groupInBedSeconds = 32_000.0),
            expected = 93.0)
    }
}

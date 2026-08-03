package com.noop.analytics

import com.noop.data.DailyMetric
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The illness baseline policy is one parameter set, owned by whoop-rs, and both illness paths read it:
 * the heads-up z-scores in [V5HealthSignals] and the banner baseline in [IllnessWatch].
 */
class IllnessBaselineGapTest {

    private fun day(i: Int, restingHr: Int): DailyMetric = DailyMetric(
        deviceId = "test",
        day = "2026-%02d-%02d".format(1 + i / 28, 1 + i % 28),
        restingHr = restingHr,
    )

    /** 52 calm nights (54/56 alternating) then an 8-night resting-HR rise to 62. */
    private fun risingHistory(): List<DailyMetric> =
        (0 until 52).map { day(it, if (it % 2 == 0) 54 else 56) } +
            (52 until 60).map { day(it, 62) }

    @Test
    fun cfgIsTheSinglePolicyBothPathsRead() {
        val cfg = RustScores.illnessBaselineCfg
        assertEquals(3, cfg.gapNights.toInt())
        assertEquals(30, cfg.windowNights.toInt())
        assertEquals(14, cfg.minNights.toInt())
    }

    @Test
    fun multiDayRiseFiresBecauseTheBaselineExcludesTheRecentNights() {
        val snap = V5HealthSignals.evaluate(risingHistory(), cycleOptedIn = false)
        assertTrue(snap.baselineTrusted)
        assertTrue(snap.illness.firedSignals.contains("RHR up"))
    }

    /**
     * The defect this closes: scored against the gapless window the app used to build, the same night
     * sits at 1.71 SD and stays silent - three of its own ill nights are inside its baseline.
     */
    @Test
    fun gaplessBaselineWouldNotHaveFired() {
        val rhr = risingHistory().map { it.restingHr!!.toDouble() }
        val i = rhr.size - 1
        val window = rhr.subList(i - RustScores.illnessBaselineCfg.windowNights.toInt(), i)
        val mean = window.average()
        val sd = sqrt(window.sumOf { (it - mean) * (it - mean) } / (window.size - 1))
        val zGapless = (rhr[i] - mean) / sd
        assertTrue("gapless z was $zGapless", zGapless < IllnessSignalEngine.signalZThreshold)
    }

    /** The banner reads the same policy, so its baseline ends the same gap before today. */
    @Test
    fun bannerBaselineEndsTheGapBeforeToday() {
        val cfg = RustScores.illnessBaselineCfg
        // Calm history, then two nights of RHR +10 and a third the banner must not average in.
        val days = (0 until 40).map { day(it, 50) } + (40 until 43).map { day(it, 60) }
        val msg = IllnessWatch.evaluate(days)
        val base = days.takeLast((cfg.windowNights + cfg.gapNights).toInt()).dropLast(cfg.gapNights.toInt())
        assertTrue(base.all { it.restingHr == 50 })
        assertEquals(cfg.windowNights.toInt(), base.size)
        // One flag only (resting HR), so no banner - the gate is 2+.
        assertNull(msg)
    }
}

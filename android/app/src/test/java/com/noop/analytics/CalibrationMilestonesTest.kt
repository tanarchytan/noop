package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure calibration-milestone timeline that drives the Today countdown cards. The targets are a
 * PRESENTATION overlay (they never touch [Baselines]); these tests lock the ordering, the exactly-one
 * ACTIVE invariant, the done/locked partition, the absolute-fraction contract the card text relies on,
 * and the retire-at-final gate. Mirrors the Swift CalibrationMilestonesTests.
 */
class CalibrationMilestonesTest {

    @Test
    fun timeline_isTheWhoopFamiliarSetInOrder() {
        val nights = CalibrationMilestones.all.map { it.nights }
        assertEquals(listOf(4, 7, 14, 30), nights)
        // The soonest/last targets stay pinned to the honest baseline gates + WHOOP's day-30 full baseline.
        assertEquals(Baselines.minNightsSeed, CalibrationMilestones.all.first().nights)
        assertEquals(Baselines.minNightsTrust, CalibrationMilestones.all[2].nights)
        assertEquals(30, CalibrationMilestones.finalNights)
    }

    @Test
    fun brandNewUser_firstMilestoneIsActive_restLocked_noneDone() {
        val p = CalibrationMilestones.progress(0)
        assertEquals(CalibrationMilestones.State.ACTIVE, p[0].state)
        assertTrue(p.drop(1).all { it.state == CalibrationMilestones.State.LOCKED })
        assertFalse(p.any { it.state == CalibrationMilestones.State.DONE })
        // "N nights to go" is the raw gap; the first bar starts empty.
        assertEquals(4, p[0].remaining)
        assertEquals(0.0, p[0].fraction, 1e-9)
    }

    @Test
    fun exactlyOneActive_untilFullyCalibrated() {
        for (n in 0..29) {
            val active = CalibrationMilestones.progress(n).count { it.state == CalibrationMilestones.State.ACTIVE }
            assertEquals("banked=$n should have exactly one live countdown", 1, active)
        }
    }

    @Test
    fun midCalibration_partitionsAndCountsDown() {
        // 9 nights banked: 4 & 7 are DONE, 14 is the live countdown, 30 is locked (matches the card mockup).
        val p = CalibrationMilestones.progress(9)
        assertEquals(CalibrationMilestones.State.DONE, p[0].state)   // 4
        assertEquals(CalibrationMilestones.State.DONE, p[1].state)   // 7
        assertEquals(CalibrationMilestones.State.ACTIVE, p[2].state) // 14
        assertEquals(CalibrationMilestones.State.LOCKED, p[3].state) // 30
        assertEquals(5, p[2].remaining)   // "5 nights to go"
        assertEquals(21, p[3].remaining)  // "21 nights to go"
        // Absolute fill so the "9/14" / "9/30" labels agree with the bars.
        assertEquals(9.0 / 14.0, p[2].fraction, 1e-9)
        assertEquals(9.0 / 30.0, p[3].fraction, 1e-9)
        // A DONE milestone is always full, never over/under-filled.
        assertEquals(1.0, p[0].fraction, 1e-9)
    }

    @Test
    fun onTheDot_countsAsDone() {
        // Reaching the target exactly (>=) flips it DONE with zero remaining, not a lingering "1 to go".
        val p = CalibrationMilestones.progress(14)
        assertEquals(CalibrationMilestones.State.DONE, p[2].state)
        assertEquals(0, p[2].remaining)
        assertEquals(CalibrationMilestones.State.ACTIVE, p[3].state) // 30 is now the live one
    }

    @Test
    fun fullyCalibrated_retiresTheCard() {
        assertTrue(CalibrationMilestones.isCalibrating(0))
        assertTrue(CalibrationMilestones.isCalibrating(29))
        assertFalse(CalibrationMilestones.isCalibrating(30))
        assertFalse(CalibrationMilestones.isCalibrating(45))
        // At/after the final target everything is DONE, nothing is left ACTIVE.
        val p = CalibrationMilestones.progress(30)
        assertTrue(p.all { it.state == CalibrationMilestones.State.DONE })
    }

    @Test
    fun negativeCountClampsToZero() {
        // Defensive: a bad caller count never throws or produces a negative fraction/remaining.
        val p = CalibrationMilestones.progress(-3)
        assertEquals(0.0, p[0].fraction, 1e-9)
        assertEquals(4, p[0].remaining)
        assertEquals(CalibrationMilestones.State.ACTIVE, p[0].state)
    }

    @Test
    fun bankedNightsMatchesBaselineValidityPredicate() {
        // The uncapped banked-night predicate the cards feed from (used all the way to 30) excludes
        // out-of-range nights, so a wild reading can't inflate the countdown. Parity with Swift
        // RecoveryScorer.bankedNights (CalibrationMilestonesTests.testBankedNightsMatchesBaselineValidityPredicate).
        val cfg = Baselines.hrvCfg
        val good = (cfg.minVal + cfg.maxVal) / 2.0
        val nightly = listOf<Double?>(good, null, cfg.maxVal + 1000, good, good)
        assertEquals(3, RecoveryScorer.bankedNights(nightly, cfg))
    }
}

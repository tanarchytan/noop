package com.noop.ui

import com.noop.ui.whoop.chargeReadinessWord
import com.noop.ui.whoop.stressHoursCaption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.whoop_ffi.HrvReadinessInfo
import uniffi.whoop_ffi.ReadinessTier
import uniffi.whoop_ffi.RecoveryState

/**
 * The pure copy helpers behind the round-1 gate fixes: the Charge readiness word, the Health stress
 * caption, the HRV band sentence, the value/unit join and the live-session verdict's no-second case.
 */
class GateRoundOneCopyTest {

    @Test fun readinessWordMovesWithTheScoreBand() {
        assertEquals("Rest", chargeReadinessWord(RecoveryState.DEPLETED))
        assertEquals("Rest", chargeReadinessWord(RecoveryState.LOW))
        assertEquals("Maintain", chargeReadinessWord(RecoveryState.MODERATE))
        assertEquals("Push", chargeReadinessWord(RecoveryState.PRIMED))
        assertEquals("Push", chargeReadinessWord(RecoveryState.PEAK))
        // The defect this closes: a low day and a peak day read the same word.
        assertNotEquals(
            chargeReadinessWord(RecoveryState.LOW),
            chargeReadinessWord(RecoveryState.PEAK),
        )
    }

    @Test fun stressCaptionNamesTheHoursItHasOrWhatItLacks() {
        assertEquals("12m high stress today", stressHoursCaption(scoredHours = 6, highMinutes = 12L))
        assertTrue(stressHoursCaption(scoredHours = 1, highMinutes = 12L).contains("more scored hours"))
        assertTrue(stressHoursCaption(scoredHours = 6, highMinutes = null).contains("more scored hours"))
    }

    @Test fun hrvBandSentenceReadsAgainstTheBandNotInsideIt() {
        val read = HrvReadinessInfo(
            tier = ReadinessTier.NORMAL,
            baseline7Ms = 54.4,
            normalLowMs = 48.6,
            normalHighMs = 61.2,
            overreachingWatch = false,
        )
        assertEquals(
            "Your 7-night baseline is 54 ms, against a normal band of 49 ms to 61 ms.",
            hrvReadinessDetail(read),
        )
        assertTrue(
            hrvReadinessDetail(read.copy(overreachingWatch = true)).contains("drifting down"),
        )
    }

    @Test fun percentBindsTightAndEveryOtherUnitTakesASpace() {
        assertEquals("97%", UnitFormatter.withUnit("97", "%"))
        assertEquals("71 ms", UnitFormatter.withUnit("71", "ms"))
        assertEquals("12.3/21", UnitFormatter.withUnit("12.3", "/21"))
        assertEquals("36", UnitFormatter.withUnit("36", ""))
    }

    @Test fun aSessionThatClassifiedNoSecondSaysSoRatherThanJudging() {
        val none = liveSessionVerdict(inBandSec = 0.0, belowSec = 0.0, aboveSec = 0.0)
        assertTrue("nothing scored must be named", none.contains("nothing could be scored"))
        assertNotEquals(none, liveSessionVerdict(inBandSec = 10.0, belowSec = 0.0, aboveSec = 0.0))
        assertEquals("Too short to judge.", liveSessionVerdict(10.0, 0.0, 0.0))
    }
}

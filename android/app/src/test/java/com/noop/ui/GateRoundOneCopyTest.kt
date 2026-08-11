package com.noop.ui

import com.noop.R
import com.noop.ui.whoop.chargeReadinessWord
import com.noop.ui.whoop.stressHoursCaptionRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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
        // The wording is a resource now; which of the two lines the day earns is the decision.
        assertEquals(
            R.string.uicore_stress_high_today,
            stressHoursCaptionRes(scoredHours = 6, highMinutes = 12L),
        )
        assertEquals(
            R.string.uicore_stress_needs_more_hours,
            stressHoursCaptionRes(scoredHours = 1, highMinutes = 12L),
        )
        assertEquals(
            R.string.uicore_stress_needs_more_hours,
            stressHoursCaptionRes(scoredHours = 6, highMinutes = null),
        )
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

    /** The verdict now names a key; strings_core.xml says what the key reads, so both stay pinned. */
    @Test fun aSessionThatClassifiedNoSecondSaysSoRatherThanJudging() {
        val core = coreStrings()
        val none = liveSessionVerdict(inBandSec = 0.0, belowSec = 0.0, aboveSec = 0.0)
        assertEquals(R.string.core_live_verdict_nothing_scored, none)
        assertTrue(
            "nothing scored must be named",
            core.getValue("core_live_verdict_nothing_scored").contains("nothing could be scored"),
        )
        assertNotEquals(none, liveSessionVerdict(inBandSec = 10.0, belowSec = 0.0, aboveSec = 0.0))
        assertEquals(R.string.core_live_verdict_too_short, liveSessionVerdict(10.0, 0.0, 0.0))
        assertEquals("Too short to judge.", core.getValue("core_live_verdict_too_short"))
    }

    /** Every `<string name="x">y</string>` in the app's strings_core.xml. */
    private fun coreStrings(): Map<String, String> {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val res = listOf(userDir, File(userDir, "app"), File(userDir, "android/app"))
            .map { File(it, "src/main/res/values/strings_core.xml") }
            .firstOrNull { it.isFile }
            ?: error("strings_core.xml not found from ${userDir.absolutePath}")
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(res.readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }
}

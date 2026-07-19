package com.noop.ui

import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mainSleepSpan] bridges a night split into two stored fragments (brief mid-night wake, gap <
 * [com.noop.analytics.SleepStageTotals.GAP_BRIDGE_MAX_MIN]) into one bed-wake span, so every screen
 * reports the same "last night" instead of naming one fragment alone.
 */
class MainSleepSpanTest {

    private val fragment1 = SleepSession(
        deviceId = "my-whoop",
        startTs = 1_800_000_000L,                                    // onset
        endTs = 1_800_000_000L + 2 * 3600 + 14 * 60,                 // 2h14m asleep, then a brief wake
    )

    // A 20-minute mid-night wake -- well under GAP_BRIDGE_MAX_MIN (60 min) -- so this is ONE
    // interrupted night, not a nap followed by a separate main sleep.
    private val fragment2 = SleepSession(
        deviceId = "my-whoop",
        startTs = fragment1.endTs + 20 * 60,
        endTs = fragment1.endTs + 20 * 60 + 4 * 3600 + 41 * 60,      // 4h41m asleep, then wake
    )

    @Test
    fun bridgesBothFragmentsIntoOneSpan() {
        val span = mainSleepSpan(listOf(fragment1, fragment2))
        assertEquals(fragment1.effectiveStartTs to fragment2.endTs, span)
    }

    @Test
    fun oldFreshestEndingPickWouldHaveTruncatedTheNight() {
        // Regression guard: picking by "latest endTs" alone names only the second fragment.
        val freshestEndingPick = listOf(fragment1, fragment2).maxByOrNull { it.endTs }!!
        val span = mainSleepSpan(listOf(fragment1, fragment2))!!
        assertTrue(span.first < freshestEndingPick.effectiveStartTs)
    }

    @Test
    fun oldLongestSingleBlockPickWouldHaveTruncatedTheNight() {
        // "Longest single overlapping block" also names only the second fragment, since it out-lasts the first.
        val longestSinglePick = listOf(fragment1, fragment2).maxByOrNull { it.endTs - it.startTs }!!
        val span = mainSleepSpan(listOf(fragment1, fragment2))!!
        assertTrue(span.first < longestSinglePick.effectiveStartTs)
    }
}

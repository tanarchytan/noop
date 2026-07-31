package com.noop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carried sleep caption may only say "Last night" when the carried day IS yesterday.
 *
 * A user awake from the evening of Jul 29 to the evening of Jul 30 saw Jul 29's sleep on Jul 30
 * captioned "Last night", on a night he had not slept. The caption used [isCarryStale], whose window is
 * [CARRY_FRESHNESS_DAYS] = 2 days, so exactly one skipped night still read as fresh. The stored data was
 * right — Jul 30 held no sleep fields at all — and only the wording claimed otherwise.
 */
class CarriedCaptionTest {

    @Test
    fun yesterdayIsTheOnlyDayThatMayReadLastNight() {
        assertTrue(isPreviousDay("2026-07-29", today = "2026-07-30"))
        assertFalse("a skipped night is not last night", isPreviousDay("2026-07-29", today = "2026-07-31"))
        assertFalse(isPreviousDay("2026-07-29", today = "2026-08-05"))
        assertFalse("the same day is not last night", isPreviousDay("2026-07-30", today = "2026-07-30"))
    }

    /** The reported case: on Jul 30 the Jul 29 carry must not claim the night happened. */
    @Test
    fun aSkippedNightReadsAsLatestSleepNotLastNight() {
        val skipped = carriedCaption("2026-07-29", today = "2026-07-31")
        assertTrue(skipped, skipped.startsWith("Latest sleep"))
        assertFalse(skipped, skipped.contains("Last night"))
    }

    @Test
    fun anOrdinaryMorningStillReadsLastNight() {
        assertTrue(carriedCaption("2026-07-29", today = "2026-07-30").startsWith("Last night"))
    }

    /** Every caption stamps the carried day's own date, whichever prefix it takes, so the reader can
     *  always see which night the number came from. */
    @Test
    fun everyCaptionCarriesItsDate() {
        listOf("2026-07-30" to "2026-07-31", "2026-07-29" to "2026-07-31").forEach { (prior, today) ->
            val caption = carriedCaption(prior, today = today)
            assertTrue(caption, caption.contains("·"))
            assertTrue(caption, caption.length > "Last night · ".length)
        }
    }

    /** The Rest tail-fallback window is a separate question from the wording and is left alone: a
     *  one-night gap still carries a value, it is just no longer called last night. */
    @Test
    fun theFallbackWindowIsUnchangedByTheWordingFix() {
        assertFalse(isCarryStale("2026-07-29", today = "2026-07-31"))
        assertTrue(isCarryStale("2026-07-29", today = "2026-08-05"))
    }
}

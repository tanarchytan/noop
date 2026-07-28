package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shared day-key arithmetic, pinned against the cases the four copies it replaces relied on:
 * leap years, month ends, year and century boundaries, and the round trip through a Julian Day
 * Number. Integer-only and timezone-free, so none of this may depend on a clock or a zone.
 */
class CalendarDayTest {

    @Test
    fun leapYearsFollowTheGregorianRule() {
        assertEquals(29, CalendarDay.daysInMonth(2024, 2))   // divisible by 4
        assertEquals(28, CalendarDay.daysInMonth(2026, 2))   // ordinary year
        assertEquals(28, CalendarDay.daysInMonth(1900, 2))   // century, not divisible by 400
        assertEquals(29, CalendarDay.daysInMonth(2000, 2))   // divisible by 400
    }

    @Test
    fun monthLengthsAndTheOutOfRangeSentinel() {
        assertEquals(31, CalendarDay.daysInMonth(2026, 1))
        assertEquals(30, CalendarDay.daysInMonth(2026, 4))
        assertEquals(0, CalendarDay.daysInMonth(2026, 0))
        assertEquals(0, CalendarDay.daysInMonth(2026, 13))
    }

    @Test
    fun parseRejectsWhatIsNotARealDate() {
        assertEquals(Triple(2026, 7, 28), CalendarDay.parse("2026-07-28"))
        assertNull(CalendarDay.parse("2026-02-30"))   // past the month's end
        assertNull(CalendarDay.parse("2026-13-01"))   // month out of range
        assertNull(CalendarDay.parse("2026-07"))      // wrong shape
        assertNull(CalendarDay.parse("not-a-date"))
        assertEquals(Triple(2024, 2, 29), CalendarDay.parse("2024-02-29"))  // real on a leap year
        assertNull(CalendarDay.parse("2026-02-29"))                          // not on an ordinary one
    }

    @Test
    fun formatZeroPadsSoKeysSortAsStrings() {
        assertEquals("2026-07-08", CalendarDay.format(2026, 7, 8))
        assertEquals("0999-01-01", CalendarDay.format(999, 1, 1))
    }

    @Test
    fun theJulianRoundTripIsExactAcrossBoundaries() {
        for (day in listOf("2026-07-28", "2024-02-29", "2000-02-29", "1900-03-01",
                           "2025-12-31", "2026-01-01", "0999-12-31")) {
            val (y, m, d) = CalendarDay.parse(day)!!
            val (yy, mm, dd) = CalendarDay.fromJdn(CalendarDay.toJdn(y, m, d))
            assertEquals(day, CalendarDay.format(yy, mm, dd))
        }
    }

    @Test
    fun addDaysCrossesMonthYearAndLeapBoundaries() {
        assertEquals("2026-08-01", CalendarDay.addDays("2026-07-31", 1))
        assertEquals("2026-01-01", CalendarDay.addDays("2025-12-31", 1))
        assertEquals("2024-02-29", CalendarDay.addDays("2024-02-28", 1))
        assertEquals("2026-03-01", CalendarDay.addDays("2026-02-28", 1))
        assertEquals("2026-07-28", CalendarDay.addDays("2026-08-04", -7))
        assertEquals("2026-07-28", CalendarDay.addDays("2026-07-28", 0))
    }

    @Test
    fun anUnparseableKeyComesBackUnchangedRatherThanWrong() {
        assertEquals("garbage", CalendarDay.addDays("garbage", 5))
        assertNull(CalendarDay.daysBetween("garbage", "2026-07-28"))
    }

    @Test
    fun daysBetweenIsSignedAndSpansLeapDays() {
        assertEquals(7, CalendarDay.daysBetween("2026-07-21", "2026-07-28"))
        assertEquals(-7, CalendarDay.daysBetween("2026-07-28", "2026-07-21"))
        assertEquals(0, CalendarDay.daysBetween("2026-07-28", "2026-07-28"))
        assertEquals(366, CalendarDay.daysBetween("2024-01-01", "2025-01-01"))  // leap year
        assertEquals(365, CalendarDay.daysBetween("2026-01-01", "2027-01-01"))
    }
}

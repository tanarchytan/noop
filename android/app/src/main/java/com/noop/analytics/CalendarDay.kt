package com.noop.analytics

/*
 * Proleptic-Gregorian date arithmetic on "yyyy-MM-dd" day keys.
 *
 * Integer-only and timezone-free by design: a day key is a calendar label, not an instant, so
 * shifting one must never route through a zone or a clock. Four copies of these routines had grown
 * across the analytics and ingest packages.
 */
internal object CalendarDay {

    /** True for a leap year in the proleptic Gregorian calendar. */
    fun isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)

    /** Days in month [m] of year [y]; 0 when [m] is outside 1..12. */
    fun daysInMonth(y: Int, m: Int): Int = when (m) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeap(y)) 29 else 28
        else -> 0
    }

    /** Parse "yyyy-MM-dd" into (year, month, day), or null when malformed or not a real date. */
    fun parse(s: String): Triple<Int, Int, Int>? {
        val parts = s.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d < 1 || d > daysInMonth(y, m)) return null
        return Triple(y, m, d)
    }

    /** Format as "yyyy-MM-dd", zero-padding a year below 1000 so keys stay sortable as strings. */
    fun format(y: Int, m: Int, d: Int): String {
        val yy = if (y < 1000) y.toString().padStart(4, '0') else y.toString()
        val mm = if (m < 10) "0$m" else "$m"
        val dd = if (d < 10) "0$d" else "$d"
        return "$yy-$mm-$dd"
    }

    /** Date to Julian Day Number, so day arithmetic is integer subtraction. */
    fun toJdn(y: Int, m: Int, d: Int): Int {
        val a = (14 - m) / 12
        val yy = y + 4800 - a
        val mm = m + 12 * a - 3
        return d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
    }

    /** Julian Day Number back to (year, month, day). */
    fun fromJdn(jdn: Int): Triple<Int, Int, Int> {
        val a = jdn + 32044
        val b = (4 * a + 3) / 146097
        val c = a - (146097 * b) / 4
        val dd = (4 * c + 3) / 1461
        val e = c - (1461 * dd) / 4
        val mm = (5 * e + 2) / 153
        return Triple(100 * b + dd - 4800 + mm / 10, mm + 3 - 12 * (mm / 10), e - (153 * mm + 2) / 5 + 1)
    }

    /** [day] shifted by [n] days (negative shifts back); returns [day] unchanged when unparseable. */
    fun addDays(day: String, n: Int): String {
        val (y, m, d) = parse(day) ?: return day
        val (yy, mm, dd) = fromJdn(toJdn(y, m, d) + n)
        return format(yy, mm, dd)
    }

    /** Whole days from [from] to [to], or null when either key is unparseable. */
    fun daysBetween(from: String, to: String): Int? {
        val a = parse(from) ?: return null
        val b = parse(to) ?: return null
        return toJdn(b.first, b.second, b.third) - toJdn(a.first, a.second, a.third)
    }
}

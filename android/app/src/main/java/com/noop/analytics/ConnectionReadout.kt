package com.noop.analytics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ConnectionReadout.kt - pure values + line formatters for the Connection & Sync test mode: the
// clock-drift summary line (strap-reported banked-record range vs wall clock with a future-date flag),
// the firmware-layout line, the no-cursor / trim sentinel line, and the tagged-tail parsers for the
// three liveReadout ids. No state, no IO, no em-dashes.

object ConnectionTrace {

    /**
     * The CLOCK-DRIFT summary line: strap-reported banked-record window [oldest, newest] vs wall clock,
     * ending in the shared clock VERDICT ([clockVerdict]): FUTURE-DATED, RTC-EPOCH (never-set clock), or
     * CLOCK-WARNING (behind tolerance), else clockOk. [oldestUnix] is optional; timestamps are unix seconds.
     */
    fun clockDriftLine(
        oldestUnix: Long?,
        newestUnix: Long,
        wallNowUnix: Long,
        futureToleranceSeconds: Long = 120L,
        behindToleranceSeconds: Long = BEHIND_TOLERANCE_DEFAULT,
    ): String {
        val iso = isoDate(newestUnix)
        val aheadSeconds = newestUnix - wallNowUnix
        val sb = StringBuilder()
        sb.append("clockDrift newest=").append(iso)
            .append(" wall=").append(isoDate(wallNowUnix))
            .append(" newestVsWall=").append(signed(aheadSeconds)).append("s")
        if (oldestUnix != null) {
            val spanDays = maxOf(0L, newestUnix - oldestUnix) / 86_400L
            sb.append(" oldest=").append(isoDate(oldestUnix)).append(" spanDays=").append(spanDays)
        }
        sb.append(clockVerdict(aheadSeconds, newestUnix, futureToleranceSeconds, behindToleranceSeconds))
        return sb.toString()
    }

    // Strap-clock verdict - shared by clockDriftLine on both its Connection and universal emit sites.

    /** 1972-01-01 unix. A strap RTC that was never set counts up from its 1970 epoch, so any strap-side
     *  timestamp below this ceiling means "the clock never latched" — the strap banks nothing to flash
     *  until its clock is set. */
    const val RTC_EPOCH_CEILING_UNIX = 63_072_000L

    /** Default BEHIND drift tolerance: +-48 h. A newest banked record a day or two behind is a strap that
     *  simply was not worn; beyond that the line must warn, never claim "clockOk". */
    const val BEHIND_TOLERANCE_DEFAULT = 48L * 3_600L

    /** The strap-clock VERDICT token the clock-drift line ends with, ordered most specific first: FUTURE
     *  (RTC ahead), RTC-EPOCH (never set, ~1970/71), CLOCK-WARNING (behind tolerance), else clockOk.
     *  Honest wording on the behind case: a reset clock and a long-unworn strap look identical, so it names both. */
    internal fun clockVerdict(
        aheadSeconds: Long,
        newestUnix: Long,
        futureToleranceSeconds: Long,
        behindToleranceSeconds: Long,
    ): String {
        if (aheadSeconds > futureToleranceSeconds) return " FUTURE-DATED (strap clock ahead of wall)"
        if (newestUnix < RTC_EPOCH_CEILING_UNIX) {
            return " RTC-EPOCH (strap clock reads 1970/71, never set; charge to 100% and reconnect so it latches)"
        }
        if (aheadSeconds < -behindToleranceSeconds) {
            val days = -aheadSeconds / 86_400L
            return " CLOCK-WARNING (newest banked record ${days}d behind wall; strap clock reset or history stale)"
        }
        return " clockOk"
    }

    /** The firmware-layout line for a HEALTHY sync: which historical record layout the strap emits
     *  (v18/v24/v25/v26). */
    fun firmwareLine(version: Int, decodable: Boolean): String =
        "firmware layout=v$version " +
            if (decodable) "decodable" else "UNMAPPED (no motion/HR decoded)"

    /** The trim / no-cursor sentinel line: the strap reported trim=0xFFFFFFFF, its "no valid flash
     *  cursor" marker (a clock/charge state, not a decode bug). */
    fun noCursorLine(): String =
        "offload trim=0xFFFFFFFF noCursor (strap has no banked history to offload)"

    /** Compact ISO-8601 date-time (no fractional seconds), UTC. */
    internal fun isoDate(unix: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(unix * 1000L))
    }

    /** Sign-prefixed integer so the newest-vs-wall delta reads as a signed offset. */
    internal fun signed(n: Long): String = if (n >= 0) "+$n" else "$n"
}

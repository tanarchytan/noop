package com.noop.analytics

import com.noop.data.MetricSeriesRow
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Tap-to-mark "going to sleep" / "awake" logging (Phase 1). A user-tapped sleep boundary,
 * captured for the record only — it does NOT feed the sleep detector (that stays the strap's
 * job). Every mark is persisted into the existing long-format `metricSeries` store under the
 * key "sleep_mark", AND appended as a human-readable line to the shareable strap log.
 *
 * The store's natural key is (deviceId, day, key) with a single REAL `value`, so the mark TYPE
 * is encoded in the value (0 = bedtime, 1 = wake) and the row is keyed on the mark's local
 * calendar day. The precise wall-clock instant lives in the strap-log line (and [tsMs] here).
 * Pure and DB-free, so it unit-tests without a UI; keep the value encoding and the "sleep_mark"
 * key stable so existing rows keep decoding.
 */

/** One sleep boundary the user tapped. */
enum class SleepMarkType(val seriesValue: Int) {
    BEDTIME(0),   // "Going to sleep"
    WAKE(1);      // "I'm awake"

    /** Short word used in the confirming toast / strap-log line. */
    val word: String get() = if (this == BEDTIME) "bedtime" else "wake"

    companion object {
        /** Decode a persisted series value back to a type. Tolerant of float drift (rounds) and
         *  clamps any unexpected value to the nearest valid case so a corrupt row never crashes. */
        fun fromSeriesValue(value: Double): SleepMarkType =
            if (Math.round(value).toInt() == WAKE.seriesValue) WAKE else BEDTIME
    }
}

/**
 * A captured mark: a type plus the wall-clock instant it was tapped (unix MILLISECONDS, `tsMs`).
 * The calendar [dayKey] is derived locally for the store's natural key.
 */
data class SleepMark(
    val type: SleepMarkType,
    val tsMs: Long,
) {
    /** The mark's local calendar day (yyyy-MM-dd) — the `day` of the store's natural key. Local zone
     *  so the mark lands on the day the user actually tapped it. */
    val dayKey: String get() = dayFormatter().format(Date(tsMs))

    /** Project this mark into a `metricSeries` row: key "sleep_mark", value 0/1 = type, day = local
     *  calendar day. Upsert is idempotent by (deviceId, day, key); a later same-day mark replaces the
     *  earlier value — the strap log keeps the full sequence, which Phase-1 logging relies on. */
    fun metricPoint(deviceId: String): MetricSeriesRow =
        MetricSeriesRow(deviceId = deviceId, day = dayKey, key = SERIES_KEY, value = type.seriesValue.toDouble())

    /** The human-readable strap-log line, e.g. "Sleep mark · bedtime (going to sleep) @ 23:42".
     *  Appended to the shared strap log so the mark appears in a debug export. Carries no PII. */
    fun logLine(): String {
        val clock = clockFormatter().format(Date(tsMs))
        val phrase = if (type == SleepMarkType.BEDTIME) "going to sleep" else "awake"
        return "Sleep mark · ${type.word} ($phrase) @ $clock"
    }

    /** The confirming toast, e.g. "Logged bedtime at 23:42." */
    fun confirmation(): String {
        val clock = clockFormatter().format(Date(tsMs))
        val what = if (type == SleepMarkType.BEDTIME) "bedtime" else "wake-up"
        return "Logged $what at $clock."
    }

    companion object {
        /** The metric-series key all sleep marks share. */
        const val SERIES_KEY = "sleep_mark"

        /** Capture a mark at the current instant. */
        fun now(type: SleepMarkType): SleepMark = SleepMark(type, System.currentTimeMillis())

        /** Capture a mark at the current instant for a NON-UI caller (double-tap automation) with no
         *  bedtime/wake picker. Defaults to BEDTIME, the boundary a double-tap-to-sleep gesture most
         *  naturally marks; the UI's two-button card keeps choosing the type explicitly. */
        fun nowDefault(): SleepMark = now(SleepMarkType.BEDTIME)

        /** Reconstruct a mark from a persisted row — the round-trip read-back. The row carries no
         *  sub-day time, so the instant resolves to that day's LOCAL midnight; the type is exact.
         *  Returns null for a row that isn't a sleep-mark or whose day won't parse. */
        fun fromRow(row: MetricSeriesRow): SleepMark? {
            if (row.key != SERIES_KEY) return null
            val date = runCatching { dayFormatter().parse(row.day) }.getOrNull() ?: return null
            return SleepMark(SleepMarkType.fromSeriesValue(row.value), date.time)
        }

        // Formatters are created per-call (SimpleDateFormat is not thread-safe); these are tiny.
        private fun dayFormatter(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }

        // Device-locale SHORT clock for the log/toast lines — follows the locale's 12-/24-hour
        // convention (the locale's own preference), pure and context-free. Local time zone.
        private fun clockFormatter(): DateFormat =
            DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
                .apply { timeZone = TimeZone.getDefault() }
    }
}

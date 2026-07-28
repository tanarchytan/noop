package com.noop.analytics

import java.util.Locale

/** Pure formatter for the Battery test mode's per-sample (t, soc) log line, kept out of the
 *  Android-bound BLE client so it is JVM-unit-testable. No em-dashes in the output. */
object BatterySocLine {
    fun format(pct: Double, tSeconds: Long): String =
        "bank soc=${String.format(Locale.US, "%.1f", pct)} t=${tSeconds}s"
}

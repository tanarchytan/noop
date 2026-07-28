package com.noop.analytics

// DisplayTrace.kt - pure values + line formatters for the Display & Performance test mode: the
// device-metrics summary, the rolling frame-time / hitch summary, and the memory high-water line,
// plus the tagged-tail parsers for the deviceMetricsNow / frameSummaryNow ids. No state, no IO.

/**
 * A snapshot of the display environment, already read by the caller (Android Configuration / window
 * insets) so this type is pure data. Nullable fields are metrics a platform cannot offer; the
 * formatter prints "n/a" for a null, never fabricating a value.
 */
data class DisplayMetrics(
    val horizontalSizeClass: String?,
    val verticalSizeClass: String?,
    val widthPt: Double,
    val heightPt: Double,
    val scale: Double,
    val safeTop: Double,
    val safeBottom: Double,
    val safeLeading: Double,
    val safeTrailing: Double,
    val dynamicType: String?,
    val orientation: String,
    val theme: String,
)

/**
 * A snapshot of the on-device DATA VOLUME: the read-set that backs the screens, so an
 * import-driven-lag report shows what it renders over, not just frame stats. Counts are read
 * from the STORE directly (never the view-model caches), so this type stays store-independent.
 */
data class DataVolume(
    /** Total raw stream rows in the store (HR + RR + events + the biometric streams), the dominant cost. */
    val dbRows: Int,
    /** Number of distinct days that carry imported daily metrics. */
    val importedDays: Int,
    /** Total detected/recorded workout rows. */
    val workouts: Int,
    /** Rows touched by the most recent render the caller measured, or null when it hasn't measured one. */
    val lastRenderRows: Int?,
)

object DisplayTrace {

    /** The data-volume line: one upfront summary of the store's read-set, so a "feels laggy after
     *  import" report shows how much data the screens render over (db rows, imported days, workouts,
     *  last render's row count). A null lastRenderRows prints "n/a" rather than fabricating a 0. */
    fun dataVolumeLine(v: DataVolume): String {
        val last = v.lastRenderRows?.toString() ?: "n/a"
        return "dataVolume dbRows=${v.dbRows} importedDays=${v.importedDays} " +
            "workouts=${v.workouts} lastRenderRows=$last"
    }

    /** The device-metrics line: one upfront summary of the resolved DisplayMetrics, so a "screen looks
     *  wrong" report carries the exact layout environment. Whole-point rounding; a null size class or
     *  Dynamic Type prints "n/a". */
    fun deviceMetricsLine(m: DisplayMetrics): String {
        val h = m.horizontalSizeClass ?: "n/a"
        val v = m.verticalSizeClass ?: "n/a"
        val dt = m.dynamicType ?: "n/a"
        return "deviceMetrics " +
            "size=${pt(m.widthPt)}x${pt(m.heightPt)}pt @${scaleLabel(m.scale)}x " +
            "sizeClass=$h/$v " +
            "safeArea=t${pt(m.safeTop)} b${pt(m.safeBottom)} l${pt(m.safeLeading)} r${pt(m.safeTrailing)} " +
            "dynamicType=$dt orientation=${m.orientation} theme=${m.theme}"
    }

    /** The rolling frame-time / hitch summary line: a periodic digest (not a per-frame line) of the
     *  frame monitor's last window. */
    fun frameSummaryLine(
        frames: Int, meanMs: Double, p95Ms: Double, hitches: Int, worstMs: Double, hitchThresholdMs: Double,
    ): String =
        "frameSummary frames=$frames mean=${ms(meanMs)}ms p95=${ms(p95Ms)}ms " +
            "hitches=$hitches worst=${ms(worstMs)}ms threshold=${ms(hitchThresholdMs)}ms"

    /** The memory high-water line: the peak resident footprint (MB) seen while the mode was active. */
    fun memoryHighWaterLine(peakMB: Double): String = "memoryHighWater peak=${ms(peakMB)}MB"

    /** Round a point value to a whole number; negatives clamp to 0 (an inset is never negative). */
    internal fun pt(v: Double): String = maxOf(0.0, v).let { Math.round(it).toInt().toString() }

    /** Backing scale to one decimal; "?" when the caller could not read it (0). */
    internal fun scaleLabel(v: Double): String = if (v > 0) oneDecimal(v) else "?"

    /** Millisecond / MB value to one decimal, clamped at 0. */
    internal fun ms(v: Double): String = oneDecimal(maxOf(0.0, v))

    /** Locale-stable one-decimal format so the line reads identically everywhere (Locale.US avoids a
     *  device's default locale changing the decimal separator). */
    private fun oneDecimal(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)
}

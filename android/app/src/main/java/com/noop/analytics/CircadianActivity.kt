package com.noop.analytics

import com.noop.data.GravitySample

/**
 * Pools persisted gravity samples into per-hour rest-activity bins for the circadian cosinor. Each bin is
 * the mean of dynAccelG (the v18 on-chip gravity-removed motion magnitude) over its LOCAL clock hour, so a
 * multi-day window collapses to at most 24 bins. Samples with a null dynAccelG (WHOOP 4.0, or rows banked
 * before @41 was persisted) are skipped. Pure: the caller supplies the Room read + the tz offset, and hands
 * the result to RustScores.rhythmAge / circadianPhase.
 */
object CircadianActivity {

    private const val HOURS = 24
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86_400L

    /** Per-hour mean dynAccelG across [samples] as `CircadianBin`s, ascending by hour; hours with no sample
     *  are omitted. [tzOffsetSeconds] maps each ts to the user's local clock hour. Empty when no sample
     *  carries a dynAccelG. */
    fun hourlyBins(samples: List<GravitySample>, tzOffsetSeconds: Long): List<uniffi.whoop_ffi.CircadianBin> {
        val sum = DoubleArray(HOURS)
        val count = IntArray(HOURS)
        for (s in samples) {
            val g = s.dynAccelG ?: continue
            val localSec = s.ts + tzOffsetSeconds
            val hour = (((localSec % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY / SECONDS_PER_HOUR).toInt()
            sum[hour] += g
            count[hour]++
        }
        return (0 until HOURS)
            .filter { count[it] > 0 }
            .map { uniffi.whoop_ffi.CircadianBin(it.toDouble(), sum[it] / count[it]) }
    }
}

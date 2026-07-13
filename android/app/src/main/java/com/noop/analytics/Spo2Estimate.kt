package com.noop.analytics

import kotlin.math.ceil
import kotlin.math.floor

/*
 * Spo2Estimate.kt - an OPT-IN, WELLNESS-tier (uncalibrated, non-clinical) blood-oxygen estimate from the
 * raw dual-wavelength PPG the strap already banks (per-sample red / IR ADC over the sleep window). It is
 * the honest "instrumentation" tier: decode -> ratio-of-ratios -> a plausible % behind a default-off
 * Experimental toggle, HEAVILY labelled, never a calibrated / clinical claim.
 *
 * WHY THIS IS NOT Spo2ReTrace: that file dumps records so an offline pass can look for a strap-BANKED %.
 * This computes a % from the raw red/IR ourselves using the textbook ratio-of-ratios curve. It is
 * deliberately walled off from the default path: analyzeDay only calls it when the caller threads the
 * opt-in flag, and it returns null (never a fabricated number) whenever the pulsatile signal is absent -
 * e.g. a WHOOP 5.0/MG v18 night, which banks NO red/IR at all, so the Blood O2 card stays "No Data".
 *
 * The method: slide NON-OVERLAPPING windows over the ts-ordered samples. Per window per channel take the
 * DC (mean) and a robust pulsatile AC amplitude (p95 - p5, resistant to a single spike). The
 * ratio-of-ratios R = (AC_red/DC_red) / (AC_ir/DC_ir); SpO2 = curveA - curveB * R (the standard empirical
 * line), clamped to a plausible wellness band. The night value is the MEDIAN of the surviving per-window
 * SpO2 (robust to a few bad windows). A window is SKIPPED unless it has enough samples and a positive
 * DC + AC on both channels, so flat / sparse / absent signal contributes nothing.
 *
 * Pure + deterministic + dependency-free (no IO, no state, no em-dashes). Byte-identical to the Swift twin
 * Spo2Estimate.swift: same constants, same window/percentile/median math, same null discipline.
 */
object Spo2Estimate {

    /** Samples per non-overlapping window. The banked red/IR arrive at roughly 1 Hz, so this is about a
     *  30 s window - long enough for several pulse beats, short enough that many windows span a night. */
    const val WINDOW_SECONDS = 30

    /** Fewest samples a window must carry before it is trusted; a short trailing chunk is skipped so a
     *  handful of stray samples can never manufacture a per-window value. */
    const val MIN_SAMPLES_PER_WINDOW = 10

    /** Empirical ratio-of-ratios calibration line SpO2 = curveA - curveB * R. The textbook constants
     *  (110, 25) give ~100 % near R = 0.4 and fall through the healthy range as R climbs. WELLNESS-tier:
     *  this is a generic curve, NOT WHOOP's proprietary per-device calibration. */
    const val CURVE_A = 110.0
    const val CURVE_B = 25.0

    /** Plausible wellness clamp for the per-window SpO2 (never assert outside a believable band). */
    const val CLAMP_LOW = 70.0
    const val CLAMP_HIGH = 100.0

    /** Percentiles for the robust AC amplitude proxy (p95 - p5), resistant to a lone outlier sample. */
    const val AC_PERCENTILE_LOW = 0.05
    const val AC_PERCENTILE_HIGH = 0.95

    // --- Rolling readout (WHOOP-style) --------------------------------------------------------------
    // The per-night [compute] value is a single, noisy, UNCALIBRATED number. WHOOP never shows a raw
    // night: it shows a smoothed baseline that only appears after a short calibration period. [rollingReading]
    // reproduces that behaviour so the Blood O2 card stays plausible + directionally meaningful.

    /** Bootstrap gate: below this many nights the readout stays "Calibrating" (WHOOP's "one recovery
     *  moment" before a baseline is trusted). */
    const val MIN_NIGHTS = 3

    /** Trailing window whose median sets the soft-anchor baseline - about a month of nights. */
    const val WINDOW = 30

    /** Recent window whose median is the reported direction of travel (the last week of nights). */
    const val RECENT = 7

    /** Plausible healthy median SpO2 the 30-day baseline is softly anchored to, so an uncalibrated
     *  sensor's arbitrary DC offset is removed while the night-to-night SPREAD is preserved. */
    const val ANCHOR = 96.5

    /** Display clamp for the rolling readout (never assert outside a believable band). */
    const val ROLLING_CLAMP_LOW = 88.0
    const val ROLLING_CLAMP_HIGH = 100.0

    /**
     * Result of [rollingReading]: exactly one field is non-null. [pct] is the whole-percent smoothed
     * readout once calibrated; while still calibrating [pct] is null and [calibratingNights] carries how
     * many nights have accrued (of [MIN_NIGHTS]). Byte-identical to the Swift twin's `RollingReading`.
     */
    data class RollingReading(val pct: Double?, val calibratingNights: Int?)

    /**
     * WHOOP-style rolling Blood O2 readout from recent per-night [compute] estimates. [recentNightly] is
     * the non-null nightly spo2Pct estimates OLDEST -> NEWEST. Uses up to the last [WINDOW] of them.
     *
     *  - fewer than [MIN_NIGHTS] -> a CALIBRATING result carrying the count (pct null).
     *  - else: soft-anchor the window so its 30-day MEDIAN reads ~[ANCHOR] (offset = ANCHOR - baseline,
     *    preserving spread), add that offset to the [RECENT]-night median, clamp to the display band, and
     *    round to a whole percent. Deterministic + dependency-free; the same median as [compute].
     */
    fun rollingReading(recentNightly: List<Double>): RollingReading {
        val window = if (recentNightly.size > WINDOW) recentNightly.subList(recentNightly.size - WINDOW, recentNightly.size)
                     else recentNightly
        if (window.size < MIN_NIGHTS) return RollingReading(pct = null, calibratingNights = window.size)

        val baseline = median(window)
        val offset = ANCHOR - baseline
        val recentCount = minOf(RECENT, window.size)
        val recent = median(window.subList(window.size - recentCount, window.size))
        val raw = recent + offset
        val clamped = minOf(maxOf(raw, ROLLING_CLAMP_LOW), ROLLING_CLAMP_HIGH)
        // Round-half-up via floor(x + 0.5): byte-identical across Swift/Kotlin (avoids Kotlin's
        // round-half-to-even vs Swift's round-half-away-from-zero divergence). Values are always positive.
        val rounded = floor(clamped + 0.5)
        return RollingReading(pct = rounded, calibratingNights = null)
    }

    /**
     * Wellness-tier SpO2 (%) for the night from the sleep-window red/IR samples, or null when no window
     * survives (flat / too-sparse / absent signal - e.g. a 5.0/MG night with no red/IR). [red] and [ir]
     * are the per-sample ADC values in timestamp order; parallel arrays (element i of each is one sample).
     * NEVER fabricates: an absent pulsatile signal returns null, never a guessed number.
     */
    fun compute(red: List<Double>, ir: List<Double>): Double? {
        val n = minOf(red.size, ir.size)
        if (n == 0) return null

        val perWindow = ArrayList<Double>()
        var start = 0
        while (start < n) {
            val end = minOf(start + WINDOW_SECONDS, n)
            val count = end - start
            if (count < MIN_SAMPLES_PER_WINDOW) { start = end; continue }

            val redWin = red.subList(start, end)
            val irWin = ir.subList(start, end)
            start = end

            val dcRed = mean(redWin)
            val dcIr = mean(irWin)
            if (dcRed <= 0.0 || dcIr <= 0.0) continue

            val acRed = amplitude(redWin)
            val acIr = amplitude(irWin)
            if (acRed <= 0.0 || acIr <= 0.0) continue

            val ratioRed = acRed / dcRed
            val ratioIr = acIr / dcIr
            if (ratioIr <= 0.0) continue

            val r = ratioRed / ratioIr
            val spo2 = CURVE_A - CURVE_B * r
            perWindow.add(minOf(maxOf(spo2, CLAMP_LOW), CLAMP_HIGH))
        }

        if (perWindow.isEmpty()) return null
        return median(perWindow)
    }

    /** Arithmetic mean (the DC level). Caller guarantees non-empty. */
    private fun mean(xs: List<Double>): Double = xs.sum() / xs.size

    /** Robust pulsatile amplitude: the p95 - p5 spread of the window (a lone spike moves neither tail). */
    private fun amplitude(xs: List<Double>): Double {
        val sorted = xs.sorted()
        return percentile(sorted, AC_PERCENTILE_HIGH) - percentile(sorted, AC_PERCENTILE_LOW)
    }

    /** Linear-interpolated percentile over an ASCENDING-sorted list, [p] in [0, 1]. Interpolating between
     *  two IEEE-754 doubles is byte-identical across Swift and Kotlin, keeping the twins in lockstep. */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        val n = sorted.size
        if (n == 1) return sorted[0]
        val rank = p * (n - 1)
        val lo = floor(rank).toInt()
        val hi = ceil(rank).toInt()
        if (lo == hi) return sorted[lo]
        val frac = rank - lo
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac
    }

    /** Deterministic median: upper-middle on odd counts, the mean of the two middles on even counts.
     *  Byte-identical to the Swift twin. */
    private fun median(xs: List<Double>): Double {
        val sorted = xs.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}

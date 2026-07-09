package com.noop.bp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device cuffless blood-pressure **estimate** from the strap's 24 Hz optical PPG waveform — a
 * faithful port of the noop-cloud `bp.ts` model so the whole pipeline can run natively (offline, PPG
 * never leaves the phone). Extracts pulse-wave features and maps them to systolic/diastolic via a
 * per-user cuff calibration plus population-prior slopes.
 *
 * ⚠ UNVALIDATED WELLNESS ESTIMATE, not a medical measurement. It tracks deltas from the user's last
 * cuff calibration; it drifts and needs recalibration. Never a diagnosis. Kept numerically identical
 * to the cloud model so native and cloud agree for the same window + calibration.
 */
object BpEngine {
    data class PpgFeatures(
        val hr: Double,          // bpm, from the dominant pulse period
        val upstrokeMs: Double,  // mean foot→peak rise time (shorter ≈ stiffer arteries ≈ higher BP)
        val amplitude: Double,   // mean peak−foot (raw ADC)
        val widthMs: Double,     // mean pulse width at half amplitude
        val pulses: Int,         // clean pulses detected (quality signal)
    )

    data class Estimate(val systolic: Int, val diastolic: Int, val confidence: Double, val features: PpgFeatures)

    // ── population-prior slopes (documented, rough — honest constants, not clinical), match bp.ts ──
    private const val K_HR_SBP = 0.4    // mmHg per bpm above the calibration HR
    private const val K_UP_SBP = 0.08   // mmHg per ms the upstroke is SHORTER than calibration
    private const val K_HR_DBP = 0.25
    private const val K_UP_DBP = 0.04

    /**
     * Extract pulse-wave features from a raw PPG window. Detrends, finds pulse feet/peaks, measures the
     * rise time / amplitude / width. Returns null if the signal is too poor to trust.
     */
    fun extractFeatures(ppg: List<Int>, fs: Int): PpgFeatures? {
        val n = ppg.size
        if (n < fs * 3 || fs <= 0) return null

        // Linear detrend (remove DC + baseline wander).
        val mx = (n - 1) / 2.0
        val my = ppg.sumOf { it.toDouble() } / n
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) { sxy += (i - mx) * (ppg[i] - my); sxx += (i - mx) * (i - mx) }
        val slope = if (sxx != 0.0) sxy / sxx else 0.0
        val sig = DoubleArray(n) { i -> ppg[i] - (my + slope * (i - mx)) }

        // HR by normalised autocorrelation over 30–220 bpm (mirrors PpgHr's approach).
        val minLag = floor((fs * 60.0) / 220).toInt()
        val maxLag = ceil((fs * 60.0) / 30).toInt()
        var bestLag = 0
        var bestR = 0.0
        var energy = 0.0
        for (v in sig) energy += v * v
        if (energy == 0.0) energy = 1.0
        val hiLag = min(maxLag, n - 1)
        for (lag in minLag..hiLag) {
            var r = 0.0
            var i = 0
            while (i + lag < n) { r += sig[i] * sig[i + lag]; i++ }
            r /= energy
            if (r > bestR) { bestR = r; bestLag = lag }
        }
        if (bestLag == 0 || bestR < 0.3) return null // no clean pulse
        val hr = (60.0 * fs) / bestLag

        // Pulse feet = local minima roughly one period apart; measure foot→peak rise + amplitude + width.
        val period = bestLag
        val ups = ArrayList<Double>()
        val amps = ArrayList<Double>()
        val widths = ArrayList<Double>()
        var start = 0
        while (start + period < n) {
            // foot = min in [start, start+period*0.5]; peak = max after the foot within a period.
            var footI = start
            var footV = sig[start]
            var i = start
            while (i < start + period * 0.5 && i < n) { if (sig[i] < footV) { footV = sig[i]; footI = i }; i++ }
            var peakI = footI
            var peakV = sig[footI]
            i = footI
            while (i < footI + period && i < n) { if (sig[i] > peakV) { peakV = sig[i]; peakI = i }; i++ }
            if (peakI <= footI) { start += period; continue }
            val amp = peakV - footV
            if (amp <= 0) { start += period; continue }
            ups.add((peakI - footI).toDouble() / fs * 1000)
            amps.add(amp)
            // width at half amplitude around the peak.
            val half = footV + amp / 2
            var l = peakI
            while (l > footI && sig[l] > half) l--
            var rr = peakI
            while (rr < n - 1 && sig[rr] > half) rr++
            widths.add((rr - l).toDouble() / fs * 1000)
            start += period
        }
        if (ups.size < 2) return null
        fun mean(a: List<Double>) = a.sum() / a.size
        return PpgFeatures(hr, mean(ups), mean(amps), mean(widths), ups.size)
    }

    /** Estimate SBP/DBP from features, relative to the cuff calibration. */
    fun estimate(cal: BpStore.Calibration, f: PpgFeatures): Estimate {
        val dHr = f.hr - cal.hr
        val dUp = cal.upstrokeMs - f.upstrokeMs // positive when upstroke shortened (stiffer → higher BP)
        val systolic = (cal.systolic + K_HR_SBP * dHr + K_UP_SBP * dUp).roundToInt()
        val diastolic = (cal.diastolic + K_HR_DBP * dHr + K_UP_DBP * dUp).roundToInt()
        // Confidence from pulse count + how far we are from the calibration point (extrapolation penalty).
        val drift = min(1.0, abs(dHr) / 40 + abs(dUp) / 100)
        val confidence = max(0.1, min(0.9, (f.pulses / 8.0) * (1 - 0.5 * drift)))
        // Clamp to a sane physiological range so a bad window can't emit nonsense.
        fun clamp(v: Int, lo: Int, hi: Int) = max(lo, min(hi, v))
        return Estimate(
            clamp(systolic, 80, 200),
            clamp(diastolic, 40, 130),
            (confidence * 100).roundToInt() / 100.0,
            f,
        )
    }
}

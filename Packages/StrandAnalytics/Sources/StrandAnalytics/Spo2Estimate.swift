import Foundation

// Spo2Estimate.swift - an OPT-IN, WELLNESS-tier (uncalibrated, non-clinical) blood-oxygen estimate
// from the raw dual-wavelength PPG the strap already banks (per-sample red / IR ADC over the sleep
// window). It is the honest "instrumentation" tier: decode -> ratio-of-ratios -> a plausible % behind a
// default-off Experimental toggle, HEAVILY labelled, never a calibrated / clinical claim.
//
// WHY THIS IS NOT SpO2ReTrace: that file dumps records so an offline pass can look for a strap-BANKED %.
// This computes a % from the raw red/IR ourselves using the textbook ratio-of-ratios curve. It is
// deliberately walled off from the default path: analyzeDay only calls it when the caller threads the
// opt-in flag, and it returns nil (never a fabricated number) whenever the pulsatile signal is absent -
// e.g. a WHOOP 5.0/MG v18 night, which banks NO red/IR at all, so the Blood O2 card stays "No Data".
//
// The method: slide NON-OVERLAPPING windows over the ts-ordered samples. Per window per channel take the
// DC (mean) and a robust pulsatile AC amplitude (p95 - p5, resistant to a single spike). The
// ratio-of-ratios R = (AC_red/DC_red) / (AC_ir/DC_ir); SpO2 = curveA - curveB * R (the standard empirical
// line), clamped to a plausible wellness band. The night value is the MEDIAN of the surviving per-window
// SpO2 (robust to a few bad windows). A window is SKIPPED unless it has enough samples and a positive
// DC + AC on both channels, so flat / sparse / absent signal contributes nothing.
//
// Pure + deterministic + dependency-free (no I/O, no state, no em-dashes). Byte-identical to the Kotlin
// twin Spo2Estimate.kt: same constants, same window/percentile/median math, same nil discipline.
public enum Spo2Estimate {

    /// Samples per non-overlapping window. The banked red/IR arrive at roughly 1 Hz, so this is about a
    /// 30 s window - long enough for several pulse beats, short enough that many windows span a night.
    public static let windowSeconds = 30

    /// Fewest samples a window must carry before it is trusted; a short trailing chunk is skipped so a
    /// handful of stray samples can never manufacture a per-window value.
    public static let minSamplesPerWindow = 10

    /// Empirical ratio-of-ratios calibration line SpO2 = curveA - curveB * R. The textbook constants
    /// (110, 25) give ~100 % near R = 0.4 and fall through the healthy range as R climbs. WELLNESS-tier:
    /// this is a generic curve, NOT WHOOP's proprietary per-device calibration.
    public static let curveA = 110.0
    public static let curveB = 25.0

    /// Plausible wellness clamp for the per-window SpO2 (never assert outside a believable band).
    public static let clampLow = 70.0
    public static let clampHigh = 100.0

    /// Percentiles for the robust AC amplitude proxy (p95 - p5), resistant to a lone outlier sample.
    public static let acPercentileLow = 0.05
    public static let acPercentileHigh = 0.95

    /// Wellness-tier SpO2 (%) for the night from the sleep-window red/IR samples, or nil when no window
    /// survives (flat / too-sparse / absent signal - e.g. a 5.0/MG night with no red/IR). `red` and `ir`
    /// are the per-sample ADC values in timestamp order; parallel arrays (element i of each is one
    /// sample). NEVER fabricates: an absent pulsatile signal returns nil, never a guessed number.
    public static func compute(red: [Double], ir: [Double]) -> Double? {
        let n = min(red.count, ir.count)
        if n == 0 { return nil }

        var perWindow: [Double] = []
        var start = 0
        while start < n {
            let end = min(start + windowSeconds, n)
            let count = end - start
            if count < minSamplesPerWindow { start = end; continue }

            let redWin = Array(red[start..<end])
            let irWin = Array(ir[start..<end])
            start = end

            let dcRed = mean(redWin)
            let dcIr = mean(irWin)
            if dcRed <= 0 || dcIr <= 0 { continue }

            let acRed = amplitude(redWin)
            let acIr = amplitude(irWin)
            if acRed <= 0 || acIr <= 0 { continue }

            let ratioRed = acRed / dcRed
            let ratioIr = acIr / dcIr
            if ratioIr <= 0 { continue }

            let r = ratioRed / ratioIr
            let spo2 = curveA - curveB * r
            perWindow.append(min(max(spo2, clampLow), clampHigh))
        }

        if perWindow.isEmpty { return nil }
        return median(perWindow)
    }

    /// Arithmetic mean (the DC level). Caller guarantees non-empty.
    private static func mean(_ xs: [Double]) -> Double {
        xs.reduce(0, +) / Double(xs.count)
    }

    /// Robust pulsatile amplitude: the p95 - p5 spread of the window (a lone spike moves neither tail).
    private static func amplitude(_ xs: [Double]) -> Double {
        let sorted = xs.sorted()
        return percentile(sorted, acPercentileHigh) - percentile(sorted, acPercentileLow)
    }

    /// Linear-interpolated percentile over an ASCENDING-sorted list, `p` in [0, 1]. Interpolating between
    /// two IEEE-754 doubles is byte-identical across Swift and Kotlin, keeping the twins in lockstep.
    private static func percentile(_ sorted: [Double], _ p: Double) -> Double {
        let n = sorted.count
        if n == 1 { return sorted[0] }
        let rank = p * Double(n - 1)
        let lo = Int(rank.rounded(.down))
        let hi = Int(rank.rounded(.up))
        if lo == hi { return sorted[lo] }
        let frac = rank - Double(lo)
        return sorted[lo] * (1.0 - frac) + sorted[hi] * frac
    }

    /// Deterministic median: upper-middle on odd counts, the mean of the two middles on even counts.
    /// Byte-identical to the Kotlin twin.
    private static func median(_ xs: [Double]) -> Double {
        let sorted = xs.sorted()
        let n = sorted.count
        if n % 2 == 1 { return sorted[n / 2] }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}

import Foundation
import WhoopProtocol

// RecoveryScorer.swift — resting HR during sleep + a transparent 0–100 recovery score.
//
// Ported from server/ingest/app/analysis/recovery.py.
//
// recovery() is a z-score + logistic composite. It is APPROXIMATE — not
// WHOOP-identical (WHOOP's model is proprietary). It is a transparent,
// HRV-dominant, baseline-normalized proxy.
//
// Weighting (documented, grounded, explainable; this is "Charge" in the UI):
//   higher HRV vs baseline       → higher recovery  (W_HRV       = 0.55, dominant)
//   lower resting HR vs baseline → higher recovery  (W_RHR       = 0.20)
//   higher rest quality (sleep)  → higher recovery  (W_SLEEP     = 0.15)
//   lower resp vs baseline       → higher recovery  (W_RESP      = 0.05)
//   skin-temp deviation from 0   → lower recovery   (W_SKIN_TEMP = 0.05)
//
// The Charge/Effort/Rest redesign folds skin temperature in (illness/overreach
// signal): HRV dropped 0.60 → 0.55 to make room for W_SKIN_TEMP = 0.05. The
// skin-temp term is a SYMMETRIC penalty on the ±°C deviation (−|dev|/scale), so any
// drift away from the personal baseline — hot or cold — lowers Charge. It is added
// ONLY when a skin-temp deviation is supplied; when nil the term drops and the
// weights renormalize, leaving the no-skin-temp score IDENTICAL to before.
//
// Each metric is standardized to a robust z-score against the personal baseline
// (mean + EWMA-abs-dev spread). Missing terms are dropped and the weights
// renormalized. The composite z is squashed through a logistic anchored so that
// Z = 0 → ~58% (WHOOP's published population-average recovery).
//
// Cold-start: if the HRV baseline (dominant driver) is not yet usable
// (< MIN_NIGHTS_SEED valid nights), recovery() returns nil. Callers may use
// RECOVERY_POPULATION_MEAN (58.0) as a fallback but should flag it.

public enum RecoveryScorer {

    // MARK: - Constants (recovery.py)

    public static let wHRV: Double = 0.55
    public static let wRHR: Double = 0.20
    public static let wResp: Double = 0.05
    public static let wSleep: Double = 0.15
    /// Skin-temperature deviation weight (Charge/Effort/Rest redesign). HRV gave up
    /// 0.05 (0.60 → 0.55) to fund it.
    public static let wSkinTemp: Double = 0.05

    /// Skin-temp penalty scale (°C): a 1 °C deviation from baseline costs ≈1 z-unit of
    /// penalty before weighting. Symmetric — sign of the deviation does not matter.
    public static let skinTempScaleC: Double = 1.0

    /// Logistic spread: ±2 z-units ≈ full Red–Green band (15%–95%).
    public static let logisticK: Double = 1.6
    /// Logistic offset so Z=0 → 58%.
    public static let logisticZ0: Double = -0.20
    /// WHOOP-published population-average recovery (%). Cold-start fallback.
    public static let populationMean: Double = 58.0

    /// Recovery band thresholds (WHOOP color scheme).
    public static let bandRedMax: Double = 34.0
    public static let bandYellowMax: Double = 67.0

    /// Sleep-performance center ("good night" at ~85% efficiency).
    public static let sleepPerfCenter: Double = 0.85
    /// Sleep-performance scale (±2 z spans the normal range).
    public static let sleepPerfScale: Double = 0.12

    /// Rolling-mean HR window (seconds) for the resting-HR estimate.
    public static let restingHRWindowS: Int = 5 * 60

    // MARK: - Resting HR

    /// Lowest sustained HR during the in-bed window (bpm, rounded), or nil.
    ///
    /// "Sustained" = the minimum of 5-minute non-overlapping bin means of the HR
    /// samples whose ts ∈ [start, end]. Rejects single-beat dips while capturing
    /// the night's true floor. Returns nil when there are no HR samples in window.
    public static func restingHR(_ hr: [HRSample], start: Int, end: Int) -> Int? {
        let seg = hr.filter { $0.ts >= start && $0.ts <= end }
        guard !seg.isEmpty else { return nil }

        var means: [Double] = []
        var t = start
        while t < end {
            let win = seg.filter { $0.ts >= t && $0.ts < t + restingHRWindowS }
            if !win.isEmpty {
                means.append(Double(win.reduce(0) { $0 + $1.bpm }) / Double(win.count))
            }
            t += restingHRWindowS
        }
        let floor: Double
        if let m = means.min() {
            floor = m
        } else {
            floor = Double(seg.reduce(0) { $0 + $1.bpm }) / Double(seg.count)
        }
        return Int(floor.rounded())
    }

    // MARK: - Recovery band

    /// WHOOP-style color band for a recovery score [0, 100].
    public static func band(_ score: Double) -> String {
        if score < bandRedMax { return "red" }
        if score < bandYellowMax { return "yellow" }
        return "green"
    }

    // MARK: - Cold-start calibration progress

    /// Nights carrying a usable nightly HRV — the signal that seeds the recovery baseline. While
    /// recovery is still nil and this count is in [1, seed), it is the honest
    /// "Calibrating — N of <seed> nights" progress the dashboard shows in place of a bare empty
    /// state; nil once recovery exists or no night has data yet. Matches the baseline's validity
    /// predicate, not just non-nil: `Baselines.update` only advances the recovery seed (nValid)
    /// for nights whose value is within the metric config bounds, so an implausible out-of-range
    /// night must NOT be counted here either — else the displayed N could over-state nValid.
    /// Never claims "calibrating" at/above the seed gate (a nil recovery there is some other gap).
    /// Mirrors Android TodayScreen.recoveryCalibrationNights (RecoveryCalibrationTest is the oracle).
    public static func calibrationNights(nightlyHrv: [Double?],
                                         hasRecovery: Bool,
                                         seed: Int = Baselines.minNightsSeed,
                                         cfg: MetricCfg = Baselines.hrvCfg) -> Int? {
        guard !hasRecovery else { return nil }
        let n = nightlyHrv.compactMap { $0 }.filter { $0 >= cfg.minVal && $0 <= cfg.maxVal }.count
        // Include 0: a brand-new user (no banked nights yet) should read "Calibrating — 0 of N" on the
        // Charge ring, not a bare "No data" that looks broken (#335). Past days are gated to nil by the
        // caller; >= seed (recovery should exist) still returns nil.
        return (0..<seed).contains(n) ? n : nil
    }

    // MARK: - Recovery score

    /// A baseline driver: mean + spread (internal abs-dev units, as in BaselineState).
    public struct DriverBaseline: Equatable, Sendable {
        public let mean: Double
        public let spread: Double
        public init(mean: Double, spread: Double) {
            self.mean = mean; self.spread = spread
        }
        public init(_ state: BaselineState) {
            self.mean = state.baseline; self.spread = state.spread
        }
    }

    /// Robust z-score using EWMA spread: (value − mean) / (1.253 × spread).
    static func zScore(_ value: Double, mean: Double, spread: Double) -> Double {
        let sigma = max(1.253 * spread, 1e-9)
        return (value - mean) / sigma
    }

    /// Z-score + logistic recovery score in [0, 100]. APPROXIMATE.
    ///
    /// Returns nil when the HRV baseline (dominant driver) is not yet usable, or
    /// no valid driver is available at all.
    ///
    /// - Parameters:
    ///   - hrv: tonight's HRV (RMSSD, ms).
    ///   - rhr: tonight's resting HR (bpm).
    ///   - resp: tonight's respiration (raw or calibrated — z is scale-invariant);
    ///           nil drops the term.
    ///   - hrvBaseline: HRV baseline (required for a score).
    ///   - rhrBaseline: resting-HR baseline; nil drops the RHR term.
    ///   - respBaseline: respiration baseline; nil drops the resp term.
    ///   - sleepPerf: Rest quality (Rest composite ÷100, 0..1; was raw efficiency);
    ///     nil drops the term.
    ///   - skinTempDev: skin-temperature deviation from the personal baseline (±°C,
    ///     `DailyMetric.skinTempDevC`). Entered as a SYMMETRIC penalty −|dev|/scale,
    ///     weight wSkinTemp. nil drops the term and the weights renormalize so the
    ///     no-skin-temp score is identical to before.
    ///   - hrvBaselineUsable: whether the HRV baseline has enough nights
    ///     (BaselineState.usable). When false, returns nil (cold-start).
    public static func recovery(hrv: Double,
                                rhr: Double,
                                resp: Double?,
                                hrvBaseline: DriverBaseline?,
                                rhrBaseline: DriverBaseline?,
                                respBaseline: DriverBaseline?,
                                sleepPerf: Double?,
                                skinTempDev: Double? = nil,
                                hrvBaselineUsable: Bool = true) -> Double? {
        // Cold-start gate: HRV is the dominant driver; if its baseline isn't
        // usable, refuse to score (more honest than a fabricated value).
        if !hrvBaselineUsable { return nil }

        var terms: [(z: Double, w: Double)] = []

        // HRV term: higher is better.
        if let b = hrvBaseline {
            terms.append((zScore(hrv, mean: b.mean, spread: b.spread), wHRV))
        }
        // RHR term: lower is better → (μ − x) / σ.
        if let b = rhrBaseline {
            terms.append((zScore(b.mean, mean: rhr, spread: b.spread), wRHR))
        }
        // Resp term: lower is better, optional.
        if let r = resp, let b = respBaseline {
            terms.append((zScore(b.mean, mean: r, spread: b.spread), wResp))
        }
        // Sleep-performance / Rest-quality term: no baseline needed; centered at SLEEP_PERF_CENTER.
        if let sp = sleepPerf {
            terms.append(((sp - sleepPerfCenter) / sleepPerfScale, wSleep))
        }
        // Skin-temp term: SYMMETRIC penalty on |deviation| (illness/overreach). Any
        // drift from the personal baseline lowers Charge; added only when supplied.
        if let dev = skinTempDev {
            terms.append((-abs(dev) / skinTempScaleC, wSkinTemp))
        }

        guard !terms.isEmpty else { return nil }
        let totalWeight = terms.reduce(0) { $0 + $1.w }
        guard totalWeight > 0 else { return nil }

        let z = terms.reduce(0) { $0 + $1.z * $1.w } / totalWeight
        let score = 100.0 / (1.0 + exp(-logisticK * (z - logisticZ0)))
        return max(0.0, min(100.0, score))
    }

    /// Convenience overload taking BaselineState directly. Enforces the cold-start
    /// gate using `hrvBaseline.usable`.
    public static func recovery(hrv: Double,
                                rhr: Double,
                                resp: Double?,
                                hrvBaseline: BaselineState,
                                rhrBaseline: BaselineState?,
                                respBaseline: BaselineState?,
                                sleepPerf: Double?,
                                skinTempDev: Double? = nil) -> Double? {
        recovery(hrv: hrv,
                 rhr: rhr,
                 resp: resp,
                 hrvBaseline: DriverBaseline(hrvBaseline),
                 rhrBaseline: rhrBaseline.map(DriverBaseline.init),
                 respBaseline: respBaseline.map(DriverBaseline.init),
                 sleepPerf: sleepPerf,
                 skinTempDev: skinTempDev,
                 hrvBaselineUsable: hrvBaseline.usable)
    }
}

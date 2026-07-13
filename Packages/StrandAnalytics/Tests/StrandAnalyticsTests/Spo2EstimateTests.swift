import XCTest
@testable import StrandAnalytics

/// Tests `Spo2Estimate` — the OPT-IN wellness-tier (uncalibrated, non-clinical) SpO₂ estimate from raw
/// red/IR PPG (ratio-of-ratios). Twin of the Android `Spo2EstimateTest`, value-for-value.
///
/// Per the derived-biosignal standard: a synthetic method must recover MULTIPLE injected values, not one
/// coincidental match. Each window is engineered so the ratio-of-ratios R yields a KNOWN SpO₂; the tests
/// recover ~97 / ~94 / ~90 % (and the median of several), plus the honest nil on a flat / absent signal.
final class Spo2EstimateTests: XCTestCase {

    /// Build ONE 30-sample window (parallel red/ir) engineered so the estimate recovers `targetSpo2`:
    /// both channels are half at DC−AC/2, half at DC+AC/2, so mean = DC and p95−p5 = AC exactly. The IR
    /// channel is fixed (DC 1000, AC 20 → ratio 0.02); the red AC is chosen so
    /// R = (AC_red/DC_red)/(AC_ir/DC_ir) = (110 − targetSpo2)/25, i.e. SpO₂ = 110 − 25·R = targetSpo2.
    private func window(_ targetSpo2: Double, samples: Int = 30) -> (red: [Double], ir: [Double]) {
        let r = (Spo2Estimate.curveA - targetSpo2) / Spo2Estimate.curveB
        let dcIr = 1000.0, acIr = 20.0
        let dcRed = 1000.0
        let acRed = r * (acIr / dcIr) * dcRed
        let half = samples / 2
        var red: [Double] = [], ir: [Double] = []
        for _ in 0..<half { red.append(dcRed - acRed / 2.0); ir.append(dcIr - acIr / 2.0) }
        for _ in 0..<(samples - half) { red.append(dcRed + acRed / 2.0); ir.append(dcIr + acIr / 2.0) }
        return (red, ir)
    }

    func testRecoversMultipleInjectedValues() {
        for target in [97.0, 94.0, 90.0] {
            let w = window(target)
            let got = try! XCTUnwrap(Spo2Estimate.compute(red: w.red, ir: w.ir))
            XCTAssertEqual(got, target, accuracy: 1.0)
        }
    }

    func testNightValueIsMedianOfPerWindowEstimatesOddCount() {
        // Three back-to-back windows engineered to 97 / 94 / 90 → median 94.
        let w0 = window(97.0), w1 = window(94.0), w2 = window(90.0)
        let got = try! XCTUnwrap(Spo2Estimate.compute(red: w0.red + w1.red + w2.red,
                                                      ir: w0.ir + w1.ir + w2.ir))
        XCTAssertEqual(got, 94.0, accuracy: 1.0)
    }

    func testNightValueIsMedianOfPerWindowEstimatesEvenCount() {
        // Two windows engineered to 96 / 92 → median = mean of the two middles = 94.
        let w0 = window(96.0), w1 = window(92.0)
        let got = try! XCTUnwrap(Spo2Estimate.compute(red: w0.red + w1.red, ir: w0.ir + w1.ir))
        XCTAssertEqual(got, 94.0, accuracy: 1.0)
    }

    func testClampsToWellnessBand() {
        // A window whose raw curve would give 105 % clamps to 100; one that would give 60 % clamps to 70.
        let hi = window(105.0), lo = window(60.0)
        XCTAssertEqual(try! XCTUnwrap(Spo2Estimate.compute(red: hi.red, ir: hi.ir)), 100.0, accuracy: 1e-6)
        XCTAssertEqual(try! XCTUnwrap(Spo2Estimate.compute(red: lo.red, ir: lo.ir)), 70.0, accuracy: 1e-6)
    }

    func testFlatSignalReturnsNil() {
        // No pulsatile amplitude (AC = 0) on either channel → every window is skipped → nil (never faked).
        let flat = Array(repeating: 1000.0, count: 30)
        XCTAssertNil(Spo2Estimate.compute(red: flat, ir: flat))
    }

    func testEmptyInputReturnsNil() {
        // The WHOOP 5.0/MG v18 case: no red/IR banked at all → nil → the Blood O₂ card stays No Data.
        XCTAssertNil(Spo2Estimate.compute(red: [], ir: []))
    }

    func testTooFewSamplesForAWindowReturnsNil() {
        // Below minSamplesPerWindow the only (short) window is skipped → nil.
        let w = window(97.0, samples: Spo2Estimate.minSamplesPerWindow - 1)
        XCTAssertNil(Spo2Estimate.compute(red: w.red, ir: w.ir))
    }
}

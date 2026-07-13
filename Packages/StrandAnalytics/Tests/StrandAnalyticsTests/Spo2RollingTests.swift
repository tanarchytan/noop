import XCTest
@testable import StrandAnalytics

/// Tests `Spo2Estimate.rollingReading` — the WHOOP-style Blood O₂ readout: a 30-day-smoothed,
/// soft-anchored guesstimate that only appears after a short calibration period. Twin of the Android
/// `Spo2RollingTest`, value-for-value.
///
/// Per the derived-biosignal standard: multi-value coverage, not one coincidental point. We check that a
/// stable healthy series reads ~96–97, that a recent dip reads clearly below the anchor with relative
/// order preserved, that the soft-anchor removes an uncalibrated DC offset while preserving spread, and
/// that the calibration gate returns the right N (and nil pct) below `minNights`.
final class Spo2RollingTests: XCTestCase {

    private func pctOf(_ series: [Double]) -> Double {
        Spo2Estimate.rollingReading(recentNightly: series).pct!
    }

    func testStableHealthySeriesReadsAround96to97() {
        // A tight, stable series around 95–97 → the smoothed readout sits in the healthy 96–97 band.
        let series = (0..<10).flatMap { _ in [95.0, 96.0, 97.0] } // 30 nights
        let r = Spo2Estimate.rollingReading(recentNightly: series)
        XCTAssertNil(r.calibratingNights, "calibrated (>= minNights)")
        XCTAssertTrue((96.0...97.0).contains(r.pct!), "pct \(r.pct!) in 96...97")
    }

    func testRecentNightsBelowBaselineReadBelowAnchorOrderPreserved() {
        // 23 nights at 97 then a recent week at 92: recent < 30-day baseline → pct clearly < anchor.
        let dipping = Array(repeating: 97.0, count: 23) + Array(repeating: 92.0, count: 7)
        let steady = Array(repeating: 97.0, count: 30)
        let dipPct = pctOf(dipping)
        let steadyPct = pctOf(steady)
        XCTAssertLessThan(dipPct, Spo2Estimate.anchor, "dip pct \(dipPct) < anchor \(Spo2Estimate.anchor)")
        // Relative order preserved: a recent dip reads lower than a flat baseline of the same nights.
        XCTAssertLessThan(dipPct, steadyPct, "dip \(dipPct) < steady \(steadyPct)")
    }

    func testSoftAnchorRemovesUncalibratedDcOffsetPreservesSpread() {
        // An uncalibrated sensor sitting ~11 points low (all ~85): the soft-anchor shifts the 30-day
        // median to ~anchor, so a flat-low series reads plausible/healthy…
        let flatLow = Array(repeating: 85.0, count: 30)
        XCTAssertTrue((96.0...97.0).contains(pctOf(flatLow)), "flat-low anchors near anchor")
        // …while a genuine recent dip below that same baseline still reads lower (spread preserved).
        let dipLow = Array(repeating: 85.0, count: 23) + Array(repeating: 80.0, count: 7)
        XCTAssertLessThan(pctOf(dipLow), pctOf(flatLow), "recent dip reads lower than the flat baseline")
    }

    func testExactlyMinNightsIsCalibratedNotGated() {
        // Boundary: exactly minNights produces a value (not a calibrating result).
        let series = Array(repeating: 96.0, count: Spo2Estimate.minNights)
        let r = Spo2Estimate.rollingReading(recentNightly: series)
        XCTAssertNil(r.calibratingNights)
        XCTAssertEqual(r.pct!, 96.0, accuracy: 1.0)
    }

    func testBelowMinNightsReturnsCalibratingWithCount() {
        // 1 and 2 nights → calibrating, carrying the accrued count; pct nil (never a fabricated readout).
        let one = Spo2Estimate.rollingReading(recentNightly: [96.0])
        XCTAssertNil(one.pct)
        XCTAssertEqual(one.calibratingNights, 1)

        let two = Spo2Estimate.rollingReading(recentNightly: [96.0, 95.0])
        XCTAssertNil(two.pct)
        XCTAssertEqual(two.calibratingNights, 2)
    }

    func testEmptySeriesReturnsCalibratingZero() {
        // No estimates yet → calibrating with N = 0, pct nil (the UI renders this as No Data).
        let r = Spo2Estimate.rollingReading(recentNightly: [])
        XCTAssertNil(r.pct)
        XCTAssertEqual(r.calibratingNights, 0)
    }

    func testOnlyLastWindowNightsAreUsed() {
        // More than window nights: only the last window feed the reading. A long-ago block of lows is
        // ignored once window fresh healthy nights have accrued.
        let oldLows = Array(repeating: 80.0, count: 40)
        let recentHealthy = Array(repeating: 96.0, count: Spo2Estimate.window)
        let r = Spo2Estimate.rollingReading(recentNightly: oldLows + recentHealthy)
        XCTAssertNil(r.calibratingNights)
        XCTAssertEqual(r.pct!, 96.0, accuracy: 1.0)
    }
}

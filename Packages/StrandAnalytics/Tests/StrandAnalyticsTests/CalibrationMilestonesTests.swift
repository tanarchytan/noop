import XCTest
@testable import StrandAnalytics

/// Pins the pure calibration-milestone timeline that drives the Today countdown cards. The targets are a
/// PRESENTATION overlay (they never touch `Baselines`); these tests lock the ordering, the exactly-one
/// ACTIVE invariant, the done/locked partition, the absolute-fraction contract the card text relies on,
/// and the retire-at-final gate. Mirrors the Kotlin CalibrationMilestonesTest.
final class CalibrationMilestonesTests: XCTestCase {

    func testTimelineIsTheWhoopFamiliarSetInOrder() {
        XCTAssertEqual(CalibrationMilestones.all.map(\.nights), [4, 7, 14, 30])
        // The soonest/last targets stay pinned to the honest baseline gates + WHOOP's day-30 full baseline.
        XCTAssertEqual(CalibrationMilestones.all.first?.nights, Baselines.minNightsSeed)
        XCTAssertEqual(CalibrationMilestones.all[2].nights, Baselines.minNightsTrust)
        XCTAssertEqual(CalibrationMilestones.finalNights, 30)
    }

    func testBrandNewUserFirstActiveRestLockedNoneDone() {
        let p = CalibrationMilestones.progress(nightsBanked: 0)
        XCTAssertEqual(p[0].state, .active)
        XCTAssertTrue(p.dropFirst().allSatisfy { $0.state == .locked })
        XCTAssertFalse(p.contains { $0.state == .done })
        XCTAssertEqual(p[0].remaining, 4)
        XCTAssertEqual(p[0].fraction, 0.0, accuracy: 1e-9)
    }

    func testExactlyOneActiveUntilFullyCalibrated() {
        for n in 0..<30 {
            let active = CalibrationMilestones.progress(nightsBanked: n).filter { $0.state == .active }.count
            XCTAssertEqual(active, 1, "banked=\(n) should have exactly one live countdown")
        }
    }

    func testMidCalibrationPartitionsAndCountsDown() {
        // 9 nights banked: 4 & 7 DONE, 14 the live countdown, 30 locked (matches the card mockup).
        let p = CalibrationMilestones.progress(nightsBanked: 9)
        XCTAssertEqual(p[0].state, .done)   // 4
        XCTAssertEqual(p[1].state, .done)   // 7
        XCTAssertEqual(p[2].state, .active) // 14
        XCTAssertEqual(p[3].state, .locked) // 30
        XCTAssertEqual(p[2].remaining, 5)   // "5 nights to go"
        XCTAssertEqual(p[3].remaining, 21)  // "21 nights to go"
        // Absolute fill so the "9/14" / "9/30" labels agree with the bars.
        XCTAssertEqual(p[2].fraction, 9.0 / 14.0, accuracy: 1e-9)
        XCTAssertEqual(p[3].fraction, 9.0 / 30.0, accuracy: 1e-9)
        XCTAssertEqual(p[0].fraction, 1.0, accuracy: 1e-9)
    }

    func testOnTheDotCountsAsDone() {
        // Reaching the target exactly (>=) flips it DONE with zero remaining, not a lingering "1 to go".
        let p = CalibrationMilestones.progress(nightsBanked: 14)
        XCTAssertEqual(p[2].state, .done)
        XCTAssertEqual(p[2].remaining, 0)
        XCTAssertEqual(p[3].state, .active) // 30 is now the live one
    }

    func testFullyCalibratedRetiresTheCard() {
        XCTAssertTrue(CalibrationMilestones.isCalibrating(nightsBanked: 0))
        XCTAssertTrue(CalibrationMilestones.isCalibrating(nightsBanked: 29))
        XCTAssertFalse(CalibrationMilestones.isCalibrating(nightsBanked: 30))
        XCTAssertFalse(CalibrationMilestones.isCalibrating(nightsBanked: 45))
        let p = CalibrationMilestones.progress(nightsBanked: 30)
        XCTAssertTrue(p.allSatisfy { $0.state == .done })
    }

    func testNegativeCountClampsToZero() {
        // Defensive: a bad caller count never produces a negative fraction/remaining.
        let p = CalibrationMilestones.progress(nightsBanked: -3)
        XCTAssertEqual(p[0].fraction, 0.0, accuracy: 1e-9)
        XCTAssertEqual(p[0].remaining, 4)
        XCTAssertEqual(p[0].state, .active)
    }

    /// The banked-night predicate the cards feed from excludes out-of-range nights (parity with Android's
    /// `bankedCalibrationNights`), so a wild reading can't inflate the countdown.
    func testBankedNightsMatchesBaselineValidityPredicate() {
        let cfg = Baselines.hrvCfg
        let good = (cfg.minVal + cfg.maxVal) / 2.0
        let nightly: [Double?] = [good, nil, cfg.maxVal + 1000, good, good]
        XCTAssertEqual(RecoveryScorer.bankedNights(nightlyHrv: nightly, cfg: cfg), 3)
    }
}

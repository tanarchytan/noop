// Calibration milestones — the WHOOP-familiar countdown timeline surfaced on Today (gamification).
//
// PRESENTATION LAYER ONLY. These targets drive the milestone COUNTDOWN CARDS; they do NOT change the
// baseline math in `Baselines`. NOOP keeps its own honest gates (seed `Baselines.minNightsSeed` = 4,
// trust `Baselines.minNightsTrust` = 14, with early-adapt) — a milestone reaching "done" here is a UI
// badge, not a claim the analytics changed. The night count fed in is the SAME valid-HRV-night tally the
// recovery seed uses (`RecoveryScorer.bankedNights`), so a progress bar can never over-state what the
// baseline has actually banked.
//
// The targets mirror WHOOP's published Calibration Timeline (Day 4 Recovery, Day 7 Sleep, Day 30 full
// baseline) plus NOOP's own 14-night Trusted gate, so a WHOOP user sees the timeline they expect while
// NOOP's better statistics run underneath. Mirrors the Kotlin `CalibrationMilestones` byte-for-byte.

public enum CalibrationMilestones {

    /// The 5.0/MG "first week" sleep-coaching milestone (WHOOP Day 7). Not a baseline gate — a card target.
    public static let sleepBaselineNights: Int = 7

    /// WHOOP's full-baseline / rolling-30-day milestone (Day 30). Not a baseline gate — a card target.
    public static let fullBaselineNights: Int = 30

    /// A single calibration checkpoint. `nights` is the banked valid-night count at which it unlocks.
    public struct Milestone: Equatable, Sendable {
        /// Stable id (persisted / cross-platform / analytics-safe). Never renumber.
        public let id: String
        public let title: String
        public let nights: Int
        /// One-line "what this unlocks", shown on the active card.
        public let unlocks: String
    }

    /// The ordered timeline, soonest first. Targets are pinned to the honest gates where they coincide.
    public static let all: [Milestone] = [
        Milestone(id: "firstRecovery", title: "First Recovery",
                  nights: Baselines.minNightsSeed, // 4 — matches WHOOP Day 4
                  unlocks: "Charge, Effort and Rest become personal to you."),
        Milestone(id: "sleepBaseline", title: "Sleep baseline",
                  nights: sleepBaselineNights, // 7 — WHOOP "after your first week"
                  unlocks: "Your sleep need and coaching tune to your own nights."),
        Milestone(id: "trustedBaseline", title: "Trusted baseline",
                  nights: Baselines.minNightsTrust, // 14 — NOOP's full-confidence gate
                  unlocks: "Full-confidence baselines — the calibrating tag drops."),
        Milestone(id: "fullBaseline", title: "30-day baseline",
                  nights: fullBaselineNights, // 30 — WHOOP full baseline / rolling window
                  unlocks: "Your complete rolling 30-day baseline is set."),
    ]

    /// The furthest target. Banked ≥ this ⇒ calibration is fully complete and the card retires.
    public static var finalNights: Int { all.map(\.nights).max() ?? 0 }

    /// DONE = already reached; ACTIVE = the live countdown (first unreached); LOCKED = still ahead.
    public enum State: String, Sendable { case done, active, locked }

    /// A milestone paired with its computed progress against a banked-night count.
    public struct Progress: Equatable, Sendable {
        public let milestone: Milestone
        public let state: State
        /// Nights still needed (0 once done).
        public let remaining: Int
        /// 0…1 absolute fill toward this milestone's target (1.0 once done).
        public let fraction: Double
    }

    /// Resolve every milestone's `Progress` for a user who has banked `nightsBanked` valid nights. The
    /// FIRST not-yet-reached milestone is `.active` (the live countdown); earlier ones are `.done`, later
    /// ones `.locked`. Fraction is absolute (banked ÷ target) so the bar text ("9/14 nights") and the fill
    /// agree. Pure + unit-tested; mirrors the Kotlin twin.
    public static func progress(nightsBanked: Int) -> [Progress] {
        let n = max(0, nightsBanked)
        var activeAssigned = false
        return all.map { m in
            let done = n >= m.nights
            let state: State
            if done {
                state = .done
            } else if !activeAssigned {
                activeAssigned = true
                state = .active
            } else {
                state = .locked
            }
            return Progress(
                milestone: m,
                state: state,
                remaining: max(0, m.nights - n),
                fraction: done ? 1.0 : min(max(Double(n) / Double(m.nights), 0), 1)
            )
        }
    }

    /// True while at least one milestone is still unreached (banked < `finalNights`) — the card should show.
    public static func isCalibrating(nightsBanked: Int) -> Bool { nightsBanked < finalNights }
}

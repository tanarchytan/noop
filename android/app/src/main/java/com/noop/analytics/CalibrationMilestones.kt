package com.noop.analytics

/**
 * Calibration milestones — the WHOOP-familiar countdown timeline surfaced on Today (gamification).
 *
 * PRESENTATION LAYER ONLY. These targets drive the milestone COUNTDOWN CARDS; they do NOT change the
 * baseline math in [Baselines]. noop keeps its own honest gates (seed [Baselines.minNightsSeed] = 4,
 * trust [Baselines.minNightsTrust] = 14, with early-adapt) — a milestone reaching "done" here is a UI
 * badge, not a claim the analytics changed. The night count fed in is the SAME valid-HRV-night tally the
 * recovery seed uses (see `bankedCalibrationNights` in TodayScreen), so a progress bar can never
 * over-state what the baseline has actually banked.
 *
 * The targets mirror WHOOP's published Calibration Timeline (Day 4 Recovery, Day 7 Sleep, Day 30 full
 * baseline) plus noop's own 14-night Trusted gate, so a WHOOP user sees the timeline they expect while
 * noop's better statistics run underneath. Mirrors the Swift `CalibrationMilestones`.
 */
object CalibrationMilestones {

    /** The 5.0/MG "first week" sleep-coaching milestone (WHOOP Day 7). Not a baseline gate — a card target. */
    const val sleepBaselineNights: Int = 7

    /** WHOOP's full-baseline / rolling-30-day milestone (Day 30). Not a baseline gate — a card target. */
    const val fullBaselineNights: Int = 30

    /** A single calibration checkpoint. [nights] is the banked valid-night count at which it unlocks. */
    data class Milestone(
        /** Stable id (persisted / cross-platform / analytics-safe). Never renumber. */
        val id: String,
        val title: String,
        val nights: Int,
        /** One-line "what this unlocks", shown on the active card. */
        val unlocks: String,
    )

    /** The ordered timeline, soonest first. Targets are pinned to the honest gates where they coincide. */
    val all: List<Milestone> = listOf(
        Milestone(
            id = "firstRecovery",
            title = "First Recovery",
            nights = Baselines.minNightsSeed, // 4 — matches WHOOP Day 4
            unlocks = "Charge, Effort and Rest become personal to you.",
        ),
        Milestone(
            id = "sleepBaseline",
            title = "Sleep baseline",
            nights = sleepBaselineNights, // 7 — WHOOP "after your first week"
            unlocks = "Your sleep need and coaching tune to your own nights.",
        ),
        Milestone(
            id = "trustedBaseline",
            title = "Trusted baseline",
            nights = Baselines.minNightsTrust, // 14 — noop's full-confidence gate
            unlocks = "Full-confidence baselines — the calibrating tag drops.",
        ),
        Milestone(
            id = "fullBaseline",
            title = "30-day baseline",
            nights = fullBaselineNights, // 30 — WHOOP full baseline / rolling window
            unlocks = "Your complete rolling 30-day baseline is set.",
        ),
    )

    /** The furthest target. Banked ≥ this ⇒ calibration is fully complete and the card retires.
     *  `maxOfOrNull ?: 0` matches the Swift twin's empty-safe default (never throws on an empty list). */
    val finalNights: Int get() = all.maxOfOrNull { it.nights } ?: 0

    /** DONE = already reached; ACTIVE = the live countdown (first unreached); LOCKED = still ahead. */
    enum class State { DONE, ACTIVE, LOCKED }

    /** A milestone paired with its computed progress against a banked-night count. */
    data class Progress(
        val milestone: Milestone,
        val state: State,
        /** Nights still needed (0 once done). */
        val remaining: Int,
        /** 0..1 absolute fill toward this milestone's target (1.0 once done). */
        val fraction: Double,
    )

    /**
     * Resolve every milestone's [Progress] for a user who has banked [nightsBanked] valid nights. The
     * FIRST not-yet-reached milestone is [State.ACTIVE] (the live countdown); earlier ones are DONE,
     * later ones LOCKED. Fraction is absolute (banked ÷ target) so the bar text ("9/14 nights") and the
     * fill agree. Pure + unit-tested; mirrors the Swift twin.
     */
    fun progress(nightsBanked: Int): List<Progress> {
        val n = nightsBanked.coerceAtLeast(0)
        var activeAssigned = false
        return all.map { m ->
            val done = n >= m.nights
            val state = when {
                done -> State.DONE
                !activeAssigned -> { activeAssigned = true; State.ACTIVE }
                else -> State.LOCKED
            }
            Progress(
                milestone = m,
                state = state,
                remaining = (m.nights - n).coerceAtLeast(0),
                fraction = if (done) 1.0 else (n.toDouble() / m.nights).coerceIn(0.0, 1.0),
            )
        }
    }

    /** True while at least one milestone is still unreached (banked < [finalNights]) — the card should show. */
    fun isCalibrating(nightsBanked: Int): Boolean = nightsBanked < finalNights
}

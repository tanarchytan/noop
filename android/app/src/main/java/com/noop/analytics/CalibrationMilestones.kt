package com.noop.analytics

/**
 * WHOOP-familiar countdown timeline surfaced on Today (gamification).
 *
 * Presentation layer only — drives the milestone countdown cards, never the baseline math in
 * [Baselines] (seed [Baselines.minNightsSeed] = 4, trust [Baselines.minNightsTrust] = 14). The
 * night count fed in is the same valid-HRV-night tally the recovery seed uses, so a progress bar
 * can never over-state what the baseline has actually banked.
 *
 * These targets (seed 4, trust 14, plus two round coaching targets) answer "how far is my
 * baseline" — a different question from the app's per-feature unlock schedule (Recovery 3
 * nights, sleep consistency 5, VO2 max 14), which answers "is this metric shown yet".
 */
object CalibrationMilestones {

    /** First-week sleep-coaching target. Not a baseline gate — a card target. */
    const val sleepBaselineNights: Int = 7

    /** The rolling-30-day baseline target. Not a baseline gate — a card target. */
    const val fullBaselineNights: Int = 30

    /**
     * A single calibration checkpoint. [nights] is the banked valid-night count at which it unlocks.
     * The title and the "what this unlocks" line are keyed off [id] in the UI, which is where the
     * words live.
     */
    data class Milestone(
        /** Stable id (persisted, analytics-safe). Never renumber. */
        val id: String,
        val nights: Int,
    )

    /** The ordered timeline, soonest first. Targets are pinned to the honest gates where they coincide. */
    val all: List<Milestone> = listOf(
        Milestone(id = "firstRecovery", nights = Baselines.minNightsSeed), // 4 — noop seeds its baseline here
        Milestone(id = "sleepBaseline", nights = sleepBaselineNights), // 7 — a full week of nights
        Milestone(id = "trustedBaseline", nights = Baselines.minNightsTrust), // 14 — full-confidence gate
        Milestone(id = "fullBaseline", nights = fullBaselineNights), // 30 — the full rolling window
    )

    /** The furthest target. Banked ≥ this means calibration is fully complete and the card retires.
     *  `maxOfOrNull ?: 0` is the empty-safe default (never throws on an empty list). */
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
     * first not-yet-reached milestone is [State.ACTIVE]; earlier ones are DONE, later ones LOCKED.
     * Fraction is absolute (banked ÷ target) so the bar text ("9/14 nights") and the fill agree.
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

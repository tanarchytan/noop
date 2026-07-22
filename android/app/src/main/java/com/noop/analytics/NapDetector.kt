package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrRow

/*
 * NapDetector.kt — the app-side entry for short-nap detection. The tri-state inference (dense-gravity
 * eligibility, the longest-quiet-run, the length + HR-settled gates) lives in whoop-rs (physio-algo nap),
 * reached via RustScores. A nap is INFERRED from wrist motion + HR, so the verdict is conservative and only
 * PROPOSES a review card (via NapStore) — it NEVER auto-writes a sleep session. Times are unix SECONDS.
 */

/** Tri-state verdict for one candidate window. Conservative by construction. */
enum class NapVerdict {
    /** Confident short nap: dense quiet motion + a settled HR band over a plausible nap length. */
    NAP,

    /** Confident NOT a nap: the window had dense data but the person was clearly awake/active. */
    NONE,

    /** Couldn't tell — too little dense data, or mixed signals. Shown honestly, never guessed. */
    INCONCLUSIVE,
}

/**
 * A proposed nap the user can review (accept → it becomes a manual nap session; dismiss → forgotten).
 * Times are wall-clock unix seconds. [confidence] in 0..1 is for ordering/UI only — NEVER a medical claim.
 */
data class NapCandidate(
    val start: Long,
    val end: Long,
    /** Mean HR over the quiet stretch (bpm), for the honest review-card sub-line. Null if no HR landed. */
    val meanHr: Int?,
    /** 0..1 ordering confidence (motion quietness + HR settling). Not a probability, not a diagnosis. */
    val confidence: Double,
) {
    val durationS: Long get() = end - start
}

/** The full outcome of one [NapDetector.evaluate] pass: the verdict + (when NAP) the candidate to review. */
data class NapDecision(
    val verdict: NapVerdict,
    /** Present only when [verdict] == NAP; the window to offer as a review card. */
    val candidate: NapCandidate?,
)

/**
 * User-tunable thresholds. Defaults calibrated from on-wrist data (desk/quiet ≈ 0.05–0.10 g smoothed,
 * walking ≈ 0.2–0.4 g) plus typical resting-HR bands. Resolved into the whoop-rs nap config on each call.
 */
data class NapConfig(
    /** Feature toggle (default OFF — opt-in, manual-first). */
    val enabled: Boolean = false,
    /** Shortest stretch that counts as a nap (minutes). Below this it's just sitting still. */
    val minNapMinutes: Int = NapDetector.DEFAULT_MIN_NAP_MIN,
    /** Longest a daytime "nap" can be before it's really main sleep we shouldn't fold in (minutes). */
    val maxNapMinutes: Int = NapDetector.DEFAULT_MAX_NAP_MIN,
    /** Smoothed wrist-motion at/under this (g) is "lying still" — quieter than the sedentary threshold,
     *  because a nap needs genuine stillness, not just "not walking". */
    val stillThresholdG: Double = NapDetector.DEFAULT_STILL_THRESHOLD_G,
    /** HR must sit at/under (restingHr + this margin) bpm to read as asleep, not awake-but-still. */
    val hrSettleMarginBpm: Int = NapDetector.DEFAULT_HR_SETTLE_MARGIN_BPM,
    /** Rolling-mean window (seconds) for the motion signal — shorter than the sedentary one so a brief
     *  nap isn't smoothed away. */
    val smoothWindowSeconds: Double = NapDetector.DEFAULT_SMOOTH_WINDOW_S,
)

object NapDetector {

    // ── Defaults (the NapConfig data-class reads these) ──────────────────────
    const val DEFAULT_MIN_NAP_MIN: Int = 20
    const val DEFAULT_MAX_NAP_MIN: Int = 90
    const val DEFAULT_STILL_THRESHOLD_G: Double = 0.08
    const val DEFAULT_HR_SETTLE_MARGIN_BPM: Int = 8
    const val DEFAULT_SMOOTH_WINDOW_S: Double = 120.0

    /**
     * Classify the candidate window for a short nap. Pass the freshly-arrived [gravity] + [hr] for the
     * window, the person's [restingHr] (null if unknown), and the [config]. The tri-state logic (OFF or
     * sparse → INCONCLUSIVE; dense but moving or a too-short quiet run → NONE; a too-long run → INCONCLUSIVE;
     * a quiet run in `[min,max]` with HR not settled when known → NONE; else → NAP) runs in whoop-rs.
     */
    fun evaluate(gravity: List<GravitySample>, hr: List<HrRow>, restingHr: Int?, config: NapConfig): NapDecision =
        RustScores.napEvaluate(gravity, hr, restingHr, config)
}

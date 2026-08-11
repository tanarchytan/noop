package com.noop.analytics

// IllnessSignalEngine.kt — multi-signal "Heads-Up" early-warning with explicit false-positive suppression.
//
// INDEPENDENT implementation of the published multi-parameter pre-symptomatic signature documented across
// the wearable literature: resting HR ↑, skin temperature ↑, HRV (RMSSD) ↓ and respiration ↑ move TOGETHER,
// days before symptoms. NOOP re-derives the PATTERN against the user's OWN rolling baseline, never a
// population cutoff, via a calibrated 0–100 score, a ≥2-signal corroboration gate, and EXPLICIT confounder
// suppression cross-checked against the same-day journal tags.
//
// WELLNESS ONLY — APPROXIMATE, NOT A DIAGNOSIS. It decides WHICH read fires ([Message]); the UI words it,
// and no wording here may ever name a condition.
object IllnessSignalEngine {

    // ── Tuning constants (pinned by test) ──
    const val raiseThreshold: Double = 50.0
    const val mildThreshold: Double = 25.0
    const val minCorroboratingSignals: Int = 2
    const val signalZThreshold: Double = 2.0
    const val kZToScore: Double = 22.0
    const val perSignalCap: Double = 40.0
    const val confounderDampen: Double = 0.45

    // ── Inputs ──

    /**
     * One signal's recent-vs-baseline reading, already z-scored by the caller. [zIllnessward] is
     * oriented so positive always means "more illness-like" (RHR/skin-temp/respiration pass raw z,
     * HRV passes the NEGATED z). [present] = false means no usable data; it's skipped, not corroboration.
     */
    data class SignalReading(val zIllnessward: Double, val present: Boolean = true)

    /** All four signal readings for the recent window. Any may be absent (sparse 5/MG nights). */
    data class Inputs(
        val restingHR: SignalReading? = null,
        val skinTemp: SignalReading? = null,
        val hrv: SignalReading? = null,
        val respiration: SignalReading? = null,
    )

    /**
     * Same-day behaviour context that can explain an anomaly away. [travelPhaseJump] is the cross-feature
     * hook — CircadianEngine can flag a detected body-clock jump (jet lag). [baselineTrusted] = false →
     * the engine stays silent (don't warn off a cold-start baseline).
     */
    data class Context(
        val alcohol: Boolean = false,
        val stress: Boolean = false,
        val sauna: Boolean = false,
        val hardOrLateWorkout: Boolean = false,
        val travelPhaseJump: Boolean = false,
        val alreadyUnwell: Boolean = false,
        val baselineTrusted: Boolean = true,
    )

    // ── Output ──

    enum class Level(val raw: String) {
        QUIET("quiet"),
        MILD("mild"),
        RAISED("raised"),
        SUPPRESSED("suppressed"),
        ALREADY_UNWELL("alreadyUnwell"),
    }

    /** Which signal cleared [signalZThreshold]. The wording for each lives in the UI. */
    enum class SignalKey(val key: String) {
        RESTING_HR("restingHR"), SKIN_TEMP("skinTemp"), HRV("hrv"), RESPIRATION("respiration"),
    }

    /** Which logged behaviour explained an anomaly away. The wording for each lives in the UI. */
    enum class Confounder { ALCOHOL, STRESS, SAUNA, HARD_OR_LATE_WORKOUT, TRAVEL }

    /** Which one-line read the heads-up resolves to. The wording for each lives in the UI. */
    enum class Message {
        LEARNING_BASELINE,
        UNWELL_AND_AGREES,
        UNWELL_LOGGED,
        NOTHING_NOTABLE,
        SUPPRESSED,
        MILD,
        RAISED,
    }

    data class Result(
        val score: Double,
        val level: Level,
        /** Caller-supplied phrases for the signals that fired, in [SignalKey] order. Empty unless the
         *  caller passed `firedLabels`; the UI words [firedKeys] instead. */
        val firedSignals: List<String>,
        /** Which signals fired, in a fixed order. The wording for each lives in the UI. */
        val firedKeys: List<SignalKey>,
        val suppressedBy: List<Confounder>,
        val signalCount: Int,
        val message: Message,
    )

    // ── Evaluate ──

    /**
     * Score the recent window and decide the heads-up level + [Message]. [firedLabels] maps a
     * [SignalKey.key] to a caller-rendered phrase carried through as [Result.firedSignals]; the UI words
     * [Result.firedKeys] instead. Only signals that clear [signalZThreshold] are surfaced.
     */
    fun evaluate(inputs: Inputs, context: Context, firedLabels: Map<String, String> = emptyMap()): Result {
        // Order is fixed so firedKeys is deterministic.
        val ordered: List<Pair<SignalKey, SignalReading?>> = listOf(
            SignalKey.RESTING_HR to inputs.restingHR,
            SignalKey.SKIN_TEMP to inputs.skinTemp,
            SignalKey.HRV to inputs.hrv,
            SignalKey.RESPIRATION to inputs.respiration,
        )

        var rawScore = 0.0
        val firedKeys = mutableListOf<SignalKey>()
        for ((key, reading) in ordered) {
            if (reading == null || !reading.present) continue
            val over = reading.zIllnessward - signalZThreshold
            if (over <= 0) continue
            firedKeys.add(key)
            rawScore += minOf(perSignalCap, kZToScore * over)
        }
        val score = minOf(100.0, rawScore)
        val signalCount = firedKeys.size
        val firedSignals = firedKeys.mapNotNull { firedLabels[it.key] }

        fun result(at: Double, level: Level, suppressed: List<Confounder>, message: Message) =
            Result(at, level, firedSignals, firedKeys, suppressed, signalCount, message)

        // Gate 0: untrusted baseline → silent.
        if (!context.baselineTrusted) {
            return result(score, Level.QUIET, emptyList(), Message.LEARNING_BASELINE)
        }

        // Already-unwell path: switch from "early warning" to a gentle "rest up".
        if (context.alreadyUnwell) {
            val agreeing = score >= mildThreshold && signalCount >= 1
            return result(
                score, Level.ALREADY_UNWELL, emptyList(),
                if (agreeing) Message.UNWELL_AND_AGREES else Message.UNWELL_LOGGED,
            )
        }

        // Corroboration + magnitude gate.
        if (signalCount < minCorroboratingSignals || score < mildThreshold) {
            return result(score, Level.QUIET, emptyList(), Message.NOTHING_NOTABLE)
        }

        // Confounder suppression — the differentiating part.
        val suppressedBy = mutableListOf<Confounder>()
        if (context.alcohol) suppressedBy.add(Confounder.ALCOHOL)
        if (context.stress) suppressedBy.add(Confounder.STRESS)
        if (context.sauna) suppressedBy.add(Confounder.SAUNA)
        if (context.hardOrLateWorkout) suppressedBy.add(Confounder.HARD_OR_LATE_WORKOUT)
        if (context.travelPhaseJump) suppressedBy.add(Confounder.TRAVEL)

        if (suppressedBy.isNotEmpty()) {
            return result(score * confounderDampen, Level.SUPPRESSED, suppressedBy, Message.SUPPRESSED)
        }

        // No confounder. Mild stays in the detail view; a strong composite raises.
        if (score < raiseThreshold) {
            return result(score, Level.MILD, emptyList(), Message.MILD)
        }
        return result(score, Level.RAISED, emptyList(), Message.RAISED)
    }
}

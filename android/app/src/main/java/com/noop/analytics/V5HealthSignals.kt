package com.noop.analytics

import com.noop.data.DailyMetric

/**
 * Pure adapter turning cached [DailyMetric] history into per-night z-scored inputs for the three v5
 * skin-temp-suite engines (CyclePhaseEngine / CircadianEngine / IllnessSignalEngine), run once per
 * analytics pass. No I/O, no state — caller hands `recentDays` + prefs/profile flags, gets a [Snapshot].
 * Uses whoop-rs's illness baseline z, not the full [Baselines] EWMA, since the cards only need a
 * deviation-against-your-own-range read, kept cheap and DB-free. NON-CLINICAL: every output is an
 * approximation, never a diagnosis. Cycle awareness is opt-in, gated on a default-off pref before
 * [Snapshot.cycle] is read.
 */
object V5HealthSignals {

    /** The published engine results for the Health hub's skin-temp suite, all already decided. */
    data class Snapshot(
        val cycle: CyclePhaseEngine.Result,
        val bodyClock: CircadianEngine.PhaseEstimate?,
        val illness: IllnessSignalEngine.Result,
        /**
         * Parallel Mahalanobis illness-distance read, computed on the same illness-ward z-vector as [illness]
         * but never gating the alert — [IllnessSignalEngine] stays the sole fire gate. Only surfaces a "how
         * strong" confidence readout in the Heads-Up card once the engine has already raised; null if absent.
         */
        val illnessDistance: IllnessDistance.Result?,
        /** True once there are enough trusted nights for any of these to be more than "learning". */
        val baselineTrusted: Boolean,
    )

    /**
     * Runs the three engines over [days] (oldest to newest). [cycleOptedIn] gates the cycle classifier
     * (off returns a cheap LEARNING result so the UI's opt-in card still shows). [loggedPeriodStarts] are
     * optional "yyyy-MM-dd" period-start days; [journalContext] carries same-day confounder flags for illness suppression.
     */
    fun evaluate(
        days: List<DailyMetric>,
        cycleOptedIn: Boolean,
        loggedPeriodStarts: List<String> = emptyList(),
        journalContext: IllnessSignalEngine.Context = IllnessSignalEngine.Context(),
        habitualWakeHour: Double = 7.0,
        activitySamples: List<uniffi.whoop_ffi.ActivitySample> = emptyList(),
        tzOffsetSeconds: Long = 0,
    ): Snapshot {
        val baselineCfg = RustScores.illnessBaselineCfg
        val baselineTrusted = days.count { hasAnyVital(it) } >= baselineCfg.minNights.toInt()

        // ── Per-night z-scores: whoop-rs owns the trailing window, its recent-night gap and the z ──
        val tempZs = RustScores.illnessBaselineZ(days.map { it.skinTempDevC })
        val rhrZs = RustScores.illnessBaselineZ(days.map { it.restingHr?.toDouble() })
        val hrvZs = RustScores.illnessBaselineZ(days.map { it.avgHrv })
        val respZs = RustScores.illnessBaselineZ(days.map { it.respRateBpm })
        val nights = days.mapIndexed { i, d ->
            CyclePhaseEngine.Night(day = d.day, tempZ = tempZs[i], rhrZ = rhrZs[i], hrvZ = hrvZs[i])
        }

        // ── Cycle awareness (opt-in) ──
        val cycle = if (cycleOptedIn) {
            CyclePhaseEngine.classify(nights, baselineUsable = baselineTrusted, loggedPeriodStarts = loggedPeriodStarts)
        } else {
            CyclePhaseEngine.Result(
                phase = CyclePhaseEngine.Phase.LEARNING,
                confidence = CyclePhaseEngine.Confidence.LEARNING,
                cycleDayLow = null, cycleDayHigh = null, cycleLengthDays = null,
                nextPeriodWindow = null, shiftMarkers = emptyList(),
                note = "Turn on cycle awareness to read a coarse phase from your nightly temperature.",
            )
        }

        // ── Illness heads-up (confounder-suppressed) ──
        val latest = nights.lastOrNull()
        val firedLabels = HashMap<String, String>()
        latest?.rhrZ?.let { if (it >= IllnessSignalEngine.signalZThreshold) firedLabels["restingHR"] = "RHR up" }
        latest?.tempZ?.let { if (it >= IllnessSignalEngine.signalZThreshold) firedLabels["skinTemp"] = "skin temp up" }
        latest?.hrvZ?.let { if (-it >= IllnessSignalEngine.signalZThreshold) firedLabels["hrv"] = "HRV down" }
        val respZ = respZs.lastOrNull()
        respZ?.let { if (it >= IllnessSignalEngine.signalZThreshold) firedLabels["respiration"] = "respiration up" }

        val illnessInputs = IllnessSignalEngine.Inputs(
            restingHR = latest?.rhrZ?.let { IllnessSignalEngine.SignalReading(it) },
            skinTemp = latest?.tempZ?.let { IllnessSignalEngine.SignalReading(it) },
            // HRV must be oriented illness-ward: HRV ↓ is illness-like, so negate the raw z.
            hrv = latest?.hrvZ?.let { IllnessSignalEngine.SignalReading(-it) },
            respiration = respZ?.let { IllnessSignalEngine.SignalReading(it) },
        )
        val illness = IllnessSignalEngine.evaluate(
            inputs = illnessInputs,
            context = journalContext.copy(baselineTrusted = baselineTrusted),
            firedLabels = firedLabels,
        )

        // Parallel Mahalanobis distance on the same illness-ward z-vector (RHR up, HRV negated, skin-temp up,
        // respiration up); never gates the alert — IllnessSignalEngine above is the sole fire gate, this only
        // drives the Heads-Up card's "how strong" band. A null z drops out; correlation = null (identity).
        val illnessDistance = IllnessDistance.evaluate(
            features = IllnessDistance.FeatureVector(
                restingHR = latest?.rhrZ,
                rmssd = latest?.hrvZ?.let { -it },   // orient illness-ward: HRV down is illness-like
                skinTemp = latest?.tempZ,
                respiration = respZ,
            ),
            correlation = null,
        )

        // ── Body clock: whoop-rs bins the rest-activity samples per local hour and fits the cosinor,
        //    the same fit Rhythm Age reads, so the two can never disagree. No samples leaves it null and
        //    the card keeps its honest empty state. ──
        val wornDays = activitySamples.map { (it.unix + tzOffsetSeconds) / 86_400L }.distinct().size
        val bodyClock = if (activitySamples.isEmpty()) null else {
            RustScores.circadianPhase(activitySamples, tzOffsetSeconds, wornDays, habitualWakeHour, null)
                ?.let { CircadianEngine.fromRust(it) }
        }

        return Snapshot(cycle = cycle, bodyClock = bodyClock, illness = illness,
            illnessDistance = illnessDistance, baselineTrusted = baselineTrusted)
    }

    /** A day is "usable" for the baseline if it carries at least one of the four illness/cycle vitals. */
    private fun hasAnyVital(d: DailyMetric): Boolean =
        d.restingHr != null || d.avgHrv != null || d.skinTempDevC != null || d.respRateBpm != null
}

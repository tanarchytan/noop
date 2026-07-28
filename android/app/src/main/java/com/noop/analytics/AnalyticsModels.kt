package com.noop.analytics

/*
 * AnalyticsModels.kt — shared on-device analytics value types.
 *
 * Naming: the detected-sleep type is [DetectedSleep] so it does not clash with the Room
 * entity com.noop.data.SleepSession. HR-zone display types live in HrZones.kt.
 *
 * All `ts` / `start` / `end` are wall-clock unix SECONDS (Long). All derived intensity /
 * energy / sleep-stage outputs are APPROXIMATE, a wellness estimate, never medical advice.
 */

/** On-device analytics namespace marker. */
object StrandAnalytics {
    const val VERSION: String = "0.1.0"
}

// ─────────────────────────────────────────────────────────────────────────────
// HRV
// ─────────────────────────────────────────────────────────────────────────────

/** One HRV analysis over a window, filled from whoop-rs by [RustScores.analyzeRaw]. Every field is null
 *  and [nClean] is 0 when a cleaning gate refused the reading. */
data class HrvResult(
    /** RMSSD in milliseconds, or null when too few valid beats. */
    val rmssd: Double?,
    /** SDNN (sample SD, ddof=1) in milliseconds, or null when too few valid beats. */
    val sdnn: Double?,
    /** Mean NN interval (ms) over the cleaned beats, or null. */
    val meanNN: Double?,
    /** pNN50: % of successive |dNN| > 50 ms, or null. */
    val pnn50: Double?,
    /** Count of RR intervals supplied to the analysis (before cleaning). */
    val nInput: Int,
    /** Count of clean NN intervals after range + ectopic filtering. */
    val nClean: Int,
) {
    companion object {
        /** An empty/insufficient-data result that preserves the input count. */
        fun empty(nInput: Int): HrvResult =
            HrvResult(rmssd = null, sdnn = null, meanNN = null, pnn50 = null, nInput = nInput, nClean = 0)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UserProfile
// ─────────────────────────────────────────────────────────────────────────────

/** User profile for HRmax and calorie estimation. */
data class UserProfile(
    val weightKg: Double = 70.0,
    val heightCm: Double = 170.0,
    val age: Double = 30.0,
    /** "male" | "female" | "nonbinary". */
    val sex: String = "nonbinary",
    /**
     * Counter ticks per real step for the @57 motion counter. The WHOOP 5/MG counter
     * overcounts and its true tick rate is unknown, so the daily-steps total divides by
     * this. 1.0 = raw pass-through (default); the engine clamps to >= 0.5.
     */
    val stepTicksPerStep: Double = 1.0,
    /**
     * Waist circumference (cm) for the Fitness Age VO₂max estimate. 0 = not set. Optional:
     * unlocks the VO₂max readout but does not sharpen Fitness Age itself (the body term
     * cancels out of that formula).
     */
    val waistCm: Double = 0.0,
)

// ─────────────────────────────────────────────────────────────────────────────
// Sleep staging output shapes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A contiguous sleep-stage segment. Times are wall-clock unix seconds, encoded verbatim
 * into stagesJSON. `start`/`end` are `var` so the stager can extend the trailing segment
 * in place.
 */
data class StageSegment(
    var start: Long,
    var end: Long,
    /** "wake" | "light" | "deep" | "rem". */
    var stage: String,
)

/**
 * A detected sleep session (in-bed span) with APPROXIMATE staging. Named [DetectedSleep]
 * to avoid clashing with the Room entity com.noop.data.SleepSession.
 */
data class DetectedSleep(
    val start: Long,
    val end: Long,
    /** asleep / in-bed in [0, 1] (AASM TST/TIB; asleep = in-bed − wake). */
    val efficiency: Double,
    val stages: List<StageSegment>,
    /** Lowest 5-min rolling-mean HR during the session (bpm), or null. */
    val restingHR: Int?,
    /** Mean RMSSD over 5-min windows across the session (ms), or null. */
    val avgHRV: Double?,
    /** Per-30 s-epoch motion magnitudes (summed |Δgravity|), from the whoop-rs analyze; empty when ungriddable. */
    val motionGrid: List<Double> = emptyList(),
    /** Per-30 s-epoch band sleep_state (0 wake/1 still/2 asleep/3 up), from the whoop-rs analyze; empty when absent. */
    val sleepStateGrid: List<Int> = emptyList(),
)

/** AASM-style metrics from a session's stage segments. */
data class HypnogramMetrics(
    val tibS: Double,
    val tstS: Double,
    val sptS: Double,
    val solS: Double,
    /** NaN if no REM. */
    val remLatencyS: Double,
    val wasoS: Double,
    val efficiency: Double,
    val disturbances: Int,
    val deepMin: Double,
    val remMin: Double,
    val lightMin: Double,
    val deepPct: Double,
    val remPct: Double,
    val lightPct: Double,
)

// ─────────────────────────────────────────────────────────────────────────────
// Workout detection output shapes
// ─────────────────────────────────────────────────────────────────────────────

/** Per-record motion-intensity sample. */
data class ActivityPoint(
    val ts: Long,
    val intensity: Double,
)

/** A detected workout window. All intensity fields are APPROXIMATE. */
data class ExerciseSession(
    val start: Long,
    val end: Long,
    val avgHR: Double,
    val peakHR: Int,
    val strain: Double?,
    val durationS: Double,
    /** Edwards zone (0–5) time breakdown as % of HR samples; sums to 100. */
    val zoneTimePct: Map<Int, Double>,
    /** Mean Karvonen %HRR over the bout, clamped [0, 100], or null. */
    val avgHRRPct: Double?,
    /** Effective HRmax used for zone math (bpm), or null. */
    val hrmax: Double?,
    /** "caller" | "observed" | "tanaka" | "unknown". */
    val hrmaxSource: String,
    val caloriesKcal: Double?,
    val caloriesKJ: Double?,
)

// ─────────────────────────────────────────────────────────────────────────────
// Personal baselines
// ─────────────────────────────────────────────────────────────────────────────

/** Per-metric configuration for the baseline model. */
data class MetricCfg(
    /** Physiological lower bound (hard reject below). */
    val minVal: Double,
    /** Physiological upper bound (hard reject above). */
    val maxVal: Double,
    /** σ_floor: minimum dispersion. */
    val floorSpread: Double,
    /** Baseline-center half-life (nights). */
    val halfLifeB: Double,
    /** Spread half-life (nights, slower than center). */
    val halfLifeS: Double,
)

/**
 * Baseline status flags (cold-start → trusted → stale). [raw] is the exact lowercase
 * wire string persisted for this status.
 */
enum class BaselineStatus(val raw: String) {
    /** Fewer than MIN_NIGHTS_SEED valid nights; no score yet. */
    CALIBRATING("calibrating"),
    /** Between seed and trust thresholds; usable, higher uncertainty. */
    PROVISIONAL("provisional"),
    /** At least MIN_NIGHTS_TRUST valid nights. */
    TRUSTED("trusted"),
    /** Usable but no update for > STALE_DAYS nights. */
    STALE("stale"),
}

/** Immutable snapshot of a personal baseline for one metric after N nights. */
data class BaselineState(
    /** Robust EWMA center (the personal "mean"). */
    val baseline: Double,
    /**
     * EWMA of absolute deviations, floored at cfg.floorSpread. Multiply by 1.253
     * to approximate Gaussian σ.
     */
    val spread: Double,
    /** Count of valid nights contributing to the state. */
    val nValid: Int,
    /** Consecutive nights with no valid value (staleness tracking). */
    val nightsSinceUpdate: Int,
    /** Cold-start / staleness status. */
    val status: BaselineStatus,
) {
    /** True iff fully trusted (not calibrating or stale). */
    val trusted: Boolean get() = status == BaselineStatus.TRUSTED

    /** True iff at least provisionally usable (nValid ≥ MIN_NIGHTS_SEED). */
    val usable: Boolean
        get() = status == BaselineStatus.PROVISIONAL || status == BaselineStatus.TRUSTED
}

/** Three forms of deviation from a personal baseline. */
data class Deviation(
    /** Robust z-score: (value − baseline) / (1.253 × spread). */
    val z: Double,
    /** Signed physical-units delta: value − baseline. */
    val delta: Double,
    /** Fractional deviation: value / baseline − 1. */
    val ratio: Double,
    /** True iff |z| ≤ 1.0. */
    val inNormalRange: Boolean,
)

// ─────────────────────────────────────────────────────────────────────────────
// Engine orchestration shapes
// ─────────────────────────────────────────────────────────────────────────────

/** Baselines passed in by the caller (built from prior nights via Baselines). */
data class ProfileBaselines(
    val hrv: BaselineState? = null,
    val restingHR: BaselineState? = null,
    val resp: BaselineState? = null,
    val skinTemp: BaselineState? = null,
    // Rolling daily-Effort/strain baseline for the recovery Activity-Balance term.
    val effort: BaselineState? = null,
)

/**
 * The full analysis result for one day. [daily] is the Room entity com.noop.data.DailyMetric
 * (cache shape, recovery/strain/sleep rolled up); the [DetectedSleep] sessions persist to
 * com.noop.data.SleepSession rows, wired by the caller when upserting.
 */
data class DayResult(
    /** DailyMetric in the Room cache shape (recovery/strain/sleep rolled up). */
    val daily: com.noop.data.DailyMetric,
    /** Detected sleep sessions (rich, with stage segments). */
    val sleepSessions: List<DetectedSleep>,
    /** Detected workout/exercise sessions. */
    val workouts: List<ExerciseSession>,
    /** Charge (recovery) score [0,100] or null (cold-start / no HRV baseline). */
    val recovery: Double?,
    /** Effort (strain) score [0,100] or null (insufficient HR samples / invalid HRR). */
    val strain: Double?,
    /**
     * Rest (sleep_performance) composite [0,100] or null (no in-bed session), stored under the
     * `sleep_performance` key: duration-vs-need 0.50 + efficiency 0.20 + restorative 0.20 +
     * consistency 0.10.
     */
    val rest: Double? = null,
    /**
     * Wear-gated mean in-bed skin temperature (°C) for this night, or null when no worn in-bed
     * samples exist. Baseline-independent: the caller seeds a personal skin-temp baseline from
     * these nightly means, then re-derives [com.noop.data.DailyMetric.skinTempDevC]. APPROXIMATE.
     */
    val nightlySkinTempC: Double? = null,
    /** Per-score certainty tier for Charge (recovery). */
    val chargeConfidence: ScoreConfidence = ScoreConfidence.CALIBRATING,
    /** Per-score certainty tier for Effort (strain). */
    val effortConfidence: ScoreConfidence = ScoreConfidence.CALIBRATING,
    /** Per-score certainty tier for Rest (sleep_performance composite). */
    val restConfidence: ScoreConfidence = ScoreConfidence.CALIBRATING,
    /**
     * Per-session per-epoch motion magnitudes, keyed by [DetectedSleep.start], on the same 30 s
     * grid as `stagesJSON`. Omitted (no key) when too little gravity exists to grid, so
     * `WhoopRepository.persistSessionMotion` never persists a fabricated zero series.
     */
    val sessionMotionByStart: Map<Long, List<Double>> = emptyMap(),
    /**
     * Per-session band sleep_state (@81 code: 0 wake/1 still/2 asleep/3 up), keyed by
     * [DetectedSleep.start] on the 30 s `stagesJSON` grid; OMITTED (no key) when absent so NULL
     * persists, not a fabricated array. Never overrides the derived hypnogram; empty on WHOOP 4.0.
     */
    val sessionSleepStateByStart: Map<Long, List<Int>> = emptyMap(),
)

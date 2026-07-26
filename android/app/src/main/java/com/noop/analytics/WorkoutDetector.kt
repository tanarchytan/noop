package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/*
 * WorkoutDetector.kt — retroactive workout detection from the 1 Hz store.
 *
 * Faithful Kotlin port of StrandAnalytics/WorkoutDetector.swift (verified on macOS),
 * itself ported from server/ingest/app/analysis/exercise.py (+ activity.py, calories.py).
 *
 * A workout is a SUSTAINED window (≥ MIN_EXERCISE_MIN) of elevated HR (above
 * resting + HR_MARGIN_BPM) AND sustained motion (gravity-derived intensity above
 * MOTION_THRESHOLD). Both gates must hold for a sample to count as active.
 *
 * Per detected bout: avg/peak HR, duration, Edwards zone time-%, mean %HRR,
 * strain (StrainScorer), and estimated calories (Keytel 2005 active + revised
 * Harris–Benedict BMR resting, age/sex/weight/height adjusted).
 *
 * All intensity/energy outputs are APPROXIMATE and not medical advice.
 *
 * Types note: [UserProfile], [ExerciseSession] and [ActivityPoint] live in
 * AnalyticsModels.kt (shared value types) and are NOT redefined here. Inputs are
 * the Room entities com.noop.data.HrSample (ts:Long seconds, bpm:Int) and
 * com.noop.data.GravitySample (ts:Long seconds, x/y/z:Double). All `ts`/`start`/`end`
 * are unix SECONDS as Long. The Swift source used Int seconds.
 */
object WorkoutDetector {

    /**
     * Per-record motion-intensity series (L2 magnitude of the gravity change vs the previous record).
     * The one motion spine the sedentary and nap readings measure against.
     */
    fun activitySeries(gravity: List<GravitySample>): List<ActivityPoint> =
        RustScores.activitySeries(gravity)

    /** Trailing rolling mean of [activitySeries] intensities over `windowS` seconds. */
    internal fun smoothedIntensity(motion: List<ActivityPoint>, windowS: Double): List<Double> =
        RustScores.smoothedIntensity(motion, windowS)

    /**
     * Detect workout bouts from the day's HR + gravity streams: sustained elevated HR AND sustained
     * motion. Each bout carries its avg/peak HR, Edwards zone breakdown, mean %HRR, strain and the
     * estimated calories. The detection and every derived figure are computed in whoop-rs.
     */
    fun detect(
        hr: List<HrSample>,
        gravity: List<GravitySample>,
        restingHR: Double? = null,
        maxHR: Double? = null,
        age: Double? = null,
        profile: UserProfile? = null,
    ): List<ExerciseSession> = RustScores.workoutDetect(hr, gravity, restingHR, maxHR, age, profile)
}

/**
 * HR-based energy estimate for one bout: Keytel active energy above the HR-reserve gate, revised
 * Harris-Benedict BMR below it, each sample weighted by the elapsed time to the next. Computed in
 * whoop-rs; this is the profile-shaped door the app calls through.
 */
object Calories {

    /** `(kcal, kJ)` for a bout. Missing profile figures fall back to the neutral 70 kg / 170 cm / 30 y. */
    fun estimateBoutCalories(
        hrSamples: List<HrSample>,
        profile: UserProfile,
        hrmax: Double?,
        restingHR: Double?,
    ): Pair<Double, Double> = RustScores.caloriesBout(hrSamples, profile, hrmax, restingHR)
}

package com.noop.analytics

import com.noop.protocol.RawImuSample
import com.noop.protocol.Whoop5ImuFrame
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ImuFeatureExtractor.kt — activity features from decoded WHOOP 5/MG raw 6-axis IMU (#423). Kotlin twin
// of StrandAnalytics/ImuFeatureExtractor.swift (parity contract).
//
// The 5/MG offload buffer decodes (Whoop5RawImu) to 100 Hz 3-axis accel (g) + 3-axis gyro (deg/s). At
// 100 Hz the accelerometer resolves gait cadence, impact/jerk, and rotational energy the 1 Hz gravity
// vector physically cannot (a 1.8 Hz step rate is above the 1 Hz stream's Nyquist limit). This turns a
// window of raw samples into a compact activity-feature vector for coarse sport / HAR classification.
//
// Per the repo's derived-signal rule: cadence here is an autocorrelation peak on the accel-magnitude AC
// over a genuinely high-rate stream (not a fixed-N-per-record buffer), reported with its own strength so
// a caller can ignore a weak/absent peak; it is a FEATURE, never fed to a physiological gate. Validated
// to recover MULTIPLE injected cadences, not one lucky match (see ImuFeatureExtractorTest).

/** Compact activity features over a window of raw IMU samples. */
data class ImuActivityFeatures(
    /** RMS of the accel-magnitude AC (gravity removed), in g — overall movement intensity. */
    val accelEnergyG: Double,
    /** Mean gyroscope magnitude over the window, in deg/s — rotational intensity. */
    val gyroEnergyDps: Double,
    /** RMS of the accel first-difference (jerk), in g/sample — impact / explosiveness. */
    val jerkRms: Double,
    /** Dominant cadence in the gait band, Hz — null when no rhythmic peak clears [minCadenceStrength].
     *  Multiply by 60 for steps/min. */
    val cadenceHz: Double?,
    /** Normalized strength (0..1) of that cadence peak — high = rhythmic, low = bursty or still. */
    val cadenceStrength: Double,
    val sampleCount: Int,
)

object ImuFeatureExtractor {

    /** Cadence search band, Hz — human gait/pedal foot rate (~72-210 steps/min). Below the IMU Nyquist. */
    val cadenceBand: ClosedFloatingPointRange<Double> = 1.2..3.5

    /** A cadence peak below this normalized autocorrelation strength is treated as "no rhythm" (→ null Hz). */
    const val minCadenceStrength = 0.20

    /** Extract features from [samples] (from one or more [Whoop5ImuFrame]s, in order) at [sampleRateHz]. */
    /** Feature vector over one window of raw samples. The maths lives in whoop-rs. */
    fun extract(samples: List<RawImuSample>, sampleRateHz: Int): ImuActivityFeatures =
        RustScores.imuFeatures(samples, sampleRateHz)

    fun extract(frames: List<Whoop5ImuFrame>): ImuActivityFeatures {
        val rate = frames.firstOrNull()?.sampleRateHz ?: 100
        return extract(frames.flatMap { it.samples }, rate)
    }
}

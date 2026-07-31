package com.noop.analytics

import com.noop.protocol.RawImuSample
import com.noop.protocol.Whoop5ImuFrame

// Activity features from decoded WHOOP 5/MG raw 6-axis IMU. The offload buffer decodes to 100 Hz
// 3-axis accel (g) + 3-axis gyro (deg/s); at that rate the accelerometer resolves gait cadence,
// impact/jerk, and rotational energy the 1 Hz gravity vector cannot (a 1.8 Hz step rate is above its
// Nyquist limit). Turns a window of raw samples into a compact feature vector for coarse sport/HAR
// classification.
//
// Cadence is an autocorrelation peak on the accel-magnitude AC over the high-rate stream, reported
// with its own strength so a weak/absent peak can be ignored; it is a feature, never fed to a
// physiological gate. Validated to recover multiple injected cadences, not one match.

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

package com.noop.bp

/** Outcome of a blood-pressure operation (estimate or calibration). */
sealed interface BpResult {
    data class Estimate(
        val systolic: Int,
        val diastolic: Int,
        val confidence: Double,
        /** false = uncalibrated population base (rough, trend-oriented); true = cuff-calibrated. */
        val calibrated: Boolean = true,
    ) : BpResult
    data object Calibrated : BpResult
    data object NoSignal : BpResult
    data object NotCalibrated : BpResult
    data class Error(val message: String) : BpResult
}

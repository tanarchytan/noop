package com.noop.bp

/** Outcome of a blood-pressure operation (estimate or calibration). */
sealed interface BpResult {
    data class Estimate(val systolic: Int, val diastolic: Int, val confidence: Double) : BpResult
    data object Calibrated : BpResult
    data object NoSignal : BpResult
    data object NotCalibrated : BpResult
    data class Error(val message: String) : BpResult
}

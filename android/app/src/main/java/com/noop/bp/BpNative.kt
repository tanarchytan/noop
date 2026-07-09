package com.noop.bp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device blood-pressure path: runs [BpEngine] over the local [PpgBuffer] window and keeps the
 * calibration + readings in [BpStore]. No network — the PPG never leaves the phone.
 */
object BpNative {
    /** Estimate BP from the most recent PPG window using the locally-stored cuff calibration. */
    suspend fun estimate(ctx: Context): BpResult = withContext(Dispatchers.Default) {
        val samples = PpgBuffer.window(12) ?: return@withContext BpResult.NoSignal
        val f = BpEngine.extractFeatures(samples, PpgBuffer.sampleRateHz)
            ?: return@withContext BpResult.Error("Signal too weak — hold still and try again.")
        val cal = BpStore.calibration(ctx) ?: return@withContext BpResult.NotCalibrated
        val e = BpEngine.estimate(cal, f)
        BpStore.saveReading(ctx, BpStore.Reading(e.systolic, e.diastolic, e.confidence, System.currentTimeMillis()))
        BpResult.Estimate(e.systolic, e.diastolic, e.confidence)
    }

    /** Calibrate the local model: anchor a real cuff reading to the concurrent PPG window. */
    suspend fun calibrate(ctx: Context, systolic: Int, diastolic: Int): BpResult =
        withContext(Dispatchers.Default) {
            val samples = PpgBuffer.window(12) ?: return@withContext BpResult.NoSignal
            val f = BpEngine.extractFeatures(samples, PpgBuffer.sampleRateHz)
                ?: return@withContext BpResult.Error("Signal too weak — hold still and try again.")
            BpStore.saveCalibration(
                ctx,
                BpStore.Calibration(
                    systolic, diastolic, f.hr, f.upstrokeMs, f.amplitude, f.widthMs, System.currentTimeMillis(),
                ),
            )
            BpResult.Calibrated
        }
}

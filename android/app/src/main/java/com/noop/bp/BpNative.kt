package com.noop.bp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native (on-device) blood-pressure path: runs [BpEngine] over the local [PpgBuffer] window and keeps
 * the calibration + readings in [BpStore]. No network, no cloud — the PPG never leaves the phone.
 * Mirrors [BpClient]'s [BpClient.Result] contract so [Bp] can pick native or cloud from one toggle.
 */
object BpNative {
    /** Estimate BP from the most recent PPG window using the locally-stored cuff calibration. */
    suspend fun estimate(ctx: Context): BpClient.Result = withContext(Dispatchers.Default) {
        val samples = PpgBuffer.window(12) ?: return@withContext BpClient.Result.NoSignal
        val f = BpEngine.extractFeatures(samples, PpgBuffer.sampleRateHz)
            ?: return@withContext BpClient.Result.Error("Signal too weak — hold still and try again.")
        val cal = BpStore.calibration(ctx)
            ?: return@withContext BpClient.Result.Error("Not calibrated — enter a cuff reading first.")
        val e = BpEngine.estimate(cal, f)
        BpStore.saveReading(ctx, BpStore.Reading(e.systolic, e.diastolic, e.confidence, System.currentTimeMillis()))
        BpClient.Result.Estimate(e.systolic, e.diastolic, e.confidence)
    }

    /** Calibrate the local model: anchor a real cuff reading to the concurrent PPG window. */
    suspend fun calibrate(ctx: Context, systolic: Int, diastolic: Int): BpClient.Result =
        withContext(Dispatchers.Default) {
            val samples = PpgBuffer.window(12) ?: return@withContext BpClient.Result.NoSignal
            val f = BpEngine.extractFeatures(samples, PpgBuffer.sampleRateHz)
                ?: return@withContext BpClient.Result.Error("Signal too weak — hold still and try again.")
            BpStore.saveCalibration(
                ctx,
                BpStore.Calibration(
                    systolic, diastolic, f.hr, f.upstrokeMs, f.amplitude, f.widthMs, System.currentTimeMillis(),
                ),
            )
            BpClient.Result.Calibrated
        }
}

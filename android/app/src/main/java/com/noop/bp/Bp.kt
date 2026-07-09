package com.noop.bp

import android.content.Context

/**
 * Single entry point for blood pressure. Dispatches to the on-device model ([BpNative]) or the linked
 * noop-cloud ([BpClient]) per the [BpStore.Mode] toggle — Native by default (offline + private). The UI
 * ([com.noop.ui.BpScreen]) calls only this, so the compute location is one switch, not scattered logic.
 */
object Bp {
    suspend fun estimate(ctx: Context): BpClient.Result = when (BpStore.mode(ctx)) {
        BpStore.Mode.NATIVE -> BpNative.estimate(ctx)
        BpStore.Mode.CLOUD -> BpClient.estimate(ctx)
    }

    suspend fun calibrate(ctx: Context, systolic: Int, diastolic: Int): BpClient.Result =
        when (BpStore.mode(ctx)) {
            BpStore.Mode.NATIVE -> BpNative.calibrate(ctx, systolic, diastolic)
            BpStore.Mode.CLOUD -> BpClient.calibrate(ctx, systolic, diastolic)
        }
}

package com.noop.bp

import android.content.Context

/**
 * Piggybacks blood-pressure readings onto noop's normal strap sync. Whenever an offload lands fresh
 * optical PPG (see the [PpgBuffer] feed in `HistoricalStreams`), this computes and stores an estimate
 * from the freshest window — so the last-BP card / history stay current with **zero extra strap
 * traffic and zero battery cost**. It rides whatever poll cadence noop already uses (its periodic
 * offload, a manual sync, a reconnect), not a timer of its own.
 *
 * No-ops unless the user has calibrated and the window has enough clean signal, so it never fabricates
 * a reading. Runs on the offload thread; the only I/O is a small SharedPreferences write ([BpStore]).
 */
object BpAutoMeasure {
    @Volatile private var appContext: Context? = null

    /** Wire the application context once at startup (see `NoopApplication`). */
    fun init(ctx: Context) { appContext = ctx.applicationContext }

    /** Called right after a sync records fresh PPG. Stores a reading iff calibrated + signal is usable. */
    fun onSyncedPpg() {
        val ctx = appContext ?: return
        val cal = BpStore.calibration(ctx) ?: return
        val samples = PpgBuffer.window(12) ?: return
        val f = BpEngine.extractFeatures(samples, PpgBuffer.sampleRateHz) ?: return
        val e = BpEngine.estimate(cal, f)
        BpStore.saveReading(ctx, BpStore.Reading(e.systolic, e.diastolic, e.confidence, System.currentTimeMillis()))
    }
}

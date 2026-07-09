package com.noop.bp

import android.content.Context

/**
 * Local, on-device store for the blood-pressure feature: the per-user cuff calibration and the last
 * estimate. Plain SharedPreferences — a calibration + a BP reading are personal-health data but not
 * bearer secrets; they stay on the device and are never a credential.
 */
object BpStore {
    private const val FILE = "noop_bp"

    // calibration (single row)
    private const val K_CAL = "cal_present"
    private const val K_SYS = "cal_sys"
    private const val K_DIA = "cal_dia"
    private const val K_HR = "cal_hr"
    private const val K_UP = "cal_up"
    private const val K_AMP = "cal_amp"
    private const val K_WID = "cal_wid"
    private const val K_CAL_AT = "cal_at"
    private const val K_CAL_CALIBRATED = "cal_calibrated"  // true = real cuff anchor; false = population base

    // last reading
    private const val K_R_PRESENT = "r_present"
    private const val K_R_SYS = "r_sys"
    private const val K_R_DIA = "r_dia"
    private const val K_R_CONF = "r_conf"
    private const val K_R_AT = "r_at"

    data class Calibration(
        val systolic: Int,
        val diastolic: Int,
        val hr: Double,
        val upstrokeMs: Double,
        val amplitude: Double,
        val widthMs: Double,
        val calibratedAt: Long,
        /** true = anchored to a real cuff reading; false = population-normal base (uncalibrated). */
        val calibrated: Boolean = true,
    )

    data class Reading(val systolic: Int, val diastolic: Int, val confidence: Double, val at: Long)

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun calibration(ctx: Context): Calibration? {
        val p = prefs(ctx)
        if (!p.getBoolean(K_CAL, false)) return null
        return Calibration(
            p.getInt(K_SYS, 0), p.getInt(K_DIA, 0),
            p.getFloat(K_HR, 0f).toDouble(), p.getFloat(K_UP, 0f).toDouble(),
            p.getFloat(K_AMP, 0f).toDouble(), p.getFloat(K_WID, 0f).toDouble(),
            p.getLong(K_CAL_AT, 0L),
            p.getBoolean(K_CAL_CALIBRATED, true),
        )
    }

    fun saveCalibration(ctx: Context, c: Calibration) = prefs(ctx).edit()
        .putBoolean(K_CAL, true)
        .putInt(K_SYS, c.systolic).putInt(K_DIA, c.diastolic)
        .putFloat(K_HR, c.hr.toFloat()).putFloat(K_UP, c.upstrokeMs.toFloat())
        .putFloat(K_AMP, c.amplitude.toFloat()).putFloat(K_WID, c.widthMs.toFloat())
        .putLong(K_CAL_AT, c.calibratedAt)
        .putBoolean(K_CAL_CALIBRATED, c.calibrated)
        .apply()

    fun lastReading(ctx: Context): Reading? {
        val p = prefs(ctx)
        if (!p.getBoolean(K_R_PRESENT, false)) return null
        return Reading(
            p.getInt(K_R_SYS, 0), p.getInt(K_R_DIA, 0),
            p.getFloat(K_R_CONF, 0f).toDouble(), p.getLong(K_R_AT, 0L),
        )
    }

    fun saveReading(ctx: Context, r: Reading) = prefs(ctx).edit()
        .putBoolean(K_R_PRESENT, true)
        .putInt(K_R_SYS, r.systolic).putInt(K_R_DIA, r.diastolic)
        .putFloat(K_R_CONF, r.confidence.toFloat()).putLong(K_R_AT, r.at)
        .apply()
}

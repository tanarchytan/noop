package com.noop.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar

/**
 * The DAILY scheduled debug export (#510, maddognik).
 *
 * At the user's chosen time-of-day this writes the 24h rolling strap-log buffer ([StrapLogBuffer], via
 * [LogExport.writeScheduledExport]) — plus the raw 5/MG capture alongside — to the app-private export dir
 * under a `YYYYMMDD-HHMMSS` filename, once per day, with no UI. It exists so a reporter chasing an
 * intermittent fault (maddognik's use case: a strap that misbehaves overnight) gets a dated log waiting
 * each morning instead of having to remember to hit "Share strap log" at the right moment.
 *
 * SCHEDULING — WorkManager, not AlarmManager. The smart ALARM (#207) uses `AlarmManager.setAlarmClock`
 * because waking the user is safety-critical and must beat Doze to the exact second. A debug export is
 * the opposite: it's fine for it to slide a few minutes into a maintenance window, and it must survive
 * reboot/app-kill and never need the exact-alarm permission. That's exactly WorkManager's contract, so we
 * used a PeriodicWorkRequestBuilder with a one-day period and an initial delay computed to the next
 * occurrence of the chosen time. Enqueued as UNIQUE work (KEEP) so re-enabling or a reboot doesn't stack
 * duplicate daily exports.
 *
 * Everything is on-device; nothing is sent anywhere.
 *
 * DORMANT (noop-tan): the enable UI (the Test Centre "Export" card) was removed in the diagnostics
 * consolidation, so [reschedule] now only reconciles any prior schedule to OFF (see its doc). The worker,
 * [DebugExportSettings] (still read by the Test Centre bundle), and the [applyTimeChange]/[cancel] wrappers
 * are kept as a re-add scaffold; a full teardown would also unpick [LogExport.writeScheduledExport] +
 * [StrapLogBuffer], so it is left as a separate change.
 */
object DebugExportScheduler {

    /** Unique work name so every (re)schedule + cancel addresses the SAME daily job. */
    private const val WORK_NAME = "noop_debug_export_daily"

    /**
     * Reconcile the daily export on app start / settings change. The daily-export UI (the Test Centre
     * "Export" card) was removed, so there is no longer any way to ENABLE this feature — this now forces it
     * OFF: it clears any persisted enable flag and cancels the unique work, so an install that had it
     * enabled before this cleanup stops firing an export nobody can turn off. The worker + settings stay in
     * the tree as a dormant scaffold; if the feature is ever re-added, restore the enqueue path here (a
     * PeriodicWorkRequestBuilder + the [delayToNextOccurrenceMs] timing helper are kept for that). (noop-tan)
     */
    fun reschedule(context: Context, settings: DebugExportSettings = DebugExportSettings.from(context)) {
        if (settings.enabled) settings.enabled = false
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    /** Force a fresh schedule (cancel then enqueue) — used when the chosen time-of-day changes so the new
     *  time takes effect immediately rather than waiting out the old period. */
    fun applyTimeChange(context: Context, settings: DebugExportSettings = DebugExportSettings.from(context)) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        reschedule(context, settings)
    }

    /** Cancel the daily export entirely. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Milliseconds from [nowMs] until the next wall-clock occurrence of [minuteOfDay] (today if it's still
     * ahead, else tomorrow). Pure + injectable so the unit test pins the arithmetic without a real clock.
     */
    fun delayToNextOccurrenceMs(minuteOfDay: Int, nowMs: Long = System.currentTimeMillis()): Long {
        val next = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMs) add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - nowMs
    }
}

/**
 * The worker that performs one scheduled export. Reads the rolling buffer and writes the dated pair; it
 * deliberately does NOT touch the live BLE client (it may not exist when this runs hours after a sync) —
 * [LogExport.writeScheduledExport] sources the body from [StrapLogBuffer], which the UI keeps mirrored.
 * Always returns success so a transient write hiccup doesn't poison the periodic chain.
 */
class DebugExportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // No live log text reachable from here — pass empty and let the rolling buffer supply the body.
        LogExport.writeScheduledExport(applicationContext, logText = "")
        return Result.success()
    }
}

/**
 * Persisted, opt-in settings for the daily debug export (#510). Mirrors the [com.noop.alarm.SmartAlarmStore]
 * SharedPreferences shape: enable flag (default OFF — every NOOP automation is opt-in) + a time-of-day in
 * minutes since local midnight. The maintainer wires a toggle + time picker in Settings to these and calls
 * [DebugExportScheduler.applyTimeChange] / [DebugExportScheduler.reschedule] on change. Single-user, on-device.
 */
class DebugExportSettings(private val prefs: SharedPreferences) {

    /** Master enable. Default OFF. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Time-of-day to export, minutes since midnight. Clamped to a valid minute. Default 07:00. */
    var timeMinutes: Int
        get() = prefs.getInt(KEY_TIME, DEFAULT_TIME).coerceIn(0, MINUTES_PER_DAY - 1)
        set(v) = prefs.edit().putInt(KEY_TIME, v.coerceIn(0, MINUTES_PER_DAY - 1)).apply()

    companion object {
        private const val PREFS = "noop_debug_export"
        private const val KEY_ENABLED = "debugExport.enabled"
        private const val KEY_TIME = "debugExport.timeMinutes"

        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_TIME = 7 * 60   // 07:00 — a log waiting when you wake.

        fun from(context: Context): DebugExportSettings =
            DebugExportSettings(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

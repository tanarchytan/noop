package com.noop.alarm

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.noop.R
import com.noop.ui.appLaunchIntent
import java.util.Calendar

/**
 * The wind-down nudge — a gentle, NON-safety-critical evening local notification.
 *
 * Deliberately INEXACT: a missed wind-down nudge costs nothing, so we use a daily repeating inexact
 * alarm (no exact-alarm permission needed) rather than the privileged primitive the wake alarm uses.
 * The nudge minute is derived from the user's earliest wake time via [WindDownStore.nudgeMinuteOfDay].
 *
 * The fired notification is low-key (default importance, no full-screen, no DND bypass) — it's a
 * suggestion, not an alarm.
 */
object WindDownScheduler {

    private const val REQUEST_CODE = 7311
    const val ACTION_NUDGE = "com.noop.alarm.action.WIND_DOWN_NUDGE"
    const val CHANNEL_ID = "noop_wind_down"
    private const val NOTIF_ID = 4311

    /**
     * Schedule (or reschedule) the daily nudge at the minute derived from [wakeMinutes]. Cancels any
     * prior schedule first so a settings change doesn't stack two nudges. No-op'd by the caller when
     * the nudge is disabled (it calls [cancel] instead).
     */
    fun schedule(context: Context, store: WindDownStore, wakeMinutes: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = nudgePendingIntent(context)
        am.cancel(pi)
        val minuteOfDay = store.nudgeMinuteOfDay(wakeMinutes)
        val first = nextOccurrence(minuteOfDay)
        // Inexact, repeating, NOT wakeup — a wind-down reminder doesn't need to punch through Doze.
        am.setInexactRepeating(
            AlarmManager.RTC,
            first.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pi,
        )
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(nudgePendingIntent(context))
    }

    /** Raise the low-key nudge notification. Called from [WindDownReceiver]. */
    fun fireNotification(context: Context) {
        ensureChannel(context)
        runCatching {
            val open = PendingIntent.getActivity(
                context, 0, appLaunchIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle("Time to wind down")
                .setContentText("A calm hour now helps you hit your wake time well-rested.")
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, n)
        }
    }

    private fun nudgePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, WindDownReceiver::class.java).setAction(ACTION_NUDGE)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wind-down nudge", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "An optional evening reminder to start winding down before bed."
                    setShowBadge(false)
                },
            )
        }
    }

    private fun nextOccurrence(minuteOfDay: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
}

/** Receives the daily wind-down nudge alarm and raises the reminder notification. Inexact repeating
 *  alarms survive reboot on most OEMs, but we also re-schedule from [SmartAlarmBootReceiver] to be
 *  safe. Not exported. */
class WindDownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != WindDownScheduler.ACTION_NUDGE) return
        WindDownScheduler.fireNotification(context)
    }
}

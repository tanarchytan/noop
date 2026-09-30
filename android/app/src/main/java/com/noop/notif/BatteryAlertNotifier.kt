package com.noop.notif

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.R
import com.noop.ui.NoopPrefs
import com.noop.ui.appLaunchIntent

/**
 * Whether a strap NOBODY has heard from is worth warning about (#2556).
 *
 * [BatteryAlertPolicy] can only judge a percentage the app actually received, and both its crossings live
 * in the connection-service collector, so a strap that drains while disconnected crosses 15 and 12 unseen
 * and the wearer gets nothing. That is not hypothetical on an unbonded 5/MG, where a link can average under
 * two minutes.
 *
 * This reads the LAST BANKED reading instead, which is already durable in the `battery` table. It therefore
 * states something weaker and must say so: "last seen at 11 percent, six hours ago", never "your strap is at
 * 11 percent". The app has not seen the strap since; it does not know what it is at now.
 *
 * Deliberately silent while connected: the live crossings own that case, and two readouts of one fact must
 * not be able to disagree.
 *
 * Twin of Swift `StaleBatteryAlertPolicy`.
 */
internal object StaleBatteryAlertPolicy {
    /** How long out of contact before a low last reading is worth raising. */
    const val STALE_AFTER_SECONDS = 2L * 60L * 60L

    data class Decision(val fire: Boolean, val ageSeconds: Long)

    /**
     * How long ago, as a compact label: "6h", "3d".
     *
     * Deliberately plural-free. "6 hours ago" needs a plurals resource in every locale to avoid printing
     * "1 hours", and the age here is never below the two-hour window anyway, so the compact form carries
     * the same meaning for none of the cost. Shared with the Swift twin so the two notifications cannot
     * word the same fact differently.
     */
    fun ageLabel(seconds: Long): String {
        val hours = seconds / 3600L
        return if (hours >= 48L) "${hours / 24L}d" else "${hours}h"
    }

    private val NO = Decision(fire = false, ageSeconds = 0L)

    /**
     * [alertedForTs] is the reading this already fired for, persisted, so one stale value cannot re-notify
     * on every app open. It is keyed on the READING's timestamp rather than a boolean: a newer low reading
     * is a new fact and deserves its own alert.
     */
    fun evaluate(
        lastSocPct: Int?,
        lastTsSec: Long?,
        lastCharging: Boolean?,
        nowSec: Long,
        connected: Boolean,
        alertedForTs: Long?,
        lowThreshold: Int = BatteryAlertPolicy.LOW_THRESHOLD,
        staleAfterSeconds: Long = STALE_AFTER_SECONDS,
    ): Decision {
        if (connected) return NO
        if (lastSocPct == null || lastTsSec == null) return NO
        // Only a CONFIRMED charging reading suppresses, matching the live policy: unknown still warns.
        if (lastCharging == true) return NO
        if (lastSocPct > lowThreshold) return NO
        val age = nowSec - lastTsSec
        // A reading from the future is a clock problem, not a stale strap. Never warn on it.
        if (age < staleAfterSeconds) return NO
        if (alertedForTs == lastTsSec) return NO
        return Decision(fire = true, ageSeconds = age)
    }
}

/**
 * Pure battery-alert decision logic so it's JVM-testable (IllnessAlertPolicy idiom). The two
 * `*Alerted` flags are PERSISTED state (NoopPrefs), so the decision survives process death — no
 * in-memory previous-pct crossing, which would re-fire on every 15↔14 jitter and reset on restart.
 *
 * A 25% re-arm band (hysteresis) means a battery hovering near 15% fires the low alert exactly once
 * per discharge cycle; the full alert re-arms only after the cell drops back below 100%.
 */
internal object BatteryAlertPolicy {
    // All three are battery charge in percent.
    const val LOW_THRESHOLD = 15
    const val LOW_REARM_ABOVE = 25
    const val FULL_THRESHOLD = 100

    data class Decision(
        val fireLow: Boolean,
        val fireFull: Boolean,
        val clearFull: Boolean,
        val newLowAlerted: Boolean,
        val newFullAlerted: Boolean,
    )

    /**
     * @param pct          current strap battery percentage (rounded to Int)
     * @param charging     charging state (null = unknown)
     * @param lowAlerted   persisted: has the low alert already fired this discharge cycle?
     * @param fullAlerted  persisted: has the full alert already fired since the last drop below 100?
     *
     * `clearFull`: the strap was showing a "fully charged" notification and has now dropped
     * below 100% — the standing note is stale, so cancel it. It's exactly the full re-arm
     * transition (fullAlerted && pct < FULL_THRESHOLD), surfaced so the notifier can pull the
     * delivered full-charge notification by its id.
     */
    fun evaluate(pct: Int, charging: Boolean?, lowAlerted: Boolean, fullAlerted: Boolean): Decision {
        var low = lowAlerted
        var full = fullAlerted
        // The stale 100%-full note must be cleared the moment we re-arm below the full line.
        val clearFull = fullAlerted && pct < FULL_THRESHOLD
        // Re-arm (hysteresis) so jitter near a threshold can't re-fire. Re-arm ONLY on genuine recovery
        // (pct >= LOW_REARM_ABOVE), NOT on charging. The strap reports its charge bit only every ~8 min, so
        // it flickers true→null; re-arming on `true` then firing on the `null` gap re-fired the low alert
        // repeatedly WHILE charging. `fireLow`'s `charging != true` still suppresses an explicit charging
        // reading, and a null-charging strap (generic/FTMS) still alerts.
        if (pct >= LOW_REARM_ABOVE) low = false
        if (pct < FULL_THRESHOLD) full = false
        // Fire at most once per genuine crossing.
        val fireLow = !low && pct <= LOW_THRESHOLD && charging != true
        val fireFull = !full && pct >= FULL_THRESHOLD
        if (fireLow) low = true
        if (fireFull) full = true
        return Decision(fireLow, fireFull, clearFull, low, full)
    }
}

/**
 * Posts battery-state alerts — low battery (≤15%) and charge-complete (100%) — as real system
 * notifications. Mirrors [IllnessAlertNotifier]'s pattern: called from WhoopConnectionService on
 * every live-state update, gated behind a user setting and the OS notification permission. The
 * once-per-crossing dedupe lives in [BatteryAlertPolicy] over two persisted NoopPrefs flags.
 *
 * With thanks to @ujix for the original notification copy and channel.
 */
object BatteryAlertNotifier {
    private const val CHANNEL_ID = "noop_battery_alert"
    // each notifier posts under a DISTINCT id (notify() is tagless, so a shared id silently
    // replaces an undismissed notification). Full map: 4201 connection, 4202 illness, 4203 inactivity,
    // 4204 smart alarm, 4205/4206/4207 battery (runtime/low/full), 4208/4209 scheduled report.
    private const val NOTIF_ID_RUNTIME = 4205
    private const val NOTIF_ID_LOW = 4206
    private const val NOTIF_ID_FULL = 4207
    private const val NOTIF_ID_STALE = 4213

    /**
     * Predictive twin of [onBatteryUpdate]: run the runtime estimate against
     * [com.noop.analytics.BatteryEstimator.runtimeAlert] (fire ≤24 h, re-arm ≥36 h — a runtime
     * threshold gives the same warning lead time on a 4.0 and a 5.0/MG, which a fixed SoC line
     * can't) and post at most one notification per discharge cycle. The 15% SoC alert stays as the
     * safety net for straps with no usable estimate (null skips here). Same gating discipline as
     * persisted flag advances even when delivery is deferred; no-ops when battery alerts are
     * off. iOS/macOS twin: BatteryNotifier.onRuntimeEstimate.
     */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    fun onRuntimeEstimate(context: Context, remainingHours: Double?, charging: Boolean?) {
        if (remainingHours == null) return
        if (!NoopPrefs.batteryAlerts(context)) return
        if (!NoopPrefs.predictiveBatteryAlerts(context)) return
        runCatching {
            val decision = com.noop.analytics.BatteryEstimator.runtimeAlert(
                remainingHours = remainingHours,
                charging = charging,
                alerted = NoopPrefs.batteryRuntimeAlerted(context),
            )
            // ALWAYS persist the updated gate — re-arming must stick even when nothing fired.
            NoopPrefs.setBatteryRuntimeAlerted(context, decision.newAlerted)
            if (!decision.fire) return
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val label = com.noop.analytics.BatteryEstimator.label(remainingHours)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle("Strap battery low")
                .setContentText("$label left on your WHOOP — recharge tonight.")
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID_RUNTIME, n)
        }
    }

    /**
     * Warn that a strap last seen LOW has not been heard from since (#2556).
     *
     * The live crossings need a connection, so a strap that drains out of range is never judged at all.
     * This reads the last banked reading instead, which is why the copy says "last seen at" rather than
     * naming a current percentage: the app has not seen the strap since and does not know what it is at now.
     *
     * Shares `batteryAlerts` with the live alerts, so turning battery alerts off silences this too.
     */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    fun onStrapNotSeen(
        context: Context,
        lastSocPct: Int?,
        lastTsSec: Long?,
        lastCharging: Boolean?,
        nowSec: Long,
        connected: Boolean,
    ) {
        if (!NoopPrefs.batteryAlerts(context)) return
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val decision = StaleBatteryAlertPolicy.evaluate(
                lastSocPct = lastSocPct,
                lastTsSec = lastTsSec,
                lastCharging = lastCharging,
                nowSec = nowSec,
                connected = connected,
                alertedForTs = NoopPrefs.batteryStaleAlertedTs(context),
            )
            if (!decision.fire || lastSocPct == null || lastTsSec == null) return
            ensureChannel(context)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle("WHOOP last seen low")
                .setContentText(
                    "$lastSocPct% when NOOP last heard from it, " +
                        "${StaleBatteryAlertPolicy.ageLabel(decision.ageSeconds)} ago. Charge it before tonight.",
                )
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIF_ID_STALE, n)
            // Persist AFTER posting, keyed on the reading, so a failed notify retries next open.
            NoopPrefs.setBatteryStaleAlertedTs(context, lastTsSec)
        }
    }

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    fun onBatteryUpdate(context: Context, currPct: Int?, charging: Boolean?) {
        if (currPct == null) return
        if (!NoopPrefs.batteryAlerts(context)) return
        // Defensive: never let a notify() throw (revoked POST_NOTIFICATIONS, OEM quirk) crash a collector.
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)
            val decision = BatteryAlertPolicy.evaluate(
                pct = currPct,
                charging = charging,
                lowAlerted = NoopPrefs.batteryLowAlerted(context),
                fullAlerted = NoopPrefs.batteryFullAlerted(context),
            )
            if (decision.fireLow) {
                val n = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_heart)
                    .setContentTitle("Low battery")
                    .setContentText("Recharge your WHOOP before tonight.")
                    .setContentIntent(openAppIntent(context))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build()
                NotificationManagerCompat.from(context).notify(NOTIF_ID_LOW, n)
            }
            if (decision.fireFull) {
                val n = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_heart)
                    .setContentTitle("Strap fully charged")
                    .setContentText("Your WHOOP is at 100%.")
                    .setContentIntent(openAppIntent(context))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                NotificationManagerCompat.from(context).notify(NOTIF_ID_FULL, n)
            }
            // the strap has dropped below 100% — pull the stale "fully charged" note so it
            // can't linger after the cell discharges. cancel() covers a posted notification; a
            // not-yet-shown one simply no-ops.
            if (decision.clearFull) {
                NotificationManagerCompat.from(context).cancel(NOTIF_ID_FULL)
            }
            // ALWAYS persist the updated flags — re-arming must stick even when nothing fired.
            NoopPrefs.setBatteryLowAlerted(context, decision.newLowAlerted)
            NoopPrefs.setBatteryFullAlerted(context, decision.newFullAlerted)
        }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 3,
            appLaunchIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Battery alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Alerts when the strap battery is low or fully charged."
                },
            )
        }
    }
}

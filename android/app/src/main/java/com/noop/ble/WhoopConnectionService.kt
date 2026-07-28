package com.noop.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.noop.NoopApplication
import com.noop.R
import com.noop.alarm.SleepWindowWatcher
import com.noop.alarm.SmartAlarmScheduler
import com.noop.alarm.SmartAlarmStore
import com.noop.analytics.BatteryEstimator
import com.noop.analytics.IllnessWatch
import com.noop.analytics.RestScorer
import com.noop.data.DailyMetric
import com.noop.location.GpsSession
import com.noop.location.LocationTracker
import com.noop.notif.BatteryAlertNotifier
import com.noop.notif.IllnessAlertNotifier
import com.noop.ui.NoopPrefs
import com.noop.ui.appLaunchIntent
import com.noop.widget.WidgetSnapshot
import com.noop.widget.WidgetSnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One tick of the ongoing-notification/widget stream. [todayRow] is the unscored today row the
 * notification reads (honest-null until scored); [anchorRow] is the widget-only carried anchor
 * (today if scored, else the latest prior scored day), so the widget matches Today's day.
 */
private data class NotifyTick(
    val state: LiveState,
    val todayRow: DailyMetric?,
    val anchorRow: DailyMetric?,
    val illness: String?,
)

/**
 * Foreground service that keeps the WHOOP BLE connection alive while the app is backgrounded or
 * closed. Android tears the process down shortly after the last Activity goes away; a started
 * foreground service with an ongoing notification keeps the process — and the
 * [com.noop.NoopApplication]-owned [WhoopBleClient]'s GATT link — resident, so heart rate keeps
 * streaming and offloads keep landing in the background.
 *
 * Does not own or drive the connection, only holds the process up and mirrors the client's
 * [LiveState] into the notification. Start/stop is gated by `NoopPrefs.backgroundConnection` and
 * only ever runs from the foreground, so it never trips Android 12+'s background-start restriction.
 */
class WhoopConnectionService : Service() {

    /** Main-thread scope used only to mirror [LiveState] into the notification. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The single live-state→notification collector. Re-`start`s land here repeatedly (on every
     *  connect, plus any OS restart), so we cancel the old one before launching a new one. */
    private var notifyJob: Job? = null

    /** Watches [GpsSession] and runs the platform location stream while a GPS workout is active. Runs
     *  on the always-on service, not the Activity-scoped ViewModel Android cancels when the screen
     *  turns off, so route tracking survives it. */
    private var gpsGateJob: Job? = null

    /** The actual location collector, alive only while a GPS workout is in flight. Cancelled (which
     *  removes the LocationManager updates via the stream's awaitClose) the moment the workout ends. */
    private var gpsJob: Job? = null

    /** Platform-GPS wrapper (no Google Play Services). Lazily built — the service holds a Context. */
    private val locationTracker by lazy { LocationTracker(this) }

    /** Last illness-watch evaluation seen by the collector — clear→raised is the notify edge.
     *  In-memory on purpose: the persisted once-a-day gate (NoopPrefs) handles dedupe across
     *  process restarts and the AppViewModel call site. */
    private var lastIllnessAlert: String? = null

    /** Last battery % the predictive runtime alert was evaluated at. The live-state flow emits far
     *  more often than the strap's ~8-min battery cadence; gating the Room read + estimator fit on an
     *  actual SoC change keeps the predictive path as cheap as the SoC-only alert beside it. */
    private var lastRuntimeEvalPct: Int? = null

    /** Smart-alarm light-sleep watcher, reset each time the wake window is (re)entered. Feeds live HR
     *  inside the window and, on a lighter-phase reading, advances the alarm earlier only — the hard
     *  AlarmManager deadline is the floor, so a BLE drop or no light sleep still wakes at window end. */
    private val sleepWatcher = SleepWindowWatcher()
    private var inAlarmWindow = false

    /** The smart-alarm HR collector, alive for the life of the service. */
    private var alarmJob: Job? = null

    private val ble get() = (application as NoopApplication).ble
    private val repo get() = (application as NoopApplication).repository

    /**
     * Watches the OS Bluetooth radio so turning it off tears down the GATT link at once: without
     * this, [WhoopBleClient] never learns the radio died, stays "connected", and the next write
     * crashes on a dead binder. STATE_TURNING_OFF/OFF tears down; STATE_ON resumes.
     */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                // Catch TURNING_OFF (the earliest signal) AND OFF — by TURNING_OFF the binder is already
                // on its way down, so tearing down here pre-empts the crash window.
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> ble.onBluetoothRadioOff()
                BluetoothAdapter.STATE_ON -> ble.onBluetoothRadioOn()
            }
        }
    }

    /** True once [bluetoothStateReceiver] is registered, so repeat onStartCommands don't double-register
     *  (which would later throw on a single unregister). */
    private var bluetoothReceiverRegistered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification "Disconnect" action routes back here as a self-intent.
        if (intent?.action == ACTION_STOP) {
            runCatching { ble.disconnect() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        // Must call startForeground promptly after startForegroundService(). If it fails (e.g. the
        // API 34 connectedDevice type needs BLUETOOTH_CONNECT and the user denied it) we stop cleanly
        // rather than crash — the connection itself keeps working in the foreground regardless.
        if (!startForegroundCompat(buildNotification(ble.state.value, null))) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Listen for the OS Bluetooth radio toggling so turning it off tears the link down at once.
        // Guarded so repeat onStartCommands (every connect / OS restart) don't stack registrations.
        if (!bluetoothReceiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    this,
                    bluetoothStateReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }.onSuccess { bluetoothReceiverRegistered = true }
        }

        // Keep the ongoing notification in step with the live connection state and today's recovery (the
        // 15-min IntelligenceEngine recompute), so it re-posts when either changes. Reads the same merged
        // store the dashboard uses.
        notifyJob?.cancel()
        notifyJob = scope.launch {
            combine(
                ble.state,
                // Defence-in-depth: if this flow errors, catch{emit} keeps combine running on ble.state
                // with days frozen rather than killing the process. The bounded merge suffices since the
                // notification reads only today's row, not the full re-merged history.
                repo.recentDaysMergedFlow("my-whoop").catch { emit(emptyList()) },
            ) { state, days ->
                // Resolve the day the way the dashboard does, via the logical local day (rolls at 04:00),
                // not a naive LocalDate.now() that rolls at midnight and looks up a not-yet-scored day.
                // Two distinct rows come out, so each surface keeps its own honest contract.
                val logicalKey = com.noop.ui.logicalDayKeyNow()
                val localKey = java.time.LocalDate.now().toString()
                // todayRow is the naive/unscored today row: Recovery stays honest-null until scored so
                // the lock screen never shows a stale carried figure as live. anchorRow is today's row
                // once scored, else the latest prior scored day; only the widget reads it, matching Today.
                val todayRow = com.noop.ui.resolveTodayRow(days, logicalKey, localKey)
                val anchorRow = com.noop.ui.widgetAnchorRow(days, logicalKey, localKey)
                NotifyTick(
                    state = state,
                    todayRow = todayRow,
                    anchorRow = anchorRow,
                    // Illness watch in the background (gated on the opt-out pref): the FGS is the
                    // only long-lived collector, so this is what makes the early-warning reach a
                    // user who hasn't opened the app today.
                    illness = if (NoopPrefs.illnessWatch(this@WhoopConnectionService)) IllnessWatch.evaluate(days) else null,
                )
            }.catch { /* belt-and-braces: a frozen notification beats a dead process */ }
                // conflate + collect, not collectLatest: the widget push suspends in Glance machinery
                // longer than the live-HR emission interval, so collectLatest would cancel every push
                // mid-flight and starve the widget on stale data. Conflation still keeps only the latest value.
                .conflate()
                .collect { (state, todayRow, anchorRow, illness) ->
                // Honest-null: the notification's Recovery line reads the naive today row, never the
                // carried anchor, so it stays blank until tonight's recovery actually lands.
                postNotification(state, todayRow?.recovery)
                // Banner transition (clear → raised) → real system notification; the notifier's
                // persisted day gate dedupes against the app-open (AppViewModel) call site.
                if (lastIllnessAlert == null && illness != null) {
                    IllnessAlertNotifier.onEvaluated(this@WhoopConnectionService, illness)
                }
                lastIllnessAlert = illness
                // Battery alerts — low (≤15%) and charge-complete (100%). The once-per-crossing
                // dedupe is persisted in NoopPrefs (BatteryAlertPolicy), so no in-memory pct tracking.
                BatteryAlertNotifier.onBatteryUpdate(
                    this@WhoopConnectionService,
                    currPct = state.batteryPct?.roundToInt(),
                    charging = state.charging,
                )
                // Predictive runtime alert: re-fits the "~X left" estimate from persisted SoC samples and
                // warns at <=24h remaining, evaluated only when battery % changes (~8-min cadence) so the
                // Room read + slope fit skips redundant emissions, using the same inputs as the Today badge so the two can't disagree.
                val runtimePct = state.batteryPct?.roundToInt()
                if (runtimePct != null && runtimePct != lastRuntimeEvalPct) {
                    lastRuntimeEvalPct = runtimePct
                    runCatching {
                        val nowS = System.currentTimeMillis() / 1000
                        val samples = repo.batterySamples("my-whoop", nowS - 14L * 86_400, nowS, limit = 2_000)
                            .mapNotNull { s -> s.soc?.let { s.ts to it } }
                        val rated = if (state.whoop5Detected) BatteryEstimator.ratedLifeHoursWhoop5
                                    else BatteryEstimator.ratedLifeHoursWhoop4
                        BatteryAlertNotifier.onRuntimeEstimate(
                            this@WhoopConnectionService,
                            remainingHours = BatteryEstimator.estimate(samples, rated)?.hoursRemaining,
                            charging = state.charging,
                        )
                    }
                }
                // Feed the home-screen widget from the same stream — this service is its heartbeat
                // while the app UI is closed. Throttled + no-op without a placed widget (the store
                // checks both); runCatching so a Glance hiccup never tears down the connection.
                runCatching {
                    WidgetSnapshotStore.push(
                        this@WhoopConnectionService,
                        WidgetSnapshot(
                            recoveryPct = anchorRow?.recovery?.roundToInt(),
                            // Rest = the sleep_performance composite from the anchor row's banked stage
                            // figures (honest-null until last night is scored); Effort = the 0-100 strain.
                            // Widget-only carry, so it shows the same day as Today.
                            restPct = anchorRow?.let { RestScorer.restFromDaily(it)?.roundToInt() },
                            effortPct = anchorRow?.strain?.roundToInt(),
                            heartRate = state.heartRate,
                            batteryPct = state.batteryPct?.roundToInt(),
                            connected = state.connected,
                            updatedAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }

        // Drive GPS route tracking from here so it outlives the UI. While a workout is active, collect
        // the platform location stream into the process-level [GpsSession]; the ViewModel only observes
        // that shared route, gated on the active flag; re-`start`s cancel + relaunch rather than stacking.
        gpsGateJob?.cancel()
        gpsGateJob = scope.launch {
            GpsSession.state
                .map { it.active }
                .distinctUntilChanged()
                .collect { active ->
                    gpsJob?.cancel()
                    gpsJob = null
                    if (active) {
                        // Re-post with the location service type added so background location is permitted
                        // while tracking; Android 14+ requires a service reading location in the background
                        // to declare the location FGS type. Reverts to connectedDevice-only when the workout ends.
                        startForegroundCompat(buildNotification(ble.state.value, null), tracking = true)
                        // Workouts & GPS test mode (Test Centre): wire the GpsSession fix-progress sink to the
                        // .workouts-tagged strap log ONLY when the WORKOUTS mode is on (one SharedPreferences
                        // bool read here). When off, the sink stays null and the route fold is byte-identical.
                        GpsSession.workoutsLog =
                            if (com.noop.testcentre.TestCentre.from(applicationContext)
                                    .active(com.noop.testcentre.TestDomain.WORKOUTS)
                            ) {
                                { line -> ble.externalLog(line, com.noop.testcentre.TestDomain.WORKOUTS) }
                            } else {
                                null
                            }
                        gpsJob = launch {
                            // LocationTracker fails SAFE (no permission / no provider just ends the
                            // stream); runCatching guards an OEM throw so it can't tear down the FGS.
                            runCatching {
                                locationTracker.stream().collect { pt -> GpsSession.append(pt) }
                            }
                        }
                    } else {
                        GpsSession.workoutsLog = null   // route finished: drop the test-mode sink
                        startForegroundCompat(buildNotification(ble.state.value, null), tracking = false)
                    }
                }
        }

        // Smart-alarm light-sleep watcher. While enabled and inside the wake window, feed live HR to
        // the detector; on a lighter-phase reading, advance the alarm earlier only, since the scheduler
        // clamps to the window and the hard AlarmManager deadline still fires if this service isn't running.
        alarmJob?.cancel()
        alarmJob = scope.launch {
            val store = SmartAlarmStore.from(this@WhoopConnectionService)
            ble.state
                .map { it.heartRate ?: 0 }
                .conflate()
                .collect { hr ->
                    if (!store.enabled || store.scheduledDeadlineMs <= 0L) {
                        inAlarmWindow = false
                        return@collect
                    }
                    val now = System.currentTimeMillis()
                    val inWindow = now in store.scheduledWindowStartMs until store.scheduledDeadlineMs
                    if (inWindow && !inAlarmWindow) sleepWatcher.reset()   // fresh night
                    inAlarmWindow = inWindow
                    if (!inWindow) return@collect
                    if (sleepWatcher.shouldWake(hr)) {
                        SmartAlarmScheduler.advanceTo(this@WhoopConnectionService, store, now)
                    }
                }
        }

        // START_NOT_STICKY: the FGS's job is to keep this process alive while running, not to
        // resurrect after a kill — a fresh process has no strap/model context to reconnect with, so
        // resurrecting would only show a "Reconnecting…" notification that never resolves.
        return START_NOT_STICKY
    }

    /** Promote to the foreground. Returns false (rather than throwing) if the platform refuses. When
     *  [tracking] a GPS workout we add the location FGS type — Android 14+ requires it for a service
     *  that reads location in the background (the manifest declares `connectedDevice|location`). */
    private fun startForegroundCompat(notification: Notification, tracking: Boolean = false): Boolean = runCatching {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val locationType = if (tracking) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or locationType
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }.isSuccess

    /** Signature of the fields the notification actually renders. The live HR stream emits ~1 Hz but
     *  the notification no longer shows BPM, so we only re-post when one of these changes, turning a
     *  per-beat wakeup into a handful of updates a day. */
    private var lastNotificationKey: String? = null

    private fun postNotification(state: LiveState, recoveryPct: Double? = null) {
        val key = listOf(
            state.connected,
            state.backfilling,
            recoveryPct?.roundToInt(),
            state.batteryPct?.roundToInt(),
        ).joinToString("|")
        if (key == lastNotificationKey) return
        lastNotificationKey = key
        // Defensive: a notify() throw (OEM quirk, revoked POST_NOTIFICATIONS on some ROMs) must not
        // crash the collector and tear down the connection we exist to keep alive.
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildNotification(state, recoveryPct))
        }
    }

    private fun buildNotification(state: LiveState, recoveryPct: Double?): Notification {
        // Deliberately no live BPM in the title: a per-beat-changing notification forces the foreground
        // service to re-post (and wake the device) ~once a second, a real battery cost for a number
        // nobody reads off the lock screen. The title reflects only the connection/sync state.
        val title = when {
            !state.connected   -> "Reconnecting to your WHOOP…"
            state.backfilling  -> "Syncing strap history…"
            else               -> "Connected to your WHOOP"
        }
        val detail = buildList {
            add(if (state.connected) "Streaming in the background" else "Keeping the link open")
            recoveryPct?.let { add("Recovery ${it.roundToInt()}%") }
            state.batteryPct?.let { add("Strap ${it.roundToInt()}%") }
        }.joinToString("  ·  ")

        val openApp = PendingIntent.getActivity(
            this,
            0,
            appLaunchIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = PendingIntent.getService(
            this,
            1,
            Intent(this, WhoopConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openApp)
            .addAction(0, "Disconnect", stopAction)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Defensive: channel creation can throw on some OEM ROMs / under memory pressure; never let
        // that crash onStartCommand (it would take the FGS — and the connection — down with it).
        runCatching {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Strap connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while NOOP keeps your WHOOP connected in the background."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (bluetoothReceiverRegistered) {
            // unregisterReceiver throws if it was never registered; the flag guards that, and runCatching
            // covers the rare case the OS already reclaimed it.
            runCatching { unregisterReceiver(bluetoothStateReceiver) }
            bluetoothReceiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "noop_strap_connection"
        private const val NOTIF_ID = 4201
        const val ACTION_STOP = "com.noop.ble.action.STOP_CONNECTION"

        /**
         * Promote the process to the foreground so the strap stays connected. Safe to call when
         * already running; must be called from a foreground context to satisfy Android 12+'s
         * background-start rule. Any failure is swallowed so it can never break the connect flow.
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WhoopConnectionService::class.java),
                )
            }
        }

        /** Drop the foreground promotion. The connection itself is torn down by the caller. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, WhoopConnectionService::class.java)) }
        }
    }
}

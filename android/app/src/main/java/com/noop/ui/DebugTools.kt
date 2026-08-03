package com.noop.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.BuildConfig
import com.noop.analytics.CalendarDay
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.ingest.HistoryRecordSink
import com.noop.ingest.RawSensorExport
import com.noop.testcentre.ReportReviewGate
import com.noop.testcentre.TestBundleAssembler
import com.noop.testcentre.TestBundleMeta
import com.noop.testcentre.TestDomain
import java.io.File
import java.util.Calendar

// MARK: - Debug export
//
// Everything the Debug screen ships out, in one place, because the three pieces only make sense
// together: [StrapLogBuffer] is the durable 24h tail of the strap log, [DebugBundle] is the manual
// "Full debug export" the user taps, and [DebugExportScheduler] is the unattended daily one that reads
// the buffer back from a WorkManager worker with no live BLE client to hand. Assembly and scheduling
// live here so TestCentreScreen stays presentation only.

/**
 * A process-wide, time-bounded ring buffer of strap-log lines for the SCHEDULED debug export.
 *
 * WHY this is separate from [com.noop.ble.WhoopBleClient]'s own `logBuffer`: that one lives on the live
 * BLE client instance, which is owned by the ViewModel / foreground service and is NOT reachable from a
 * background [androidx.work.Worker] running hours later (possibly after the client was torn down). The
 * scheduled daily export needs its OWN durable, independently-addressable tail of the log, so [LogExport]
 * mirrors every line it ships into here and [DebugExportScheduler]'s worker reads it back with no live
 * BLE dependency.
 *
 * BOUNDED two ways, whichever bites first, so it can never grow without limit:
 *   • a hard cap of [MAX_LINES] entries (matches the client's 5000-line cap), AND
 *   • a ~24h rolling window — lines older than [RETENTION_MS] are dropped on every append/read.
 *
 * Each entry carries the wall-clock epoch it was appended so the time window can be enforced without
 * parsing the line text. Lines are stored already-redacted (LogExport appends the client's
 * `exportLogText()`, which is PII-scrubbed at source), so nothing un-redacted ever lands here.
 *
 * All access is synchronized: appends arrive from the UI thread (LogExport) and reads from a WorkManager
 * background thread (the scheduler). Pure JVM logic — no Android types — so it unit-tests directly.
 */
object StrapLogBuffer {

    /** One retained line: the wall-clock epoch (ms) it was recorded, plus the already-redacted text. */
    private data class Entry(val tsMs: Long, val line: String)

    private val lines = ArrayDeque<Entry>()

    /**
     * Append [text] to the rolling buffer, splitting on newlines so multi-line blobs (e.g. the whole
     * `exportLogText()` snapshot) become individually-aged entries. Blank input is ignored. Each line is
     * stamped with [nowMs] and the buffer is trimmed afterwards so it stays inside both bounds.
     *
     * [nowMs] is injectable purely so the unit test can age entries deterministically; production callers
     * use the default wall clock.
     */
    @Synchronized
    fun append(text: String, nowMs: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        for (raw in text.split('\n')) {
            // Keep blank interior lines that sit between real content (formatting), but skip a trailing
            // empty split so "a\n" doesn't bank a phantom line.
            lines.addLast(Entry(nowMs, raw))
        }
        trim(nowMs)
    }

    /**
     * Replace the buffer's contents with a fresh snapshot (newest last). Used when LogExport ships the
     * client's full `exportLogText()` tail: the client already holds the authoritative recent window, so
     * mirroring is a REPLACE (not an append) to avoid duplicating the overlap on every export. Still
     * bounded + aged on the way in.
     */
    @Synchronized
    fun replaceWith(text: String, nowMs: Long = System.currentTimeMillis()) {
        lines.clear()
        append(text, nowMs)
    }

    /** Newest-last snapshot of the retained lines as a single string, aged to the window first. Empty
     *  string when nothing is retained. */
    @Synchronized
    fun snapshot(nowMs: Long = System.currentTimeMillis()): String {
        trim(nowMs)
        return lines.joinToString("\n") { it.line }
    }

    /** Current retained line count (after aging) — for tests + diagnostics. */
    @Synchronized
    fun size(nowMs: Long = System.currentTimeMillis()): Int {
        trim(nowMs)
        return lines.size
    }

    /** Drop everything (used by tests; never needed in production). */
    @Synchronized
    fun clear() = lines.clear()

    /** Enforce BOTH bounds: drop anything older than the retention window, then cap the line count. */
    private fun trim(nowMs: Long) {
        val cutoff = nowMs - RETENTION_MS
        while (lines.isNotEmpty() && lines.first().tsMs < cutoff) lines.removeFirst()
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    /** ~24h rolling window. */
    const val RETENTION_MS: Long = 24L * 60L * 60L * 1000L

    /** Hard line cap, mirroring WhoopBleClient.LOG_BUFFER_MAX so the two logs hold a comparable tail. */
    const val MAX_LINES = 5000
}

/**
 * Gathers the Debug screen's "Full debug export": the strap log and diagnostics inline, plus every
 * capture file the debug switch writes and the decoded-sensor CSV as streamed attachments. Assembly and
 * sharing live here so [TestCentreScreen] stays presentation only.
 */
object DebugBundle {

    /** One in-zip name per capture file the debug switch writes, newest generation last. */
    private val CAPTURE_FILES: List<Pair<String, String>> = listOf(
        "raw-frames.prev.jsonl" to "${WhoopBleClient.WHOOP5_CAPTURE_FILE}.1",
        "raw-frames.jsonl" to WhoopBleClient.WHOOP5_CAPTURE_FILE,
        "records.prev.jsonl" to HistoryRecordSink.PREV_FILE,
        "records.jsonl" to HistoryRecordSink.FILE,
        "events.prev.jsonl" to "${WhoopBleClient.WHOOP5_EVENT_LOG_FILE}.1",
        "events.jsonl" to WhoopBleClient.WHOOP5_EVENT_LOG_FILE,
        "deep-buffers.prev.jsonl" to "${WhoopBleClient.WHOOP5_DEEPBUFFER_FILE}.1",
        "deep-buffers.jsonl" to WhoopBleClient.WHOOP5_DEEPBUFFER_FILE,
    )

    /** The name the sensor CSV takes inside the bundle. */
    private const val SENSOR_CSV_ENTRY = "sensors.csv"

    /**
     * A bundle staged for the review gate: [inline] is already redacted, [streams] are scrubbed line by
     * line as they copy into the zip, and [gate] holds both so the review names every attachment.
     */
    class Staged(
        val inline: List<Pair<String, ByteArray>>,
        val streams: List<Pair<String, File>>,
        val gate: ReportReviewGate,
    )

    /**
     * The capture files that exist in [dir], as (in-zip name, file). Absent files are dropped rather than
     * shipped empty, so the bundle lists only what was really recorded.
     */
    fun captureStreams(dir: File): List<Pair<String, File>> =
        CAPTURE_FILES.map { (entry, name) -> entry to File(dir, name) }
            .filter { it.second.exists() && it.second.length() > 0L }

    /**
     * Assemble the full export. Reads the store (row counts, the sensor CSV), so callers run it off the
     * main thread. The log text comes from the live BLE client, which only the caller can reach.
     */
    suspend fun stage(
        context: Context,
        repo: WhoopRepository,
        logText: String,
        strapId: String,
    ): Staged {
        val inline = TestBundleAssembler.assemble(
            context,
            TestDomain.MASTER,
            logText,
            storageProbe(context, repo),
            strapModel(context),
        )
        val streams = ArrayList(captureStreams(context.filesDir))
        RawSensorExport.writeFile(context, repo, strapId)?.let { csv ->
            if (csv.length() > 0L) streams.add(SENSOR_CSV_ENTRY to csv)
        }
        val sizes = streams.map { (entry, file) -> entry to file.length() }
        return Staged(inline, streams, ReportReviewGate(inline, sizes))
    }

    /** Share the staged bundle once the gate is cleared. A cleared gate is the only path to a share. */
    fun share(context: Context, staged: Staged): File? {
        if (!staged.gate.isCleared) return null
        val name = LogExport.bundleName("debug", "android", BuildConfig.VERSION_NAME)
        return LogExport.shareBundle(context, name, staged.inline, staged.streams)
    }

    /**
     * The real storage figures for meta.json: the Room store on disk (with its -wal/-shm sidecars), the
     * per-table row counts, and the capture files' own footprint. A probe that reads nothing returns null
     * so meta keeps its honest zeroed block rather than a made-up figure.
     */
    private suspend fun storageProbe(context: Context, repo: WhoopRepository): TestBundleMeta.Storage? {
        val dbPath = context.getDatabasePath(WhoopDatabase.DB_NAME)
        var dbBytes = 0L
        for (suffix in listOf("", "-wal", "-shm")) {
            val f = File(dbPath.path + suffix)
            if (f.exists()) dbBytes += f.length()
        }
        val rows = repo.storageRowCounts()
        val rawBytes = captureStreams(context.filesDir).sumOf { it.second.length() }
        if (dbBytes <= 0L && rows.isEmpty() && rawBytes <= 0L) return null
        return TestBundleMeta.Storage(
            dbBytes = dbBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            rows = rows,
            rawCaptureBytes = rawBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }

    /** The connected strap's display name: the scan/connect path persists the detected family to this pref. */
    private fun strapModel(context: Context): String? =
        NoopPrefs.of(context).getString("noop.selectedWhoopModel", null)
            ?.let { name -> runCatching { WhoopModel.valueOf(name).displayName }.getOrNull() }
}

/**
 * The DAILY scheduled debug export (maddognik).
 *
 * At the user's chosen time-of-day this writes the 24h rolling strap-log buffer ([StrapLogBuffer], via
 * [LogExport.writeScheduledExport]) — plus the raw 5/MG capture alongside — to the app-private export dir
 * under a `YYYYMMDD-HHMMSS` filename, once per day, with no UI. It exists so a reporter chasing an
 * intermittent fault (maddognik's use case: a strap that misbehaves overnight) gets a dated log waiting
 * each morning instead of having to remember to hit "Share strap log" at the right moment.
 *
 * SCHEDULING — WorkManager, not AlarmManager. The smart ALARM uses `AlarmManager.setAlarmClock`
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
 * Persisted, opt-in settings for the daily debug export. Mirrors the [com.noop.alarm.SmartAlarmStore]
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
        get() = prefs.getInt(KEY_TIME, DEFAULT_TIME).coerceIn(0, CalendarDay.MINUTES_PER_DAY - 1)
        set(v) = prefs.edit().putInt(KEY_TIME, v.coerceIn(0, CalendarDay.MINUTES_PER_DAY - 1)).apply()

    companion object {
        private const val PREFS = "noop_debug_export"
        private const val KEY_ENABLED = "debugExport.enabled"
        private const val KEY_TIME = "debugExport.timeMinutes"

        const val DEFAULT_TIME = 7 * 60   // 07:00 — a log waiting when you wake.

        fun from(context: Context): DebugExportSettings =
            DebugExportSettings(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

package com.noop.ui

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.data.DataBackup
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Backup & Sync (Phase 1 - folder destination). Writes the full `.noopbak` snapshot (the existing
 * [DataBackup] whole-DB format) into a user-chosen folder (a SAF tree), on demand and on an opt-in
 * daily schedule. Point that folder at a desktop Google Drive / Dropbox sync client (or a phone sync
 * app) and you get off-device backup with NO in-app cloud account, no OAuth, no secrets - NOOP only
 * ever writes a local file; the user's own sync client does any upload.
 *
 * DESIGN
 * - Snapshots are timestamped and immutable. "Restore" REPLACES the live DB (whole-DB snapshot,
 *   newest-wins), exactly as [DataBackup.importFrom] already does - we add nothing to the restore
 *   safety path (magic-byte + Room/GRDB-origin validation, sidecar snapshot, rollback-on-failure).
 * - The pure filename/selection helpers are unit-tested byte-for-byte against the Apple twin so a
 *   `.noopbak` produced on either platform is named + selected identically.
 * - The daily schedule is opt-in (default OFF) and runs off the main thread (a whole-DB zip can be
 *   100MB+). The periodic write goes through WorkManager (survives reboot/app-kill, never the
 *   launch-critical path); the on-launch CATCH-UP is a deferred IO coroutine (see [catchUpIfDue]).
 */
object BackupSync {

    /** The two backup destinations Phase 1 is built to support (Drive lands in a later phase). */
    enum class Destination { FOLDER /* , GOOGLE_DRIVE */ }

    private const val PREFIX = "noop-backup-"
    private const val SUFFIX = ".noopbak"

    /** Generic binary MIME for the SAF createDocument call (the bytes are a ZIP container). */
    const val MIME = "application/octet-stream"

    /** Default snapshots kept by prune: 7, i.e. a week of daily rollback points. (The Apple twin still
     *  defaults to 10; this fork lowered it — parity dropdown on iOS is a follow-up.) */
    const val DEFAULT_KEEP = 7

    /** A day in ms - the catch-up cadence (mirrors the Apple `dayMs`). */
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** DEFAULT time-of-day the daily snapshot fires: 01:00 (minutes since midnight). A quiet hour; the
     *  user can change it (BackupSyncPrefs.backupMinute). WorkManager isn't exact and may slide it into
     *  a maintenance window, which is fine for a backup. */
    const val BACKUP_MINUTE_OF_DAY = 60

    /** Ms from [nowMs] to the next occurrence of [minuteOfDay] (minutes since midnight) wall-clock —
     *  today if still ahead, else tomorrow. Pure + injectable so the arithmetic is unit-testable without
     *  a real clock. Mirrors DebugExportScheduler. */
    fun delayToNextBackupMs(nowMs: Long = System.currentTimeMillis(), minuteOfDay: Int = BACKUP_MINUTE_OF_DAY): Long {
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

    // ── Pure helpers (unit-tested; mirror the Apple BackupSync) ─────────────────

    private fun fmt() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    /** Canonical snapshot filename for an instant: `noop-backup-YYYYMMDD-HHMMSS.noopbak` (UTC). */
    fun snapshotName(epochMs: Long): String = PREFIX + fmt().format(Date(epochMs)) + SUFFIX

    /** The UTC instant (ms) encoded in a snapshot filename, or null if [name] is not one of ours. */
    fun snapshotTimeMs(name: String): Long? {
        if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) return null
        val stamp = name.substring(PREFIX.length, name.length - SUFFIX.length)
        return runCatching { fmt().parse(stamp)?.time }.getOrNull()
    }

    fun isSnapshot(name: String): Boolean = snapshotTimeMs(name) != null

    /**
     * Any `.noopbak` file, whatever it's named. The RESTORE list uses this (not [isSnapshot]) so a
     * hand-named backup like `noop-backup-2026-06-30.noopbak` still shows (#852). Case-insensitive on
     * the extension. Prune/latest stay strict on [isSnapshot], so a non-canonical name is listed for
     * restore but never auto-deleted.
     */
    fun isBackupFile(name: String): Boolean = name.lowercase().endsWith(SUFFIX)

    /** Newest snapshot by encoded time among [names] (non-snapshots ignored), or null if none. */
    fun latestSnapshot(names: List<String>): String? =
        names.filter(::isSnapshot).maxByOrNull { snapshotTimeMs(it)!! }

    /**
     * Snapshots to DELETE to keep only the [keep] newest (oldest-first). Empty when within budget.
     * Strict on purpose: only canonical snapshots are prune candidates, so a hand-named `.noopbak`
     * in the folder is never auto-deleted.
     */
    fun snapshotsToPrune(names: List<String>, keep: Int): List<String> {
        val snaps = names.filter(::isSnapshot).sortedByDescending { snapshotTimeMs(it)!! }
        return if (snaps.size <= keep) emptyList() else snaps.drop(keep)
    }

    /** One restorable `.noopbak` for the folder picker: its filename and the ms used to order/label it. */
    data class Restorable(val name: String, val timeMs: Long)

    /**
     * One `.noopbak` document as the SAF cursor sees it: display name, its Uri, and the raw last-modified
     * ms the provider reported (0 when the column is null). Two docs can share [name] (Drive duplicates,
     * a sync client dropping the same date-only file twice); the [uri] is what keeps them distinct.
     */
    data class BackupDoc(val name: String, val uri: Uri, val modifiedMs: Long)

    /**
     * ALL `.noopbak` files (any name) ordered newest-first for the restore picker (#852). Canonical
     * names use their embedded UTC stamp; the rest fall back to the file date [fileDateMs] gives for
     * that name (0 when unknown). Non-`.noopbak` files are dropped. Pure, so it's unit-tested; the I/O
     * layer supplies [fileDateMs] from the SAF cursor's last-modified column.
     */
    fun restorablesNewestFirst(names: List<String>, fileDateMs: (String) -> Long): List<Restorable> =
        names.filter(::isBackupFile)
            .map { Restorable(it, snapshotTimeMs(it) ?: fileDateMs(it)) }
            // Newest-first, with a name tie-break so equal-time entries (two files sharing a date-only
            // name, or a provider that reports the same modified date) order deterministically and
            // identically to Swift's `timeMs desc then name asc`.
            .sortedWith(compareByDescending<Restorable> { it.timeMs }.thenBy { it.name })

    /**
     * Newest-first ordering of raw docs, PRESERVING DUPLICATES (#852). Unlike [restorablesNewestFirst]
     * (which keys off a bare name list), this keeps every distinct document even when two share a display
     * name - Drive duplicates, or a sync client / second device dropping `noop-backup-2026-06-30.noopbak`
     * twice - so no real backup silently vanishes. Date-only names are the most collision-prone, and they
     * are exactly the files #852 rescues. Non-`.noopbak` docs are dropped. Canonical names order by their
     * embedded stamp; the rest by the provider's last-modified ms.
     *
     * Generic over the doc type via [name]/[modifiedMs] accessors so the pure ordering is unit-testable
     * WITHOUT constructing an Android [Uri] (this project has no Robolectric). The I/O layer feeds it
     * [BackupDoc]s straight from the cursor.
     */
    fun <T> restorableDocsNewestFirst(
        docs: List<T>,
        name: (T) -> String,
        modifiedMs: (T) -> Long,
    ): List<T> =
        docs.filter { isBackupFile(name(it)) }
            // Same key as [restorablesNewestFirst]: the embedded stamp when present, else the file date.
            // Same newest-first + name tie-break so a doc list and a name list order identically.
            .sortedWith(
                compareByDescending<T> { snapshotTimeMs(name(it)) ?: modifiedMs(it) }
                    .thenBy { name(it) },
            )

    /** True when [nowMs] is at least a day past [lastBackupMs] (pure, so the catch-up gate is testable). */
    fun isCatchUpDue(lastBackupMs: Long, nowMs: Long): Boolean = nowMs - lastBackupMs >= DAY_MS

    // ── Fixed-folder I/O (Android-only fork). Backups write to a STABLE public folder via All-Files
    //    Access, NOT a SAF tree whose per-URI grant is silently REVOKED on reinstall (that revoke, with
    //    the folder pref surviving, is what made "Back up now" fail quietly). A normal path survives
    //    reinstall and any file-manager / cloud-sync app (FolderSync, Autosync) can watch it. ──

    /** The stable public folder every backup writes to: `<external storage>/NOOP`
     *  (`/storage/emulated/0/NOOP`). */
    const val BACKUP_DIR_NAME = "NOOP"
    fun backupDir(): File = File(Environment.getExternalStorageDirectory(), BACKUP_DIR_NAME)

    /** True when NOOP can write [backupDir] without a picker: All-Files-Access on API 30+
     *  ([Environment.isExternalStorageManager]), or the legacy WRITE_EXTERNAL_STORAGE runtime grant on
     *  API ≤ 29. The Backup screen requests it; every write + the schedule gate on it. */
    fun hasFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** One restorable snapshot: its [file], display [name], and the ms used to order + label it. */
    data class SnapshotFile(val file: File, val name: String, val timeMs: Long)

    /** Create + write one snapshot into [backupDir]; returns the file, or null on failure (no access,
     *  the folder couldn't be made, or the write threw). A partial write is deleted so prune/latest
     *  never pick up a corrupt snapshot. */
    fun writeSnapshot(context: Context, nowMs: Long = System.currentTimeMillis()): File? {
        if (!hasFilesAccess(context)) return null
        val dir = backupDir()
        if (!dir.exists()) runCatching { dir.mkdirs() }
        if (!dir.isDirectory) return null
        val file = File(dir, snapshotName(nowMs))
        return runCatching {
            DataBackup.exportTo(context, Uri.fromFile(file))
            file
        }.getOrElse {
            runCatching { file.delete() }
            null
        }
    }

    /** Run one backup into [backupDir], prune to [BackupSyncPrefs.keepCount], stamp last-backup. */
    fun backupNow(context: Context): Boolean {
        writeSnapshot(context) ?: return false
        BackupSyncPrefs.setLastBackupMs(context, System.currentTimeMillis())
        prune(context)
        return true
    }

    /** Best-effort retention: delete snapshots beyond keepCount. Listing failures are ignored. */
    private fun prune(context: Context) {
        val keep = BackupSyncPrefs.keepCount(context)
        val files = runCatching { listSnapshots() }.getOrDefault(emptyList())
        val toDelete = snapshotsToPrune(files.map { it.name }, keep).toSet()
        for (f in files) if (f.name in toDelete) runCatching { f.file.delete() }
    }

    /**
     * Every `.noopbak` in [backupDir], newest-first, as [SnapshotFile] rows — drives restore-from-folder.
     * Lists ANY `.noopbak` (#852): a hand-named backup like `noop-backup-2026-06-30.noopbak` still shows.
     * Canonical names order + label by their embedded UTC stamp; the rest by the file's last-modified date.
     * Content is validated on restore, so a bad file here is caught then.
     */
    fun listSnapshots(): List<SnapshotFile> {
        val files = backupDir().listFiles { f -> f.isFile && isBackupFile(f.name) }?.toList().orEmpty()
        return restorableDocsNewestFirst(files, { it.name }, { it.lastModified() }).map {
            SnapshotFile(it, it.name, snapshotTimeMs(it.name) ?: it.lastModified())
        }
    }

    // ── Scheduling (WorkManager - survives reboot/app-kill, mirrors DebugExportScheduler) ──

    private const val WORK = "noop_backup_sync_daily"

    /**
     * (Re)schedule (or cancel) the daily auto-backup from the persisted state. No-op + cancels when the
     * feature is off or no folder is chosen, so toggling off stops the writes. KEEP so re-enabling or a
     * reboot doesn't stack duplicate daily jobs. Wrap call sites so a WorkManager hiccup never throws.
     */
    fun reschedule(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!BackupSyncPrefs.autoEnabled(context) || !hasFilesAccess(context)) {
            wm.cancelUniqueWork(WORK)
            return
        }
        // Anchor the first run to the next chosen time-of-day, then repeat daily. KEEP so an already-
        // scheduled job keeps its anchor rather than resetting on every app-start (matches
        // DebugExportScheduler); toggling auto off/on OR changing the time via [applyTimeChange]
        // re-anchors. On-launch [catchUpIfDue] still covers any missed day regardless of timing.
        val req = PeriodicWorkRequestBuilder<BackupSyncWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayToNextBackupMs(minuteOfDay = BackupSyncPrefs.backupMinute(context)), TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Re-anchor the daily backup to the (just-changed) [BackupSyncPrefs.backupMinute] immediately —
     *  cancel then reschedule, since KEEP would otherwise leave the old time in place. */
    fun applyTimeChange(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
        reschedule(context)
    }

    /**
     * On-launch CATCH-UP backup. Must-fix #4: deferred off the launch-critical path and run fully off
     * the main thread by the CALLER (MainActivity launches this on Dispatchers.IO after the critical
     * startup work). Gated on the toggle being ON, a folder being set, and it being at least a day since
     * the last backup. Cheap to call when off (two SharedPreferences reads, no I/O). Returns true if it
     * actually wrote a backup.
     */
    fun catchUpIfDue(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!BackupSyncPrefs.autoEnabled(context)) return false
        if (!hasFilesAccess(context)) return false
        if (!isCatchUpDue(BackupSyncPrefs.lastBackupMs(context), nowMs)) return false
        return backupNow(context)
    }
}

/**
 * The worker that performs one scheduled backup. Re-checks the toggle (a stale enqueued job from before
 * the user turned auto off must not fire) and writes one snapshot into the chosen folder. A transient
 * failure (e.g. the folder's sync app has the tree busy) returns retry rather than poisoning the chain.
 */
class BackupSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!BackupSyncPrefs.autoEnabled(applicationContext)) return Result.success()
        return if (BackupSync.backupNow(applicationContext)) Result.success() else Result.retry()
    }
}

/**
 * Small SharedPreferences-backed store for the folder destination + schedule state. Mirrors the Apple
 * `FolderBackup` UserDefaults keys: a persisted SAF tree uri, the auto toggle (default OFF), the last
 * backup instant, and the keep-N (default [BackupSync.DEFAULT_KEEP]). Single-user, on-device.
 */
object BackupSyncPrefs {
    private const val FILE = "backup_sync"
    private fun p(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Master enable for the daily auto-backup. Default OFF (every NOOP automation is opt-in). */
    fun autoEnabled(c: Context): Boolean = p(c).getBoolean("auto", false)
    fun setAutoEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("auto", on).apply()

    /** Time-of-day the daily backup runs, minutes since local midnight. Default [BackupSync.BACKUP_MINUTE_OF_DAY]
     *  (01:00). Clamped to a valid minute so a corrupt value can't schedule at nonsense o'clock. */
    fun backupMinute(c: Context): Int =
        p(c).getInt("backup_minute", BackupSync.BACKUP_MINUTE_OF_DAY).coerceIn(0, 24 * 60 - 1)
    fun setBackupMinute(c: Context, m: Int) =
        p(c).edit().putInt("backup_minute", m.coerceIn(0, 24 * 60 - 1)).apply()

    fun lastBackupMs(c: Context): Long = p(c).getLong("last_ms", 0L)
    fun setLastBackupMs(c: Context, ms: Long) = p(c).edit().putLong("last_ms", ms).apply()

    fun keepCount(c: Context): Int = p(c).getInt("keep", BackupSync.DEFAULT_KEEP)
    fun setKeepCount(c: Context, n: Int) = p(c).edit().putInt("keep", n.coerceIn(1, 100)).apply()
}

package com.noop.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-store EXPORT / IMPORT for device migration.
 *
 * NOOP keeps everything on-device in a single Room/SQLite file ([WhoopDatabase.DB_NAME]).
 * Moving to a new phone therefore means moving exactly that one file. There is no cloud,
 * no account, nothing leaves the device except through these two explicit, user-driven
 * file operations (a SAF document the user picks).
 *
 * Export: checkpoint the WAL into the main db file, then write a ZIP (the `.noopbak`
 * format) containing the SQLite file plus a small `settings.json` entry (#1000) with the
 * whitelisted profile/display settings (see [BackupSettingsCodec]), so a restore also
 * brings back weight/height/units and not just the rows. ZIP deflate typically reduces a
 * 100 MB+ SQLite backup to 10–20 MB — SQLite's page-aligned text data compresses very
 * well. The ZIP is a standard container: users can rename `.noopbak` → `.zip` and
 * extract the SQLite manually with any archive tool on any OS.
 *
 * Import: detect whether the picked file is a `.noopbak` ZIP (PK magic) or a legacy
 * plain `.sqlite` / `.noopdb` (SQLite magic) and handle both, so old backups keep
 * working. Validates the extracted/direct SQLite header, the backup's origin, AND its
 * structural integrity (`PRAGMA quick_check`, #1014) before touching the live DB.
 * Closes the live Room singleton, snapshots the current db, overwrites it with the
 * chosen one, drops the stale `-wal` / `-shm` sidecars, then re-verifies the landed
 * file and rolls back to the snapshot automatically if the copy tore (#1014). The
 * caller then instructs the user to restart the app so Room re-opens the new file fresh.
 */
object DataBackup {

    /** Entry name of the SQLite inside the `.noopbak` ZIP. */
    private const val ZIP_ENTRY_NAME = "noop-backup.sqlite"

    /** Entry name of the optional whitelisted-settings JSON (#1000). Matches the Apple exporter. */
    private const val SETTINGS_ENTRY_NAME = BackupSettingsCodec.ENTRY_NAME

    private const val MAX_BACKUP_SQLITE_BYTES = 2_147_483_648L
    private const val MAX_BACKUP_SETTINGS_BYTES = 1_048_576L

    /** First 16 bytes of every SQLite 3 file: "SQLite format 3\0". */
    private val SQLITE_MAGIC: ByteArray =
        byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )

    /** First 4 bytes of every ZIP file: "PK\x03\x04". */
    private val ZIP_MAGIC: ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** Which foreign platform / fork a picked backup came from, used to word the import confirmation. */
    enum class ForeignBackupKind { IOS, ANDROID_FORK }

    /** Outcome of an [importFrom] call. On success the app must be restarted. */
    sealed interface ImportResult {
        /**
         * The new database is in place; tell the user to relaunch NOOP. [warnings] is empty for a
         * plain same-app restore; a cross-platform / cross-fork row-copy import (see
         * [reconcileForeignBackup]) fills it with what the two schemas didn't share (tables/columns
         * only one side has), for the UI to surface after the restore. Never a hard error.
         */
        data class NeedsRestart(val warnings: List<String> = emptyList()) : ImportResult

        /**
         * The picked file is a FOREIGN NOOP backup (the iOS/GRDB store, or a divergent Android NOOP
         * fork) whose rows CAN be merged in by [reconcileForeignBackup], but doing so is a deliberate
         * cross-platform act, so it isn't done silently. The UI shows a confirm dialog keyed on [kind]
         * and, on approval, re-invokes [importFrom] with `confirmedForeign = true`.
         */
        data class NeedsConfirmation(val kind: ForeignBackupKind) : ImportResult

        /** Import failed and the original database is untouched. */
        data class Failed(val message: String) : ImportResult

        /**
         * Like [Failed] the live database FILE is intact and untouched, but a cross-fork reconcile
         * (see [reconcileForeignBackup]) failed AFTER the live Room singleton was closed for the merge,
         * so its DAOs now point at a closed connection (the #57 stale-handle hazard). The reconcile only
         * READS the live file and writes scratch, so the data is safe; the UI shows [message], then
         * relaunches the app on dismiss so Room re-opens the intact store fresh.
         */
        data class FailedNeedsRestart(val message: String) : ImportResult
    }

    /**
     * Export the live database to [uri] as a compressed `.noopbak` (single-entry ZIP).
     *
     * Runs `PRAGMA wal_checkpoint(TRUNCATE)` first so the db file is fully consistent.
     * The ZIP uses deflate compression; typical reduction is 80–90% vs the raw SQLite.
     * Throws on failure so the caller can surface the message in a toast/snackbar.
     */
    @Throws(IOException::class)
    fun exportTo(context: Context, uri: Uri) {
        val appContext = context.applicationContext

        // Fold the WAL back into the main file so the snapshot is complete.
        val db = WhoopDatabase.get(appContext)
        db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            cursor.moveToFirst()
        }

        val dbFile = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
        if (!dbFile.exists()) {
            throw IOException("No database to export yet.")
        }

        // #1014 defence-in-depth (export side): after the checkpoint the single file IS the whole
        // store — verify it BEFORE archiving. A backup of an already-corrupt database only fails
        // the import-side integrity gate months later, when the original data may be long gone;
        // failing loudly NOW is the honest move. Read-only probe, sits safely beside the open Room
        // connection (WAL allows concurrent readers). Twin of the Apple writeVerifiedBackupZip.
        sqliteQuickCheckFailure(dbFile)?.let { complaint ->
            throw IOException(
                "Couldn't export: the NOOP database failed its integrity check (SQLite reports: " +
                    "$complaint). A backup of it would not restore. Export the WHOOP-format CSV " +
                    "instead to save what's still readable."
            )
        }

        // #1000: the whitelisted profile/display settings ride along as a second entry so a restore
        // brings back weight/height/units, not just the rows. Null (nothing user-set) degrades to the
        // legacy single-entry ZIP. The DB entry stays FIRST — older importers stop at the first
        // `.sqlite` entry, so entry order is part of the cross-platform container contract.
        val settingsJson = BackupSettingsBridge.snapshotJson(appContext)

        val resolver = appContext.contentResolver
        val output = resolver.openOutputStream(uri)
            ?: throw IOException("Could not open the chosen file for writing.")
        output.use { out ->
            // #1014: copy the file while HOLDING Room's write transaction. In WAL mode the main
            // file is only rewritten by a checkpoint, and a checkpoint only runs on a commit — so
            // with the (single) write connection parked in an empty transaction for the duration
            // of the copy, no commit can land and the bytes we stream can't be torn mid-page by a
            // concurrent auto-checkpoint. Writers queue behind us and proceed after; readers are
            // unaffected. Anything committed after the checkpoint above lives in the new WAL and
            // is simply (consistently) absent from this snapshot, same as before.
            db.runInTransaction {
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry(ZIP_ENTRY_NAME))
                    dbFile.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    if (settingsJson != null) {
                        zip.putNextEntry(ZipEntry(SETTINGS_ENTRY_NAME))
                        zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    /**
     * Replace the live database with the backup at [uri].
     *
     * Accepts both the new `.noopbak` (ZIP) format and legacy plain `.sqlite`/`.noopdb`
     * files so older backups keep working after the format upgrade.
     *
     * A same-app Room backup (any version this app's migrator can open) restores by the fast file
     * swap below. A FOREIGN backup — the iOS/GRDB store, or a divergent Android NOOP fork whose schema
     * this build can't migrate forward — is instead merged in row-by-row by [reconcileForeignBackup],
     * but only once the caller passes [confirmedForeign] true (the first call returns
     * [ImportResult.NeedsConfirmation] so the UI can ask). Detection is by schema CONTENT, never by
     * version (GRDB always reports user_version 0, forks reuse the same integers).
     *
     * On any error the current database is left exactly as it was. On success the caller
     * MUST instruct the user to fully restart the app.
     */
    fun importFrom(context: Context, uri: Uri, confirmedForeign: Boolean = false): ImportResult {
        val appContext = context.applicationContext
        val resolver = appContext.contentResolver

        // 1. Peek at the first 16 bytes to distinguish ZIP from plain SQLite.
        val header = ByteArray(16)
        try {
            val read = resolver.openInputStream(uri)?.use { readFully(it, header) }
                ?: return ImportResult.Failed("Could not open the chosen file.")
            if (read < 4) return ImportResult.Failed("That file is not a NOOP backup.")
        } catch (e: IOException) {
            return ImportResult.Failed("Could not read the chosen file: ${e.message}")
        }

        // 2. If it's a ZIP (.noopbak), extract the SQLite entry to a temp file.
        //    If it's a plain SQLite (legacy), copy it to the same temp file.
        //    The container-staging step is factored into [stageBackupSqlite] (a pure file/stream
        //    function) so it can be exercised under real file I/O in unit tests without Room/Context.
        //    A `settings.json` entry (#1000) is staged alongside when present; the stale-delete first
        //    matters, or a leftover from an earlier import could masquerade as THIS backup's settings.
        val tempSqlite = File(appContext.cacheDir, "import-extract.sqlite")
        val tempSettings = File(appContext.cacheDir, "import-settings.json")
        tempSettings.delete()
        try {
            when (val staged = stageBackupSqlite(resolver.openInputStream(uri), header, tempSqlite, tempSettings)) {
                StageResult.OK -> Unit
                StageResult.CANNOT_OPEN -> return ImportResult.Failed("Could not open the chosen file.")
                StageResult.NO_DB_IN_ZIP -> {
                    tempSettings.delete()
                    return ImportResult.Failed("The backup archive doesn't contain a database file.")
                }
                StageResult.ENTRY_TOO_LARGE -> {
                    tempSqlite.delete()
                    tempSettings.delete()
                    return ImportResult.Failed("The backup archive is too large to restore safely.")
                }
                StageResult.NOT_A_BACKUP -> return ImportResult.Failed(
                    "That file is not a NOOP backup - it doesn't look like a .noopbak archive or a SQLite database."
                )
            }
        } catch (e: IOException) {
            tempSqlite.delete()
            tempSettings.delete()
            return ImportResult.Failed("Could not read the chosen file: ${e.message}")
        }

        // 3. Validate the extracted file is a real SQLite database (magic-byte check).
        if (!isValidSqliteHeader(tempSqlite)) {
            tempSqlite.delete()
            tempSettings.delete()
            return ImportResult.Failed("The backup archive doesn't contain a valid NOOP database.")
        }

        // 3b. Route by backup CONTENT, not version (the SQLite magic passes for ANY SQLite file, and
        //     the header's user_version is unusable here — GRDB always writes 0 and Room forks reuse
        //     the same integers). Read the table names + the dailyMetric columns READ-ONLY:
        //       - our own Room store (any version this app's migrator can open) keeps the fast file
        //         swap restore below;
        //       - a FOREIGN backup — the iOS/GRDB store, or a divergent Android NOOP fork whose schema
        //         this build can't migrate forward (a raw swap would then strand or wipe it) — is
        //         merged in row-by-row by [reconcileForeignBackup], but only after the user confirms
        //         (a cross-platform restore is a deliberate act, so it is never silent);
        //       - a file that holds data yet carries neither migrator's bookkeeping is some other
        //         app's database and is still refused outright.
        //     Empty/pre-migration files fall through to Room's open-time migrator, exactly as before.
        var importWarnings: List<String> = emptyList()
        // A NULL schema read means the backup's SQLite could not be OPENED or queried AT ALL — distinct
        // from a readable-but-empty file, which reads as an empty set and falls through to Room's
        // open-time migrator exactly as before. Refuse a null honestly instead of falling through to the
        // raw file-swap, which would drop an unreadable file over the live store.
        val backupTables = sqliteTableNames(tempSqlite)
            ?: return rejectForeign(
                tempSqlite,
                tempSettings,
                "Couldn't read this backup's database. Your current data is untouched. Try an earlier " +
                    "backup file.",
            )
        val backupDailyMetricColumns = sqliteColumnNames(tempSqlite, "dailyMetric") ?: emptySet()
        when (val foreign = foreignBackupKind(backupTables, backupDailyMetricColumns)) {
            null ->
                if (backupOriginOf(backupTables) == BackupOrigin.UNKNOWN && holdsData(backupTables)) {
                    return rejectForeign(
                        tempSqlite,
                        tempSettings,
                        "This isn't a NOOP backup from this app. It's missing the database bookkeeping a " +
                            "NOOP backup carries (it looks like another app's database). Restoring it would " +
                            "strand your store.",
                    )
                }
            else -> {
                // Gate the cross-platform merge behind an explicit confirmation; nothing has touched
                // the live DB yet, so returning here leaves it exactly as it was.
                if (!confirmedForeign) {
                    tempSqlite.delete()
                    tempSettings.delete()
                    return ImportResult.NeedsConfirmation(foreign)
                }
                // Fresh-install contract (twin of the Swift restore): a foreign row-copy inherits THIS
                // app's exact schema + Room identity from the live store. If NOOP has never opened its
                // store on this device there is nothing to reconcile into, so refuse honestly rather than
                // create an empty store and merge into it. Checked BEFORE the WhoopDatabase.get() below,
                // which would otherwise create that empty store.
                val liveDb = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
                if (!liveDb.exists()) {
                    return rejectForeign(
                        tempSqlite,
                        tempSettings,
                        "This looks like a backup from another NOOP platform, but there's no NOOP store " +
                            "on this device yet to merge it into. Open NOOP once to set up your store, " +
                            "then import again - or move your history with a WHOOP-format CSV export.",
                    )
                }
                // Row-copy the foreign rows into a file carrying THIS app's schema + identity, then
                // REPLACE the staged file with it so the ordinary integrity/snapshot/swap path below
                // lands it. Checkpoint + close the live store first so the file the reconcile reads is
                // quiescent.
                importWarnings = runCatching {
                    WhoopDatabase.get(appContext).query("PRAGMA wal_checkpoint(TRUNCATE)", null)
                        .use { it.moveToFirst() }
                    WhoopDatabase.close()
                    val (reconciled, warnings) =
                        reconcileForeignBackup(appContext, liveDb, tempSqlite, ImportMode.REPLACE)
                    reconciled.copyTo(tempSqlite, overwrite = true)
                    reconciled.delete()
                    // Reopen the live store so the shared gates below (3c integrity check) run with it
                    // OPEN, exactly as the same-app restore does; step 4 re-closes it for the swap. This
                    // keeps a downstream failure on the ordinary Failed path instead of stranding closed DAOs.
                    WhoopDatabase.get(appContext)
                    warnings
                }.getOrElse { e ->
                    tempSqlite.delete()
                    tempSettings.delete()
                    // The live DB FILE is untouched (reconcile only reads it + writes scratch), but the
                    // Room singleton is now CLOSED (closed above so the reconcile read a quiescent file).
                    // A plain Failed here would strand the app on stale/closed DAOs (the #57 hazard);
                    // FailedNeedsRestart makes the UI show the error, then relaunch on the intact live DB.
                    return ImportResult.FailedNeedsRestart(
                        "Couldn't bring this backup into NOOP's format: ${e.message}"
                    )
                }
            }
        }

        // 3c. #1014 defence-in-depth: gates 3 and 3b read only the FIRST pages of the file — the
        //     16-byte magic and sqlite_master both survive a backup that was truncated mid-upload or
        //     torn by a flaky drive/cloud client, and such a file then "restores" into a store that
        //     silently shows no data (the #1014 report; the #1000 settings code was exonerated, but
        //     the family needed armour). Run SQLite's own `PRAGMA quick_check` over the STAGED file,
        //     read-only, BEFORE the live DB is touched, and refuse the swap honestly. quick_check
        //     (not integrity_check) skips index-content verification so it stays fast on a 100 MB+
        //     library while still catching truncation and malformed pages. Twin of the Apple side's
        //     DatabaseIntegrity gate.
        sqliteQuickCheckFailure(tempSqlite)?.let { complaint ->
            tempSqlite.delete()
            tempSettings.delete()
            return ImportResult.Failed(
                "This backup file is damaged and can't be restored (SQLite reports: $complaint). " +
                    "Your current data is untouched. Try an earlier backup file."
            )
        }

        val dbFile = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
        val walFile = File(dbFile.path + "-wal")
        val shmFile = File(dbFile.path + "-shm")
        val rollbackFile = File(dbFile.path + ".import-bak")

        // 4. Close the live Room singleton so the file handles are released.
        WhoopDatabase.close()

        // 5. Snapshot the current db so a failed copy can be rolled back.
        try {
            rollbackFile.delete()
            if (dbFile.exists()) dbFile.copyTo(rollbackFile, overwrite = true)
        } catch (e: IOException) {
            tempSqlite.delete()
            tempSettings.delete()
            return ImportResult.Failed("Could not back up the current data: ${e.message}")
        }

        // 6. Overwrite the db file with the extracted backup, then drop the stale sidecars.
        try {
            dbFile.parentFile?.mkdirs()
            tempSqlite.copyTo(dbFile, overwrite = true)
            walFile.delete()
            shmFile.delete()
        } catch (e: IOException) {
            runCatching { if (rollbackFile.exists()) rollbackFile.copyTo(dbFile, overwrite = true) }
            rollbackFile.delete()
            tempSqlite.delete()
            tempSettings.delete()
            return ImportResult.Failed("Import failed, your data is unchanged: ${e.message}")
        }

        // 6b. #1014 defence-in-depth, post-swap: re-verify the file that actually LANDED at the live
        //     path with a second read-only quick_check. The staged file was verified in 3c, but the
        //     copy itself can tear — disk-full mid-copy, a dying flash chip, the process killed at
        //     the wrong instant — and the next launch would meet a corrupt store (which, before the
        //     CorruptionPreservingOpenHelperFactory below, the platform would then silently DELETE).
        //     On failure, roll back to the `.import-bak` snapshot automatically and say so.
        sqliteQuickCheckFailure(dbFile)?.let { complaint ->
            tempSqlite.delete()
            tempSettings.delete()
            walFile.delete()
            shmFile.delete()
            val message: String
            if (rollbackFile.exists()) {
                if (runCatching { rollbackFile.copyTo(dbFile, overwrite = true) }.isSuccess) {
                    rollbackFile.delete()
                    message = "The backup failed its integrity check after the copy (SQLite reports: " +
                        "$complaint). Your previous data was rolled back automatically and is unchanged."
                } else {
                    // The roll-back copy itself failed: KEEP the snapshot on disk — it is now the
                    // only good copy of the user's data — and tell the user exactly where it is.
                    message = "The backup failed its integrity check after the copy (SQLite reports: " +
                        "$complaint), and rolling back also failed. Your previous data is preserved at " +
                        "${rollbackFile.name} next to the app's database."
                }
            } else {
                // Fresh install: nothing existed before the import, so removing the damaged file
                // returns to the exact pre-import (empty) state.
                dbFile.delete()
                message = "The backup failed its integrity check after the copy (SQLite reports: " +
                    "$complaint). There was no previous data to roll back."
            }
            return ImportResult.Failed(message)
        }

        // 7. #1000: re-apply the backup's whitelisted profile/display settings (weight, height, age,
        //    sex, HR-max override, unit prefs) — but only NOW, after the DB swap landed. Every failure
        //    path above returns without touching settings. Legacy single-entry backups staged no
        //    settings file and restore exactly as before; a malformed settings entry degrades to
        //    "fewer keys applied" inside the codec and can never fail the restore.
        if (tempSettings.exists()) {
            runCatching {
                BackupSettingsBridge.apply(appContext, tempSettings.readText(Charsets.UTF_8))
            }.onFailure { e ->
                // Non-fatal to the DB restore (the rows already landed), but don't swallow it: surface it
                // as a restore warning so the user knows their profile/display settings didn't come back
                // and can re-enter them, instead of silently reverting to the device's current values.
                importWarnings = importWarnings +
                    "Your saved profile and display settings couldn't be re-applied: ${e.message}"
            }
            tempSettings.delete()
        }

        rollbackFile.delete()
        tempSqlite.delete()
        // #57 debug: record when a restore swapped the DB, so the export can correlate a restore with a
        // subsequent write stall (a restore that wasn't followed by a restart is exactly the #57 failure).
        runCatching {
            com.noop.ui.NoopPrefs.of(appContext).edit()
                .putLong("backup.lastRestoreAt", System.currentTimeMillis() / 1000L).apply()
        }
        return ImportResult.NeedsRestart(importWarnings)
    }

    // ── Container staging (pure file/stream layer, unit-tested under real file I/O) ──────

    /** Outcome of [stageBackupSqlite]: the SQLite was staged, or why it wasn't. */
    enum class StageResult { OK, CANNOT_OPEN, NO_DB_IN_ZIP, NOT_A_BACKUP, ENTRY_TOO_LARGE }

    /**
     * Stage the SQLite payload of a backup into [dest], from an already-opened [input] stream whose
     * first bytes are [header]. Handles both the `.noopbak` ZIP (extract the `.sqlite` entry) and a
     * legacy plain SQLite (copy through). Closes [input]. Context-free + stream-driven so the unit
     * tests drive it with real `java.util.zip` archives and real files, exercising the exact extraction
     * the live import uses (no behaviour fork between test and production).
     *
     * When [settingsDest] is given, a `settings.json` entry (#1000) is ALSO staged there if the ZIP
     * carries one (either platform's exporter may have written it, in either entry order). Its absence
     * is not an error — every pre-#1000 backup is a single-entry ZIP — and it never affects the
     * returned [StageResult]: the DB is the payload that decides success.
     *
     * NOTE this does NOT validate the staged file's SQLite header or origin; [importFrom] does that
     * next, on the staged file. Keeping staging and validation separate keeps each pure-testable.
     */
    fun stageBackupSqlite(
        input: java.io.InputStream?,
        header: ByteArray,
        dest: File,
        settingsDest: File? = null,
    ): StageResult {
        if (input == null) return StageResult.CANNOT_OPEN
        input.use { stream ->
            when {
                header.startsWith(ZIP_MAGIC) -> {
                    var foundDb = false
                    var foundSettings = false
                    ZipInputStream(stream).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            when {
                                !entry.isDirectory && !foundDb &&
                                    entry.name.substringAfterLast('/') == ZIP_ENTRY_NAME -> {
                                    FileOutputStream(dest).use { out ->
                                        if (!copyBounded(zip, out, MAX_BACKUP_SQLITE_BYTES)) {
                                            dest.delete()
                                            return StageResult.ENTRY_TOO_LARGE
                                        }
                                    }
                                    foundDb = true
                                }
                                !entry.isDirectory && !foundSettings && settingsDest != null &&
                                    entry.name.substringAfterLast('/') == SETTINGS_ENTRY_NAME -> {
                                    FileOutputStream(settingsDest).use { out ->
                                        if (!copyBounded(zip, out, MAX_BACKUP_SETTINGS_BYTES)) {
                                            settingsDest.delete()
                                            return StageResult.ENTRY_TOO_LARGE
                                        }
                                    }
                                    foundSettings = true
                                }
                            }
                            // Everything we could want is staged - stop reading the archive.
                            if (foundDb && (settingsDest == null || foundSettings)) break
                            entry = zip.nextEntry
                        }
                    }
                    return if (foundDb) StageResult.OK else StageResult.NO_DB_IN_ZIP
                }
                header.startsWith(SQLITE_MAGIC) -> {
                    FileOutputStream(dest).use { out ->
                        if (!copyBounded(stream, out, MAX_BACKUP_SQLITE_BYTES)) {
                            dest.delete()
                            return StageResult.ENTRY_TOO_LARGE
                        }
                    }
                    return StageResult.OK
                }
                else -> return StageResult.NOT_A_BACKUP
            }
        }
    }

    private fun copyBounded(input: java.io.InputStream, out: java.io.OutputStream, cap: Long): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            if (total + read > cap) return false
            out.write(buffer, 0, read)
            total += read
        }
    }

    /** Write [dbFile]'s bytes into a deflate ZIP at [dest] (the `.noopbak` container), DB entry first,
     *  plus the optional `settings.json` entry (#1000) when [settingsJson] is non-null. Context-free
     *  twin of the stream the live [exportTo] writes, so tests round-trip a real archive of either
     *  shape (legacy single-entry when [settingsJson] is null). */
    @Throws(IOException::class)
    fun writeBackupZip(dbFile: File, dest: File, settingsJson: String? = null) {
        FileOutputStream(dest).use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(ZIP_ENTRY_NAME))
                dbFile.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                if (settingsJson != null) {
                    zip.putNextEntry(ZipEntry(SETTINGS_ENTRY_NAME))
                    zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    /** True when [file] begins with the SQLite 3 magic. Pure; used by [importFrom] and the tests. */
    fun isValidSqliteHeader(file: File): Boolean {
        val buf = ByteArray(SQLITE_MAGIC.size)
        return runCatching {
            val read = file.inputStream().use { readFully(it, buf) }
            read >= SQLITE_MAGIC.size && buf.contentEquals(SQLITE_MAGIC)
        }.getOrDefault(false)
    }

    /** First [n] bytes of [file] (or fewer at EOF): the header peek the import does on the raw file. */
    fun peekHeader(file: File, n: Int = 16): ByteArray {
        val buf = ByteArray(n)
        val read = runCatching { file.inputStream().use { readFully(it, buf) } }.getOrDefault(0)
        return buf.copyOf(read)
    }

    /** Read up to [buffer].size bytes from [input], looping over short reads. Returns bytes read. */
    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }

    /** True when [this] begins with every byte in [prefix]. */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    // ── Origin validation (parity with the Apple GRDB-origin rejection) ─────────

    /** Which platform produced a NOOP backup, judged by its migrator's bookkeeping table. */
    enum class BackupOrigin { MAC, ANDROID, UNKNOWN }

    /**
     * Pure classification over a backup's `sqlite_master` table names: Room (this app) writes
     * `room_master_table`; GRDB (the Mac/iOS app) writes `grdb_migrations`. `.UNKNOWN` (neither, an
     * empty or pre-migration file) falls through to the normal import path, where Room's open-time
     * migrator decides. Mirrors the Apple `DataBackup.backupOrigin(of:)` so both platforms agree
     * byte-for-byte on what a foreign backup is.
     *
     * This platform's marker wins on the (degenerate) both-present case: restoring our own store here
     * is the less destructive read.
     */
    fun backupOriginOf(tableNames: Set<String>): BackupOrigin {
        if (tableNames.contains("room_master_table")) return BackupOrigin.ANDROID
        if (tableNames.contains("grdb_migrations")) return BackupOrigin.MAC
        // Older Room layouts didn't carry `room_master_table`; treat the Room/AndroidX pairing of
        // `android_metadata` + `sqlite_sequence` as one of ours too (mirrors the Apple side, which
        // reads that same duo as Android).
        if (tableNames.contains("android_metadata") && tableNames.contains("sqlite_sequence")) {
            return BackupOrigin.ANDROID
        }
        return BackupOrigin.UNKNOWN
    }

    /**
     * Does this backup actually hold app data (vs an empty/fresh file)? True when it carries any
     * user-content table beyond the SQLite/Android housekeeping ones. An `.UNKNOWN` file with no
     * content is harmless to restore; one WITH content but no recognised bookkeeping is some other
     * app's database and is rejected.
     */
    fun holdsData(tableNames: Set<String>): Boolean {
        val housekeeping = setOf("android_metadata", "sqlite_sequence", "room_master_table", "grdb_migrations")
        return tableNames.any { it !in housekeeping && !it.startsWith("sqlite_") }
    }

    /** Open [file] for a schema PROBE: read-only first, then falling back to read-write exactly as
     *  [sqliteQuickCheckFailure] does. A checkpointed WAL `.noopbak` carries a WAL-mode header that
     *  pre-3.22 SQLite (API 26/27, minSdk 26) cannot open read-only without an initialized `-shm`, so a
     *  read-only-only probe would spuriously fail on valid Android 8.x backups. Returns null when NEITHER
     *  open succeeds. Both opens carry [PRESERVE_ON_CORRUPTION] (#1014) so a probe can never delete what
     *  it probes; a read-write open only runs standard SQLite recovery, never a content change, and every
     *  probed file is a staged temp copy this app owns. */
    private fun openReadableForProbe(file: File): SQLiteDatabase? =
        runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, PRESERVE_ON_CORRUPTION)
        }.recoverCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, PRESERVE_ON_CORRUPTION)
        }.getOrNull()

    /** Every table name in [file], or NULL when the database could not be OPENED or queried at all —
     *  distinct from a readable-but-empty file, which returns an EMPTY set. The caller ([importFrom])
     *  turns a null into an honest "couldn't read this backup" refusal instead of falling through to the
     *  raw file-swap. Opened via [openReadableForProbe] so a WAL `.noopbak` still reads on API 26/27. */
    private fun sqliteTableNames(file: File): Set<String>? {
        val db = openReadableForProbe(file) ?: return null
        return try {
            val names = LinkedHashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                while (c.moveToNext()) c.getString(0)?.let(names::add)
            }
            names
        } catch (e: Exception) {
            null
        } finally {
            runCatching { db.close() }
        }
    }

    /** Column names of [table] in [file], or NULL when the database could not be OPENED or queried at
     *  all. A readable file that simply LACKS [table] returns an EMPTY set (not null). Opened via
     *  [openReadableForProbe] like [sqliteTableNames]. Used by the fork-marker check ([foreignBackupKind])
     *  to spot a column another fork carries but this build's schema doesn't. */
    private fun sqliteColumnNames(file: File, table: String): Set<String>? {
        val db = openReadableForProbe(file) ?: return null
        return try {
            val names = LinkedHashSet<String>()
            db.rawQuery("PRAGMA table_info(${quoteId(table)})", null).use { c ->
                val ni = c.getColumnIndex("name")
                while (c.moveToNext()) if (ni >= 0) c.getString(ni)?.let(names::add)
            }
            names
        } catch (e: Exception) {
            null
        } finally {
            runCatching { db.close() }
        }
    }

    /** Delete the staged temp files and return a Failed result, keeping the live DB untouched. */
    private fun rejectForeign(tempSqlite: File, tempSettings: File, message: String): ImportResult {
        tempSqlite.delete()
        tempSettings.delete()
        return ImportResult.Failed(message)
    }

    // ── Integrity gate (#1014 defence-in-depth; twin of the Apple DatabaseIntegrity) ─────

    /**
     * Pure classification of the rows `PRAGMA quick_check` returned: null = healthy (the single
     * canonical "ok" row), otherwise the first complaint row VERBATIM — never a fabricated summary.
     * An EMPTY result set is a failure too: quick_check always answers, so silence means the query
     * was swallowed and the file must not be trusted. Mirrors the Apple side's
     * `DatabaseIntegrity.verdict(fromRows:)` byte-for-byte — the same golden vectors are pinned in
     * [DataBackupIntegrityTest] here and `DatabaseIntegrityTests` there, so both platforms agree on
     * what "healthy" means. Pure + public so the plain-JVM test can drive it without Robolectric.
     */
    fun quickCheckVerdict(rows: List<String>): String? {
        if (rows.size == 1 && rows[0].equals("ok", ignoreCase = true)) return null
        return rows.firstOrNull { !it.equals("ok", ignoreCase = true) }
            ?: "quick_check returned no verdict"
    }

    /**
     * A [android.database.DatabaseErrorHandler] that closes the handle and PRESERVES the file. The
     * framework default ([android.database.DefaultDatabaseErrorHandler]) DELETES the file it was
     * probing on SQLITE_CORRUPT/SQLITE_NOTADB — every `openDatabase` overload without an explicit
     * handler inherits that. For the integrity probes below that would be catastrophic: the export
     * probe opens the LIVE database, so a corrupt store would be silently destroyed by the very
     * check meant to protect it (#1014). Also used by the origin probe for the same reason.
     */
    private val PRESERVE_ON_CORRUPTION = android.database.DatabaseErrorHandler { dbObj ->
        runCatching { dbObj.close() }
    }

    /**
     * Run `PRAGMA quick_check(1)` on [file]. Returns null when the file is healthy, otherwise a
     * short human-readable complaint for the caller's honest failure message. `quick_check(1)`
     * stops at the first error, so a damaged 100 MB library still answers quickly.
     *
     * Opens READ-ONLY first (never mutates the probed file; sits safely beside an open Room
     * connection — WAL allows concurrent readers). If the read-only open itself fails, falls back
     * to a read-write open: pre-3.22 SQLite (API 26/27, minSdk 26) cannot read-only-open a
     * WAL-header file without an initialized `-shm`, which is exactly what a checkpointed staged
     * backup looks like — refusing those would break valid restores on Android 8.x. Every probed
     * file is ours to touch (the staged temp copy, the just-swapped live file, or the live store
     * the export is about to archive), and a read-write open only performs standard SQLite
     * recovery, never a content change. Both opens carry [PRESERVE_ON_CORRUPTION] so no probe can
     * ever delete what it probes.
     */
    private fun sqliteQuickCheckFailure(file: File): String? {
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, PRESERVE_ON_CORRUPTION)
        }.recoverCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, PRESERVE_ON_CORRUPTION)
        }.getOrElse { return "could not open the database: ${it.message}" }
        return try {
            val rows = ArrayList<String>()
            db.rawQuery("PRAGMA quick_check(1)", null).use { c ->
                while (c.moveToNext()) c.getString(0)?.let(rows::add)
            }
            quickCheckVerdict(rows)
        } catch (e: Exception) {
            // The query failed outright (SQLITE_NOTADB on garbage behind a valid magic header,
            // a malformed page 1, …). That IS the verdict: the file is not a usable database.
            "quick_check failed: ${e.message}"
        } finally {
            runCatching { db.close() }
        }
    }

    // ── Cross-platform / cross-fork row-copy import (version-agnostic) ────────────

    /**
     * Decide whether [tableNames] + [dailyMetricColumns] describe a FOREIGN backup this app should
     * reconcile (row-copy) rather than file-swap, and if so from where. Content-based, never
     * version-based:
     *  - a GRDB store (`grdb_migrations`) is the iOS / Mac NOOP app → [ForeignBackupKind.IOS];
     *  - a Room store carrying a table or column THIS build's schema doesn't have — a divergent
     *    Android NOOP fork — → [ForeignBackupKind.ANDROID_FORK]: its ahead/renamed schema can't be
     *    brought forward by this app's Room migrator, so a raw file swap would strand or wipe it.
     * Returns null for our OWN Room backup (including an older one the migrator can still open, and a
     * fork that is merely BEHIND — a version difference, not a content divergence) and for an
     * empty/unrecognised file: those keep the existing same-app restore / open-time-migrator path.
     *
     * The fork-only markers are the two tables/columns no upstream NOOP schema carries: the
     * `spo2PctSample` table and `dailyMetric.skinTempAbsC`. Pure (no DB open) so it is unit-tested
     * directly on the plain JVM.
     */
    fun foreignBackupKind(tableNames: Set<String>, dailyMetricColumns: Set<String>): ForeignBackupKind? {
        if (tableNames.contains("grdb_migrations")) return ForeignBackupKind.IOS
        if (tableNames.contains("room_master_table")) {
            if (tableNames.contains("spo2PctSample") || dailyMetricColumns.contains("skinTempAbsC")) {
                return ForeignBackupKind.ANDROID_FORK
            }
        }
        return null
    }

    /** How a foreign backup's rows fold into the target store. */
    enum class ImportMode { MERGE, REPLACE }

    /**
     * One column's identity plus the two `PRAGMA table_info` facts the row copy needs to keep a
     * source-absent NOT NULL column instead of dropping its rows: whether it is NOT NULL, and whether it
     * carries a schema DEFAULT (`dflt_value`). [type] is the declared type, the affinity source for the
     * typed zero literal a filled column gets.
     *
     * The distinction is load-bearing across platforms: Room emits NO SQL default for a Kotlin `= 0`
     * property, so an Android `synced` flag is NOT NULL with no default, whereas the GRDB twin of the
     * same column is written `NOT NULL DEFAULT 0`. Omitting such a column from an `INSERT OR IGNORE`
     * makes SQLite drop EVERY row (a NOT NULL violation, silently ignored) instead of filling a default —
     * which is why the copy must read these facts and fill the column explicitly.
     */
    internal data class SchemaColumn(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val hasDefault: Boolean,
        /**
         * True when this column is part of the table's PRIMARY KEY or any UNIQUE index. A source-absent
         * NOT NULL-no-default column that is a KEY must NOT be CONSTANT-filled: every row would get the
         * SAME typed zero, so `INSERT OR IGNORE` would treat all but the first as a key clash and collapse
         * the whole table to one row. The planner fills it with the source `rowid` (per-row-unique) instead,
         * so the rows import. Read from `PRAGMA table_info` (`pk` > 0) plus `PRAGMA index_list` / `index_info`.
         */
        val key: Boolean = false,
    )

    /**
     * A content-based import plan: the SQL to run, plus what didn't line up (surfaced to the user as
     * warnings, never as a hard error). [statements] run inside one transaction with `src` ATTACHed.
     */
    internal data class RowCopyPlan(
        val statements: List<String>,
        /** Target tables absent from the backup — no data to import for them. */
        val missingTables: List<String>,
        /** Backup tables with no home in the target — their rows are skipped. */
        val droppedTables: List<String>,
        /** Per table, source-absent target columns that are NULLABLE or carry a DEFAULT — omitted from the
         *  INSERT so SQLite fills NULL / the default. */
        val missingColumns: Map<String, List<String>>,
        /** Per table, source-absent target columns that are NOT NULL with NO default — KEPT in the INSERT
         *  and filled with a typed zero literal, so `INSERT OR IGNORE` can't silently drop the row. */
        val filledColumns: Map<String, List<String>> = emptyMap(),
        /** Per table, source-absent NOT NULL-no-default KEY (PK / UNIQUE) columns filled with the source
         *  `rowid` (a per-row-unique id) so the rows import without a constant collapsing the table. Maps
         *  the table to those key column(s). */
        val synthesizedKeyColumns: Map<String, List<String>> = emptyMap(),
        /** Tables an INSERT was actually emitted for — the set the reconcile row-count backstop verifies. */
        val copiedTables: List<String> = emptyList(),
    ) {
        /** Human-readable warnings, empty when the backup lines up cleanly. */
        fun warnings(): List<String> = buildList {
            if (missingTables.isNotEmpty()) add("No data in this backup for: ${missingTables.joinToString(", ")}.")
            if (droppedTables.isNotEmpty()) add("Skipped tables not in this app: ${droppedTables.joinToString(", ")}.")
            // A key column the backup didn't carry, filled with a generated id so the rows still import.
            synthesizedKeyColumns.forEach { (t, cols) ->
                add("$t: generated ids for the key column(s) ${cols.joinToString(", ")} this backup didn't carry.")
            }
            // Filled columns are KEPT (their rows survive) — say so, never "imported empty", which would
            // wrongly imply the rows were dropped. Listed before the omitted-column notes for stable order.
            filledColumns.forEach { (t, cols) -> add("$t: filled ${cols.joinToString(", ")} with defaults.") }
            missingColumns.forEach { (t, cols) -> add("$t is missing fields ${cols.joinToString(", ")} (imported empty).") }
        }
    }

    /** SQLite / Room / GRDB bookkeeping tables — never row-copied: they carry the target's own
     *  identity, autoincrement counters and migration ledger, which copying would corrupt. */
    private val HOUSEKEEPING_TABLES =
        setOf("android_metadata", "sqlite_sequence", "room_master_table", "grdb_migrations")

    /**
     * Plan a version-agnostic row copy from [source] into [target] — each a `table -> ordered columns`
     * map (with per-column NOT NULL / default facts) read at runtime from `PRAGMA table_info`, so it
     * needs no schema version and never touches Room's identity. For every data table in BOTH:
     *  - copy the intersection of columns (target column order preserved);
     *  - a target column ABSENT from the source that is NOT NULL with NO default is KEPT and filled with
     *    a typed zero literal — otherwise `INSERT OR IGNORE` would drop the whole table's rows on the
     *    NOT NULL violation (Room emits no SQL default for a Kotlin `= 0`, e.g. `synced`);
     *  - EXCEPT when that source-absent NOT NULL-no-default column is a KEY (PK / UNIQUE member): a constant
     *    fill would collapse the table under `INSERT OR IGNORE`, so it is filled with the source `rowid`
     *    (per-row-unique) instead, keeping the rows without collapsing (see [synthesizedKeyColumns]);
     *  - a source-absent column that is nullable or has a default is omitted (SQLite fills NULL/default).
     * Other type/quoting differences across forks/platforms are irrelevant (SQLite coerces on insert).
     * [ImportMode.REPLACE] clears each target table first (restore semantics); [ImportMode.MERGE] keeps
     * existing rows on a PK clash. Both use `INSERT OR IGNORE`, so a clash is skipped, never overwritten.
     * Mismatched tables/columns become [RowCopyPlan] warnings, not failures.
     */
    internal fun planRowCopyImport(
        target: Map<String, List<SchemaColumn>>,
        source: Map<String, List<SchemaColumn>>,
        mode: ImportMode,
    ): RowCopyPlan {
        fun dataTables(keys: Set<String>) = keys.filter { it !in HOUSEKEEPING_TABLES && !it.startsWith("sqlite_") }
        val tgt = dataTables(target.keys).toSet()
        val src = dataTables(source.keys).toSet()
        val stmts = ArrayList<String>()
        val copied = ArrayList<String>()
        val missingCols = LinkedHashMap<String, List<String>>()
        val filledCols = LinkedHashMap<String, List<String>>()
        val synthKeyCols = LinkedHashMap<String, List<String>>()
        for (t in (tgt intersect src).sorted()) {
            val srcCols = source[t]!!.map { it.name }.toSet()
            val insertCols = ArrayList<String>()
            val selectExprs = ArrayList<String>()
            val omitted = ArrayList<String>()
            val filled = ArrayList<String>()
            val synthKeys = ArrayList<String>()
            for (col in target[t]!!) {
                when {
                    // Shared column: straight copy, target order preserved.
                    col.name in srcCols -> {
                        insertCols.add(col.name)
                        selectExprs.add(quoteId(col.name))
                    }
                    // Source-absent NOT NULL-no-default column that is a KEY (PK / UNIQUE member): a CONSTANT
                    // fill would give every row the same value and INSERT OR IGNORE would collapse the table
                    // to one row. Fill the source `rowid` instead — a per-row-unique id — so the rows import
                    // without collapsing (the row-count backstop still catches any true shortfall).
                    col.notNull && !col.hasDefault && col.key -> {
                        insertCols.add(col.name)
                        selectExprs.add("rowid")
                        synthKeys.add(col.name)
                    }
                    // Source-absent NOT NULL with no default (not a key): MUST be kept + filled, or
                    // INSERT OR IGNORE drops the whole row on the NOT NULL violation. Fill a typed zero.
                    col.notNull && !col.hasDefault -> {
                        insertCols.add(col.name)
                        selectExprs.add(typedZeroLiteral(col.type))
                        filled.add(col.name)
                    }
                    // Source-absent nullable / has-default: omit it, SQLite fills NULL / the default.
                    else -> omitted.add(col.name)
                }
            }
            if (synthKeys.isNotEmpty()) synthKeyCols[t] = synthKeys
            if (omitted.isNotEmpty()) missingCols[t] = omitted
            if (filled.isNotEmpty()) filledCols[t] = filled
            if (insertCols.isEmpty()) continue
            val colList = insertCols.joinToString(", ") { quoteId(it) }
            val selList = selectExprs.joinToString(", ")
            if (mode == ImportMode.REPLACE) stmts.add("DELETE FROM main.${quoteId(t)}")
            stmts.add("INSERT OR IGNORE INTO main.${quoteId(t)} ($colList) SELECT $selList FROM src.${quoteId(t)}")
            copied.add(t)
        }
        return RowCopyPlan(
            statements = stmts,
            missingTables = (tgt - src).sorted(),
            droppedTables = (src - tgt).sorted(),
            missingColumns = missingCols,
            filledColumns = filledCols,
            synthesizedKeyColumns = synthKeyCols,
            copiedTables = copied,
        )
    }

    /** The zero literal a source-absent NOT NULL-no-default column is filled with, by declared type:
     *  TEXT (`''`), BLOB (`x''`), everything else — INTEGER, REAL, NUMERIC, and an untyped column —
     *  numeric `0`. Matches the Swift twin (`ForeignBackupImport.zeroLiteral`) exactly, so a column
     *  filled on either platform lands the same byte. The untyped case aligns to Swift's `0` purely for
     *  parity (both platforms must emit the same literal); it doesn't arise in either real Room/GRDB
     *  schema, since both always declare an affinity keyword. */
    private fun typedZeroLiteral(declaredType: String): String {
        val t = declaredType.uppercase()
        return when {
            t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT") -> "''"
            t.contains("BLOB") -> "x''"
            else -> "0"
        }
    }

    /** A backtick-quoted SQL identifier with any embedded backtick DOUBLED, so a foreign backup's table
     *  or column name (interpolated into the PRAGMA / row-copy statements below — PRAGMA can't bind an
     *  identifier parameter) can't break out of its quoting. Bounded even without this — every mutating
     *  statement targets trusted `main` names — but a stray backtick in a source identifier would
     *  otherwise throw mid-read; doubling keeps the read honest. Twin of the Swift `quoteId`. */
    private fun quoteId(id: String): String = "`" + id.replace("`", "``") + "`"

    /** Row count of [table] in [schema] ('main' or the ATTACHed 'src'), for the reconcile backstop. */
    private fun rowCount(db: SQLiteDatabase, schema: String, table: String): Long =
        db.rawQuery("SELECT count(*) FROM $schema.${quoteId(table)}", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    /** Names of every column of [table] in [schema] that participates in the PRIMARY KEY or any UNIQUE
     *  index — the columns the planner must NOT constant-fill (a shared value would collapse the table
     *  under `INSERT OR IGNORE`). PK members come from `PRAGMA table_info` (`pk` > 0); UNIQUE members from
     *  each `unique` index in `PRAGMA index_list`, expanded via `PRAGMA index_info`. */
    private fun keyColumns(db: SQLiteDatabase, schema: String, table: String): Set<String> {
        val keys = LinkedHashSet<String>()
        db.rawQuery("PRAGMA $schema.table_info(${quoteId(table)})", null).use { c ->
            val ni = c.getColumnIndex("name")
            val pi = c.getColumnIndex("pk")
            while (c.moveToNext()) {
                val name = if (ni >= 0) c.getString(ni) else null
                val isPk = pi >= 0 && c.getInt(pi) != 0
                if (name != null && isPk) keys.add(name)
            }
        }
        val uniqueIndexes = ArrayList<String>()
        db.rawQuery("PRAGMA $schema.index_list(${quoteId(table)})", null).use { il ->
            val nameIdx = il.getColumnIndex("name")
            val uniqIdx = il.getColumnIndex("unique")
            while (il.moveToNext()) {
                val unique = uniqIdx >= 0 && il.getInt(uniqIdx) != 0
                val idxName = if (nameIdx >= 0) il.getString(nameIdx) else null
                if (unique && idxName != null) uniqueIndexes.add(idxName)
            }
        }
        for (idx in uniqueIndexes) {
            db.rawQuery("PRAGMA $schema.index_info(${quoteId(idx)})", null).use { ii ->
                val cn = ii.getColumnIndex("name")
                while (ii.moveToNext()) if (cn >= 0) ii.getString(cn)?.let(keys::add)
            }
        }
        return keys
    }

    /** `table -> ordered [SchemaColumn]s` from `PRAGMA table_info` on [schema] ('main' or an ATTACHed
     *  alias). Reads each column's declared type, NOT NULL flag (`notnull`), whether it carries a
     *  DEFAULT (`dflt_value` non-null), AND whether it is a KEY (PK / UNIQUE member, via [keyColumns]),
     *  so the planner can keep + fill a source-absent NOT NULL-no-default column — but never a KEY one,
     *  which it skips — instead of silently dropping the table's rows. */
    private fun readSchema(db: SQLiteDatabase, schema: String): Map<String, List<SchemaColumn>> {
        val out = LinkedHashMap<String, List<SchemaColumn>>()
        db.rawQuery("SELECT name FROM $schema.sqlite_master WHERE type = 'table'", null).use { tc ->
            while (tc.moveToNext()) {
                val t = tc.getString(0) ?: continue
                val keyCols = keyColumns(db, schema, t)
                val cols = ArrayList<SchemaColumn>()
                db.rawQuery("PRAGMA $schema.table_info(${quoteId(t)})", null).use { cc ->
                    val ni = cc.getColumnIndex("name")
                    val ti = cc.getColumnIndex("type")
                    val nn = cc.getColumnIndex("notnull")
                    val df = cc.getColumnIndex("dflt_value")
                    while (cc.moveToNext()) {
                        val name = (if (ni >= 0) cc.getString(ni) else null) ?: continue
                        val type = (if (ti >= 0) cc.getString(ti) else null) ?: ""
                        val notNull = nn >= 0 && cc.getInt(nn) != 0
                        val hasDefault = df >= 0 && !cc.isNull(df)
                        cols.add(SchemaColumn(name, type, notNull, hasDefault, key = name in keyCols))
                    }
                }
                out[t] = cols
            }
        }
        return out
    }

    /**
     * Reconcile a foreign / cross-platform [stagedBackup] into a file carrying THIS app's exact schema
     * + Room identity, by COPYING [liveDbFile] (a valid store) and row-copying the backup's data into
     * it — REPLACE clears each shared table, then inserts the column intersection. Version-agnostic:
     * reads by table/column via [readSchema], never by schema version, so any fork's or the iOS/GRDB
     * backup lands by its logical data alone. Returns the reconciled file + [RowCopyPlan] warnings; the
     * caller swaps the returned file in through the normal snapshot / rollback path. THROWS on any SQL
     * failure (never swallows) so a torn reconcile can never masquerade as a good restore; on ANY throw
     * the half-built work file and its `-wal` / `-shm` sidecars are deleted, so a failed reconcile never
     * leaks a partial store into the cache. The live store must already exist.
     */
    fun reconcileForeignBackup(
        appContext: Context,
        liveDbFile: File,
        stagedBackup: File,
        mode: ImportMode,
    ): Pair<File, List<String>> {
        require(liveDbFile.exists()) { "no live store to reconcile the backup against" }
        val work = File(appContext.cacheDir, "import-reconciled.db")
        val artifacts = listOf(work, File(work.path + "-wal"), File(work.path + "-shm"))
        artifacts.forEach { it.delete() }
        var handedOff = false
        try {
            liveDbFile.copyTo(work, overwrite = true) // inherits this app's schema + identity; rows cleared below
            val warnings: List<String>
            val db = SQLiteDatabase.openDatabase(work.path, null, SQLiteDatabase.OPEN_READWRITE, PRESERVE_ON_CORRUPTION)
            try {
                val target = readSchema(db, "main")
                db.execSQL("ATTACH DATABASE ? AS src", arrayOf(stagedBackup.path))
                val source = readSchema(db, "src")
                val plan = planRowCopyImport(target, source, mode)
                db.beginTransaction()
                try {
                    for (s in plan.statements) db.execSQL(s)
                    // Row-count backstop (REPLACE restore only). Each copied table was cleared then re-filled
                    // from the source, so on a clean import `landed == source`. A shortfall means
                    // `INSERT OR IGNORE` silently dropped rows on a constraint the planner didn't model — a
                    // CHECK, or a UNIQUE the source didn't enforce — so ABORT (throw, rolling back) instead
                    // of committing a quietly-truncated table. The planner already prevents the key-column
                    // collapse by skipping such tables; this catches everything else, before the swap path
                    // ever touches the live store. MERGE is exempt: its `INSERT OR IGNORE` PK-clash drops
                    // are intentional (existing rows win). `src` is still ATTACHed here (DETACH is below).
                    if (mode == ImportMode.REPLACE) {
                        for (t in plan.copiedTables) {
                            val sourceRows = rowCount(db, "src", t)
                            val landed = rowCount(db, "main", t)
                            if (landed < sourceRows) {
                                throw IllegalStateException(
                                    "table \"$t\" kept only $landed of $sourceRows rows " +
                                        "(a schema constraint dropped the rest)"
                                )
                            }
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                db.execSQL("DETACH DATABASE src")
                // wal_checkpoint returns a row, so it must go through rawQuery, not execSQL. Fold the WAL
                // so `work` is a self-contained file for the swap.
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                warnings = plan.warnings()
            } finally {
                db.close()
            }
            File(work.path + "-wal").delete()
            File(work.path + "-shm").delete()
            handedOff = true
            return work to warnings
        } finally {
            // On ANY throw (copy, open, SQL, checkpoint) the work file was never handed to the caller —
            // delete it AND its -wal/-shm sidecars so nothing partial survives. On success `work` is
            // returned and only its (now-empty) sidecars were dropped above.
            if (!handedOff) artifacts.forEach { runCatching { it.delete() } }
        }
    }
}

/**
 * #1014 defence-in-depth: a Room open-helper factory whose ONLY behavioural change is corruption
 * handling. The platform DEFAULT — androidx.sqlite routes SQLITE_CORRUPT to
 * [SupportSQLiteOpenHelper.Callback.onCorruption], whose base implementation mirrors Android's
 * `DefaultDatabaseErrorHandler` — silently DELETES the corrupt database file. For NOOP that means
 * permanently destroying already-acked strap history the strap will never re-send, without the user
 * ever seeing a byte of it. Confirmed absent in this app before #1014: nothing overrode
 * onCorruption, so the delete-on-corruption default applied.
 *
 * The factory wraps the stock [FrameworkSQLiteOpenHelperFactory] and delegates every lifecycle
 * callback (configure/create/migrate/open) to Room's real callback UNCHANGED, so migrations behave
 * exactly as before. Only `onCorruption` is replaced: it logs loudly, closes the handle, and moves
 * the corrupt file aside to a TIMESTAMPED `.corrupt.<epochMillis>` quarantine (best-effort), keeping
 * the newest [MAX_CORRUPT_QUARANTINES] and pruning older ones so a crash loop can't multiply 100 MB
 * files (#661). It never silently discards the newest corrupt copy — repeated corruption preserves
 * the latest (most recovery-worthy) data, not just the first. The trade-off is deliberate and matches
 * [WhoopDatabase]'s no-destructive-fallback doctrine: the app may then fail to open the store (the
 * user sees an error instead of a silently empty app), but the corrupt file survives for
 * backup/recovery instead of vanishing.
 *
 * `allowDataLossOnRecovery` is pinned FALSE for the same reason: androidx's recovery path deletes
 * the file when an open fails, which is exactly the destruction this factory exists to prevent.
 */
class CorruptionPreservingOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val preserving = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(PreservingCallback(configuration.callback))
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        return delegate.create(preserving)
    }

    /** Delegates everything to Room's callback except the destructive corruption default. */
    private class PreservingCallback(
        private val roomCallback: SupportSQLiteOpenHelper.Callback,
    ) : SupportSQLiteOpenHelper.Callback(roomCallback.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = roomCallback.onConfigure(db)
        override fun onCreate(db: SupportSQLiteDatabase) = roomCallback.onCreate(db)
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            roomCallback.onUpgrade(db, oldVersion, newVersion)
        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            roomCallback.onDowngrade(db, oldVersion, newVersion)
        override fun onOpen(db: SupportSQLiteDatabase) = roomCallback.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            // Do NOT call super — the base implementation DELETES the file outright (non-resendable strap
            // history gone without a trace). Instead QUARANTINE the corrupt file aside and let the store
            // recreate a fresh one, so the app OPENS instead of crash-looping on every launch. #1014
            // (wanxorg): 8.2.0 preserved the file but left it in place, so the very next open re-hit the
            // same corruption and the app crashed on startup until a reinstall. Moving the original out of
            // the way keeps the crash-recovery the platform default gives (next open finds no file → clean
            // rebuild) WITHOUT the silent data loss — the corrupt copy stays as `*.corrupt` for recovery.
            val path = runCatching { db.path }.getOrNull()
            Log.e(
                "WhoopDatabase",
                "SQLite reported corruption in $path — quarantining it to *.corrupt.<epoch> and recreating " +
                    "a fresh store. The corrupt copy is kept; restore from a backup to get your data back.",
            )
            runCatching { db.close() }
            if (path != null && path != ":memory:") {
                val original = File(path)
                if (original.exists()) {
                    // #661: quarantine under a TIMESTAMPED name and keep the newest few, instead of a
                    // single fixed `.corrupt` slot. The old single-slot logic kept the FIRST corrupt copy
                    // and deleted every later one — but a later corruption is a rebuilt store that has
                    // banked new non-resendable strap history since, so the copy it dropped was the most
                    // recovery-worthy. Keeping the newest N caps disk (a crash loop can't multiply files)
                    // AND preserves the latest data plus a couple of priors for diagnosing a recurring
                    // corruption path.
                    val dir = original.parentFile
                    val dbName = original.name
                    val existing = dir?.listFiles()?.map { it.name }
                        ?.filter {
                            it.startsWith("$dbName.corrupt.") &&
                                it.substringAfterLast(".corrupt.").toLongOrNull() != null
                        } ?: emptyList()
                    val plan = planCorruptQuarantine(dbName, existing, System.currentTimeMillis())
                    val preserved = File(dir, plan.quarantineName)
                    // Move (not copy) so the original is gone and the next open rebuilds clean; fall back
                    // to copy+delete if rename fails (e.g. across a storage boundary).
                    if (!runCatching { original.renameTo(preserved) }.getOrDefault(false)) {
                        // Cross-storage rename fallback. Copy best-effort (overwrite=true so a same-
                        // millisecond re-entry of the same corrupt lineage still lands), then drop the
                        // original EITHER WAY: leaving a corrupt file on the live path re-hits corruption
                        // and crash-loops the app on every launch (#1014). Preservation is best-effort.
                        runCatching { original.copyTo(preserved, overwrite = true) }
                        runCatching { original.delete() }
                    }
                    // Move the WAL/SHM sidecars ALONGSIDE the quarantine rather than deleting them: the WAL
                    // can hold the most recent un-checkpointed writes (the newest strap data). The move
                    // still clears the live path, so the fresh rebuild can't inherit a stale write-ahead log.
                    moveSidecarBesideQuarantine("$path-wal", "${preserved.path}-wal")
                    moveSidecarBesideQuarantine("$path-shm", "${preserved.path}-shm")
                    // Prune the oldest quarantines beyond the cap (+ their moved sidecars).
                    plan.evict.forEach { name ->
                        val stale = File(dir, name)
                        runCatching { stale.delete() }
                        runCatching { File("${stale.path}-wal").delete() }
                        runCatching { File("${stale.path}-shm").delete() }
                    }
                } else {
                    // No original to quarantine — clear any orphan sidecars so the fresh rebuild is clean.
                    runCatching { File("$path-wal").delete() }
                    runCatching { File("$path-shm").delete() }
                }
            }
        }
    }
}

/** #661: at most this many `.corrupt.<epoch>` quarantines survive (newest kept), bounding disk on a
 *  repeated-corruption crash loop while preserving the latest copies for recovery/diagnosis. */
internal const val MAX_CORRUPT_QUARANTINES = 3

internal data class CorruptQuarantinePlan(
    val quarantineName: String,
    val evict: List<String>,
)

/**
 * #661: name the new corrupt-DB quarantine (`<dbName>.corrupt.<epochMillis>`) and decide which OLD
 * quarantines to evict so at most [keep] survive (newest by embedded epoch). Pure / filesystem-free so
 * it is unit-tested directly. [existing] must already be filtered to `<dbName>.corrupt.<digits>` names.
 * The just-created quarantine is never evicted, even if [keep] is 0.
 */
internal fun planCorruptQuarantine(
    dbName: String,
    existing: List<String>,
    nowMillis: Long,
    keep: Int = MAX_CORRUPT_QUARANTINES,
): CorruptQuarantinePlan {
    val quarantineName = "$dbName.corrupt.$nowMillis"
    fun epochOf(name: String): Long = name.substringAfterLast(".corrupt.").toLongOrNull() ?: 0L
    val evict = (existing + quarantineName)
        .distinct()
        .sortedByDescending { epochOf(it) }
        .drop(keep.coerceAtLeast(0))
        .filter { it != quarantineName }
    return CorruptQuarantinePlan(quarantineName, evict)
}

/** Best-effort move of a WAL/SHM sidecar next to its quarantined DB (rename, else copy+delete). */
private fun moveSidecarBesideQuarantine(fromPath: String, toPath: String) {
    val src = File(fromPath)
    if (!src.exists()) return
    val dst = File(toPath)
    if (!runCatching { src.renameTo(dst) }.getOrDefault(false)) {
        // Best-effort preserve, then ALWAYS clear the live sidecar: a stale WAL left on the live path
        // can be applied to the freshly-rebuilt DB (WAL identity is salt-based only), re-injecting
        // corrupt pages. Keeping the rebuild clean wins over preserving an un-movable sidecar.
        runCatching { src.copyTo(dst, overwrite = true) }
        runCatching { src.delete() }
    }
}

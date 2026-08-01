package com.noop.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.noop.ble.WhoopModel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-store EXPORT / IMPORT for device migration.
 *
 * NOOP keeps everything on-device in a single Room/SQLite file ([WhoopDatabase.DB_NAME]); moving
 * to a new phone means moving exactly that file, through these two explicit, user-driven SAF
 * file operations — no cloud, no account.
 *
 * Export: checkpoints the WAL into the main db file, then writes a ZIP (`.noopbak`) containing
 * the SQLite file plus an optional `settings.json` entry with the whitelisted profile/display
 * settings (see [BackupSettingsCodec]). It's a standard container — rename `.noopbak` to `.zip`
 * and extract with any archive tool.
 *
 * Import: detects a `.noopbak` ZIP (PK magic) vs a legacy plain `.sqlite`/`.noopdb` (SQLite
 * magic) and handles both. Validates the SQLite header, the backup's origin, and its structural
 * integrity (`PRAGMA quick_check`) before touching the live DB, then snapshots, swaps in the
 * chosen file, drops stale `-wal`/`-shm` sidecars, and rolls back automatically if the copy
 * tore. The caller must then restart the app so Room re-opens the fresh file.
 */
object DataBackup {

    /** Entry name of the SQLite inside the `.noopbak` ZIP. */
    private const val ZIP_ENTRY_NAME = "noop-backup.sqlite"

    /** Entry name of the optional whitelisted-settings JSON. */
    private const val SETTINGS_ENTRY_NAME = BackupSettingsCodec.ENTRY_NAME

    /** First 16 bytes of every SQLite 3 file: "SQLite format 3\0". */
    private val SQLITE_MAGIC: ByteArray =
        byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )

    /** First 4 bytes of every ZIP file: "PK\x03\x04". */
    private val ZIP_MAGIC: ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** Outcome of an [importFrom] call. On success the app must be restarted. */
    sealed interface ImportResult {
        /** The new database is in place; tell the user to relaunch NOOP. */
        data object NeedsRestart : ImportResult

        /** Import failed and the original database is untouched. */
        data class Failed(val message: String) : ImportResult
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

        // Verify the file BEFORE archiving: after the checkpoint the single file IS the whole
        // store, and a backup of an already-corrupt database only fails the import-side
        // integrity gate months later, when the original data may be long gone. Read-only probe,
        // sits safely beside the open Room connection (WAL allows concurrent readers).
        sqliteQuickCheckFailure(dbFile)?.let { complaint ->
            throw IOException(
                "Couldn't export: the NOOP database failed its integrity check (SQLite reports: " +
                    "$complaint). A backup of it would not restore. Export the WHOOP-format CSV " +
                    "instead to save what's still readable."
            )
        }

        // The whitelisted profile/display settings ride along as a second entry so a restore
        // brings back weight/height/units, not just the rows. Null (nothing user-set) degrades to
        // the legacy single-entry ZIP. The DB entry stays FIRST — older importers stop at the
        // first `.sqlite` entry.
        val settingsJson = BackupSettingsBridge.snapshotJson(appContext)

        val resolver = appContext.contentResolver
        val output = resolver.openOutputStream(uri)
            ?: throw IOException("Could not open the chosen file for writing.")
        output.use { out ->
            // Copy the file while HOLDING Room's write transaction: in WAL mode the main file is
            // only rewritten by a checkpoint, and a checkpoint only runs on a commit, so parking
            // the single write connection in an empty transaction for the duration of the copy
            // means no commit can land and the streamed bytes can't be torn mid-page. Writers
            // queue behind us and proceed after; readers are unaffected either way.
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
     * On any error the current database is left exactly as it was. On success the caller
     * MUST instruct the user to fully restart the app.
     */
    fun importFrom(context: Context, uri: Uri): ImportResult {
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

        // 2. Extract the SQLite entry if it's a ZIP (.noopbak), or copy a plain SQLite (legacy)
        //    to the same temp file, via [stageBackupSqlite] (pure file/stream, unit-testable
        //    without Room/Context). A stale settings.json is deleted first so a leftover from an
        //    earlier import can't masquerade as this backup's settings.
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

        // 3b. Route by schema content: a foreign backup (another platform, or a fork without our
        //     marker) is row-copied into a clone of the live store; our own/older backup takes
        //     the migrate path below; an unrecognized file that holds data is refused.
        val backupTables = sqliteTableNames(tempSqlite)
        var importWarnings: List<String> = emptyList()
 // True only when the own/older backup migrated cleanly (not a foreign row-copy, nor the migrate→reconcile
 // fallback) — the one case where the restored my-whoop peripheralId is the user's real local strap.
        var migratedOwnData = false
        val reconciled: Boolean
        when (foreignBackupKind(backupTables)) {
            null -> {
                if (backupOriginOf(backupTables) == BackupOrigin.UNKNOWN && holdsData(backupTables)) {
                    return rejectForeign(
                        tempSqlite,
                        tempSettings,
                        "This isn't a NOOP backup from this app. It's missing the database bookkeeping a " +
                            "NOOP backup carries (it looks like another app's database). Restoring it would " +
                            "strand your store.",
                    )
                }
                reconciled = false
            }
            else -> {
                val liveDbFile = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
                if (!liveDbFile.exists()) {
                    return rejectForeign(
                        tempSqlite,
                        tempSettings,
                        "There's no NOOP store on this device yet to merge this backup into. Open NOOP once " +
                            "to set up your store, then import again.",
                    )
                }
                importWarnings = runCatching {
                    reconcileForeign(appContext, liveDbFile, tempSqlite)
                }.getOrElse { e ->
                    return rejectForeign(
                        tempSqlite,
                        tempSettings,
                        "Couldn't bring this backup into NOOP's format: ${e.message}",
                    )
                }
                reconciled = true
            }
        }

        // 3b-mid. Own/older backup: migrate its Room schema forward before the swap. If it can't migrate
        //         (a mis-hinted foreign backup, or one too far off), fall back to a content row-copy so it
        //         still imports. A reconciled foreign backup already carries our schema, so skip it.
        if (!reconciled) {
            val migrationError = migrateBackupIfNeeded(
                appContext,
                tempSqlite,
                WhoopDatabase.SCHEMA_VERSION,
                WhoopDatabase.ALL_MIGRATIONS,
            )
            if (migrationError != null) {
                val liveDbFile = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
                if (!liveDbFile.exists()) {
                    return rejectForeign(
                        tempSqlite,
                        tempSettings,
                        "The backup could not be migrated to the current NOOP schema: $migrationError",
                    )
                }
                importWarnings = runCatching {
                    reconcileForeign(appContext, liveDbFile, tempSqlite)
                }.getOrElse { e ->
                    return rejectForeign(
                        tempSqlite,
                        tempSettings,
                        "Couldn't bring this backup into NOOP's format: ${e.message}",
                    )
                }
            } else {
                migratedOwnData = true
            }
        }

        // 3c. Gates 3 and 3b read only the FIRST pages of the file — the 16-byte magic and
        //     sqlite_master both survive a backup truncated mid-upload or torn by a flaky
        //     drive/cloud client, and such a file then "restores" into a store that silently
        //     shows no data. Run `PRAGMA quick_check` over the STAGED file, read-only, BEFORE the
        //     live DB is touched, and refuse the swap honestly. quick_check (not integrity_check)
        //     skips index-content verification so it stays fast on a 100 MB+ library while still
        //     catching truncation and malformed pages.
        sqliteQuickCheckFailure(tempSqlite)?.let { complaint ->
            tempSqlite.delete()
            tempSettings.delete()
            return ImportResult.Failed(
                "This backup file is damaged and can't be restored (SQLite reports: $complaint). " +
                    "Your current data is untouched. Try an earlier backup file."
            )
        }

        val dbFile = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
        val pending = File(dbFile.path + WhoopDatabase.PENDING_RESTORE_SUFFIX)

        // 4. Stage the reconciled store beside the live DB. The swap into place is DEFERRED to
        //    the next launch ([WhoopDatabase.applyPendingRestore] from Application.onCreate,
        //    before Room opens), so no live connection or background coroutine can re-open the
        //    file mid-swap. The live DB is left untouched here.
        try {
            pending.parentFile?.mkdirs()
            pending.delete()
            tempSqlite.copyTo(pending, overwrite = true)
        } catch (e: IOException) {
            pending.delete()
            tempSqlite.delete()
            tempSettings.delete()
            return ImportResult.Failed("Import failed, your data is unchanged: ${e.message}")
        }
        tempSqlite.delete()

        // 4b. Re-verify the STAGED copy (the copy itself can tear — disk-full, a dying flash chip), so the
        //     next launch never swaps in a torn store. The live DB is untouched, so a failure just discards
        //     the staged file and leaves the current data in place.
        sqliteQuickCheckFailure(pending)?.let { complaint ->
            pending.delete()
            tempSettings.delete()
            return ImportResult.Failed(
                "This backup file is damaged and can't be restored (SQLite reports: $complaint). " +
                    "Your current data is untouched. Try an earlier backup file.",
            )
        }

        // 5. Re-apply the backup's whitelisted profile/display settings (weight, height, age, sex, HR-max
        //    override, unit prefs). SharedPreferences are independent of the DB file, so they persist across
        //    the relaunch that applies the staged store. A malformed entry degrades to "fewer keys applied".
        if (tempSettings.exists()) {
            runCatching {
                BackupSettingsBridge.apply(appContext, tempSettings.readText(Charsets.UTF_8))
            }
            tempSettings.delete()
        }

 // An own-data (migrate) restore carries the user's real strap identity in the migrated
 // my-whoop row, but SharedPreferences don't survive a fresh install, so rehydrate the
 // reconnect target from the DB instead — the strap then reconnects and shows in Devices.
 // Foreign row-copies are excluded, so a foreign backup's my-whoop can't masquerade as a local band.
        if (migratedOwnData) {
            rehydrateLastDeviceFromOwnBackup(appContext, pending)
        }

        // Record the restore time so the export can correlate a restore with a later write stall.
        runCatching {
            com.noop.ui.NoopPrefs.of(appContext).edit()
                .putLong("backup.lastRestoreAt", System.currentTimeMillis() / 1000L).apply()
        }
        // Cross-fork row-copy notes (tables/columns that didn't line up) are best-effort diagnostics.
        if (importWarnings.isNotEmpty()) {
            Log.i("DataBackup", "Cross-fork import notes: ${importWarnings.joinToString("; ")}")
        }
        return ImportResult.NeedsRestart
    }

    // ── Container staging (pure file/stream layer, unit-tested under real file I/O) ──────

    /** Outcome of [stageBackupSqlite]: the SQLite was staged, or why it wasn't. */
    enum class StageResult { OK, CANNOT_OPEN, NO_DB_IN_ZIP, NOT_A_BACKUP }

    /**
     * Stages the SQLite payload of a backup into [dest], from an already-opened [input] stream
     * whose first bytes are [header]: extracts the `.sqlite` entry from a `.noopbak` ZIP, or
     * copies a legacy plain SQLite through; closes [input]. Context-free + stream-driven so unit
     * tests exercise the exact extraction the live import uses.
     *
     * When [settingsDest] is given, a `settings.json` entry is also staged there if the ZIP
     * carries one, in either entry order. Its absence is not an error, and it never affects the
     * returned [StageResult]: the DB is the payload that decides success.
     *
     * Does NOT validate the staged file's SQLite header or origin; [importFrom] does that next,
     * on the staged file — keeping staging and validation separate keeps each pure-testable.
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
                                !entry.isDirectory && !foundDb && entry.name.endsWith(".sqlite") -> {
                                    FileOutputStream(dest).use { out -> zip.copyTo(out) }
                                    foundDb = true
                                }
                                !entry.isDirectory && !foundSettings && settingsDest != null &&
                                    entry.name.substringAfterLast('/') == SETTINGS_ENTRY_NAME -> {
                                    FileOutputStream(settingsDest).use { out -> zip.copyTo(out) }
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
                    FileOutputStream(dest).use { out -> stream.copyTo(out) }
                    return StageResult.OK
                }
                else -> return StageResult.NOT_A_BACKUP
            }
        }
    }

    /** Writes [dbFile]'s bytes into a deflate ZIP at [dest] (the `.noopbak` container), DB entry
     *  first, plus the optional `settings.json` entry when [settingsJson] is non-null.
     *  Context-free twin of the stream [exportTo] writes, so tests round-trip either shape
     *  (legacy single-entry when [settingsJson] is null). */
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

    // ── Origin validation ────────────────────────────────────────────────────

    /** Which platform produced a NOOP backup, judged by its migrator's bookkeeping table. */
    enum class BackupOrigin { MAC, ANDROID, UNKNOWN }

    /**
     * Classifies a backup by its `sqlite_master` tables: Room (this app) writes
     * `room_master_table`, a foreign backup writes `grdb_migrations`. `.UNKNOWN` (neither) falls
     * through to the normal import path, where Room's migrator decides; on both present, this
     * platform's marker wins — restoring our own store is the less destructive read.
     */
    fun backupOriginOf(tableNames: Set<String>): BackupOrigin {
        if (tableNames.contains("room_master_table")) return BackupOrigin.ANDROID
        if (tableNames.contains("grdb_migrations")) return BackupOrigin.MAC
        // Older Room layouts didn't carry `room_master_table`; treat the Room/AndroidX pairing of
        // `android_metadata` + `sqlite_sequence` as one of ours too.
        if (tableNames.contains("android_metadata") && tableNames.contains("sqlite_sequence")) {
            return BackupOrigin.ANDROID
        }
        return BackupOrigin.UNKNOWN
    }

    /**
     * True when this backup carries any user-content table beyond the SQLite/Android
     * housekeeping ones (vs an empty/fresh file). An `.UNKNOWN` file with no content is harmless
     * to restore; one WITH content but no recognised bookkeeping is some other app's database.
     */
    fun holdsData(tableNames: Set<String>): Boolean {
        val housekeeping = setOf("android_metadata", "sqlite_sequence", "room_master_table", "grdb_migrations")
        return tableNames.any { it !in housekeeping && !it.startsWith("sqlite_") }
    }

    /** Every table name in [file], opened READ-ONLY so the probed file is never mutated. Empty on
     *  failure. Carries [PRESERVE_ON_CORRUPTION]: without an explicit handler the framework
     *  default would DELETE the staged file when the open reports SQLITE_NOTADB/CORRUPT. */
    private fun sqliteTableNames(file: File): Set<String> {
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, PRESERVE_ON_CORRUPTION)
        }.getOrNull() ?: return emptySet()
        return try {
            val names = LinkedHashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                while (c.moveToNext()) c.getString(0)?.let(names::add)
            }
            names
        } catch (e: Exception) {
            emptySet()
        } finally {
            runCatching { db.close() }
        }
    }

    /** Every column name on [table] in [file], opened READ-ONLY so the probed file is never mutated.
     *  Empty on failure or an absent table. Carries [PRESERVE_ON_CORRUPTION] for the same reason
     *  [sqliteTableNames] does. */
    private fun sqliteColumnNames(file: File, table: String): Set<String> {
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, PRESERVE_ON_CORRUPTION)
        }.getOrNull() ?: return emptySet()
        return try {
            val names = LinkedHashSet<String>()
            db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) {
                    if (nameIdx >= 0) c.getString(nameIdx)?.let(names::add)
                }
            }
            names
        } catch (e: Exception) {
            emptySet()
        } finally {
            runCatching { db.close() }
        }
    }

    /** After an own-data (migrate) restore, copy the migrated "my-whoop" strap address into
     *  NoopPrefs.lastDevice — the reconnect target + the Devices "bound here" signal a fresh install lacks.
     *  Read-only, best-effort; a foreign row-copy never calls this, so a foreign peripheralId can't leak. */
    private fun rehydrateLastDeviceFromOwnBackup(appContext: Context, restored: File) {
        runCatching {
            val db = SQLiteDatabase.openDatabase(restored.path, null, SQLiteDatabase.OPEN_READONLY, PRESERVE_ON_CORRUPTION)
            try {
                db.rawQuery(
                    "SELECT peripheralId, model FROM pairedDevice WHERE id = 'my-whoop' AND peripheralId IS NOT NULL LIMIT 1",
                    null,
                ).use { c ->
                    if (c.moveToFirst()) {
                        val addr = c.getString(0)
                        val modelText = c.getString(1) ?: ""
                        // Model self-corrects on the live handshake (whoop5Detected) and a wrong family
                        // rotates via fallbackScanModel, so a default is safe; the address is what pins it.
                        if (!addr.isNullOrBlank()) {
                            val model = if (modelText.contains("4")) WhoopModel.WHOOP4 else WhoopModel.WHOOP5_MG
                            com.noop.ui.NoopPrefs.setLastDevice(appContext, addr, model)
                        }
                    }
                }
            } finally {
                runCatching { db.close() }
            }
        }
    }

    /** Delete the staged temp files and return a Failed result, keeping the live DB untouched. */
    private fun rejectForeign(tempSqlite: File, tempSettings: File, message: String): ImportResult {
        tempSqlite.delete()
        tempSettings.delete()
        return ImportResult.Failed(message)
    }

    // ── Integrity gate ───────────────────────────────────────────────────

    /**
     * Classifies the rows `PRAGMA quick_check` returned: null = healthy (the single canonical
     * "ok" row), otherwise the first complaint row VERBATIM, never a fabricated summary. An
     * EMPTY result set is also a failure — quick_check always answers, so silence means the
     * query was swallowed. Public so [DataBackupIntegrityTest] can drive it on the plain JVM.
     */
    fun quickCheckVerdict(rows: List<String>): String? {
        if (rows.size == 1 && rows[0].equals("ok", ignoreCase = true)) return null
        return rows.firstOrNull { !it.equals("ok", ignoreCase = true) }
            ?: "quick_check returned no verdict"
    }

    /**
     * A [android.database.DatabaseErrorHandler] that closes the handle and PRESERVES the file,
     * unlike the framework default ([android.database.DefaultDatabaseErrorHandler]), which
     * DELETES the file on SQLITE_CORRUPT/SQLITE_NOTADB — catastrophic here since the export probe
     * opens the LIVE database. Also used by the origin probe for the same reason.
     */
    private val PRESERVE_ON_CORRUPTION = android.database.DatabaseErrorHandler { dbObj ->
        runCatching { dbObj.close() }
    }

    /**
     * `PRAGMA quick_check(1)` on [file]: null = healthy, else a short complaint for the caller's
     * failure message; stops at the first error so even a large file answers fast. Opens
     * READ-ONLY first, falling back to READ-WRITE only because pre-3.22 SQLite (minSdk 26)
     * cannot read-only-open a WAL-header file without an initialized `-shm`, which is exactly
     * what a checkpointed staged backup looks like. Both opens carry [PRESERVE_ON_CORRUPTION] so
     * no probe can ever delete what it probes.
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

    /**
     * Byte offset of `PRAGMA user_version` in the SQLite 3 database header — a big-endian 32-bit integer
     * at offset 60 (offset 64 is the incremental-vacuum field, offset 68 the application_id).
     */
    private const val USER_VERSION_OFFSET = 60

    /** Bytes of the SQLite header needed to extract [USER_VERSION_OFFSET] (offset + 4). */
    private const val HEADER_READ_SIZE = 64

    /**
     * Read the Room schema version (`PRAGMA user_version`) from a SQLite file's database header,
     * at [USER_VERSION_OFFSET]. Pure file I/O — no Android SQLite API needed, so it's testable on
     * the plain JVM alongside [isValidSqliteHeader] and the other byte-level functions.
     *
     * Returns null when the file is not (or is too small to be) a valid SQLite database.
     */
    fun readUserVersion(file: File): Int? {
        val buf = ByteArray(HEADER_READ_SIZE)
        val read = runCatching {
            file.inputStream().use { readFully(it, buf) }
        }.getOrNull() ?: return null
        if (read < HEADER_READ_SIZE) return null
        if (!buf.copyOf(SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) return null
        return ((buf[USER_VERSION_OFFSET].toInt() and 0xFF) shl 24) or
            ((buf[USER_VERSION_OFFSET + 1].toInt() and 0xFF) shl 16) or
            ((buf[USER_VERSION_OFFSET + 2].toInt() and 0xFF) shl 8) or
            (buf[USER_VERSION_OFFSET + 3].toInt() and 0xFF)
    }

    /**
     * Whether a staged store at [currentVersion] needs the migrate/heal path, given the `pairedDevice`
     * columns it carries. False for an empty file (v0 = fresh Room, a foreign one is refused earlier),
     * for anything ABOVE [targetVersion] — never repaired downward — and for a store already current in
     * both version and shape. A store AT the target but missing a column the version absorbed while
     * unreleased is the one same-version case that still needs work.
     */
    internal fun needsSchemaWork(currentVersion: Int, targetVersion: Int, pairedDeviceColumns: Set<String>): Boolean {
        if (currentVersion <= 0 || currentVersion > targetVersion) return false
        if (currentVersion < targetVersion) return true
        // No readable `pairedDevice` at the current version is an unreadable or foreign file, not a
        // pre-fold one — leave it to the integrity gate rather than opening it read-write.
        if (pairedDeviceColumns.isEmpty()) return false
        return WhoopDatabase.missingDeviceScopeSql(pairedDeviceColumns).isNotEmpty()
    }

    /**
     * Migrates a staged backup SQLite file to the current Room schema version. MUST run after
     * staging/origin validation but BEFORE [sqliteQuickCheckFailure], so the migration is
     * verified before it touches the live DB. Pins the open callback to the file's current
     * version (skips [onUpgrade]), then runs the applicable [Migration]s and sets [targetVersion]
     * directly. A backup already AT [targetVersion] is skipped untouched unless [needsSchemaWork]
     * says its shape predates a column the version absorbed while unreleased.
     * Returns null on success, else an error string — the caller must then delete [stagedFile],
     * since its schema is not safe to restore.
     */
    fun migrateBackupIfNeeded(
        appContext: Context,
        stagedFile: File,
        targetVersion: Int,
        migrations: List<Migration>,
    ): String? {
        val currentVersion = readUserVersion(stagedFile)
            ?: return "Could not read the backup's schema version."

        if (!needsSchemaWork(currentVersion, targetVersion, sqliteColumnNames(stagedFile, "pairedDevice"))) {
            return null
        }

        val plan = planMigrationPath(currentVersion, targetVersion, migrations)
        val applicable = plan.path ?: return plan.error

        // Open the staged file read-write through FrameworkSQLiteOpenHelper, which wraps a raw
        // SQLiteDatabase as a SupportSQLiteDatabase — exactly what Migration.migrate() expects.
        return runCatching {
            val config = SupportSQLiteOpenHelper.Configuration.builder(appContext)
                .name(stagedFile.absolutePath)
                .callback(object : SupportSQLiteOpenHelper.Callback(currentVersion) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .allowDataLossOnRecovery(false)
                .build()

            val factory = FrameworkSQLiteOpenHelperFactory()
            val helper = factory.create(config)
            try {
                val db = helper.writableDatabase
                for (migration in applicable) {
                    migration.migrate(db)
                }
                // Additive, idempotent, and the only step a same-version backup needs: a file written
                // before v101 absorbed its last columns carries the version without them.
                WhoopDatabase.healDeviceScopeColumns(db)
                db.execSQL("PRAGMA user_version = $targetVersion")
                // Manual migration updates SCHEMA + user_version but not Room's identity
                // bookkeeping (room_master_table), which Room only rewrites on its own migration —
                // left stale, the app rejects the store ("Room cannot verify the data
                // integrity"). Copy the identity from a live store at the same schema instead
                // (best-effort: an unreadable hash leaves prior behaviour).
                runCatching {
                    val liveHash = WhoopDatabase.get(appContext).openHelper.writableDatabase
                        .query("SELECT identity_hash FROM room_master_table LIMIT 1").use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    if (liveHash != null) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, ?)", arrayOf(liveHash))
                    }
                }
            } finally {
                helper.close()
            }
            null // success
        }.getOrElse { e ->
            "Migration from v$currentVersion to v$targetVersion failed: ${e.message}"
        }
    }

    /** Result of [planMigrationPath]: the ordered migrations to run, or an [error] when no step exists. */
    internal data class MigrationPlan(val path: List<Migration>?, val error: String?)

    /**
     * Plans the path from [currentVersion] to [targetVersion]: each step takes the migration that
     * starts at the current version and jumps FURTHEST (endVersion <= target) — Room's own
     * resolution rule, so a catch-all range reaches target without a strictly contiguous
     * per-version chain. Returns an empty path when already at/after target, the ordered path on
     * success, or a null path with [MigrationPlan.error] set when some version has no outgoing migration.
     */
    internal fun planMigrationPath(currentVersion: Int, targetVersion: Int, migrations: List<Migration>): MigrationPlan {
        if (currentVersion >= targetVersion) return MigrationPlan(emptyList(), null)
        val startsAt = migrations.filter { it.endVersion <= targetVersion }.groupBy { it.startVersion }
        val path = ArrayList<Migration>()
        var at = currentVersion
        while (at < targetVersion) {
            val next = startsAt[at]?.maxByOrNull { it.endVersion }
                ?: return MigrationPlan(null, "No migration path from schema v$at to v$targetVersion.")
            path.add(next)
            at = next.endVersion
        }
        return MigrationPlan(path, null)
    }

    // ── Content-based (version-agnostic) row-copy import ──────────────────────────

    /** How a backup's rows fold into the target store. */
    enum class ImportMode { MERGE, REPLACE }

    /** A foreign store to reconcile in by row-copy. */
    private enum class ForeignBackupKind { IOS, CROSS_FORK }

    /** A `grdb_migrations` table, or a Room store missing our `spo2PctSample` marker, means a
     *  foreign backup — both row-copied. Our own backup (has the marker) and an unrecognized file
     *  return null for the migrate/reject path. Fast-path hint only: a misread marker is caught
     *  by the migrate-then-reconcile fallback in [importFrom]. */
    private fun foreignBackupKind(tableNames: Set<String>): ForeignBackupKind? {
        if (tableNames.contains("grdb_migrations")) return ForeignBackupKind.IOS
        if (tableNames.contains("room_master_table") && !tableNames.contains("spo2PctSample")) {
            return ForeignBackupKind.CROSS_FORK
        }
        return null
    }

    /**
     * A content-based import plan: the SQL to run, plus what didn't line up (surfaced as warnings, never a
     * hard error). [statements] run inside one transaction with `src` ATTACHed.
     */
    internal data class RowCopyPlan(
        val statements: List<String>,
        /** Target tables absent from the backup — no data to import for them. */
        val missingTables: List<String>,
        /** Backup tables with no home in the target — their rows are skipped. */
        val droppedTables: List<String>,
        /** Per table, source-absent target columns that are nullable/defaulted — omitted, imported empty. */
        val missingColumns: Map<String, List<String>>,
        /** Per table, source-absent NOT NULL-no-default columns kept + filled with a typed zero. */
        val filledColumns: Map<String, List<String>> = emptyMap(),
        /** Per table, source-absent NOT NULL-no-default KEY columns filled with the source rowid (a unique
         *  per-row id) so the rows import instead of the table collapsing. */
        val synthesizedKeyColumns: Map<String, List<String>> = emptyMap(),
    ) {
        /** Human-readable warnings, empty when the backup lines up cleanly. */
        fun warnings(): List<String> = buildList {
            if (missingTables.isNotEmpty()) add("No data in this backup for: ${missingTables.joinToString(", ")}.")
            if (droppedTables.isNotEmpty()) add("Skipped tables not in this app: ${droppedTables.joinToString(", ")}.")
            synthesizedKeyColumns.forEach { (t, cols) ->
                add("$t: generated ids for the key column(s) ${cols.joinToString(", ")} this backup didn't carry.")
            }
            filledColumns.forEach { (t, cols) -> add("$t: filled ${cols.joinToString(", ")} with defaults.") }
            missingColumns.forEach { (t, cols) -> add("$t is missing fields ${cols.joinToString(", ")} (imported empty).") }
        }
    }

    private val HOUSEKEEPING_TABLES = setOf("android_metadata", "sqlite_sequence", "room_master_table", "grdb_migrations")

    /** One column as PRAGMA table_info reports it: name, declared type, NOT NULL, has-schema-default, and
     *  whether it is part of the PRIMARY KEY or a UNIQUE index. */
    internal data class SchemaColumn(
        val name: String,
        val type: String = "",
        val notNull: Boolean = false,
        val hasDefault: Boolean = false,
        val key: Boolean = false,
    )

    /** Copy the shared table+column intersection from [source] into [target] (read from PRAGMA, no schema
     *  version). A source-absent NOT NULL-no-default column is filled with a typed zero; if it is a KEY it is
     *  filled with the source rowid (unique, never collapses). REPLACE clears each table first; MERGE keeps clashes. */
    internal fun planRowCopyImport(
        target: Map<String, List<SchemaColumn>>,
        source: Map<String, List<SchemaColumn>>,
        mode: ImportMode,
    ): RowCopyPlan {
        fun dataTables(keys: Set<String>) = keys.filter { it !in HOUSEKEEPING_TABLES && !it.startsWith("sqlite_") }
        val tgt = dataTables(target.keys).toSet()
        val src = dataTables(source.keys).toSet()
        val stmts = ArrayList<String>()
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
                    col.name in srcCols -> {
                        insertCols.add(col.name)
                        selectExprs.add(quoteId(col.name))
                    }
                    // A source-absent NOT NULL-no-default KEY: fill the source rowid, a unique per-row id, so
                    // INSERT OR IGNORE keeps every row instead of a constant collapsing the table.
                    col.notNull && !col.hasDefault && col.key -> {
                        insertCols.add(col.name)
                        selectExprs.add("rowid")
                        synthKeys.add(col.name)
                    }
                    col.notNull && !col.hasDefault -> {
                        insertCols.add(col.name)
                        selectExprs.add(typedZeroLiteral(col.type))
                        filled.add(col.name)
                    }
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
        }
        return RowCopyPlan(
            statements = stmts,
            missingTables = (tgt - src).sorted(),
            droppedTables = (src - tgt).sorted(),
            missingColumns = missingCols,
            filledColumns = filledCols,
            synthesizedKeyColumns = synthKeyCols,
        )
    }

    /** The zero a source-absent NOT NULL-no-default column is filled with, by declared type: TEXT `''`,
     *  BLOB `x''`, everything else (INTEGER/REAL/NUMERIC/untyped) `0`. */
    private fun typedZeroLiteral(declaredType: String): String {
        val t = declaredType.uppercase()
        return when {
            t.contains("CHAR") || t.contains("CLOB") || t.contains("TEXT") -> "''"
            t.contains("BLOB") -> "x''"
            else -> "0"
        }
    }

    /** A backtick-quoted identifier with embedded backticks doubled, so a foreign name can't break the SQL. */
    private fun quoteId(id: String): String = "`" + id.replace("`", "``") + "`"

    /** Column names of [table] in [schema] that are part of the PRIMARY KEY or a UNIQUE index — the ones a
     *  constant fill must never touch. PK from table_info.pk, UNIQUE from index_list/index_info. */
    private fun keyColumns(db: SQLiteDatabase, schema: String, table: String): Set<String> {
        val keys = LinkedHashSet<String>()
        db.rawQuery("PRAGMA $schema.table_info(${quoteId(table)})", null).use { c ->
            val ni = c.getColumnIndex("name")
            val pi = c.getColumnIndex("pk")
            while (c.moveToNext()) {
                val name = if (ni >= 0) c.getString(ni) else null
                if (name != null && pi >= 0 && c.getInt(pi) != 0) keys.add(name)
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

    /** `table -> ordered [SchemaColumn]s` from PRAGMA table_info on [schema] ('main' or an ATTACHed alias),
     *  carrying the NOT NULL / default / key facts the planner needs to keep rows instead of dropping them. */
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
                        cols.add(SchemaColumn(name, type, notNull, hasDefault, name in keyCols))
                    }
                }
                out[t] = cols
            }
        }
        return out
    }

    /**
     * Reconciles a foreign [stagedBackup] into a file carrying this app's current schema +
     * identity, by copying [liveDbFile] (a valid Room store) and row-copying the backup's data
     * into it — REPLACE clears each shared table, then inserts the column intersection.
     * Version-agnostic: reads by table/column via [readSchema], never by schema version, so any
     * foreign backup lands by logical data alone. The live store must already exist; returns the
     * reconciled file + [RowCopyPlan] warnings, or throws on failure.
     */
    fun reconcileForeignBackup(
        appContext: Context,
        liveDbFile: File,
        stagedBackup: File,
        mode: ImportMode,
    ): Pair<File, List<String>> {
        require(liveDbFile.exists()) { "no live store to reconcile the backup against" }
        val work = File(appContext.cacheDir, "import-reconciled.db")
        listOf(work, File(work.path + "-wal"), File(work.path + "-shm")).forEach { it.delete() }
        liveDbFile.copyTo(work, overwrite = true) // exact schema + identity (local rows cleared below)
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
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.execSQL("DETACH DATABASE src")
            // wal_checkpoint returns a row, so it must go through rawQuery, not execSQL. Fold the WAL so
            // `work` is a self-contained file for the swap.
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            warnings = plan.warnings()
        } finally {
            db.close()
        }
        File(work.path + "-wal").delete()
        File(work.path + "-shm").delete()
        return work to warnings
    }

    /** Reconcile a foreign [staged] backup, staging the result back over [staged]. The live store is cloned
     *  while Room stays OPEN — a checkpoint plus a held transaction keep the copy quiescent — then the backup
     *  is row-copied into the clone. Returns the row-copy warnings; throws (live file untouched) on failure. */
    private fun reconcileForeign(appContext: Context, liveDbFile: File, staged: File): List<String> {
        val clone = File(appContext.cacheDir, "import-live-clone.db")
        val sidecars = listOf(clone, File(clone.path + "-wal"), File(clone.path + "-shm"))
        sidecars.forEach { it.delete() }
        try {
            val liveDb = WhoopDatabase.get(appContext)
            liveDb.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            liveDb.runInTransaction { liveDbFile.copyTo(clone, overwrite = true) }
            val (work, warnings) = reconcileForeignBackup(appContext, clone, staged, ImportMode.REPLACE)
            work.copyTo(staged, overwrite = true)
            work.delete()
            return warnings
        } finally {
            sidecars.forEach { runCatching { it.delete() } }
        }
    }
}

/**
 * A Room open-helper factory whose ONLY behavioural change is corruption handling. The platform
 * default routes SQLITE_CORRUPT to [SupportSQLiteOpenHelper.Callback.onCorruption], whose base
 * implementation silently DELETES the corrupt database file — for NOOP that means permanently
 * destroying already-acked strap history the strap will never re-send.
 *
 * Wraps the stock [FrameworkSQLiteOpenHelperFactory] and delegates every lifecycle callback
 * (configure/create/migrate/open) to Room's real callback UNCHANGED. Only `onCorruption` is
 * replaced: it logs loudly, closes the handle, sets ONE `.corrupt` sibling copy aside
 * (best-effort, skipped when one already exists so repeated failed opens can't multiply 100 MB
 * files), and deletes NOTHING — matching [WhoopDatabase]'s no-destructive-fallback doctrine: the
 * app may fail to open the store, but the file survives for recovery instead of vanishing.
 * `allowDataLossOnRecovery` is pinned FALSE for the same reason.
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
            // Do NOT call super — the base implementation DELETES the file outright (non-resendable
            // strap history gone without a trace). QUARANTINE the corrupt file aside instead and
            // let the store recreate a fresh one: leaving it in place would re-hit the same
            // corruption on the very next open and crash-loop the app. Moving (not leaving) the
            // original keeps the platform's normal crash-recovery (next open finds no file → clean
            // rebuild) WITHOUT the silent data loss — the corrupt copy stays as `*.corrupt` for recovery.
            val path = runCatching { db.path }.getOrNull()
            Log.e(
                "WhoopDatabase",
                "SQLite reported corruption in $path — quarantining it to *.corrupt and recreating a fresh " +
                    "store. The corrupt copy is kept; restore from a backup to get your data back.",
            )
            runCatching { db.close() }
            if (path != null && path != ":memory:") {
                val original = File(path)
                if (original.exists()) {
                    val preserved = File("$path.corrupt")
                    if (!preserved.exists()) {
                        // Move (not copy) so the original is gone and the next open rebuilds clean. Fall
                        // back to copy+delete if rename fails (e.g. across a storage boundary).
                        if (!runCatching { original.renameTo(preserved) }.getOrDefault(false)) {
                            runCatching { original.copyTo(preserved, overwrite = false) }
                            runCatching { original.delete() }
                        }
                    } else {
                        // A quarantine copy already exists — just drop the still-corrupt original.
                        runCatching { original.delete() }
                    }
                }
                // Drop the WAL/SHM sidecars so a fresh DB can't inherit a stale write-ahead log.
                runCatching { File("$path-wal").delete() }
                runCatching { File("$path-shm").delete() }
            }
        }
    }
}

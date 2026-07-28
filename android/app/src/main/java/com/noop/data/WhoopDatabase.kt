package com.noop.data

import android.content.Context
import java.io.File
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Local Room database. Holds phone-collected raw streams AND the offline cache of
 * server-computed derived metrics.
 *
 * Each migration is additive (ALTER/CREATE only), never destructive, so a user's
 * already-offloaded raw streams survive (the strap trims acked history and won't
 * re-send it). exportSchema=false means there's no build-time schema check, so a
 * hand-written-SQL mismatch would otherwise SILENTLY wipe that history; there is no
 * destructive fallback — Room throws loudly instead, and MigrationRoundTripTest
 * guards the SQL in CI.
 */
@Database(
    entities = [
        DeviceRow::class,
        HrSample::class,
        RrInterval::class,
        EventRow::class,
        BatterySample::class,
        Spo2Sample::class,
        Spo2PctSample::class,
        SkinTempSample::class,
        StepSample::class,
        SleepStateSampleEntity::class,
        RespSample::class,
        GravitySample::class,
        DailyMetric::class,
        SleepSession::class,
        MetricSeriesRow::class,
        JournalEntry::class,
        WorkoutRow::class,
        DismissedWorkout::class,
        DismissedSleep::class,
        AppleDaily::class,
        PpgHrSample::class,
        PairedDeviceRow::class,
        DayOwnershipRow::class,
        LabMarkerRow::class,
        LiveSessionRow::class,
        PpgWaveformSampleEntity::class,
        V18Sample::class,
    ],
    version = 101,
    exportSchema = false,
)
abstract class WhoopDatabase : RoomDatabase() {
    abstract fun whoopDao(): WhoopDao

    companion object {
        const val DB_NAME = "noop_whoop.db"

        /** Current Room schema version. Must match [Database.version]. */
        const val SCHEMA_VERSION = 101

        /**
         * Ordered list of all Room migrations, earliest to latest, used by
         * [DataBackup.migrateBackupIfNeeded] to bring an older backup to the current schema
         * before it replaces the live database. v22-99 are catch-all slots: any DB at v22+
         * converges to v100 via [reconcileToTan], so no per-version migration is needed.
         */
        val ALL_MIGRATIONS: List<Migration> by lazy {
            listOf(
            MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
            MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
            MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
            MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22,
            MIGRATION_100_101,
            ) + UPSTREAM_CATCHALL_MIGRATIONS
        }

        /**
         * Two additive columns: the beat's position within its second (so RMSSD reads beats
         * in emission order, not by magnitude), and the v18 per-second channels the stream
         * funnel was decoding but dropping. All nullable, so existing rows read back null.
         */
        internal val MIGRATION_100_101 = object : Migration(100, 101) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rrInterval ADD COLUMN ord INTEGER")
                db.execSQL("ALTER TABLE skinTempSample ADD COLUMN auxRaw1 INTEGER")
                db.execSQL("ALTER TABLE skinTempSample ADD COLUMN auxRaw2 INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `v18Sample` (" +
                        "`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `recordIndex` INTEGER, " +
                        "`sleepStateRaw` INTEGER, `opticalBaselineA` INTEGER, `opticalBaselineB` INTEGER, " +
                        "`opticalAmpA` INTEGER, `opticalAmpB` INTEGER, `opticalSignalPoor` INTEGER, " +
                        "`rawU8At28` INTEGER, `rawU8At29` INTEGER, `rawU16At30` INTEGER, `rawF32At105` REAL, " +
                        "`rawU16At26` INTEGER, `unpinned` BLOB, " +
                        "PRIMARY KEY(`deviceId`, `ts`))",
                )
            }
        }

        /** Any upstream version 22-99 converges to v100 via [reconcileToTan]. */
        private val UPSTREAM_CATCHALL_MIGRATIONS: List<Migration> by lazy {
            (22..99).map { v ->
                object : Migration(v, 100) {
                    override fun migrate(db: SupportSQLiteDatabase) { reconcileToTan(db) }
                }
            }
        }

        @Volatile
        private var instance: WhoopDatabase? = null

        /** Process-wide singleton. Safe to call from any thread. */
        fun get(context: Context): WhoopDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /**
         * Close and forget the singleton so all file handles on [DB_NAME] are released.
         * The next [get] call rebuilds against whatever file is on disk.
         */
        fun close() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        /** Suffix of the staged-restore file [DataBackup.importFrom] writes beside [DB_NAME]. */
        const val PENDING_RESTORE_SUFFIX = ".pending-restore"

        /**
         * Swap a staged restore into place before the store is opened. [DataBackup.importFrom]
         * writes the reconciled DB to `DB_NAME + PENDING_RESTORE_SUFFIX`; the swap happens here so
         * no live connection or background coroutine can re-open a torn file mid-swap. MUST run
         * before the first [get]. No-op when nothing is staged.
         */
        fun applyPendingRestore(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            val pending = File(dbFile.path + PENDING_RESTORE_SUFFIX)
            if (!pending.exists()) return
            synchronized(this) {
                instance?.let { it.close(); instance = null }
                runCatching { dbFile.delete() }
                runCatching { File(dbFile.path + "-wal").delete() }
                runCatching { File(dbFile.path + "-shm").delete() }
                if (!pending.renameTo(dbFile)) {
                    runCatching { pending.copyTo(dbFile, overwrite = true) }
                    runCatching { pending.delete() }
                }
            }
        }

        /**
         * v2 -> v3: additive, adds `stepSample` + `dailyMetric.steps`/`activeKcalEst`. Non-destructive
         * so already-offloaded raw streams survive (the strap won't re-send trimmed history). SQL must
         * match Room's generated schema exactly: `synced` NOT NULL, the two new columns nullable.
         */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stepSample` (`deviceId` TEXT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, `counter` INTEGER NOT NULL, " +
                        "`synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
                )
                db.execSQL("ALTER TABLE `dailyMetric` ADD COLUMN `steps` INTEGER")
                db.execSQL("ALTER TABLE `dailyMetric` ADD COLUMN `activeKcalEst` REAL")
            }
        }

        /**
         * v3 -> v4: additive, adds `workout.routePolyline` (nullable TEXT) for GPS routes. Nullable
         * so existing workouts migrate untouched; SQL must match Room's schema for a `String?`
         * column exactly: TEXT, no NOT NULL, no default.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workout` ADD COLUMN `routePolyline` TEXT")
            }
        }

        /**
         * v4 -> v5: additive, adds `dismissedWorkout`: a durable marker that keeps a dismissed
         * auto-detected workout hidden after the engine re-derives it. CREATE TABLE only. SQL must
         * match [DismissedWorkout] exactly: all three PK columns NOT NULL, composite PK in declaration order.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dismissedWorkout` (`deviceId` TEXT NOT NULL, " +
                        "`startTs` INTEGER NOT NULL, `endTs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`deviceId`, `startTs`))",
                )
            }
        }

        /**
         * v5 -> v6: additive, adds `ppgHrSample`: HR derived from the optical PPG waveform via
         * autocorrelation. CREATE TABLE only. SQL must match [PpgHrSample] exactly: every column
         * NOT NULL, `conf` REAL, composite PK (deviceId, ts) in declaration order.
         */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ppgHrSample` (`deviceId` TEXT NOT NULL, " +
                        "`ts` INTEGER NOT NULL, `bpm` INTEGER NOT NULL, `conf` REAL NOT NULL, " +
                        "`synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
                )
            }
        }

        /**
         * v6 -> v7: additive, adds `sleepSession.userEdited` + `startTsAdjusted` for durable
         * bed/wake editing. `userEdited` (Kotlin Boolean) stores as INTEGER NOT NULL DEFAULT 0;
         * `startTsAdjusted` (nullable Long) as INTEGER, no NOT NULL. Existing rows read back
         * userEdited=false, startTsAdjusted=null.
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sleepSession` ADD COLUMN `userEdited` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `sleepSession` ADD COLUMN `startTsAdjusted` INTEGER")
            }
        }

        /**
         * v7 -> v8: additive, adds the device registry (`pairedDevice` + `dayOwnership`). CREATE
         * TABLE only. SQL must match [PairedDeviceRow]/[DayOwnershipRow] exactly: `pairedDevice.nickname`
         * is the only nullable column; `dayOwnership.locked` (Kotlin Boolean, constructor default
         * false) stores as INTEGER NOT NULL with NO SQL DEFAULT — do not add `DEFAULT 0` or the
         * schema won't match. Seeds "my-whoop" (brand/model "WHOOP", sourceKind 'liveBLE', full
         * capability set, status 'active') via `INSERT OR IGNORE` so a re-run or restore is a no-op.
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pairedDevice` (`id` TEXT NOT NULL, " +
                        "`brand` TEXT NOT NULL, `model` TEXT NOT NULL, `nickname` TEXT, " +
                        "`sourceKind` TEXT NOT NULL, `capabilities` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "`lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dayOwnership` (`day` TEXT NOT NULL, " +
                        "`deviceId` TEXT NOT NULL, `locked` INTEGER NOT NULL, PRIMARY KEY(`day`))",
                )
                val now = System.currentTimeMillis() / 1000
                db.execSQL(
                    "INSERT OR IGNORE INTO `pairedDevice` " +
                        "(`id`, `brand`, `model`, `nickname`, `sourceKind`, `capabilities`, " +
                        "`status`, `addedAt`, `lastSeenAt`) VALUES " +
                        "('my-whoop', 'WHOOP', 'WHOOP', NULL, 'liveBLE', " +
                        "'hr,hrv,spo2,skinTemp,sleep,strainLoad', 'active', $now, $now)",
                )
            }
        }

        /**
         * v8 -> v9: additive, adds `pairedDevice.peripheralId` (nullable TEXT): the strap's stable
         * BLE MAC address ([android.bluetooth.BluetoothDevice]). Lets the BLE client pin a connect
         * to one specific strap and look up a freshly-paired device by address. ALTER ADD COLUMN
         * only; existing rows, including the seeded "my-whoop", read back `peripheralId = NULL`.
         */
        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `pairedDevice` ADD COLUMN `peripheralId` TEXT")
            }
        }

        /**
         * v9 -> v10: additive, adds the `dismissedSleep` tombstone table: a durable marker that
         * keeps a user-deleted computed sleep night from regenerating on the next recompute. CREATE
         * TABLE only. SQL must match [DismissedSleep] exactly: all three columns NOT NULL, composite
         * PK (deviceId, startTs) in declaration order.
         */
        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dismissedSleep` (`deviceId` TEXT NOT NULL, " +
                        "`startTs` INTEGER NOT NULL, `endTs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`deviceId`, `startTs`))",
                )
            }
        }

        /**
         * v10 -> v11: additive, adds `labMarker` (Health Records "Lab Book"): one row per dated,
         * user-entered reading. Non-clinical — holds only user-entered values plus an optional
         * user-entered `referenceText`; no reference-range tables or normality verdict. CREATE TABLE
         * + indexes only. SQL must match [LabMarkerRow]: PK is the single TEXT `id`; `value`,
         * `valueText`, `note`, `referenceText` are the only nullable columns; three indexes (one
         * UNIQUE natural-key, two lookup). Exposed as [LAB_MARKER_MIGRATION_SQL]; keep the constants
         * and migration in sync.
         */
        internal val LAB_MARKER_CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS `labMarker` (`id` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `markerKey` TEXT NOT NULL, " +
                "`category` TEXT NOT NULL, `day` TEXT NOT NULL, `takenAt` INTEGER NOT NULL, " +
                "`value` REAL, `valueText` TEXT, `unit` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`note` TEXT, `referenceText` TEXT, PRIMARY KEY(`id`))"

        internal val LAB_MARKER_INDEX_SQL = listOf(
            "CREATE UNIQUE INDEX IF NOT EXISTS `idx_labMarker_natural` " +
                "ON `labMarker` (`deviceId`, `markerKey`, `takenAt`, `source`)",
            "CREATE INDEX IF NOT EXISTS `idx_labMarker_device_marker_takenAt` " +
                "ON `labMarker` (`deviceId`, `markerKey`, `takenAt`)",
            "CREATE INDEX IF NOT EXISTS `idx_labMarker_device_category` " +
                "ON `labMarker` (`deviceId`, `category`)",
        )

        /** All statements the migration runs, in order: the table then its indexes. */
        internal val LAB_MARKER_MIGRATION_SQL: List<String> =
            listOf(LAB_MARKER_CREATE_SQL) + LAB_MARKER_INDEX_SQL

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in LAB_MARKER_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v11 -> v12: additive, adds `sleepSession.motionJSON` + `sleepStateJSON` (nullable TEXT):
         * per-epoch analytics banked beside `stagesJSON` on the same row — the per-epoch motion
         * magnitudes and the decoded v18 band sleep_state per epoch. ALTER ADD COLUMN only; existing
         * rows read back both columns NULL. Exposed as [SLEEP_MOTION_STATE_MIGRATION_SQL] for a JVM
         * pin test.
         */
        internal val SLEEP_MOTION_STATE_MIGRATION_SQL: List<String> = listOf(
            "ALTER TABLE `sleepSession` ADD COLUMN `motionJSON` TEXT",
            "ALTER TABLE `sleepSession` ADD COLUMN `sleepStateJSON` TEXT",
        )

        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in SLEEP_MOTION_STATE_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v12 -> v13: additive, adds `stepSample.activityClass` (nullable INTEGER): the @63
         * activity-class enum (0=still, 1=walk, 2=run; null when the byte was 0xFF/invalid/absent).
         * [StepRow] already decoded this but it was dropped at the insert boundary, so a classed
         * sample could never persist. ALTER ADD COLUMN only; existing rows read back NULL, never a
         * fabricated 0.
         */
        internal val STEP_ACTIVITY_CLASS_MIGRATION_SQL: List<String> = listOf(
            "ALTER TABLE `stepSample` ADD COLUMN `activityClass` INTEGER",
        )

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in STEP_ACTIVITY_CLASS_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v13 -> v14: additive, adds `journal.numericValue` (nullable REAL): a numeric value
         * (caffeine mg, alcohol units) alongside the yes/no answer. A numeric log writes
         * answeredYes=1 AND numericValue=v, so the EffectRanker with/without split is unaffected.
         * ALTER ADD COLUMN only; existing rows read back NULL, never a fabricated 0.
         */
        internal val JOURNAL_NUMERIC_MIGRATION_SQL: List<String> = listOf(
            "ALTER TABLE `journal` ADD COLUMN `numericValue` REAL",
        )

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in JOURNAL_NUMERIC_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v14 -> v15: additive, adds `sleepStateSample`. The strap's own band sleep_state (@81 high
         * nibble: 0 wake/1 still/2 asleep/3 up) was decoded but dropped at stream extraction. Keyed
         * by (deviceId, ts) like stepSample/ppgHrSample; idempotently upserts a second's band state.
         * `state` is the raw 0-3 code carried verbatim, never fabricated — a strap that never
         * reports it has no rows. CREATE TABLE only, every column NOT NULL, composite PK
         * (deviceId, ts) in declaration order.
         */
        internal val SLEEP_STATE_SAMPLE_MIGRATION_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `sleepStateSample` (`deviceId` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, `state` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
        )

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in SLEEP_STATE_SAMPLE_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v15 -> v16: additive, adds `liveSession`: one row per coaching session, natural key
         * (deviceId, startTs); `endTs` is null while the session is in progress. CREATE TABLE only.
         * SQL must match [LiveSessionRow]: `endTs`/`chargeAtStart` nullable, rest NOT NULL, composite
         * PK (deviceId, startTs) in declaration order.
         */
        internal val LIVE_SESSION_MIGRATION_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `liveSession` (`deviceId` TEXT NOT NULL, " +
                "`startTs` INTEGER NOT NULL, `endTs` INTEGER, `chargeAtStart` REAL, " +
                "`floorBpm` REAL NOT NULL, `ceilingBpm` REAL NOT NULL, `inBandSec` REAL NOT NULL, " +
                "`belowSec` REAL NOT NULL, `aboveSec` REAL NOT NULL, `pushCount` INTEGER NOT NULL, " +
                "`easeCount` INTEGER NOT NULL, `hrSource` TEXT NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `startTs`))",
        )

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in LIVE_SESSION_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v16 -> v17: additive, adds the WHOOP 4.0 raw SpO2 PPG ADC means (red/IR) to
         * `dailyMetric` as two nullable INTEGER columns. ALTER ADD COLUMN only; existing rows and
         * non-4.0 nights simply read back NULL.
         */
        internal val DAILY_SPO2_RAW_MIGRATION_SQL: List<String> = listOf(
            "ALTER TABLE `dailyMetric` ADD COLUMN `spo2Red` INTEGER",
            "ALTER TABLE `dailyMetric` ADD COLUMN `spo2Ir` INTEGER",
        )

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in DAILY_SPO2_RAW_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v17 -> v18: rebuild `rrInterval`, PK (deviceId, ts, rrMs) -> (deviceId, ts, rrMs, seq). The
         * old key silently dropped the second of two EQUAL R-R intervals landing in the same `ts`
         * second (`ON CONFLICT DO NOTHING`), biasing RMSSD/HRV high; `seq` disambiguates them, distinct
         * beats keep seq 0. The rebuild is loss-less: every existing row copies with `seq = 0`, exact
         * because the old PK guaranteed UNIQUE (deviceId, ts, rrMs) per row, so seq 0 never collides.
         * No window functions (minSdk 26 SQLite lacks `ROW_NUMBER`). This only stops FUTURE equal-beat
         * drops — beats already dropped under the old key are unrecoverable and historical HRV is not
         * corrected.
         */
        internal val RR_SEQ_MIGRATION_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `rrInterval_new` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, " +
                "`rrMs` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `synced` INTEGER NOT NULL, " +
                "PRIMARY KEY(`deviceId`, `ts`, `rrMs`, `seq`))",
            "INSERT INTO `rrInterval_new` (`deviceId`, `ts`, `rrMs`, `seq`, `synced`) " +
                "SELECT `deviceId`, `ts`, `rrMs`, 0, `synced` FROM `rrInterval`",
            "DROP TABLE `rrInterval`",
            "ALTER TABLE `rrInterval_new` RENAME TO `rrInterval`",
        )

        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in RR_SEQ_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v18 -> v19: efficiency-unit heal. UPDATE-only, no schema change. The Oura API importer and
         * (pre-fix) the WHOOP CSV importer wrote a 0-100 integer efficiency straight into
         * `sleepSession.efficiency` / `dailyMetric.efficiency`, but the sleep pipeline stores that
         * same column as a 0-1 fraction (asleep / in-bed) everywhere else it computes it. Divides
         * `efficiency` by 100 for any row > 1.5 — a fraction can never exceed 1.0 and no real night is
         * <=1.5% efficient, so the predicate is idempotent and can't touch an already-correct row. Not
         * deviceId-scoped: heals every known percent-writing source. Required because the CSV exporter
         * multiplies efficiency by 100 at write time, so an unhealed percent row would export at 100x
         * scale.
         */
        internal val EFFICIENCY_HEAL_MIGRATION_SQL: List<String> = listOf(
            "UPDATE `sleepSession` SET `efficiency` = `efficiency` / 100.0 WHERE `efficiency` > 1.5",
            "UPDATE `dailyMetric` SET `efficiency` = `efficiency` / 100.0 WHERE `efficiency` > 1.5",
        )

        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in EFFICIENCY_HEAL_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v19 -> v20: additive, adds `ppgWaveformSample`: durable storage for the 24 Hz optical PPG
         * waveform, which was fully decoded but only used to derive `ppgHrSample` then discarded. One
         * row per (deviceId, ts); samples are packed into a compact BLOB (2 bytes/sample, little-endian
         * i16, [StreamPersistence.packPpgSamples]) rather than 24 scalar rows. CREATE TABLE only, all
         * columns NOT NULL, composite PK (deviceId, ts) in declaration order.
         */
        internal val PPG_WAVEFORM_MIGRATION_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `ppgWaveformSample` (`deviceId` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, `samples` BLOB NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
        )

        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in PPG_WAVEFORM_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v20 -> v21: additive, adds `spo2PctSample`: the WHOOP 5.0/MG sleep SpO2 percent already
         * decoded from v18 @frame-82 but discarded before this. One row per (deviceId, ts), every
         * column NOT NULL, composite PK (deviceId, ts) in declaration order.
         */
        internal val SPO2_PCT_SAMPLE_MIGRATION_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `spo2PctSample` (`deviceId` TEXT NOT NULL, " +
                "`ts` INTEGER NOT NULL, `pct` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
        )

        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (stmt in SPO2_PCT_SAMPLE_MIGRATION_SQL) db.execSQL(stmt)
            }
        }

        /**
         * v21 -> v22: additive, adds [DailyMetric.skinTempAbsC]: absolute skin temperature (°C)
         * computed from raw SkinTempSample data during the nightly analytics pass, alongside the
         * existing baseline-deviation value. ALTER TABLE only, nullable REAL.
         */
        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE dailyMetric ADD COLUMN skinTempAbsC REAL")
            }
        }

        /**
         * Bring an upstream or unknown schema into the current shape.
         *
         * Detection uses the `spo2PctSample` table: present means already current, no-op.
         * Missing tables/columns are added idempotently (CREATE IF NOT EXISTS / ALTER ADD).
         * Extra upstream columns (rawImuSample, managementVisible) are harmless and left in place.
         */
        private fun reconcileToTan(db: SupportSQLiteDatabase) {
            if (!tableExists(db, "spo2PctSample")) {
                for (stmt in SPO2_PCT_SAMPLE_MIGRATION_SQL) db.execSQL(stmt)
            }
            if (!columnExists(db, "dailyMetric", "skinTempAbsC")) {
                db.execSQL("ALTER TABLE dailyMetric ADD COLUMN skinTempAbsC REAL")
            }
            if (!tableExists(db, "ppgWaveformSample")) {
                for (stmt in PPG_WAVEFORM_MIGRATION_SQL) db.execSQL(stmt)
            }
            if (!columnExists(db, "gravitySample", "dynAccelG")) {
                db.execSQL("ALTER TABLE gravitySample ADD COLUMN dynAccelG REAL")
            }
            if (!columnExists(db, "dailyMetric", "sleepNeedHours")) {
                db.execSQL("ALTER TABLE dailyMetric ADD COLUMN sleepNeedHours REAL")
            }
            if (!columnExists(db, "dailyMetric", "sleepConsistency")) {
                db.execSQL("ALTER TABLE dailyMetric ADD COLUMN sleepConsistency REAL")
            }
            if (!columnExists(db, "dailyMetric", "recoveryIndexSlope")) {
                db.execSQL("ALTER TABLE dailyMetric ADD COLUMN recoveryIndexSlope REAL")
            }
            if (!columnExists(db, "dailyMetric", "priorDayEffort")) {
                db.execSQL("ALTER TABLE dailyMetric ADD COLUMN priorDayEffort REAL")
            }
        }

        /** True if [table] exists in sqlite_master (case-insensitive). */
        private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean {
            val c = db.query(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=? COLLATE NOCASE",
                arrayOf(table),
            )
            return c.use { it.moveToFirst() && it.getInt(0) > 0 }
        }

        /** True if [column] exists on [table]. */
        private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            val c = db.query("PRAGMA table_info(`$table`)")
            val nameIdx = c.getColumnIndex("name")
            return c.use {
                while (it.moveToNext()) {
                    if (nameIdx >= 0 && it.getString(nameIdx).equals(column, ignoreCase = true))
                        return@use true
                }
                false
            }
        }

        private fun build(appContext: Context): WhoopDatabase =
            Room.databaseBuilder(appContext, WhoopDatabase::class.java, DB_NAME)
                // Replaces only the corruption handling of the default open-helper. The platform
                // default silently DELETES a corrupt database file (non-resendable strap history
                // gone without a trace); this factory logs + preserves it instead. Every other
                // callback is delegated to Room unchanged.
                .openHelperFactory(CorruptionPreservingOpenHelperFactory())
                // Real additive migrations, no destructive fallback: with exportSchema=false a
                // silent rebuild would lose already-acked, non-resendable strap history on any
                // schema mismatch. Room throws loudly instead; CI guards the SQL.
                .addMigrations(*ALL_MIGRATIONS.toTypedArray())
                // A fresh install builds the schema straight at the current version and runs NO
                // migrations, so the MIGRATION_7_8 "my-whoop" registry seed never fires and a paired,
                // streaming WHOOP never appears in the Devices list. Seed the canonical row on create
                // too (same idempotent INSERT OR IGNORE as the migration) so a first-ever install
                // still lists its WHOOP.
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val now = System.currentTimeMillis() / 1000
                        db.execSQL(
                            "INSERT OR IGNORE INTO `pairedDevice` " +
                                "(`id`, `brand`, `model`, `nickname`, `sourceKind`, `capabilities`, " +
                                "`status`, `addedAt`, `lastSeenAt`) VALUES " +
                                "('my-whoop', 'WHOOP', 'WHOOP', NULL, 'liveBLE', " +
                                "'hr,hrv,spo2,skinTemp,sleep,strainLoad', 'active', $now, $now)",
                        )
                    }
                })
                .build()
    }
}

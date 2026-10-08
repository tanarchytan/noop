package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * v102 -> v103: `rrInterval.tsSuspect` and the 500 ms filler marking ([RrFill]). Runs the REAL SQL on a
 * real SQLite (sqlite-jdbc): the migration statements, the backfill, the DAO `@Query` text read off the
 * annotation, and the repository's insert-time marking through a DAO that executes those queries. The
 * predicate itself lives in whoop-rs; a parity test holds the SQL to `isRrFill` over a grid.
 */
class V103MigrationTest {

    private lateinit var db: Connection

    // The v102 shape of the two tables the backfill touches (what MIGRATION_101_102 leaves behind).
    private val v102Ddl = listOf(
        "CREATE TABLE `hrSample` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `bpm` INTEGER NOT NULL, " +
            "`synced` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
        "CREATE TABLE `rrInterval` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `rrMs` INTEGER NOT NULL, " +
            "`seq` INTEGER NOT NULL, `synced` INTEGER NOT NULL, `ord` INTEGER, " +
            "PRIMARY KEY(`deviceId`, `ts`, `rrMs`, `seq`))",
    )

    @Before fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { s -> v102Ddl.forEach { s.execute(it) } }
    }

    @After fun close() = db.close()

    private fun hr(dev: String, ts: Long, bpm: Int): Int =
        db.prepareStatement("INSERT OR IGNORE INTO hrSample VALUES (?,?,?,0)").use {
            it.setString(1, dev); it.setLong(2, ts); it.setInt(3, bpm); it.executeUpdate()
        }

    private fun rr(dev: String, ts: Long, ms: Int, seq: Int = 0): Int =
        db.prepareStatement("INSERT OR IGNORE INTO rrInterval (deviceId, ts, rrMs, seq, synced, ord) VALUES (?,?,?,?,0,NULL)").use {
            it.setString(1, dev); it.setLong(2, ts); it.setInt(3, ms); it.setInt(4, seq); it.executeUpdate()
        }

    private fun one(sql: String): Long = db.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getLong(1) } }

    private fun migrate(): Int {
        db.createStatement().use { s -> WhoopDatabase.RR_TS_SUSPECT_MIGRATION_SQL.forEach { s.execute(it) } }
        return mark()
    }

    /** Run [RrFill.MARK_ALL_SQL] with the whoop-rs constants. Named binds bind by first appearance. */
    private fun mark(): Int = db.prepareStatement(RrFill.MARK_ALL_SQL).use {
        it.setInt(1, RrFill.fillMs); it.setInt(2, RrFill.maxHrBpm); it.executeUpdate()
    }

    /** The `@Query` text of a DAO method, read from the source (the annotation is not kept at runtime), with
     *  string pieces joined, [RR_FILL_MARK_WHERE] resolved, and `:name` binds turned into positional `?`
     *  (each name is used once in the queries run here, so first-appearance order is the parameter order). */
    private fun daoSql(method: String): String {
        val dir = File(System.getProperty("user.dir") ?: ".")
        val src = listOf("src", "app/src", "android/app/src").map { File(dir, "$it/main/java/com/noop/data/WhoopDao.kt") }
            .first { it.exists() }.readText().replace("\r\n", "\n")
        val head = src.substringBefore("suspend fun $method(")
        val query = head.substringAfterLast("@Query(").lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        val sql = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"|RR_FILL_MARK_WHERE").findAll(query)
            .joinToString("") { it.groups[1]?.value ?: RR_FILL_MARK_WHERE }
        return sql.replace(Regex(":[A-Za-z]+"), "?")
    }

    // -- the step ---------------------------------------------------------------------------------------

    @Test fun theStepIs102To103AndIsTheCurrentSchema() {
        assertEquals(102, WhoopDatabase.MIGRATION_102_103.startVersion)
        assertEquals(103, WhoopDatabase.MIGRATION_102_103.endVersion)
        assertEquals(103, WhoopDatabase.SCHEMA_VERSION)
        assertTrue(WhoopDatabase.ALL_MIGRATIONS.any { it.startVersion == 102 && it.endVersion == 103 })
    }

    @Test fun theConstantsComeFromWhoopRs() {
        // Pinned to the whoop-rs values the mechanism note decided; a drift in Rust fails here, not in a store.
        assertEquals(500, RrFill.fillMs)
        assertEquals(100, RrFill.maxHrBpm)
        assertFalse("constants are binds, never inlined", RrFill.MARK_ALL_SQL.contains("500") || RrFill.MARK_ALL_SQL.contains("100"))
    }

    @Test fun theColumnIsAdditiveAndMatchesTheEntity() {
        val stmt = WhoopDatabase.RR_TS_SUSPECT_MIGRATION_SQL.single()
        assertEquals("ALTER TABLE `rrInterval` ADD COLUMN `tsSuspect` INTEGER NOT NULL DEFAULT 0", stmt)
        val fields = RrInterval::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }
        assertEquals("ADD COLUMN lands last, so the entity must declare it last", "tsSuspect", fields.last())
        // ColumnInfo is not kept at runtime, so read the declaration: the entity default must equal the ALTER's.
        val dir = File(System.getProperty("user.dir") ?: ".")
        val entities = listOf("src", "app/src", "android/app/src").map { File(dir, "$it/main/java/com/noop/data/Entities.kt") }
            .first { it.exists() }.readText().filterNot { it.isWhitespace() }
        assertTrue(entities.contains("""@ColumnInfo(defaultValue="0")valtsSuspect:Int=0"""))
    }

    @Test fun noMigrationStatementCanLoseARow() {
        for (s in WhoopDatabase.RR_TS_SUSPECT_MIGRATION_SQL + RrFill.MARK_ALL_SQL) {
            assertFalse(s, s.contains("DELETE", ignoreCase = true) || s.contains("DROP", ignoreCase = true))
        }
    }

    // -- the backfill, executed -------------------------------------------------------------------------

    @Test fun backfillMarksOnlyTheFillerAndKeepsEveryRow() {
        hr("A", 10, 60); rr("A", 10, 500)             // flagged
        hr("A", 11, 99); rr("A", 11, 500)             // flagged (strictly below 100)
        hr("A", 12, 100); rr("A", 12, 500)            // HR at the bound: not
        hr("A", 13, 60); rr("A", 13, 501)             // not 500
        rr("A", 14, 500)                              // no same-second HR: not
        hr("B", 10, 60); rr("B", 11, 500)             // other device / other second: not
        hr("A", 15, 60); rr("A", 15, 500, 0); rr("A", 15, 500, 1) // equal pair in one second: both
        val before = one("SELECT COUNT(*) FROM rrInterval")

        assertEquals(4, migrate())

        assertEquals("no row lost", before, one("SELECT COUNT(*) FROM rrInterval"))
        assertEquals(4, one("SELECT COUNT(*) FROM rrInterval WHERE tsSuspect = 1"))
        assertEquals(0, one("SELECT COUNT(*) FROM rrInterval WHERE tsSuspect = 1 AND (rrMs <> 500 OR ts IN (12, 14))"))
        assertEquals("a second pass marks nothing new", 0, mark())
    }

    @Test fun sqlPredicateAgreesWithTheRustRuleOverAGrid() {
        var n = 0
        for (ms in listOf(0, 499, 500, 501, 512)) for (bpm in listOf<Int?>(null, 30, 99, 100, 101, 180)) {
            val ts = 1000L + n++
            if (bpm != null) hr("A", ts, bpm)
            rr("A", ts, ms)
        }
        migrate()
        var checked = 0
        db.createStatement().use { s ->
            s.executeQuery(
                "SELECT r.rrMs, h.bpm, r.tsSuspect FROM rrInterval r " +
                    "LEFT JOIN hrSample h ON h.deviceId = r.deviceId AND h.ts = r.ts",
            ).use { rs ->
                while (rs.next()) {
                    val bpm = rs.getInt(2).takeUnless { rs.wasNull() }
                    val rust = uniffi.whoop_ffi.isRrFill(rs.getInt(1).toUShort(), bpm?.toUInt())
                    assertEquals("rr=${rs.getInt(1)} hr=$bpm", rust, rs.getInt(3) == 1)
                    checked++
                }
            }
        }
        assertEquals(30, checked)
    }

    // -- the reads, executed ----------------------------------------------------------------------------

    @Test fun scoringReadDropsTheFillerAndTheExportReadKeepsItMarked() {
        hr("A", 10, 60); rr("A", 10, 500); rr("A", 10, 812)
        hr("A", 11, 61); rr("A", 11, 790)
        migrate()

        fun rows(method: String): List<Pair<Int, Int>> = db.prepareStatement(daoSql(method)).use {
            it.setString(1, "A"); it.setLong(2, 0); it.setLong(3, 100); it.setInt(4, 1000)
            it.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getInt("rrMs") to rs.getInt("tsSuspect") else null }.toList()
            }
        }
        assertEquals(listOf(790 to 0, 812 to 0), rows("rrIntervals").sortedBy { it.first })
        assertEquals(
            "the export read keeps the suspect beat, marked",
            listOf(500 to 1, 790 to 0, 812 to 0), rows("rrIntervalsAll").sortedBy { it.first },
        )
    }

    // -- insert-time marking, through the repository ----------------------------------------------------

    /** A DAO whose insert / mark methods run their real SQL on [db]; any other call is unexpected. */
    private fun sqlDao(): WhoopDao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java)) { _, m, a ->
        @Suppress("UNCHECKED_CAST")
        when (m.name) {
            "insertHr" -> (a[0] as List<HrSample>).map { hr(it.deviceId, it.ts, it.bpm).toLong() }
            "insertRr" -> (a[0] as List<RrInterval>).map { rr(it.deviceId, it.ts, it.rrMs, it.seq).toLong() }
            "markRrFill" -> db.prepareStatement(daoSql("markRrFill")).use {
                it.setString(1, a[0] as String); it.setLong(2, a[1] as Long); it.setLong(3, a[2] as Long)
                it.setInt(4, a[3] as Int); it.setInt(5, a[4] as Int); it.executeUpdate()
            }
            else -> error("unexpected DAO call ${m.name}")
        }
    } as WhoopDao

    @Test fun anInsertedBatchIsMarkedAtInsertTime() = runBlocking {
        migrate() // the column must exist, as it does on a v103 store
        WhoopRepository(sqlDao()).insert(
            StreamBatch(
                hr = listOf(HrRow(100, 58), HrRow(101, 130)),
                rr = listOf(RrRow(100, 500), RrRow(100, 640), RrRow(101, 500), RrRow(102, 500)),
            ),
            "A",
        )
        // ts 100: resting HR + 500 -> filler; ts 101: HR 130 -> real; ts 102: no HR -> not flagged
        assertEquals(1, one("SELECT COUNT(*) FROM rrInterval WHERE tsSuspect = 1"))
        assertEquals(1, one("SELECT COUNT(*) FROM rrInterval WHERE tsSuspect = 1 AND ts = 100 AND rrMs = 500"))
        assertEquals("marked, never deleted", 4, one("SELECT COUNT(*) FROM rrInterval"))
    }

    @Test fun anHrOnlyBatchTouchesNothing() = runBlocking {
        migrate()
        // markRrFill would be an unexpected call here; an HR-only batch has no R-R second range to mark.
        WhoopRepository(sqlDao()).insert(StreamBatch(hr = listOf(HrRow(1, 60))), "A")
        assertEquals(0, one("SELECT COUNT(*) FROM rrInterval"))
    }

    // -- backup import, through the real row-copy planner -----------------------------------------------

    private fun schemaOf(schema: String): Map<String, List<DataBackup.SchemaColumn>> =
        listOf("hrSample", "rrInterval").associateWith { t ->
            db.createStatement().use { s ->
                s.executeQuery("PRAGMA $schema.table_info($t)").use { rs ->
                    generateSequence { if (rs.next()) rs else null }.map {
                        DataBackup.SchemaColumn(
                            it.getString("name"), it.getString("type"), it.getInt("notnull") == 1,
                            it.getString("dflt_value") != null, it.getInt("pk") > 0,
                        )
                    }.toList()
                }
            }
        }

    /** Copy a backup's two tables into a v103 `main` the way the import does, then run the post-copy mark. */
    private fun importBackup(backupDdl: List<String>, seed: List<String>): Pair<Long, Long> {
        db.createStatement().use { s ->
            WhoopDatabase.RR_TS_SUSPECT_MIGRATION_SQL.forEach { s.execute(it) }
            s.execute("ATTACH DATABASE ':memory:' AS src")
            backupDdl.forEach { s.execute(it.replace("CREATE TABLE ", "CREATE TABLE src.")) }
            seed.forEach { s.execute(it) }
        }
        val plan = DataBackup.planRowCopyImport(schemaOf("main"), schemaOf("src"), DataBackup.ImportMode.REPLACE)
        db.createStatement().use { s -> plan.statements.forEach { s.execute(it) } }
        mark()
        return one("SELECT COUNT(*) FROM rrInterval") to one("SELECT COUNT(*) FROM rrInterval WHERE tsSuspect = 1")
    }

    @Test fun anOlderBackupWithoutTheColumnGetsItsFillerMarked() {
        val (rows, marked) = importBackup(
            v102Ddl,
            listOf(
                "INSERT INTO src.hrSample VALUES ('A', 10, 60, 0)",
                "INSERT INTO src.rrInterval VALUES ('A', 10, 500, 0, 0, NULL)",
                "INSERT INTO src.rrInterval VALUES ('A', 10, 812, 0, 0, NULL)",
            ),
        )
        assertEquals("every beat imported", 2L, rows)
        assertEquals("the filler is marked, not dropped", 1L, marked)
    }

    @Test fun aV103BackupKeepsItsMarksAndRows() {
        val v103Rr = "CREATE TABLE `rrInterval` (`deviceId` TEXT NOT NULL, `ts` INTEGER NOT NULL, `rrMs` INTEGER NOT NULL, " +
            "`seq` INTEGER NOT NULL, `synced` INTEGER NOT NULL, `ord` INTEGER, `tsSuspect` INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY(`deviceId`, `ts`, `rrMs`, `seq`))"
        val (rows, marked) = importBackup(
            listOf(v102Ddl[0], v103Rr),
            listOf(
                "INSERT INTO src.hrSample VALUES ('A', 10, 130, 0)", // HR now says real, but the backup marked it
                "INSERT INTO src.rrInterval VALUES ('A', 10, 500, 0, 0, NULL, 1)",
                "INSERT INTO src.rrInterval VALUES ('A', 10, 812, 0, 0, NULL, 0)",
            ),
        )
        assertEquals(2L, rows)
        assertEquals("the backup's own mark survives the copy", 1L, marked)
    }
}

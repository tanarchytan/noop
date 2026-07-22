package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure JVM tests for [DataBackup.readUserVersion].
 *
 * The actual SQL migration execution (opening the file through
 * [android.database.sqlite.SQLiteDatabase] and calling Room [Migration.migrate]) requires a real
 * Android SQLite stack and lives in the instrumented test target / on-device verification.
 *
 * What we CAN pin on the plain JVM:
 *  - Byte-level `PRAGMA user_version` extraction from a SQLite header (pure file I/O).
 *  - Invalid / not-a-SQLite-file rejection.
 *  - Round-trip at every version the app uses (2 through 100, v1-tan).
 */
class DataBackupMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    /** The 16-byte "SQLite format 3\0" magic. */
    private val sqliteMagic = byteArrayOf(
        0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
    )

    /**
     * Build a minimal file with a valid SQLite 3 database header carrying [userVersion]. Per the SQLite
     * file format `user_version` is a big-endian 32-bit int at byte offset 60 (offset 64 is the unrelated
     * incremental-vacuum field). The remaining bytes beyond the header are arbitrary (zeroed).
     */
    private fun sqliteHeaderFile(userVersion: Int): java.io.File {
        val f = tmp.newFile()
        val buf = ByteArray(4096)
        sqliteMagic.copyInto(buf, 0)
        buf[16] = 0x10; buf[17] = 0x00 // page size 4096
        buf[60] = ((userVersion shr 24) and 0xFF).toByte()
        buf[61] = ((userVersion shr 16) and 0xFF).toByte()
        buf[62] = ((userVersion shr 8) and 0xFF).toByte()
        buf[63] = (userVersion and 0xFF).toByte()
        f.writeBytes(buf)
        return f
    }

    @Test
    fun readUserVersion_returnsVersion14() {
        assertEquals(14, DataBackup.readUserVersion(sqliteHeaderFile(14)))
    }

    @Test
    fun readUserVersion_returnsZeroForFreshDatabase() {
        assertEquals(0, DataBackup.readUserVersion(sqliteHeaderFile(0)))
    }

    @Test
    fun readUserVersion_returnsNullForFileTooSmall() {
        val f = tmp.newFile()
        f.writeBytes(ByteArray(60))
        assertNull(DataBackup.readUserVersion(f))
    }

    @Test
    fun readUserVersion_returnsNullForNoSqliteMagic() {
        val f = tmp.newFile()
        f.writeBytes(ByteArray(68)) // all zeros, no SQLite magic
        assertNull(DataBackup.readUserVersion(f))
    }

    @Test
    fun readUserVersion_returnsNullForEmptyFile() {
        assertNull(DataBackup.readUserVersion(tmp.newFile()))
    }

    @Test
    fun readUserVersion_handlesMaxVersionInt() {
        assertEquals(Int.MAX_VALUE, DataBackup.readUserVersion(sqliteHeaderFile(Int.MAX_VALUE)))
    }

    @Test
    fun readUserVersion_roundTripsAppVersions() {
        for (v in listOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 100)) {
            assertEquals("schema version $v", v, DataBackup.readUserVersion(sqliteHeaderFile(v)))
        }
    }

    @Test
    fun readUserVersion_roundTripsBoundaryVersions() {
        for (v in listOf(0, 1, 255, 65535)) {
            assertEquals(v, DataBackup.readUserVersion(sqliteHeaderFile(v)))
        }
    }

    /**
     * Pins the byte offset at 60 (SQLite spec), not 64. A real Room backup carries its schema version at
     * offset 60; offset 64 holds the incremental-vacuum flag. Reading offset 64 by mistake mis-reports
     * every non-current backup as v0 (the bug this guards). Writes DISTINCT values at 60 and 64 and
     * requires the offset-60 value, so a regression to offset 64 fails here.
     */
    @Test
    fun readUserVersion_readsOffset60_notTheIncrementalVacuumFieldAt64() {
        val f = tmp.newFile()
        val buf = ByteArray(4096)
        sqliteMagic.copyInto(buf, 0)
        buf[16] = 0x10; buf[17] = 0x00
        buf[63] = 20 // user_version = 20 at offset 60..63
        buf[67] = 99 // incremental-vacuum = 99 at offset 64..67 (must be ignored)
        f.writeBytes(buf)
        assertEquals(20, DataBackup.readUserVersion(f))
    }

    // ── planMigrationPath (greedy longest-jump, catch-all aware) ──────────────

    private val migrations = WhoopDatabase.ALL_MIGRATIONS
    private val target = WhoopDatabase.SCHEMA_VERSION // 100 (v1-tan)

    private fun path(from: Int) = DataBackup.planMigrationPath(from, target, migrations)

    @Test
    fun planPath_upstreamV20_stepsToV22ThenLeapsToTarget() {
        // v20 has only the individual 20->21, 21->22; at 22 the catch-all jumps straight to 100.
        val p = path(20)
        assertNull(p.error)
        assertEquals(listOf(20 to 21, 21 to 22, 22 to target), p.path!!.map { it.startVersion to it.endVersion })
    }

    @Test
    fun planPath_catchAllVersions_leapDirectlyToTarget() {
        for (v in listOf(22, 50, 99)) {
            val p = path(v)
            assertNull("v$v", p.error)
            assertEquals("v$v", listOf(v to target), p.path!!.map { it.startVersion to it.endVersion })
        }
    }

    @Test
    fun planPath_lowVersion_walksTheFullChainToTarget() {
        val p = path(5)
        assertNull(p.error)
        val steps = p.path!!
        assertEquals(5, steps.first().startVersion)
        assertEquals(target, steps.last().endVersion)
        // Contiguous: each step starts where the previous ended.
        var at = 5
        for (m in steps) { assertEquals(at, m.startVersion); at = m.endVersion }
        assertEquals(target, at)
    }

    @Test
    fun planPath_atOrAboveTarget_isEmpty() {
        assertEquals(emptyList<Any>(), path(target).path)
        assertEquals(emptyList<Any>(), DataBackup.planMigrationPath(target + 1, target, migrations).path)
    }

    @Test
    fun planPath_versionWithNoOutgoingMigration_reportsNoPath() {
        // Migrations start at v2, so a v1 backup has no step out of v1.
        val p = path(1)
        assertNull(p.path)
        assertEquals("No migration path from schema v1 to v$target.", p.error)
    }
}

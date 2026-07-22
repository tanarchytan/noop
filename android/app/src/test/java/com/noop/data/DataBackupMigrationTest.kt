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
     * Build a minimal file with a valid SQLite 3 database header at a given [userVersion].
     * The header is 100 bytes; we need at least 68 bytes for [DataBackup.readUserVersion] to
     * succeed. The remaining bytes beyond the header are arbitrary (zeroed).
     */
    private fun sqliteHeaderFile(userVersion: Int): java.io.File {
        val f = tmp.newFile()
        val buf = ByteArray(4096)
        sqliteMagic.copyInto(buf, 0)
        buf[16] = 0x10; buf[17] = 0x00 // page size 4096
        buf[64] = ((userVersion shr 24) and 0xFF).toByte()
        buf[65] = ((userVersion shr 16) and 0xFF).toByte()
        buf[66] = ((userVersion shr 8) and 0xFF).toByte()
        buf[67] = (userVersion and 0xFF).toByte()
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
}

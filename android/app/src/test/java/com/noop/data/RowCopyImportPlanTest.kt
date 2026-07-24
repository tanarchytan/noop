package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [DataBackup.planRowCopyImport] — the version-agnostic, content-based import planner.
 * Schemas here mirror the real shapes seen in the field: a Room (noop-tan v100) target vs an iOS/GRDB
 * backup source, plus the NOT NULL-fill and key-skip contracts that keep rows instead of dropping them.
 */
class RowCopyImportPlanTest {

    private fun c(name: String, notNull: Boolean = false, hasDefault: Boolean = false, key: Boolean = false) =
        DataBackup.SchemaColumn(name, "INTEGER", notNull, hasDefault, key)

    private fun cols(vararg names: String) = names.map { c(it) }

    // Target = noop-tan Room v100 (subset). dailyMetric carries the newer skinTempAbsC; two tan-only tables.
    private val room = mapOf(
        "hrSample" to cols("deviceId", "ts", "bpm", "synced"),
        "rrInterval" to cols("deviceId", "ts", "rrMs", "seq", "synced"),
        "dailyMetric" to cols("deviceId", "day", "restingHr", "avgHrv", "skinTempAbsC"),
        "spo2PctSample" to cols("deviceId", "ts", "pct"), // tan-only
        "dismissedWorkout" to cols("deviceId", "startTs", "endTs"), // tan-only
        "room_master_table" to cols("id", "identity_hash"), // housekeeping — never copied
    )

    // Source = iOS/GRDB backup (subset). Same column names; dailyMetric lacks skinTempAbsC; iOS-only tables.
    private val ios = mapOf(
        "hrSample" to cols("deviceId", "ts", "bpm", "synced"),
        "rrInterval" to cols("deviceId", "ts", "rrMs", "seq", "synced"),
        "dailyMetric" to cols("deviceId", "day", "restingHr", "avgHrv"),
        "cursors" to cols("deviceId", "kind", "value"), // iOS-only
        "ouraRaw" to cols("id", "json"), // iOS-only
        "grdb_migrations" to cols("identifier"), // housekeeping — never copied
    )

    @Test
    fun merge_copiesSharedTablesOverIntersectedColumns() {
        val p = DataBackup.planRowCopyImport(room, ios, DataBackup.ImportMode.MERGE)
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO main.`dailyMetric` (`deviceId`, `day`, `restingHr`, `avgHrv`) SELECT `deviceId`, `day`, `restingHr`, `avgHrv` FROM src.`dailyMetric`",
                "INSERT OR IGNORE INTO main.`hrSample` (`deviceId`, `ts`, `bpm`, `synced`) SELECT `deviceId`, `ts`, `bpm`, `synced` FROM src.`hrSample`",
                "INSERT OR IGNORE INTO main.`rrInterval` (`deviceId`, `ts`, `rrMs`, `seq`, `synced`) SELECT `deviceId`, `ts`, `rrMs`, `seq`, `synced` FROM src.`rrInterval`",
            ),
            p.statements,
        )
    }

    @Test
    fun replace_prependsADeletePerSharedTable() {
        val p = DataBackup.planRowCopyImport(room, ios, DataBackup.ImportMode.REPLACE)
        assertEquals("DELETE FROM main.`dailyMetric`", p.statements[0])
        assertTrue(p.statements[1].startsWith("INSERT OR IGNORE INTO main.`dailyMetric`"))
        assertEquals(3, p.statements.count { it.startsWith("DELETE") })
        assertEquals(3, p.statements.count { it.startsWith("INSERT") })
    }

    @Test
    fun housekeepingTablesAreNeverCopied() {
        val p = DataBackup.planRowCopyImport(room, ios, DataBackup.ImportMode.MERGE)
        assertTrue(p.statements.none { it.contains("room_master_table") || it.contains("grdb_migrations") })
    }

    @Test
    fun tablesOnlyInTargetBecomeMissingWarnings() {
        val p = DataBackup.planRowCopyImport(room, ios, DataBackup.ImportMode.MERGE)
        assertEquals(listOf("dismissedWorkout", "spo2PctSample"), p.missingTables)
    }

    @Test
    fun tablesOnlyInBackupAreDroppedWithAWarning() {
        val p = DataBackup.planRowCopyImport(room, ios, DataBackup.ImportMode.MERGE)
        assertEquals(listOf("cursors", "ouraRaw"), p.droppedTables)
    }

    @Test
    fun newerColumnMissingFromAnOlderBackupIsAWarningNotAFailure() {
        val p = DataBackup.planRowCopyImport(room, ios, DataBackup.ImportMode.MERGE)
        assertEquals(mapOf("dailyMetric" to listOf("skinTempAbsC")), p.missingColumns)
        // The copy still runs over the shared columns — the missing nullable field just imports empty.
        assertTrue(p.statements.any { it.startsWith("INSERT OR IGNORE INTO main.`dailyMetric`") })
    }

    @Test
    fun warningsReadHumanly() {
        val w = DataBackup.planRowCopyImport(room, ios, DataBackup.ImportMode.MERGE).warnings()
        assertTrue(w.any { it.contains("No data in this backup for") && it.contains("spo2PctSample") })
        assertTrue(w.any { it.contains("Skipped tables") && it.contains("ouraRaw") })
        assertTrue(w.any { it.contains("skinTempAbsC") && it.contains("imported empty") })
    }

    @Test
    fun cleanBackupProducesNoWarnings() {
        val same = mapOf("hrSample" to cols("deviceId", "ts", "bpm", "synced"))
        assertTrue(DataBackup.planRowCopyImport(same, same, DataBackup.ImportMode.MERGE).warnings().isEmpty())
    }

    @Test
    fun sourceAbsentNotNullColumnIsFilledWhileNullableOneIsOmitted() {
        // A NOT NULL-no-default column the backup lacks is KEPT and filled with a typed zero so the rows
        // survive INSERT OR IGNORE; a nullable one is omitted (imported empty).
        val target = mapOf("t" to listOf(c("id"), c("synced", notNull = true), c("note")))
        val source = mapOf("t" to listOf(c("id")))
        val p = DataBackup.planRowCopyImport(target, source, DataBackup.ImportMode.MERGE)
        assertEquals(
            listOf("INSERT OR IGNORE INTO main.`t` (`id`, `synced`) SELECT `id`, 0 FROM src.`t`"),
            p.statements,
        )
        assertEquals(mapOf("t" to listOf("synced")), p.filledColumns)
        assertEquals(mapOf("t" to listOf("note")), p.missingColumns)
        assertTrue(p.warnings().any { it == "t: filled synced with defaults." })
    }

    @Test
    fun typedZeroLiteralFollowsColumnAffinity() {
        // TEXT -> '', BLOB -> x'', everything else (incl. untyped) -> 0.
        val target = mapOf(
            "t" to listOf(
                c("k"),
                DataBackup.SchemaColumn("i", "INTEGER", notNull = true),
                DataBackup.SchemaColumn("r", "REAL", notNull = true),
                DataBackup.SchemaColumn("s", "TEXT", notNull = true),
                DataBackup.SchemaColumn("b", "BLOB", notNull = true),
                DataBackup.SchemaColumn("u", "", notNull = true),
            ),
        )
        val source = mapOf("t" to listOf(c("k")))
        val p = DataBackup.planRowCopyImport(target, source, DataBackup.ImportMode.MERGE)
        assertEquals(
            listOf("INSERT OR IGNORE INTO main.`t` (`k`, `i`, `r`, `s`, `b`, `u`) SELECT `k`, 0, 0, '', x'', 0 FROM src.`t`"),
            p.statements,
        )
    }

    @Test
    fun backupLackingANotNullKeyColumnFillsItWithRowidSoRowsStillImport() {
        // A NOT NULL-no-default KEY column the backup lacks is filled with the source rowid (unique per row),
        // so the table imports instead of collapsing under a constant fill; a sibling with all keys present
        // copies normally. This is the rrInterval `seq` case: a seq-less backup still lands its R-R rows.
        val target = mapOf(
            "hrSample" to listOf(c("deviceId", notNull = true, key = true), c("ts", notNull = true, key = true), c("bpm")),
            "sleepSession" to listOf(c("deviceId", notNull = true, key = true), c("startTs", notNull = true, key = true), c("efficiency")),
        )
        val source = mapOf(
            "hrSample" to listOf(c("deviceId"), c("stamp"), c("bpm")), // ts renamed to stamp
            "sleepSession" to listOf(c("deviceId"), c("startTs"), c("efficiency")),
        )
        val p = DataBackup.planRowCopyImport(target, source, DataBackup.ImportMode.REPLACE)
        assertEquals(
            "INSERT OR IGNORE INTO main.`hrSample` (`deviceId`, `ts`, `bpm`) SELECT `deviceId`, rowid, `bpm` FROM src.`hrSample`",
            p.statements.first { it.startsWith("INSERT OR IGNORE INTO main.`hrSample`") },
        )
        assertEquals(mapOf("hrSample" to listOf("ts")), p.synthesizedKeyColumns)
        assertTrue(p.statements.any { it.startsWith("INSERT OR IGNORE INTO main.`sleepSession`") })
        assertTrue(
            p.warnings().any {
                it == "hrSample: generated ids for the key column(s) ts this backup didn't carry."
            },
        )
    }
}

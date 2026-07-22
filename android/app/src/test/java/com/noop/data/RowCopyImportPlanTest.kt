package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [DataBackup.planRowCopyImport] — the version-agnostic, content-based import planner.
 * Schemas here mirror the real shapes seen in the field: a Room (noop-tan v100) target vs an iOS/GRDB
 * backup source (matching column names by the cross-platform parity contract, but with iOS-only tables,
 * missing a newer column, and missing noop-tan-only tables). No live DB — pure statement + warning planning.
 */
class RowCopyImportPlanTest {

    // Target = noop-tan Room v100 (subset). dailyMetric carries the newer skinTempAbsC; two tan-only tables.
    private val room = mapOf(
        "hrSample" to listOf("deviceId", "ts", "bpm", "synced"),
        "rrInterval" to listOf("deviceId", "ts", "rrMs", "seq", "synced"),
        "dailyMetric" to listOf("deviceId", "day", "restingHr", "avgHrv", "skinTempAbsC"),
        "spo2PctSample" to listOf("deviceId", "ts", "pct"), // tan-only
        "dismissedWorkout" to listOf("deviceId", "startTs", "endTs"), // tan-only
        "room_master_table" to listOf("id", "identity_hash"), // housekeeping — never copied
    )

    // Source = iOS/GRDB backup (subset). Same column names; dailyMetric lacks skinTempAbsC; iOS-only tables.
    private val ios = mapOf(
        "hrSample" to listOf("deviceId", "ts", "bpm", "synced"),
        "rrInterval" to listOf("deviceId", "ts", "rrMs", "seq", "synced"),
        "dailyMetric" to listOf("deviceId", "day", "restingHr", "avgHrv"),
        "cursors" to listOf("deviceId", "kind", "value"), // iOS-only
        "ouraRaw" to listOf("id", "json"), // iOS-only
        "grdb_migrations" to listOf("identifier"), // housekeeping — never copied
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
        // The copy still runs over the shared columns — the missing field just imports empty.
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
        val same = mapOf("hrSample" to listOf("deviceId", "ts", "bpm", "synced"))
        assertTrue(DataBackup.planRowCopyImport(same, same, DataBackup.ImportMode.MERGE).warnings().isEmpty())
    }
}

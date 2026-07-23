package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the version-agnostic row-copy planner [DataBackup.planRowCopyImport]. The planner
 * is a pure function over two `table -> ordered [DataBackup.SchemaColumn]s` maps, so it is pinned here
 * without Robolectric or a live SQLite; the real DB executor ([DataBackup.reconcileForeignBackup]) only
 * ATTACHes the two stores and runs the SQL this planner emits, so covering the plan covers the copy.
 *
 * The target is always this app's own store ([CrossForkSchemaFixtures.ownAndroidStore]); each of the four
 * field shapes is exercised as the SOURCE, asserting the table+column intersection, the emitted
 * `INSERT OR IGNORE` (and, in REPLACE, the leading `DELETE`) statements, and the missing-table /
 * dropped-table / missing-column / filled-column warnings. The same statement/quoting shape is what
 * [DataBackup.reconcileForeignBackup] runs inside one transaction with the backup ATTACHed as `src`.
 *
 * The critical case these tests LOCK: `stepSample` / `ppgHrSample` carry a NOT NULL-no-default `synced`
 * flag (Room emits no SQL default for a Kotlin `= 0`). A source that lacks the column must FILL it with a
 * typed zero literal — omitting it would make `INSERT OR IGNORE` drop the whole table's rows on the
 * NOT NULL violation. The iOS/GRDB source below lacks `synced` and proves the fill keeps the rows.
 */
class CrossForkImportPlanTest {

    private val fx = CrossForkSchemaFixtures
    private val target = CrossForkSchemaFixtures.ownAndroidStore

    // ── Shape A: own Android store — the clean, same-schema baseline ─────────────

    @Test fun sameStoreMergePlanIsAFullCopyWithNoWarnings() {
        // Arrange: source == target (restoring the app's own Room store into itself).
        // Act
        val plan = DataBackup.planRowCopyImport(target, fx.ownAndroidStore, DataBackup.ImportMode.MERGE)

        // Assert: every data table copies its full column set (incl. the NOT NULL `synced`, present on
        // both sides so it copies straight); housekeeping (room_master_table, android_metadata,
        // sqlite_sequence) is filtered out; nothing is missing, dropped, or filled.
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO main.`dailyMetric` (`deviceId`, `day`, `restingHr`, `avgHrv`, `skinTempDevC`, `spo2Red`, `spo2Ir`) SELECT `deviceId`, `day`, `restingHr`, `avgHrv`, `skinTempDevC`, `spo2Red`, `spo2Ir` FROM src.`dailyMetric`",
                "INSERT OR IGNORE INTO main.`hrSample` (`deviceId`, `ts`, `bpm`) SELECT `deviceId`, `ts`, `bpm` FROM src.`hrSample`",
                "INSERT OR IGNORE INTO main.`ppgHrSample` (`deviceId`, `ts`, `bpm`, `conf`, `synced`) SELECT `deviceId`, `ts`, `bpm`, `conf`, `synced` FROM src.`ppgHrSample`",
                "INSERT OR IGNORE INTO main.`ppgWaveformSample` (`deviceId`, `ts`, `samples`) SELECT `deviceId`, `ts`, `samples` FROM src.`ppgWaveformSample`",
                "INSERT OR IGNORE INTO main.`rrInterval` (`deviceId`, `ts`, `rrMs`, `seq`) SELECT `deviceId`, `ts`, `rrMs`, `seq` FROM src.`rrInterval`",
                "INSERT OR IGNORE INTO main.`sleepSession` (`deviceId`, `startTs`, `endTs`, `efficiency`) SELECT `deviceId`, `startTs`, `endTs`, `efficiency` FROM src.`sleepSession`",
                "INSERT OR IGNORE INTO main.`stepSample` (`deviceId`, `ts`, `counter`, `activityClass`, `synced`) SELECT `deviceId`, `ts`, `counter`, `activityClass`, `synced` FROM src.`stepSample`",
            ),
            plan.statements,
        )
        assertEquals(emptyList<String>(), plan.missingTables)
        assertEquals(emptyList<String>(), plan.droppedTables)
        assertTrue(plan.missingColumns.isEmpty())
        assertTrue(plan.filledColumns.isEmpty())
        assertEquals(emptyList<String>(), plan.warnings())
    }

    @Test fun housekeepingTablesAreNeverCopied() {
        val plan = DataBackup.planRowCopyImport(target, fx.ownAndroidStore, DataBackup.ImportMode.MERGE)
        assertTrue(
            plan.statements.none {
                it.contains("room_master_table") || it.contains("android_metadata") || it.contains("sqlite_sequence")
            },
        )
    }

    // ── Shape B: iOS (GRDB) — REPLACE restore + the CRITICAL filled-column case ───

    @Test fun iosGrdbReplacePlanKeepsNotNullRowsByFillingSyncedAndFlagsOnDeviceOnlyColumns() {
        // Arrange: the iOS/GRDB store — same column names by the parity contract, plus a GRDB-only
        // collector table (dropped), a dailyMetric that lacks the on-device-only spo2Red/spo2Ir, and
        // stepSample/ppgHrSample that lack the Android-only NOT NULL-no-default `synced`.
        // Act
        val plan = DataBackup.planRowCopyImport(target, fx.iosGrdbStore, DataBackup.ImportMode.REPLACE)

        // Assert: REPLACE clears each shared table first, then INSERT OR IGNORE (never INSERT OR REPLACE).
        // For stepSample/ppgHrSample the source-absent `synced` is FILLED with the typed zero literal `0`
        // (INTEGER affinity) so the rows are KEPT, not dropped — the critical regression, locked.
        assertEquals(
            listOf(
                "DELETE FROM main.`dailyMetric`",
                "INSERT OR IGNORE INTO main.`dailyMetric` (`deviceId`, `day`, `restingHr`, `avgHrv`, `skinTempDevC`) SELECT `deviceId`, `day`, `restingHr`, `avgHrv`, `skinTempDevC` FROM src.`dailyMetric`",
                "DELETE FROM main.`hrSample`",
                "INSERT OR IGNORE INTO main.`hrSample` (`deviceId`, `ts`, `bpm`) SELECT `deviceId`, `ts`, `bpm` FROM src.`hrSample`",
                "DELETE FROM main.`ppgHrSample`",
                "INSERT OR IGNORE INTO main.`ppgHrSample` (`deviceId`, `ts`, `bpm`, `conf`, `synced`) SELECT `deviceId`, `ts`, `bpm`, `conf`, 0 FROM src.`ppgHrSample`",
                "DELETE FROM main.`ppgWaveformSample`",
                "INSERT OR IGNORE INTO main.`ppgWaveformSample` (`deviceId`, `ts`, `samples`) SELECT `deviceId`, `ts`, `samples` FROM src.`ppgWaveformSample`",
                "DELETE FROM main.`rrInterval`",
                "INSERT OR IGNORE INTO main.`rrInterval` (`deviceId`, `ts`, `rrMs`, `seq`) SELECT `deviceId`, `ts`, `rrMs`, `seq` FROM src.`rrInterval`",
                "DELETE FROM main.`sleepSession`",
                "INSERT OR IGNORE INTO main.`sleepSession` (`deviceId`, `startTs`, `endTs`, `efficiency`) SELECT `deviceId`, `startTs`, `endTs`, `efficiency` FROM src.`sleepSession`",
                "DELETE FROM main.`stepSample`",
                "INSERT OR IGNORE INTO main.`stepSample` (`deviceId`, `ts`, `counter`, `activityClass`, `synced`) SELECT `deviceId`, `ts`, `counter`, `activityClass`, 0 FROM src.`stepSample`",
            ),
            plan.statements,
        )
        // The two on-device-only columns the GRDB backup can't carry import empty (nullable → omitted).
        assertEquals(mapOf("dailyMetric" to listOf("spo2Red", "spo2Ir")), plan.missingColumns)
        // The NOT NULL-no-default `synced` on each of the two tables was KEPT + filled, not dropped.
        assertEquals(
            mapOf("ppgHrSample" to listOf("synced"), "stepSample" to listOf("synced")),
            plan.filledColumns,
        )
        assertEquals(emptyList<String>(), plan.missingTables)
        assertEquals(listOf("healthKitCursor"), plan.droppedTables)
        assertTrue(plan.warnings().any { it.contains("dailyMetric is missing fields spo2Red, spo2Ir") })
        assertTrue(plan.warnings().any { it.contains("Skipped tables not in this app") && it.contains("healthKitCursor") })
        // Never "imported empty" for the kept rows — the filled columns report "filled ... with defaults".
        assertTrue(plan.warnings().any { it.contains("ppgHrSample: filled synced with defaults") })
        assertTrue(plan.warnings().any { it.contains("stepSample: filled synced with defaults") })
        assertTrue(plan.warnings().none { it.contains("stepSample is missing fields") })
    }

    // ── Shape C: a behind Android fork (Room) — MERGE keeps existing rows ────────

    @Test fun behindForkMergePlanFlagsMissingTablesAndOlderColumns() {
        // Arrange: a fork a few migrations behind — no ppgWaveformSample, no stepSample/ppgHrSample, no
        // rrInterval.seq, and a dailyMetric without the v7 sleep aggregates. (In the live import this shape
        // carries no upstream-absent marker and so restores by file-swap, not reconcile; the planner is
        // still exercised here to prove the intersection/warning maths for an OLDER schema.)
        // Act
        val plan = DataBackup.planRowCopyImport(target, fx.behindAndroidFork, DataBackup.ImportMode.MERGE)

        // Assert: MERGE emits INSERT OR IGNORE only (no DELETE); only the shared columns copy.
        assertEquals(
            listOf(
                "INSERT OR IGNORE INTO main.`dailyMetric` (`deviceId`, `day`, `restingHr`, `avgHrv`) SELECT `deviceId`, `day`, `restingHr`, `avgHrv` FROM src.`dailyMetric`",
                "INSERT OR IGNORE INTO main.`hrSample` (`deviceId`, `ts`, `bpm`) SELECT `deviceId`, `ts`, `bpm` FROM src.`hrSample`",
                "INSERT OR IGNORE INTO main.`rrInterval` (`deviceId`, `ts`, `rrMs`) SELECT `deviceId`, `ts`, `rrMs` FROM src.`rrInterval`",
                "INSERT OR IGNORE INTO main.`sleepSession` (`deviceId`, `startTs`, `endTs`, `efficiency`) SELECT `deviceId`, `startTs`, `endTs`, `efficiency` FROM src.`sleepSession`",
            ),
            plan.statements,
        )
        // The newer tables have no data to import; the older dailyMetric/rrInterval columns import empty.
        assertEquals(listOf("ppgHrSample", "ppgWaveformSample", "stepSample"), plan.missingTables)
        assertEquals(emptyList<String>(), plan.droppedTables)
        assertEquals(
            mapOf(
                "dailyMetric" to listOf("skinTempDevC", "spo2Red", "spo2Ir"),
                "rrInterval" to listOf("seq"),
            ),
            plan.missingColumns,
        )
        // The behind fork lacks the whole stepSample/ppgHrSample tables (missing TABLES), not the column
        // within a shared table, so nothing is filled.
        assertTrue(plan.filledColumns.isEmpty())
        assertTrue(plan.warnings().any { it.contains("No data in this backup for") && it.contains("ppgWaveformSample") })
    }

    // ── Shape D: an ahead Android fork (Room) — REPLACE restore ──────────────────

    @Test fun aheadForkReplacePlanDropsMarkerTableAndIgnoresMarkerColumn() {
        // Arrange: the ahead fork carrying the upstream-absent spo2PctSample table + dailyMetric.skinTempAbsC.
        // Being an Android Room fork, its stepSample/ppgHrSample DO carry `synced`, so those copy straight.
        // Act
        val plan = DataBackup.planRowCopyImport(target, fx.aheadAndroidFork, DataBackup.ImportMode.REPLACE)

        // Assert: shared tables copy in full; the upstream-absent skinTempAbsC has no home here so the copy
        // runs over the shared columns only (never a failure), and the upstream-absent table is dropped.
        assertEquals(
            listOf(
                "DELETE FROM main.`dailyMetric`",
                "INSERT OR IGNORE INTO main.`dailyMetric` (`deviceId`, `day`, `restingHr`, `avgHrv`, `skinTempDevC`, `spo2Red`, `spo2Ir`) SELECT `deviceId`, `day`, `restingHr`, `avgHrv`, `skinTempDevC`, `spo2Red`, `spo2Ir` FROM src.`dailyMetric`",
                "DELETE FROM main.`hrSample`",
                "INSERT OR IGNORE INTO main.`hrSample` (`deviceId`, `ts`, `bpm`) SELECT `deviceId`, `ts`, `bpm` FROM src.`hrSample`",
                "DELETE FROM main.`ppgHrSample`",
                "INSERT OR IGNORE INTO main.`ppgHrSample` (`deviceId`, `ts`, `bpm`, `conf`, `synced`) SELECT `deviceId`, `ts`, `bpm`, `conf`, `synced` FROM src.`ppgHrSample`",
                "DELETE FROM main.`ppgWaveformSample`",
                "INSERT OR IGNORE INTO main.`ppgWaveformSample` (`deviceId`, `ts`, `samples`) SELECT `deviceId`, `ts`, `samples` FROM src.`ppgWaveformSample`",
                "DELETE FROM main.`rrInterval`",
                "INSERT OR IGNORE INTO main.`rrInterval` (`deviceId`, `ts`, `rrMs`, `seq`) SELECT `deviceId`, `ts`, `rrMs`, `seq` FROM src.`rrInterval`",
                "DELETE FROM main.`sleepSession`",
                "INSERT OR IGNORE INTO main.`sleepSession` (`deviceId`, `startTs`, `endTs`, `efficiency`) SELECT `deviceId`, `startTs`, `endTs`, `efficiency` FROM src.`sleepSession`",
                "DELETE FROM main.`stepSample`",
                "INSERT OR IGNORE INTO main.`stepSample` (`deviceId`, `ts`, `counter`, `activityClass`, `synced`) SELECT `deviceId`, `ts`, `counter`, `activityClass`, `synced` FROM src.`stepSample`",
            ),
            plan.statements,
        )
        assertEquals(emptyList<String>(), plan.missingTables)
        assertEquals(listOf("spo2PctSample"), plan.droppedTables)
        // skinTempAbsC is a source-only column with no target home — ignored, not a missing-column warning.
        assertTrue(plan.missingColumns.isEmpty())
        // `synced` is present on both sides here, so nothing is filled.
        assertTrue(plan.filledColumns.isEmpty())
        assertTrue(plan.statements.none { it.contains("skinTempAbsC") })
        assertTrue(plan.warnings().any { it.contains("Skipped tables not in this app") && it.contains("spo2PctSample") })
    }

    // ── The fill contract in isolation (NOT NULL-no-default vs nullable) ─────────

    @Test fun sourceMissingNotNullNoDefaultColumnIsFilledWhileNullableColumnIsOmitted() {
        // A target table with: a shared column, a source-missing NOT NULL-no-default column (must be
        // FILLED with a typed zero literal or INSERT OR IGNORE drops every row), and a source-missing
        // NULLABLE column (safely omitted so SQLite fills NULL).
        val target = mapOf(
            "t" to listOf(
                DataBackup.SchemaColumn("id", "INTEGER", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("synced", "INTEGER", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("note", "TEXT", notNull = false, hasDefault = false),
            ),
        )
        val source = mapOf(
            "t" to listOf(DataBackup.SchemaColumn("id", "INTEGER", notNull = true, hasDefault = false)),
        )

        val plan = DataBackup.planRowCopyImport(target, source, DataBackup.ImportMode.MERGE)

        // `synced` appears in the INSERT column list with a typed zero literal in its SELECT slot; `note`
        // is omitted from both lists entirely.
        assertEquals(
            listOf("INSERT OR IGNORE INTO main.`t` (`id`, `synced`) SELECT `id`, 0 FROM src.`t`"),
            plan.statements,
        )
        assertEquals(mapOf("t" to listOf("synced")), plan.filledColumns)
        assertEquals(mapOf("t" to listOf("note")), plan.missingColumns)
        assertTrue(plan.warnings().any { it == "t: filled synced with defaults." })
        assertTrue(plan.warnings().any { it == "t is missing fields note (imported empty)." })
    }

    @Test fun aSourceMissingColumnThatHasAnExplicitDefaultIsOmittedNotFilled() {
        // A NOT NULL column that DOES carry a schema default (e.g. the GRDB `NOT NULL DEFAULT 0` twin of a
        // Room `synced`) is safely omitted — SQLite fills the declared default, so no literal is needed.
        val target = mapOf(
            "t" to listOf(
                DataBackup.SchemaColumn("id", "INTEGER", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("synced", "INTEGER", notNull = true, hasDefault = true),
            ),
        )
        val source = mapOf(
            "t" to listOf(DataBackup.SchemaColumn("id", "INTEGER", notNull = true, hasDefault = false)),
        )

        val plan = DataBackup.planRowCopyImport(target, source, DataBackup.ImportMode.MERGE)

        assertEquals(
            listOf("INSERT OR IGNORE INTO main.`t` (`id`) SELECT `id` FROM src.`t`"),
            plan.statements,
        )
        assertTrue(plan.filledColumns.isEmpty())
        assertEquals(mapOf("t" to listOf("synced")), plan.missingColumns)
    }

    @Test fun filledZeroLiteralFollowsColumnAffinity() {
        // Each declared type gets its own zero literal: TEXT → '', BLOB → x'', and everything else —
        // INTEGER, REAL, and an UNTYPED column — numeric 0. The untyped case matches the Swift twin's
        // zeroLiteral exactly (both emit 0), the one point the two used to diverge (Kotlin emitted x'').
        val target = mapOf(
            "t" to listOf(
                DataBackup.SchemaColumn("k", "INTEGER", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("i", "INTEGER", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("r", "REAL", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("s", "TEXT", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("b", "BLOB", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("u", "", notNull = true, hasDefault = false),
            ),
        )
        val source = mapOf(
            "t" to listOf(DataBackup.SchemaColumn("k", "INTEGER", notNull = true, hasDefault = false)),
        )

        val plan = DataBackup.planRowCopyImport(target, source, DataBackup.ImportMode.MERGE)

        assertEquals(
            listOf("INSERT OR IGNORE INTO main.`t` (`k`, `i`, `r`, `s`, `b`, `u`) SELECT `k`, 0, 0, '', x'', 0 FROM src.`t`"),
            plan.statements,
        )
    }

    // ── The key-collapse guard: a source-absent NOT NULL-no-default KEY column is filled with rowid ────

    @Test fun sourceMissingNotNullNoDefaultKeyColumnFillsItWithRowidSoRowsImport() {
        // hrSample's PK column `ts` is NOT NULL, no default, and a KEY. The backup renamed it (`stamp`),
        // so `ts` is source-absent. A CONSTANT fill would give every row the SAME key and INSERT OR IGNORE
        // would collapse the table to one row (the reviewer's data-loss hole). Filling the source `rowid`
        // (per-row-unique) instead keeps every row without collapsing — the real rrInterval-`seq` case. A
        // sibling table whose key IS present copies straight.
        val target = mapOf(
            "hrSample" to listOf(
                DataBackup.SchemaColumn("deviceId", "TEXT", notNull = true, hasDefault = false, key = true),
                DataBackup.SchemaColumn("ts", "INTEGER", notNull = true, hasDefault = false, key = true),
                DataBackup.SchemaColumn("bpm", "INTEGER", notNull = false, hasDefault = false),
            ),
            "sleepSession" to listOf(
                DataBackup.SchemaColumn("deviceId", "TEXT", notNull = true, hasDefault = false, key = true),
                DataBackup.SchemaColumn("startTs", "INTEGER", notNull = true, hasDefault = false, key = true),
                DataBackup.SchemaColumn("efficiency", "REAL", notNull = false, hasDefault = false),
            ),
        )
        val source = mapOf(
            "hrSample" to listOf(
                DataBackup.SchemaColumn("deviceId", "TEXT", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("stamp", "INTEGER", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("bpm", "INTEGER", notNull = false, hasDefault = false),
            ),
            "sleepSession" to listOf(
                DataBackup.SchemaColumn("deviceId", "TEXT", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("startTs", "INTEGER", notNull = true, hasDefault = false),
                DataBackup.SchemaColumn("efficiency", "REAL", notNull = false, hasDefault = false),
            ),
        )

        val plan = DataBackup.planRowCopyImport(target, source, DataBackup.ImportMode.REPLACE)

        // hrSample imports with `ts` filled from the source rowid; sleepSession copies straight.
        assertEquals(
            listOf(
                "DELETE FROM main.`hrSample`",
                "INSERT OR IGNORE INTO main.`hrSample` (`deviceId`, `ts`, `bpm`) SELECT `deviceId`, rowid, `bpm` FROM src.`hrSample`",
                "DELETE FROM main.`sleepSession`",
                "INSERT OR IGNORE INTO main.`sleepSession` (`deviceId`, `startTs`, `efficiency`) SELECT `deviceId`, `startTs`, `efficiency` FROM src.`sleepSession`",
            ),
            plan.statements,
        )
        assertEquals(mapOf("hrSample" to listOf("ts")), plan.synthesizedKeyColumns)
        assertEquals(listOf("hrSample", "sleepSession"), plan.copiedTables)
        assertTrue(
            plan.warnings().any {
                it == "hrSample: generated ids for the key column(s) ts this backup didn't carry."
            },
        )
    }

    @Test fun aSourceMissingKeyColumnThatIsNullableIsOmittedNotRowidFilled() {
        // Only a NOT NULL-no-default key is rowid-filled. A nullable key column the source lacks is safely
        // OMITTED — SQLite fills NULL, and NULLs never collide in a UNIQUE index — so it needs no synthetic
        // id. This locks that the rowid fill fires on the (NOT NULL ∧ no-default ∧ key) triple, not on `key`
        // alone.
        val target = mapOf(
            "t" to listOf(
                DataBackup.SchemaColumn("id", "INTEGER", notNull = true, hasDefault = false, key = true),
                DataBackup.SchemaColumn("altKey", "TEXT", notNull = false, hasDefault = false, key = true),
            ),
        )
        val source = mapOf(
            "t" to listOf(DataBackup.SchemaColumn("id", "INTEGER", notNull = true, hasDefault = false)),
        )

        val plan = DataBackup.planRowCopyImport(target, source, DataBackup.ImportMode.MERGE)

        assertEquals(
            listOf("INSERT OR IGNORE INTO main.`t` (`id`) SELECT `id` FROM src.`t`"),
            plan.statements,
        )
        assertTrue(plan.synthesizedKeyColumns.isEmpty())
        assertEquals(mapOf("t" to listOf("altKey")), plan.missingColumns)
    }

    // ── Mode contract shared across shapes ──────────────────────────────────────

    @Test fun replaceEmitsOneDeletePerInsertWhileMergeEmitsNone() {
        val replace = DataBackup.planRowCopyImport(target, fx.aheadAndroidFork, DataBackup.ImportMode.REPLACE)
        val inserts = replace.statements.count { it.startsWith("INSERT") }
        assertEquals(inserts, replace.statements.count { it.startsWith("DELETE") })

        val merge = DataBackup.planRowCopyImport(target, fx.aheadAndroidFork, DataBackup.ImportMode.MERGE)
        assertEquals(0, merge.statements.count { it.startsWith("DELETE") })
        assertEquals(inserts, merge.statements.count { it.startsWith("INSERT") })
    }

    @Test fun everyInsertIsOrIgnoreNeverOrReplace() {
        val shapes = listOf(fx.ownAndroidStore, fx.iosGrdbStore, fx.behindAndroidFork, fx.aheadAndroidFork)
        for (source in shapes) {
            for (mode in DataBackup.ImportMode.values()) {
                val plan = DataBackup.planRowCopyImport(target, source, mode)
                plan.statements.filter { it.startsWith("INSERT") }.forEach {
                    assertTrue(it, it.startsWith("INSERT OR IGNORE INTO main."))
                }
            }
        }
    }
}

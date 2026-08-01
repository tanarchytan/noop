package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decision behind the restore repair: what a store is missing, what SQL brings it up, and
 * when no additive statement can. Reading a real store needs the Android SQLite stack, so the
 * reader is exercised by the corpus harness; everything decided from a shape is pinned here.
 */
class SchemaShapeTest {

    private fun col(
        name: String,
        type: String = "INTEGER",
        notNull: Boolean = false,
        default: String? = null,
        pk: Int = 0,
    ) = SchemaShape.Column(name, type, notNull, default, pk)

    private fun table(
        name: String,
        columns: List<SchemaShape.Column>,
        indexes: Map<String, String> = emptyMap(),
    ) = name.lowercase() to SchemaShape.Table(
        name = name,
        columns = columns,
        createSql = "CREATE TABLE IF NOT EXISTS `$name` (…)",
        indexes = indexes,
    )

    /** `pairedDevice` after v101 absorbed the read-scope columns, as Room declares it. */
    private val pairedDeviceNow = table(
        "pairedDevice",
        listOf(
            col("id", "TEXT", notNull = true, pk = 1),
            col("status", "TEXT", notNull = true),
            col("dataIncluded", "INTEGER", notNull = true, default = "1"),
            col("serial", "TEXT"),
        ),
    )

    /** The same table as a backup written before v101 absorbed them carries it. */
    private val pairedDeviceBefore = table(
        "pairedDevice",
        listOf(
            col("id", "TEXT", notNull = true, pk = 1),
            col("status", "TEXT", notNull = true),
        ),
    )

    private fun plan(target: Map<String, SchemaShape.Table>, actual: Map<String, SchemaShape.Table>) =
        SchemaShape.plan(target, actual)

    // ── what is missing, and the SQL that repairs it ──────────────────────────

    /** A store the version chain left short of the two read-scope columns asks for exactly the two
     *  statements the migration itself runs — the general repair subsumes the old two-column heal. */
    @Test
    fun aPreFoldStoreAsksForTheTwoDeviceScopeStatements() {
        val p = plan(mapOf(pairedDeviceNow), mapOf(pairedDeviceBefore))
        assertEquals(emptyList<String>(), p.refusals)
        assertEquals(WhoopDatabase.DATA_INCLUDED_MIGRATION_SQL, p.statements)
    }

    /** Half a repair (a run that died between the two) asks only for what is still missing. */
    @Test
    fun aHalfRepairedStoreAsksOnlyForTheRest() {
        val half = table(
            "pairedDevice",
            listOf(
                col("id", "TEXT", notNull = true, pk = 1),
                col("status", "TEXT", notNull = true),
                col("dataIncluded", "INTEGER", notNull = true, default = "1"),
            ),
        )
        val p = plan(mapOf(pairedDeviceNow), mapOf(half))
        assertEquals(listOf(WhoopDatabase.DATA_INCLUDED_MIGRATION_SQL[1]), p.statements)
    }

    /** A current store asks for nothing, which is what makes a second restore a no-op. */
    @Test
    fun aCurrentStoreIsClean() {
        assertTrue(plan(mapOf(pairedDeviceNow), mapOf(pairedDeviceNow)).isClean)
    }

    /** Applying the plan clears it: the repair converges rather than re-asking every restore. */
    @Test
    fun theRepairIsIdempotent() {
        val once = plan(mapOf(pairedDeviceNow), mapOf(pairedDeviceBefore))
        assertTrue(once.statements.isNotEmpty())
        assertTrue(plan(mapOf(pairedDeviceNow), mapOf(pairedDeviceNow)).isClean)
    }

    /** SQLite names are case-insensitive, so a differently-cased store must not be re-ALTERed. */
    @Test
    fun columnMatchingIgnoresCase() {
        val shouty = table(
            "PAIREDDEVICE",
            listOf(
                col("ID", "TEXT", notNull = true, pk = 1),
                col("STATUS", "TEXT", notNull = true),
                col("DATAINCLUDED", "INTEGER", notNull = true, default = "1"),
                col("Serial", "TEXT"),
            ),
        )
        assertTrue(plan(mapOf(pairedDeviceNow), mapOf(shouty)).isClean)
    }

    /** A table the store never had is created whole, with its indexes, before anything else. */
    @Test
    fun aMissingTableIsCreatedWithItsIndexes() {
        val target = table("labMarker", listOf(col("id", "TEXT", notNull = true, pk = 1)),
            indexes = mapOf("idx_a" to "CREATE INDEX `idx_a` ON `labMarker` (`id`)"))
        val p = plan(mapOf(target), emptyMap())
        assertEquals(emptyList<String>(), p.refusals)
        assertEquals(
            listOf("CREATE TABLE IF NOT EXISTS `labMarker` (…)", "CREATE INDEX `idx_a` ON `labMarker` (`id`)"),
            p.statements,
        )
    }

    /** A declared index missing from an existing table is added on its own. */
    @Test
    fun aMissingIndexIsAdded() {
        val create = "CREATE INDEX `idx_a` ON `t` (`v`)"
        val target = table("t", listOf(col("v")), indexes = mapOf("idx_a" to create))
        val actual = table("t", listOf(col("v")))
        assertEquals(listOf(create), plan(mapOf(target), mapOf(actual)).statements)
    }

    /** The ADD COLUMN definition is rebuilt from the target's own facts, defaults included. */
    @Test
    fun theAddColumnDefinitionCarriesTypeNullabilityAndDefault() {
        val target = table("t", listOf(col("a"), col("b", "REAL"), col("c", "INTEGER", notNull = true, default = "1")))
        val p = plan(mapOf(target), mapOf(table("t", emptyList())))
        assertEquals(
            listOf(
                "ALTER TABLE `t` ADD COLUMN `a` INTEGER",
                "ALTER TABLE `t` ADD COLUMN `b` REAL",
                "ALTER TABLE `t` ADD COLUMN `c` INTEGER NOT NULL DEFAULT 1",
            ),
            p.statements,
        )
    }

    // ── when to refuse ───────────────────────────────────────────────────────

    /** SQLite cannot append a required column with no default to a table that already holds rows. */
    @Test
    fun aMissingRequiredColumnWithNoDefaultIsRefused() {
        val target = table("t", listOf(col("v", "INTEGER", notNull = true)))
        val p = plan(mapOf(target), mapOf(table("t", emptyList())))
        assertEquals(emptyList<String>(), p.statements)
        assertTrue(p.refusals.single().contains("t.v"))
    }

    /** Nor a primary-key column: the table would have to be rebuilt, which is not additive. */
    @Test
    fun aMissingKeyColumnIsRefused() {
        val target = table("t", listOf(col("k", "TEXT", notNull = true, default = "''", pk = 1)))
        val p = plan(mapOf(target), mapOf(table("t", emptyList())))
        assertEquals(emptyList<String>(), p.statements)
        assertTrue(p.refusals.single().contains("primary key"))
    }

    /** A column that exists with the wrong type is a different table, not a repairable one. */
    @Test
    fun aTypeMismatchIsRefused() {
        val target = table("t", listOf(col("v", "TEXT")))
        val p = plan(mapOf(target), mapOf(table("t", listOf(col("v", "INTEGER")))))
        assertTrue(p.refusals.single().contains("type INTEGER"))
    }

    @Test
    fun aNullabilityMismatchIsRefused() {
        val target = table("t", listOf(col("v", "INTEGER", notNull = true, default = "0")))
        val p = plan(mapOf(target), mapOf(table("t", listOf(col("v", "INTEGER")))))
        assertTrue(p.refusals.single().contains("notNull"))
    }

    @Test
    fun aKeyPositionMismatchIsRefused() {
        val target = table("t", listOf(col("v", "TEXT", notNull = true, pk = 1)))
        val p = plan(mapOf(target), mapOf(table("t", listOf(col("v", "TEXT", notNull = true, pk = 2)))))
        assertTrue(p.refusals.single().contains("key position"))
    }

    /** A column no entity declares breaks Room's open just as a missing one does, and SQLite on
     *  minSdk cannot drop it — so it is refused rather than stamped over. */
    @Test
    fun anExtraColumnIsRefused() {
        val p = plan(mapOf(table("t", listOf(col("v")))), mapOf(table("t", listOf(col("v"), col("legacy")))))
        assertEquals(emptyList<String>(), p.statements)
        assertTrue(p.refusals.single().contains("legacy"))
    }

    @Test
    fun anExtraIndexIsRefused() {
        val target = table("t", listOf(col("v")))
        val actual = table("t", listOf(col("v")), indexes = mapOf("idx_old" to "CREATE INDEX `idx_old` ON `t` (`v`)"))
        assertTrue(plan(mapOf(target), mapOf(actual)).refusals.single().contains("idx_old"))
    }

    /** A default the store carries but no entity declares is what every pre-v7 store gets from its
     *  own migration. Room ignores it, so this must not refuse a store Room would open. */
    @Test
    fun anUndeclaredDefaultIsAccepted() {
        val target = table("t", listOf(col("userEdited", "INTEGER", notNull = true)))
        val actual = table("t", listOf(col("userEdited", "INTEGER", notNull = true, default = "0")))
        assertTrue(plan(mapOf(target), mapOf(actual)).isClean)
    }

    /** A declared default that the store contradicts IS compared, so read scope can't silently invert. */
    @Test
    fun aContradictedDeclaredDefaultIsRefused() {
        val actual = table(
            "pairedDevice",
            listOf(
                col("id", "TEXT", notNull = true, pk = 1),
                col("status", "TEXT", notNull = true),
                col("dataIncluded", "INTEGER", notNull = true, default = "0"),
                col("serial", "TEXT"),
            ),
        )
        assertTrue(plan(mapOf(pairedDeviceNow), mapOf(actual)).refusals.single().contains("default 0"))
    }

    /** A refusal has to name what it refused: it becomes the user's failure message. */
    @Test
    fun theRefusalMessageNamesTheColumn() {
        val target = table("t", listOf(col("v", "INTEGER", notNull = true)))
        val text = SchemaShape.describe(plan(mapOf(target), mapOf(table("t", emptyList()))))
        assertTrue(text, text.contains("t.v"))
    }

    /** Statements that survive a repair pass are reported too — an unapplied one is not "clean". */
    @Test
    fun describeAlsoNamesUnappliedStatements() {
        val text = SchemaShape.describe(plan(mapOf(pairedDeviceNow), mapOf(pairedDeviceBefore)))
        assertTrue(text, text.contains("could not apply") && text.contains("dataIncluded"))
    }
}

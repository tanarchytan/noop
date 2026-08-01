package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the v101 -> v102 step that adds `pairedDevice.dataIncluded`
 * ([WhoopDatabase.DATA_INCLUDED_MIGRATION_SQL]), the column that splits read scope off the BLE status.
 * This environment has no Robolectric / Room-testing, so the SQL is pinned to Room's generated shape
 * for [PairedDeviceRow.dataIncluded] and the two axes are proven on the pure functions that read them.
 */
class DataIncludedMigrationTest {

    private val sql = WhoopDatabase.DATA_INCLUDED_MIGRATION_SQL

    @Test
    fun migration_addsOneNotNullColumnDefaultingToIncluded() {
        assertEquals("one ADD COLUMN", 1, sql.size)
        assertEquals(
            "ALTER TABLE `pairedDevice` ADD COLUMN `dataIncluded` INTEGER NOT NULL DEFAULT 1",
            sql[0],
        )
        val up = sql[0].uppercase()
        for (banned in listOf("DROP ", "DELETE ", "UPDATE ")) {
            assertFalse("the step must stay additive: $banned", up.contains(banned))
        }
    }

    /** DEFAULT 1 is the fix: every row an existing install already carries — the removed ones included
     *  — stays in the read scope, so the upgrade itself can never hide a recorded day. */
    @Test
    fun everyExistingRowUpgradesToIncluded() {
        assertTrue("existing rows must default to included", sql[0].endsWith("DEFAULT 1"))
        assertTrue("the entity default must agree", deviceRow("x").dataIncluded)
    }

    /** The version pair, and that a device sitting on v101 has a step to take. v101 shipped to a phone
     *  holding real history, so its column set is frozen and new columns land on v102. */
    @Test
    fun migration_isWiredAsTheV102Step() {
        assertEquals(101, WhoopDatabase.MIGRATION_101_102.startVersion)
        assertEquals(102, WhoopDatabase.MIGRATION_101_102.endVersion)
        assertEquals(102, WhoopDatabase.SCHEMA_VERSION)
        assertTrue(
            "the step must be reachable from ALL_MIGRATIONS",
            WhoopDatabase.ALL_MIGRATIONS.any { it.startVersion == 101 && it.endVersion == 102 },
        )
    }

    /** Every version the migration list starts from reaches [WhoopDatabase.SCHEMA_VERSION] by following
     *  the steps, so no install is stranded one version short of the entity set. */
    @Test
    fun everyStartVersionReachesTheCurrentSchema() {
        val steps = WhoopDatabase.ALL_MIGRATIONS.groupBy { it.startVersion }
        for (from in steps.keys) {
            var at = from
            var hops = 0
            while (at < WhoopDatabase.SCHEMA_VERSION && hops < 200) {
                at = steps[at]?.maxOf { it.endVersion } ?: break
                hops++
            }
            assertEquals("v$from must reach the current schema", WhoopDatabase.SCHEMA_VERSION, at)
        }
    }

    // ── The two axes the column creates ──────────────────────────────────────────────────────────────

    private val strap = "whoop-E4:0B:03:2B:C6:21"

    /** Presence governs BLE alone: a removed strap is unreachable AND fully readable. */
    @Test
    fun removedMeansUnreachableNotUnreadable() {
        val devices = listOf(deviceRow(strap, DeviceStatus.archived, addedAt = 1L))
        assertTrue(strap in WhoopRepository.importedSourceIdsFor(devices))
        assertTrue("a removed strap is not a connect target", connectableDevices(devices).isEmpty())
    }

    /** Inclusion governs reads alone: an excluded strap is unreadable AND still reachable. */
    @Test
    fun excludedMeansUnreadableNotUnreachable() {
        val devices = listOf(deviceRow(strap, DeviceStatus.active, addedAt = 1L, included = false))
        assertFalse(strap in WhoopRepository.importedSourceIdsFor(devices))
        assertEquals("an excluded strap is still a connect target", listOf(strap), connectableDevices(devices).map { it.id })
    }
}

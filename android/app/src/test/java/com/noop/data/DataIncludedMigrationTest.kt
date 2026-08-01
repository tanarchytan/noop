package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `pairedDevice.dataIncluded` statements ([WhoopDatabase.DATA_INCLUDED_MIGRATION_SQL]) that
 * split read scope off the BLE status, the unreleased v101 step carrying them, and the restore heal that
 * brings an earlier-v101 backup up to them. This environment has no Robolectric / Room-testing, so the
 * SQL is pinned to Room's generated shape for [PairedDeviceRow.dataIncluded] and the heal is proven on
 * the pure function that decides it.
 */
class DataIncludedMigrationTest {

    private val sql = WhoopDatabase.DATA_INCLUDED_MIGRATION_SQL

    @Test
    fun migration_addsTheTwoDeviceColumns() {
        assertEquals("two ADD COLUMNs", 2, sql.size)
        assertEquals(
            "ALTER TABLE `pairedDevice` ADD COLUMN `dataIncluded` INTEGER NOT NULL DEFAULT 1",
            sql[0],
        )
        assertEquals("ALTER TABLE `pairedDevice` ADD COLUMN `serial` TEXT", sql[1])
        val up = sql.joinToString(" ").uppercase()
        for (banned in listOf("DROP ", "DELETE ", "UPDATE ")) {
            assertFalse("the step must stay additive: $banned", up.contains(banned))
        }
        assertFalse("an unread serial must read back null", sql[1].uppercase().contains("NOT NULL"))
    }

    /** Nothing ever rewrites a stored `deviceId`: provenance is the only record of which strap measured
     *  what, so identity resolution moves an ADDRESS onto a row, never rows onto each other. */
    @Test
    fun theStepRewritesNoStoredDeviceId() {
        assertFalse(sql.joinToString(" ").contains("deviceId"))
        assertEquals(null, deviceRow("x").serial)
    }

    /** DEFAULT 1 is the fix: every row an existing install already carries — the removed ones included
     *  — stays in the read scope, so the upgrade itself can never hide a recorded day. */
    @Test
    fun everyExistingRowUpgradesToIncluded() {
        assertTrue("existing rows must default to included", sql[0].endsWith("DEFAULT 1"))
        assertTrue("the entity default must agree", deviceRow("x").dataIncluded)
    }

    /** v101 is unreleased, so it absorbs these columns instead of burning a version: a v100 install
     *  reaches the whole schema in ONE hop and no step past v101 exists. */
    @Test
    fun theColumnsLandOnTheUnreleasedV101Step() {
        assertEquals("no version bump: v101 is unreleased", 101, WhoopDatabase.SCHEMA_VERSION)
        assertTrue(
            "v100 must reach the current schema in one hop",
            WhoopDatabase.ALL_MIGRATIONS.any { it.startVersion == 100 && it.endVersion == 101 },
        )
        assertTrue(
            "no migration may step past the current schema",
            WhoopDatabase.ALL_MIGRATIONS.none { it.endVersion > WhoopDatabase.SCHEMA_VERSION },
        )
    }

    /** Each pair names the column its own statement adds. A mis-paired name makes the heal re-add a
     *  column the store already has, which SQLite rejects outright. */
    @Test
    fun eachScopeColumnNameMatchesItsOwnStatement() {
        assertEquals(2, WhoopDatabase.DEVICE_SCOPE_COLUMNS.size)
        for ((name, stmt) in WhoopDatabase.DEVICE_SCOPE_COLUMNS) {
            assertTrue("$name is not what its statement adds: $stmt", stmt.contains("ADD COLUMN `$name`"))
        }
        assertEquals(
            "the statements are the migration's, in order",
            WhoopDatabase.DATA_INCLUDED_MIGRATION_SQL,
            WhoopDatabase.DEVICE_SCOPE_COLUMNS.map { it.second },
        )
    }

    // ── The restore heal for a backup written before v101 absorbed the columns ───────────────────────

    /** A store from the earlier v101 carries the version but neither column, so it needs both. */
    @Test
    fun aPreFoldStoreNeedsBothColumns() {
        val before = setOf("id", "brand", "model", "nickname", "peripheralId", "sourceKind", "capabilities", "status", "addedAt", "lastSeenAt")
        assertEquals(WhoopDatabase.DATA_INCLUDED_MIGRATION_SQL, WhoopDatabase.missingDeviceScopeSql(before))
    }

    /** The heal is idempotent: a current store asks for nothing, so a second restore is a no-op. */
    @Test
    fun aCurrentStoreNeedsNothing() {
        val after = setOf("id", "status", "addedAt", "lastSeenAt", "dataIncluded", "serial")
        assertEquals(emptyList<String>(), WhoopDatabase.missingDeviceScopeSql(after))
        assertEquals(
            "a store healed once must ask for nothing the second time",
            emptyList<String>(),
            WhoopDatabase.missingDeviceScopeSql(after + setOf("brand", "model")),
        )
    }

    /** Half a heal (a run that died between the two ALTERs) asks only for the column still missing. */
    @Test
    fun aHalfHealedStoreNeedsOnlyTheRest() {
        val half = setOf("id", "status", "dataIncluded")
        assertEquals(listOf(WhoopDatabase.DATA_INCLUDED_MIGRATION_SQL[1]), WhoopDatabase.missingDeviceScopeSql(half))
    }

    /** SQLite column names are case-insensitive, so a differently-cased store must not be re-ALTERed. */
    @Test
    fun columnMatchingIgnoresCase() {
        assertEquals(emptyList<String>(), WhoopDatabase.missingDeviceScopeSql(setOf("DATAINCLUDED", "Serial")))
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

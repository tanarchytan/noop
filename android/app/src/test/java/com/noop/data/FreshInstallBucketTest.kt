package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what a FRESH install starts with. A fresh install runs no migrations, so the `pairedDevice`
 * seed is written by [WhoopDatabase.freshInstallBucketSql] alone. Seeded as a `liveBLE` device with
 * no `peripheralId` it is a strap that does not exist: every new user got one, and a user who never
 * paired recorded days under it. It is seeded `legacy` instead — a pile of data, not hardware —
 * which is the same end state MIGRATION_100_101 gives an upgraded install, and it becomes a device
 * only when a strap adopts it (covered by DeviceRegistryTest's adoption tests).
 */
class FreshInstallBucketTest {

    private val sql = WhoopDatabase.freshInstallBucketSql(1_700_000_000L)

    @Test
    fun theSeededBucketIsNotADevice() {
        assertTrue("seed must name a sourceKind", sql.contains("'legacy'"))
        assertFalse("a fresh install must not seed a live device", sql.contains("'liveBLE'"))
        // The registry filters the device list on exactly this set.
        for (kind in DEVICE_SOURCE_KINDS) {
            assertFalse("seed must not use a device kind, found '$kind'", sql.contains("'$kind'"))
        }
        assertTrue("legacy must not be a device kind", SourceKind.legacy.name !in DEVICE_SOURCE_KINDS)
    }

    @Test
    fun theSeedLeavesPeripheralIdUnset() {
        // peripheralId is absent from the column list, so it defaults to NULL. Adoption sets it, and
        // that is what promotes the bucket to a device.
        assertFalse("seed must not claim an address", sql.contains("peripheralId"))
    }

    @Test
    fun theSeedIsIdempotentAndInsertOnly() {
        val up = sql.uppercase()
        assertTrue("must be INSERT OR IGNORE so a re-run/restore is a no-op", up.startsWith("INSERT OR IGNORE INTO"))
        for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "ALTER ")) {
            assertFalse("seed must not contain '$banned': $sql", up.contains(banned))
        }
    }

    @Test
    fun theSeedCarriesTheCanonicalIdAndBrand() {
        assertTrue(sql.contains("'my-whoop'"))
        // isWhoop() keys off id + brand, so the bucket still classifies as WHOOP for the BLE path.
        assertTrue(sql.contains("'WHOOP'"))
        assertTrue("timestamps are the passed second", sql.contains("1700000000"))
    }

    /**
     * A fresh install and an upgraded install must end in the SAME state. MIGRATION_100_101 retags a
     * null-peripheral `my-whoop` to `legacy`; the create path must not disagree with it.
     */
    @Test
    fun createAgreesWithTheUpgradePath() {
        val retag = WhoopDatabase.ALL_MIGRATIONS.first { it.startVersion == 100 && it.endVersion == 101 }
        assertEquals(100, retag.startVersion)
        assertEquals(101, retag.endVersion)
        assertTrue("create must seed the kind the upgrade retags to", sql.contains("'${SourceKind.legacy.name}'"))
    }
}

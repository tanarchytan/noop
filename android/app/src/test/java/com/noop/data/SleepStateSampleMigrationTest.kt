package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the additive v14 -> v15 Room migration (`sleepStateSample` table). The SQL is exposed as
 * [WhoopDatabase.SLEEP_STATE_SAMPLE_MIGRATION_SQL] and pinned to Room's generated CREATE-TABLE shape;
 * additive only (no DROP/DELETE/UPDATE/INSERT/ALTER). `state` holds the band's raw 0-3 code.
 */
class SleepStateSampleMigrationTest {

    @Test
    fun migration_isAdditive_onlyCreateTable() {
        val sql = WhoopDatabase.SLEEP_STATE_SAMPLE_MIGRATION_SQL
        assertEquals("one CREATE TABLE statement", 1, sql.size)
        for (s in sql) {
            val up = s.trimStart().uppercase()
            assertTrue("only CREATE TABLE allowed, got: $s", up.startsWith("CREATE TABLE"))
            for (banned in listOf("DROP ", "DELETE ", "UPDATE ", "INSERT ", "ALTER ")) {
                assertTrue("additive migration must not contain '$banned': $s", !up.contains(banned))
            }
        }
    }

    @Test
    fun migration_createsExactTable() {
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS `sleepStateSample` (`deviceId` TEXT NOT NULL, " +
                    "`ts` INTEGER NOT NULL, `state` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
            ),
            WhoopDatabase.SLEEP_STATE_SAMPLE_MIGRATION_SQL,
        )
    }

    @Test
    fun migration_versionPair_is14to15() {
        assertEquals(14, WhoopDatabase.MIGRATION_14_15.startVersion)
        assertEquals(15, WhoopDatabase.MIGRATION_14_15.endVersion)
    }

    /**
     * The entity + transient decode row carry the raw 0-3 code verbatim (incl. 0, a real wake reading)
     * and round-trip once the table exists.
     */
    @Test
    fun sleepStateRow_andEntity_shape() {
        val row = SleepStateRow(1_780_916_150L, 0)   // wake, carried verbatim
        assertEquals(0, row.state)
        val entity = SleepStateSampleEntity("my-whoop", row.ts, row.state)
        assertEquals("my-whoop", entity.deviceId)
        assertEquals(1_780_916_150L, entity.ts)
        assertEquals(0, entity.state)
    }
}

package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the v100 -> v101 statements that split the one `userEdited` bit into a bound each
 * ([WhoopDatabase.SLEEP_END_ADJUSTED_MIGRATION_SQL]) and the accessor pair they back. This environment
 * has no Robolectric / Room-testing, so the SQL is pinned to Room's generated shape for
 * [SleepSession.endTsAdjusted] and the backfill predicate is proven on the fingerprint it rests on.
 */
class SleepEndAdjustedMigrationTest {

    private val sql = WhoopDatabase.SLEEP_END_ADJUSTED_MIGRATION_SQL

    @Test
    fun migration_addsOneNullableColumn_thenBackfillsIt() {
        assertEquals("one ADD COLUMN, one backfill", 2, sql.size)
        assertEquals("ALTER TABLE `sleepSession` ADD COLUMN `endTsAdjusted` INTEGER", sql[0])
        val up = sql[0].uppercase()
        for (banned in listOf("NOT NULL", "DEFAULT", "DROP ", "DELETE ")) {
            assertTrue("the column must stay nullable and additive: $banned", !up.contains(banned))
        }
    }

    /**
     * The backfill is scoped to hand-edited rows only, and freezes them off the wake picker's
     * whole-minute fingerprint — never every edited row, which would re-freeze the defect.
     */
    @Test
    fun backfill_isScopedToEditedRowsOnAWholeMinute() {
        assertEquals(
            "UPDATE `sleepSession` SET `endTsAdjusted` = `endTs` " +
                "WHERE `userEdited` = 1 AND `endTs` % 60 = 0",
            sql[1],
        )
        assertTrue("must never touch a detected row", sql[1].contains("`userEdited` = 1"))
        assertTrue("must key on the whole-minute fingerprint", sql[1].contains("`endTs` % 60 = 0"))
    }

    @Test
    fun migration_isPartOfTheUnreleasedV101Step() {
        assertEquals(100, WhoopDatabase.MIGRATION_100_101.startVersion)
        assertEquals(101, WhoopDatabase.MIGRATION_100_101.endVersion)
        assertEquals("no version bump: v101 is unreleased", 101, WhoopDatabase.SCHEMA_VERSION)
    }

    // ── The accessor pair the column backs ───────────────────────────────────────────────────────────

    private fun session(endTs: Long, endTsAdjusted: Long? = null, startTsAdjusted: Long? = null) =
        SleepSession(
            deviceId = "my-whoop-noop", startTs = 1_000L, endTs = endTs,
            userEdited = endTsAdjusted != null || startTsAdjusted != null,
            startTsAdjusted = startTsAdjusted, endTsAdjusted = endTsAdjusted,
        )

    /** An old row (and every detected row) reads the detected end — the same value as before. */
    @Test
    fun absentColumnReadsTheDetectedEnd() {
        val row = session(endTs = 5_000)
        assertNull(row.endTsAdjusted)
        assertEquals(5_000L, row.effectiveEndTs)
    }

    /** A hand-set wake is what the app displays; the detected end stays on the row beside it. */
    @Test
    fun handSetWakeWinsTheEffectiveRead() {
        val row = session(endTs = 5_000, endTsAdjusted = 7_600)
        assertEquals("the detected end is preserved", 5_000L, row.endTs)
        assertEquals("the user's wake is what is read", 7_600L, row.effectiveEndTs)
    }

    /** Duration spans the EFFECTIVE bounds, so both halves of an edit reach it. */
    @Test
    fun durationUsesBothEffectiveBounds() {
        // Hand-set onset 400, hand-set wake 400 + 2 h; the detected 1_000 -> 5_000 must not be read.
        val row = session(endTs = 5_000, endTsAdjusted = 400 + 7_200, startTsAdjusted = 400)
        assertEquals(400L, row.effectiveStartTs)
        assertEquals(7_600L, row.effectiveEndTs)
        assertEquals("duration must read effective onset -> effective wake", 2.0, row.durationHours, 1e-9)
    }
}

package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Content-marker detection for the cross-fork import: [DataBackup.foreignBackupKind] (the row-copy
 * router), cross-checked against [DataBackup.backupOriginOf] and [DataBackup.holdsData]. All three are
 * pure functions over a backup's table-name set (+ the `dailyMetric` column set), so they are pinned
 * here on the plain JVM with the [CrossForkSchemaFixtures] descriptors — no live SQLite needed.
 *
 * The routing is CONTENT-based, never version-based (GRDB always reports user_version 0, Room forks
 * reuse the same integers), so each of the four field shapes must classify by what tables/columns it
 * actually carries:
 *   - own Android store (Room)     → reconcile NOT needed (fast file-swap restore);
 *   - iOS/GRDB store               → IOS;
 *   - a behind Android fork (Room) → reconcile NOT needed (a version gap is not a divergence);
 *   - an ahead Android fork (Room) → ANDROID_FORK.
 */
class CrossForkImportDetectionTest {

    private val fx = CrossForkSchemaFixtures

    // ── foreignBackupKind: the four field shapes ────────────────────────────────

    @Test fun ownAndroidStoreDoesNotReconcile() {
        // Arrange: this app's own Room store — room_master_table, no upstream-absent marker.
        val shape = fx.ownAndroidStore

        // Act
        val kind = DataBackup.foreignBackupKind(fx.tablesOf(shape), fx.dailyMetricColumnsOf(shape))

        // Assert: null → keeps the fast file-swap restore, no row-copy.
        assertNull(kind)
    }

    @Test fun iosGrdbStoreIsAnIosBackup() {
        // Arrange: the iOS/GRDB store — grdb_migrations bookkeeping.
        val shape = fx.iosGrdbStore

        // Act
        val kind = DataBackup.foreignBackupKind(fx.tablesOf(shape), fx.dailyMetricColumnsOf(shape))

        // Assert
        assertEquals(DataBackup.ForeignBackupKind.IOS, kind)
    }

    @Test fun behindForkDoesNotReconcile() {
        // Arrange: a Room fork a few migrations behind — no ppgWaveformSample, no upstream-absent marker.
        val shape = fx.behindAndroidFork

        // Act
        val kind = DataBackup.foreignBackupKind(fx.tablesOf(shape), fx.dailyMetricColumnsOf(shape))

        // Assert: a version gap is not a content divergence → open-time migrator handles it, no row-copy.
        assertNull(kind)
    }

    @Test fun aheadForkIsAnotherAndroidFork() {
        // Arrange: the ahead Android fork carrying the upstream-absent spo2PctSample table + skinTempAbsC.
        val shape = fx.aheadAndroidFork

        // Act
        val kind = DataBackup.foreignBackupKind(fx.tablesOf(shape), fx.dailyMetricColumnsOf(shape))

        // Assert
        assertEquals(DataBackup.ForeignBackupKind.ANDROID_FORK, kind)
    }

    // ── foreignBackupKind: the two upstream-absent markers, each on its own ──────

    @Test fun theMarkerTableAloneMarksAnAndroidFork() {
        // Arrange: a Room store carrying spo2PctSample but a dailyMetric WITHOUT skinTempAbsC.
        val tables = setOf("room_master_table", "hrSample", "dailyMetric", "spo2PctSample")
        val dailyMetricColumns = setOf("deviceId", "day", "restingHr")

        // Act / Assert
        assertEquals(
            DataBackup.ForeignBackupKind.ANDROID_FORK,
            DataBackup.foreignBackupKind(tables, dailyMetricColumns),
        )
    }

    @Test fun theMarkerColumnAloneMarksAnAndroidFork() {
        // Arrange: a Room store WITHOUT spo2PctSample, but dailyMetric carries the upstream-absent skinTempAbsC.
        val tables = setOf("room_master_table", "hrSample", "dailyMetric")
        val dailyMetricColumns = setOf("deviceId", "day", "skinTempAbsC")

        // Act / Assert
        assertEquals(
            DataBackup.ForeignBackupKind.ANDROID_FORK,
            DataBackup.foreignBackupKind(tables, dailyMetricColumns),
        )
    }

    @Test fun grdbBookkeepingWinsOverAMarkerLookingColumn() {
        // Arrange: a degenerate store carrying BOTH grdb_migrations and an upstream-absent column. GRDB is
        // checked first, so it classifies as the iOS store (never a Room fork).
        val tables = setOf("grdb_migrations", "dailyMetric")
        val dailyMetricColumns = setOf("deviceId", "skinTempAbsC")

        // Act / Assert
        assertEquals(DataBackup.ForeignBackupKind.IOS, DataBackup.foreignBackupKind(tables, dailyMetricColumns))
    }

    @Test fun anEmptyOrUnrecognisedFileDoesNotReconcile() {
        assertNull(DataBackup.foreignBackupKind(emptySet(), emptySet()))
        assertNull(DataBackup.foreignBackupKind(setOf("android_metadata", "sqlite_sequence"), emptySet()))
    }

    // ── Cross-check the origin classifier + data probe agree on each shape ───────

    @Test fun backupOriginMatchesEachShapesBookkeeping() {
        assertEquals(DataBackup.BackupOrigin.ANDROID, DataBackup.backupOriginOf(fx.tablesOf(fx.ownAndroidStore)))
        assertEquals(DataBackup.BackupOrigin.MAC, DataBackup.backupOriginOf(fx.tablesOf(fx.iosGrdbStore)))
        assertEquals(DataBackup.BackupOrigin.ANDROID, DataBackup.backupOriginOf(fx.tablesOf(fx.behindAndroidFork)))
        assertEquals(DataBackup.BackupOrigin.ANDROID, DataBackup.backupOriginOf(fx.tablesOf(fx.aheadAndroidFork)))
    }

    @Test fun everyPopulatedShapeHoldsData() {
        // holdsData sees past the housekeeping tables to the real content each shape carries.
        assertTrue(DataBackup.holdsData(fx.tablesOf(fx.ownAndroidStore)))
        assertTrue(DataBackup.holdsData(fx.tablesOf(fx.iosGrdbStore)))
        assertTrue(DataBackup.holdsData(fx.tablesOf(fx.behindAndroidFork)))
        assertTrue(DataBackup.holdsData(fx.tablesOf(fx.aheadAndroidFork)))
        // Housekeeping-only carries no user content.
        assertFalse(DataBackup.holdsData(setOf("android_metadata", "sqlite_sequence", "room_master_table")))
    }
}

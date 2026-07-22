package com.noop.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device verification of the content-based cross-platform importer ([DataBackup.reconcileForeignBackup]).
 * Runs against the real Android SQLite + Room stack (no Robolectric in the tree). Builds a synthetic GRDB
 * (iOS-style) backup whose DDL differs physically from Room (DOUBLE, double-quotes, SQL DEFAULT) but matches
 * logically, reconciles it into a copy of the live v100 store, then RE-OPENS the result through Room — the
 * real identity check that a raw file-swap would fail.
 */
@RunWith(AndroidJUnit4::class)
class CrossPlatformImportTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshLiveStore(): File {
        val db = WhoopDatabase.get(ctx) // creates/opens the empty v100 Room store (correct identity)
        db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        WhoopDatabase.close()
        return ctx.getDatabasePath(WhoopDatabase.DB_NAME)
    }

    private fun foreignGrdbBackup(): File {
        val f = File(ctx.cacheDir, "foreign-test.sqlite")
        f.delete()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE grdb_migrations (identifier TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("INSERT INTO grdb_migrations VALUES ('v22')")
            // GRDB-style DDL: DOUBLE, double-quotes, a SQL DEFAULT — physically != Room, logically identical.
            db.execSQL(
                """CREATE TABLE "hrSample" ("deviceId" TEXT NOT NULL, "ts" INTEGER NOT NULL,
                   "bpm" INTEGER NOT NULL, "synced" INTEGER NOT NULL DEFAULT 0,
                   PRIMARY KEY("deviceId","ts"))""",
            )
            db.execSQL("INSERT INTO hrSample VALUES ('ios-dev',1000,60,0),('ios-dev',1001,61,0),('ios-dev',1002,62,0)")
            db.execSQL("""CREATE TABLE "ouraRaw" ("id" TEXT PRIMARY KEY, "json" TEXT)""") // iOS-only -> dropped + warned
            db.execSQL("INSERT INTO ouraRaw VALUES ('x','{}')")
        }
        return f
    }

    @Test
    fun foreignBackupReconcilesAndRoomOpensTheResult() {
        val live = freshLiveStore()
        val foreign = foreignGrdbBackup()

        val (reconciled, warnings) = DataBackup.reconcileForeignBackup(ctx, live, foreign, DataBackup.ImportMode.REPLACE)

        // The reconciled file carries Room's identity (copied from live) + the backup's logical rows.
        SQLiteDatabase.openDatabase(reconciled.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM hrSample", null).use { c -> c.moveToFirst(); assertEquals(3, c.getInt(0)) }
            db.rawQuery("SELECT count(*) FROM sqlite_master WHERE name='room_master_table'", null)
                .use { c -> c.moveToFirst(); assertTrue("Room identity table preserved", c.getInt(0) > 0) }
        }
        assertTrue("dropping ouraRaw should warn", warnings.any { it.contains("ouraRaw") })

        // Swap it in and let ROOM open it at v100 — the identity check a raw file-swap would fail. If the
        // reconcile broke the schema/identity, get() throws here; then the copied rows must be queryable.
        WhoopDatabase.close()
        reconciled.copyTo(live, overwrite = true)
        File(live.path + "-wal").delete()
        File(live.path + "-shm").delete()
        val room = WhoopDatabase.get(ctx)
        room.query("SELECT count(*) FROM hrSample WHERE deviceId='ios-dev'", null).use { c ->
            c.moveToFirst()
            assertEquals("Room reads the cross-platform-imported rows", 3, c.getInt(0))
        }

        // Leave the store clean for the app.
        WhoopDatabase.close()
        listOf(live, File(live.path + "-wal"), File(live.path + "-shm"), foreign, reconciled).forEach { it.delete() }
    }
}

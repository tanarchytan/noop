package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * Guards the additive v20 -> v21 Room migration (the `spo2PctSample` table — the WHOOP 5.0/MG sleep SpO2
 * percent whoop-rs decodes, v18 @frame-82, previously discarded), plus the repository insert plumbing.
 *
 * This environment has no Robolectric Room, so the migration SQL is exposed as an internal constant
 * ([WhoopDatabase.SPO2_PCT_SAMPLE_MIGRATION_SQL]) and pinned here to Room's generated shape for
 * [Spo2PctSample]; the store-write path is exercised through a Proxy [WhoopDao] (no DB), like the other
 * per-signal migration tests.
 */
class Spo2PctSampleMigrationTest {

    @Test
    fun migration_isAdditive_onlyCreateTable() {
        val sql = WhoopDatabase.SPO2_PCT_SAMPLE_MIGRATION_SQL
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
        // deviceId TEXT, ts INTEGER, pct INTEGER — column order == entity field order, PRIMARY KEY(deviceId, ts).
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS `spo2PctSample` (`deviceId` TEXT NOT NULL, " +
                    "`ts` INTEGER NOT NULL, `pct` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `ts`))",
            ),
            WhoopDatabase.SPO2_PCT_SAMPLE_MIGRATION_SQL,
        )
    }

    @Test
    fun migration_versionPair_is20to21() {
        assertEquals(20, WhoopDatabase.MIGRATION_20_21.startVersion)
        assertEquals(21, WhoopDatabase.MIGRATION_20_21.endVersion)
    }

    @Test
    fun repositoryInsertRoutesPctRowsToDao() = runBlocking {
        var captured: List<Spo2PctSample>? = null
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "insertSpo2Pct" -> {
                    @Suppress("UNCHECKED_CAST")
                    captured = args[0] as List<Spo2PctSample>
                    listOf(1L)
                }
                else -> throw UnsupportedOperationException("spo2Pct-only insert must not call ${method.name}")
            }
        } as WhoopDao

        WhoopRepository(dao).insert(
            StreamBatch(spo2Pct = listOf(Spo2PctRow(ts = 1_784_000_000L, pct = 98))),
            deviceId = "my-whoop",
        )

        val rows = captured ?: error("insertSpo2Pct was never called")
        assertEquals(1, rows.size)
        assertEquals("my-whoop", rows[0].deviceId)
        assertEquals(1_784_000_000L, rows[0].ts)
        assertEquals(98, rows[0].pct)
    }
}

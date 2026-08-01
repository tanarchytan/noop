package com.noop.data

import com.noop.ingest.HealthConnectImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Guards the Health Connect daily routing: the source it writes under, the read bucket that ranks it,
 * and the provenance predicate the one-shot repair moves on.
 *
 * This environment has no Robolectric / Room-testing / JDBC-SQLite (see the migration tests in this
 * package), so the repair SQL is pinned as the constant the DAO actually compiles, and the column
 * coverage is derived from the entity by reflection rather than restated.
 */
class HealthConnectDailyRoutingTest {

    /** The `dailyMetric` columns Health Connect writes. Everything else is another writer's. */
    private val hcWriteColumns = setOf(
        "totalSleepMin", "restingHr", "avgHrv", "spo2Pct", "respRateBpm", "exerciseCount",
    )

    /** Every metric column on the entity, off its backing fields (kotlin-reflect is not on the test
     *  classpath) — the natural key is not a metric. */
    private fun metricColumns(): List<String> =
        DailyMetric::class.java.declaredFields
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .filter { it != "deviceId" && it != "day" }

    // MARK: - Routing

    @Test
    fun importerWritesUnderTheSourceTheGapFillBucketReads() {
        // The one thing that makes the whole change work: what the importer writes is what the merge reads.
        assertEquals(WhoopRepository.HEALTH_CONNECT_SOURCE, HealthConnectImporter.HC_DEVICE)
        assertTrue(
            "the gap-fill bucket must read Health Connect's own source",
            WhoopRepository.HEALTH_CONNECT_SOURCE in WhoopRepository.GAP_FILL_SOURCE_IDS,
        )
    }

    @Test
    fun theRowTheImporterBuildsCarriesTheGapFillSourceAndOnlyItsOwnColumns() {
        val row = HealthConnectImporter.hcDailyRow(
            day = "2026-07-31",
            restingHr = 61, hrv = 31.3, sleepMin = 512.0,
            spo2 = null, respRate = 13.6, exerciseCount = null,
        )!!
        assertEquals(WhoopRepository.HEALTH_CONNECT_SOURCE, row.deviceId)
        assertEquals(31.3, row.avgHrv!!, 0.0)
        // Every column outside the write set stays null, which is what makes the repair's proof hold.
        val set = mutableSetOf<String>()
        for (f in DailyMetric::class.java.declaredFields) {
            if (f.isSynthetic || Modifier.isStatic(f.modifiers)) continue
            f.isAccessible = true
            if (f.get(row) != null && f.name != "deviceId" && f.name != "day") set += f.name
        }
        assertTrue("wrote a column outside its own set: ${set - hcWriteColumns}", set.all { it in hcWriteColumns })
    }

    @Test
    fun aDayWithNoneOfTheSixValuesWritesNoRow() {
        assertNull(
            HealthConnectImporter.hcDailyRow("2026-07-31", null, null, null, null, null, null),
        )
    }

    @Test
    fun gapFillBucketIsDisjointFromTheStrapBuckets() {
        // A source in two buckets would be merged against itself and its precedence would be undefined.
        val strap = WhoopRepository.importedSourceIdsFor(reAddedRegistry("whoop-AA:BB")) +
            WhoopRepository.computedSourceIdsFor(reAddedRegistry("whoop-AA:BB"))
        assertTrue(WhoopRepository.GAP_FILL_SOURCE_IDS.none { it in strap })
    }

    @Test
    fun healthConnectResolvesLastForAStrapUser() {
        // Compare/Insights read through sourceCandidates, not mergeDaily. Health Connect has to be
        // present there or moving the rows would drop a phone-only day off those surfaces, and it has to
        // be LAST or the resolver would disagree with the dashboard.
        val candidates = WhoopRepository.sourceCandidates(
            key = "hrv",
            preferredSource = WhoopRepository.WHOOP_SOURCE,
            strapIds = WhoopRepository.importedSourceIdsFor(reAddedRegistry("whoop-AA:BB")),
        )
        assertEquals(WhoopRepository.HEALTH_CONNECT_SOURCE, candidates.last().source)
        assertEquals("hrv", candidates.last().key)
        assertTrue(
            "every strap and import source must resolve before the phone",
            candidates.indexOfFirst { it.source == WhoopRepository.HEALTH_CONNECT_SOURCE } ==
                candidates.size - 1,
        )
    }

    // MARK: - The repair's provenance predicate

    @Test
    fun predicateCoversEveryColumnOutsideTheWriteSet() {
        // The proof is "no column another writer owns is set". A column added to the entity and not to
        // the predicate would silently widen what the repair claims to have proven, so it fails here.
        val foreign = metricColumns().filter { it !in hcWriteColumns }
        val missing = foreign.filter { !WhoopDao.HC_DAILY_FOREIGN_COLUMNS_NULL.contains("$it IS NULL") }
        assertEquals("columns missing from the repair's NULL test: $missing", emptyList<String>(), missing)
        assertEquals(
            "one NULL test per foreign column",
            foreign.size,
            Regex("\\bIS NULL\\b").findAll(WhoopDao.HC_DAILY_FOREIGN_COLUMNS_NULL).count(),
        )
    }

    @Test
    fun predicateNamesNoColumnHealthConnectWrites() {
        // The mirror of the check above: a write-set column in the NULL test would make the repair
        // refuse every genuine Health Connect row.
        for (c in hcWriteColumns) {
            assertTrue(
                "$c is written by Health Connect and must not be required null",
                !WhoopDao.HC_DAILY_FOREIGN_COLUMNS_NULL.contains("$c IS NULL"),
            )
        }
    }

    @Test
    fun writeSetTestNamesExactlyTheColumnsHealthConnectWrites() {
        val named = Regex("(\\w+) IS NOT NULL").findAll(WhoopDao.HC_DAILY_ANY_WRITE_COLUMN)
            .map { it.groupValues[1] }.toSet()
        assertEquals(hcWriteColumns, named)
    }

    @Test
    fun theTwoTestsPartitionTheEntity() {
        // No column may be in both, and none may be in neither.
        val foreign = metricColumns().filter { WhoopDao.HC_DAILY_FOREIGN_COLUMNS_NULL.contains("$it IS NULL") }
        assertEquals(metricColumns().size, foreign.size + hcWriteColumns.size)
        assertTrue(foreign.none { it in hcWriteColumns })
    }

    // MARK: - The repair statement itself

    @Test
    fun repairMovesRowsAndNeverDeletesThem() {
        val sql = WhoopDao.MOVE_HC_DAILY_ROWS_SQL
        assertTrue("must be an UPDATE: $sql", sql.startsWith("UPDATE OR IGNORE dailyMetric SET"))
        for (banned in listOf("DELETE", "DROP", "INSERT", "ALTER", "REPLACE")) {
            assertTrue("the repair must not contain '$banned': $sql", !sql.contains(banned))
        }
    }

    @Test
    fun repairMovesOnlyOutOfTheCanonicalBucketIntoHealthConnect() {
        val sql = WhoopDao.MOVE_HC_DAILY_ROWS_SQL
        assertTrue(sql.contains("SET deviceId = '${WhoopRepository.HEALTH_CONNECT_SOURCE}'"))
        assertTrue(sql.contains("WHERE deviceId = '${WhoopRepository.WHOOP_SOURCE}'"))
        // Both halves of the proof are present: nothing foreign set, something of HC's set.
        assertTrue(sql.contains(WhoopDao.HC_DAILY_FOREIGN_COLUMNS_NULL))
        assertTrue(sql.contains(WhoopDao.HC_DAILY_ANY_WRITE_COLUMN))
    }

    @Test
    fun repairIgnoresARowItCannotProve() {
        // The shapes it must leave alone, each expressed as the predicate a row would have to satisfy.
        // A WHOOP CSV night carries efficiency and stages; a strap-adopted "my-whoop" carries recovery,
        // strain or a raw SpO2 channel. Both name a foreign column, so both fail the NULL test.
        for (c in listOf("efficiency", "deepMin", "recovery", "strain", "spo2Red", "steps", "skinTempDevC")) {
            assertTrue(
                "a row carrying $c must be refused",
                WhoopDao.HC_DAILY_FOREIGN_COLUMNS_NULL.contains("$c IS NULL"),
            )
        }
    }
}

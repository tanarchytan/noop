package com.noop.data

import com.noop.protocol.FUTURE_MARGIN
import com.noop.protocol.MIN_PLAUSIBLE_UNIX
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * The bad-clock heal that actually RUNS: [WhoopRepository.healImplausibleTimestamps] and the twelve
 * `prune*` deletes it drives. [HistoryHealPredicateTest] pins a pure Kotlin mirror of the same bound that
 * no production path calls, so inverting the real `DELETE ... WHERE ts < :minTs OR ts > :maxTs` to `AND`
 * (which purges nothing) failed nothing.
 *
 * Two halves, because this source set has no SQLite: the repository half runs for real against a stubbed
 * DAO and pins which queries fire and with what bounds; the SQL half is a TEXT check on the annotation
 * strings KSP compiles into the DAO - it reads the predicate, it does not execute it.
 */
class HistoryHealQueryTest {

    /** The ts-bounded deletes, in the order [WhoopRepository.healImplausibleTimestamps] issues them. */
    private val tsPrunes = listOf(
        "pruneHrByTs", "prunePpgHrByTs", "pruneRrByTs", "pruneSkinTempByTs", "pruneStepByTs",
        "pruneRespByTs", "pruneGravityByTs", "pruneSpo2ByTs", "pruneEventByTs", "pruneBatteryByTs",
    )

    /** The raw stream tables those deletes target, paired with the query text below. */
    private val tsPruneTables = listOf(
        "hrSample", "ppgHrSample", "rrInterval", "skinTempSample", "stepSample",
        "respSample", "gravitySample", "spo2Sample", "event", "battery",
    )

    /** Records every DAO call's arguments (minus the trailing Continuation) and reports one deleted row. */
    private class PruneRecorder {
        val calls = LinkedHashMap<String, List<Any?>>()
        val dao: WhoopDao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            calls[method.name] = (args ?: emptyArray()).dropLast(1)
            1
        } as WhoopDao
    }

    // MARK: - the repository half (the path that runs)

    @Test
    fun healBoundsEveryRawStreamOnTheIngestGateWindow() = runBlocking {
        val r = PruneRecorder()
        val now = 1_780_916_150L
        val deleted = WhoopRepository(r.dao).healImplausibleTimestamps(nowSec = now, today = "2026-06-19")

        // Nothing is bounded on a literal: the floor is the ingest gate's own constant and the ceiling is
        // now plus its margin, so a strap clock and a heal can never disagree about what is plausible.
        for (name in tsPrunes) {
            assertEquals("$name must run", listOf<Any?>(MIN_PLAUSIBLE_UNIX, now + FUTURE_MARGIN), r.calls[name])
        }
        assertEquals(
            "sleep sessions share the ts window",
            listOf<Any?>(MIN_PLAUSIBLE_UNIX, now + FUTURE_MARGIN),
            r.calls["pruneSleepSessionByTs"],
        )
        // One row per query, so the returned figure is a real sum and not a fixed count.
        assertEquals(tsPrunes.size + 2, deleted)
    }

    @Test
    fun healRunsEveryPruneExactlyOnceAndNothingElse() = runBlocking {
        val r = PruneRecorder()
        WhoopRepository(r.dao).healImplausibleTimestamps(nowSec = 1_780_916_150L, today = "2026-06-19")
        assertEquals(tsPrunes + listOf("pruneDailyMetricByDay", "pruneSleepSessionByTs"), r.calls.keys.toList())
    }

    @Test
    fun theDailyFloorDayIsDerivedFromTheTimestampFloor() = runBlocking {
        val r = PruneRecorder()
        // Noon UTC, so the local date is the same in every plausible device zone.
        WhoopRepository(r.dao).healImplausibleTimestamps(
            nowSec = 1_780_916_150L, today = "2026-06-19", minTs = 1_699_963_200L,
        )
        assertEquals(listOf<Any?>("2026-06-19", "2023-11-14"), r.calls["pruneDailyMetricByDay"])
    }

    @Test
    fun aWiderFutureMarginWidensTheCeiling() = runBlocking {
        val r = PruneRecorder()
        val now = 1_780_916_150L
        WhoopRepository(r.dao).healImplausibleTimestamps(
            nowSec = now, today = "2026-06-19", futureMargin = 3 * 86_400L,
        )
        // The ceiling tracks the margin rather than a baked-in day, on every ts query.
        for (name in tsPrunes) assertEquals(now + 3 * 86_400L, (r.calls[name]!![1] as Long))
    }

    // MARK: - the SQL half (text of the annotations KSP compiles)

    private fun daoSource(): String {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val f = listOf(
            File(userDir, "src/main/java/com/noop/data/WhoopDao.kt"),
            File(userDir, "app/src/main/java/com/noop/data/WhoopDao.kt"),
            File(userDir, "android/app/src/main/java/com/noop/data/WhoopDao.kt"),
        ).firstOrNull(File::isFile)
        // Never assume-skip: a moved file must fail this gate, not silently pass it.
        assertTrue("WhoopDao.kt not found from ${userDir.absolutePath}", f != null)
        return f!!.readText()
    }

    @Test
    fun everyRawPruneDeletesOutsideTheWindow_notInside() {
        val src = daoSource()
        for (table in tsPruneTables) {
            val want = "DELETE FROM $table WHERE ts < :minTs OR ts > :maxTs"
            assertTrue("missing or altered prune for $table: expected `$want`", src.contains(want))
        }
        // `OR` is the whole predicate: `AND` between the two bounds can never hold, so the heal would
        // purge nothing while every count-based assertion still passed.
        assertTrue(
            "a ts prune must never AND its two bounds",
            !Regex("""ts < :minTs\s+AND\s+ts > :maxTs""").containsMatchIn(src),
        )
    }

    @Test
    fun theTwoSourceScopedPrunesKeepTheirImportEscape() {
        val src = daoSource()
        // A future day is purged whatever wrote it; the far-past floor is computed-only, so an imported
        // multi-year history survives the heal.
        assertTrue(
            src.contains("DELETE FROM dailyMetric WHERE day > :today OR (day < :minDay AND deviceId LIKE '%-noop')"),
        )
        assertTrue(
            src.contains("DELETE FROM sleepSession WHERE startTs > :maxTs OR (startTs < :minTs AND deviceId LIKE '%-noop')"),
        )
    }
}

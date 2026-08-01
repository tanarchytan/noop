package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The read scope is the union of every non-archived paired device plus the legacy import sink, derived
 * from the registry. Before this, the scope was (active id, "my-whoop") built from ONE id, so a second
 * paired strap's days were excluded by construction and a null active device collapsed the scope onto an
 * archived phantom.
 *
 * Pins the four shapes: two straps, an archived strap, a single strap (byte-identical), and no active row.
 */
class MultiStrapReadScopeTest {

    private val legacy = WhoopRepository.WHOOP_SOURCE
    private val strapA = "whoop-E4:0B:03:2B:C6:21"
    private val strapB = "whoop-D5:B2:02:FA:38:29"

    /** The registry a two-strap install carries: the legacy bucket archived, one strap active. */
    private fun twoStraps(activeIsB: Boolean = true): List<PairedDeviceRow> = listOf(
        deviceRow(legacy, DeviceStatus.archived, addedAt = 0L),
        deviceRow(strapA, if (activeIsB) DeviceStatus.paired else DeviceStatus.active, addedAt = 1L),
        deviceRow(strapB, if (activeIsB) DeviceStatus.active else DeviceStatus.paired, addedAt = 2L),
    )

    private fun day(deviceId: String, day: String, asleep: Double) =
        DailyMetric(deviceId = deviceId, day = day, totalSleepMin = asleep)

    /** A repository whose `days(id)` answers from [rowsByDevice] and whose registry is [devices]. */
    private fun repo(devices: List<PairedDeviceRow>, rowsByDevice: Map<String, List<DailyMetric>>):
        WhoopRepository {
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "pairedDevices" -> devices
                "days" -> rowsByDevice[args?.get(0) as String].orEmpty()
                "editedSleepSessions" -> emptyList<SleepSession>()
                else -> throw UnsupportedOperationException("read scope must not call ${method.name}")
            }
        } as WhoopDao
        return WhoopRepository(dao)
    }

    // --- the id list ---

    /** Both straps are in scope, active FIRST, and the legacy sink closes the list. */
    @Test
    fun bothPairedStrapsAreInScopeActiveFirst() {
        assertEquals(
            listOf(strapB, strapA, legacy),
            WhoopRepository.importedSourceIdsFor(twoStraps()),
        )
        assertEquals(
            listOf("$strapB-noop", "$strapA-noop", "$legacy-noop"),
            WhoopRepository.computedSourceIdsFor(twoStraps()),
        )
    }

    /** An ARCHIVED strap leaves the scope — archiving is how a user retires a band. The legacy sink is
     *  not a device, so its archived status never removes it. */
    @Test
    fun archivedStrapLeavesTheScopeButTheLegacySinkStays() {
        val devices = listOf(
            deviceRow(legacy, DeviceStatus.archived, addedAt = 0L),
            deviceRow(strapA, DeviceStatus.archived, addedAt = 1L),
            deviceRow(strapB, DeviceStatus.active, addedAt = 2L),
        )
        val ids = WhoopRepository.importedSourceIdsFor(devices)
        assertFalse("an archived strap must not be read", strapA in ids)
        assertEquals(listOf(strapB, legacy), ids)
    }

    /** A single-strap install resolves to exactly the two ids it always did, in the same order. */
    @Test
    fun singleStrapScopeIsUnchanged() {
        assertEquals(listOf(legacy), WhoopRepository.importedSourceIdsFor(singleWhoopRegistry()))
        assertEquals(listOf("$legacy-noop"), WhoopRepository.computedSourceIdsFor(singleWhoopRegistry()))
        assertEquals(
            listOf(strapA, legacy),
            WhoopRepository.importedSourceIdsFor(reAddedRegistry(strapA)),
        )
    }

    /** No `active` row: every real strap stays in scope. The old fallback resolved to "my-whoop" and its
     *  single-source shortcut then returned ONLY the archived phantom, hiding every strap. */
    @Test
    fun noActiveRowKeepsTheRealStrapsAndDoesNotFallBackToThePhantomAlone() {
        val devices = listOf(
            deviceRow(legacy, DeviceStatus.archived, addedAt = 0L),
            deviceRow(strapA, DeviceStatus.paired, addedAt = 1L),
            deviceRow(strapB, DeviceStatus.paired, addedAt = 2L),
        )
        val ids = WhoopRepository.importedSourceIdsFor(devices)
        assertEquals(listOf(strapA, strapB, legacy), ids)
        assertTrue("the read must not collapse onto the legacy id alone", ids.size > 1)
    }

    // --- what the dashboard actually reads ---

    /** Two straps owning DISTINCT days: every day is visible. This is the measured defect — 13 days
     *  banked under a second paired strap were excluded from the merged read. */
    @Test
    fun twoStrapsOwningDistinctDaysAreAllVisible() = runBlocking {
        val repo = repo(
            twoStraps(),
            mapOf(
                "$strapA-noop" to listOf(day("$strapA-noop", "2026-07-16", 420.0)),
                "$strapB-noop" to listOf(day("$strapB-noop", "2026-07-30", 400.0)),
                legacy to listOf(day(legacy, "2026-06-24", 380.0)),
            ),
        )
        assertEquals(
            listOf("2026-06-24", "2026-07-16", "2026-07-30"),
            repo.daysMerged().map { it.day },
        )
    }

    /** An archived strap's days stay hidden through the merged read, not just through the id list. */
    @Test
    fun anArchivedStrapsDaysStayHidden() = runBlocking {
        val devices = listOf(
            deviceRow(legacy, DeviceStatus.archived, addedAt = 0L),
            deviceRow(strapA, DeviceStatus.archived, addedAt = 1L),
            deviceRow(strapB, DeviceStatus.active, addedAt = 2L),
        )
        val repo = repo(
            devices,
            mapOf(
                "$strapA-noop" to listOf(day("$strapA-noop", "2026-07-16", 420.0)),
                "$strapB-noop" to listOf(day("$strapB-noop", "2026-07-30", 400.0)),
            ),
        )
        assertEquals(listOf("2026-07-30"), repo.daysMerged().map { it.day })
    }

    /** With no active row the real straps' days still render. */
    @Test
    fun noActiveRowStillRendersTheRealStrapsDays() = runBlocking {
        val devices = listOf(
            deviceRow(legacy, DeviceStatus.archived, addedAt = 0L),
            deviceRow(strapA, DeviceStatus.paired, addedAt = 1L),
        )
        val repo = repo(
            devices,
            mapOf("$strapA-noop" to listOf(day("$strapA-noop", "2026-07-16", 420.0))),
        )
        assertEquals(listOf("2026-07-16"), repo.daysMerged().map { it.day })
    }

    /** A day BOTH straps hold resolves to ONE row — the active strap's, verbatim. The values are never
     *  averaged, and the day is never listed twice. */
    @Test
    fun aDayBothStrapsHoldResolvesToTheActiveStrapsRowVerbatim() = runBlocking {
        val repo = repo(
            twoStraps(),
            mapOf(
                "$strapA-noop" to listOf(day("$strapA-noop", "2026-07-20", 420.0)),
                "$strapB-noop" to listOf(day("$strapB-noop", "2026-07-20", 466.0)),
            ),
        )
        val merged = repo.daysMerged()
        assertEquals("the day is listed once", 1, merged.size)
        assertEquals("the active strap's value, verbatim", 466.0, merged[0].totalSleepMin)
    }
}

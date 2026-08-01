package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The read scope is the union of every INCLUDED registry row plus the legacy import sink. Presence
 * (`status`) is a BLE fact and never narrows it, so a removed strap keeps its recorded days; only the
 * inclusion axis ([PairedDeviceRow.dataIncluded]) subtracts.
 *
 * Pins the shapes: two straps, a removed strap, an excluded strap, a single strap, and no active row.
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

    /** A REMOVED (archived) strap stays in the scope: removing a band retires a radio, not a month of
     *  history. This is the measured fault — 19 recorded nights went invisible on an archive. */
    @Test
    fun aRemovedStrapStaysInTheScope() {
        val devices = listOf(
            deviceRow(legacy, DeviceStatus.archived, addedAt = 0L),
            deviceRow(strapA, DeviceStatus.archived, addedAt = 1L),
            deviceRow(strapB, DeviceStatus.active, addedAt = 2L),
        )
        val ids = WhoopRepository.importedSourceIdsFor(devices)
        assertTrue("a removed strap must still be read", strapA in ids)
        assertEquals("straps in registration order, the import sink last", listOf(strapB, strapA, legacy), ids)
    }

    /** Only the INCLUSION axis subtracts. Excluding one strap drops exactly it; the removed one beside
     *  it, still included, keeps rendering. */
    @Test
    fun anExcludedStrapLeavesTheScopeAndNothingElseDoes() {
        val devices = listOf(
            deviceRow(legacy, DeviceStatus.archived, addedAt = 0L),
            deviceRow(strapA, DeviceStatus.archived, addedAt = 1L, included = false),
            deviceRow(strapB, DeviceStatus.active, addedAt = 2L),
        )
        val ids = WhoopRepository.importedSourceIdsFor(devices)
        assertFalse("an excluded dataset must not be read", strapA in ids)
        assertEquals(listOf(strapB, legacy), ids)
    }

    /** The import sink is read unless its OWN row is excluded — it is a data bucket, not a device, so
     *  no BLE state ever removes it. */
    @Test
    fun theImportSinkFollowsItsOwnInclusionFlagOnly() {
        assertTrue(legacy in WhoopRepository.importedSourceIdsFor(twoStraps()))
        val excluded = listOf(
            deviceRow(legacy, DeviceStatus.archived, addedAt = 0L, included = false),
            deviceRow(strapB, DeviceStatus.active, addedAt = 2L),
        )
        assertEquals(listOf(strapB), WhoopRepository.importedSourceIdsFor(excluded))
    }

    /** The other axis, unchanged: a removed strap is unreachable over BLE, so the picker and
     *  auto-connect never offer it — while its data stays visible above. */
    @Test
    fun theBleSurfaceStillExcludesARemovedStrap() {
        val devices = listOf(
            deviceRow(strapA, DeviceStatus.archived, addedAt = 1L),
            deviceRow(strapB, DeviceStatus.active, addedAt = 2L),
            deviceRow(legacy, DeviceStatus.paired, addedAt = 0L, kind = SourceKind.legacy),
        )
        assertEquals(listOf(strapB), connectableDevices(devices).map { it.id })
        assertTrue("an EXCLUDED strap is still connectable — inclusion is not presence",
            strapB in connectableDevices(
                devices.map { if (it.id == strapB) it.copy(dataIncluded = false) else it },
            ).map { it.id },
        )
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

    /** A REMOVED strap's days stay visible through the merged read, not just in the id list — the
     *  regression for the archive fault. */
    @Test
    fun aRemovedStrapsDaysStayVisible() = runBlocking {
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
        assertEquals(listOf("2026-07-16", "2026-07-30"), repo.daysMerged().map { it.day })
    }

    /** Excluding that same strap is what hides it, and nothing else does. */
    @Test
    fun anExcludedStrapsDaysAreHidden() = runBlocking {
        val devices = listOf(
            deviceRow(legacy, DeviceStatus.archived, addedAt = 0L),
            deviceRow(strapA, DeviceStatus.archived, addedAt = 1L, included = false),
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

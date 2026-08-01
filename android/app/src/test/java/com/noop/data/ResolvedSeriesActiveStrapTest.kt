package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * resolvedSeries() resolves its strap candidates from the REGISTRY, so a score banked under a
 * re-added strap's computed sibling is found with no id threaded by the caller. There is no
 * strapDeviceId argument left to pass wrongly, which is what used to leave the Today Rest ring on
 * "Calibrating" while the Sleep tab showed fully scored nights.
 *
 * Complements [ResolverUnionTest] (the candidate LIST shape). Driven through a Proxy-stub
 * [WhoopDao] (no Room): the resolver touches pairedDevices + metricSeries + dailyMetricsRange,
 * answered from fixtures; everything else throws.
 */
class ResolvedSeriesActiveStrapTest {

    private val canonical = "my-whoop"
    private val reAdded = "whoop-ABC123" // the id a re-added strap gets (whoop-<uuid>)

    // The wide-open window every Today/Trends caller passes.
    private val from = "0000-00-00"
    private val to = "9999-99-99"

    /** Build a repository whose metricSeries answers from [rows] (filtered like the real query:
     *  by deviceId + key + day window, ascending) and whose dailyMetricsRange is empty. Every
     *  other dao call throws, proof the resolver reached past its contract. */
    private fun repo(
        rows: List<MetricSeriesRow>,
        devices: List<PairedDeviceRow> = singleWhoopRegistry(),
    ): WhoopRepository {
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "metricSeries" -> {
                    val a = args!!
                    val deviceId = a[0] as String
                    val key = a[1] as String
                    val lo = a[2] as String
                    val hi = a[3] as String
                    rows.filter { it.deviceId == deviceId && it.key == key && it.day >= lo && it.day <= hi }
                        .sortedBy { it.day }
                }
                "dailyMetricsRange" -> emptyList<DailyMetric>()
                "pairedDevices" -> devices
                else -> throw UnsupportedOperationException("resolver must not call ${method.name}")
            }
        } as WhoopDao
        return WhoopRepository(dao)
    }

    private fun row(source: String, day: String, value: Double) =
        MetricSeriesRow(deviceId = source, day = day, key = "sleep_performance", value = value)

    // --- the #175 fix: threading the active id reaches the re-added strap's banked scores ---

    @Test
    fun reAddedStrap_resolvesScoresBankedUnderItsComputedSibling() = runBlocking {
        val repo = repo(
            listOf(
                row("$reAdded-noop", "2026-07-08", 91.0),
                row("$reAdded-noop", "2026-07-09", 84.0),
            ),
            devices = reAddedRegistry(reAdded),
        )

        val resolved = repo.resolvedSeries("sleep_performance", canonical, from, to)

        assertEquals(
            listOf("2026-07-08" to 91.0, "2026-07-09" to 84.0),
            resolved.values,
        )
        assertEquals(listOf("$reAdded-noop"), resolved.usedSources)
    }

    /** A strap the registry does not carry is out of scope: its scores stay invisible, so widening the
     *  scope to the registry never widened it to every id in the table. */
    @Test
    fun aStrapAbsentFromTheRegistryStaysOutOfScope() = runBlocking {
        val repo = repo(
            listOf(
                row("$reAdded-noop", "2026-07-08", 91.0),
                row("$reAdded-noop", "2026-07-09", 84.0),
            ),
            devices = singleWhoopRegistry(),
        )

        val resolved = repo.resolvedSeries("sleep_performance", canonical, from, to)

        assertTrue(
            "an unregistered id must not be read (got ${resolved.values})",
            resolved.values.isEmpty(),
        )
    }

    // --- the single-strap install resolves exactly as it always did ---

    @Test
    fun singleStrapInstallResolvesTheCanonicalPairOnly() = runBlocking {
        val repo = repo(
            listOf(
                row("$canonical-noop", "2026-07-08", 77.0),
                row("$canonical-noop", "2026-07-09", 82.0),
            ),
        )

        val resolved = repo.resolvedSeries("sleep_performance", canonical, from, to)

        assertEquals(
            listOf(canonical, "$canonical-noop", "health-connect"),
            resolved.candidates.map { it.source },
        )
        assertEquals(listOf("2026-07-08" to 77.0, "2026-07-09" to 82.0), resolved.values)
    }

    // --- the union, exercised through the registry-derived read ---

    @Test
    fun activeWinsItsDay_canonicalFillsHistoryBankedBeforeReAdd() = runBlocking {
        val repo = repo(
            listOf(
                // History banked under the canonical pair BEFORE the re-add…
                row("$canonical-noop", "2026-07-01", 70.0),
                row("$canonical-noop", "2026-07-08", 80.0),
                // …and the re-added strap's own scored night for the 8th.
                row("$reAdded-noop", "2026-07-08", 91.0),
            ),
            devices = reAddedRegistry(reAdded),
        )

        val resolved = repo.resolvedSeries("sleep_performance", canonical, from, to)

        // Active pair wins the day it covers; canonical fills the pre-re-add day. Nothing dropped.
        assertEquals(
            listOf("2026-07-01" to 70.0, "2026-07-08" to 91.0),
            resolved.values,
        )
        assertEquals(listOf("$canonical-noop", "$reAdded-noop"), resolved.usedSources.sorted())
    }
}

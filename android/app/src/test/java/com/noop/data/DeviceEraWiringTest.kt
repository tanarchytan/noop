package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * [WhoopRepository.deviceEraEpoch] is what actually reaches a baseline fold: before this the
 * era-boundary function existed and NOTHING called it, so a strap swap re-seeded nothing. This pins
 * the wiring — the per-day winner resolution, the registry family lookup, and the max against the
 * user's manual recalibration epoch.
 */
class DeviceEraWiringTest {

    private fun strap(id: String, model: String, status: DeviceStatus, addedAt: Long) =
        deviceRow(id, status, addedAt).copy(model = model)

    private fun repo(devices: List<PairedDeviceRow>, rowsByDevice: Map<String, List<String>>):
        WhoopRepository {
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "pairedDevices" -> devices
                "days" -> rowsByDevice[args?.get(0) as String].orEmpty()
                    .map { DailyMetric(deviceId = args[0] as String, day = it) }
                else -> throw UnsupportedOperationException("the era read must not call ${method.name}")
            }
        } as WhoopDao
        return WhoopRepository(dao)
    }

    private fun epochOfDayUTC(day: String): Double =
        java.time.LocalDate.parse(day).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond().toDouble()

    /** David's real registry shape: two 5-series straps and the import sink. No era opens, so his
     *  baseline is untouched by this change. */
    @Test
    fun twoFiveSeriesStrapsPlusTheImportSinkOpenNoEra() = runBlocking {
        val devices = listOf(
            strap(WhoopRepository.WHOOP_SOURCE, "WHOOP", DeviceStatus.archived, 0L),
            strap("whoop-E4:0B:03:2B:C6:21", "5.0 MG", DeviceStatus.archived, 1L),
            strap("whoop-D5:B2:02:FA:38:29", "5.0 MG", DeviceStatus.active, 2L),
        )
        val repo = repo(
            devices,
            mapOf(
                WhoopRepository.WHOOP_SOURCE to (2..14).map { "2026-07-%02d".format(it) },
                "whoop-E4:0B:03:2B:C6:21-noop" to (15..28).map { "2026-07-%02d".format(it) },
                "whoop-D5:B2:02:FA:38:29-noop" to (29..31).map { "2026-07-%02d".format(it) },
            ),
        )
        assertEquals(0.0, repo.deviceEraEpoch(), 0.0)
    }

    /** A 4.0 handing over to a 5-series strap re-seeds from the first 5-series day. */
    @Test
    fun aFamilySwapOpensTheEraAtTheFirstDayOfTheNewFamily() = runBlocking {
        val devices = listOf(
            strap("whoop-old4", "4.0", DeviceStatus.archived, 1L),
            strap("whoop-new5", "5.0 / MG", DeviceStatus.active, 2L),
        )
        val repo = repo(
            devices,
            mapOf(
                "whoop-old4-noop" to (1..10).map { "2026-07-%02d".format(it) },
                "whoop-new5-noop" to (11..20).map { "2026-07-%02d".format(it) },
            ),
        )
        assertEquals(epochOfDayUTC("2026-07-11"), repo.deviceEraEpoch(), 0.0)
    }

    /** An excluded dataset is outside the read scope, so it cannot open an era for days nobody sees. */
    @Test
    fun anExcludedDatasetOpensNoEra() = runBlocking {
        val devices = listOf(
            strap("whoop-old4", "4.0", DeviceStatus.archived, 1L).copy(dataIncluded = false),
            strap("whoop-new5", "5.0 / MG", DeviceStatus.active, 2L),
        )
        val repo = repo(
            devices,
            mapOf(
                "whoop-old4-noop" to (1..10).map { "2026-07-%02d".format(it) },
                "whoop-new5-noop" to (11..20).map { "2026-07-%02d".format(it) },
            ),
        )
        assertEquals(0.0, repo.deviceEraEpoch(), 0.0)
    }

    /** The manual "Recalibrate baseline" anchor still wins when it is later than the era boundary. */
    @Test
    fun theEffectiveEpochIsTheLaterOfTheManualAnchorAndTheEra() = runBlocking {
        val devices = listOf(
            strap("whoop-old4", "4.0", DeviceStatus.archived, 1L),
            strap("whoop-new5", "5.0 / MG", DeviceStatus.active, 2L),
        )
        val repo = repo(
            devices,
            mapOf(
                "whoop-old4-noop" to (1..10).map { "2026-07-%02d".format(it) },
                "whoop-new5-noop" to (11..20).map { "2026-07-%02d".format(it) },
            ),
        )
        val era = epochOfDayUTC("2026-07-11")
        assertEquals(era, repo.effectiveBaselineEpoch(0.0), 0.0)
        assertEquals(era, repo.effectiveBaselineEpoch(era - 86_400.0), 0.0)
        assertEquals("a later manual recalibration still wins", era + 86_400.0,
            repo.effectiveBaselineEpoch(era + 86_400.0), 0.0)
    }

    /** A single-family install keeps the manual anchor exactly, so nothing changes for the common case. */
    @Test
    fun aSingleFamilyInstallKeepsTheManualAnchorUnchanged() = runBlocking {
        val devices = listOf(strap("whoop-new5", "5.0 / MG", DeviceStatus.active, 2L))
        val repo = repo(devices, mapOf("whoop-new5-noop" to (1..20).map { "2026-07-%02d".format(it) }))
        assertEquals(0.0, repo.effectiveBaselineEpoch(0.0), 0.0)
        assertEquals(1_700_000_000.0, repo.effectiveBaselineEpoch(1_700_000_000.0), 0.0)
    }
}

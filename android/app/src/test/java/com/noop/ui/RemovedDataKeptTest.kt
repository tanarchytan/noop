package com.noop.ui

import com.noop.data.DailyMetric
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SleepSession
import com.noop.data.SourceKind
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import com.noop.data.connectableDevices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The removed card promises "Removed · data kept" and the read scope used to drop every row that strap
 * recorded. This pins the storage behaviour to the promise the user actually reads, so a change to
 * either side breaks here rather than silently costing a month of history.
 */
class RemovedDataKeptTest {

    private val strap = "whoop-E4:0B:03:2B:C6:21"
    private val other = "whoop-D5:B2:02:FA:38:29"

    private fun row(id: String, status: DeviceStatus, addedAt: Long, included: Boolean = true) =
        PairedDeviceRow(
            id = id, brand = "WHOOP", model = "5.0 / MG", nickname = null,
            peripheralId = null, sourceKind = SourceKind.liveBLE.name, capabilities = "hr,hrv,sleep",
            status = status.name, addedAt = addedAt, lastSeenAt = addedAt, dataIncluded = included,
        )

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

    /** The exact strings the removed card shows. Named here so a copy change has to face this test. */
    @Test
    fun theRemovedCardPromisesTheDataIsKept() {
        val removed = row(strap, DeviceStatus.archived, addedAt = 1L)
        assertEquals("Removed · data kept", lastSeenLine(removed, isLiveConnected = false))
        assertEquals(
            "Removed",
            devicePillState(
                isArchived = true, isActive = false, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).label,
        )
    }

    /** And the promise holds: archiving a strap that owns N days leaves all N visible. */
    @Test
    fun archivingADeviceKeepsEveryDayItOwnsVisible() = runBlocking {
        val days = listOf("2026-07-16", "2026-07-17", "2026-07-18", "2026-07-19")
        val devices = listOf(
            row(strap, DeviceStatus.archived, addedAt = 1L),
            row(other, DeviceStatus.active, addedAt = 2L),
        )
        val repo = repo(
            devices,
            mapOf("$strap-noop" to days.map { DailyMetric(deviceId = "$strap-noop", day = it, avgHrv = 60.0) }),
        )
        assertEquals(days, repo.daysMerged().map { it.day })
    }

    /** Removing still removes, as a device action: the strap leaves the picker and auto-connect. */
    @Test
    fun theRemovedStrapStillLeavesTheDevicePicker() {
        val devices = listOf(
            row(strap, DeviceStatus.archived, addedAt = 1L),
            row(other, DeviceStatus.active, addedAt = 2L),
        )
        assertEquals(listOf(other), connectableDevices(devices).map { it.id })
    }

    /** Hiding a strap's DATA is still possible — it is just a separate choice now. */
    @Test
    fun excludingTheDatasetIsWhatHidesIt() = runBlocking {
        val devices = listOf(
            row(strap, DeviceStatus.archived, addedAt = 1L, included = false),
            row(other, DeviceStatus.active, addedAt = 2L),
        )
        val repo = repo(
            devices,
            mapOf("$strap-noop" to listOf(DailyMetric(deviceId = "$strap-noop", day = "2026-07-16"))),
        )
        assertTrue(repo.daysMerged().isEmpty())
        assertTrue("and hiding data never removes the strap's row", devices.any { it.id == strap })
    }
}

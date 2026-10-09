package com.noop.analytics

import com.noop.data.DeviceStatus
import com.noop.data.HrSample
import com.noop.data.HrWindowStats
import com.noop.data.WhoopDao
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import com.noop.data.deviceRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** A manual workout is saved under "my-whoop" while its HR sits under the strap's registry id. */
class ManualWorkoutScopeTest {

    private val strap = "whoop-AA:BB"
    private val manual = WorkoutRow(
        deviceId = "my-whoop", startTs = 1_000L, endTs = 2_200L, sport = "Run", source = "manual",
        energyKcal = 1.0,
    )
    private val hr = (0 until 1200).map { HrSample(strap, 1_000L + it, 140) }
    private val upserted = ArrayList<WorkoutRow>()

    private fun repo(): WhoopRepository {
        val devices = listOf(
            deviceRow("my-whoop", DeviceStatus.archived),
            deviceRow(strap, DeviceStatus.active, addedAt = 1L),
        )
        val dao = Proxy.newProxyInstance(WhoopDao::class.java.classLoader, arrayOf(WhoopDao::class.java)) { _, m, a ->
            when (m.name) {
                "pairedDevices" -> devices
                "workouts" -> if (a!![0] == "my-whoop") listOf(manual) else emptyList<WorkoutRow>()
                "hrSamples" -> if (a!![0] == strap) hr else emptyList<HrSample>()
                "hrWindowStats" ->
                    if (a!![0] == strap) HrWindowStats(hr.size.toLong(), 140.0, 140) else HrWindowStats(0L, null, null)
                "upsertWorkouts" -> { @Suppress("UNCHECKED_CAST") upserted.addAll(a!![0] as List<WorkoutRow>); Unit }
                else -> throw UnsupportedOperationException(m.name)
            }
        } as WhoopDao
        return WhoopRepository(dao)
    }

    @Test fun displayFillReadsTheStrapIdForAMyWhoopRow() = runBlocking {
        val out = repo().fillWorkoutHrFromStrap(listOf(manual)).single()
        assertEquals(140, out.avgHr)
        assertEquals(140, out.maxHr)
    }

    @Test fun rescoreWritesHrAndKcalFromTheStrapId() = runBlocking {
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 30.0, sex = "male")
        IntelligenceEngine.rescoreManualWorkouts(repo(), profile, strap, 190.0, 5_000L)
        val row = upserted.single()
        assertEquals("my-whoop", row.deviceId)
        assertEquals(140, row.avgHr)
        assertNotNull(row.energyKcal)
        assertTrue((row.energyKcal ?: 0.0) > 50.0)
    }
}

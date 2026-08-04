package com.noop.data

import com.noop.analytics.SleepStageTotals
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * A sleep row's `efficiency` must describe the window the row claims. [WhoopRepository] used to reclip
 * `stagesJSON` on an edit and copy every other field, so the stored efficiency kept describing the
 * PRE-EDIT stages: on a real 1.77 GB store 11 of 11 user-edited nights disagreed with their daily row
 * and 0 of 19 untouched nights did. The value itself is computed in whoop-rs; these pin that every write
 * path asks for it over the bounds it is persisting, and that the row stays self-consistent.
 */
class SleepEditEfficiencyTest {

    private val bed = 1_770_000_000L
    private val wake = bed + 3_600L

    /** A staged night that tiles `[bed, wake]`: 50 min light, then 10 min wake. */
    private fun tiledNight(efficiency: Double?) = SleepSession(
        deviceId = "my-whoop-noop",
        startTs = bed,
        endTs = wake,
        efficiency = efficiency,
        stagesJSON = """[{"end":${bed + 3_000},"stage":"light","start":$bed},""" +
            """{"end":$wake,"stage":"wake","start":${bed + 3_000}}]""",
    )

    /** The efficiency a row's OWN stages give over its OWN effective window — what it must agree with. */
    private fun selfConsistent(row: SleepSession): Double? =
        SleepStageTotals.efficiencyForWindow(row.effectiveStartTs, row.effectiveEndTs, row.stagesJSON, null)

    private fun edit(session: SleepSession, newStart: Long, newEnd: Long, wakeSetByUser: Boolean): SleepSession {
        val written = ArrayList<SleepSession>()
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "upsertSleepSessions" -> {
                    @Suppress("UNCHECKED_CAST")
                    written.addAll(args[0] as List<SleepSession>)
                    Unit
                }
                else -> throw UnsupportedOperationException("a time edit must not call ${method.name}")
            }
        } as WhoopDao
        runBlocking { WhoopRepository(dao).updateSleepSessionTimes(session, newStart, newEnd, wakeSetByUser) }
        assertEquals("the edit writes exactly one row", 1, written.size)
        return written[0]
    }

    // ── The 11-of-11 regression: an edit re-derives the row's own efficiency ─────────────────────────

    @Test
    fun trimmingTheWakeOffLeavesEfficiencyConsistentWithTheReclippedStages() {
        // Drop the 10 awake minutes off the end: what remains is 50 min of light over a 50 min window.
        val row = edit(tiledNight(efficiency = 3_000.0 / 3_600.0), bed, bed + 3_000, wakeSetByUser = true)
        assertEquals(1.0, row.efficiency!!, 1e-12)
        assertEquals("the row must agree with its own stages", selfConsistent(row)!!, row.efficiency!!, 1e-12)
    }

    @Test
    fun movingTheBedLaterLeavesEfficiencyConsistentWithTheReclippedStages() {
        // Bed 10 min later: 40 min light + 10 min wake over a 50 min window.
        val row = edit(tiledNight(efficiency = 3_000.0 / 3_600.0), bed + 600, wake, wakeSetByUser = false)
        assertEquals(2_400.0 / 3_000.0, row.efficiency!!, 1e-12)
        assertEquals(selfConsistent(row)!!, row.efficiency!!, 1e-12)
    }

    @Test
    fun theStoredValueActuallyMoves() {
        // The defect's signature: the pre-edit number survived the edit. It must not.
        val stale = 3_000.0 / 3_600.0
        val row = edit(tiledNight(efficiency = stale), bed, bed + 3_000, wakeSetByUser = true)
        assertTrue("efficiency must follow the corrected window", Math.abs(row.efficiency!! - stale) > 1e-6)
    }

    @Test
    fun anEditThatChangesNothingKeepsTheSameNumber() {
        // Re-saving the same bounds is not a change: the recompute is idempotent, not destructive.
        val row = edit(tiledNight(efficiency = null), bed, wake, wakeSetByUser = true)
        assertEquals(3_000.0 / 3_600.0, row.efficiency!!, 1e-12)
    }

    @Test
    fun stagesWithNoTimelineKeepTheStoredEfficiency() {
        // No stages at all: nothing to re-derive from, so an imported number is preserved, not nulled.
        val imported = SleepSession(deviceId = "my-whoop", startTs = bed, endTs = wake, efficiency = 0.91)
        assertEquals(0.91, edit(imported, bed, bed + 3_000, wakeSetByUser = true).efficiency!!, 1e-12)
    }

    @Test
    fun aFullyAwakeCorrectedWindowStoresNoEfficiencyRatherThanAStaleOne() {
        // Trimmed to the awake tail: nothing is asleep, so "not staged" is stored, never the old 0.83.
        val row = edit(tiledNight(efficiency = 3_000.0 / 3_600.0), bed + 3_000, wake, wakeSetByUser = true)
        assertNull(row.efficiency)
    }

    // ── The nap path seeds the same number from the same owner ───────────────────────────────────────

    private fun nap(gravity: List<GravitySample>): SleepSession {
        val written = ArrayList<SleepSession>()
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "gravitySamples" -> gravity
                "hrSamples", "rrIntervals", "stepSamples" -> emptyList<Any>()
                "insertSleepSession" -> {
                    written.add(args[0] as SleepSession)
                    1L
                }
                else -> throw UnsupportedOperationException("adding a nap must not call ${method.name}")
            }
        } as WhoopDao
        runBlocking { WhoopRepository(dao).addManualNap("whoop-AA:BB", bed, wake) }
        assertEquals("adding a nap writes exactly one row", 1, written.size)
        return written[0]
    }

    @Test
    fun aStagedNapSeedsAnEfficiencyItsOwnStagesAgreeWith() {
        // Dense gravity (1/min over the hour, floor is 30) so the whoop-rs stager runs for real.
        val grav = (0 until 60).map { GravitySample("whoop-AA:BB", bed + it * 60L, 0.0, 0.0, 1.0) }
        val row = nap(grav)
        assertNotNull("dense raw must stage asleep time, not fall back to one wake block", row.efficiency)
        assertEquals(selfConsistent(row), row.efficiency)
    }

    // ── The post-sync heal swaps the stages AND the number read off them ─────────────────────────────

    @Test
    fun healingTheStagesAlsoRewritesTheEfficiency() {
        // The heal writes real stages over an edited night's fabricated ones. Leaving efficiency behind
        // would re-stale every row the edit above just fixed, on the next sync.
        var seen: Pair<String, Double?>? = null
        val dao = Proxy.newProxyInstance(
            WhoopDao::class.java.classLoader,
            arrayOf(WhoopDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "updateSleepStages" -> {
                    seen = (args[2] as String) to (args[3] as Double?)
                    1
                }
                else -> throw UnsupportedOperationException("the stage heal must not call ${method.name}")
            }
        } as WhoopDao
        val healed = tiledNight(efficiency = null).stagesJSON!!
        runBlocking { WhoopRepository(dao).updateSleepStages("my-whoop-noop", bed, healed, bed, wake) }
        assertEquals(healed, seen!!.first)
        assertEquals(3_000.0 / 3_600.0, seen!!.second!!, 1e-12)
    }

    @Test
    fun anUnstagedNapSeedsNoEfficiency() {
        // Sparse raw: the fallback is one wake block, which is no asleep time, so no number is seeded.
        val row = nap(emptyList())
        assertNull(row.efficiency)
        assertEquals(selfConsistent(row), row.efficiency)
    }
}

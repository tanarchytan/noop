package com.noop.data

import com.noop.analytics.CircadianEngine
import com.noop.analytics.SleepStageHealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/**
 * The mock flavour's datasets. Each scenario states what it seeds ([MockClaims]) and this asserts the
 * dataset delivers it, so a screen can be checked against a stated number instead of a plausible one.
 *
 * [MockScenario.TYPICAL] additionally carries a frozen digest of every row it produces: it is the
 * dataset every earlier walk was done on, so it must not move when the seeder gains scenarios.
 */
class MockScenarioTest {

    /** A fixed day and zone, because a dataset built on "today" is a different dataset every day. */
    private val today: LocalDate = LocalDate.of(2026, 8, 4)
    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")

    /**
     * Every row [MockScenario.TYPICAL] produces on [today] in [zone]. The generator was moved verbatim
     * when the seeder gained scenarios — only its date and zone became parameters — so this is the
     * dataset every walk and screenshot was taken on. Re-pinning it is a decision about the mock data,
     * never a repair for a failing test.
     *
     * Re-pinned twice. (1) `efficiency` was seeded as a percent into a column the store defines as a
     * 0-1 fraction ([MockSeederEfficiencyUnitTest]) - same rows, corrected unit. (2) The seeder wrote
     * today's sessions across the whole calendar day, so Home listed a workout four hours in the
     * future as completed; a session that ends after [nowSec] is now not written, which drops one row
     * from this dataset. [noSessionIsWrittenAfterTheDatasetsOwnClock] states that rule directly, since
     * a digest only says a byte moved and never which one.
     *
     * Re-pinned a third time: the dataset gained the raw per-sample streams (motion, step ticks, skin
     * temperature) it had never carried, so no motion-derived surface could render on it at all. Those
     * rows are covered here rather than left out, because a digest that does not cover a table cannot
     * notice that table changing.
     */
    private val typicalDigest = "f3b409446cf84a6d3d38e8e2f6c28f91cb939de020b451208d394b16e2f201d9"

    /** The instant the dataset is "as of". Fixed, because "now" decides which of today's sessions have
     *  finished, and an unpinned clock would rebuild a different dataset every hour. */
    private val nowSec: Long = today.atTime(14, 0).atZone(zone).toEpochSecond()

    private fun build(s: MockScenario) = MockSeeder.build(s, today, zone, nowSec)

    /** Every field of every row, in the order the seeder emits them. */
    private fun canonical(ds: MockDataset): String = buildString {
        ds.devices.forEach { appendLine("device|${it.id}|${it.name}") }
        ds.pairedDevices.forEach { appendLine("paired|$it") }
        ds.daily.forEach { appendLine("daily|$it") }
        ds.sleeps.forEach { appendLine("sleep|$it") }
        ds.series.forEach { appendLine("series|$it") }
        ds.apple.forEach { appendLine("apple|$it") }
        ds.workouts.forEach { appendLine("workout|$it") }
        ds.journal.forEach { appendLine("journal|$it") }
        ds.hr.forEach { appendLine("hr|$it") }
        ds.gravity.forEach { appendLine("gravity|$it") }
        ds.steps.forEach { appendLine("steps|$it") }
        ds.skinTemp.forEach { appendLine("skinTemp|$it") }
    }

    private fun digest(ds: MockDataset): String =
        MessageDigest.getInstance("SHA-256").digest(canonical(ds).toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun typicalStillProducesTheDatasetEveryWalkWasDoneOn() {
        val ds = build(MockScenario.TYPICAL)
        assertEquals(
            "TYPICAL moved. Row counts: ${counts(ds)}",
            typicalDigest, digest(ds),
        )
    }

    /**
     * Today is in progress, so no scenario may present a session that has not finished. The second
     * half is the negative control: built as of the end of the day instead, TYPICAL writes MORE
     * sessions, which proves the cut fires rather than passing on a dataset that never reached it.
     */
    @Test
    fun noSessionIsWrittenAfterTheDatasetsOwnClock() {
        for (s in MockScenario.entries) {
            val future = MockSeeder.build(s, today, zone, nowSec).workouts.filter { it.endTs > nowSec }
            assertEquals("${s.id} presents an unfinished session as completed", emptyList<WorkoutRow>(), future)
        }
        val endOfDay = today.plusDays(1).atStartOfDay(zone).toEpochSecond()
        assertTrue(
            "the clock did not cut anything, so this gate proves nothing",
            MockSeeder.build(MockScenario.TYPICAL, today, zone, endOfDay).workouts.size >
                MockSeeder.build(MockScenario.TYPICAL, today, zone, nowSec).workouts.size,
        )
    }

    @Test
    fun everyScenarioIsDeterministic() {
        for (s in MockScenario.entries) {
            assertEquals("$s is not reproducible", digest(build(s)), digest(build(s)))
        }
    }

    @Test
    fun everyScenarioMeetsItsOwnClaims() {
        for (s in MockScenario.entries) {
            val claims = MockClaims.of(s, build(s), today, zone)
            assertTrue("$s states nothing checkable", claims.isNotEmpty())
            val broken = claims.filterNot { it.holds }
            assertEquals("$s does not deliver what it claims: $broken", emptyList<MockClaim>(), broken)
        }
    }

    @Test
    fun onlyEmptySeedsNothing() {
        for (s in MockScenario.entries) {
            val ds = build(s)
            assertEquals("$s: seedsData disagrees with what it built", s.seedsData, !ds.isEmpty)
        }
    }

    @Test
    fun everyScenarioIsReachableByItsId() {
        val ids = MockScenario.entries.map { it.id }
        assertEquals("two scenarios share an id", ids.size, ids.toSet().size)
        for (s in MockScenario.entries) {
            assertEquals(s, MockScenario.forId(s.id))
            assertEquals("the id is matched case-insensitively", s, MockScenario.forId(" ${s.id.uppercase()} "))
        }
        assertEquals("an unknown id is never guessed at", null, MockScenario.forId("nope"))
        assertEquals(null, MockScenario.forId(null))
    }

    /** The two hand-written tables are the ones a screen is asserted against, so their shape is pinned
     *  here rather than left to whatever the table happens to hold. */
    @Test
    fun theTabledScenariosCoverBothEndsAndEveryBandEdge() {
        val extremes = build(MockScenario.EXTREMES)
        assertEquals(14, extremes.daily.size)
        assertEquals("one day with no night", 13, extremes.sleeps.size)

        val edges = MockScenarios.recoveryStateEdges()
        assertTrue("whoop-rs must band recovery into more than one state", edges.isNotEmpty())
        val boundaries = build(MockScenario.BOUNDARIES)
        // A day on each edge, a day one step below each, the two ends of the scale, and one night
        // slept to exactly the need.
        assertEquals(2 * edges.size + 3, boundaries.daily.size)
    }

    /** A gap is a gap: the unworn day holds no row of any kind, and the days either side are intact. */
    @Test
    fun theGapsScenarioLeavesRealHolesRatherThanZeroes() {
        val ds = build(MockScenario.GAPS)
        val unworn = today.minusDays(MockScenarios.UNWORN_DAY).toString()
        assertEquals(emptyList<DailyMetric>(), ds.daily.filter { it.day == unworn })
        assertEquals(emptyList<AppleDaily>(), ds.apple.filter { it.day == unworn })
        assertEquals(emptyList<MetricSeriesRow>(), ds.series.filter { it.day == unworn })
        assertNotNull(ds.daily.firstOrNull { it.day == today.minusDays(MockScenarios.UNWORN_DAY - 1).toString() })
        assertNotNull(ds.daily.firstOrNull { it.day == today.minusDays(MockScenarios.UNWORN_DAY + 1).toString() })
        assertTrue("the whole absent week is gone", MockScenarios.UNWORN_WEEK.none { back ->
            ds.daily.any { it.day == today.minusDays(back).toString() }
        })
    }

    /** The swap gives each strap its own days and nothing in between. */
    @Test
    fun theTwoStrapScenarioSplitsTheWindowCleanly() {
        val ds = build(MockScenario.TWO_STRAPS)
        val owners = ds.daily.groupBy { it.deviceId }.mapValues { it.value.size }
        assertEquals(
            mapOf(MockSeeder.SECOND_STRAP to 117, MockSeeder.WHOOP to 3),
            owners,
        )
        assertEquals(
            "the second strap must be in the device list or it owns days nobody can see",
            1, ds.pairedDevices.count { it.id == MockSeeder.SECOND_STRAP },
        )
    }

    /**
     * The three states a motion-derived card has, each on a scenario: an estimate, a count-up towards
     * the floor, and no card at all. Before the raw streams were seeded every scenario sat in the third
     * one, so neither the Body Clock nor Rhythm Age could be reached from the mock at all.
     */
    @Test
    fun theWornDayFloorIsCoveredOnBothSides() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val offset = zone.rules.getOffset(java.time.Instant.now()).totalSeconds.toLong()
        fun worn(s: MockScenario, deviceId: String = MockSeeder.WHOOP) = CircadianEngine.wornDays(
            build(s).gravity.filter { it.deviceId == deviceId }
                .mapNotNull { g -> g.dynAccelG?.let { uniffi.whoop_ffi.ActivitySample(g.ts, it) } },
            offset,
        )
        assertEquals("TYPICAL must clear the floor", MockSeeder.RAW_STREAM_DAYS, worn(MockScenario.TYPICAL))
        assertTrue("GAPS keeps enough days to clear the floor", worn(MockScenario.GAPS) >= CircadianEngine.MIN_WORN_DAYS)
        assertEquals(
            "BOUNDARIES must sit one day UNDER the floor, or the count-up state is never drawn",
            CircadianEngine.MIN_WORN_DAYS - 1, worn(MockScenario.BOUNDARIES),
        )
        assertEquals("EXTREMES seeds no motion", 0, worn(MockScenario.EXTREMES))
        assertEquals("EMPTY seeds no motion", 0, worn(MockScenario.EMPTY))
        // The swap moves the window with the strap: the active one holds only the days since it.
        assertEquals(MockScenarios.STRAP_SWAP_DAY.toInt(), worn(MockScenario.TWO_STRAPS))
        assertEquals(
            MockSeeder.RAW_STREAM_DAYS - MockScenarios.STRAP_SWAP_DAY.toInt(),
            worn(MockScenario.TWO_STRAPS, MockSeeder.SECOND_STRAP),
        )
    }

    /**
     * The seeded motion is a fixture INPUT, not a second opinion on a night: it must stay under
     * [SleepStageHealer]'s density gate, or the heal pass would re-derive stages on device and the
     * curated night every stage claim is written against would quietly stop being the one on screen.
     */
    @Test
    fun theSeededMotionCannotRestageASeededNight() {
        for (s in MockScenario.entries) {
            val ds = build(s)
            val dense = ds.sleeps.count {
                SleepStageHealer.isDense(ds.gravity.filter { g -> g.deviceId == it.deviceId }, it.startTs, it.endTs)
            }
            assertEquals("$s seeds motion dense enough to restage a night", 0, dense)
        }
    }

    /** No stream row may sit after the dataset's own clock, for the same reason no session may. */
    @Test
    fun noStreamSampleIsWrittenAfterTheDatasetsOwnClock() {
        for (s in MockScenario.entries) {
            val ds = build(s)
            val future = ds.gravity.count { it.ts > nowSec } + ds.steps.count { it.ts > nowSec } +
                ds.skinTemp.count { it.ts > nowSec }
            assertEquals("${s.id} banks a stream sample from the future", 0, future)
        }
        assertTrue(
            "the clock did not cut anything, so this gate proves nothing",
            MockSeeder.build(MockScenario.TYPICAL, today, zone, today.plusDays(1).atStartOfDay(zone).toEpochSecond())
                .gravity.size > build(MockScenario.TYPICAL).gravity.size,
        )
    }

    private fun counts(ds: MockDataset) =
        "daily=${ds.daily.size} sleeps=${ds.sleeps.size} series=${ds.series.size} " +
            "apple=${ds.apple.size} workouts=${ds.workouts.size} journal=${ds.journal.size} " +
            "hr=${ds.hr.size} gravity=${ds.gravity.size} steps=${ds.steps.size} skinTemp=${ds.skinTemp.size}"
}

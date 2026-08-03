package com.noop.data

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
     */
    private val typicalDigest = "1e2abd86228c8c59365abb0a014cc7973fcf1d0948b5d75ad53f26d46196a4a4"

    private fun build(s: MockScenario) = MockSeeder.build(s, today, zone)

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

    private fun counts(ds: MockDataset) =
        "daily=${ds.daily.size} sleeps=${ds.sleeps.size} series=${ds.series.size} " +
            "apple=${ds.apple.size} workouts=${ds.workouts.size} journal=${ds.journal.size} hr=${ds.hr.size}"
}

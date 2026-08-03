package com.noop.data

import com.noop.analytics.RestScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * `efficiency` is stored as a 0-1 fraction, in both `dailyMetric` and `sleepSession`. That is the store's
 * own definition: [com.noop.data.WhoopDatabase] carries a heal migration that divides any row > 1.5 by 100,
 * the CSV importer converts percent to fraction on the way in, and the exporter multiplies by 100 on the
 * way out.
 *
 * The seeder wrote percent. A fresh mock database is created at the current version, so the heal migration
 * never runs on it and the wrong unit survives to every reader.
 */
class MockSeederEfficiencyUnitTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 4)
    private val zone: ZoneId = ZoneId.of("Europe/Amsterdam")

    private fun build(s: MockScenario) = MockSeeder.build(s, today, zone)

    /** The heal migration's own predicate: a fraction can never exceed 1.0, so > 1.5 is a percent row. */
    @Test
    fun `every scenario seeds efficiency as the fraction the store defines`() {
        MockScenario.entries.forEach { s ->
            val ds = build(s)
            ds.daily.mapNotNull { it.efficiency }.forEach {
                assertTrue("${s.id}: dailyMetric.efficiency $it is a percent, not a fraction", it <= 1.0)
            }
            ds.sleeps.mapNotNull { it.efficiency }.forEach {
                assertTrue("${s.id}: sleepSession.efficiency $it is a percent, not a fraction", it <= 1.0)
            }
        }
    }

    /**
     * The consequence, and the reason the unit matters rather than being cosmetic: whoop-rs scores the
     * efficiency term as `(efficiency * 100).clamp(0, 100)`. Fed a percent, every night saturates at the
     * maximum, so the term stops discriminating and Rest is inflated on all of them.
     */
    @Test
    fun `a night's efficiency still moves its Rest score`() {
        val nights = build(MockScenario.TYPICAL).daily.filter { it.efficiency != null && it.totalSleepMin != null }
        val worst = nights.minByOrNull { it.efficiency!! }!!
        val best = nights.maxByOrNull { it.efficiency!! }!!
        assertNotEquals("the dataset must span a range of efficiency to test this", worst.efficiency, best.efficiency)

        // One night, scored at each end of the seeded efficiency range: only the efficiency term moves.
        val low = RestScorer.restFromDaily(worst.copy(efficiency = worst.efficiency))!!
        val high = RestScorer.restFromDaily(worst.copy(efficiency = best.efficiency))!!
        assertTrue("a better-slept night must not score lower on Rest", high >= low)
        assertNotEquals("the Rest efficiency term is saturated, so efficiency no longer counts", low, high)
    }

    /** One night, one efficiency: the day row and the session it was built from cannot disagree. */
    @Test
    fun `a seeded night's session and its day carry the same efficiency`() {
        val ds = build(MockScenario.TYPICAL)
        val dayEff = ds.daily.mapNotNull { it.efficiency }.sorted()
        val sessionEff = ds.sleeps.mapNotNull { it.efficiency }.sorted()
        assertEquals("a slept night writes an efficiency to both rows", dayEff, sessionEff)
    }
}

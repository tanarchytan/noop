package com.noop.data

import com.noop.analytics.SleepStageTotals
import com.noop.ui.canonicalStage
import com.noop.ui.parsePersistedSegments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seeded `stagesJSON` must be the shape the app's own readers understand, because for a long time it
 * was not and nothing said so.
 *
 * It emitted `{"stage", "min"}`. [parsePersistedSegments] needs `start`/`end` and returned null, so the
 * hero fell to its reconstructed branch and tap-to-highlight had no real timeline to paint bands from; the
 * totals parser reads durations from `start`/`end` too, so it scored every stage zero. The vocabulary was
 * wrong as well: whoop-rs emits `wake`, the seeder wrote `awake`. Every test passed throughout, because
 * none of them read the field.
 */
class MockSeederStagesJsonTest {

    private val onset = 1_785_000_000L
    private val wake = onset + 8 * 3600L

    private fun seeded(): String = MockSeeder.stagesJson(
        deep = 90.0, rem = 100.0, light = 250.0, startTs = onset, endTs = wake,
    )

    @Test
    fun `the seeded stages parse as real timestamped segments`() {
        val segs = parsePersistedSegments(seeded())
        assertNotNull("the timeline's own parser must understand the seeder's output", segs)
        assertTrue("a highlightable night needs more than one run", segs!!.size >= 2)
    }

    @Test
    fun `the segments tile the session exactly, with no gap and no overhang`() {
        val segs = parsePersistedSegments(seeded())!!
        assertEquals("first segment starts at onset", onset, segs.first().start)
        assertEquals("last segment ends at wake", wake, segs.last().end)
        segs.zipWithNext { a, b ->
            assertEquals("a gap here would render as missing time", a.end, b.start)
        }
        segs.forEach { assertTrue("every run must move forward", it.end > it.start) }
    }

    @Test
    fun `the vocabulary is the one whoop-rs emits, so every stage survives canonicalisation`() {
        val stages = parsePersistedSegments(seeded())!!.map { canonicalStage(it.stage) }.toSet()
        assertEquals(
            "whoop-rs emits wake|light|deep|rem; anything else is silently dropped downstream",
            setOf("awake", "light", "deep", "rem"),
            stages,
        )
    }

    @Test
    fun `a degenerate span yields an empty array rather than a broken one`() {
        assertEquals("[]", MockSeeder.stagesJson(90.0, 100.0, 250.0, onset, onset))
        assertEquals("[]", MockSeeder.stagesJson(0.0, 0.0, 0.0, onset, wake))
    }

    /**
     * The gate that was missing: nothing checked the segments still CARRY the minutes they were built
     * from. They were stretched onto the span instead, so the hero card read every stage ~9% above the
     * daily column beside it and four other screens printed the smaller figure.
     */
    @Test
    fun `decoding the segments returns the stage minutes they were built from`() {
        val m = SleepStageTotals.minutes(seeded())!!
        assertEquals("deep must survive the round trip", 90.0, m.deep, 0.05)
        assertEquals("rem must survive the round trip", 100.0, m.rem, 0.05)
        assertEquals("light must survive the round trip", 250.0, m.light, 0.05)
        assertEquals("asleep is the sum of the three", 440.0, m.asleep, 0.05)
    }

    /** Awake is the span's remainder, so in-bed is the span and efficiency reads back off the segments. */
    @Test
    fun `awake is what the span has left over the sleep, so in-bed is the whole session`() {
        val m = SleepStageTotals.minutes(seeded())!!
        assertEquals("awake closes the span", 40.0, m.awake, 0.05)
        assertEquals("in-bed is the session span", (wake - onset) / 60.0, m.inBed, 0.05)
    }

    /**
     * The invariant the whole Sleep tab rests on: a seeded night's DAILY COLUMNS are the aggregate of its
     * OWN segments. Break it and the hero card, "Hours vs Needed", Intelligence, the weekly Restorative
     * chart and the fused record each describe a different night, with nothing to say which is the night.
     */
    @Test
    fun `a seeded night's columns are the aggregate of its own segments`() {
        // (asleep, efficiency %) across the range the seeder draws from, stages in its own proportions.
        listOf(300.0 to 72.0, 416.2 to 90.2, 430.0 to 89.0, 540.0 to 98.0).forEach { (asleep, effPct) ->
            val deep = Math.round(asleep * 0.20 * 10) / 10.0
            val rem = Math.round(asleep * 0.23 * 10) / 10.0
            val light = Math.round((asleep - deep - rem) * 10) / 10.0
            val total = deep + rem + light
            val span = MockSeeder.inBedSecFor(total, effPct)
            val m = SleepStageTotals.minutes(MockSeeder.stagesJson(deep, rem, light, onset, onset + span))!!
            assertEquals("$asleep@$effPct asleep column", total, m.asleep, 0.05)
            assertEquals("$asleep@$effPct deep column", deep, m.deep, 0.05)
            assertEquals("$asleep@$effPct rem column", rem, m.rem, 0.05)
            assertEquals("$asleep@$effPct light column", light, m.light, 0.05)
            assertEquals("$asleep@$effPct efficiency column", effPct, m.asleep / m.inBed * 100.0, 0.05)
        }
    }
}

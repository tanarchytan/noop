package com.noop.data

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
        deep = 90.0, rem = 100.0, light = 250.0, awakeMin = 30, startTs = onset, endTs = wake,
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
        assertEquals("[]", MockSeeder.stagesJson(90.0, 100.0, 250.0, 30, onset, onset))
        assertEquals("[]", MockSeeder.stagesJson(0.0, 0.0, 0.0, 0, onset, wake))
    }
}

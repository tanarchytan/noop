package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval
import com.noop.ui.NoopPrefs
import com.noop.ui.parsePersistedSegments
import com.noop.ui.unscoredFractions
import com.noop.ui.unscoredSpans
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.whoop_ffi.SleepEngine

/** The Original/Experimental sleep engine setting: default, routing, persistence of unscored stretches. */
class SleepEngineToggleTest {

    private val dev = "d"
    private val start = 1_749_513_600L + 3_600L
    private val phase = 90 * 60
    private val dur = phase * 4

    @After
    fun resetEngine() {
        RustSleepStager.engineProvider = { SleepEngine.ORIGINAL }
    }

    private fun rsaWave(ph: Int, i: Int): Int {
        val amp = intArrayOf(12, 60, 30, 20)[ph]
        return intArrayOf(0, amp, 0, -amp)[i % 4]
    }

    private val grav = ArrayList<GravitySample>()
    private val hr = ArrayList<HrSample>()
    private val rr = ArrayList<RrInterval>()

    private fun night() {
        if (grav.isNotEmpty()) return
        for (i in 0 until dur) {
            val ts = start + i
            val ph = i / phase
            val restless = ph == 3 && (i % 20) < 6
            grav.add(if (restless) GravitySample(dev, ts, 0.2, 0.15, 0.96) else GravitySample(dev, ts, 0.0, 0.0, 1.0))
            val bpm = when (ph) {
                0 -> 50
                1 -> 54 + intArrayOf(0, 1, 2, 3, 2, 1)[(i / 20) % 6]
                2 -> 56 + (i / 60) % 4
                else -> 66 + (i / 30) % 6
            }
            hr.add(HrSample(dev, ts, bpm))
            rr.add(RrInterval(dev, ts, 60_000 / bpm + rsaWave(ph, i)))
        }
    }

    private fun stage() = RustSleepStager.stage(start, start + dur, grav, hr, rr, emptyList())

    @Test
    fun `engine setting defaults to original`() {
        assertEquals(SleepEngine.ORIGINAL, NoopPrefs.sleepEngineOf(null))
        assertEquals(SleepEngine.ORIGINAL, NoopPrefs.sleepEngineOf("original"))
        assertEquals(SleepEngine.ORIGINAL, NoopPrefs.sleepEngineOf("garbage"))
        assertEquals(SleepEngine.EXPERIMENTAL, NoopPrefs.sleepEngineOf("experimental"))
        assertEquals(SleepEngine.ORIGINAL, RustSleepStager.engineProvider())
    }

    @Test
    fun `original engine keeps the frozen hypnogram and scores every epoch`() {
        night()
        val staged = stage()
        assertEquals(6, staged.stages.size)
        assertEquals(start, staged.stages.first().start)
        assertEquals(start + dur, staged.stages.last().end)
        assertTrue(staged.unscored.isEmpty())
    }

    @Test
    fun `experimental engine returns unscored stretches inside the night and the same stage totals path`() {
        night()
        RustSleepStager.engineProvider = { SleepEngine.EXPERIMENTAL }
        val staged = stage()
        assertTrue("experimental must leave some stretch unscored", staged.unscored.isNotEmpty())
        var prevEnd = start
        for ((s, e) in staged.unscored) {
            assertTrue("span inside the night", s >= start && e <= start + dur && e > s)
            assertTrue("ascending, non-overlapping", s >= prevEnd)
            prevEnd = e
        }
        assertTrue(staged.stages.isNotEmpty())
    }

    @Test
    fun `analyze routes the chosen engine and maps unscored onto the detected night`() {
        night()
        val original = RustSleepStager.analyze(hr, rr, grav, emptyList(), 0L, emptyList(), emptyList())
        assertTrue(original.all { it.unscored.isEmpty() })
        RustSleepStager.engineProvider = { SleepEngine.EXPERIMENTAL }
        val experimental = RustSleepStager.analyze(hr, rr, grav, emptyList(), 0L, emptyList(), emptyList())
        assertEquals(original.size, experimental.size)
        if (experimental.isNotEmpty()) assertTrue(experimental.any { it.unscored.isNotEmpty() })
    }

    @Test
    fun `unscored spans round trip through stagesJSON and leave stage readers untouched`() {
        val stages = listOf(
            StageSegment(1_000, 1_600, "light"),
            StageSegment(1_600, 2_200, "deep"),
            StageSegment(2_200, 3_000, "wake"),
        )
        val spans = listOf(1_300L to 1_900L, 2_500L to 2_800L)
        val plain = AnalyticsEngine.encodeStages(stages)
        val withHoles = AnalyticsEngine.encodeStages(stages, spans)
        assertEquals(spans, unscoredSpans(withHoles))
        assertEquals(parsePersistedSegments(plain), parsePersistedSegments(withHoles))
        val a = SleepStageTotals.minutes(plain)!!
        val b = SleepStageTotals.minutes(withHoles)!!
        assertEquals(a.light, b.light, 0.0)
        assertEquals(a.deep, b.deep, 0.0)
        assertEquals(a.awake, b.awake, 0.0)
        assertEquals(SleepStageTotals.timelineSegments(plain), SleepStageTotals.timelineSegments(withHoles))
    }

    @Test
    fun `a night stored before the field existed loads with no unscored spans`() {
        val legacy = """[{"end":1600,"stage":"light","start":1000},{"end":2200,"stage":"deep","start":1600}]"""
        assertTrue(unscoredSpans(legacy).isEmpty())
        assertTrue(unscoredSpans(null).isEmpty())
        assertTrue(unscoredSpans("not json").isEmpty())
        assertTrue(unscoredSpans("""{"awake":1,"light":2}""").isEmpty())
        assertNotNull(parsePersistedSegments(legacy))
    }

    @Test
    fun `unscored fractions map timestamps to the night axis and clip to it`() {
        val f = unscoredFractions(listOf(1_250L to 1_500L, 900L to 1_100L, 5_000L to 6_000L), 1_000L, 1_000.0)
        assertEquals(2, f.size)
        assertEquals(0.25f, f[0].first, 1e-6f)
        assertEquals(0.25f, f[0].second, 1e-6f)
        assertEquals(0f, f[1].first, 1e-6f)
        assertEquals(0.1f, f[1].second, 1e-6f)
        assertTrue(unscoredFractions(listOf(1L to 2L), null, 100.0).isEmpty())
    }
}

package com.noop.analytics

import com.noop.data.StepSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * App-side parity gate for routing the 5/MG step total through whoop-rs. The wrap-aware
 * `step_motion_counter@57` tick sum is moving from the Kotlin [StepsCounter] to physio-algo (via the
 * [RustScores.steps] bridge -> uniffi -> whoop-rs `steps_counter`); this pins the swap by replaying the
 * SAME counter series through BOTH paths and asserting the Int? total is identical (the exact value
 * [AnalyticsEngine] divides by `stepTicksPerStep` into `DailyMetric.steps`). Loads the host libwhoop_ffi
 * via JNA (see jna.library.path / buildRustHostDll).
 */
class RustStepsParityTest {

    private val dev = "t"
    private val start = 1_783_651_476L

    private fun sample(ts: Long, counter: Int) = StepSample(dev, ts, counter)

    private fun assertStepsParity(label: String, samples: List<StepSample>) {
        val kotlin = StepsCounter.stepsInWindow(samples)
        val rust = RustScores.steps(samples)
        assertEquals("$label (kotlin=$kotlin rust=$rust)", kotlin, rust)
    }

    @Test
    fun `steady 1 Hz counter increments match`() {
        // Climb by a few ticks per second, hold flat, climb again.
        val s = ArrayList<StepSample>()
        var c = 1000
        for (i in 0 until 600) {
            c += if (i % 3 == 0) 2 else 0
            s.add(sample(start + i, c and 0xFFFF))
        }
        assertStepsParity("steady", s)
    }

    @Test
    fun `u16 wrap is counted wrap-aware on both paths`() {
        // Cross the 65536 boundary: 65500 -> 20 is a wrap-aware +56, not a huge negative.
        val s = listOf(
            sample(start, 65_400),
            sample(start + 1, 65_500),
            sample(start + 2, 20), // wrap: (20 - 65500) and 0xFFFF = 56
            sample(start + 3, 90),
        )
        assertStepsParity("wrap", s)
    }

    @Test
    fun `a gap or reset delta at-or-above 512 is dropped identically`() {
        val s = listOf(
            sample(start, 100),
            sample(start + 1, 200), // +100 kept
            sample(start + 2, 1200), // +1000 >= 512 dropped (sync gap / reboot)
            sample(start + 3, 1210), // +10 kept
        )
        assertStepsParity("gap-drop", s)
    }

    @Test
    fun `unsorted input sorts by ts identically`() {
        val s = listOf(
            sample(start + 3, 130),
            sample(start, 100),
            sample(start + 2, 120),
            sample(start + 1, 108),
        )
        assertStepsParity("unsorted", s)
    }

    @Test
    fun `null gates match - too few samples and no movement`() {
        assertNull(StepsCounter.stepsInWindow(emptyList()))
        assertNull(RustScores.steps(emptyList()))
        assertStepsParity("single", listOf(sample(start, 100)))
        // All-flat counter -> no forward movement -> both null (distinct from a real zero).
        val flat = (0 until 50).map { sample(start + it, 500) }
        assertStepsParity("flat-no-movement", flat)
    }
}

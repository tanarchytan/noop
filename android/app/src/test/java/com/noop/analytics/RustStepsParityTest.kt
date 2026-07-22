package com.noop.analytics

import com.noop.data.StepSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FFI smoke test for the 5/MG step total ([RustScores.steps] -> uniffi -> whoop-rs `steps_counter`). The
 * wrap-aware `step_motion_counter@57` tick math lives in physio-algo; this asserts the marshalled result
 * end-to-end (wrap handling, the gap/reset drop, ts sorting, the null gates), catching a bad binding/.so.
 * The exact tick numbers are frozen by the Rust golden tests; here we pin the observable behaviour. Loads
 * the host libwhoop_ffi via JNA (buildRustHostDll).
 */
class RustStepsParityTest {

    private val dev = "t"
    private val start = 1_783_651_476L

    private fun sample(ts: Long, counter: Int) = StepSample(dev, ts, counter)

    @Test
    fun `steady counter sums the per-second increments`() {
        // +2 on i = 0,3,..,597 (200 bumps). The i=0 bump is baked into the first sample's baseline (no
        // prior sample to diff), so 199 captured deltas of +2 = 398.
        val s = ArrayList<StepSample>()
        var c = 1000
        for (i in 0 until 600) {
            c += if (i % 3 == 0) 2 else 0
            s.add(sample(start + i, c and 0xFFFF))
        }
        assertEquals(398, RustScores.steps(s))
    }

    @Test
    fun `u16 wrap counts wrap-aware not as a huge negative`() {
        // 65400 ->65500 (+100), 65500 ->20 wraps to +56, 20 ->90 (+70) = 226.
        val s = listOf(sample(start, 65_400), sample(start + 1, 65_500), sample(start + 2, 20), sample(start + 3, 90))
        assertEquals(226, RustScores.steps(s))
    }

    @Test
    fun `a gap or reset delta at-or-above 512 is dropped`() {
        // +100 kept, +1000 dropped (sync gap / reboot), +10 kept = 110.
        val s = listOf(sample(start, 100), sample(start + 1, 200), sample(start + 2, 1200), sample(start + 3, 1210))
        assertEquals(110, RustScores.steps(s))
    }

    @Test
    fun `unsorted input is sorted by ts before differencing`() {
        val s = listOf(sample(start + 3, 130), sample(start, 100), sample(start + 2, 120), sample(start + 1, 108))
        assertEquals(30, RustScores.steps(s)) // 100 ->108 ->120 ->130 = 30
    }

    @Test
    fun `null gates - too few samples and no forward movement`() {
        assertNull("empty", RustScores.steps(emptyList()))
        assertNull("single sample", RustScores.steps(listOf(sample(start, 100))))
        val flat = (0 until 50).map { sample(start + it, 500) }
        assertNull("flat counter is no data, not a real zero", RustScores.steps(flat))
    }
}

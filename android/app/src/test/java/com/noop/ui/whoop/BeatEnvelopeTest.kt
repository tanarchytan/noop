package com.noop.ui.whoop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The beating heart beside the live badge. The envelope is a scale factor over one cycle, so it is
 * presentation, not a physiological figure — nothing here is read off the strap and nothing here
 * becomes a number the user reads.
 *
 * What matters is that it reads as a HEART: a sharp thump, a smaller second bump, then a rest long
 * enough to separate one beat from the next. A smooth sine would read as a breath.
 */
class BeatEnvelopeTest {

    private val peak = 1.15f
    private val secondPeak = 1.07f

    @Test
    fun `rests at unit scale for the second half of the cycle`() {
        for (phase in listOf(0.50f, 0.62f, 0.75f, 0.9f, 0.999f)) {
            assertEquals("phase $phase should be at rest", 1f, beatScale(phase), 1e-6f)
        }
    }

    @Test
    fun `starts and ends the cycle at unit scale so it loops without a jump`() {
        assertEquals(1f, beatScale(0f), 1e-6f)
        assertEquals(beatScale(0f), beatScale(0.999f), 1e-3f)
    }

    @Test
    fun `systolic thump reaches its peak and is the larger of the two`() {
        assertEquals("the thump tops out at 0.09", peak, beatScale(0.09f), 1e-5f)
        assertEquals("the second bump tops out at 0.37", secondPeak, beatScale(0.37f), 1e-5f)
        assertTrue("the first peak must be the larger", beatScale(0.09f) > beatScale(0.37f))
    }

    @Test
    fun `holds a beat of rest between the thump and the second bump`() {
        assertEquals(1f, beatScale(0.25f), 1e-6f)
    }

    @Test
    fun `never exceeds the thump and never shrinks below unit scale`() {
        var p = 0f
        while (p < 1f) {
            val s = beatScale(p)
            assertTrue("scale $s at phase $p went below 1", s >= 1f - 1e-5f)
            assertTrue("scale $s at phase $p overshot the peak", s <= peak + 1e-5f)
            p += 0.001f
        }
    }
}

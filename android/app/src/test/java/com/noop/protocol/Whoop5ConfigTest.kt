package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The WHOOP 5/MG R22 deep-data enable SEQUENCE (the ordered flag list + values — the send POLICY that
 * stays in Kotlin). The frame BYTES for each flag are built by whoop-rs and locked, at the shared
 * judes.club/Swift golden seq=1, in [SendFrameParityTest]. (#174)
 */
class Whoop5ConfigTest {

    @Test
    fun sequenceIsSixteenFlagsWithExpectedValues() {
        val seq = Whoop5Config.enableR22Sequence
        assertEquals(16, seq.size)
        assertEquals("enable_r22_packets", seq[0].name)
        assertEquals(0x32, seq[0].value)
        // v4 and the passive-strap-fit flag are the only '1' (0x31) values in the documented set.
        assertEquals(0x31, seq.first { it.name == "enable_r22_v4_packets" }.value)
        assertEquals(0x31, seq.first { it.name == "enable_passive_strap_fit_gen5" }.value)
        // #103: the 16th flag `enable_sig12` was seen in a real on-strap capture, appended after the 15
        // judes.club-documented flags. #423: a second real on-strap capture (spanning a live workout)
        // decoded its value as '1' (0x31), not '2' — corrected here. Mirror of the Swift Whoop5ConfigTests guard.
        assertEquals("enable_sig12", seq.last().name)
        assertEquals(0x31, seq.last().value)
    }
}

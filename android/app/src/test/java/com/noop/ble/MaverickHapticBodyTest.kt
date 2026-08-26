package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte-lock for the 5/MG haptic body [WhoopBleClient.maverickHapticBody] builds. Pure Kotlin, so it
 * runs without the host libwhoop_ffi that SendFrameParityTest loads.
 */
class MaverickHapticBodyTest {

    private fun h(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** The body [WhoopBleClient.buzz] hands in: `[patternId=2, loops, 0, 0, 0]`. */
    private fun body(loops: Int) =
        WhoopBleClient.maverickHapticBody(byteArrayOf(2, loops.toByte(), 0, 0, 0))

    @Test
    fun `one pulse leaves overallLoop at zero`() =
        assertEquals("012f98000000000000000000", h(body(1)))

    @Test
    fun `overallLoop counts the repeats after the first pulse`() {
        assertEquals(1, body(2)[11].toInt())   // double
        assertEquals(2, body(3)[11].toInt())   // triple
        assertEquals(4, body(5)[11].toInt())   // the long cue IntervalsScreen sends
    }

    @Test
    fun `effects preset and loopControl are untouched by the repeat count`() {
        assertEquals("012f980000000000000000", h(body(6).copyOfRange(0, 11)))
        assertEquals(12, body(6).size)
    }

    @Test
    fun `loops is clamped, not wrapped`() {
        assertEquals(7, body(8)[11].toInt())
        assertEquals(7, body(255)[11].toInt())
        assertEquals(0, body(0)[11].toInt())
    }

    @Test
    fun `a payload too short to carry a count gives one pulse`() {
        assertEquals(0, WhoopBleClient.maverickHapticBody(byteArrayOf(2))[11].toInt())
        assertEquals(0, WhoopBleClient.maverickHapticBody(byteArrayOf())[11].toInt())
    }
}

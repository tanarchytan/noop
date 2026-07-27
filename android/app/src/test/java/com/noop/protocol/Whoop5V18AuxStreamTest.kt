package com.noop.protocol

import com.noop.data.V18Row
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-second channels the storage funnel used to decode and discard, carried end to end on a real
 * offloaded frame: whoop-rs decode, the flat map, then [extractHistoricalStreams] into rows. The
 * funnel drops whatever it does not name, so this is what proves the naming reaches the store.
 */
class Whoop5V18AuxStreamTest {

    /** One real v18 second. Its two optical amplitudes both read 128, which is the quality sentinel. */
    private val realV18 =
        "aa01740001003fb12f128066b7760180fc546aeb71005601" +
            "1d0200000000000000002c0481555700ffb063f13d852b853db87ef43d298e803f7a01a1000000000000000000" +
            "51015901db0d6006010c020c00000000000000000000000000000000000000000000000100adb18080000000c8" +
            "d69bc00000000d27d7e3"

    private fun bytes(hex: String) =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

    private fun streams() =
        extractHistoricalStreams(listOf(bytes(realV18)), 1_783_954_560, 1_783_954_560, DeviceFamily.WHOOP5)

    @Test
    fun `the aux channels reach the store as one row`() {
        val rows = streams().v18
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(1_783_954_560L, r.ts)
        assertEquals(24_557_414L, r.recordIndex)
        assertEquals(0, r.sleepStateRaw)
        assertEquals(173, r.opticalBaselineA)
        assertEquals(177, r.opticalBaselineB)
        assertEquals(129, r.rawU8At28)
        assertEquals(85, r.rawU8At29)
        assertEquals(87, r.rawU16At30)
        assertEquals(1068, r.rawU16At26)
        assertEquals(-4.869968, r.rawF32At105!!, 1e-6)
    }

    @Test
    fun `the amplitude sentinel withholds both channels and reports itself instead`() {
        val r = streams().v18[0]
        assertEquals(true, r.opticalSignalPoor)
        // Withheld, not zero: a reading of 128 on both channels is the band saying it could not trust
        // this second's beat detection, so publishing it as a magnitude would invent a measurement.
        assertNull(r.opticalAmpA)
        assertNull(r.opticalAmpB)
    }

    @Test
    fun `the unpinned bytes arrive packed in offset order`() {
        val expected = intArrayOf(128, 235, 113, 0, 161, 96, 6, 1, 12, 2, 12, 0, 0)
            .map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, streams().v18[0].unpinned)
    }

    @Test
    fun `the auxiliary thermal registers ride the skin-temperature row`() {
        val skin = streams().skinTemp
        assertEquals(1, skin.size)
        assertEquals(3547, skin[0].raw)
        // Deci-degrees against the skin channel's centi-degrees, and below it while worn.
        assertEquals(337, skin[0].auxRaw1)
        assertEquals(345, skin[0].auxRaw2)
        assertTrue(skin[0].auxRaw1!! < skin[0].raw / 10)
    }

    @Test
    fun `a record carrying none of them writes no row`() {
        // A WHOOP 4.0 frame reaches the same funnel and must not produce an empty v18 row.
        assertTrue(V18Row(ts = 1L).isEmpty)
    }
}

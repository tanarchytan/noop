package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHOOP 5.0/MG type-47 "v18" band sleep_state (#175) reaches the stream through [extractHistoricalStreams].
 * Record bytes→values decode now runs through whoop-rs; this pins the app-side plumbing that carries the
 * decoded band state out as a [com.noop.data.SleepStateRow] (it was decoded but dropped before #175).
 */
class Whoop5HistoricalDecodeTest {

    private fun bytes(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // Real worn WHOOP 5 v18 frame: hr=102, rr=[602,613] ms, |gravity|≈1, band sleep_state=0 (wake).
    private val wornV18 =
        "aa01740001003fb12f1280733d8401b69f266a66460066025a0265020000000000007b0a8d656463ff0012163cf6a439bf2924fd3ed763fe3e3200aa000000000000000000f7000901f10b0007010c020c00000000000000000000000000000000000000000000000100656f1e1e0000009d61a7c00000003e862817"

    /** Mutate one absolute frame byte and re-stamp the CRC32 (over frame[8..len-4]) so it passes the gate. */
    private fun mutateAndReCrc(index: Int, value: Int): ByteArray {
        val f = bytes(wornV18); f[index] = value.toByte()
        val end = f.size - 4
        val crc = Crc.crc32(f.copyOfRange(8, end))
        f[end] = (crc and 0xFF).toByte(); f[end + 1] = ((crc shr 8) and 0xFF).toByte()
        f[end + 2] = ((crc shr 16) and 0xFF).toByte(); f[end + 3] = ((crc shr 24) and 0xFF).toByte()
        return f
    }

    // #175: the decoded band sleep_state must survive extractHistoricalStreams as a SleepStateRow. On the
    // REAL worn daytime fixture the band reads 0 (wake) — carried VERBATIM (0 is a real wake reading).
    @Test
    fun sleepStateReachesStreamOnRealFixture() {
        val st = extractHistoricalStreams(listOf(bytes(wornV18)), 1780916150, 1780916150, DeviceFamily.WHOOP5)
        assertEquals(listOf(com.noop.data.SleepStateRow(1780916150L, 0)), st.sleepState)
    }

    // The non-zero codes come only from an in-memory byte override (we hold NO real sleeping-night capture),
    // so this proves the PLUMBING carries whatever the band reports. The CRC is re-stamped so the extractor's
    // CRC gate passes; it does NOT assert the code meanings against real data.
    @Test
    fun sleepStateStreamCarriesEachNibble() {
        for ((raw, expected) in listOf(0x10 to 1, 0x20 to 2, 0x30 to 3)) {
            val frame = mutateAndReCrc(81, raw)
            val st = extractHistoricalStreams(listOf(frame), 1780916150, 1780916150, DeviceFamily.WHOOP5)
            assertEquals(listOf(com.noop.data.SleepStateRow(1780916150L, expected)), st.sleepState)
        }
    }
}

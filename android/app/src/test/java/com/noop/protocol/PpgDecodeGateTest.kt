package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The v26 PPG decode gate now lives in whoop-rs (the Kotlin @9==26 pre-check was dropped): decodePpg
 * returns null for any non-v26 frame and the 24 samples only for a real v26 buffer. Loads the host
 * libwhoop_ffi via JNA (see jna.library.path / buildRustHostDll).
 */
class PpgDecodeGateTest {

    private fun bytes(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    // Real worn WHOOP 5/MG frames: a v18 per-second record (no waveform) and a v26 24-sample PPG buffer.
    private val v18Hex =
        "aa01740001003fb12f1280733d8401b69f266a66460066025a0265020000000000007b0a8d65" +
        "6463ff0012163cf6a439bf2924fd3ed763fe3e3200aa000000000000000000f7000901f10b00" +
        "07010c020c00000000000000000000000000000000000000000000000100656f1e1e0000009d" +
        "61a7c00000003e862817"
    private val v26Hex =
        "aa015000010035412f1a804b047b019452596aae0701004b8503006bfdcffd36fe50fe12ff73ff" +
        "6dff42ffa7ffc9fff9ffe5ff5c005a007a00f20089003000dbfd2efd0bfe3ffeaefe3affc06c213" +
        "c50070001001ddc65fe"

    @Test
    fun `v18 history frame yields no PPG`() {
        // Version gate: a v18 record is not a PPG buffer, so decodePpg must return null (not 24 samples).
        assertNull(RustCodec.decodePpg(bytes(v18Hex)))
    }

    @Test
    fun `v26 frame still yields 24 PPG samples`() {
        // The gate must not over-reject: a real v26 buffer still decodes to its 24 optical samples.
        val ppg = RustCodec.decodePpg(bytes(v26Hex))
        assertNotNull(ppg)
        assertEquals(24, ppg!!.samples.size)
    }
}

package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Permanent byte-lock for the SEND path: every outbound frame the whoop-rs FFI builds is frozen here as
 * hex. The golden vectors were captured from the byte-parity cutover, where each FFI frame was proven
 * byte-identical to the Kotlin builder it replaced (which already worked on real 5.0 + 4.0 hardware).
 * A drift in any FFI builder now reds this test. Loads the host libwhoop_ffi via JNA (buildRustHostDll).
 */
class SendFrameParityTest {

    private val seq = 7

    private fun h(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun `gen5 generic command frame`() =
        assertEquals("aa010c000001e74123072a010203000060fd64e4", h(RustCodec.commandFrame(true, seq, 0x2A, byteArrayOf(1, 2, 3))!!))

    @Test
    fun `gen4 generic command frame`() =
        assertEquals("aa0800a8230703008a9aa1bd", h(RustCodec.commandFrame(false, seq, 3, byteArrayOf(0))!!))

    @Test
    fun `gen5 maverick buzz frame`() =
        assertEquals("aa0114000001e1e1230713012f98000000000000000000006a1f7987", h(RustCodec.buzzFrame(seq)))

    @Test
    fun `gen4 get-battery bond frame`() =
        assertEquals("aa0800a823071a009233a126", h(RustCodec.getBatteryFrame(false, seq)))

    @Test
    fun `set clock 8-byte form both families`() {
        assertEquals("aa0f00c323070a00ae556a0000000046975e9b", h(RustCodec.setClockFrame(false, seq, 1_784_000_000L)))
        assertEquals("aa0110000001e0d123070a00ae556a0000000000bf55264d", h(RustCodec.setClockFrame(true, seq, 1_784_000_000L)))
    }

    @Test
    fun `set clock legacy 9-byte form`() =
        assertEquals("aa10005723070a00ae556a0000000000bf55264d", h(RustCodec.setClockLegacyFrame(false, seq, 1_784_000_000L)))

    @Test
    fun `gen5 rev4 alarm set frame`() =
        assertEquals("aa011c000001e381230742040100f15365be0f2f980000000000000000071e00435de024", h(RustCodec.alarmSetFrame(seq, 1_700_000_000_123L)))

    @Test
    fun `gen4 alarm set frame`() =
        assertEquals("aa1000572307420130d5356a00000000579f764b", h(RustCodec.alarmSetFrameGen4(seq, 1_781_912_880L)))

    @Test
    fun `gen5 disable alarm frame`() =
        assertEquals("aa010c000001e74123074502ff0000001c4a2c2c", h(RustCodec.alarmDisableFrame(seq)))

    @Test
    fun `gen4 advertising name frame`() =
        assertEquals("aa0e00d623074d00006e6f6f7000c2b1eab8", h(RustCodec.advertisingNameFrame(seq, "noop")))

    @Test
    fun `gen4 advertising name frame clamps to 24 utf8 bytes`() =
        assertEquals("aa22008423074d000061616161616161616161616161616161616161616161616100946857e1", h(RustCodec.advertisingNameFrame(seq, "a".repeat(40))))

    @Test
    fun `gen5 set config frame`() {
        val flag = Whoop5Config.enableR22Sequence[0]
        assertEquals("aa0130000001eb1123077801656e61626c655f7232325f7061636b6574730000000000000000000000000000320000000000000089dea9fe", h(RustCodec.setConfigFrame(seq, flag.name, flag.value)))
    }

    // --- Ground-truth vectors carried over from the deleted Kotlin-builder tests: real-hardware-acked
    //     WHOOP 5 offload commands (Goose) and the judes.club / Swift-parity goldens, at their original
    //     seq. The FFI now reproduces these exact bytes. ---

    @Test
    fun `whoop5 offload commands match the goose-acked bytes`() {
        assertEquals("aa0108000001e67123012200dbf3b335", h(RustCodec.commandFrame(true, 1, 34, byteArrayOf())!!))
        assertEquals("aa0108000001e6712302160075bedf8c", h(RustCodec.commandFrame(true, 2, 22, byteArrayOf())!!))
    }

    @Test
    fun `enable_r22_packets matches the judesclub swift golden at seq 1`() {
        assertEquals(
            "aa0130000001eb1123017801656e61626c655f7232325f7061636b65747300000000000000000000000000003200000000000000d2eeb0b7",
            h(RustCodec.setConfigFrame(1, "enable_r22_packets", 0x32)),
        )
    }

    @Test
    fun `maverick buzz + rev4 alarm + disable + run-alarm match the swift goldens at seq 1`() {
        assertEquals("aa0114000001e1e1230113012f980000000000000000000098cb83a5", h(RustCodec.buzzFrame(1)))
        assertEquals("aa011c000001e381230142040100f15365be0f2f980000000000000000071e00392f2ac9", h(RustCodec.alarmSetFrame(1, 1_700_000_000_123L)))
        assertEquals("aa010c000001e74123014502ff000000267ffc4f", h(RustCodec.alarmDisableFrame(1)))
        assertEquals("aa010c000001e741230144020100000017cd19e2", h(RustCodec.commandFrame(true, 1, 68, byteArrayOf(0x02, 0x01))!!))
    }
}

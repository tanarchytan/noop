package com.noop.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLE fragment-reassembly vectors for the (kept) [Reassembler]. The bytes->values decode moved to the
 * Rust whoop-rs codec, but reassembly stays here as transport, so these guard the hottest transport
 * component (every live + offloaded frame). The reassembler frames by SOF + length only, never CRC, so
 * the fixture uses a placeholder header CRC8 and a real CRC32 trailer.
 */
class ReassemblerTest {

    /** A length-correct WHOOP 4.0 COMMAND frame `[0xAA][len u16][crc8][inner][crc32]`, decode fixture only. */
    private fun cmd4(cmd: CommandNumber, payload: ByteArray, seq: Int): ByteArray {
        val inner = byteArrayOf(PacketType.COMMAND.rawValue.toByte(), seq.toByte(), cmd.rawValue.toByte()) + payload
        val length = inner.size + 4
        val out = ByteArray(1 + 3 + inner.size + 4)
        out[0] = 0xAA.toByte()
        out[1] = (length and 0xFF).toByte()
        out[2] = ((length ushr 8) and 0xFF).toByte()
        out[3] = 0 // header CRC8: reassembly is length-based, not CRC-checked, so a placeholder is fine
        inner.copyInto(out, 4)
        val c = Crc.crc32(inner)
        for (i in 0..3) out[4 + inner.size + i] = ((c ushr (8 * i)) and 0xFFL).toByte()
        return out
    }

    private fun fromHex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte() }

    /** A real type-40 REALTIME_DATA frame from a worn WHOOP 5 (hr=98, rr=[603,587], ts=1780916382). */
    private val whoop5RealtimeHex =
        "aa011800010022e128029ea0266aae4762025b024b020000000001005ed515dc"

    @Test
    fun reassembler_splitFrameAcrossFragments() {
        val frame = cmd4(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0), seq = 0)
        val r = Reassembler()
        val cut = frame.size / 2
        assertTrue(r.feed(frame.copyOfRange(0, cut)).isEmpty())
        val done = r.feed(frame.copyOfRange(cut, frame.size))
        assertEquals(1, done.size)
        assertArrayEquals(frame, done[0])
    }

    @Test
    fun reassembler_twoFramesInOneFragment() {
        val a = cmd4(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0), seq = 0)
        val b = cmd4(CommandNumber.GET_CLOCK, byteArrayOf(0), seq = 1)
        val r = Reassembler()
        val out = r.feed(a + b)
        assertEquals(2, out.size)
        assertArrayEquals(a, out[0])
        assertArrayEquals(b, out[1])
    }

    @Test
    fun reassembler_dropsLeadingGarbageBeforeSof() {
        val frame = cmd4(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0), seq = 0)
        val r = Reassembler()
        val out = r.feed(byteArrayOf(0x00, 0x11, 0x22) + frame)
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun reassembler_garbageLengthDoesNotStall_resyncsToNextFrame() {
        // A misaligned SOF with an impossibly large declared length must not wedge the stream waiting
        // for bytes that can never arrive over BLE; the reassembler drops the bad SOF and recovers the
        // real frame that follows. (Without the guard this is the live-HR-freeze failure mode.)
        val good = cmd4(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0), seq = 0)
        val garbage = byteArrayOf(0xAA.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00)
        val r = Reassembler()
        val out = r.feed(garbage + good)
        assertEquals(1, out.size)
        assertArrayEquals(good, out[0])
    }

    @Test
    fun reassembler_reassemblesWhenFedOneByteAtATime() {
        // Worst case for the offset/compact window: head advances one byte per feed(). Output must still
        // be the two exact frames, in order.
        val first = cmd4(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0), seq = 0)
        val second = cmd4(CommandNumber.GET_CLOCK, byteArrayOf(0), seq = 1)
        val r = Reassembler()
        val out = ArrayList<ByteArray>()
        for (b in first + second) out += r.feed(byteArrayOf(b))
        assertEquals(2, out.size)
        assertArrayEquals(first, out[0])
        assertArrayEquals(second, out[1])
    }

    @Test
    fun reassembler_resetDropsPartialFrame() {
        // reset() (called on every reconnect) must discard a buffered half-frame so stale bytes can't
        // corrupt the first frame of the next session.
        val frame = cmd4(CommandNumber.GET_BATTERY_LEVEL, byteArrayOf(0), seq = 0)
        val r = Reassembler()
        val cut = frame.size / 2
        assertTrue(r.feed(frame.copyOfRange(0, cut)).isEmpty())
        r.reset()
        val out = r.feed(frame)
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
    }

    @Test
    fun whoop5_reassembler_isFamilyAware() {
        // The WHOOP4 length rule decodes a bogus ~6 KB length for a 5/MG frame and never emits; the
        // family-aware reassembler frames it correctly (declLen @[2..4], total + 8).
        val frame = fromHex(whoop5RealtimeHex)
        val out = Reassembler(DeviceFamily.WHOOP5).feed(frame)
        assertEquals(1, out.size)
        assertArrayEquals(frame, out[0])
        assertTrue(Reassembler(DeviceFamily.WHOOP4).feed(frame).isEmpty())
    }
}

package com.noop.protocol

/*
 * BLE fragment reassembly.
 *
 * The bytes->values decode (per-type field parsing, CRC gating, command building) now lives in the
 * Rust whoop-rs codec, reached through RustAdapter; this file keeps only the transport-level job of
 * accumulating BLE notification fragments into complete frames before they cross into the decoder.
 */

/**
 * Accumulate BLE notification fragments into complete frames.
 *
 * A complete frame is `length + 4` bytes where `length` = u16 LE at buf[1..3]. Leading bytes before
 * the 0xAA SOF are discarded. Mirrors framing.py / Swift `Reassembler`.
 */
class Reassembler(private val family: DeviceFamily = DeviceFamily.WHOOP4) {
    // The backing store is a plain ByteArray plus a read cursor, not an ArrayList<Byte>. The old form
    // boxed each incoming byte and drained a completed frame with repeated removeAt(0) calls, every one
    // of which shifts the whole tail down by one slot. Draining a single frame was therefore O(n^2), and
    // the historical offload pushes thousands of ~1.9 KB records across a multi-night sync, so that cost
    // dominated. Here fragments are appended into [data], [head] simply advances past consumed bytes,
    // and the leftover tail is slid back to the front once per feed(). The emitted frames are identical
    // in bytes and order; ReassemblerTest's reassembler vectors hold that contract.
    private var data = ByteArray(0)
    private var head = 0   // index of the first byte not yet consumed
    private var tail = 0   // index one past the last valid byte

    /**
     * Drop any partial-frame remnant. Called on (re)connect so a stalled or garbage frame from one
     * session can't wedge the live stream in the next. The macOS BLEManager achieves the same by
     * reassigning a fresh `Reassembler` on every connect (BLEManager.swift:183).
     */
    fun reset() {
        head = 0
        tail = 0
    }

    /** Feed one fragment; return zero or more complete frames now available, in order. */
    fun feed(fragment: ByteArray): List<ByteArray> {
        append(fragment)
        val out = ArrayList<ByteArray>()
        while (true) {
            val sof = indexOfSof()
            if (sof < 0) {
                // No SOF left in the window: nothing here is salvageable, so drop it all.
                head = 0
                tail = 0
                break
            }
            // Skip any leading bytes ahead of the SOF instead of physically removing them.
            if (sof > head) head = sof
            val avail = tail - head
            if (avail < 4) break
            // Frame length is encoded differently per family: WHOOP4 = u16 @[1..3], total = length + 4;
            // WHOOP5/MG ("puffin") = declaredLength u16 @[2..4], total = declaredLength + 8 (it counts
            // the payload + the 4-byte CRC32 trailer, and has 2 extra header bytes). Using the WHOOP4
            // formula on a 5/MG frame decodes a bogus 6 KB length and the live stream never emits.
            val total: Int = if (family == DeviceFamily.WHOOP5) {
                ((data[head + 2].toInt() and 0xFF) or ((data[head + 3].toInt() and 0xFF) shl 8)) + 8
            } else {
                ((data[head + 1].toInt() and 0xFF) or ((data[head + 2].toInt() and 0xFF) shl 8)) + 4
            }
            if (total > MAX_FRAME_BYTES) {
                // A corrupt or misaligned SOF decodes an impossibly large length and we'd wait forever
                // for bytes that can never arrive over BLE — the live stream would freeze until a
                // reconnect. The largest real WHOOP frame is ~1920 B, so anything past the 8 KB ceiling
                // is garbage: drop this 0xAA and resync to the next one.
                head += 1
                continue
            }
            if (avail < total) break
            out.add(data.copyOfRange(head, head + total))
            head += total
        }
        compact()
        return out
    }

    /** Index of the first 0xAA at or after [head] in the live window, or -1 if none remain. */
    private fun indexOfSof(): Int {
        var i = head
        while (i < tail) {
            if (data[i] == 0xAA.toByte()) return i
            i++
        }
        return -1
    }

    /** Append a fragment, doubling the backing array when it would otherwise overflow. */
    private fun append(fragment: ByteArray) {
        if (fragment.isEmpty()) return
        if (tail + fragment.size > data.size) {
            var cap = if (data.isEmpty()) 256 else data.size
            while (cap < tail + fragment.size) cap = cap shl 1
            data = data.copyOf(cap)
        }
        System.arraycopy(fragment, 0, data, tail, fragment.size)
        tail += fragment.size
    }

    /**
     * Slide the unconsumed tail back to offset 0 so [head] can't drift forever and the array stays
     * small. compact() runs at the end of every feed(), so [head] is always 0 when the next append()
     * lands. The leftover is at most one in-progress frame (< MAX_FRAME_BYTES), so the move is bounded.
     */
    private fun compact() {
        if (head == 0) return
        val remaining = tail - head
        if (remaining > 0) System.arraycopy(data, head, data, 0, remaining)
        head = 0
        tail = remaining
    }

    private companion object {
        /** ~4× the largest observed WHOOP frame (~1920 B raw/historical); above this is a bad length. */
        const val MAX_FRAME_BYTES = 8192
    }
}

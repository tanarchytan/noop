package com.noop.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Offline safe-trim parity over a real 5.0 offload capture (BackfillCaptureJsonl). Proves the rewired
 * offload path — [classifyHistoricalMeta] now decodes METADATA through whoop-rs [RustCodec.decodeMetadata]
 * — reproduces the meta_type + HISTORY_END unix/trim_cursor the Kotlin frame layer wrote at capture time.
 * A wrong trim cursor deletes un-drained history, so this is the #1 offload-safety gate.
 *
 * The whole-capture sweep self-skips unless WHOOP_CAPTURE points at the .jsonl (kept out of the repo; the
 * frames are real), so it proves nothing on an ordinary run. The committed single-frame fixture below runs
 * ALWAYS and carries the same claim on one real HISTORY_END: without it, gutting [classifyHistoricalMeta]
 * to return `Other` for every frame left the whole suite green. Loads the host libwhoop_ffi via JNA
 * (buildRustHostDll), like the other FFI parity tests.
 */
class CaptureTrimParityTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    /** Real worn WHOOP 5 HISTORY_END: meta_type 2, unix 1784236473, trim cursor 113405. */
    private val historyEndHex =
        "aa011c00010023d1319102b949596a705d3b000000fdba010010000000000000f269faec"

    /** Overwrite frame bytes and re-stamp the CRC32 over frame[8 until size-4] so the integrity gate passes. */
    private fun patched(vararg pairs: Pair<Int, Int>): ByteArray {
        val f = hex(historyEndHex)
        for ((at, v) in pairs) f[at] = v.toByte()
        val end = f.size - 4
        val c = java.util.zip.CRC32().apply { update(f, 8, end - 8) }.value
        for (i in 0..3) f[end + i] = ((c shr (8 * i)) and 0xFF).toByte()
        return f
    }

    /**
     * The fixture-backed half: one committed real HISTORY_END, no environment variable. Pins the cursor
     * and the frame's own unix at their captured values and walks all four classifier arms off the same
     * frame with only the meta-type byte moved.
     */
    @Test
    fun `a committed history_end frame classifies with its captured unix and trim`() {
        val meta = classifyHistoricalMeta(hex(historyEndHex), DeviceFamily.WHOOP5)
        assertTrue("a real HISTORY_END must classify as End, got $meta", meta is HistoricalMeta.End)
        val end = meta as HistoricalMeta.End
        assertEquals("HISTORY_END unix", 1_784_236_473L, end.unix)
        assertEquals("HISTORY_END trim cursor", 113_405L, end.trim)

        assertTrue(classifyHistoricalMeta(patched(10 to 1), DeviceFamily.WHOOP5) is HistoricalMeta.Start)
        assertTrue(classifyHistoricalMeta(patched(10 to 3), DeviceFamily.WHOOP5) is HistoricalMeta.Complete)
        assertTrue(classifyHistoricalMeta(patched(10 to 9), DeviceFamily.WHOOP5) is HistoricalMeta.Other)

        // Integrity gate: the same frame with a byte moved and the CRC left stale must not classify, so a
        // garbled or forged peer cannot advance the trim for history we never stored.
        val forged = hex(historyEndHex)
        forged[21] = 0x00
        assertTrue(classifyHistoricalMeta(forged, DeviceFamily.WHOOP5) is HistoricalMeta.Other)
    }

    @Test
    fun `history_end trim and unix match the capture through the rust metadata path`() {
        val path = System.getenv("WHOOP_CAPTURE") ?: return // self-skip without the capture
        val file = File(path)
        if (!file.exists()) return

        var metaAll = 0
        var metaTypeOk = 0
        var endTotal = 0
        var endOk = 0
        file.forEachLine { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachLine
            val o = JSONObject(line)
            if (o.optString("type_name") != "METADATA") return@forEachLine
            val parsed = o.getJSONObject("parsed")
            val frame = hex(o.getString("hex"))
            val meta = classifyHistoricalMeta(frame, DeviceFamily.WHOOP5)
            val label = parsed.getString("meta_type")
            metaAll++
            val classified = when (meta) {
                is HistoricalMeta.Start -> label.startsWith("HISTORY_START")
                is HistoricalMeta.Complete -> label.startsWith("HISTORY_COMPLETE")
                is HistoricalMeta.End -> label.startsWith("HISTORY_END")
                is HistoricalMeta.Other -> false
            }
            if (classified) metaTypeOk++
            if (label.startsWith("HISTORY_END")) {
                endTotal++
                val wantUnix = parsed.getLong("unix") and 0xFFFFFFFFL
                val wantTrim = parsed.getLong("trim_cursor") and 0xFFFFFFFFL
                val end = meta as HistoricalMeta.End
                if (end.unix == wantUnix && end.trim == wantTrim) endOk++
            }
        }

        println("CaptureTrimParity: meta_type $metaTypeOk/$metaAll, HISTORY_END unix+trim $endOk/$endTotal")
        // Floors: an equality between two counters both at zero passes while proving nothing, so a
        // capture that carries no METADATA (or no HISTORY_END) must fail rather than read as parity.
        assertTrue("capture carries no METADATA frames", metaAll > 0)
        assertTrue("capture carries no HISTORY_END frame — the trim cursor is unchecked", endTotal > 0)
        assertEquals("metadata classified", metaAll, metaTypeOk)
        assertEquals("HISTORY_END trim+unix parity", endTotal, endOk)
    }
}

package com.noop.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Offline safe-trim parity over a real 5.0 offload capture (BackfillCaptureJsonl). Proves the rewired
 * offload path — [classifyHistoricalMeta] now decodes METADATA through whoop-rs [RustCodec.decodeMetadata]
 * — reproduces the meta_type + HISTORY_END unix/trim_cursor the Kotlin frame layer wrote at capture time.
 * A wrong trim cursor deletes un-drained history, so this is the #1 offload-safety gate.
 *
 * Self-skips unless WHOOP_CAPTURE points at the .jsonl (kept out of the repo; the frames are real). Loads
 * the host libwhoop_ffi via JNA (buildRustHostDll), like the other FFI parity tests.
 */
class CaptureTrimParityTest {

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private fun paren(s: String): Int = s.substringAfterLast('(').substringBefore(')').toInt()

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
            if (line.isBlank()) return@forEachLine
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
        assertEquals("metadata classified", metaAll, metaTypeOk)
        assertEquals("HISTORY_END trim+unix parity", endTotal, endOk)
    }
}

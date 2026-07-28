package com.noop.ingest

import java.io.File

/**
 * Appends historical records to a JSONL file, one JSON object per frame: the decoded fields AND the
 * frame's own bytes, so nothing the codec did not name is lost.
 *
 * Fed from the decode funnel in `extractHistoricalStreams`, which drops every key it does not name and
 * skips a frame that decodes to nothing. Both are captured here instead: a frame whose layout has no
 * decoder yet writes its bytes with no fields, which is the only record of an unmapped layout.
 */
object HistoryRecordSink {

    const val FILE = "noop-records.jsonl"
    const val PREV_FILE = "$FILE.1"

    /** Soft cap per generation; one previous generation is kept, so worst case is twice this on disk. */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** Appends one frame. Never throws: a diagnostic must not be able to fail an ingest. */
    fun append(dir: File, deviceId: String, fields: Map<String, Any?>?, frame: ByteArray) {
        runCatching {
            dir.mkdirs()
            val f = File(dir, FILE)
            if (f.length() > MAX_BYTES) {
                val prev = File(dir, PREV_FILE)
                prev.delete()
                f.renameTo(prev)
            }
            f.appendText(line(deviceId, fields, frame) + "\n")
        }
    }

    /**
     * One frame as a JSON object: `device`, then every decoded field, then `frame` as hex. Field order
     * puts the bytes last so a truncated final line still yields the identifying fields.
     */
    internal fun line(deviceId: String, fields: Map<String, Any?>?, frame: ByteArray): String {
        val sb = StringBuilder(512)
        sb.append('{').append(str("device")).append(':').append(str(deviceId))
        for ((k, v) in fields.orEmpty()) {
            if (v == null || k == "device" || k == "frame") continue
            sb.append(',').append(str(k)).append(':').append(value(v))
        }
        // A frame the codec could not read carries no fields at all, so mark it rather than leaving the
        // reader to infer an unmapped layout from an absence.
        if (fields == null) sb.append(',').append(str("decoded")).append(":false")
        sb.append(',').append(str("frame")).append(':').append(str(hex(frame)))
        return sb.append('}').toString()
    }

    private fun hex(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    private val HEX = "0123456789abcdef".toCharArray()

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Int, is Long, is Short, is Byte -> v.toString()
        is Float -> value(v.toDouble())
        // A non-finite double is not JSON; null tells the reader the field was unusable.
        is Double -> if (v.isFinite()) v.toString() else "null"
        is ByteArray -> str(hex(v))
        is IntArray -> v.joinToString(",", "[", "]")
        is List<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> str(v.toString())
    }

    private fun str(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}

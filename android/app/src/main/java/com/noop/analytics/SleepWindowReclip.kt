package com.noop.analytics

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reshapes a sleep session's stored stage breakdown to a hand-corrected [newStart]..[newEnd] window, so a
 * bed-time/wake-time edit updates the hypnogram and stage footer, not just the label. Pure, deterministic,
 * no store/I-O. An onset-only edit (newStart moves) must drop stages before the new bed time, not keep them.
 * Segment-array nights (computed) are clipped to [newStart]..[newEnd], appending a trailing "wake" segment
 * if the window grew. Minute-dict nights (imported, no timeline) shift by the duration delta, trimming
 * tail-most stages (awake→light→rem→deep) when shortened or adding to awake when lengthened. Returns
 * re-encoded JSON in the same shape, or null if nothing is usable.
 */
object SleepWindowReclip {

    fun reclip(stagesJSON: String?, sessionStart: Long, oldEnd: Long, newStart: Long, newEnd: Long): String? {
        stagesJSON ?: return null
        return try {
            when {
                stagesJSON.trimStart().startsWith("[") ->
                    reclipSegments(JSONArray(stagesJSON), newStart, newEnd)
                stagesJSON.trimStart().startsWith("{") ->
                    reclipMinutes(JSONObject(stagesJSON), (newEnd - newStart) - (oldEnd - sessionStart))
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w("SleepReclip", "reclipFromEpoch: parse failed, returning null", e)
            null
        }
    }

    private fun reclipSegments(arr: JSONArray, newStart: Long, newEnd: Long): String? {
        val out = JSONArray()
        var maxEnd = newStart
        for (i in 0 until arr.length()) {
            val seg = arr.optJSONObject(i) ?: continue
            val start = seg.optLong("start", -1)
            val end = seg.optLong("end", -1)
            val stage = seg.optString("stage", "")
            if (start < 0 || end <= start || stage.isEmpty()) continue
            if (start >= newEnd) continue                        // wholly after new wake → drop
            if (end <= newStart) continue                        // wholly before the new bed time → drop
            val clippedStart = maxOf(start, newStart)            // clip the segment spanning the new bed time
            val clippedEnd = minOf(end, newEnd)
            out.put(JSONObject().put("start", clippedStart).put("end", clippedEnd).put("stage", stage))
            if (clippedEnd > maxEnd) maxEnd = clippedEnd
        }
        if (newEnd > maxEnd && maxEnd >= newStart) {             // window grew → trailing awake
            out.put(JSONObject().put("start", maxEnd).put("end", newEnd).put("stage", "wake"))
        }
        // If every segment trimmed away, emit a single wake covering the corrected window so the
        // store's COALESCE doesn't keep the old segments extending past the new wake time.
        if (out.length() == 0 && newEnd > newStart) {
            out.put(JSONObject().put("start", newStart).put("end", newEnd).put("stage", "wake"))
        }
        return if (out.length() > 0) out.toString() else null
    }

    private fun reclipMinutes(dict: JSONObject, deltaSeconds: Long): String? {
        var awake = dict.optDouble("awake", 0.0)
        var light = dict.optDouble("light", 0.0)
        var deep = dict.optDouble("deep", 0.0)
        var rem = dict.optDouble("rem", 0.0)
        val deltaMin = deltaSeconds / 60.0
        if (deltaMin >= 0) {
            awake += deltaMin                                     // extra time in bed = awake
        } else {
            var trim = -deltaMin
            fun cut(v: Double): Double { val c = minOf(v, maxOf(trim, 0.0)); trim -= c; return v - c }
            awake = cut(awake); light = cut(light); rem = cut(rem); deep = cut(deep)
        }
        val total = awake + light + deep + rem
        if (total <= 0.0) return null
        return JSONObject()
            .put("awake", awake).put("light", light).put("deep", deep).put("rem", rem)
            .toString()
    }
}

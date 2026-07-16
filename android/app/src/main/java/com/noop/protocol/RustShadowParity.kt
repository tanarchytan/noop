package com.noop.protocol

/**
 * Running match/mismatch tally for the Rust-codec SHADOW decode (default OFF, see PuffinExperiment).
 * The shadow diff at the offload / live / response seams decodes each frame a SECOND time through the
 * whoop-rs FFI and compares field-by-field against the authoritative Kotlin decode; this holds the
 * per-field-type counters and the first few mismatch descriptions so Test Centre can show a parity
 * report from a real device session. Never touched on the default path (only when the flag is on).
 */
object RustShadowParity {

    /** First N mismatch descriptions kept for the report (bounded; older ones are not overwritten). */
    private const val MAX_NOTES = 40

    private class Counter {
        var match: Long = 0
        var mismatch: Long = 0
    }

    private val lock = Any()
    private val counters = LinkedHashMap<String, Counter>()
    private val notes = ArrayList<String>()
    private var framesCompared: Long = 0
    private var nativeErrors: Long = 0

    /** Record one field comparison outcome for [field] (e.g. "hr", "rr", "gravity", "battery"). */
    fun record(field: String, isMatch: Boolean) = synchronized(lock) {
        val c = counters.getOrPut(field) { Counter() }
        if (isMatch) c.match++ else c.mismatch++
    }

    /** Note a mismatch detail (kept up to [MAX_NOTES]); pairs with a [record] mismatch. */
    fun note(detail: String) = synchronized(lock) {
        if (notes.size < MAX_NOTES) notes.add(detail)
    }

    /** One frame was diffed (any seam). */
    fun frameCompared() = synchronized(lock) { framesCompared++ }

    /** The shadow decode threw (native load / decode error) — counted, never crashes the caller. */
    fun nativeError() = synchronized(lock) { nativeErrors++ }

    /** Zero every counter and clear the notes. */
    fun reset() = synchronized(lock) {
        counters.clear()
        notes.clear()
        framesCompared = 0
        nativeErrors = 0
    }

    /** True once any comparison has been recorded (drives the "no data yet" UI state). */
    fun hasData(): Boolean = synchronized(lock) { framesCompared > 0 || counters.isNotEmpty() }

    /** A human-readable multi-line report for the Test Centre card / bug bundle. */
    fun report(): String = synchronized(lock) {
        val sb = StringBuilder()
        sb.append("Rust shadow parity — frames diffed: $framesCompared")
        if (nativeErrors > 0) sb.append(", native errors: $nativeErrors")
        sb.append('\n')
        if (counters.isEmpty()) {
            sb.append("(no field comparisons yet)")
        } else {
            var totalMismatch = 0L
            for ((field, c) in counters) {
                totalMismatch += c.mismatch
                sb.append("  $field: ${c.match} match / ${c.mismatch} mismatch\n")
            }
            sb.append(if (totalMismatch == 0L) "ALL FIELDS BYTE-IDENTICAL" else "MISMATCHES PRESENT ($totalMismatch)")
            if (notes.isNotEmpty()) {
                sb.append("\nfirst mismatches:")
                for (n in notes) sb.append("\n  ").append(n)
            }
        }
        sb.toString()
    }
}

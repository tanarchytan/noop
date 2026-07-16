package com.noop.protocol

/**
 * Running match/mismatch tally for the Rust-codec decode comparator (default OFF, see PuffinExperiment).
 * Shared by the SHADOW seam (whoop-rs decodes a frame a SECOND time and diffs it, storing nothing) and
 * the PRIMARY seam (whoop-rs is the authoritative writer and the Kotlin decode feeds this comparator).
 * Holds the per-field-type counters and the first few mismatch descriptions so Test Centre can show a
 * parity report from a real device session. Never touched on the default path (only when a flag is on).
 *
 * A per-field delta is NOT automatically a defect. Where adjudication chose whoop-rs over the old Kotlin
 * (the intended-deltas: the v18/v24 gravity |g|-gate, the event charging-bit residual), a difference is
 * correct-by-design → classified EXPECTED. Every other mismatch is UNEXPECTED (a real diff to triage).
 * Both counts are surfaced separately; there is no blanket "all fields byte-identical" claim.
 */
object RustShadowParity {

    /** First N mismatch descriptions kept for the report (bounded; older ones are not overwritten). */
    private const val MAX_NOTES = 40

    /**
     * Field names whose Rust-vs-Kotlin delta is an ADJUDICATED intended improvement (EXPECTED), not a bug:
     *  - gravity: whoop-rs gates v18/v24 |g| to [0.5,1.5] and drops non-finite/out-of-range vectors the
     *    ungated Kotlin path would store (cleaner sleep-stager input). On real data this never fires.
     *  - event.charging: the widened Rust event carries charging as a bool, so Kotlin's raw-byte "ch<=1"
     *    omit-gate isn't reproduced; only diverges for a frame byte >=2, which no real frame exhibits.
     * Matched by prefix so both the offload ("gravity") and live ("live.gravity") field labels classify.
     */
    private val EXPECTED_PREFIXES = listOf("gravity", "event.charging")

    private fun isExpected(field: String): Boolean = EXPECTED_PREFIXES.any { field == it || field.endsWith(it) }

    private class Counter {
        var match: Long = 0
        var expectedMismatch: Long = 0
        var unexpectedMismatch: Long = 0
    }

    private val lock = Any()
    private val counters = LinkedHashMap<String, Counter>()
    private val notes = ArrayList<String>()
    private var framesCompared: Long = 0
    private var nativeErrors: Long = 0
    private var primary = false

    /** Note the active mode for the report header: true once the PRIMARY (authoritative-Rust) seam ran. */
    fun setPrimary(isPrimary: Boolean) = synchronized(lock) { if (isPrimary) primary = true }

    /**
     * Record one field comparison outcome for [field] (e.g. "hr", "rr", "gravity", "battery"). A mismatch
     * is split into EXPECTED (an adjudicated intended-delta, see [EXPECTED_PREFIXES]) vs UNEXPECTED.
     */
    fun record(field: String, isMatch: Boolean) = synchronized(lock) {
        val c = counters.getOrPut(field) { Counter() }
        if (isMatch) c.match++ else if (isExpected(field)) c.expectedMismatch++ else c.unexpectedMismatch++
    }

    /** Note a mismatch detail (kept up to [MAX_NOTES]); pairs with a [record] mismatch. */
    fun note(detail: String) = synchronized(lock) {
        if (notes.size < MAX_NOTES) notes.add(detail)
    }

    /** One frame was diffed (any seam). */
    fun frameCompared() = synchronized(lock) { framesCompared++ }

    /** The Rust decode threw (native load / decode error) — counted; the caller falls back to Kotlin. */
    fun nativeError() = synchronized(lock) { nativeErrors++ }

    /** Zero every counter and clear the notes. */
    fun reset() = synchronized(lock) {
        counters.clear()
        notes.clear()
        framesCompared = 0
        nativeErrors = 0
        primary = false
    }

    /** True once any comparison has been recorded (drives the "no data yet" UI state). */
    fun hasData(): Boolean = synchronized(lock) { framesCompared > 0 || counters.isNotEmpty() }

    /** A human-readable multi-line report for the Test Centre card / bug bundle. */
    fun report(): String = synchronized(lock) {
        val sb = StringBuilder()
        val mode = if (primary) "primary (whoop-rs authoritative)" else "shadow"
        sb.append("Rust $mode parity — frames diffed: $framesCompared")
        if (nativeErrors > 0) sb.append(", native fallbacks: $nativeErrors")
        sb.append('\n')
        if (counters.isEmpty()) {
            sb.append("(no field comparisons yet)")
        } else {
            var expected = 0L
            var unexpected = 0L
            for ((field, c) in counters) {
                expected += c.expectedMismatch
                unexpected += c.unexpectedMismatch
                val d = c.expectedMismatch + c.unexpectedMismatch
                val tag = if (d == 0L) "" else " (${c.expectedMismatch} expected / ${c.unexpectedMismatch} unexpected)"
                sb.append("  $field: ${c.match} match / $d delta$tag\n")
            }
            sb.append("EXPECTED deltas (adjudicated whoop-rs win): $expected · UNEXPECTED (triage): $unexpected")
            if (notes.isNotEmpty()) {
                sb.append("\nfirst deltas:")
                for (n in notes) sb.append("\n  ").append(n)
            }
        }
        sb.toString()
    }
}

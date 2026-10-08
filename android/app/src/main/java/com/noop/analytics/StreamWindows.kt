package com.noop.analytics

/**
 * Read two overlapping [from, to] ranges of one ts-ascending stream with ONE query where possible.
 *
 * Storage only: rows are fetched, never computed on. The result is identical to calling [read] once per
 * range, because both ranges are inclusive and each read is `ORDER BY ts ASC LIMIT limit`: a superset read
 * sliced by ts equals the per-range read as long as the superset read was not truncated by [limit]. If it
 * was (size >= limit) the per-range reads are used instead, so truncation behaves exactly as before.
 */
internal object StreamWindows {
    suspend fun <T> readTwo(
        a: LongRange,
        b: LongRange,
        limit: Int,
        ts: (T) -> Long,
        read: suspend (from: Long, to: Long, limit: Int) -> List<T>,
    ): Pair<List<T>, List<T>> {
        val all = read(minOf(a.first, b.first), maxOf(a.last, b.last), limit)
        if (all.size >= limit) return read(a.first, a.last, limit) to read(b.first, b.last, limit)
        return all.filter { ts(it) in a } to all.filter { ts(it) in b }
    }
}

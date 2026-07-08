package com.noop.bp

/**
 * A small in-memory ring buffer holding the tail of the strap's v26 optical PPG waveform (24 Hz), which
 * noop decodes for heart rate and otherwise discards. The opt-in blood-pressure feature reads the most
 * recent window from here and sends it to the cloud for a cuffless estimate. Bounded + best-effort — no
 * persistence, cleared on process death. Thread-safe (offload decode runs off the main thread).
 */
object PpgBuffer {
    /** ~30 s at 24 Hz is plenty for a BP window and keeps the footprint tiny. */
    private const val CAPACITY = 24 * 30

    private val lock = Any()
    private val ring = ArrayDeque<Int>(CAPACITY)
    @Volatile var sampleRateHz: Int = 24
        private set

    /** Append newly-decoded samples (raw ADC), dropping the oldest past [CAPACITY]. */
    fun record(samples: List<Int>, fs: Int) {
        if (samples.isEmpty()) return
        synchronized(lock) {
            sampleRateHz = fs
            for (v in samples) {
                if (ring.size >= CAPACITY) ring.removeFirst()
                ring.addLast(v)
            }
        }
    }

    /** The most recent [seconds] of PPG samples in time order, or null if we don't have enough yet. */
    fun window(seconds: Int = 12): List<Int>? = synchronized(lock) {
        val need = sampleRateHz * seconds
        if (ring.size < sampleRateHz * 3) return null // need at least a few seconds of signal
        val list = ring.toList()
        return if (list.size <= need) list else list.subList(list.size - need, list.size)
    }

    fun clear() = synchronized(lock) { ring.clear() }
}

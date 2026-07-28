package com.noop.ble

/**
 * Capped exponential reconnect backoff. A strap that's genuinely out of range must not hammer BLE
 * with a fixed-3s rescan loop; the delay grows 3 → 6 → 12 → 24 → 48 → 60s and then holds at the 60s
 * ceiling. Pure and side-effect-free, unit-testable in isolation from the GATT machinery;
 * [WhoopBleClient] owns the attempt counter and resets it on a real connect.
 */
internal object ReconnectBackoff {

    /** First and minimum delay before a reconnect attempt. */
    const val BASE_DELAY_MS = 3_000L

    /** Ceiling — the schedule never waits longer than this between attempts. */
    const val MAX_DELAY_MS = 60_000L

    /**
     * Delay before the [attempt]-th reconnect (1-based: 1→3s, 2→6s, 3→12s, 4→24s, 5→48s, 6+→60s).
     * Values <= 1 coerce to the base delay instead of a sub-base or negative wait. `3000 shl n`
     * would overflow a Long and blow past the 60s cap for large n, so attempt >= 6 short-circuits
     * to [MAX_DELAY_MS]; the largest shift used is `3000 shl 4` (attempt 5 = 48s).
     */
    fun nextDelayMs(attempt: Int): Long {
        val n = attempt.coerceAtLeast(1)
        if (n >= 6) return MAX_DELAY_MS
        val delay = BASE_DELAY_MS shl (n - 1)   // 3000, 6000, 12000, 24000, 48000
        return delay.coerceAtMost(MAX_DELAY_MS)
    }
}

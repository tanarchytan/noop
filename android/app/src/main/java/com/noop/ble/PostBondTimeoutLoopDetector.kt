package com.noop.ble

/**
 * Detects a WHOOP 4 "bond-loop": the strap bonds, then the encrypted link drops ~1s later with a
 * CONNECTION TIMEOUT (Android `GATT_CONN_TIMEOUT` / `0x08`), auto-rescan reconnects, it bonds again,
 * and dies again — an endless bond-then-timeout cycle that never settles.
 *
 * The tell is a TIMEOUT drop shortly after a GENUINE bond; a bond that survives well past the window
 * is healthy and breaks the streak, since links flap for benign reasons later on and a late drop must
 * not be blamed on the bond. One quick drop is noise; >= [tripThreshold] CONSECUTIVE bond-then-quick-
 * timeout cycles trips it, and the client then surfaces the existing re-pair guide (`reconnectGuide`).
 *
 * Pure value type, unit-testable without a BLE seam — same shape as [EmptySyncTracker].
 */
class PostBondTimeoutLoopDetector(
    /**
     * How many consecutive bond-then-quick-timeout cycles before we surface the re-pair guide.
     * 2 (not 1): one quick post-bond drop is noise; two in a row is the loop, not a fluke.
     */
    private val tripThreshold: Int = 2,
    /**
     * A timeout counts as "right after bonding" only within this many milliseconds of the bond; a drop
     * well into a healthy session is unrelated and must not count. Generous vs the radio detector's 20s,
     * since the loop's signature is a near-immediate (~1s) drop but pre-loop links can limp a few seconds.
     */
    val quickTimeoutWindowMs: Long = 8_000L,
) {
    var consecutiveBondTimeouts = 0
        private set

    /** True once we've tripped: the client has surfaced (or should surface) the re-pair guide. */
    var tripped = false
        private set

    /**
     * A connection ended. [wasBonded] = reached a genuine encrypted bond this connection; [msSinceBond] =
     * ms from bond to end (null if never bonded); [timedOut] = looks like a connection timeout vs an
     * intentional disconnect or clean close. Returns true only if THIS event freshly tripped the loop.
     */
    fun connectionEnded(wasBonded: Boolean, msSinceBond: Long?, timedOut: Boolean): Boolean {
        // Only a timeout that lands within the window after we actually bonded is evidence of the loop.
        // Anything else (never bonded, non-timeout close, a drop long after a healthy bond) breaks the
        // streak — a single healthy spell should clear prior suspicion.
        val bondThenQuickTimeout = wasBonded && timedOut &&
            (msSinceBond != null && msSinceBond <= quickTimeoutWindowMs)
        if (!bondThenQuickTimeout) {
            consecutiveBondTimeouts = 0
            return false
        }
        consecutiveBondTimeouts += 1
        if (!tripped && consecutiveBondTimeouts >= tripThreshold) {
            tripped = true
            return true        // freshly tripped — caller surfaces the re-pair guide once
        }
        return false
    }

    /**
     * Clear all suspicion: a clean session is flowing, or the user explicitly disconnected. Lets a
     * transient bond hiccup recover instead of permanently flagging the link as bond-looping.
     */
    fun reset() {
        consecutiveBondTimeouts = 0
        tripped = false
    }
}

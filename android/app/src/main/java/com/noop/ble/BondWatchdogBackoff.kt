package com.noop.ble

/**
 * Paces the WHOOP 4.0 bond-handshake watchdog so a genuinely SLOW bond gets progressively more time
 * before the link is bounced, and a strap whose handshake never completes stops bouncing after a capped
 * number of tries instead of looping forever.
 *
 * Distinct from the [PostBondTimeoutLoopDetector] loop: that one is a strap that DOES reach a genuine
 * encrypted bond and then drops ~1s later with a real GATT_CONN_TIMEOUT (status 0x08). Here the handshake
 * never LANDS inside the fixed 7s window at all - the CCCD subscribe + confirmed bond write is slow on
 * some phone/strap pairings, the watchdog fires mid-handshake and bounces the link with
 * `gatt.disconnect()`, reported as GATT_CONN_TERMINATE_LOCAL_HOST (status 22 / 0x16, NOT the 0x08 the
 * other detector keys on). Because STATE_CONNECTED is reached every cycle,
 * [WhoopBleClient.resetReconnectBackoff] zeroes the reconnect backoff each pass, so the bond phase itself
 * never backs off - bond -> 7s -> bounce -> reconnect -> bond -> 7s -> bounce, indefinitely.
 *
 * Three parts, all decided here so they're unit-testable without a BLE seam (same shape as
 * [PostBondTimeoutLoopDetector] / [BondRefusalGiveUp]):
 *
 *  1. ESCALATE the watchdog window per consecutive bounce ([windowMsForAttempt]) so a slow-but-healthy
 *     handshake gets more time on the second/third try instead of being bounced forever at a too-tight
 *     7s. Capped so a truly dead handshake can't wait minutes.
 *  2. COUNT consecutive bounces ([recordBounce]); the streak survives the intermediate STATE_CONNECTED
 *     and is cleared only by a genuine bond ([reset]) or an explicit user reconnect.
 *  3. GIVE UP after [giveUpThreshold] bounces ([shouldGiveUp]): stop bouncing, surface the existing
 *     re-pair guide and pause auto-reconnect, so a strap that genuinely can't finish the handshake stops
 *     draining the battery.
 */
class BondWatchdogBackoff(
    /** The first (tightest) watchdog window, matching the historical fixed 7s timeout. */
    private val baseWindowMs: Long = 7_000L,
    /** Extra time added to the window per prior bounce (attempt 1 → base, 2 → base+step, ...). */
    private val stepMs: Long = 3_000L,
    /** Ceiling — a slow handshake gets at most this long before a bounce, so a dead one can't hang. */
    private val maxWindowMs: Long = 16_000L,
    /**
     * Consecutive bounces before we STOP bouncing and hand off to the re-pair guide + auto-reconnect
     * pause. 4, not 2: each bounce here also costs a full reconnect + rediscover, so a slow-but-recoverable
     * handshake gets several escalating windows (7s, 10s, 13s, 16s) before being declared stuck - generous
     * enough that a genuinely healthy slow bond lands first.
     */
    private val giveUpThreshold: Int = 4,
) {
    /** Consecutive bond-watchdog bounces with no genuine bond in between. */
    var consecutiveBounces = 0
        private set

    /** True once [giveUpThreshold] bounces have accrued — the caller must stop bouncing and hand off. */
    var gaveUp = false
        private set

    /**
     * The watchdog window to arm for the NEXT handshake, given the bounces seen so far. Escalates from
     * [baseWindowMs] by [stepMs] per prior bounce, capped at [maxWindowMs]. With 0 bounces this is the
     * historical 7s, so the first, common healthy connect is UNCHANGED.
     */
    fun currentWindowMs(): Long = windowMsForAttempt(consecutiveBounces)

    /** Pure window schedule: [priorBounces] = how many bounces have already happened (0-based). */
    fun windowMsForAttempt(priorBounces: Int): Long {
        val n = priorBounces.coerceAtLeast(0)
        val window = baseWindowMs + stepMs * n
        return window.coerceAtMost(maxWindowMs)
    }

    /**
     * Record one bond-watchdog bounce (the handshake didn't land inside its window). Returns true if THIS
     * bounce freshly crossed [giveUpThreshold] — the caller then stops bouncing and surfaces the re-pair
     * guide + pauses auto-reconnect exactly once.
     */
    fun recordBounce(): Boolean {
        consecutiveBounces += 1
        if (!gaveUp && consecutiveBounces >= giveUpThreshold) {
            gaveUp = true
            return true
        }
        return false
    }

    /** Whether the caller should give up bouncing now (already at/over the threshold). */
    fun shouldGiveUp(): Boolean = gaveUp

    /**
     * Clear the streak: a genuine bond landed, or the user explicitly reconnected. Re-arms the tight
     * base window and lets a later slow handshake escalate afresh.
     */
    fun reset() {
        consecutiveBounces = 0
        gaveUp = false
    }
}

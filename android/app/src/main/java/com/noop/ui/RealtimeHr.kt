package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

// MARK: - RealtimeHr — who wants the strap's live HR stream, and for how long
//
// Arming TOGGLE_REALTIME_HR streams 1 Hz off the strap's own battery and pins the GATT link at
// CONNECTION_PRIORITY_HIGH, so the decision is split into two inputs neither of which can override the
// other: the INTENT (which screen or session asked — [RealtimeHrArbiter.request]) and the REACHABILITY
// (is the app's UI on screen — [RealtimeHrArbiter.setVisible]). A SCREEN want additionally carries a
// lease that a started composition renews, so a want nobody releases expires on its own.
// [AppViewModel] owns the single arbiter and mirrors [RealtimeHrArbiter.armed] onto [com.noop.ble.WhoopBleClient].

/**
 * Every consumer that may ask for the live HR stream, and whether its want survives the app leaving the
 * foreground. A session keeps coaching a pocketed phone, so it does; a screen nobody can see never does.
 */
enum class RealtimeHrOwner(val backgroundEligible: Boolean) {
    HEALTH(backgroundEligible = false),
    HRV_SNAPSHOT(backgroundEligible = false),
    LIVE_WORKOUT(backgroundEligible = true),
    LIVE_SESSION(backgroundEligible = true),
}

/**
 * The live-HR want set. Pure and clock-injected, so the whole arm/disarm decision is JVM-testable
 * without a strap. Every mutator returns the resulting [armed] state for the caller to mirror.
 */
class RealtimeHrArbiter(private val leaseMs: Long = SCREEN_LEASE_MS) {

    private class Held(var count: Int, var leaseUntilMs: Long)

    private val held = LinkedHashMap<RealtimeHrOwner, Held>()

    /** Whether the app's UI is on screen. Seeded true — the arbiter is built by a visible Activity's
     *  view-model, and [setVisible] takes over from that Activity's first lifecycle callback. */
    private var visible = true

    /** True while TOGGLE_REALTIME_HR should be armed. */
    var armed: Boolean = false
        private set

    /** A screen or session wants the stream. Also starts the owner's lease. */
    fun request(owner: RealtimeHrOwner, nowMs: Long): Boolean {
        val h = held.getOrPut(owner) { Held(count = 0, leaseUntilMs = 0L) }
        h.count += 1
        h.leaseUntilMs = nowMs + leaseMs
        return recompute(nowMs)
    }

    /** Refresh a want's lease from a composition that is still started. Never resurrects a released
     *  owner, so a stray renew can never arm the stream by itself. */
    fun renew(owner: RealtimeHrOwner, nowMs: Long): Boolean {
        held[owner]?.leaseUntilMs = nowMs + leaseMs
        return recompute(nowMs)
    }

    /** Drop one want. A release nothing requested, or a second release of the same owner, is a no-op —
     *  it can neither push a count below zero nor disturb another owner's want. */
    fun release(owner: RealtimeHrOwner, nowMs: Long): Boolean {
        val h = held[owner]
        if (h != null) {
            h.count -= 1
            if (h.count <= 0) held.remove(owner)
        }
        return recompute(nowMs)
    }

    /** The app's UI appeared or went away. Suppresses every screen want; session wants are unaffected. */
    fun setVisible(visible: Boolean, nowMs: Long): Boolean {
        this.visible = visible
        return recompute(nowMs)
    }

    /** Re-read the decision against the clock alone — this is what expires an unrenewed screen lease. */
    fun evaluate(nowMs: Long): Boolean = recompute(nowMs)

    /** Drop every want at once (the owning view-model is going away). */
    fun releaseAll(nowMs: Long): Boolean {
        held.clear()
        return recompute(nowMs)
    }

    /** The owners currently keeping the stream armed — a held-but-suppressed want is not one. */
    fun holders(nowMs: Long): Set<RealtimeHrOwner> =
        held.keys.filterTo(LinkedHashSet()) { counts(it, nowMs) }

    private fun counts(owner: RealtimeHrOwner, nowMs: Long): Boolean {
        val h = held[owner] ?: return false
        return if (owner.backgroundEligible) true else visible && nowMs < h.leaseUntilMs
    }

    private fun recompute(nowMs: Long): Boolean {
        armed = held.keys.any { counts(it, nowMs) }
        return armed
    }

    companion object {
        /** A screen want expires this long after its last renewal, so a want nothing ever releases costs
         *  minutes of streaming rather than a night of it. */
        const val SCREEN_LEASE_MS = 3 * 60_000L

        /** Renewal cadence for a started composition — comfortably inside [SCREEN_LEASE_MS]. */
        const val LEASE_RENEW_MS = 45_000L
    }
}

/**
 * Hold a live-HR want for [owner] while this composition exists AND its lifecycle is at least STARTED,
 * renewing the lease on a timer. Backgrounding cancels the block, which releases in its `finally`; the
 * arbiter's own visibility gate is the independent second layer, and the lease the third.
 */
@Composable
fun RealtimeHrWhileVisible(vm: AppViewModel, owner: RealtimeHrOwner) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, owner, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.requestRealtimeHr(owner)
            try {
                while (true) {
                    delay(RealtimeHrArbiter.LEASE_RENEW_MS)
                    vm.renewRealtimeHr(owner)
                }
            } finally {
                vm.releaseRealtimeHr(owner)
            }
        }
    }
}

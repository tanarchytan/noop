package com.noop.ble

import android.content.Context
import com.noop.R

/**
 * Decides when a strap that keeps REFUSING the encrypted bond (INSUFFICIENT_AUTHENTICATION/_ENCRYPTION,
 * no genuine bond in between) has refused enough times that hammering it further is pointless. Pure so
 * it's unit-testable without a BLE seam.
 *
 *  - PAUSE: after [giveUpThreshold] consecutive refusals auto-reconnect stops re-kicking and surfaces an
 *    honest hint instead of looping forever and draining the battery.
 *  - EPITAPH: at the same moment, emit one summary line recording how the bond attempt died (the streak +
 *    an opaque, install-local id), with no PII (no MAC, no serial).
 *
 * The streak accumulates across the reconnect loop (a disconnect does NOT reset it), cleared only by a
 * genuine bond or an explicit user reconnect, like the client's existing [bondRefusalStreak].
 */
class BondRefusalGiveUp(
    /**
     * Consecutive bond refusals before auto-reconnect pauses + writes the epitaph. 5, not 2 (where the
     * pairing hint already shows): gives the user several reconnect cycles to act before we stop
     * hammering. A genuinely held/stale strap reaches 5 within a couple of minutes.
     */
    private val giveUpThreshold: Int = 5,
) {
    var refusals = 0
        private set

    /**
     * True once [giveUpThreshold] is reached: auto-reconnect should pause and the epitaph has been (or
     * should be) written. Stays true until [reset] so the pause holds across the loop.
     */
    var gaveUp = false
        private set

    /**
     * Record one bond refusal. Returns true if THIS refusal freshly crossed the give-up threshold (so the
     * caller pauses the reconnect + writes the epitaph exactly once).
     */
    fun recordRefusal(): Boolean {
        refusals += 1
        if (!gaveUp && refusals >= giveUpThreshold) {
            gaveUp = true
            return true
        }
        return false
    }

    /** Clear the streak: a genuine bond landed, or the user explicitly reconnected. Re-arms auto-reconnect. */
    fun reset() {
        refusals = 0
        gaveUp = false
    }

    companion object {
        /**
         * The one-line bond-refusal epitaph. Records the streak + an OPAQUE install-local id only, never
         * a MAC or serial. [opaqueId] is a short token derived from the per-install local device id, so it
         * carries no PII. Pure so a fixture pins it; no em-dash.
         */
        fun epitaphLine(refusals: Int, opaqueId: String): String =
            "Bond epitaph: the strap [$opaqueId] refused the encrypted bond ${refusals}x in a row with no " +
                "successful bond - giving up auto-reconnect to stop hammering it. It is almost certainly " +
                "held by the official WHOOP app or a stale phone pairing. Free it (close the WHOOP app, put " +
                "the strap in pairing mode, forget it in Bluetooth settings) then reconnect in NOOP."

        /**
         * The honest user-facing hint shown when auto-reconnect pauses, in the phone's language. Tells
         * them why it stopped and how to get going again.
         */
        fun pausedHint(context: Context): String = context.getString(R.string.connect_paused_hint)

        /**
         * The same hint with no Context: the untranslated twin the pure fixtures read. Never rendered -
         * [pausedHint] is what reaches the screen.
         */
        fun pausedHint(): String =
            "NOOP stopped retrying because your strap keeps refusing to pair. It is likely still held by the " +
                "official WHOOP app, or your phone is holding an old pairing. Close the WHOOP app, put the " +
                "strap in pairing mode (tap until the LEDs flash blue), and if it is listed in your Bluetooth " +
                "settings choose Forget This Device. Then tap Connect to try again."

        /**
         * A short OPAQUE token for the epitaph, derived from the strap's device id. The strap id IS a MAC
         * address (PII), so we must NEVER expose its bytes: hash it (SHA-256, first 4 bytes as 8 hex
         * chars) for a token stable within a log, distinct per strap, but irreversible. Pure + deterministic.
         */
        fun opaqueId(localId: String): String = try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(localId.lowercase().toByteArray(Charsets.UTF_8))
            digest.take(4).joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            // Defense-in-depth: never let id-formatting throw into the bond path. A safe constant token
            // still keeps the MAC out of the log.
            "device"
        }
    }
}

package com.noop.ble

/**
 * A non-WHOOP live BLE source the [SourceCoordinator] can run as the single active source (a generic HR
 * strap, an FTMS gym machine, an experimental Huami band, or an experimental Oura ring).
 *
 * Deliberately MINIMAL: the coordinator only ever starts, targets, and stops a source, so the contract
 * is exactly [scan] / [connect] / [stop]. Richer per-source state — discovered peripherals, scanning
 * flag, battery, needs-pairing, Oura's adopt phase — stays on the concrete type for the wizard/live UI
 * to observe, not this interface; that keeps the active-source lifecycle decoupled from the
 * pairing/observation surface, so adding a brand is a factory arm, not new plumbing.
 *
 * Every implementer owns its OWN scanner/GATT and never references [WhoopBleClient], so nothing here
 * can regress the WHOOP path.
 */
interface LiveHrSource {
    /** Discover and connect to the source's peripheral by scanning (the fallback when the registry row
     *  has no usable stored address). */
    fun scan()

    /** Connect directly to the source's known peripheral by its stable BLE [address] (preferred over
     *  [scan]). */
    fun connect(address: String)

    /** Tear the source down and stop streaming. Idempotent. */
    fun stop()
}

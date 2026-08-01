package com.noop.data

/**
 * What a strap's own serial (GATT 0x2A25) means for the registry. A BLE address is the phone's name
 * for a strap and changes; the serial does not, so this is what lets a band that comes back on a new
 * address land on the row that already holds its history.
 *
 * Pure decision, no I/O: [DeviceRegistry.bindSerial] applies the outcome.
 */
object StrapIdentity {

    /** The one action a (serial, address) pair implies. */
    sealed interface Outcome {
        /** A row carries this serial and nothing else pins the address — re-point that row. */
        data class Reuse(val id: String) : Outcome

        /** The row already pinned to this address has no serial yet — record it. */
        data class Backfill(val id: String) : Outcome

        /** The serial's row and the address's row are DIFFERENT rows. Never merged: a wrong merge
         *  fuses two straps' histories and cannot be undone. */
        data class Ambiguous(val serialRowId: String, val addressRowId: String) : Outcome

        /** The address's row already carries this serial, or no row is involved. Nothing to write. */
        data object None : Outcome
    }

    /** The outcome for a strap that connected at [address] and reported [serial]. */
    fun resolve(serial: String, address: String, rows: List<PairedDeviceRow>): Outcome {
        val bySerial = rows.firstOrNull { it.serial?.equals(serial, ignoreCase = true) == true }
        val byAddress = rows.firstOrNull { it.peripheralId?.equals(address, ignoreCase = true) == true }
        return when {
            bySerial != null && byAddress == null -> Outcome.Reuse(bySerial.id)
            bySerial != null && bySerial.id == byAddress?.id -> Outcome.None
            bySerial != null -> Outcome.Ambiguous(bySerial.id, byAddress!!.id)
            byAddress != null && byAddress.serial == null && isDeviceRow(byAddress) ->
                Outcome.Backfill(byAddress.id)
            else -> Outcome.None
        }
    }
}

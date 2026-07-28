package com.noop.ble

import java.util.UUID

/**
 * Which strap the user is pairing. They pick this before scanning so we look for
 * exactly one device family instead of guessing — a WHOOP 4.0 scan no longer
 * waits forever on a WHOOP 5/MG wrist, and vice versa.
 *
 * This is the user-facing choice; it is deliberately separate from the
 * protocol-layer DeviceFamily (which carries CRC/characteristic detail).
 */
enum class WhoopModel(val displayName: String, val service: UUID) {
    WHOOP4("WHOOP 4.0", WhoopBleClient.WHOOP4_SERVICE),
    WHOOP5_MG("WHOOP 5.0 / MG", WhoopBleClient.WHOOP5_SERVICE);

    /**
     * The OTHER WHOOP family to try when a service-filtered scan for this model finds nothing. A
     * stale or missing persisted preference can point the scan at the wrong service forever with the
     * strap right there; rotating to the other family and persisting whichever advertises recovers it.
     */
    val fallbackScanModel: WhoopModel
        get() = when (this) {
            WHOOP4 -> WHOOP5_MG
            WHOOP5_MG -> WHOOP4
        }

    companion object {
        /**
         * Resolve the WHOOP family from a strap's advertised GATT service UUIDs. The merged onboarding
         * scan lists BOTH families at once (a ScanFilter list is OR'd), so each strap's family is read
         * back from which service it advertised. Null (no service UUID) resolves at connect instead.
         */
        fun fromServiceUuids(uuids: List<UUID>?): WhoopModel? {
            if (uuids.isNullOrEmpty()) return null
            return WhoopModel.entries.firstOrNull { model -> uuids.any { it == model.service } }
        }
    }
}

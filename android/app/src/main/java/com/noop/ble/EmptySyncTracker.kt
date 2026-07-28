package com.noop.ble

/**
 * Decides when a completed sync that handed over only the strap's console/diagnostic output (no
 * sensor records) is sustained enough to warn that the strap's clock has lost sync and isn't banking
 * to flash. A single empty cycle is common on a healthy strap (a console-only window, especially
 * under heavy live-HR polling), so warning on one cycle false-alarms; CONSECUTIVE empty cycles are
 * required and any cycle that banks real sensor records clears the streak. Pure, unit-testable
 * without a BLE seam.
 */
class EmptySyncTracker(
    /**
     * Consecutive console-only completed syncs before the clock-lost banner shows. 3 (not 1): a genuinely
     * un-banking strap is console-only on EVERY cycle, so 3 is reached within minutes, while a transient
     * empty cycle amid healthy ones never accumulates.
     */
    private val threshold: Int = 3,
) {
    var consecutiveEmptySyncs = 0
        private set

    /**
     * Record a COMPLETED (HISTORY_COMPLETE) offload. [bankedSensorRecords] = real records handed
     * over this cycle (decoded or undecodable-but-archived — either way, banking). [consoleOnly] =
     * only diagnostic frames, no sensor records. Returns true once >= [threshold] consecutive
     * console-only cycles accrue; any banking or nothing-to-offload cycle clears the streak.
     */
    fun recordCompletedSync(bankedSensorRecords: Boolean, consoleOnly: Boolean): Boolean {
        if (!consoleOnly || bankedSensorRecords) {
            consecutiveEmptySyncs = 0
            return false
        }
        consecutiveEmptySyncs += 1
        return consecutiveEmptySyncs >= threshold
    }
}

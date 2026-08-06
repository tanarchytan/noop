package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the Devices card's state-pill priority (#221): "Connected · not paired" must beat "Active · Live"
 * but yield to a reboot's "Reconnecting…". Mirrors the Swift `DevicePillStateTests` exactly — a silent
 * reorder on either platform would otherwise only be caught by eyeballing a screenshot.
 */
class DevicePillStateTest {

    @Test
    fun bondRefused_beatsActiveLive_butYieldsToReconnecting() {
        assertEquals(
            "Connected · not paired",
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = true, isLiveConnected = true,
            ).label,
        )
        assertEquals(
            "Reconnecting…",
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = true,
                bondRefused = true, isLiveConnected = true,
            ).label,
        )
    }

    @Test
    fun normalConnect_isUnaffected() {
        assertEquals(
            "Active · Live",
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = true,
            ).label,
        )
        assertEquals(
            "Active",
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).label,
        )
    }

    @Test
    fun nonActiveAndArchived() {
        assertEquals(
            "Paired",
            devicePillState(
                isArchived = false, isActive = false, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).label,
        )
        assertEquals(
            "Removed",
            devicePillState(
                isArchived = true, isActive = false, isReconnecting = false,
                bondRefused = false, isLiveConnected = false,
            ).label,
        )
    }

    @Test
    fun charging_replacesActiveLive_butYieldsToBothWarnings() {
        assertEquals(
            "Charging · Live",
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = true, isCharging = true,
            ).label,
        )
        assertEquals(
            "Connected · not paired",
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = true, isLiveConnected = true, isCharging = true,
            ).label,
        )
        assertEquals(
            "Reconnecting…",
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = true,
                bondRefused = false, isLiveConnected = true, isCharging = true,
            ).label,
        )
        // A charge report with the link down is not a live state — the strap clears the flag on
        // disconnect, so this can only be a stale read and must not claim "Charging · Live".
        assertEquals(
            "Active",
            devicePillState(
                isArchived = false, isActive = true, isReconnecting = false,
                bondRefused = false, isLiveConnected = false, isCharging = true,
            ).label,
        )
    }

    @Test
    fun historyLayoutLine_formatsObservedHistoricalRecordVersion() {
        assertEquals("v25 history", historyLayoutLine(25))
        assertNull(historyLayoutLine(null))
    }

    @Test
    fun powerPackLine_namesOnlyAPackTheStrapActuallyReported() {
        assertEquals("PowerPack · BPK-0001", powerPackLine(67.0, "BPK-0001"))
        // A charge with no serial still names the pack; a blank serial must not read as one.
        assertEquals("PowerPack", powerPackLine(67.0, null))
        assertEquals("PowerPack", powerPackLine(67.0, "  "))
        // No charge means the strap reported no pack, so the line is absent rather than invented.
        assertNull(powerPackLine(null, null))
        assertNull(powerPackLine(null, "BPK-0001"))
    }

    @Test
    fun powerPackLine_showsTheFirmwareAheadOfTheSerialAndKeepsBoth() {
        assertEquals(
            "PowerPack · FW 3.30.5.0 · WBB5AP0126395",
            powerPackLine(73.3, "WBB5AP0126395", "3.30.5.0"),
        )
        assertEquals("PowerPack · FW 3.30.5.0", powerPackLine(73.3, null, "3.30.5.0"))
        assertEquals("PowerPack · WBB5AP0126395", powerPackLine(73.3, "WBB5AP0126395", "  "))
        // A remembered firmware must never name a pack the strap is not reporting a charge for.
        assertNull(powerPackLine(null, "WBB5AP0126395", "3.30.5.0"))
    }
}

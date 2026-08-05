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
    fun powerPackLine_prefersTheFirmwareItActuallyRead() {
        assertEquals(
            "PowerPack · FW 3.30.5.0",
            powerPackLine(com.noop.ble.PowerPackState(connected = true, firmware = "3.30.5.0")),
        )
        assertEquals(
            "PowerPack · connected",
            powerPackLine(com.noop.ble.PowerPackState(connected = true)),
        )
        assertEquals(
            "PowerPack · looking…",
            powerPackLine(com.noop.ble.PowerPackState(scanning = true)),
        )
        assertEquals(
            "PowerPack · not found nearby",
            powerPackLine(com.noop.ble.PowerPackState(note = "not found nearby")),
        )
        // Nothing is being looked at, so the line is absent rather than an empty claim.
        assertNull(powerPackLine(com.noop.ble.PowerPackState()))
    }
}

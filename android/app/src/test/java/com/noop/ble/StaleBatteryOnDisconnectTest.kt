package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A strap-specific readout must not outlive the link that produced it. Battery is the one that bites:
 * a 5/MG reports it only from a polled 0x2A19 read, so a value left in place shows the PREVIOUS strap's
 * charge until a read happens to land.
 */
class StaleBatteryOnDisconnectTest {

    private fun connected() = LiveState(
        connected = true,
        bonded = true,
        heartRate = 62,
        batteryPct = 91.0,
        charging = false,
        strapFirmware = "50.40.1.0",
    ).withRRIntervals(listOf(820, 835))

    @Test
    fun droppingTheLinkBlanksTheBattery() {
        val after = WhoopBleClient.disconnectedLiveState(connected())
        assertNull("battery must not outlive the link", after.batteryPct)
        assertNull(after.heartRate)
        assertNull(after.charging)
        assertNull(after.strapFirmware)
        assertEquals(emptyList<Int>(), after.rrRecent)
    }

    @Test
    fun releasingAStrapBlanksTheBattery() {
        assertNull(WhoopBleClient.releasedLiveState(connected()).batteryPct)
    }

    /**
     * The point of the fix: a second strap must never inherit the first one's charge. Without the
     * clear, the 91% survives the swap and reads as the new strap's level.
     */
    @Test
    fun aSecondStrapDoesNotInheritTheFirstsCharge() {
        val afterSwap = WhoopBleClient.disconnectedLiveState(connected())
            .copy(connected = true, bonded = true)
        assertNull("the new link starts with no battery reading, not the old strap's", afterSwap.batteryPct)
    }
}

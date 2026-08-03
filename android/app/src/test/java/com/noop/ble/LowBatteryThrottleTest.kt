package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure low-battery throttle decisions on [WhoopBleClient] - the gate and the stretched
 * offload cadence - unit-testable without a BLE stack.
 */
class LowBatteryThrottleTest {

    @Test fun idleThrottleEngagesOnlyWhenDischargingAtOrBelowThreshold() {
        // at/below threshold, discharging, no Battery Saver → engage
        assertTrue(WhoopBleClient.idleThrottleActive(batteryPct = 20, charging = false, thresholdPct = 20, powerSave = false))
        assertTrue(WhoopBleClient.idleThrottleActive(batteryPct = 12, charging = false, thresholdPct = 20, powerSave = false))
        // above threshold, no Battery Saver → do not engage
        assertFalse(WhoopBleClient.idleThrottleActive(batteryPct = 21, charging = false, thresholdPct = 20, powerSave = false))
    }

    @Test fun idleThrottleNeverEngagesWhenChargingOrDisabled() {
        // charging → never (battery isn't the concern), even under Battery Saver
        assertFalse(WhoopBleClient.idleThrottleActive(batteryPct = 5, charging = true, thresholdPct = 30, powerSave = true))
        // threshold 0 → disabled; NOT even Battery Saver forces it
        assertFalse(WhoopBleClient.idleThrottleActive(batteryPct = 1, charging = false, thresholdPct = 0, powerSave = true))
    }

    @Test fun batterySaverEngagesAnArmedThrottleAboveThreshold() {
        // armed (threshold 20), battery well above it, but Battery Saver on + discharging → engage
        assertTrue(WhoopBleClient.idleThrottleActive(batteryPct = 80, charging = false, thresholdPct = 20, powerSave = true))
    }

    // --- battery-adaptive offload cadence ---

    private val base = 900_000L      // 15 min
    private val low = 2_700_000L     // 45 min

    @Test fun offloadStretchesOnlyWhenDischargingAtOrBelowThreshold() {
        // discharging, at/below → stretched
        assertEquals(low, WhoopBleClient.offloadIntervalMsFor(base, low, batteryPct = 18, charging = false, thresholdPct = 20, powerSave = false))
        // above threshold, no Battery Saver → normal cadence
        assertEquals(base, WhoopBleClient.offloadIntervalMsFor(base, low, batteryPct = 40, charging = false, thresholdPct = 20, powerSave = false))
        // armed + Battery Saver above threshold → stretched
        assertEquals(low, WhoopBleClient.offloadIntervalMsFor(base, low, batteryPct = 70, charging = false, thresholdPct = 20, powerSave = true))
    }

    @Test fun offloadNeverStretchesWhenChargingOrDisabled() {
        // charging → normal even at low battery / Battery Saver
        assertEquals(base, WhoopBleClient.offloadIntervalMsFor(base, low, batteryPct = 8, charging = true, thresholdPct = 30, powerSave = true))
        // threshold 0 → normal cadence always, even under Battery Saver
        assertEquals(base, WhoopBleClient.offloadIntervalMsFor(base, low, batteryPct = 3, charging = false, thresholdPct = 0, powerSave = true))
    }
}

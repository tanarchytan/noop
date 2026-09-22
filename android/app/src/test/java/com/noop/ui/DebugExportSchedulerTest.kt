package com.noop.ui

import com.noop.analytics.CalendarDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the daily debug export's persisted time bound. The delay-to-next-occurrence arithmetic it used
 * to own now has a single owner in BackupSync, and BackupSyncTest pins it.
 */
class DebugExportSchedulerTest {

    @Test
    fun settingsClampTimeToValidMinuteOfDay() {
        // The store clamps out-of-range minutes; mirror SmartAlarmStore's defensive coercion shape.
        // (No SharedPreferences here — assert the bound constants are internally consistent.)
        assertEquals(24 * 60, CalendarDay.MINUTES_PER_DAY)
        assertTrue(DebugExportSettings.DEFAULT_TIME in 0 until CalendarDay.MINUTES_PER_DAY)
    }
}

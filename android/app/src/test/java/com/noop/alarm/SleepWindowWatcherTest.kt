package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smart-alarm light-sleep detector only ADVISES the scheduler to move the alarm earlier; it never
 * cancels the hard deadline (that clamp lives in SmartAlarmScheduler). These pin that the heuristic
 * fires once on a real HR rise and stays quiet otherwise.
 */
class SleepWindowWatcherTest {

    private fun watcher() = SleepWindowWatcher(riseBpm = 6, minSamples = 5, troughCeilingBpm = 90)

    @Test fun staysQuietBeforeEnoughSamples() {
        val w = watcher()
        // Even a spike before the warm-up sample count is reached must not fire.
        repeat(4) { assertFalse(w.shouldWake(80)) }
    }

    @Test fun firesOnceOnRiseAboveTrough() {
        val w = watcher()
        // Settle near a trough of 50 bpm over the warm-up window.
        repeat(6) { assertFalse(w.shouldWake(50)) }
        // A clear rise of >= 6 bpm above the trough = lighter phase → fire exactly once.
        assertTrue(w.shouldWake(58))
        // No re-fire on subsequent readings.
        assertFalse(w.shouldWake(60))
        assertFalse(w.shouldWake(58))
    }

    @Test fun smallWobbleDoesNotFire() {
        val w = watcher()
        repeat(6) { assertFalse(w.shouldWake(52)) }
        // +4 is below the 6 bpm threshold.
        assertFalse(w.shouldWake(56))
    }

    @Test fun ignoresNonPositiveHr() {
        val w = watcher()
        repeat(6) { assertFalse(w.shouldWake(50)) }
        // No live HR (0) must never trip the alarm — that's the BLE-down case where the hard
        // deadline is the only thing that should wake the user.
        assertFalse(w.shouldWake(0))
        assertFalse(w.shouldWake(-1))
    }

    @Test fun highBriefSpikeDoesNotPoisonTheTrough() {
        val w = watcher()
        // A brief got-up-to-the-bathroom spike above the ceiling must not be recorded as the trough,
        // so the later genuine trough still anchors the rise detection.
        assertFalse(w.shouldWake(120))
        repeat(6) { assertFalse(w.shouldWake(48)) }
        assertTrue(w.shouldWake(55))   // 55 is +7 over the real trough of 48
    }

    @Test fun resetClearsState() {
        val w = watcher()
        repeat(6) { w.shouldWake(50) }
        assertTrue(w.shouldWake(58))
        w.reset()
        // After reset the warm-up gate applies again.
        assertFalse(w.shouldWake(58))
    }

    @Test fun nudgeMinuteWrapsAcrossMidnight() {
        // Not the watcher, but the wind-down derivation shares this file's concern: a very early wake
        // can push the nudge before midnight.
        val store = WindDownStoreFake(sleepNeed = 8 * 60, lead = 30)
        // 00:30 wake − 8h − 30m = 16:00 the previous evening (wraps).
        assertEquals(16 * 60, store.nudge(30))
    }

    /** Tiny pure stand-in so the wrap maths can be checked without a SharedPreferences context. */
    private class WindDownStoreFake(val sleepNeed: Int, val lead: Int) {
        fun nudge(wakeMinutes: Int): Int {
            val raw = wakeMinutes - sleepNeed - lead
            val day = 24 * 60
            return ((raw % day) + day) % day
        }
    }
}

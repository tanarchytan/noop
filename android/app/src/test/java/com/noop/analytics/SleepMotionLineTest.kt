package com.noop.analytics

import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exact wire strings [RestScorer] emits for the Sleep & Rest test-mode export: the
 * motion-coverage/staging line and the onset trace.
 */
class SleepMotionLineTest {

    @Test
    fun `sparse WHOOP4 night`() {
        assertEquals(
            "sleep-motion day=2026-07-12 grav=118 hr=590 sparse=true stager=V2 family=whoop4",
            RestScorer.sleepMotionLine(
                day = "2026-07-12", grav = 118, hr = 590, sparse = true,
                family = DeviceFamily.WHOOP4))
    }

    @Test
    fun `dense 5MG night`() {
        assertEquals(
            "sleep-motion day=2026-07-12 grav=800 hr=590 sparse=false stager=V2 family=whoop5",
            RestScorer.sleepMotionLine(
                day = "2026-07-12", grav = 800, hr = 590, sparse = false,
                family = DeviceFamily.WHOOP5))
    }

    // onset trace

    @Test
    fun `medianBpm sorted middle`() {
        assertEquals(null, RestScorer.medianBpm(emptyList()))
        assertEquals(60, RestScorer.medianBpm(listOf(60)))
        assertEquals(60, RestScorer.medianBpm(listOf(50, 70, 60)))        // sorted 50,60,70 -> idx 1
        assertEquals(70, RestScorer.medianBpm(listOf(50, 60, 70, 80)))    // count 4 -> idx 2 (upper-middle)
    }

    @Test
    fun `onset line HR not dipped is suspected pre-onset-awake`() {
        assertEquals(
            "sleep-onset onsetTs=1700000000 hrAtOnset=58 baselineHr=60 hrRatio=0.97",
            RestScorer.sleepOnsetLine(onsetTs = 1_700_000_000L, hrAtOnsetBpm = 58, baselineHrBpm = 60))
    }

    @Test
    fun `onset line HR dipped is a real onset`() {
        assertEquals(
            "sleep-onset onsetTs=1700000000 hrAtOnset=48 baselineHr=64 hrRatio=0.75",
            RestScorer.sleepOnsetLine(onsetTs = 1_700_000_000L, hrAtOnsetBpm = 48, baselineHrBpm = 64))
    }

    @Test
    fun `onset line zero baseline is safe`() {
        assertEquals(
            "sleep-onset onsetTs=1 hrAtOnset=50 baselineHr=0 hrRatio=0.0",
            RestScorer.sleepOnsetLine(onsetTs = 1L, hrAtOnsetBpm = 50, baselineHrBpm = 0))
    }
}

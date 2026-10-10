package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.RrInterval
import com.noop.data.WhoopRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.whoop_ffi.SleepSegment
import uniffi.whoop_ffi.SleepStage

/** The nightly-HRV coverage gate through the FFI bridge: the decision is whoop-rs, this pins the wiring. */
class HrvQualityGateTest {

    private val deep = listOf(SleepSegment(start = 0L, end = 600L, stage = SleepStage.DEEP))

    private fun night(copies: Int): List<RrInterval> =
        (0L until 600L).flatMap { t ->
            (0 until copies).map { c -> RrInterval("d", t, if (t % 2 == 0L) 950 else 1050, seq = c) }
        }

    @Test fun healthyNightKeepsItsHrv() {
        val r = RustScores.nightlyHrvChecked(0, 600, night(1), deep)
        assertNotNull(r.value)
        assertFalse(r.duplicatedBeats)
    }

    @Test fun doubledNightIsRefusedWithItsCoverage() {
        val r = RustScores.nightlyHrvChecked(0, 600, night(2), deep)
        assertNull(r.value)
        assertTrue(r.duplicatedBeats)
        assertTrue(r.coverage > 1.9)
    }

    @Test fun uncheckedNightlyHrvIsUnchanged() {
        assertNotNull(RustScores.nightlyHrv(0, 600, night(2), deep))
    }

    @Test fun refusedFlagSurvivesTheDayMergeOnlyWhenNoDeviceHasAnHrv() {
        val refused = DailyMetric(deviceId = "a", day = "2026-07-30", hrvDupCoverage = 1.86)
        val empty = DailyMetric(deviceId = "b", day = "2026-07-30")
        assertEquals(1.86, WhoopRepository.coalesceDay(refused, empty).hrvDupCoverage!!, 1e-9)
        val other = DailyMetric(deviceId = "b", day = "2026-07-30", avgHrv = 55.0)
        val merged = WhoopRepository.coalesceDay(refused, other)
        assertEquals(55.0, merged.avgHrv!!, 1e-9)
        assertNull(merged.hrvDupCoverage)
    }
}

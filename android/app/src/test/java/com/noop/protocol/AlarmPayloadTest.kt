package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Tests for the app-side wake-alarm clock policy [AlarmPayload.nextWakeEpochMs] (timezone → next wake
 * epoch). The rev4/rev2 alarm frame BYTES are built by whoop-rs and locked in [SendFrameParityTest].
 */
class AlarmPayloadTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    /** Fixed "now": 2026-06-07 08:00:00 UTC. */
    private fun nowMs(): Long =
        ZonedDateTime.of(LocalDate.of(2026, 6, 7), LocalTime.of(8, 0), utc).toInstant().toEpochMilli()

    @Test
    fun nextWake_laterToday_staysToday() {
        val wake = AlarmPayload.nextWakeEpochMs(18, 30, nowMs(), utc)
        val zdt = java.time.Instant.ofEpochMilli(wake).atZone(utc)
        assertEquals(LocalDate.of(2026, 6, 7), zdt.toLocalDate())
        assertEquals(18, zdt.hour)
        assertEquals(30, zdt.minute)
        assertTrue(wake > nowMs())
    }

    @Test
    fun nextWake_earlierThanNow_rollsToTomorrow() {
        val wake = AlarmPayload.nextWakeEpochMs(6, 0, nowMs(), utc)
        val zdt = java.time.Instant.ofEpochMilli(wake).atZone(utc)
        assertEquals(LocalDate.of(2026, 6, 8), zdt.toLocalDate())
        assertTrue(wake > nowMs())
    }

    @Test
    fun nextWake_equalToNow_rollsToTomorrow() {
        val wake = AlarmPayload.nextWakeEpochMs(8, 0, nowMs(), utc)
        val zdt = java.time.Instant.ofEpochMilli(wake).atZone(utc)
        assertEquals(LocalDate.of(2026, 6, 8), zdt.toLocalDate())
    }
}

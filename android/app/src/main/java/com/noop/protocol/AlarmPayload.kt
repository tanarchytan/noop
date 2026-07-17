package com.noop.protocol

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * WHOOP 5.0/MG wake-alarm app-side clock policy: resolves a local HH:MM into the next wake epoch. The
 * frame bytes (the rev4 SET_ALARM_TIME body + the rev2 disable) are built by whoop-rs; this keeps only
 * the timezone→epoch resolution the codec can't do.
 */
object AlarmPayload {
    /**
     * Next future epoch-millis for local wake [hour]:[minute], relative to [nowMs] in [zone].
     * Today's occurrence if strictly in the future, else tomorrow's (next occurrence after now).
     */
    fun nextWakeEpochMs(hour: Int, minute: Int, nowMs: Long, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val candidate = now.with(LocalTime.of(hour, minute, 0, 0)) // second + nanos cleared → subseconds 0
        val target = if (candidate.toInstant().toEpochMilli() > nowMs) candidate else candidate.plusDays(1)
        return target.toInstant().toEpochMilli()
    }
}

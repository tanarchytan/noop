package com.noop.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.whoop_ffi.HistorySummary

/**
 * Pure mapping tests for [RustAdapter.historyToRows]: constructing the uniffi record type is plain
 * Kotlin (no native library load), so these run everywhere and pin the byte-identity rules the
 * adapter must preserve. The native-backed field-by-field diff lives in [RustKotlinHistoryParityTest].
 */
class RustAdapterTest {

    private fun summary(
        heartRate: Int? = null,
        rr: List<Int> = emptyList(),
        gravity: List<Float>? = null,
        skinTempRaw: Int? = null,
        spo2Red: Int? = null,
        spo2Ir: Int? = null,
        spo2Pct: Int? = null,
        respRaw: Int? = null,
        steps: Int? = null,
        activityClass: Int? = null,
        sleepState: Int? = null,
    ) = HistorySummary(
        version = 18.toUByte(),
        unix = 1_784_000_000u,
        heartRate = heartRate?.toUByte(),
        rrIntervals = rr.map { it.toUShort() },
        gravity = gravity,
        skinTempC = null,
        skinTempRaw = skinTempRaw?.toUShort(),
        spo2Red = spo2Red?.toUShort(),
        spo2Ir = spo2Ir?.toUShort(),
        spo2Pct = spo2Pct?.toUByte(),
        respRaw = respRaw?.toUShort(),
        steps = steps?.toUShort(),
        activityClass = activityClass?.toUByte(),
        sleepState = sleepState?.toUByte(),
        signalFlags = null,
        signalQuality = null,
    )

    @Test
    fun `hr zero is dropped`() {
        val b = RustAdapter.historyToRows(summary(heartRate = 0), ts = 100L)
        assertTrue(b.hr.isEmpty())
    }

    @Test
    fun `hr and rr map at ts, zero rr dropped`() {
        val b = RustAdapter.historyToRows(summary(heartRate = 96, rr = listOf(602, 0, 613)), ts = 100L)
        assertEquals(listOf(HrRowExpected(100L, 96)), b.hr.map { HrRowExpected(it.ts, it.bpm) })
        assertEquals(listOf(602, 613), b.rr.map { it.rrMs })
        assertTrue(b.rr.all { it.ts == 100L })
    }

    @Test
    fun `gravity widens f32 to double exactly`() {
        val gx = -0.72517335f
        val b = RustAdapter.historyToRows(summary(gravity = listOf(gx, 0.4944165f, 0.49685547f)), ts = 5L)
        assertEquals(1, b.gravity.size)
        assertEquals(gx.toDouble(), b.gravity[0].x, 0.0) // exact widen, no rounding
    }

    @Test
    fun `raw registers stored unscaled`() {
        val b = RustAdapter.historyToRows(summary(skinTempRaw = 3345, spo2Red = 111, spo2Ir = 222, respRaw = 44), ts = 7L)
        assertEquals(3345, b.skinTemp.single().raw)
        assertEquals(111, b.spo2.single().red)
        assertEquals(222, b.spo2.single().ir)
        assertEquals(44, b.resp.single().raw)
    }

    @Test
    fun `steps carries nullable activity class, sleep_state zero preserved`() {
        val withClass = RustAdapter.historyToRows(summary(steps = 123, activityClass = 2, sleepState = 0), ts = 9L)
        assertEquals(123, withClass.steps.single().counter)
        assertEquals(2, withClass.steps.single().activityClass)
        assertEquals(0, withClass.sleepState.single().state) // 0 is a real wake reading, not "absent"

        val noClass = RustAdapter.historyToRows(summary(steps = 5, activityClass = null), ts = 9L)
        assertEquals(null, noClass.steps.single().activityClass) // null stays null
    }

    @Test
    fun `spo2 pct is not stored`() {
        val b = RustAdapter.historyToRows(summary(spo2Pct = 97), ts = 3L)
        assertTrue(b.spo2.isEmpty()) // 5.0 sleep SpO2 % is intentionally unstored, matching Kotlin
    }

    private data class HrRowExpected(val ts: Long, val bpm: Int)
}

package com.noop.protocol

import com.noop.data.StreamPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.whoop_ffi.HistorySummary
import uniffi.whoop_ffi.Live

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

    // ---- PRIMARY seam: HistorySummary → the flat map keys the offload loop reads (no native lib) --------

    @Test
    fun `summaryToHistMap emits the storing-loop keys, rr zeros dropped, gravity widened`() {
        val m = RustAdapter.summaryToHistMap(
            summary(heartRate = 96, rr = listOf(602, 0, 613), gravity = listOf(-0.72517335f, 0.4944165f, 0.49685547f),
                skinTempRaw = 3057, spo2Red = 592, spo2Ir = 612, respRaw = 3073, steps = 42296, activityClass = 1, sleepState = 2),
        )
        assertEquals(18, m["hist_version"])
        assertEquals(1_784_000_000, m["unix"]) // UInt → Int, matches the Kotlin decode's store type
        assertEquals(96, m["heart_rate"])
        assertEquals(listOf(602, 613), m["rr_intervals"]) // 0 dropped to match Kotlin's `v != 0`
        assertEquals(3057, m["skin_temp_raw"])
        assertEquals(592, m["spo2_red"]); assertEquals(612, m["spo2_ir"])
        assertEquals(3073, m["resp_rate_raw"])
        assertEquals(42296, m["step_motion_counter"]); assertEquals(1, m["activity_class"])
        assertEquals(2, m["sleep_state"])
        assertEquals((-0.72517335f).toDouble(), m["gravity_x"]) // exact widen
    }

    @Test
    fun `summaryToHistMap omits absent fields and never emits spo2 pct`() {
        val m = RustAdapter.summaryToHistMap(summary(heartRate = 0, spo2Pct = 97))
        assertEquals(0, m["heart_rate"]) // present-but-0: the loop drops it, the map carries it
        assertTrue(m["rr_intervals"] as List<*> == emptyList<Int>())
        assertTrue(!m.containsKey("skin_temp_raw"))
        assertTrue(!m.containsKey("gravity_x"))
        assertTrue(!m.containsKey("spo2_pct")) // 5.0 sleep SpO2 % is not a stored key
    }

    // ---- PRIMARY seam: widened Live.Event → the stored (kind, rawTs, residual) contract (no native) -----

    @Test
    fun `eventFieldsFromLive builds the NAME(raw) kind and f64 battery residual`() {
        // BATTERY_LEVEL(3): raw deci-% 999 → the exact Double 99.9 (f64 division, not f32 99.90000152).
        val ev = Live.Event(
            number = 3u, unix = 1_784_000_000u, batterySocDeci = 999u, batteryMillivolts = 4100u,
            batteryCharging = false, payloadHex = "707d",
        )
        val ef = RustAdapter.eventFieldsFromLive(ev)
        assertEquals("BATTERY_LEVEL(3)", ef.kind)
        assertEquals(1_784_000_000L, ef.rawTs)
        assertEquals(99.9, ef.residual["battery_pct"] as Double, 0.0)
        assertEquals(4100, ef.residual["battery_mV"])
        assertEquals(0, ef.residual["battery_charging"])
        assertEquals("707d", ef.residual["event_payload_hex"])
        // The canonical JSON is sorted-key; the exact f64 must render as 99.9 (not 99.90000152587891).
        assertTrue(StreamPersistence.encodePayload(ef.residual).contains("\"battery_pct\":99.9"))
    }

    @Test
    fun `eventFieldsFromLive gates an out-of-range battery mV and deci-percent out`() {
        val ev = Live.Event(
            number = 9u, unix = 5u, batterySocDeci = 1200u, batteryMillivolts = 4382u,
            batteryCharging = null, payloadHex = "a3500000",
        )
        val ef = RustAdapter.eventFieldsFromLive(ev)
        assertEquals("WRIST_ON(9)", ef.kind)
        assertTrue(!ef.residual.containsKey("battery_pct")) // deci 1200 > 1100 store gate
        assertTrue(!ef.residual.containsKey("battery_mV")) // 4382 > 4300 store gate
        assertEquals("a3500000", ef.residual["event_payload_hex"])
    }

    private data class HrRowExpected(val ts: Long, val bpm: Int)
}

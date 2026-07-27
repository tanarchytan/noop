package com.noop.protocol

import com.noop.data.StreamPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.whoop_ffi.HistorySummary
import uniffi.whoop_ffi.Live

/**
 * Pure mapping tests for [RustAdapter.summaryToHistMap] and the live event path: constructing the
 * uniffi record type is plain Kotlin (no native library load), so these run everywhere and pin the
 * byte-identity rules the adapter must preserve. The native-backed field-by-field diff lives in
 * [RustKotlinHistoryParityTest].
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
        dynamicAccelerationG: Float? = null,
        tempAux1Raw: Int? = null,
        tempAux2Raw: Int? = null,
        recordIndex: Long? = null,
        sleepStateRaw: Int? = null,
        opticalBaselineA: Int? = null,
        opticalAmpA: Int? = null,
        opticalSignalPoor: Boolean? = null,
        rawF32105: Float? = null,
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
        dynamicAccelerationG = dynamicAccelerationG,
        opticalBaselineA = opticalBaselineA?.toUByte(),
        opticalBaselineB = null,
        opticalAmpA = opticalAmpA?.toUByte(),
        opticalAmpB = null,
        opticalSignalPoor = opticalSignalPoor,
        recordIndex = recordIndex?.toUInt(),
        tempAux1Raw = tempAux1Raw?.toUShort(),
        tempAux2Raw = tempAux2Raw?.toUShort(),
        sleepStateRaw = sleepStateRaw?.toUByte(),
        rawU828 = null,
        rawU829 = null,
        rawU1630 = null,
        rawF32105 = rawF32105,
    )

    // ---- PRIMARY seam: HistorySummary → the flat map keys the offload loop reads (no native lib) --------

    @Test
    fun `summaryToHistMap emits dynamic_acceleration_g only when present`() {
        val present = RustAdapter.summaryToHistMap(summary(dynamicAccelerationG = 0.014f))
        assertEquals(0.014, present["dynamic_acceleration_g"] as Double, 1e-6)
        assertEquals(false, RustAdapter.summaryToHistMap(summary()).containsKey("dynamic_acceleration_g"))
    }

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
    fun `summaryToHistMap omits absent fields and emits spo2 pct when present`() {
        val m = RustAdapter.summaryToHistMap(summary(heartRate = 0, spo2Pct = 97))
        assertEquals(0, m["heart_rate"]) // present-but-0: the loop drops it, the map carries it
        assertTrue(m["rr_intervals"] as List<*> == emptyList<Int>())
        assertTrue(!m.containsKey("skin_temp_raw"))
        assertTrue(!m.containsKey("gravity_x"))
        assertEquals(97, m["spo2_pct"]) // 5/MG sleep SpO2 % now stored when the decode surfaced one

        // Absent (sentinel/diagnostic dropped at decode) → the key is omitted, never a fabricated 0.
        assertTrue(!RustAdapter.summaryToHistMap(summary(heartRate = 0)).containsKey("spo2_pct"))
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

    // ---- the per-second channels the funnel used to decode and drop ---------------------------------

    @Test
    fun `summaryToHistMap carries the aux channels and omits the absent ones`() {
        val m = RustAdapter.summaryToHistMap(
            summary(
                tempAux1Raw = 337, tempAux2Raw = 345, recordIndex = 24_557_414L, sleepStateRaw = 1,
                opticalBaselineA = 157, opticalAmpA = 48, opticalSignalPoor = false, rawF32105 = -4.87f,
            ),
        )
        assertEquals(337, m["temp_aux_1_raw"])
        assertEquals(345, m["temp_aux_2_raw"])
        assertEquals(24_557_414L, m["record_index"]) // Long: a u32 record index overflows an Int
        assertEquals(1, m["sleep_state_raw"])
        assertEquals(157, m["optical_baseline_a"])
        assertEquals(48, m["optical_amp_a"])
        assertEquals(false, m["optical_signal_poor"])
        assertEquals(-4.87, m["raw_f32_105"] as Double, 1e-6)
        // Absent stays absent: a key must never appear holding a fabricated zero.
        val bare = RustAdapter.summaryToHistMap(summary())
        listOf("temp_aux_1_raw", "record_index", "sleep_state_raw", "optical_amp_a", "raw_f32_105")
            .forEach { assertTrue("$it must be absent", !bare.containsKey(it)) }
    }

    @Test
    fun `a false signal-poor flag is banked, not treated as absent`() {
        // The flag is only meaningful beside the amplitudes, so `false` is a reading and must survive
        // the nullable mapping that drops genuinely-absent keys.
        val m = RustAdapter.summaryToHistMap(summary(opticalSignalPoor = false))
        assertTrue(m.containsKey("optical_signal_poor"))
        assertEquals(false, m["optical_signal_poor"])
    }
}

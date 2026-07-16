package com.noop.protocol

import com.noop.data.BatteryRow
import com.noop.data.GravityRow
import com.noop.data.HrRow
import com.noop.data.RespRow
import com.noop.data.RrRow
import com.noop.data.SkinTempRow
import com.noop.data.SleepStateRow
import com.noop.data.Spo2Row
import com.noop.data.StepRow
import com.noop.data.StreamBatch
import uniffi.whoop_ffi.HistorySummary
import uniffi.whoop_ffi.Live
import uniffi.whoop_ffi.Response

/**
 * Maps whoop-rs FFI decode types onto the app's StreamBatch rows, and drives the SHADOW parity diff
 * (whoop-rs decode vs the authoritative Kotlin decode) at the offload / live / response seams.
 *
 * Row mappers preserve every byte-identity rule the Kotlin path uses (hr/rr 0-drop, raw registers
 * unscaled, gravity exact f32→Double widen, null-preserving small enums). They take an ALREADY-corrected
 * `ts` (the caller runs the app-side #547/#72 timestamp path — never re-done here). Shadow diff never
 * changes a stored value; it only records into [RustShadowParity].
 */
object RustAdapter {

    /** Family → the FFI's Gen5 flag. */
    private fun isGen5(family: DeviceFamily) = family == DeviceFamily.WHOOP5

    // --- Row mappers (reused by shadow now and a full cutover later) -------------------------------

    /**
     * Fan one per-second [summary] out to its per-signal rows at the corrected [ts]. Mirrors the
     * per-record block of [extractHistoricalStreams]: hr=0 dropped, rr=0 dropped, spo2/skinTemp/resp
     * as raw registers, steps carry the nullable activity class, sleep_state verbatim (0 kept),
     * gravity widened exactly. spo2_pct (5.0 sleep %) is intentionally NOT stored, matching Kotlin.
     */
    fun historyToRows(summary: HistorySummary, ts: Long): StreamBatch {
        val hr = ArrayList<HrRow>(1)
        summary.heartRate?.toInt()?.let { if (it != 0) hr.add(HrRow(ts, it)) }

        val rr = ArrayList<RrRow>(summary.rrIntervals.size)
        for (v in summary.rrIntervals) {
            val ms = v.toInt()
            if (ms != 0) rr.add(RrRow(ts, ms))
        }

        val spo2 = ArrayList<Spo2Row>(1)
        summary.spo2Red?.toInt()?.let { red -> spo2.add(Spo2Row(ts, red = red, ir = summary.spo2Ir?.toInt() ?: 0)) }

        val skinTemp = ArrayList<SkinTempRow>(1)
        summary.skinTempRaw?.toInt()?.let { skinTemp.add(SkinTempRow(ts, it)) }

        val resp = ArrayList<RespRow>(1)
        summary.respRaw?.toInt()?.let { resp.add(RespRow(ts, it)) }

        val steps = ArrayList<StepRow>(1)
        summary.steps?.toInt()?.let { c -> steps.add(StepRow(ts, c, activityClass = summary.activityClass?.toInt())) }

        val sleepState = ArrayList<SleepStateRow>(1)
        summary.sleepState?.toInt()?.let { sleepState.add(SleepStateRow(ts, it)) }

        val gravity = ArrayList<GravityRow>(1)
        summary.gravity?.let { g ->
            gravity.add(
                GravityRow(
                    ts,
                    x = g.getOrElse(0) { 0f }.toDouble(),
                    y = g.getOrElse(1) { 0f }.toDouble(),
                    z = g.getOrElse(2) { 0f }.toDouble(),
                ),
            )
        }

        return StreamBatch(
            hr = hr, rr = rr, spo2 = spo2, skinTemp = skinTemp, resp = resp,
            steps = steps, sleepState = sleepState, gravity = gravity,
        )
    }

    /** A live realtime/battery frame → the rows it carries (event kind-naming stays Kotlin, so events
     *  are not mapped here). Realtime HR=0 dropped; RR=0 dropped. Used by a later live cutover. */
    fun liveToBatch(live: Live, ts: Long): StreamBatch = when (live) {
        is Live.Realtime -> {
            val hr = ArrayList<HrRow>(1)
            live.heartRate.toInt().let { if (it != 0) hr.add(HrRow(ts, it)) }
            val rr = ArrayList<RrRow>()
            for (v in live.rrIntervals) { val ms = v.toInt(); if (ms != 0) rr.add(RrRow(ts, ms)) }
            StreamBatch(hr = hr, rr = rr)
        }
        is Live.Battery -> StreamBatch(
            battery = listOf(BatteryRow(ts, soc = live.socPercent.toDouble(), mv = live.millivolts.toInt(), charging = live.charging)),
        )
        else -> StreamBatch()
    }

    /** A command response → its battery row (soc %), or null for identity/clock/range/version/other. */
    fun responseToBattery(response: Response, ts: Long): BatteryRow? = when (response) {
        is Response.Battery -> BatteryRow(ts, soc = response.percent.toDouble(), mv = null)
        is Response.ExtendedBattery -> BatteryRow(ts, soc = null, mv = response.millivolts.toInt())
        is Response.BatteryPack -> BatteryRow(ts, soc = response.socPct.toDouble(), mv = response.millivolts.toInt())
        else -> null
    }

    // --- Shadow diff (additive; records into RustShadowParity, changes nothing) --------------------

    /**
     * OFFLOAD seam: decode every HISTORICAL_DATA frame in [frames] a second time through the FFI and
     * diff the decoded record fields against the Kotlin [decodeHistorical]. Console/metadata frames and
     * v26 PPG (both sides null) are skipped. Called only when the shadow flag is on. Total; a native
     * failure is counted, never thrown.
     */
    fun diffHistoryChunk(frames: List<ByteArray>, family: DeviceFamily) {
        val gen5 = isGen5(family)
        val typeIndex = if (gen5) 8 else 4
        for (frame in frames) {
            val t = if (frame.size > typeIndex) frame[typeIndex].toInt() and 0xFF else -1
            if (t != PacketType.HISTORICAL_DATA.rawValue) continue
            try {
                val k = decodeHistorical(frame, family)
                val r = RustCodec.decodeHistory(gen5, frame)
                if (k == null && r == null) continue // both agree: undecodable (v26 / CRC-fail) — not a record
                RustShadowParity.frameCompared()
                if (k == null || r == null) {
                    RustShadowParity.record("record", false)
                    RustShadowParity.note("record decode disagrees (kotlin=${k != null} rust=${r != null})")
                    continue
                }
                RustShadowParity.record("record", true)
                diffHistoryFields(k, r)
            } catch (t2: Throwable) {
                RustShadowParity.nativeError()
                return
            }
        }
    }

    private fun diffHistoryFields(k: Map<String, Any?>, r: HistorySummary) {
        // hr (0 dropped both sides)
        val kHr = k.intOrNull("heart_rate")?.takeIf { it != 0 }
        val rHr = r.heartRate?.toInt()?.takeIf { it != 0 }
        cmp("hr", kHr, rHr)
        // rr (0 dropped both sides)
        @Suppress("UNCHECKED_CAST")
        val kRr = (k["rr_intervals"] as? List<Int>)?.filter { it != 0 } ?: emptyList()
        val rRr = r.rrIntervals.map { it.toInt() }.filter { it != 0 }
        cmp("rr", kRr, rRr)
        // gravity: exact f32→Double widen on both sides
        val kGx = k.doubleOrNull("gravity_x")
        if (kGx != null || r.gravity != null) {
            val kg = if (kGx != null) listOf(kGx, k.doubleOrNull("gravity_y") ?: 0.0, k.doubleOrNull("gravity_z") ?: 0.0) else null
            val rg = r.gravity?.let { g -> listOf(g.getOrElse(0) { 0f }.toDouble(), g.getOrElse(1) { 0f }.toDouble(), g.getOrElse(2) { 0f }.toDouble()) }
            cmp("gravity", kg, rg)
        }
        cmp("skinTemp", k.intOrNull("skin_temp_raw"), r.skinTempRaw?.toInt())
        cmp("spo2Red", k.intOrNull("spo2_red"), r.spo2Red?.toInt())
        cmp("spo2Ir", k.intOrNull("spo2_ir"), r.spo2Ir?.toInt())
        cmp("resp", k.intOrNull("resp_rate_raw"), r.respRaw?.toInt())
        cmp("steps", k.intOrNull("step_motion_counter"), r.steps?.toInt())
        cmp("activityClass", k.intOrNull("activity_class"), r.activityClass?.toInt())
        cmp("sleepState", k.intOrNull("sleep_state"), r.sleepState?.toInt())
    }

    /**
     * LIVE + RESPONSE seam: diff one complete live/response frame against the Kotlin [parsed] decode.
     * REALTIME_DATA → decode_live HR/R-R; COMMAND_RESPONSE → decode_response battery + firmware
     * presence. Only fields the Kotlin path itself surfaces are compared. Total; never throws.
     */
    fun diffLiveOrResponse(family: DeviceFamily, frame: ByteArray, parsed: ParsedFrame) {
        if (!parsed.ok || parsed.crcOk == false) return
        val gen5 = isGen5(family)
        try {
            when (parsed.typeName) {
                "REALTIME_DATA" -> {
                    val live = RustCodec.decodeLive(gen5, frame) as? Live.Realtime
                    RustShadowParity.frameCompared()
                    val kHr = parsed.parsed.intOrNull("heart_rate")
                    val rHr = live?.heartRate?.toInt()
                    cmp("live.hr", kHr, rHr)
                    @Suppress("UNCHECKED_CAST")
                    val kRr = (parsed.parsed["rr_intervals"] as? List<Int>)?.filter { it != 0 } ?: emptyList()
                    val rRr = live?.rrIntervals?.map { it.toInt() }?.filter { it != 0 } ?: emptyList()
                    cmp("live.rr", kRr, rRr)
                }
                "COMMAND_RESPONSE" -> {
                    val resp = RustCodec.decodeResponse(gen5, frame)
                    RustShadowParity.frameCompared()
                    val kBatt = parsed.parsed.doubleOrNull("battery_pct")
                    val rBatt = (resp as? Response.Battery)?.percent?.toDouble()
                    if (kBatt != null || rBatt != null) cmp("resp.battery", kBatt, rBatt)
                    val kFw = (parsed.parsed["fw_version"] as? String) ?: (parsed.parsed["fw_harvard"] as? String)
                    if (kFw != null) cmp("resp.fwPresent", true, resp is Response.Hello || resp is Response.Version)
                }
                else -> Unit
            }
        } catch (t: Throwable) {
            RustShadowParity.nativeError()
        }
    }

    private fun cmp(field: String, kotlin: Any?, rust: Any?) {
        val match = kotlin == rust
        RustShadowParity.record(field, match)
        if (!match) RustShadowParity.note("$field: kotlin=$kotlin rust=$rust")
    }
}

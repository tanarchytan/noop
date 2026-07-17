package com.noop.protocol

import com.noop.data.GravityRow
import com.noop.data.HrRow
import com.noop.data.RespRow
import com.noop.data.RrRow
import com.noop.data.SkinTempRow
import com.noop.data.SleepStateRow
import com.noop.data.Spo2PctRow
import com.noop.data.Spo2Row
import com.noop.data.StepRow
import com.noop.data.StreamBatch
import uniffi.whoop_ffi.HistorySummary
import uniffi.whoop_ffi.Live

/**
 * Maps whoop-rs FFI decode types onto the app's StreamBatch rows / flat parsed-map keys the offload
 * path consumes. whoop-rs is the sole decoder for history + events; this is the one-way bridge glue.
 *
 * Mappers preserve every byte-identity rule the store expects (hr/rr 0-drop, raw registers unscaled,
 * gravity exact f32→Double widen, null-preserving small enums). They take an ALREADY-corrected `ts`
 * (the app-side plausibility/clock path runs in [extractHistoricalStreams], never re-done here).
 */
object RustAdapter {

    /** Family → the FFI's Gen5 flag. */
    private fun isGen5(family: DeviceFamily) = family == DeviceFamily.WHOOP5

    // --- Row mappers -------------------------------------------------------------------------------

    /**
     * Fan one per-second [summary] out to its per-signal rows at the corrected [ts]: hr=0 dropped, rr=0
     * dropped, spo2 red/IR and skinTemp/resp as raw registers, spo2_pct (5.0 sleep %) as its own stream
     * when present, steps carry the nullable activity class, sleep_state verbatim (0 kept), gravity widened
     * exactly. whoop-rs already sleep-gates spo2_pct and drops sentinels, so a non-null value is a real reading.
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

        val spo2Pct = ArrayList<Spo2PctRow>(1)
        summary.spo2Pct?.toInt()?.let { pct -> spo2Pct.add(Spo2PctRow(ts, pct)) }

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
            hr = hr, rr = rr, spo2 = spo2, spo2Pct = spo2Pct, skinTemp = skinTemp, resp = resp,
            steps = steps, sleepState = sleepState, gravity = gravity,
        )
    }

    /** Map a Rust [HistorySummary] onto the flat map keys [extractHistoricalStreams] reads. rr 0s dropped
     *  to match the `v != 0` store rule; gravity widened exactly. */
    internal fun summaryToHistMap(s: HistorySummary): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["hist_version"] = s.version.toInt()
        m["unix"] = s.unix.toInt()
        s.heartRate?.let { m["heart_rate"] = it.toInt() }
        m["rr_intervals"] = s.rrIntervals.map { it.toInt() }.filter { it != 0 }
        s.spo2Red?.let { m["spo2_red"] = it.toInt() }
        s.spo2Ir?.let { m["spo2_ir"] = it.toInt() }
        s.spo2Pct?.let { m["spo2_pct"] = it.toInt() }
        s.skinTempRaw?.let { m["skin_temp_raw"] = it.toInt() }
        s.respRaw?.let { m["resp_rate_raw"] = it.toInt() }
        s.steps?.let { m["step_motion_counter"] = it.toInt() }
        s.activityClass?.let { m["activity_class"] = it.toInt() }
        s.sleepState?.let { m["sleep_state"] = it.toInt() }
        s.gravity?.let { g ->
            if (g.size >= 3) {
                m["gravity_x"] = g[0].toDouble(); m["gravity_y"] = g[1].toDouble(); m["gravity_z"] = g[2].toDouble()
            }
        }
        return m
    }

    // --- Decode (whoop-rs is the sole decoder; null when the frame is not a stored record/event) --------

    /** Decode one type-47 record via whoop-rs into the flat map keys the offload loop reads. null for a
     *  v26 PPG / console / CRC-failed / non-record frame — whoop-rs rejects a bad-CRC frame itself, so a
     *  garbled/forged offload frame yields null (archived by rejectedHistoricalRecords, never stored). */
    fun recordFields(frame: ByteArray, family: DeviceFamily): Map<String, Any?>? =
        RustCodec.decodeHistory(isGen5(family), frame)?.let { summaryToHistMap(it) }

    /** (kind, rawTs, residual) for a widened [Live.Event] — the pieces the storing event tail needs. */
    fun eventFieldsFromLive(ev: Live.Event): EventFields =
        EventFields(eventKind(ev.number.toInt()), ev.unix.toLong() and 0xFFFFFFFFL, eventResidual(ev))

    /** Decode one offload/live EVENT frame via whoop-rs into the stored (kind, rawTs, residual) contract.
     *  null when the frame is not an event. */
    fun eventFields(frame: ByteArray, family: DeviceFamily): EventFields? {
        val live = RustCodec.decodeLive(isGen5(family), frame)
        return if (live is Live.Event) eventFieldsFromLive(live) else null
    }

    /** The residual payload map for a widened [Live.Event]: battery_* (deci-% divided in f64) for a
     *  BATTERY_LEVEL, plus the Gen5 opaque payload hex. */
    private fun eventResidual(ev: Live.Event): LinkedHashMap<String, Any?> {
        val residual = LinkedHashMap<String, Any?>()
        ev.batterySocDeci?.toInt()?.let { deci -> if (deci <= 1100) residual["battery_pct"] = deci.toDouble() / 10.0 }
        ev.batteryMillivolts?.toInt()?.let { mv -> if (mv in 3000..4300) residual["battery_mV"] = mv }
        ev.batteryCharging?.let { residual["battery_charging"] = if (it) 1 else 0 }
        ev.payloadHex?.let { residual["event_payload_hex"] = it }
        return residual
    }

    /** The stored `NAME(raw)` event kind via the [EventNumber] label table (0x-fallback for an
     *  uncatalogued number). */
    private fun eventKind(number: Int): String =
        EventNumber.fromRaw(number)?.let { "${it.name}($number)" } ?: "0x%02X(%d)".format(number, number)
}

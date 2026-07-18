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
import uniffi.whoop_ffi.Response

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
        EventNumber.fromRaw(number)?.let { "${it.name}($number)" } ?: hexLabel(number)

    // --- LIVE inbound bridge: build the app's [ParsedFrame] contract from whoop-rs decode. ----------
    //
    // The native BLE reassembler produces complete frames; this turns each into the SAME ParsedFrame
    // (typeName + crcOk + flat parsed-map keys) the live router / live collector / capture writer read,
    // but sourced entirely from whoop-rs decode (bytes->values). The only Kotlin logic here is the
    // opcode/enum LABEL table + packet-type registry (storage-contract formatting, not decode).

    /**
     * Decode one complete live/inbound frame via whoop-rs and present it as a [ParsedFrame]. The
     * packet TYPE is classified from the registry (opcode table); every VALUE comes from whoop-rs
     * (which CRC-gates internally, so a non-null decode == crcOk true). Frame types outside the live
     * router's interest (HISTORICAL_DATA / raw / IMU / unknown) get a named-but-empty frame, exactly
     * as the previous Kotlin live decoder did.
     */
    fun parseFrame(frame: ByteArray, family: DeviceFamily): ParsedFrame {
        val gen5 = isGen5(family)
        val minSize = if (gen5) 12 else 8
        if (frame.size < minSize || frame[0] != 0xAA.toByte()) return ParsedFrame.invalid()

        val name = classifyTypeName(frame, family)
        val parsed = LinkedHashMap<String, Any?>()
        var crcOk: Boolean? = null

        when (name) {
            "REALTIME_DATA" ->
                (RustCodec.decodeLive(gen5, frame) as? Live.Realtime)?.let { rt ->
                    crcOk = true
                    parsed["timestamp"] = rt.unix.toInt()
                    parsed["heart_rate"] = rt.heartRate.toInt()
                    parsed["rr_intervals"] = rt.rrIntervals.map { it.toInt() }.filter { it > 0 }
                }
            "EVENT" ->
                (RustCodec.decodeLive(gen5, frame) as? Live.Event)?.let { ev ->
                    crcOk = true
                    parsed["event"] = eventKind(ev.number.toInt())
                    parsed["event_timestamp"] = ev.unix.toInt()
                    parsed.putAll(eventResidual(ev))
                }
            "CONSOLE_LOGS" ->
                (RustCodec.decodeLive(gen5, frame) as? Live.Console)?.let { c ->
                    crcOk = true
                    parsed["console"] = c.text
                }
            "COMMAND_RESPONSE" ->
                RustCodec.decodeResponse(gen5, frame)?.let { resp ->
                    crcOk = true
                    mapResponse(resp, gen5, parsed)
                }
            "METADATA" ->
                RustCodec.decodeMetadata(gen5, frame)?.let { md ->
                    crcOk = md.crcOk
                    parsed["meta_type"] = metaLabel(md.metaType.toInt())
                    // whoop-rs reads unix@inner3 / trim@inner13 and zero-fills when the (shorter
                    // START/COMPLETE) body doesn't reach them; a real record unix/trim is never 0, so a
                    // non-zero value == "the frame carried it", matching the length-gated live contract.
                    if (md.unix != 0u) parsed["unix"] = md.unix.toInt()
                    if (md.trimCursor != 0u) parsed["trim_cursor"] = md.trimCursor.toInt()
                }
            else -> Unit // HISTORICAL_DATA / REALTIME_RAW_DATA / IMU / unknown: named, no live fields.
        }

        return ParsedFrame(ok = true, crcOk = crcOk, typeName = name, parsed = parsed)
    }

    /** Canonical packet-type name from the inner type byte (4.0 @4, 5/MG puffin @8), via the opcode
     *  registry. Mirrors the previous live decoder's `typeName`, so the stored/capture labels are
     *  unchanged. */
    private fun classifyTypeName(frame: ByteArray, family: DeviceFamily): String {
        val innerStart = if (isGen5(family)) 8 else 4
        val t = frame.getOrNull(innerStart)?.toInt()?.and(0xFF) ?: return "typeUnknown"
        return when (t) {
            PuffinPacketType.PUFFIN_COMMAND_RESPONSE -> "COMMAND_RESPONSE"
            PuffinPacketType.PUFFIN_METADATA -> "METADATA"
            else -> PacketType.fromRaw(t)?.name ?: "type$t"
        }
    }

    /** COMMAND_RESPONSE fields the live router reads. `result` is surfaced for 5/MG only, matching the
     *  4.0 decoder that carried resp_cmd + payload fields but never a result label. */
    private fun mapResponse(resp: Response, gen5: Boolean, parsed: MutableMap<String, Any?>) {
        parsed["resp_cmd"] = commandLabel(responseCmd(resp))
        if (gen5) responseResult(resp)?.let { parsed["result"] = commandResultLabel(it) }
        when (resp) {
            is Response.Battery -> parsed["battery_pct"] = resp.percent
            is Response.Clock -> if (!gen5) parsed["clock"] = resp.unix.toInt()
            is Response.Version -> if (!gen5) parsed["fw_harvard"] = resp.fw.joinToString(".") { it.toString() }
            is Response.Hello -> {
                if (resp.deviceName.length >= 6) parsed["device_name"] = resp.deviceName
                resp.fwVersion?.let { fw ->
                    if (fw.size >= 4) {
                        parsed["fw_version"] = (0 until 4).joinToString(".") { (fw[it].toInt() and 0xFF).toString() }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun responseCmd(r: Response): Int = when (r) {
        is Response.Battery -> r.respCmd.toInt()
        is Response.Clock -> r.respCmd.toInt()
        is Response.Hello -> r.respCmd.toInt()
        is Response.DataRange -> r.respCmd.toInt()
        is Response.Version -> r.respCmd.toInt()
        is Response.ExtendedBattery -> r.respCmd.toInt()
        is Response.BatteryPack -> r.respCmd.toInt()
        is Response.Other -> r.respCmd.toInt()
    }

    private fun responseResult(r: Response): Int? = when (r) {
        is Response.Battery -> r.result?.toInt()
        is Response.Clock -> r.result?.toInt()
        is Response.Hello -> r.result?.toInt()
        is Response.DataRange -> r.result?.toInt()
        is Response.Version -> r.result?.toInt()
        is Response.ExtendedBattery -> r.result?.toInt()
        is Response.BatteryPack -> r.result?.toInt()
        is Response.Other -> r.result?.toInt()
    }

    /** "NAME(raw)" for a known command, else "0xHH(raw)". */
    private fun commandLabel(v: Int): String =
        CommandNumber.fromRaw(v)?.let { "${it.name}($v)" } ?: hexLabel(v)

    /** "NAME(raw)" for a known metadata sub-type, else "0xHH(raw)". */
    private fun metaLabel(v: Int): String =
        MetadataType.fromRaw(v)?.let { "${it.name}($v)" } ?: hexLabel(v)

    /** 5/MG COMMAND_RESPONSE result codes (matches the live decoder's labels). */
    private fun commandResultLabel(v: Int): String = when (v) {
        0 -> "FAILURE(0)"
        1 -> "SUCCESS(1)"
        2 -> "PENDING(2)"
        3 -> "UNSUPPORTED(3)"
        else -> hexLabel(v)
    }

    private fun hexLabel(v: Int): String = "0x%02X(%d)".format(v, v)
}

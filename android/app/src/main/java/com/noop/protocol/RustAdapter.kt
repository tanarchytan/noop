package com.noop.protocol

import com.noop.data.BatteryRow
import com.noop.data.EventEntry
import com.noop.data.GravityRow
import com.noop.data.HrRow
import com.noop.data.RespRow
import com.noop.data.RrRow
import com.noop.data.SkinTempRow
import com.noop.data.SleepStateRow
import com.noop.data.Spo2Row
import com.noop.data.StepRow
import com.noop.data.StreamBatch
import com.noop.data.StreamPersistence
import uniffi.whoop_ffi.HistorySummary
import uniffi.whoop_ffi.Live
import uniffi.whoop_ffi.PpgSample
import uniffi.whoop_ffi.Response
import uniffi.whoop_ffi.ppgHr

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

    /**
     * A live realtime/event frame → the rows it carries at the corrected [ts]. Realtime HR=0/RR=0 dropped.
     * EVENT: builds the app's storage contract from the widened FFI struct — the `NAME(raw)` kind via the
     * [EventNumber] label table, and the canonical sorted-key `payloadJSON` via [StreamPersistence]. A
     * BATTERY_LEVEL event also fans a [BatteryRow]. soc is divided in f64 (raw deci-% / 10.0); charging
     * renders as an Int (0/1), mirroring [decodeEventWhoop5]. Gates match the Kotlin decode.
     */
    fun liveToBatch(live: Live, ts: Long): StreamBatch = when (live) {
        is Live.Realtime -> {
            val hr = ArrayList<HrRow>(1)
            live.heartRate.toInt().let { if (it != 0) hr.add(HrRow(ts, it)) }
            val rr = ArrayList<RrRow>()
            for (v in live.rrIntervals) { val ms = v.toInt(); if (ms != 0) rr.add(RrRow(ts, ms)) }
            StreamBatch(hr = hr, rr = rr)
        }
        is Live.Event -> eventToBatch(live, ts)
        else -> StreamBatch()
    }

    /** Reproduce [decodeEventWhoop5] + the storage split: the residual payload map, its canonical JSON
     *  event row, and (for BATTERY_LEVEL) the battery row — from the widened FFI [Live.Event]. */
    private fun eventToBatch(ev: Live.Event, ts: Long): StreamBatch {
        val residual = eventResidual(ev)
        val battery = ArrayList<BatteryRow>(1)
        val soc = residual["battery_pct"] as? Double
        val mv = residual["battery_mV"] as? Int
        if (soc != null || mv != null) {
            val charging = (residual["battery_charging"] as? Int)?.let { it != 0 }
            battery.add(BatteryRow(ts, soc = soc, mv = mv, charging = charging))
        }
        return StreamBatch(
            battery = battery,
            events = listOf(EventEntry(ts, eventKind(ev.number.toInt()), StreamPersistence.encodePayload(residual))),
        )
    }

    /** The residual payload map for a widened [Live.Event] — the keys the app stores beyond
     *  event/event_timestamp: battery_* (deci-% divided in f64, matching Kotlin's Double store) for a
     *  BATTERY_LEVEL, and the Gen5 opaque payload hex. Shared by the live batch + the primary event seam. */
    private fun eventResidual(ev: Live.Event): LinkedHashMap<String, Any?> {
        val residual = LinkedHashMap<String, Any?>()
        ev.batterySocDeci?.toInt()?.let { deci -> if (deci <= 1100) residual["battery_pct"] = deci.toDouble() / 10.0 }
        ev.batteryMillivolts?.toInt()?.let { mv -> if (mv in 3000..4300) residual["battery_mV"] = mv }
        ev.batteryCharging?.let { residual["battery_charging"] = if (it) 1 else 0 }
        ev.payloadHex?.let { residual["event_payload_hex"] = it }
        return residual
    }

    /** The stored `NAME(raw)` event kind via the retained [EventNumber] label table (0x-fallback for an
     *  uncatalogued number), matching the Kotlin decode's `event` field. */
    private fun eventKind(number: Int): String =
        EventNumber.fromRaw(number)?.let { "${it.name}($number)" } ?: "0x%02X(%d)".format(number, number)

    /** A command response → its battery row (soc %), or null for identity/clock/range/version/other. */
    fun responseToBattery(response: Response, ts: Long): BatteryRow? = when (response) {
        is Response.Battery -> BatteryRow(ts, soc = response.percent, mv = null)
        is Response.ExtendedBattery -> BatteryRow(ts, soc = null, mv = response.millivolts.toInt())
        is Response.BatteryPack -> BatteryRow(ts, soc = response.socPct, mv = response.millivolts.toInt())
        else -> null
    }

    // --- PRIMARY decode (whoop-rs is the authoritative writer; Kotlin decode feeds the comparator) -----
    // Each returns the SAME parsed-map/estimate shape the Kotlin storing path already consumes, so the
    // app-side ts / #547 / #72 / seq pipeline is untouched. On a native-load / decode error the Kotlin
    // decode is returned (RustShadowParity.nativeError) so no frame's data is lost. Family drives Gen4/Gen5.

    /** Map a Rust [HistorySummary] onto the flat map keys [extractHistoricalStreams] reads (the same keys
     *  [decodeHistorical] emits). rr 0s dropped to match Kotlin's `v != 0` store rule; gravity widened exactly. */
    internal fun summaryToHistMap(s: HistorySummary): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["hist_version"] = s.version.toInt()
        m["unix"] = s.unix.toInt()
        s.heartRate?.let { m["heart_rate"] = it.toInt() }
        m["rr_intervals"] = s.rrIntervals.map { it.toInt() }.filter { it != 0 }
        s.spo2Red?.let { m["spo2_red"] = it.toInt() }
        s.spo2Ir?.let { m["spo2_ir"] = it.toInt() }
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

    /** PRIMARY history record decode: Rust field map (authoritative), Kotlin decode for the comparator +
     *  the fallback. null when BOTH decoders agree the frame is not a stored record (v26 / console / CRC). */
    fun recordFieldsPrimary(frame: ByteArray, family: DeviceFamily): Map<String, Any?>? = try {
        val gen5 = isGen5(family)
        val r = RustCodec.decodeHistory(gen5, frame)
        val k = decodeHistorical(frame, family)
        when {
            r == null && k == null -> null
            r == null -> { RustShadowParity.frameCompared(); RustShadowParity.record("record", false); RustShadowParity.note("record: rust=null kotlin=present → kept kotlin"); k }
            k == null -> { RustShadowParity.frameCompared(); RustShadowParity.record("record", false); RustShadowParity.note("record: rust=present kotlin=null → kept rust"); summaryToHistMap(r) }
            else -> { RustShadowParity.frameCompared(); diffHistoryFields(k, r); summaryToHistMap(r) }
        }
    } catch (t: Throwable) {
        RustShadowParity.nativeError()
        decodeHistorical(frame, family)
    }

    /** PRIMARY v26 PPG-HR: Rust's winning sub-lag estimator (authoritative), Kotlin sub-lag for the
     *  comparator + the fallback. Samples are byte-identical on both sides (extracted app-side). */
    fun ppgEstimatesPrimary(samples: List<PpgHr.Sample>): List<PpgHr.Estimate> {
        if (samples.isEmpty()) return emptyList()
        return try {
            val rust = ppgHr(samples.map { PpgSample(it.ts, it.value) })
            val kotlin = PpgHr.estimate(samples, subLagInterp = true)
            RustShadowParity.frameCompared()
            if (rust.size != kotlin.size) {
                RustShadowParity.record("ppgHr", false)
                RustShadowParity.note("ppgHr window count rust=${rust.size} kotlin=${kotlin.size}")
            } else {
                val mism = rust.indices.count { rust[it].bpm != kotlin[it].bpm }
                RustShadowParity.record("ppgHr", mism == 0)
                if (mism > 0) RustShadowParity.note("ppgHr $mism/${rust.size} windows differ")
            }
            rust.map { PpgHr.Estimate(ts = it.ts, bpm = it.bpm, conf = it.conf) }
        } catch (t: Throwable) {
            RustShadowParity.nativeError()
            PpgHr.estimate(samples, subLagInterp = true)
        }
    }

    /** (kind, rawTs, residual) for a widened [Live.Event] — the pieces the storing event tail needs. */
    fun eventFieldsFromLive(ev: Live.Event): EventFields =
        EventFields(eventKind(ev.number.toInt()), ev.unix.toLong() and 0xFFFFFFFFL, eventResidual(ev))

    /** PRIMARY event decode: Rust widened event (authoritative kind + canonical residual), Kotlin decode
     *  for the comparator + the fallback. Returns null only when neither side decodes an event. */
    fun eventFieldsPrimary(frame: ByteArray, family: DeviceFamily): EventFields? {
        val kEv = decodeEventKotlin(frame, family)
        return try {
            val live = RustCodec.decodeLive(isGen5(family), frame)
            if (live !is Live.Event) {
                if (kEv != null) {
                    RustShadowParity.frameCompared(); RustShadowParity.record("event", false)
                    RustShadowParity.note("event: rust=non-event kotlin=${kEv.kind} → kept kotlin")
                }
                return kEv
            }
            val rEv = eventFieldsFromLive(live)
            RustShadowParity.frameCompared()
            if (kEv != null) {
                cmp("event.kind", kEv.kind, rEv.kind)
                cmp("event.ts", kEv.rawTs, rEv.rawTs)
                cmp("event.json", StreamPersistence.encodePayload(kEv.residual), StreamPersistence.encodePayload(rEv.residual))
            }
            rEv
        } catch (t: Throwable) {
            RustShadowParity.nativeError()
            kEv
        }
    }

    /** A Rust command [Response] → the battery_* parsed-map keys [appendHistBattery]/[appendBattery] read
     *  (percent divided in f64). Empty for a non-battery response. */
    private fun responseBatteryMap(response: Response?): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        when (response) {
            is Response.Battery -> m["battery_pct"] = response.percent
            is Response.ExtendedBattery -> m["battery_mV"] = response.millivolts.toInt()
            is Response.BatteryPack -> { m["battery_pct"] = response.socPct; m["battery_mV"] = response.millivolts.toInt() }
            else -> Unit
        }
        return m
    }

    /** PRIMARY command-response battery: Rust battery map (authoritative f64), Kotlin parse for the
     *  comparator + the fallback. null when the frame is not a decodable response. */
    fun responseBatteryPrimary(frame: ByteArray, family: DeviceFamily): Map<String, Any?>? {
        val kParsed = Framing.parseFrame(frame, family)
        if (!kParsed.ok || kParsed.crcOk == false) return null
        val kMap = kParsed.parsed
        return try {
            val resp = RustCodec.decodeResponse(isGen5(family), frame)
            val rMap = responseBatteryMap(resp)
            val kPct = kMap.doubleOrNull("battery_pct")
            val rPct = rMap["battery_pct"] as? Double
            if (kPct != null || rPct != null) { RustShadowParity.frameCompared(); cmp("resp.battery", kPct, rPct) }
            if (rMap.isEmpty()) kMap else rMap
        } catch (t: Throwable) {
            RustShadowParity.nativeError()
            kMap
        }
    }

    /**
     * PRIMARY live-storing decode (flushLive seam): rebuild the [Streams] the Kotlin [extractStreams]
     * would, but sourced from whoop-rs. Realtime HR/R-R, EVENTs (storage contract), and COMMAND_RESPONSE
     * battery each go through Rust with the Kotlin decode feeding the comparator; a per-frame Rust error
     * falls back to the Kotlin [ParsedFrame]. ts uses the same linear wall offset as [extractStreams].
     */
    fun liveStreamsPrimary(
        frames: List<Pair<ByteArray, ParsedFrame>>,
        family: DeviceFamily,
        deviceClockRef: Int,
        wallClockRef: Int,
    ): Streams {
        RustShadowParity.setPrimary(true)
        val out = Streams()
        fun toWall(deviceTs: Int?): Int? = if (deviceTs == null) null else wallClockRef + (deviceTs - deviceClockRef)
        for ((frame, pf) in frames) {
            if (!pf.ok || pf.crcOk == false) continue
            when (pf.typeName) {
                "REALTIME_DATA" -> {
                    val ts = toWall(pf.parsed.intOrNull("timestamp")) ?: continue
                    val live = try { RustCodec.decodeLive(isGen5(family), frame) } catch (t: Throwable) { RustShadowParity.nativeError(); null }
                    if (live is Live.Realtime) {
                        RustShadowParity.frameCompared()
                        val kHr = pf.parsed.intOrNull("heart_rate")
                        val rHr = live.heartRate.toInt()
                        cmp("live.hr", kHr, rHr)
                        val kRr = pf.parsed.intArrayOrNull("rr_intervals") ?: emptyList()
                        val rRr = live.rrIntervals.map { it.toInt() }
                        cmp("live.rr", kRr, rRr)
                        out.hr.add(HrSample(ts, rHr))
                        for (v in rRr) out.rr.add(RrInterval(ts, v))
                    } else {
                        // Rust did not surface a realtime record — keep the Kotlin decode (no loss).
                        pf.parsed.intOrNull("heart_rate")?.let { out.hr.add(HrSample(ts, it)) }
                        pf.parsed.intArrayOrNull("rr_intervals")?.let { for (v in it) out.rr.add(RrInterval(ts, v)) }
                    }
                }

                "EVENT" -> {
                    val ev = eventFieldsPrimary(frame, family) ?: continue
                    val ts = ev.rawTs.toInt()
                    if (ev.kind.startsWith("BATTERY_LEVEL")) appendBattery(out, ts, ev.residual)
                    out.events.add(WhoopEvent(ts, ev.kind, ev.residual))
                }

                "COMMAND_RESPONSE" -> {
                    val p = responseBatteryPrimary(frame, family) ?: continue
                    appendBattery(out, wallClockRef, p)
                }

                else -> Unit
            }
        }
        return out
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

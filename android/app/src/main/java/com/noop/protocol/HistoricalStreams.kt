package com.noop.protocol

import com.noop.data.BatteryRow
import com.noop.data.EventEntry
import com.noop.data.GravityRow
import com.noop.data.HrRow
import com.noop.data.PpgHrRow
import com.noop.data.PpgWaveformRow
import com.noop.data.RespRow
import com.noop.data.RrRow
import com.noop.data.SkinTempRow
import com.noop.data.SleepStateRow
import com.noop.data.Spo2Row
import com.noop.data.StepRow
import com.noop.data.StreamBatch
import com.noop.data.StreamPersistence
import uniffi.whoop_ffi.Live
import uniffi.whoop_ffi.PpgSample
import uniffi.whoop_ffi.Response

/*
 * Historical (offload) decode for the WHOOP 4.0 — the type-47 HISTORICAL_DATA path.
 *
 * Faithful port of three macOS Swift pieces:
 *   - the `postHooks["historical_data"]` decoder (Packages/WhoopProtocol/.../PostHooks.swift),
 *     which decodes a type-47 record's biometric block using the per-version field table baked
 *     into whoop_protocol.json (V24/V12 = full DSP block; V5/V7/V9 = generic HR/RR only),
 *   - `classifyHistoricalMeta` (Packages/WhoopProtocol/.../HistoricalMeta.swift), the METADATA
 *     classifier the offload state machine uses, and
 *   - `extractHistoricalStreams` (Packages/WhoopProtocol/.../HistoricalStreams.swift), which turns
 *     a batch of parsed offload frames into datastore rows.
 *
 * WHY this lives here and not in [Framing.parseFrame]: the live [Framing] decoder deliberately
 * does NOT decode type-47 (it only handles REALTIME_DATA / EVENT / COMMAND_RESPONSE / METADATA),
 * exactly like the Swift live path. Historical records are decoded only during a backfill, so the
 * type-47 decoder is kept on the offload path to mirror the Swift split precisely.
 *
 * The frame envelope is identical to Framing.kt's: [0]=0xAA, [1..2]=len u16 LE, [3]=crc8(len),
 * [4]=packet type (47 here), [5]=record VERSION (NOT a sequence byte for type-47 — the schema
 * note says "Version = seq byte (frame[5])"), [6..]=record. Field offsets in the version table
 * are FRAME-ABSOLUTE (= openwhoop data offset + 7). All multi-byte values are little-endian.
 */

// MARK: - plausible-timestamp bounds (#547)

/**
 * Lowest unix-second a real WHOOP record can carry (2023-11). A bad strap clock/flash (pikapik, #547)
 * emits records whose `unix` decodes to scattered garbage — far-past (year 2024/2019/…), a year-2027
 * spike (1_827_642_881), and even a FUTURE date. Those land in the DB verbatim and pollute the
 * day-windowed analytics (one ~12 h block re-attributed to every day; a future row surfacing as
 * "last night · 12 Jul"). Reuses the same 1.7 B floor already used to validate GET_DATA_RANGE words
 * (WhoopBleClient.dataRangeNewestUnix). Below this → drop the record.
 */
const val MIN_PLAUSIBLE_UNIX: Long = 1_700_000_000L

/**
 * How far past the offload wall-clock a record may be stamped (#547). A historical record can NEVER
 * post-date its own capture, so anything more than one day ahead of "now" is a bad-clock artefact —
 * drop it. One day of slack absorbs benign timezone/RTC skew without admitting a future-dated row.
 */
const val FUTURE_MARGIN: Long = 86_400L

/**
 * SESSION-RELATIVE slack (#547): how far OUTSIDE the strap's own GET_DATA_RANGE oldest/newest markers a
 * record may still be stamped before it's treated as wandering-clock pollution. The strap reports its
 * banked history span [oldest, newest] for THIS sync; a real record cannot predate the oldest banked marker
 * nor post-date the newest by more than benign skew, so a record dated MONTHS off the strap's OWN window is
 * a bad-clock artefact even when it clears the absolute 2023-11 floor (e.g. a 2024-12-25 record against a
 * 2026 strap window). 7 days absorbs marker jitter / a still-banking newest edge / DST while still catching
 * the months-off garbage. Kept in lockstep with Swift `HistoricalStreams.swift` SESSION_RANGE_MARGIN.
 */
const val SESSION_RANGE_MARGIN: Long = 7L * 86_400L

// MARK: - little-endian reader (null when out of range; the packet-type/version byte probe)

private fun ByteArray.histU8(off: Int): Int? = if (off + 1 <= size) this[off].toInt() and 0xFF else null

/**
 * The HISTORICAL_DATA (type-47) record frames in [rawFrames] that genuinely FAIL to decode — a CRC
 * failure, or a layout whoop-rs cannot turn into biometrics. These are exactly the record frames
 * [extractHistoricalStreams] silently drops: their biometric payload would otherwise be lost forever
 * once the strap trims the acked history.
 *
 * EXCLUDED (decode to zero rows BY DESIGN, never "lost" data — must NOT be counted):
 *   - CONSOLE_LOGS (type-50) frames — the strap's own diagnostics text channel. On WHOOP 4.0 the
 *     inner type byte is frame[4]; type-50 (0x32) is not type-47 so the family-aware type guard below
 *     already skips it. On WHOOP 5/MG the inner type byte is at frame[8].
 *   - WHOOP 5/MG v26 (raw PPG) records — deliberately unstored as per-second biometrics, known
 *     and skipped by design, not lost.
 *   - Non-record frames (METADATA, EVENT, etc.) — not type-47, so never returned.
 *
 * The Backfiller archives these raw bytes BEFORE acking the trim, so a user on an unmapped firmware
 * keeps their only copy (for a later release that maps the layout, and as the corpus that mapping
 * needs) instead of permanently losing it while the UI reports a healthy sync (#77 / #91).
 *
 * Pure function (no I/O) so it is unit-testable against captured frames.
 */
fun rejectedHistoricalRecords(
    rawFrames: List<ByteArray>,
    family: DeviceFamily = DeviceFamily.WHOOP4,
): List<ByteArray> {
    // Inner packet-type byte: WHOOP 5/MG's longer puffin envelope puts it at frame[8]; WHOOP 4 at frame[4].
    val typeIndex = if (family == DeviceFamily.WHOOP5) 8 else 4
    return rawFrames.filter { frame ->
        val t = frame.histU8(typeIndex) ?: return@filter false
        if (t != PacketType.HISTORICAL_DATA.rawValue) return@filter false // type-50 console / metadata / etc.
        // WHOOP 5/MG v26 = raw PPG block, deliberately not stored — known-skipped, not lost data.
        if (family == DeviceFamily.WHOOP5 && frame.histU8(9) == 26) return@filter false
        // A type-47 record whoop-rs cannot turn into usable biometrics (CRC failure / unmappable
        // layout). This is precisely what [extractHistoricalStreams] drops (`recordFields(...) ?:
        // continue`), so the rejected set matches the silently-lost set exactly.
        RustAdapter.recordFields(frame, family) == null
    }
}

// MARK: - METADATA classification (port of HistoricalMeta.swift)

/** Classification of a METADATA frame (type 49) for the historical-offload state machine. */
sealed class HistoricalMeta {
    object Start : HistoricalMeta()

    /** HISTORY_END: [unix] = record unix seconds, [trim] = the trim cursor to ack/advance. */
    data class End(val unix: Long, val trim: Long) : HistoricalMeta()

    object Complete : HistoricalMeta()
    object Other : HistoricalMeta()
}

/**
 * Classify a METADATA frame into the four cases the offload state machine needs. The bytes->values
 * decode routes through whoop-rs ([RustCodec.decodeMetadata]); this owns only the offload POLICY.
 *
 * Integrity gate: only act on a checksum-valid frame — whoop-rs reports `crcOk`, and a non-metadata
 * (or bad-CRC) frame yields Other, so a garbled/forged BLE peer can't forge HISTORY_END/COMPLETE and
 * advance the trim cursor for data we never durably stored.
 */
fun classifyHistoricalMeta(frame: ByteArray, family: DeviceFamily): HistoricalMeta {
    val m = RustCodec.decodeMetadata(family == DeviceFamily.WHOOP5, frame) ?: return HistoricalMeta.Other
    if (!m.crcOk) return HistoricalMeta.Other
    return when (m.metaType.toInt()) {
        MetadataType.HISTORY_START.rawValue -> HistoricalMeta.Start
        MetadataType.HISTORY_COMPLETE.rawValue -> HistoricalMeta.Complete
        // u32-on-the-wire carried as Long (unsigned-safe) so a value past 2^31 isn't negative downstream.
        MetadataType.HISTORY_END.rawValue ->
            HistoricalMeta.End(unix = m.unix.toLong() and 0xFFFFFFFFL, trim = m.trimCursor.toLong() and 0xFFFFFFFFL)
        else -> HistoricalMeta.Other
    }
}

// MARK: - EVENT decode seam (whoop-rs produces these via [RustAdapter.eventFields])

/**
 * The pieces the offload EVENT tail needs: the `NAME(raw)` [kind], the strap's own RTC [rawTs]
 * (pre-correction), and the [residual] payload map (parsed fields minus event/event_timestamp —
 * carries battery_* for a BATTERY_LEVEL, plus any Gen5 opaque payload).
 */
data class EventFields(val kind: String, val rawTs: Long, val residual: Map<String, Any?>)

// MARK: - Historical extraction (port of HistoricalStreams.swift extractHistoricalStreams)

/**
 * Turn a batch of parsed offload frames into a [StreamBatch] of datastore rows. Direct port of
 * Swift `extractHistoricalStreams`.
 *
 * HR/R-R/SpO2/skinTemp/resp/gravity come from type-47 HISTORICAL_DATA records, each of which
 * carries its OWN real unix timestamp — so NO wall-clock offset is applied to them (the
 * [deviceClockRef]/[wallClockRef] args exist only for the REALTIME_RAW_DATA fallback below and to
 * mirror the Swift signature). EVENT timestamps are real RTC unix seconds (already wall-clock).
 * CRC-failed / non-ok frames are skipped.
 *
 * [rawFrames] are the verbatim BLE frames for this chunk; each record is decoded through whoop-rs
 * ([RustAdapter.recordFields] / [RustCodec.decodePpg]). We take the frames (not pre-parsed records)
 * because the live [Framing.parseFrame] doesn't populate type-47 fields.
 *
 * DECODE routes solely through whoop-rs; the app-side plausibility drop, the grossly-stale-RTC 5-min
 * snap and rrInterval seq stability below are UNCHANGED — Rust supplies the raw fields, this owns the clock.
 */
fun extractHistoricalStreams(
    rawFrames: List<ByteArray>,
    deviceClockRef: Int,
    wallClockRef: Int,
    family: DeviceFamily = DeviceFamily.WHOOP4,
    // #547 ingest gate "now": the true wall clock used ONLY to reject future-dated records. NOT
    // wallClockRef — that arg is the (device,wall) correlation and is 0 on the RawHistoryArchive replay
    // path (which would otherwise reject everything). Take the LATER of the supplied correlation wall and
    // the real clock so a test that passes a recent wallClockRef still has a sane upper bound, and the
    // replay path's wallClockRef=0 falls back to the real clock. Mirrors the Swift wallNow seam.
    wallNow: Long = maxOf(wallClockRef.toLong(), System.currentTimeMillis() / 1000L),
    // SESSION-RELATIVE bounds (#547): the strap's own GET_DATA_RANGE oldest/newest markers for THIS sync.
    // null on the replay/import/no-range paths — the gate then falls back to the absolute-only floor
    // (unchanged). Kept in lockstep with the Swift extractHistoricalStreams session args.
    sessionOldestUnix: Long? = null,
    sessionNewestUnix: Long? = null,
    // Retained caller/Swift-signature arg (Test Centre → Experimental algorithms). The v26 PPG-HR now
    // runs through whoop-rs's adjudicated sub-lag estimator (always sub-lag), so this flag no longer
    // gates the estimate; it is kept so the caller contract is unchanged.
    @Suppress("UNUSED_PARAMETER") ppgHrSubLagInterp: Boolean = false,
): StreamBatch {
    // Count of records dropped by the #547 plausibility gate this batch, surfaced on the returned
    // StreamBatch so the Backfiller can log "bad strap clock" once per session via its existing seam.
    var droppedImplausible = 0
    // #324: oldest/newest own-timestamp among the dropped records (the poisoned-range epoch span), and the
    // dropped RTC-state events (RTC_LOST / BOOT / SET_RTC) — the ground truth that the clock reset. Declared
    // before correctedWall so the local function can capture them (Kotlin: no forward reference to locals).
    var droppedOldest: Long? = null
    var droppedNewest: Long? = null
    val droppedRtcEvents = ArrayList<DroppedRtcEvent>()

    // The plausible-timestamp window for this batch (#547): the absolute floor [MIN_PLAUSIBLE_UNIX,
    // wallNow + FUTURE_MARGIN] PLUS, when the strap's GET_DATA_RANGE markers are known AND well-formed
    // (both above the floor, oldest <= newest), the strap's OWN banked window padded by SESSION_RANGE_MARGIN.
    // A record dated months outside the strap's own window is wandering-clock pollution even if it clears the
    // absolute floor (e.g. 2024-12-25 against a 2026 strap). A legitimately-OLD record WITHIN [oldest, newest]
    // (real banked history) is always kept. Malformed/half markers fall back to absolute-only — never reject
    // real data on a wrong-epoch marker. Mirrors Swift `isPlausibleHistoricalUnix(_:wallNow:sessionOldest:sessionNewest:)`.
    fun plausible(ts: Long): Boolean {
        if (ts < MIN_PLAUSIBLE_UNIX || ts > wallNow + FUTURE_MARGIN) return false
        val oldest = sessionOldestUnix
        val newest = sessionNewestUnix
        if (oldest == null || newest == null || oldest < MIN_PLAUSIBLE_UNIX || newest < oldest) return true
        return ts >= oldest - SESSION_RANGE_MARGIN && ts <= newest + SESSION_RANGE_MARGIN
    }

    fun wall(deviceTs: Int?): Int? = if (deviceTs == null) null else wallClockRef + (deviceTs - deviceClockRef)

    // FIX #72: type-47 `unix` and EVENT `event_timestamp` are the strap RTC's own real-unix seconds.
    // When the strap RTC is grossly stale (it sat unused for months, so its clock is months behind) those
    // land far in the past — live HR works but all offloaded history is misdated. Correct them by the
    // (wall - device) clock offset, but ONLY when grossly stale, and SNAPPED to a 5-min grid so the SAME
    // record re-syncs to the SAME corrected ts (offloaded rows dedupe by (deviceId, ts); an un-snapped,
    // slightly-different offset on re-sync would duplicate every row). A normal/identity clockRef has
    // offset ~0 (< threshold) → rawTs unchanged (current behavior).
    val staleThreshold = 86_400          // 1 day
    val snapGranularity = 300            // 5 min
    val clockOffset = wallClockRef - deviceClockRef
    // #547: now NULLABLE. After resolving the final candidate ts (BOTH the raw pass-through branch AND the
    // corrected branch, including the anti-future fallback that keeps rawTs), reject the record entirely
    // when its timestamp is implausible — older than 2023-11 or more than a day ahead of now. pikapik's
    // bad-clock WHOOP 4.0 emits records whose `unix` decodes to scattered garbage (2024 / 2027-spike /
    // far-past / a future date); the constant-skew corrector returns those rawTs UNVALIDATED on a healthy-
    // looking clock (offset 0 on backfill), so they entered the DB verbatim and polluted every day-window.
    // Returning null here makes every call site skip the record. Counts each drop for the once-per-session
    // bad-clock log. Mirrors the Swift correctedWall returning nil.
    fun correctedWall(rawTs: Long): Long? {
        val candidate: Long = run {
            if (kotlin.math.abs(clockOffset) <= staleThreshold) return@run rawTs
            val snapped = (if (clockOffset >= 0) clockOffset + snapGranularity / 2
                           else clockOffset - snapGranularity / 2) / snapGranularity * snapGranularity
            val corrected = rawTs + snapped.toLong()
            // A fully-drained strap whose RTC reset to ~epoch (year ~1971) reports a near-zero
            // deviceClockRef while its frames still carry the true-unix rawTs; clockOffset is then
            // ~decades and this "correction" hurls every historical sample into the future (field: year
            // 2081), silently breaking sleep & recovery because the night never lands on the right day.
            // A record can't post-date its own capture, so when corrected overshoots wall time the offset
            // was bogus — keep the raw ts. Genuine stale (strap behind real time) has corrected <= wall,
            // so this is a no-op there. (PR #471, @cataboysbusiness-debug)
            if (corrected > wallClockRef + snapGranularity) rawTs else corrected
        }
        if (!plausible(candidate)) {
            droppedImplausible++
            // #324: track the epoch SPAN of the dropped (bad-clock) records — the strap's OWN dated value,
            // so the Backfiller can log whether the whole poisoned range is future-dated or mixed.
            droppedOldest = minOf(droppedOldest ?: candidate, candidate)
            droppedNewest = maxOf(droppedNewest ?: candidate, candidate)
            return null
        }
        return candidate
    }

    val hr = ArrayList<HrRow>()
    val rr = ArrayList<RrRow>()
    val spo2 = ArrayList<Spo2Row>()
    val skinTemp = ArrayList<SkinTempRow>()
    val steps = ArrayList<StepRow>()
    val sleepState = ArrayList<SleepStateRow>()
    val resp = ArrayList<RespRow>()
    val gravity = ArrayList<GravityRow>()
    val events = ArrayList<EventEntry>()
    val battery = ArrayList<BatteryRow>()
    // v26 PPG samples accumulate across the chunk, then get turned into HR after the loop (#156).
    val ppgSamples = ArrayList<PpgSample>()
    // The RAW v26 waveform kept PER RECORD (one row per strap-second) for durable storage (#156
    // follow-up) — the same (ts, samples) the estimator above consumes, but grouped per second so it
    // persists as its own `ppgWaveformSample` stream rather than being flattened into the HR buffer.
    val ppgWaveform = ArrayList<PpgWaveformRow>()

    for (frame in rawFrames) {
        // Packet type byte: WHOOP 5/MG's longer puffin envelope puts it at frame[8]; WHOOP 4 at frame[4].
        val t = if (family == DeviceFamily.WHOOP5) (frame.histU8(8) ?: -1)
                else if (frame.size > 4) frame[4].toInt() and 0xFF else -1
        when (t) {
            PacketType.HISTORICAL_DATA.rawValue -> {
                // WHOOP 5/MG layout v26 = the 24 Hz optical PPG buffer. It carries no per-second HR
                // (HR is PPG-derived on-device), so [RustAdapter.recordFields] returns null for it; instead
                // whoop-rs's [RustCodec.decodePpg] yields the 24 samples and HR is derived after the loop.
                // One v26 record == one strap second == 24 samples, appended in wire (time) order so the
                // concatenated stream is contiguous at 24 Hz. The whole-second `ts` is the record's unix,
                // getting the same grossly-stale-RTC correction (FIX #72) as every other stream. whoop-rs
                // version-gates the PPG decode: a non-v26 record returns null and falls through to the
                // record path below. The WHOOP5 family guard stays — the FFI PPG codec is gen5-only.
                if (family == DeviceFamily.WHOOP5) {
                    RustCodec.decodePpg(frame)?.let { rec ->
                        // #547: skip a v26 PPG buffer whose unix is implausible (correctedWall → null) so a
                        // bad-clock strap can't seed the derived-HR estimator with garbage-timestamped samples.
                        val baseTs = correctedWall(rec.unix.toLong() and 0xFFFFFFFFL)
                        if (baseTs != null) {
                            val samples = rec.samples.map { it.toInt() }
                            for (v in samples) ppgSamples.add(PpgSample(baseTs, v))
                            // Persist the raw waveform itself too (#156 follow-up), keyed on the record's
                            // corrected wall-second. Guard on non-empty so a truncated frame that decoded
                            // zero samples never banks an empty row (mirrors the Swift `!samples.isEmpty`).
                            if (samples.isNotEmpty()) ppgWaveform.add(PpgWaveformRow(baseTs, samples))
                        }
                    }
                }
                // type-47 carries the strap RTC's real-unix seconds. Correct for a grossly-stale RTC
                // (FIX #72); a normal strap is unchanged (offset < threshold). whoop-rs supplies the
                // field map; ts stays app-side.
                val p = RustAdapter.recordFields(frame, family) ?: continue
                // #547: correctedWall is now nullable — it returns null for an implausible (far-past /
                // future-dated) record, so the `?: continue` below skips a bad-clock record entirely
                // instead of letting its garbage `unix` enter the DB and pollute the day-windowed analytics.
                val ts = (p.intOrNull("unix")?.toLong())?.let { correctedWall(it) } ?: continue

                // skip startup hr=0 (matches Swift `bpm != 0`).
                p.intOrNull("heart_rate")?.let { bpm -> if (bpm != 0) hr.add(HrRow(ts, bpm)) }

                @Suppress("UNCHECKED_CAST")
                (p["rr_intervals"] as? List<Int>)?.forEach { rrMs -> rr.add(RrRow(ts, rrMs)) }

                p.intOrNull("spo2_red")?.let { red ->
                    spo2.add(Spo2Row(ts, red = red, ir = p.intOrNull("spo2_ir") ?: 0))
                }
                p.intOrNull("skin_temp_raw")?.let { raw -> skinTemp.add(SkinTempRow(ts, raw)) }
                // step_motion_counter@57 is the WHOOP5 CUMULATIVE u16 counter. Stored raw; AnalyticsEngine
                // derives the daily step total from counter deltas. APPROXIMATE — @57 semantics unverified
                // vs the official app. (#78)
                // activity_class@63 (0=still/1=walk/2=run) rides on the same record — null when invalid/absent.
                p.intOrNull("step_motion_counter")?.let { c ->
                    steps.add(StepRow(ts, c, activityClass = p.intOrNull("activity_class")))
                }
                // Band sleep_state (#175): the strap's OWN @81 high-nibble state (0 wake/1 still/2 asleep/3
                // up), decoded but DROPPED here until now, so the whole band-state chain (persist → the H7
                // re-onset confirm guard → Deep Timeline track) had no source. Carried VERBATIM including 0
                // (a real wake reading, not "absent"): only 5/MG v18 records emit the key, so a WHOOP 4.0
                // simply adds nothing.
                p.intOrNull("sleep_state")?.let { st -> sleepState.add(SleepStateRow(ts, st)) }
                p.intOrNull("resp_rate_raw")?.let { raw -> resp.add(RespRow(ts, raw)) }
                p.doubleOrNull("gravity_x")?.let { gx ->
                    gravity.add(
                        GravityRow(
                            ts,
                            x = gx,
                            y = p.doubleOrNull("gravity_y") ?: 0.0,
                            z = p.doubleOrNull("gravity_z") ?: 0.0,
                        ),
                    )
                }
            }

            PacketType.REALTIME_RAW_DATA.rawValue -> {
                // Fallback (rare during a plain type-47 offload): HR/RR off a realtime header. Its
                // timestamp is a device-epoch value, so it DOES get the wall-clock offset. whoop-rs is the
                // sole decoder; a plain type-43 raw header surfaces no biometrics (null → skip).
                val live = RustCodec.decodeLive(family == DeviceFamily.WHOOP5, frame) as? Live.Realtime ?: continue
                val ts = wall(live.unix.toInt()) ?: continue
                // #547: gate the wall()-corrected ts on the same plausibility window — a bad device clock
                // here would otherwise inject a far-past / future-dated HR/RR row.
                if (!plausible(ts.toLong())) { droppedImplausible++; continue }
                hr.add(HrRow(ts.toLong(), live.heartRate.toInt()))
                live.rrIntervals.forEach { rrMs -> if (rrMs.toInt() != 0) rr.add(RrRow(ts.toLong(), rrMs.toInt())) }
            }

            PacketType.EVENT.rawValue -> {
                // EVENT carries the strap RTC's real-unix seconds. Correct for a grossly-stale RTC
                // (FIX #72); a normal strap is unchanged. Port of the Swift `case "EVENT"` branch:
                // persist the event (with battery extracted for BATTERY_LEVEL) so offloaded
                // wrist/charge/battery events aren't lost. During a backfill the live path is
                // suppressed, so the offload extractor MUST handle these.
                // whoop-rs supplies the widened event (kind + canonical residual). The ts / #547 / #324
                // tail below is app-side and unchanged.
                val ev = RustAdapter.eventFields(frame, family) ?: continue
                // #547: correctedWall now nullable — an EVENT with an implausible event_timestamp is
                // skipped so a bad-clock wrist/charge/battery event can't enter the DB.
                val ts = correctedWall(ev.rawTs)
                if (ts == null) {
                    // #324: the #547 gate just dropped this event for an implausible ts. If it's an RTC-STATE
                    // event (RTC_LOST / BOOT / SET_RTC), that IS the ground truth that the clock reset —
                    // capture (kind, rawTs) for the strap log before discarding.
                    if (DroppedRtcEvent.isRtcStateKind(ev.kind)) droppedRtcEvents.add(DroppedRtcEvent(ev.kind, ev.rawTs))
                    continue
                }
                if (ev.kind.startsWith("BATTERY_LEVEL")) appendHistBattery(battery, ts, ev.residual)
                events.add(EventEntry(ts, ev.kind, StreamPersistence.encodePayload(ev.residual)))
            }

            PacketType.COMMAND_RESPONSE.rawValue -> {
                // No device timestamp on COMMAND_RESPONSE → stamp battery at wallClockRef (Swift parity).
                // whoop-rs decodes the response; only a GET_BATTERY_LEVEL reply carries a percent.
                (RustCodec.decodeResponse(family == DeviceFamily.WHOOP5, frame) as? Response.Battery)?.let {
                    battery.add(BatteryRow(ts = wallClockRef.toLong(), soc = it.percent, mv = null, charging = null))
                }
            }

            else -> Unit
        }
    }

    // Derive HR from the accumulated v26 PPG waveform via whoop-rs's adjudicated sub-lag estimator.
    // Empty unless the strap sent v26 records; falls back gracefully (no rows) on noise (#156).
    val ppgHr = RustCodec.ppgEstimates(ppgSamples)
        .map { PpgHrRow(ts = it.ts, bpm = it.bpm, conf = it.conf) }

    return StreamBatch(
        hr = hr, rr = rr, events = events, battery = battery,
        spo2 = spo2, skinTemp = skinTemp, resp = resp, gravity = gravity, steps = steps,
        sleepState = sleepState,
        ppgHr = ppgHr,
        ppgWaveform = ppgWaveform,
        droppedImplausibleTs = droppedImplausible,
        droppedImplausibleOldestTs = droppedOldest,   // #324 poisoned-range epoch span (diag only)
        droppedImplausibleNewestTs = droppedNewest,
        droppedRtcEvents = droppedRtcEvents,
    )
}

/**
 * Append a [BatteryRow] from a parsed frame's `battery_pct`/`battery_mV`/`battery_charging` fields
 * (no-op when neither soc nor mv is present). Mirrors the live-path `appendBattery` in Streams.kt
 * (kept local here to avoid widening that internal helper's surface).
 */
private fun appendHistBattery(out: MutableList<BatteryRow>, ts: Long, p: Map<String, Any?>) {
    val soc = p.doubleOrNull("battery_pct")
    val mv = p.intOrNull("battery_mV")
    if (soc == null && mv == null) return
    val charging = p.intOrNull("battery_charging")?.let { it != 0 }
    out.add(BatteryRow(ts = ts, soc = soc, mv = mv, charging = charging))
}

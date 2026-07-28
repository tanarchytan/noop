package com.noop.ble

import android.content.Context
import com.noop.data.InsertCounts
import com.noop.data.StreamBatch
import com.noop.data.WhoopRepository
import com.noop.protocol.BadClockDiagnostics
import com.noop.protocol.DeviceFamily
import com.noop.protocol.HistoricalMeta
import com.noop.protocol.RustAdapter
import com.noop.protocol.classifyHistoricalMeta
import com.noop.protocol.extractHistoricalStreams
import com.noop.protocol.rejectedHistoricalRecords
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Historical-offload state machine (idle / backfilling). Consumes the offload's METADATA frames
 * (HISTORY_START / repeated HISTORY_END / HISTORY_COMPLETE), accumulating the type-47 records
 * between them into chunks and committing each chunk durably.
 *
 * Per-chunk safe-trim invariant: decode -> persist decoded rows (durable) -> persist the
 * strap_trim cursor -> ack the trim to the strap. A chunk is forgotten by the strap only after
 * all three land; there is no server to fall back on.
 *
 * A high-freq-sync offload sends ONE HISTORY_START then REPEATED HISTORY_ENDs (a chunk close every
 * ~50 records): every end is acked, the accumulated frames are snapshotted and cleared, but the
 * chunk stays OPEN so later records become the next chunk. An END with no records is still acked
 * (it still advances the strap's trim).
 *
 * [ingest] is `suspend` and serialised by [mutex] so chunk assembly is never reordered; frames
 * arrive in order from a single drain coroutine.
 *
 * Decoded rows are the durable product; there is no raw-frame outbox. The one exception: frames
 * that fail decode are archived via [rejectedSink] BEFORE the ack, since the strap frees acked
 * history and those bytes would otherwise be the user's only, permanently lost copy.
 */
class Backfiller(
    private val repository: WhoopRepository,
    /** The device id every offloaded row is stamped with (read at finishChunk). MUTABLE so a
     *  WHOOP→WHOOP active-device switch re-points it via [WhoopBleClient.setActiveDeviceId] and the
     *  next chunk attributes to the new id; the single-WHOOP path never reassigns it ("my-whoop"). */
    var deviceId: String,
    private val cursorStore: TrimCursorStore,
    /**
     * Confirms one HISTORY_END chunk to the strap. Carries both the trim cursor (first u32 of
     * end_data, persisted as the `strap_trim` cursor) and the verbatim 8-byte `end_data` (the raw
     * HISTORY_END metadata.data[10:18]) the high-freq-sync ack form requires.
     */
    private val ackTrim: (trim: Long, endData: ByteArray) -> Unit,
    /**
     * Fires after a chunk's decoded rows are durably committed AND acked - i.e. real new data just
     * landed. Lets the client schedule on-device scoring right away instead of waiting for the next
     * 15-min analysis tick. Empty chunks (metadata-only ENDs) don't fire.
     */
    private val onChunkCommitted: (StreamBatch) -> Unit = {},
    /**
     * Chunk hook: a chunk arrived with frames but decoded no rows and held no genuine rejects - pure
     * diagnostic/console output. Lets the client tally a completed-but-empty offload (the strap isn't
     * banking) without false-positiving a normal caught-up sync.
     */
    private val onConsoleChunk: () -> Unit = {},
    /**
     * Diagnostic tap, null unless the debug capture switch is on: receives every decoded record's
     * property map, including the fields the stream funnel does not name. Write-only, never read back.
     */
    private val recordSink: ((Map<String, Any?>?, ByteArray) -> Unit)? = null,
    /**
     * Diagnostic sink into the strap log. Lets [finishChunk] surface a chunk that arrived with frames
     * but decoded to ZERO rows - the otherwise-invisible silent-data-loss case (frames failing CRC or
     * an unmapped layout are dropped, the chunk looks empty, and the trim acks past them).
     */
    private val log: (String) -> Unit = {},
    /**
     * Durable archive for HISTORICAL_DATA record frames that FAILED decode, called BEFORE the chunk
     * is acked. The strap frees acked history, so these raw bytes are the user's ONLY remaining copy
     * of an unmapped firmware's records. Return false ONLY when the archive itself could not be made
     * durable (a write failure, not the archive-full case): [finishChunk] then does NOT advance the
     * cursor or ack, so the strap keeps the records and re-sends them (same invariant as a failed
     * repository insert). Default keeps old behaviour for callers that wire no archive.
     */
    private val rejectedSink: (frames: List<ByteArray>, trim: Long) -> Boolean = { _, _ -> true },
    /**
     * Shared GET_CLOCK correlation. Type-47 records retain their own unix timestamps; this only
     * supplies gross stale-clock correction and the REALTIME_RAW_DATA fallback.
     */
    private val clockReference: ClockReference = ClockReference(),
    /**
     * Connection & Sync test mode (Test Centre): the cheap gate + tagged sink for the .connection
     * diagnostic lines (offload progress / firmware layout / trim sentinel). [connectionActive] is one
     * SharedPreferences bool read, always checked BEFORE building any connection line, so the
     * Backfiller pays nothing when the mode is off. [connectionLog] appends the already-built line
     * tagged .connection. Both default inert so tests get the byte-identical untraced path.
     */
    private val connectionActive: () -> Boolean = { false },
    private val connectionLog: (String) -> Unit = {},
    /**
     * Opt-in "HR-from-PPG sub-lag interpolation" (Test Centre → Experimental algorithms, default OFF).
     * Read as a live provider so a toggle flip mid-session takes effect on the next decoded chunk.
     * Passed straight into [extractHistoricalStreams] so the pure decoder never reaches for prefs.
     * Default inert keeps the untraced/test path byte-identical.
     */
    private val ppgHrSubLagInterp: () -> Boolean = { false },
    /** Live UI/export observation of the historical record layout (`hist_version`). */
    private val firmwareLayout: (Int) -> Unit = {},
) {

    /**
     * Emit one Connection & Sync test-mode line iff the mode is on. The cheap [connectionActive] gate
     * is checked BEFORE [build] runs, so the line string is never constructed when the mode is off.
     * Diagnostic only - it never changes the offload path.
     */
    private inline fun emitConnection(build: () -> String) {
        if (!connectionActive()) return
        connectionLog(build())
    }

    /**
     * SESSION-RELATIVE gate: the strap's own GET_DATA_RANGE oldest/newest banked-record markers for
     * the CURRENT offload, set by [WhoopBleClient] when the range reply lands. A record dated months
     * outside this window is wandering-clock pollution even if it clears the absolute 2023-11 floor,
     * so the ingest gate rejects it. null (both) until the range is known - the gate then falls back
     * to the absolute floor only, so behaviour is unchanged on the no-range / replay paths. Cleared
     * in [begin]. Volatile: written from the BLE callback thread, read in [finishChunk].
     */
    @Volatile
    var sessionOldestUnix: Long? = null

    @Volatile
    var sessionNewestUnix: Long? = null

    /**
     * Strap family for the CURRENT offload, set at [begin] - drives the family-aware frame parse
     * (5/MG inner record is +4) and the +4 end_data slice. The Backfiller is constructed once at
     * client init (before the family is known), so this is settable per-offload rather than a
     * constructor arg.
     */
    private var family: DeviceFamily = DeviceFamily.WHOOP4

    /** True while a historical offload session is active. */
    @Volatile
    var isBackfilling = false
        private set

    /** Serialises the suspend [ingest] calls so chunk boundaries are never crossed concurrently. */
    private val mutex = Mutex()

    /** Guards the [chunk]/[chunkOpen] mutations (the only cross-thread state: ingest vs begin/timeout). */
    private val chunkLock = Any()

    /** Buffered data frames for the current open chunk (between START and the next END). */
    private val chunk = ArrayList<ByteArray>()

    /** Whether a START has been received and we're accumulating a chunk. */
    private var chunkOpen = false

    /**
     * Per-session persistence tally - the success-side observability the forensics blind spot needed:
     * NOOP logged FAILURES (decoded-to-0) but never SUCCESSES, so a strap log couldn't tell a banking
     * strap from a broken one. Reset in [begin]; read by [WhoopBleClient] at session end to emit
     * "persisted N rows (M with motion) across K night(s)". Nights are day-keys (ts / 86400).
     */
    var sessionRowsPersisted = 0
        private set
    /** Set by [begin] when this session continues an auto-continue burst that already banked rows in
     *  an earlier session, so a trim=0xFFFFFFFF END here reads as "caught up", not "no history".
     *  Without it, the fresh session's `sessionRowsPersisted` is 0 and the scary "charge to 100%" line
     *  false-fires on the empty tail of a sync that just offloaded real records. */
    var continuedAfterRows = false
        private set
    /** Set true the moment ANY chunk's persist (decoded rows / reject archive / trim cursor) fails this
     *  session. While set, [finishChunk] must NOT ack - not even a subsequent EMPTY/metadata END, which
     *  would otherwise advance the strap's trim PAST the held records-carrying chunks, freeing history
     *  we never stored. The offload stalls safely (strap keeps everything past the last GOOD ack); a
     *  fresh session ([begin]) clears it. Exposed read-only so the client can surface a "history isn't
     *  persisting" signal in the debug export. */
    var persistStalled = false
        private set
    var sessionMotionRows = 0
        private set
    /**
     * Skin-temp samples banked this session. WHOOP 4.0 carries skin temp (and the raw SpO2 channel)
     * ONLY in its full DSP sleep records; a strap banking HR/RR-only records reports 0 here even on a
     * healthy-looking sync, so surfacing it makes "skin temp never appears" reports self-diagnosing.
     */
    var sessionSkinTempRows = 0
        private set
    private val sessionNightKeys = HashSet<Long>()
    val sessionNights: Int get() = sessionNightKeys.size

    /**
     * Logged once per session when the strap reports trim=0xFFFFFFFF — the "no valid flash cursor"
     * sentinel: it has no banked history to offload (a clock/charge state, not a decode bug).
     */
    private var loggedNoCursor = false

    /**
     * Logged once per session the first time a HISTORY_END's own timestamp is dated implausibly far
     * in the FUTURE (a corrupt strap RTC). Distinct from the per-record drop tally below: this fires
     * on the chunk metadata's own clock, the earliest visible tell that the strap's RTC is bogus.
     * Reset in [begin].
     */
    private var loggedFutureRtc = false

    /**
     * The trim cursor of the LAST chunk this Backfiller acked (durably persisted + confirmed to the
     * strap). Survives across sessions on the same connection so the auto-continue gate can ask "did
     * the offload actually advance the strap's trim this session?" - the spin-detector signal that
     * stops it re-kicking forever when the cursor is frozen. null until the first ack. NOT reset in
     * [begin] (it's a cross-session high-water mark, not a per-session tally).
     */
    @Volatile
    var lastAckedTrim: Long? = null
        private set

    /**
     * Distinct historical record-layout versions logged this session. Before this, only the unmapped/
     * reject path surfaced a version, so a HEALTHY log never revealed which layout the strap emits
     * (v24/v25 on 4.0, v18/v26 on 5/MG) - exactly the firmware→layout signal triage needs. Reset in
     * [begin]; each distinct layout is logged once per session.
     */
    private val loggedLayoutVersions = HashSet<Int>()

    /** SpO2 RE dump: how many full-record dumps this session emitted, bounded by
     *  [com.noop.analytics.Spo2ReTrace.MAX_SAMPLES]. Session-scoped so the cap spans chunks; reset in begin. */
    private var spo2Dumped = 0

    /**
     * Logged once per session the first time the ingest gate drops an implausible-timestamp record (a
     * bad strap clock/flash emitting far-past / year-2027-spike / future-dated `unix` values). Surfaces
     * a bad-clock strap in the shared log without spamming a line per chunk. Reset in [begin].
     */
    private var loggedImplausibleClock = false

    /**
     * RE-POLLUTION signal: running count of records this session the ingest gate dropped for an
     * implausible timestamp (a bad/wandering strap clock). Read by [WhoopBleClient.exitBackfilling] to
     * arm a heal re-run - if the strap is bad-clock THIS session it may have banked similar garbage on
     * an older build whose gate was weaker. Reset in [begin].
     */
    var sessionDroppedImplausible = 0
        private set

    /**
     * Called by [WhoopBleClient] when the strap signals a historical offload is beginning.
     * chunkOpen starts TRUE: the biometric replay streams records immediately and sends one
     * HISTORY_START then repeated HISTORY_ENDs, so we must accumulate from the outset.
     */
    fun begin(family: DeviceFamily = DeviceFamily.WHOOP4, continuedAfterRows: Boolean = false) {
        this.family = family
        this.continuedAfterRows = continuedAfterRows
        isBackfilling = true
        sessionRowsPersisted = 0
        sessionMotionRows = 0
        sessionSkinTempRows = 0
        sessionNightKeys.clear()
        persistStalled = false   // fresh session starts un-stalled
        loggedNoCursor = false
        loggedFutureRtc = false
        loggedLayoutVersions.clear()
        spo2Dumped = 0
        loggedImplausibleClock = false
        sessionDroppedImplausible = 0
        // The range markers belong to a connection's GET_DATA_RANGE, which the client re-sets per
        // connect; clear them so a fresh session never reuses a previous strap's window (the client
        // re-publishes them as soon as the range reply arrives).
        sessionOldestUnix = null
        sessionNewestUnix = null
        synchronized(chunkLock) {
            chunk.clear()
            chunkOpen = true
        }
    }

    /**
     * Feed one complete (reassembled) BLE frame into the state machine. Suspends while a chunk is
     * persisted so chunk boundaries are never crossed concurrently.
     */
    suspend fun ingest(frame: ByteArray) {
        mutex.withLock {
            when (val meta = classifyHistoricalMeta(frame, family)) {
                is HistoricalMeta.Start -> {
                    isBackfilling = true
                    synchronized(chunkLock) {
                        chunk.clear()
                        chunkOpen = true
                    }
                }
                is HistoricalMeta.End -> finishChunk(meta.unix, meta.trim, frame)
                is HistoricalMeta.Complete -> {
                    isBackfilling = false
                    synchronized(chunkLock) {
                        chunk.clear()
                        chunkOpen = false
                    }
                }
                is HistoricalMeta.Other -> synchronized(chunkLock) { if (chunkOpen) chunk.add(frame) }
            }
        }
    }

    /**
     * Commit one HISTORY_END chunk: persist decoded -> persist strap_trim cursor -> ack the trim.
     * Early-returns on any failure to preserve the safe-trim invariant (never ack data we failed to
     * store).
     *
     * Snapshots+clears the accumulated frames but leaves [chunkOpen] TRUE so the records following
     * this END become the next chunk. An END with no records is still acked (advances the trim).
     */
    private suspend fun finishChunk(unix: Long, trim: Long, endFrame: ByteArray) {
        val endData = endData(endFrame, family) ?: return

        // Corrupt future-RTC detection: HISTORY_END carries the strap's own clock; a genuine offload is
        // always PAST-dated, so a future-dated end means a corrupt RTC. Log once per session (ack still
        // proceeds); skip when trim is the 0xFFFFFFFF no-cursor sentinel, not a real date.
        if (trim != 0xFFFFFFFFL && !loggedFutureRtc) {
            val wallNow = System.currentTimeMillis() / 1000L
            if (isCorruptFutureRtc(unix, wallNow)) {
                loggedFutureRtc = true
                log(futureRtcLine(unix, wallNow))
            }
        }

        val frames = synchronized(chunkLock) {
            val snapshot = ArrayList(chunk)
            chunk.clear() // next records accumulate into the next chunk
            snapshot
        }

        var committed: StreamBatch? = null
        if (frames.isNotEmpty()) {
            val ref = clockReference.current
            val decoded = extractHistoricalStreams(
                frames, ref.device, ref.wall, family,
                applyStaleClockCorrection = false,
                sessionOldestUnix = sessionOldestUnix, sessionNewestUnix = sessionNewestUnix,
                ppgHrSubLagInterp = ppgHrSubLagInterp(),
                recordSink = recordSink,
            )
            // Observability: which historical layout does this strap emit? Only the unmapped/reject path
            // logged a version before, so a healthy sync never revealed v24/v25 (4.0) or v18/v26 (5/MG).
            // Sample the chunk's first genuine record (null = console/CRC-fail); log each layout once.
            frames.firstNotNullOfOrNull { RustAdapter.recordFields(it, family)?.get("hist_version") as? Int }
                ?.let { v ->
                    if (loggedLayoutVersions.add(v)) {
                        log("Backfill: historical records use layout v$v")
                        firmwareLayout(v)
                        // Connection test mode: the firmware layout as a compact tagged line. A layout that
                        // decoded a signature field (heart_rate / gravity_x / ppg_waveform) is decodable.
                        // Gated zero-cost.
                        emitConnection {
                            val decodable = frames.any {
                                val d = RustAdapter.recordFields(it, family)
                                d != null && (d.containsKey("heart_rate") || d.containsKey("gravity_x"))
                            }
                            com.noop.analytics.ConnectionTrace.firmwareLine(v, decodable)
                        }
                    }
                }
            // SpO2 RE dump: while Connection test mode is on, dump a few FULL historical records plus
            // their mapped raw SpO2 channels, so an offline pass can tell COMPUTED SpO2 (a byte tracking
            // the WHOOP app's nightly %) from the raw red/IR ADC we already decode. Only genuine records
            // (recordFields returns a map with `unix`) spend the budget - type-50 console frames have no
            // record bytes to correlate. Bounded per session across chunks ([spo2Dumped]); never a
            // user-facing number.
            if (spo2Dumped < com.noop.analytics.Spo2ReTrace.MAX_SAMPLES && connectionActive()) {
                for (f in frames) {
                    if (spo2Dumped >= com.noop.analytics.Spo2ReTrace.MAX_SAMPLES) break
                    val d = RustAdapter.recordFields(f, family) ?: continue
                    val recUnix = d["unix"] as? Int ?: continue
                    connectionLog(
                        com.noop.analytics.Spo2ReTrace.recordLine(
                            frame = f,
                            version = d["hist_version"] as? Int,
                            unix = recUnix,
                            red = d["spo2_red"] as? Int,
                            ir = d["spo2_ir"] as? Int,
                            skinRaw = d["skin_temp_raw"] as? Int,
                        ),
                    )
                    spo2Dumped++
                }
            }
            // The strap is emitting records with implausible timestamps (a bad clock/flash - far-past, a
            // year-2027 spike, or future-dated `unix`). The ingest gate dropped them so they can't pollute
            // the day-windowed analytics; surface it ONCE per session so a bad-clock strap is visible.
            sessionDroppedImplausible += decoded.droppedImplausibleTs
            if (decoded.droppedImplausibleTs > 0 && !loggedImplausibleClock) {
                loggedImplausibleClock = true
                // Append the epoch SPAN of the dropped block plus how far off it sits, so the strap log
                // shows whether the whole banked range is future-dated (safe to fast-forward-discard) or
                // just a slice.
                val span = BadClockDiagnostics.droppedSpanClause(
                    decoded.droppedImplausibleOldestTs,
                    decoded.droppedImplausibleNewestTs,
                    System.currentTimeMillis() / 1000L,
                )
                log(
                    "Backfill: WARNING dropped ${decoded.droppedImplausibleTs} record(s) with an " +
                        "implausible timestamp$span (bad strap clock — far-past or future-dated); they are " +
                        "excluded so they can't misdate history.",
                )
            }
            // The strap RTC-state events (RTC_LOST / BOOT / SET_RTC) the ingest gate dropped for a bad
            // own-timestamp - the GROUND TRUTH that the clock reset. Sparse (not per-record), so log each
            // as it appears; the bad rawTs is the future/past base the RTC jumped to.
            if (decoded.droppedRtcEvents.isNotEmpty()) {
                val nowForRtc = System.currentTimeMillis() / 1000L
                for (ev in decoded.droppedRtcEvents) {
                    log(
                        "Backfill: strap reported ${ev.kind} with an implausible own-timestamp " +
                            "${BadClockDiagnostics.isoDay(ev.rawTs)} (${BadClockDiagnostics.hoursOffset(ev.rawTs, nowForRtc)} " +
                            "vs now) — the strap's RTC reset to a wrong base (#324/#928); this is the ground-truth " +
                            "cause of the future-dated banking, not a NOOP decode bug.",
                    )
                }
            }
            // HISTORICAL_DATA record frames that fail decode (CRC failure, or an unmapped layout the v24
            // fallback's plausibility gate also rejects) must not be acked undetected - the strap trims
            // acked history, so those bytes would be the user's only, permanently lost copy. Classify PER
            // FRAME: a type-50 console frame decodes to 0 rows BY DESIGN and must not raise the alarm, and
            // a chunk-level isEmpty check would miss a mixed chunk where one good row hides the losses.
            // Rejects are archived durably AFTER the decoded insert but ALWAYS before the ack (see the
            // archive block below for why insert goes first). The WHOOP4 happy path (zero rejects) is
            // unchanged.
            val rejected = rejectedHistoricalRecords(frames, family)
            // Decoded no rows AND no genuine rejects means pure console output. Tally it so a
            // completed-but-empty offload (strap not banking) is distinguishable from a caught-up sync.
            if (decoded.isEmpty && rejected.isEmpty()) onConsoleChunk()
            if (rejected.isNotEmpty()) {
                log(
                    "Backfill: WARNING ${rejected.size} record frame(s) decoded to 0 rows " +
                        "(trim=$trim) — archiving raw bytes before ack (CRC/unmapped layout)",
                )
                // A hex sample in the strap log so an unmapped firmware's record layout can be mapped
                // from a shared log. Dump the FULL frame (not a 64-byte prefix - v25/v26 records run
                // ~84 B and the truncated tail is exactly where the unmapped motion/HR fields sit),
                // sampling a few so one log carries enough records to triangulate offsets. Only fires
                // for unmapped firmware.
                rejected.take(8).forEachIndexed { i, f ->
                    val hex = f.joinToString("") { "%02x".format(it) }
                    log("Backfill: rejected frame[$i] ${f.size}B: $hex")
                }
            }
            // Commit the decoded rows FIRST (durable), BEFORE the reject archive. Insert-first means a
            // rare insert failure - which returns below and re-sends the whole chunk next session - can't
            // have already appended this chunk's reject frames to the append-only archive, so the retry
            // can't leave duplicate lines in the corpus later firmware-layout mapping triangulates against.
            try {
                val counts = repository.insert(decoded, deviceId)
                committed = decoded
                // Tally what actually persisted so the session can emit "persisted N rows (M with motion)
                // across K night(s)" - the win-rate signal.
                val (rows, motion, nights) = chunkTally(counts, decoded.gravity.map { it.ts } + decoded.hr.map { it.ts })
                sessionRowsPersisted += rows
                sessionMotionRows += motion
                sessionSkinTempRows += counts.skinTemp
                sessionNightKeys.addAll(nights)
                // Connection test mode: per-chunk offload PROGRESS (running session totals). Gated zero-cost.
                emitConnection {
                    "offload progress trim=$trim chunkRows=$rows " +
                        "sessionRows=$sessionRowsPersisted sessionMotion=$sessionMotionRows nights=$sessionNights"
                }
            } catch (t: Throwable) {
                // The decoded rows couldn't be written - the "history stalls but live HR works" class.
                // Return WITHOUT acking so the strap keeps this chunk and re-sends it next session (no
                // data loss), and log it so a write-stall is falsifiable rather than a silent return.
                log("Backfill: failed to persist decoded rows (trim=$trim): $t, holding ack so the strap re-sends this chunk; history won't advance until the write succeeds.")
                persistStalled = true   // stall ALL further acks so an empty END can't advance past this
                return // do NOT advance/ack, chunk was never durably committed
            }
            // Any genuinely-undecodable record in this chunk must be ARCHIVED durably before we ack - the
            // ack frees the strap's copy, so the archive is the only remaining copy of an unmapped
            // firmware's records. Runs AFTER the decoded insert (see the insert comment above). A false
            // return means a genuine write failure (not the archive-full case, which returns true): hold
            // the cursor/ack so the strap re-sends the chunk; the re-send's insert is then an idempotent
            // no-op while the archive retries. No data loss either way.
            if (rejected.isNotEmpty() && !rejectedSink(rejected, trim)) {
                log("Backfill: rejected-frame archive failed (trim=$trim) — holding ack so the strap re-sends.")
                persistStalled = true
                return
            }
        }

        // trim=0xFFFFFFFF is the strap's "no valid flash cursor" sentinel, but its meaning depends on
        // whether this run already banked anything: on a fresh offload's first end it means "no banked
        // history"; after an auto-continuation that already persisted rows, the same value on the next
        // end means "caught up", not "no history" - so gate on sessionRowsPersisted (updated by THIS end
        // already) rather than firing the alarming no-history line on a strap that just synced fine. Logs
        // once per session.
        if (trim == 0xFFFFFFFFL && !loggedNoCursor) {
            loggedNoCursor = true
            log(noCursorLine(sessionRowsPersisted, continuedAfterRows))
            // Connection test mode: the no-cursor sentinel as a compact tagged line (gated zero-cost).
            emitConnection { com.noop.analytics.ConnectionTrace.noCursorLine() }
        }

        // If an EARLIER chunk this session failed to persist, do NOT advance the cursor or ack - not
        // even for this (possibly empty/metadata) END. `insert` short-circuits empty batches without
        // touching the store, so an empty END never throws; acking it would trim the strap PAST the
        // held records-carrying chunks, freeing history we never stored. Stall the whole offload until
        // a fresh session with a working store re-offers everything past the last GOOD ack.
        if (persistStalled) {
            log("Backfill: persist stalled earlier this session — NOT acking trim=$trim so the strap can't trim past un-stored history. Reconnect once the store is healthy (a backup restore needs an app restart, #57).")
            return
        }

        // Persist the trim cursor BEFORE acking, so a crash between persist and ack still resumes from
        // the right place. Stored via [TrimCursorStore] since the Room schema has no cursor table (see
        // the FLAG on [TrimCursorStore] below). trim is a u32 carried as Long (unsigned-safe).
        try {
            cursorStore.set(STRAP_TRIM_CURSOR, trim)
        } catch (t: Throwable) {
            // Decoded rows are durable but the strap_trim cursor write failed. Return WITHOUT acking -
            // acking now would let the strap trim past records the cursor hasn't recorded, so on
            // reconnect the offload could replay or skip. Holding the ack keeps it safe; the strap
            // re-offers this chunk.
            log("Backfill: failed to write strap_trim cursor (trim=$trim): $t, holding ack so the strap re-sends this chunk; history won't advance until the cursor write succeeds.")
            persistStalled = true
            return
        }

        ackTrim(trim, endData)
        lastAckedTrim = trim   // record the advanced cursor for the auto-continue spin-detector
        committed?.takeIf { !it.isEmpty }?.let(onChunkCommitted)
    }

    /**
     * Called when a backfill watchdog timer fires (strap went silent mid-offload). Clears state
     * WITHOUT acking - the open chunk was never durably committed.
     */
    fun timeoutFired() {
        isBackfilling = false
        synchronized(chunkLock) {
            chunk.clear()
            chunkOpen = false
        }
    }

    companion object {
        /** Cursor name for the strap's safe-trim watermark. */
        const val STRAP_TRIM_CURSOR = "strap_trim"

        /**
         * The 8-byte `end_data` the high-freq-sync ack requires: metadata.data[10:18]. The inner
         * record begins at frame[7] on WHOOP4 (end_data = frame[17:25]) and at frame[11] on WHOOP5/MG
         * (the +4 puffin envelope → end_data = frame[21:29]). The trim cursor is the first u32 of
         * end_data. Returns null if the frame is too short. Verified against a real WHOOP5 HISTORY_END
         * (trim=112193 at frame[21:25]).
         */
        fun endData(frame: ByteArray, family: DeviceFamily): ByteArray? {
            val start = if (family == DeviceFamily.WHOOP5) 21 else 17
            if (frame.size < start + 8) return null
            return frame.copyOfRange(start, start + 8)
        }

        /**
         * Pure per-chunk persistence tally. [rows] = biometric rows inserted (HR, R-R, SpO2, skin-temp,
         * resp, gravity - battery/events/steps are housekeeping, NOT biometric history, so they must not
         * inflate the count). [motion] = gravity rows (the sleep-critical signal). nights = distinct
         * day-keys (ts / 86400). Summed across a session by [finishChunk] to drive the success summary.
         */
        fun chunkTally(counts: InsertCounts, timestamps: List<Long>): Triple<Int, Int, Set<Long>> {
            val rows = counts.hr + counts.rr + counts.spo2 + counts.skinTemp + counts.resp + counts.gravity
            return Triple(rows, counts.gravity, timestamps.map { it / 86400L }.toSet())
        }

        /**
         * The one-line session success summary - the success-side log that never existed. Null when
         * nothing persisted, so a console-only / caught-up session stays quiet and the existing
         * empty-banking diagnostics speak instead.
         */
        fun sessionSummaryLine(rows: Int, motion: Int, skinTemp: Int, nights: Int): String? =
            if (rows <= 0) null
            else "Backfill: session persisted $rows rows ($motion with motion, $skinTemp skin-temp) across $nights night(s)."

        /**
         * The trim=0xFFFFFFFF sentinel line. 0xFFFFFFFF means two different things depending on whether
         * THIS run already banked rows: on the first end of a fresh offload it's the "no valid flash
         * cursor" state (no banked history, a clock/charge problem), but after an auto-continuation that
         * already persisted rows, the next end carries 0xFFFFFFFF to mean "caught up", not "no history".
         * Pick by [rowsPersisted]: > 0 gives a neutral caught-up line; 0 gives the genuine no-history
         * guidance. Pure so a fixture pins both.
         */
        fun noCursorLine(rowsPersisted: Int, continuedAfterRows: Boolean = false): String =
            when {
                rowsPersisted > 0 ->
                    "Backfill: reached the end of available history (trim=0xFFFFFFFF) - caught up after " +
                        "persisting $rowsPersisted row(s) this run. Nothing more to offload."
                // The empty tail of an auto-continue burst that banked rows in an EARLIER session. The
                // strap synced fine - this pass just confirms we're caught up - so DON'T false-alarm
                // "no banked history / charge to 100%".
                continuedAfterRows ->
                    "Backfill: reached the end of available history (trim=0xFFFFFFFF) - caught up; the " +
                        "strap handed over its banked history earlier this sync. Nothing more to offload."
                else ->
                    "Backfill: strap reported no flash cursor (trim=0xFFFFFFFF) - it has no banked history " +
                        "to offload. This is a clock/charge state on the strap, not a decode problem; fully " +
                        "charge it and reconnect so it starts banking."
            }

        /**
         * How far ahead of the wall clock a HISTORY_END's own timestamp may sit before we call the strap
         * RTC corrupt. The strap RTC and the phone normally agree within seconds; a genuine offload is
         * always dated in the PAST. A timestamp dated days into the FUTURE can only be a corrupt strap
         * clock. Generous (1 day) so ordinary skew or a timezone confusion never trips it.
         */
        const val FUTURE_RTC_TOLERANCE_SECONDS = 86_400L

        /**
         * Is this HISTORY_END timestamp an implausible FUTURE date (a corrupt strap RTC)? [endUnix] and
         * [wallNowUnix] are unix seconds in the same wall domain. Pure so a fixture pins the boundary.
         */
        fun isCorruptFutureRtc(endUnix: Long, wallNowUnix: Long): Boolean =
            endUnix > wallNowUnix + FUTURE_RTC_TOLERANCE_SECONDS

        /**
         * The recovery-hint line for a corrupt future-dated strap RTC. Names the cause plainly (the
         * strap's clock, not a NOOP bug) and gives the fix (charge + reconnect re-syncs the RTC). No
         * em-dash (project rule).
         */
        fun futureRtcLine(endUnix: Long, wallNowUnix: Long): String {
            val aheadDays = maxOf(0L, endUnix - wallNowUnix) / 86_400L
            return "Backfill: the strap reported a record dated about $aheadDays day(s) in the FUTURE - " +
                "its clock (RTC) is corrupt, not a NOOP problem. Those records can't be filed onto the " +
                "right day. Fully charge the strap to 100% and reconnect so it re-syncs its clock; if it " +
                "persists, forget and re-pair the strap."
        }
    }
}

/**
 * Durable key/value cursor store. The Room schema has no cursor table (see Entities.kt), so this
 * small SharedPreferences-backed store provides the equivalent durability WITHOUT touching the
 * Room schema or the build/manifest.
 *
 * FLAG: the cursor lives in SharedPreferences, separate from the Room DB, so cursor and rows do
 * not commit atomically together. The safe-trim ORDERING is preserved (decoded rows are inserted
 * and durable before the cursor is written, and the cursor is written before the ack), so the
 * worst case is a redundant re-offload of an already-stored chunk after a crash - never data
 * loss - because the decoded inserts are idempotent by natural key. If a Room `cursor` table is
 * later added, swap this implementation for a DAO-backed one.
 */
interface TrimCursorStore {
    suspend fun set(name: String, value: Long)
    suspend fun get(name: String): Long?
}

/** Default [TrimCursorStore] backed by a private SharedPreferences file. */
class PrefsTrimCursorStore(context: Context) : TrimCursorStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("noop_backfill_cursors", Context.MODE_PRIVATE)

    override suspend fun set(name: String, value: Long) {
        // commit() (synchronous) so durability is established before we ack the strap.
        prefs.edit().putLong(name, value).commit()
    }

    override suspend fun get(name: String): Long? =
        if (prefs.contains(name)) prefs.getLong(name, 0L) else null
}

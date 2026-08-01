package com.noop.data

import android.content.Context
import com.noop.protocol.DroppedRtcEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * Decoded streams to persist in one transaction, carrying the rows for a single
 * flush/backfill chunk. All `ts` values are wall-clock unix seconds (Long).
 *
 * The protocol/decoder layer builds one of these; deviceId is stamped at insert time
 * (not stored on the per-row sample models) and supplied to [WhoopRepository.insert].
 */
data class StreamBatch(
    val hr: List<HrRow> = emptyList(),
    val rr: List<RrRow> = emptyList(),
    val events: List<EventEntry> = emptyList(),
    val battery: List<BatteryRow> = emptyList(),
    val spo2: List<Spo2Row> = emptyList(),
    /**
     * WHOOP 5.0/MG sleep SpO2 percent (v18 @frame-82), decoded by whoop-rs. Distinct from [spo2] (the
     * WHOOP 4.0 raw red/IR ADC): a physiological % the strap itself computed. Persist-only. A WELLNESS
     * estimate, never medical. Empty on a WHOOP 4.0 / a non-sleep record.
     */
    val spo2Pct: List<Spo2PctRow> = emptyList(),
    val skinTemp: List<SkinTempRow> = emptyList(),
    /** The v18 record counter + optical telemetry that no biometric stream carries. */
    val v18: List<V18Row> = emptyList(),
    val resp: List<RespRow> = emptyList(),
    val gravity: List<GravityRow> = emptyList(),
    val steps: List<StepRow> = emptyList(),
    /**
     * The strap's OWN band sleep_state per record, carried verbatim off @81's high nibble. Optional
     * signal (only 5/MG v18 records emit it; a WHOOP 4.0 leaves it empty), consumed by the re-onset
     * confirm guard and shown as a Deep Timeline track. Never overrides the derived stage.
     */
    val sleepState: List<SleepStateRow> = emptyList(),
    /** HR derived from the WHOOP 5/MG v26 optical PPG waveform (autocorrelation). */
    val ppgHr: List<PpgHrRow> = emptyList(),
    /**
     * The RAW WHOOP 5/MG v26 optical PPG waveform itself, one record per second — the 24 Hz samples
     * [ppgHr] is derived FROM. Kept separate so a consumer that only wants the HR estimate never pays
     * for the 24x-larger raw stream. Persisted into `ppgWaveformSample` as a packed i16 BLOB.
     */
    val ppgWaveform: List<PpgWaveformRow> = emptyList(),
    /**
     * How many historical records this batch dropped for an implausible timestamp (before the
     * plausible floor, or more than a day ahead of now) — a bad strap clock/flash artefact. Diagnostic
     * only, excluded from [isEmpty]; logged once per session so a bad-clock strap is visible.
     */
    val droppedImplausibleTs: Int = 0,
    /**
     * Oldest / newest own-timestamp (unix seconds, the strap's own dated value) among records dropped
     * this batch for an implausible ts — tells a whole-range-future strap from one mixed with real
     * data. Diagnostic only (excluded from [isEmpty]); null when nothing was dropped.
     */
    val droppedImplausibleOldestTs: Long? = null,
    val droppedImplausibleNewestTs: Long? = null,
    /**
     * Strap RTC-STATE events (RTC_LOST / BOOT / SET_RTC) dropped for an implausible own-ts. The
     * implausible-ts gate discards them like any bad-ts record, but they are the ground truth that the
     * clock reset, so they are captured here. Diagnostic only; empty when none.
     */
    val droppedRtcEvents: List<DroppedRtcEvent> = emptyList(),
) {
    val isEmpty: Boolean
        get() = hr.isEmpty() && rr.isEmpty() && events.isEmpty() && battery.isEmpty() &&
            spo2.isEmpty() && spo2Pct.isEmpty() && skinTemp.isEmpty() && resp.isEmpty() && gravity.isEmpty() &&
            steps.isEmpty() && sleepState.isEmpty() && ppgHr.isEmpty() && ppgWaveform.isEmpty() &&
            v18.isEmpty()
}

// Device-agnostic decoded rows; deviceId is attached when inserted.
data class HrRow(val ts: Long, val bpm: Int)
data class RrRow(val ts: Long, val rrMs: Int)

/**
 * Attach a tiebreaker `seq` to each R-R interval before insert. Beats sharing one whole-second `ts`
 * need it: PK (deviceId, ts, rrMs) alone + IGNORE-on-conflict drops the second of two equal successive
 * intervals, biasing RMSSD high. Keying on (ts, rrMs, seq) keeps a distinct beat its own key even across
 * separate insert batches; re-syncing identical records reproduces the same key, so insert stays
 * idempotent. Numbering restarts per batch, so a caller must hand a wall-second over whole —
 * [liveFlushCutoff] is what keeps the live buffer doing that. Pure, testable.
 */
internal fun assignRrSeq(deviceId: String, rows: List<RrRow>): List<RrInterval> {
    val seqByBeat = HashMap<Pair<Long, Int>, Int>()
    val ordByTs = HashMap<Long, Int>()
    return rows.map { row ->
        val key = row.ts to row.rrMs
        val s = seqByBeat.getOrDefault(key, 0)
        seqByBeat[key] = s + 1
        // [rows] arrives in wire order, so the running count within a second IS the emission order.
        val o = ordByTs.getOrDefault(row.ts, 0)
        ordByTs[row.ts] = o + 1
        RrInterval(deviceId = deviceId, ts = row.ts, rrMs = row.rrMs, seq = s, ord = o)
    }
}

/** Buffered live rows (HR and R-R together) that arm a flush of the standard-profile stream. */
internal const val LIVE_FLUSH_ROWS = 30

/**
 * The exclusive `ts` a live flush may emit up to: everything strictly older than the newest buffered
 * wall-second, so [assignRrSeq] never numbers half a second. [closing] releases the held-back tail when
 * the link is going down. Returns a floor over an empty buffer, so nothing is emitted.
 */
internal fun liveFlushCutoff(hr: List<HrRow>, rr: List<RrRow>, closing: Boolean): Long =
    if (closing) Long.MAX_VALUE
    else maxOf(hr.maxOfOrNull { it.ts } ?: Long.MIN_VALUE, rr.maxOfOrNull { it.ts } ?: Long.MIN_VALUE)

/** payloadJSON is the deterministic sorted-keys JSON for the remaining parsed fields. */
data class EventEntry(val ts: Long, val kind: String, val payloadJSON: String)
data class BatteryRow(val ts: Long, val soc: Double?, val mv: Int?, val charging: Boolean? = null)
data class Spo2Row(val ts: Long, val red: Int, val ir: Int)
/** WHOOP 5.0/MG sleep SpO2 percent at [ts] (v18 @frame-82). deviceId attached on insert. Wellness estimate. */
data class Spo2PctRow(val ts: Long, val pct: Int)
/** [auxRaw1]/[auxRaw2] are the two auxiliary thermal registers riding the same record, in DECI-degrees
 *  where [raw] is centi-degrees. Null on a WHOOP 4.0 and on any record too short to carry them. */
data class SkinTempRow(val ts: Long, val raw: Int, val auxRaw1: Int? = null, val auxRaw2: Int? = null)

/**
 * The 5/MG v18 per-second channels that have no home on a biometric stream: the strap's own record
 * counter and its optical front-end telemetry. One row per v18 second, written alongside the streams
 * the same record produces. Instrumentation — no score or UI reads it.
 */
data class V18Row(
    val ts: Long,
    val recordIndex: Long? = null,
    val sleepStateRaw: Int? = null,
    val opticalBaselineA: Int? = null,
    val opticalBaselineB: Int? = null,
    val opticalAmpA: Int? = null,
    val opticalAmpB: Int? = null,
    val opticalSignalPoor: Boolean? = null,
    val rawU8At28: Int? = null,
    val rawU8At29: Int? = null,
    val rawU16At30: Int? = null,
    val rawF32At105: Double? = null,
    val rawU16At26: Int? = null,
    val unpinned: ByteArray? = null,
) {
    /** True when the record carried none of these, so the extractor can skip writing an all-null row. */
    val isEmpty: Boolean
        get() = recordIndex == null && sleepStateRaw == null && opticalBaselineA == null &&
            opticalBaselineB == null && opticalAmpA == null && opticalAmpB == null &&
            opticalSignalPoor == null && rawU8At28 == null && rawU8At29 == null &&
            rawU16At30 == null && rawF32At105 == null && rawU16At26 == null && unpinned == null
}
/**
 * Cumulative u16 step/motion counter at [ts] (WHOOP5 step_motion_counter@57). deviceId attached on
 * insert. [activityClass] is the per-record activity-class enum from @63: 0=still, 1=walk, 2=run; null
 * when the byte was 0xFF/invalid or absent.
 */
data class StepRow(val ts: Long, val counter: Int, val activityClass: Int? = null)
/**
 * The strap's OWN @81 high-nibble band sleep_state at [ts] (0 wake/1 still/2 asleep/3 up). deviceId
 * attached on insert.
 */
data class SleepStateRow(val ts: Long, val state: Int)
data class RespRow(val ts: Long, val raw: Int)
data class GravityRow(val ts: Long, val x: Double, val y: Double, val z: Double, val dynAccelG: Double? = null)
/** HR derived from the v26 PPG waveform: [ts] window-centre sec, [bpm], [conf] in 0…1. */
data class PpgHrRow(val ts: Long, val bpm: Int, val conf: Double)
/**
 * The RAW v26 optical PPG waveform for one strap-second: [ts] the record's wall-clock unix second,
 * [samples] the raw i16 ADC counts (usually 24, fewer on a truncated frame). deviceId attached on
 * insert; packed to a little-endian i16 BLOB by [StreamPersistence.packPpgSamples].
 */
data class PpgWaveformRow(val ts: Long, val samples: List<Int>)

/** Count of rows actually inserted per stream. */
data class InsertCounts(
    val hr: Int = 0,
    val rr: Int = 0,
    val events: Int = 0,
    val battery: Int = 0,
    val spo2: Int = 0,
    val skinTemp: Int = 0,
    val steps: Int = 0,
    val resp: Int = 0,
    val gravity: Int = 0,
)

/**
 * A compact snapshot of how much history each source holds, for the Data Sources "Freshness
 * Pipeline" card. Counts only — no per-day rows leave the read.
 */
data class DataFreshness(
    val importedDays: Int = 0,
    val computedDays: Int = 0,
    val appleDays: Int = 0,
    val importedSleeps: Int = 0,
    val computedSleeps: Int = 0,
    val earliestDay: String? = null,
    val latestDay: String? = null,
) {
    val hasAnyHistory: Boolean get() = importedDays > 0 || computedDays > 0 || appleDays > 0

    companion object {
        val EMPTY = DataFreshness()
    }
}

/**
 * One-time heal predicates, kept pure (no DB) so they are unit-testable on the JVM. A bad strap
 * clock/flash wrote rows with implausible timestamps; the heal purges them on upgrade so a normal
 * rescore recomputes the real days cleanly.
 *
 * Bounds mirror the ingest gate: a unix-second `ts` is implausible when below
 * [com.noop.protocol.MIN_PLAUSIBLE_UNIX] or above now + [com.noop.protocol.FUTURE_MARGIN] (one day). A
 * computed daily `day` ("yyyy-MM-dd") is implausible when it sorts after the local "today" key or
 * before the floor day. Same predicate the SQL deletes apply, exposed so a test pins the boundary.
 */
object HistoryHeal {
    /** True when a unix-second timestamp is outside the plausible window [min, nowSec + futureMargin]. */
    fun isImplausibleTs(
        ts: Long,
        nowSec: Long,
        minTs: Long = com.noop.protocol.MIN_PLAUSIBLE_UNIX,
        futureMargin: Long = com.noop.protocol.FUTURE_MARGIN,
    ): Boolean = ts < minTs || ts > nowSec + futureMargin

    /** True when a "yyyy-MM-dd" computed-day key is future (after [today]) or before [minDay]. ISO date
     *  strings sort lexicographically in chronological order, so a plain string compare is correct. */
    fun isImplausibleDay(day: String, today: String, minDay: String): Boolean =
        day > today || day < minDay
}

/**
 * Repository over [WhoopDatabase] / [WhoopDao] — the single seam the rest of the app uses to
 * read/write the local store. The phone does no metric computation here; daily/sleep rows are
 * an offline cache of server-computed values.
 */
class WhoopRepository(private val dao: WhoopDao) {

    constructor(db: WhoopDatabase) : this(db.whoopDao())

    // MARK: - Device

    suspend fun upsertDevice(id: String, mac: String? = null, name: String? = null) {
        val now = System.currentTimeMillis() / 1000
        // Preserve firstSeen on update: read existing, keep its firstSeen if present.
        val existing = dao.device(id)
        dao.upsertDevice(
            DeviceRow(
                id = id,
                mac = mac,
                name = name,
                firstSeen = existing?.firstSeen ?: now,
                lastSeen = now,
            )
        )
    }

    // MARK: - Insert decoded streams (idempotent by natural key)

    /**
     * Persist one decoded batch under [deviceId]. Returns the number of rows actually inserted
     * per stream (0 for rows that already existed). Empty sub-lists compile/run nothing.
     */
    suspend fun insert(streams: StreamBatch, deviceId: String): InsertCounts {
        if (streams.isEmpty) return InsertCounts()

        val hrIds = if (streams.hr.isEmpty()) emptyList() else
            dao.insertHr(streams.hr.map { HrSample(deviceId, it.ts, it.bpm) })
        val rrIds = if (streams.rr.isEmpty()) emptyList() else
            dao.insertRr(assignRrSeq(deviceId, streams.rr))
        val evIds = if (streams.events.isEmpty()) emptyList() else
            dao.insertEvents(streams.events.map { EventRow(deviceId, it.ts, it.kind, it.payloadJSON) })
        val batIds = if (streams.battery.isEmpty()) emptyList() else
            dao.insertBattery(streams.battery.map { BatterySample(deviceId, it.ts, it.soc, it.mv, it.charging) })
        val spo2Ids = if (streams.spo2.isEmpty()) emptyList() else
            dao.insertSpo2(streams.spo2.map { Spo2Sample(deviceId, it.ts, it.red, it.ir) })
        // WHOOP 5.0/MG sleep SpO2 percent (v18 @frame-82). Persist-only, same as sleepState/steps — the
        // strap's OWN physiological percent, decoded by whoop-rs. Idempotent by (deviceId, ts); not
        // counted into InsertCounts (no consumer reads a count). A wellness estimate.
        if (streams.spo2Pct.isNotEmpty()) {
            dao.insertSpo2Pct(streams.spo2Pct.map { Spo2PctSample(deviceId, it.ts, it.pct) })
        }
        val skinIds = if (streams.skinTemp.isEmpty()) emptyList() else
            dao.insertSkinTemp(streams.skinTemp.map { SkinTempSample(deviceId, it.ts, it.raw, it.auxRaw1, it.auxRaw2) })
        if (streams.v18.isNotEmpty()) {
            dao.insertV18(
                streams.v18.map {
                    V18Sample(
                        deviceId = deviceId,
                        ts = it.ts,
                        recordIndex = it.recordIndex,
                        sleepStateRaw = it.sleepStateRaw,
                        opticalBaselineA = it.opticalBaselineA,
                        opticalBaselineB = it.opticalBaselineB,
                        opticalAmpA = it.opticalAmpA,
                        opticalAmpB = it.opticalAmpB,
                        opticalSignalPoor = it.opticalSignalPoor,
                        rawU8At28 = it.rawU8At28,
                        rawU8At29 = it.rawU8At29,
                        rawU16At30 = it.rawU16At30,
                        rawF32At105 = it.rawF32At105,
                        rawU16At26 = it.rawU16At26,
                        unpinned = it.unpinned,
                    )
                },
            )
        }
        // activityClass (v13 column) is the @63 activity-class enum (0=still/1=walk/2=run) the decoder
        // already carries on each StepRow. it.activityClass is null when the @63 byte was
        // 0xFF/invalid/absent → stored as SQL NULL.
        val stepIds = if (streams.steps.isEmpty()) emptyList() else
            dao.insertSteps(streams.steps.map { StepSample(deviceId, it.ts, it.counter, it.activityClass) })
        // Band sleep_state. Persist-only, same as steps — the strap's OWN @81 high-nibble state (0
        // wake/1 still/2 asleep/3 up). Idempotent by (deviceId, ts); not counted into InsertCounts. The
        // raw 0-3 code is stored verbatim — a strap that never reports it inserts nothing.
        if (streams.sleepState.isNotEmpty()) {
            dao.insertSleepState(streams.sleepState.map { SleepStateSampleEntity(deviceId, it.ts, it.state) })
        }
        val respIds = if (streams.resp.isEmpty()) emptyList() else
            dao.insertResp(streams.resp.map { RespSample(deviceId, it.ts, it.raw) })
        val gravIds = if (streams.gravity.isEmpty()) emptyList() else
            dao.insertGravity(streams.gravity.map { GravitySample(deviceId, it.ts, it.x, it.y, it.z, dynAccelG = it.dynAccelG) })
        // v26 PPG-derived HR. Idempotent by (deviceId, ts); counted into InsertCounts.hr so the backfill
        // "persisted N" summary reflects HR recovered from the optical waveform too.
        val ppgHrIds = if (streams.ppgHr.isEmpty()) emptyList() else
            dao.insertPpgHr(streams.ppgHr.map { PpgHrSample(deviceId, it.ts, it.bpm, it.conf) })
        // RAW v26 optical PPG waveform — the samples ppgHr above is derived FROM. Persist-only, same as
        // steps/sleepState: not added to the InsertCounts return. Idempotent by (deviceId, ts);
        // IGNORE-on-conflict keeps the first-seen waveform for a second. Packed into one i16 BLOB per row.
        if (streams.ppgWaveform.isNotEmpty()) {
            dao.insertPpgWaveform(
                streams.ppgWaveform.map {
                    PpgWaveformSampleEntity(deviceId, it.ts, StreamPersistence.packPpgSamples(it.samples))
                },
            )
        }

        // OnConflictStrategy.IGNORE returns -1 for skipped (already-present) rows; count the inserts.
        return InsertCounts(
            hr = hrIds.countInserted() + ppgHrIds.countInserted(),
            rr = rrIds.countInserted(),
            events = evIds.countInserted(),
            battery = batIds.countInserted(),
            spo2 = spo2Ids.countInserted(),
            skinTemp = skinIds.countInserted(),
            steps = stepIds.countInserted(),
            resp = respIds.countInserted(),
            gravity = gravIds.countInserted(),
        )
    }

    /** Cheap whole-history raw-HR change fingerprint `"count:maxTs"`. The idle 15-min rescore backstop
     *  skips when this is unchanged since the last completed run. Any HR insert/delete moves it (count
     *  or maxTs), so a real change always rescores. */
    suspend fun hrFingerprint(): String = "${dao.countHr()}:${dao.maxHrTs()}"

    // MARK: - Server-derived caches (latest value wins on conflict)

    suspend fun upsertDailyMetrics(days: List<DailyMetric>) = dao.upsertDailyMetrics(days)

    /** Fill [day]'s blood oxygen only where it is absent; every other column is left alone. */
    suspend fun fillMissingSpo2(deviceId: String, day: String, pct: Double) =
        dao.fillMissingSpo2(deviceId, day, pct)
    suspend fun upsertSleepSessions(sessions: List<SleepSession>) = dao.upsertSleepSessions(sessions)

    /** Delete the computed source's cached daily rows whose day-key is in [from, to] (inclusive,
     *  yyyy-MM-dd). The local-day re-bucketing migration clears the computed UTC-keyed rows over the
     *  recompute window before re-upserting LOCAL-keyed rows. Imported rows are never touched. */
    suspend fun deleteComputedDailyInRange(deviceId: String, from: String, to: String) =
        dao.deleteDailyMetricsInRange(deviceId, from, to)

    /** Hand-correct the bed (onset) / wake (end) time of an existing sleep session, durably. The
     *  corrected onset is stored in [SleepSession.startTsAdjusted] while [SleepSession.startTs] stays the
     *  IMMUTABLE PK, so this upsert REPLACEs the row IN PLACE — a delete-then-reinsert would mutate the
     *  PK and let a later re-detect insert a second row beside the edited one, double-counting time in
     *  bed. [SleepSession.userEdited]=true keeps the overlap guard from re-inserting the detected twin;
     *  every other field is preserved via [SleepSession.copy].
     *
     *  [wakeSetByUser] says which bound the user actually moved. Only a wake the user set is banked in
     *  [SleepSession.endTsAdjusted] and frozen; a bed-only edit leaves it null and [SleepSession.endTs]
     *  detected, so [SleepStageHealer] may still refresh the end once more raw lands. */
    suspend fun updateSleepSessionTimes(
        session: SleepSession,
        newStartTs: Long,
        newEndTs: Long,
        wakeSetByUser: Boolean,
    ) {
        // Belt-and-braces: never persist a future-ending or inverted corrected window, whatever the UI
        // sent. The Sleep screen's own guards should make this unreachable; it is the last line so no
        // client misbehaviour can write a phantom night the display merge cannot render.
        val (safeStartTs, safeEndTs) = com.noop.analytics.SleepEditGuard.clampedEditWindow(
            newStartTs, newEndTs, System.currentTimeMillis() / 1000L,
        ) ?: return
        val reclipped = com.noop.analytics.SleepWindowReclip.reclip(
            session.stagesJSON, session.effectiveStartTs, session.effectiveEndTs, safeStartTs, safeEndTs,
        )
        dao.upsertSleepSessions(
            listOf(session.copy(
                startTsAdjusted = safeStartTs,
                endTsAdjusted = com.noop.analytics.SleepEditGuard.frozenWake(
                    session.endTsAdjusted, safeEndTs, wakeSetByUser,
                ),
                userEdited = true,
                stagesJSON = reclipped ?: session.stagesJSON,
            )),
        )
    }

    /** Remove a sleep session entirely — the delete half of [updateSleepSessionTimes] with no
     *  re-insert, keyed on the (deviceId, startTs) primary key. A DETECTED night is tombstoned FIRST so
     *  the recompute can't regenerate it (`endTs` is the span the engine's overlap test uses, since a
     *  re-detected onset can drift); a user-edited/created night is deleted WITHOUT a tombstone since it
     *  is never re-detected. Tombstone-before-delete is deliberate: the reverse order leaves a crash
     *  window where the row is gone but no tombstone exists, letting the next recompute resurrect it. */
    suspend fun deleteSleepSession(session: SleepSession) {
        if (com.noop.analytics.DismissedSleepGuard.writesTombstoneOnDelete(session.userEdited)) {
            dao.insertDismissedSleep(listOf(DismissedSleep(session.deviceId, session.startTs, session.effectiveEndTs)))
        }
        dao.deleteSleepSession(session.deviceId, session.startTs)
    }

    /** Undo a [deleteSleepSession]: lift the tombstone and restore the deleted row into its original
     *  namespace, preserving `userEdited` so the next analyze pass doesn't treat a hand-corrected night
     *  as a fresh detected twin. The tombstone lift is a no-op for a `userEdited` delete (which wrote none). */
    suspend fun undoDeleteSleepSession(session: SleepSession) {
        dao.deleteDismissedSleep(session.deviceId, session.startTs)
        dao.upsertSleepSessions(listOf(session))
    }

    /** Lift a deleted-sleep tombstone by (deviceId, startTs): the night regenerates from raw on the
     *  next analyze pass for a computed night. An imported night can't be re-created (no raw to
     *  re-derive); the caller shows that honest caption. */
    suspend fun allowSleepReDetection(deviceId: String, startTs: Long) =
        dao.deleteDismissedSleep(deviceId, startTs)

    /** Dedup heal: remove ONE sleep-session row WITHOUT a dismissal tombstone. Deletes stale
     *  timebase-shifted duplicates of a night whose canonical copy is staying; a tombstone here would
     *  overlap the surviving night's window and permanently suppress its re-detection. Only the engine's
     *  dedup heal calls this; the user-facing delete stays [deleteSleepSession]. */
    suspend fun deleteSleepSessionRowOnly(session: SleepSession) {
        dao.deleteSleepSession(session.deviceId, session.startTs)
    }

    /**
     * One-time heal: purge rows polluted by a bad-strap-clock timestamp. Deletes (a) raw stream rows
     * (HR/PPG-HR/RR/skinTemp/step/resp/gravity/spo2/event/battery) whose `ts` is implausible, and (b)
     * computed daily-metric + sleep-session rows whose day/ts is future or implausibly old — across every
     * device id, since bad raw rows sit under the strap id and bad computed rows under the "-noop" id.
     * Idempotent: a re-run matches nothing.
     *
     * Returns the total rows deleted (for the heal log). Bounds default to the ingest-gate constants;
     * [nowSec] / [today] / [minDay] are injectable so a test pins the boundary deterministically.
     */
    suspend fun healImplausibleTimestamps(
        nowSec: Long = System.currentTimeMillis() / 1000L,
        today: String = java.time.LocalDate.now().toString(),
        minTs: Long = com.noop.protocol.MIN_PLAUSIBLE_UNIX,
        futureMargin: Long = com.noop.protocol.FUTURE_MARGIN,
    ): Int {
        val maxTs = nowSec + futureMargin
        // The far-past floor day (local day of MIN_PLAUSIBLE_UNIX): a computed (`-noop`) row before this
        // predates NOOP, so it's bad-clock garbage and is purged. The floor applies ONLY to `-noop` rows
        // (a WHOOP CSV import keeps real historical dates); a day after `today` is always purged too.
        val minDay = java.time.Instant.ofEpochSecond(minTs)
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        var deleted = 0
        // (a) raw streams (all keyed by ts)
        deleted += dao.pruneHrByTs(minTs, maxTs)
        deleted += dao.prunePpgHrByTs(minTs, maxTs)
        deleted += dao.pruneRrByTs(minTs, maxTs)
        deleted += dao.pruneSkinTempByTs(minTs, maxTs)
        deleted += dao.pruneStepByTs(minTs, maxTs)
        deleted += dao.pruneRespByTs(minTs, maxTs)
        deleted += dao.pruneGravityByTs(minTs, maxTs)
        deleted += dao.pruneSpo2ByTs(minTs, maxTs)
        deleted += dao.pruneEventByTs(minTs, maxTs)
        deleted += dao.pruneBatteryByTs(minTs, maxTs)
        // (b) computed daily metrics (by day key) + sleep sessions (by startTs). The prune queries apply
        // the far-past floor ONLY to `-noop` computed rows, so a multi-year import (bare "my-whoop")
        // survives; future rows are always purged.
        deleted += dao.pruneDailyMetricByDay(today, minDay)
        deleted += dao.pruneSleepSessionByTs(minTs, maxTs)
        return deleted
    }

    /** Manually add a missed sleep session, typically a daytime nap the detector didn't pick up. Stages
     *  the chosen window from raw via [SleepStageHealer.restageFromRaw] (same density gate + stager the
     *  bed/wake edit uses), falling back to a single "wake" block when the strap has no dense data yet —
     *  the self-heal swaps in real stages once raw lands. Written under the COMPUTED source as its own
     *  session (userEdited=true, startTsAdjusted=null — the chosen onset IS the PK) so it is never
     *  folded into the main night. The chosen wake IS hand-set, so it is banked in endTsAdjusted and
     *  frozen against the end refresh. Purely additive — IGNORE-on-conflict makes a same-onset add a no-op. */
    suspend fun addManualNap(strapDeviceId: String, startTs: Long, endTs: Long) {
        // Belt-and-braces (same rule as updateSleepSessionTimes): a manually-added session can't end in
        // the future or invert; a future nap would otherwise own the tab's newest day as an all-awake
        // phantom. The clamped end is used verbatim.
        val (safeStartTs, safeEndTs) = com.noop.analytics.SleepEditGuard.clampedEditWindow(
            startTs, endTs, System.currentTimeMillis() / 1000L,
        ) ?: return
        val computedId = computedDeviceId(strapDeviceId)
        val stagesJSON = com.noop.analytics.SleepStageHealer.restageFromRaw(this, strapDeviceId, safeStartTs, safeEndTs)
            ?: com.noop.analytics.AnalyticsEngine.encodeStages(
                listOf(com.noop.analytics.StageSegment(start = safeStartTs, end = safeEndTs, stage = "wake")),
            )
        dao.insertSleepSession(
            SleepSession(
                deviceId = computedId,
                startTs = safeStartTs,
                endTs = safeEndTs,
                efficiency = sleepEfficiency(stagesJSON),
                stagesJSON = stagesJSON,
                userEdited = true,
                startTsAdjusted = null,
                endTsAdjusted = safeEndTs,
            ),
        )
    }

    /** Asleep fraction (light+deep+rem ÷ total in-bed) of a segment-array [stagesJSON], or null when the
     *  JSON is the fallback wake-only block / unparseable. Seeds a manual nap's efficiency so its footer
     *  reads sensibly before the next recompute re-derives it. */
    private fun sleepEfficiency(stagesJSON: String?): Double? {
        stagesJSON ?: return null
        val arr = runCatching { org.json.JSONArray(stagesJSON) }.getOrNull() ?: return null
        var asleep = 0.0
        var total = 0.0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val s = o.optLong("start", -1L)
            val e = o.optLong("end", -1L)
            val stage = o.optString("stage")
            if (s < 0 || e <= s) continue
            val dur = (e - s).toDouble()
            total += dur
            if (stage != "wake" && stage != "awake") asleep += dur
        }
        return if (total > 0 && asleep > 0) asleep / total else null
    }

    /** Narrow stages-ONLY write for the post-sync self-heal, driven by
     *  [com.noop.analytics.SleepStageHealer]. Replaces a user-edited night's stage breakdown with stages
     *  re-derived from the now-available raw, leaving the corrected bed/wake bounds and the userEdited
     *  flag untouched. Scoped to userEdited=1 rows by the DAO query; keyed by the IMMUTABLE
     *  [detectedStartTs]. Returns rows changed. */
    suspend fun updateSleepStages(deviceId: String, detectedStartTs: Long, stagesJSON: String): Int =
        dao.updateSleepStages(deviceId, detectedStartTs, stagesJSON)

    /** The other half of that heal: replace a user-edited night's DETECTED wake with the one a fresh
     *  detection gives, leaving the onset, the stages and the userEdited flag untouched. Scoped by the
     *  DAO to `userEdited = 1` rows whose `endTsAdjusted IS NULL`, so a wake the user set never moves;
     *  keyed by the IMMUTABLE [detectedStartTs]. Returns rows changed. */
    suspend fun refreshSleepEnd(deviceId: String, detectedStartTs: Long, detectedEndTs: Long): Int =
        dao.refreshSleepEnd(deviceId, detectedStartTs, detectedEndTs)

    // MARK: - Per-epoch sleep analytics (v18: motionJSON / sleepStateJSON). Banked beside stagesJSON on
    // the sleepSession row; written/read through targeted methods so the @Upsert recompute/import path
    // (which never names these columns) preserves them. An absent signal is stored as NULL and read back
    // as null, never a fabricated zero series; an EMPTY input array clears the column.

    /** Persist the SleepStager's per-epoch motion magnitudes for one session, keyed by the immutable
     *  detected [sessionStart]. Empty clears to NULL. Returns rows changed (0 when no such session). */
    suspend fun persistSessionMotion(deviceId: String, sessionStart: Long, motionEpochs: List<Double>): Int =
        dao.updateSessionMotion(deviceId, sessionStart, if (motionEpochs.isEmpty()) null else encodeDoubleArray(motionEpochs))

    /** The persisted per-epoch motion magnitudes for one session, or null when unset / unparseable. */
    suspend fun sessionMotion(deviceId: String, sessionStart: Long): List<Double>? =
        dao.sessionMotionJson(deviceId, sessionStart)?.let { decodeDoubleArray(it) }

    /** Per-epoch motion series for each of [starts] (detected session start keys), keyed by start.
     *  Motion is written ONLY under the computed ("-noop") source by the engine, so we read there; an
     *  imported-only night (no computed twin) has no motion (absent stays absent, never a fabricated
     *  zero array). Does NOT resolve the night: the caller has already chosen the main-night group and
     *  passes those blocks' starts; a start with no stored series is omitted. */
    suspend fun sessionMotions(strapDeviceId: String, starts: List<Long>): Map<Long, List<Double>> {
        if (starts.isEmpty()) return emptyMap()
        val computedId = computedDeviceId(strapDeviceId)
        val out = HashMap<Long, List<Double>>()
        for (start in starts) {
            val m = dao.sessionMotionJson(computedId, start)?.let { decodeDoubleArray(it) }
            if (!m.isNullOrEmpty()) out[start] = m
        }
        return out
    }

    /** Persist the decoded v18 band sleep_state per epoch for one session, keyed by [sessionStart].
     *  Empty clears to NULL. Returns rows changed. */
    suspend fun persistSessionSleepState(deviceId: String, sessionStart: Long, states: List<Int>): Int =
        dao.updateSessionSleepState(deviceId, sessionStart, if (states.isEmpty()) null else encodeIntArray(states))

    /** The persisted decoded v18 band sleep_state per epoch for one session, or null when unset. */
    suspend fun sessionSleepState(deviceId: String, sessionStart: Long): List<Int>? =
        dao.sessionSleepStateJson(deviceId, sessionStart)?.let { decodeIntArray(it) }

    suspend fun upsertMetricSeries(rows: List<MetricSeriesRow>) = dao.upsertMetricSeries(rows)
    suspend fun upsertJournal(rows: List<JournalEntry>) = dao.upsertJournal(rows)
    suspend fun upsertWorkouts(rows: List<WorkoutRow>) = dao.upsertWorkouts(rows)
    suspend fun upsertAppleDaily(rows: List<AppleDaily>) = dao.upsertAppleDaily(rows)

    // MARK: - Live Sessions (silent guardian, v22). The runner banks the row at start (endTs null) and
    // again at end (totals); the summary reads the recent rows for its guarded-count / streak line.
    suspend fun upsertLiveSession(row: LiveSessionRow) = dao.upsertLiveSession(row)
    suspend fun recentLiveSessions(deviceId: String, limit: Int): List<LiveSessionRow> =
        dao.recentLiveSessions(deviceId, limit)

    // MARK: - Lab Book markers (v17). Writing also projects the daily series into metricSeries under
    // WhoopDao.LAB_BOOK_SOURCE_ID, so Compare/Explore/Coach see markers unchanged.
    suspend fun upsertLabMarkers(rows: List<LabMarkerRow>) = dao.upsertLabMarkers(rows)
    suspend fun deleteLabMarker(id: String): Boolean = dao.deleteLabMarker(id)
    suspend fun labMarkersByKey(deviceId: String, markerKey: String) = dao.labMarkersByKey(deviceId, markerKey)
    suspend fun labMarkersByCategory(deviceId: String, category: String) = dao.labMarkersByCategory(deviceId, category)
    suspend fun markerKeysPresent(deviceId: String) = dao.markerKeysPresent(deviceId)

    // MARK: - Reads

    suspend fun hrSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.hrSamples(deviceId, from, to, limit)

    /**
     * HR samples over the registry read scope ([importedSourceIds]), deduped by ts with the active strap
     * winning. A strap banks its LIVE raw under its OWN id, so a read pinned to one id finds nothing and
     * the day looks frozen (Effort integrates to 0 off an empty series); the union surfaces every paired
     * strap's data plus the canonical import history. One id ⇒ byte-identical read.
     */
    suspend fun hrSamplesUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        List<HrSample> = mergeHrByTs(importedSourceIds().map { dao.hrSamples(it, from, to, limit) })

    /** Raw measured HR only (no v26 PPG-derived union) for the raw-sensor diagnostic export. */
    suspend fun rawHrSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.rawHrSamples(deviceId, from, to, limit)

    /** v26 PPG-derived HR samples (own stream) for the raw-sensor diagnostic export. */
    suspend fun ppgHrSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.ppgHrSamples(deviceId, from, to, limit)

    /**
     * The RAW v26 optical PPG waveform, one record per second, in [from, to] for one device, ascending
     * by ts. [PpgWaveformRow.samples] are the raw i16 ADC counts the strap sent, unpacked from the
     * compact on-disk BLOB ([StreamPersistence.packPpgSamples]/[unpackPpgSamples]). Empty when the strap
     * never emitted v26 (the WHOOP 4.0 / v18-only case) or the window has no v26-heavy stretch.
     */
    suspend fun ppgWaveformSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        List<PpgWaveformRow> =
        dao.ppgWaveformSamples(deviceId, from, to, limit)
            .map { PpgWaveformRow(it.ts, StreamPersistence.unpackPpgSamples(it.samples)) }

    /** Downsampled HR (mean bpm per [bucketSeconds]) for the strap, for the Today 24h trend chart. */
    suspend fun hrBuckets(deviceId: String, from: Long, to: Long, bucketSeconds: Long = 300L) =
        dao.hrBuckets(deviceId, from, to, bucketSeconds)

    /**
     * Downsampled HR buckets over the registry read scope ([importedSourceIds]), deduped by bucket start
     * with the active strap winning. Keeps the Today HR curve pointed at whichever ids the user's straps
     * actually bank under. One id ⇒ byte-identical read.
     */
    suspend fun hrBucketsUnion(from: Long, to: Long, bucketSeconds: Long = 300L):
        List<HrBucket> = mergeHrBucketsByStart(
            importedSourceIds().map { dao.hrBuckets(it, from, to, bucketSeconds) },
        )

    /**
     * DISPLAY-ONLY: reconcile a workout's shown HR with the strap trace that drives its graph / zones /
     * effort. The stored `avgHr` and the strap's ~1 Hz trace over [startTs, endTs] can DIVERGE (a
     * hand-edited Avg changes the number but not the trace), so the stored field defers to the trace
     * when present: STRAP-NATIVE rows (source "manual" or "<id>-noop") get Avg/max ALWAYS recomputed as
     * the trace's true mean/peak; IMPORTED rows keep their own avg/max and are only FILLED when null,
     * never overriding a real imported value. [minSamples] (~1 min) guards against a few stray samples
     * fabricating an average; NEVER persisted — a read-time projection re-derived every load.
     */
    suspend fun fillWorkoutHrFromStrap(
        rows: List<WorkoutRow>,
        strapDeviceId: String = "my-whoop",
        minSamples: Long = 60,
        cap: Int = 300,
        // The user's HRmax + sex. When supplied, a strap-native row whose Effort (strain) is null gets one
        // recomputed from the strap trace on display, so a session that ended with sparse HR doesn't read a
        // blank Effort. null (default) leaves existing call sites byte-identical. Display-only; the durable
        // value is written by IntelligenceEngine.rescoreManualWorkouts.
        strainMaxHR: Double? = null,
        strainSex: String = "male",
    ): List<WorkoutRow> {
        var budget = cap
        return rows.map { row ->
            if (row.endTs <= row.startTs || budget <= 0) return@map row
            // Strap-native rows are graphed/zoned/scored from the strap trace, so their Avg HR must come
            // from that same trace (recompute, overriding any stored/edited value). Imported rows keep
            // their own avg/max and are only filled when missing.
            val src = row.source.lowercase()
            val strapNative = src == "manual" || src.endsWith("-noop")
            // A strap-native row still missing a strain is a fill target even when its avgHr is present.
            val needsStrainFill = strapNative && row.strain == null && strainMaxHR != null
            if (!strapNative && row.avgHr != null && !needsStrainFill) return@map row
            budget -= 1
            val stats = dao.hrWindowStats(strapDeviceId, row.startTs, row.endTs)
            if (stats.n < minSamples || stats.avg == null || stats.max == null) return@map row
            // Recompute Effort from the SAME samples the graph/zones use. Read the raw window ONLY when
            // this row actually needs a strain (keeps the common no-fill path a single aggregate query),
            // and let StrainScorer return null on a still-too-thin window (never a fabricated number).
            val filledStrain = if (needsStrainFill && strainMaxHR != null) {
                val samples = dao.hrSamples(strapDeviceId, row.startTs, row.endTs, 8000)
                com.noop.analytics.StrainScorer.strain(samples, maxHR = strainMaxHR, sex = strainSex)
            } else null
            if (strapNative) {
                // True mean / peak of the very samples the graph + zones + effort use; FILL a null Effort
                // (never override a stored one) from the recompute.
                row.copy(avgHr = stats.avg.roundToInt(), maxHr = stats.max,
                         strain = row.strain ?: filledStrain)
            } else {
                // Imported row with no avg , fill from strap, preserving any imported max.
                row.copy(avgHr = stats.avg.roundToInt(), maxHr = row.maxHr ?: stats.max)
            }
        }
    }

    suspend fun rrIntervals(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.rrIntervals(deviceId, from, to, limit)

    /**
     * R-R over the registry read scope, PICKING ONE source for the window rather than merging: the id
     * with the most beats wins, ties keeping the earlier (active-first) one. Two straps' beat streams
     * interleaved are not a measured stream — any HRV over the mixture would be fabricated — so this
     * never combines them ([pickRichestRr]). One id ⇒ that id's rows verbatim.
     */
    suspend fun rrIntervalsUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT): List<RrInterval> =
        pickRichestRr(importedSourceIds().map { dao.rrIntervals(it, from, to, limit) })

    suspend fun events(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.events(deviceId, from, to, limit)

    suspend fun batterySamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.batterySamples(deviceId, from, to, limit)

    suspend fun spo2Samples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.spo2Samples(deviceId, from, to, limit)

    /** WHOOP 5.0/MG sleep SpO2 percent samples (v18 @frame-82) in [from, to], ascending. Feeds the nightly
     *  median banked on [DailyMetric.spo2Pct]. Empty on a WHOOP 4.0 / an unbanked window. Wellness estimate. */
    suspend fun spo2PctSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.spo2PctSamples(deviceId, from, to, limit)

    suspend fun skinTempSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.skinTempSamples(deviceId, from, to, limit)

    suspend fun stepSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.stepSamples(deviceId, from, to, limit)

    /**
     * The strap's OWN band sleep_state samples in [from, to] as (ts, state) pairs, ascending. Feeds
     * the Deep Timeline band-state track and the per-session grid the re-onset confirm guard reads. Empty
     * when the strap never reported it (a WHOOP 4.0, or a not-yet-offloaded window).
     */
    suspend fun sleepStateSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        List<SleepStateRow> =
        dao.sleepStateSamples(deviceId, from, to, limit).map { SleepStateRow(it.ts, it.state) }

    /**
     * The latest (greatest-ts) non-null @63 activity class over [from, to], read across the registry read
     * scope ([importedSourceIds]), for the Steps tile icon. A strap banks its LIVE step samples under its
     * OWN id, exactly like HR, so a read pinned to one id returned nothing and the tile icon vanished. One
     * id ⇒ byte-identical read. A ts tie favours the active strap (scanned first by [latestActivityClass]).
     */
    suspend fun stepActivityClassLatestUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        Int? = latestActivityClass(importedSourceIds().map { dao.stepSamples(it, from, to, limit) })

    /** Delete a computed source's [sport] workouts in [from, to] (makes re-detection idempotent). */
    suspend fun deleteComputedWorkouts(deviceId: String, sport: String, from: Long, to: Long) =
        dao.deleteWorkoutsBySport(deviceId, sport, from, to)

    // MARK: - Workout editing (manual add/edit · relabel · dismiss · delete)
    //
    // Manual workouts live under the strap source ([strapDeviceId], source "manual") — the same place
    // live-tracked sessions land. Detected bouts live under "<strapDeviceId>-noop" with sport "detected"
    // and are wiped + re-derived each engine run, so a durable dismissal is recorded in the independent
    // `dismissedWorkout` table.

    /** Dismissed detected-bout markers across the read scope's computed sources — the scope the display
     *  reads, so a bout dismissed under one strap's id stays dismissed once another strap is in scope. */
    suspend fun dismissedDetected(): List<DismissedWorkout> =
        computedSourceIds().flatMap { dao.dismissedWorkouts(it) }

    /** Deleted-sleep tombstones for BOTH the imported and computed sources across the read scope.
     *  [deleteSleepSession] writes the tombstone under the deleted row's OWN `session.deviceId`, so
     *  reading only the computed id would miss a deleted imported night's tombstone and let a strap
     *  re-detection resurrect it as a computed twin. Reading every id in scope finds either. De-duping
     *  on (deviceId, startTs) is unnecessary — the id namespaces never collide. */
    suspend fun dismissedSleeps(): List<DismissedSleep> =
        importedSourceIds().flatMap { dao.dismissedSleeps(it) + dao.dismissedSleeps(computedDeviceId(it)) }

    /** Hand-edited computed nights in [from, to], read across the SAME computed union the display uses
     *  ([computedSleepSessionsUnion]) rather than one id. The edit twin of [dismissedSleeps]: an edit
     *  made before the active strap id changed lives under the old computed namespace, and a read
     *  pinned to the current one misses it, so the recompute re-creates the detected twin beside it.
     *  Each row keeps its own `deviceId` — the heal writes back under that, never a passed-in id. */
    suspend fun editedSleeps(from: Long, to: Long): List<SleepSession> =
        computedSleepSessionsUnion(from, to).filter { it.userEdited }

    /**
     * Persist a retroactive / edited manual workout under the strap source. [replacing] is the row the
     * edit started from:
     *  - editing a DETECTED bout replaces it with this manual row — the detected original is dismissed
     *    durably so the re-detector doesn't bring it back;
     *  - editing a MANUAL row whose natural key (startTs/sport) changed deletes the stale row first (the
     *    (deviceId, startTs, sport) PK upsert would otherwise orphan it);
     *  - an IMPORTED row is never passed here as `replacing` (duplicating one is a pure add).
     */
    suspend fun saveManualWorkout(row: WorkoutRow, replacing: WorkoutRow? = null) {
        if (replacing != null && replacing.source.lowercase().endsWith("-noop")) {
            dismissDetected(replacing)
        } else if (replacing != null && (replacing.startTs != row.startTs || replacing.sport != row.sport)) {
            dao.deleteWorkoutByKey(replacing.deviceId, replacing.startTs, replacing.sport)
        }
        dao.upsertWorkouts(listOf(row))
    }

    /**
     * Re-label a detected bout: copy it to a manual strap row with the chosen [sport], then delete the
     * detected original. Survives analyzeRecent — the engine re-derives only sport="detected" rows and
     * skips any re-derived bout overlapping a real strap workout, which this copy now is, so the same
     * session is never re-created as a duplicate.
     */
    suspend fun relabelDetected(row: WorkoutRow, sport: String, strapDeviceId: String = "my-whoop") {
        val trimmed = sport.trim()
        if (trimmed.isEmpty()) return
        val manual = row.copy(deviceId = strapDeviceId, sport = trimmed, source = "manual")
        dao.upsertWorkouts(listOf(manual))
        dao.deleteWorkoutsBySport(computedDeviceId(strapDeviceId), "detected", row.startTs, row.startTs)
    }

    /**
     * Dismiss a DETECTED bout the user says isn't a workout: record a durable marker (so a re-detect
     * that recreates the same PK stays hidden) AND delete the current row so it disappears now.
     * No-op when the row isn't a detected bout.
     */
    suspend fun dismissDetected(row: WorkoutRow) {
        if (!row.source.lowercase().endsWith("-noop")) return
        // Marker carries the bout's [startTs, endTs] span so a re-detected bout whose boundary drifts
        // still overlaps it and stays hidden.
        dao.insertDismissed(listOf(DismissedWorkout(row.deviceId, row.startTs, row.endTs)))
        dao.deleteWorkoutsBySport(row.deviceId, row.sport, row.startTs, row.startTs)
    }

    /**
     * Delete ONE workout. A detected bout is dismissed durably (so it doesn't come back on the next
     * re-detect); everything else is removed by its exact natural key.
     */
    suspend fun deleteWorkout(row: WorkoutRow) {
        if (row.source.lowercase().endsWith("-noop")) { dismissDetected(row); return }
        dao.deleteWorkoutByKey(row.deviceId, row.startTs, row.sport)
    }

    /**
     * Merge two-or-more overlapping / adjacent MANUAL or DETECTED sessions into ONE manual session
     * ([merged], built by the pure [com.noop.ui.WorkoutMerge.merge]), then retire the originals. Imported
     * history is NEVER passed here (the caller gates on WorkoutMerge.canMerge), so the imported-read-only
     * invariant holds. The route re-key is a field copy (no side-store): keep the longest original route.
     * The caller reloads after rescoring strain from strap HR.
     */
    suspend fun mergeWorkouts(originals: List<WorkoutRow>, merged: WorkoutRow) {
        if (originals.size < 2) return
        // Keep the longest original route on the merged row.
        val keptRoute = originals.mapNotNull { it.routePolyline }.maxByOrNull { it.length }
        val mergedWithRoute = if (keptRoute != null) merged.copy(routePolyline = keptRoute) else merged
        saveManualWorkout(mergedWithRoute)
        // Retire each original. Skip any row whose natural key matches the merged row's, so we never
        // dismiss/delete the span the merged row now owns.
        for (r in originals) {
            if (r.startTs == merged.startTs && r.sport == merged.sport) continue
            when {
                r.source.lowercase().endsWith("-noop") -> dismissDetected(r)
                r.source.lowercase() == "manual" -> dao.deleteWorkoutByKey(r.deviceId, r.startTs, r.sport)
                // Defensive: canMerge already excludes imported rows; never rewrite imported history.
                else -> continue
            }
        }
    }

    /**
     * Bulk-delete the selected sessions, routing per class exactly like the single-row path
     * (detected -> durable dismiss, manual -> delete). Imported rows are never selectable so never reach
     * here. The caller reloads afterwards.
     */
    suspend fun bulkDeleteWorkouts(rows: List<WorkoutRow>) {
        for (r in rows) {
            when {
                r.source.lowercase().endsWith("-noop") -> dismissDetected(r)
                r.source.lowercase() == "manual" -> dao.deleteWorkoutByKey(r.deviceId, r.startTs, r.sport)
                else -> continue
            }
        }
    }

    suspend fun respSamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.respSamples(deviceId, from, to, limit)

    suspend fun gravitySamples(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.gravitySamples(deviceId, from, to, limit)

    // The raw-stream union reads: the registry read scope, deduped by ts with the active strap winning
    // ([mergeByTs]). Twins of [hrSamplesUnion], so every stream a chart draws follows ONE scope. The
    // single-id forms above stay for the WRITE-side callers (the engine scores one strap's raw into that
    // strap's own "-noop" scores, so those must NOT widen).
    suspend fun spo2SamplesUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        mergeByTs(importedSourceIds().map { dao.spo2Samples(it, from, to, limit) }) { it.ts }

    suspend fun spo2PctSamplesUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        mergeByTs(importedSourceIds().map { dao.spo2PctSamples(it, from, to, limit) }) { it.ts }

    suspend fun skinTempSamplesUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        mergeByTs(importedSourceIds().map { dao.skinTempSamples(it, from, to, limit) }) { it.ts }

    suspend fun respSamplesUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        mergeByTs(importedSourceIds().map { dao.respSamples(it, from, to, limit) }) { it.ts }

    suspend fun gravitySamplesUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        mergeByTs(importedSourceIds().map { dao.gravitySamples(it, from, to, limit) }) { it.ts }

    suspend fun stepSamplesUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        mergeByTs(importedSourceIds().map { dao.stepSamples(it, from, to, limit) }) { it.ts }

    suspend fun sleepStateSamplesUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT): List<SleepStateRow> =
        mergeByTs(
            importedSourceIds().map { id ->
                dao.sleepStateSamples(id, from, to, limit).map { SleepStateRow(it.ts, it.state) }
            },
        ) { it.ts }

    suspend fun sleepSessions(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT) =
        dao.sleepSessions(deviceId, from, to, limit)

    /**
     * The user's learned habitual midsleep (local time-of-day seconds) for [deviceId], or null under
     * [com.noop.analytics.SleepStageTotals.HABITUAL_MIN_DAYS] of history (cold-start). Computed exactly
     * as `IntelligenceEngine.computeHabitualMidsleep` does — the same raw imported + computed sleep-session
     * union, one HistoryBlock per session (dayKey = the local calendar day of the midpoint), deferring to
     * the same shared [com.noop.analytics.SleepStageTotals.habitualMidsleepSec] pure function, so the
     * Sleep tab's main-night pick aligns with the analytics rollup for a shift/late sleeper too. Reads a
     * wide window so the distinct-day count clears the threshold; `habitualMidsleepSec` keeps the longest
     * block per day, so window/order/source merge differences wash out.
     */
    suspend fun habitualMidsleepSec(days: Int = 4000): Long? {
        val now = System.currentTimeMillis() / 1000L
        val lo = now - days * 86_400L
        val hi = now + 86_400L
        // The registry read scope (imported) and its computed siblings, collapsing one night recorded
        // under several ids so it doesn't double-weight the learner. Reading one id narrowed the night
        // set (the learner could cold-start to null instead of returning a learned value).
        val imported = dedupSleepBlocks(importedSourceIds().map { dao.sleepSessions(it, lo, hi, 4000) })
        val computed = dedupSleepBlocks(computedSourceIds().map { dao.sleepSessions(it, lo, hi, 4000) })
        val offsetSec = (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000).toLong()
        val blocks = (imported + computed).mapNotNull { s ->
            val start = s.effectiveStartTs
            val end = s.effectiveEndTs
            if (end <= start) {
                null
            } else {
                val mid = start + (end - start) / 2
                com.noop.analytics.SleepStageTotals.HistoryBlock(
                    start, end, com.noop.analytics.AnalyticsEngine.dayString(mid, offsetSec),
                )
            }
        }
        return com.noop.analytics.SleepStageTotals.habitualMidsleepSec(blocks, offsetSec)
    }

    suspend fun metricSeries(deviceId: String, key: String, from: String, to: String) =
        dao.metricSeries(deviceId, key, from, to)

    /**
     * Computed ("-noop") [key] series over the registry read scope's computed siblings, deduped per day
     * with the active strap winning. The weekly scores are written under "<deviceId>-noop", so reading
     * only the canonical id misses a live BLE strap.
     */
    suspend fun metricSeriesComputedUnion(
        key: String,
        from: String,
        to: String,
    ): List<MetricSeriesRow> {
        val ids = computedSourceIds()
        if (ids.size == 1) return metricSeries(ids[0], key, from, to)
        return mergeComputedSeriesUnion(ids.map { metricSeries(it, key, from, to) })
    }

    /**
     * The LATEST computed ("-noop") [key] row across the active-strap union, or null — the LIMIT-1
     * twin of [metricSeriesComputedUnion] for "latest value" tiles (Today pinned cards, the Health
     * hub heroes), which were materializing the FULL series just to take `.lastOrNull()`. Reads one
     * indexed row per source id; the newest day wins, and on a shared newest day the ACTIVE strap
     * wins (ids are active-first) — byte-identical to `metricSeriesComputedUnion(...).lastOrNull()`.
     */
    suspend fun latestMetricComputedUnion(key: String): MetricSeriesRow? =
        latestFromPerSourceLatest(
            computedSourceIds().map { dao.latestMetricSeriesRow(it, key) },
        )

    /** Scalar row count for one (deviceId, key) series — the COUNT twin of [metricSeries]. */
    suspend fun metricSeriesKeyCount(deviceId: String, key: String): Int =
        dao.metricSeriesKeyCount(deviceId, key)

    /** Distinct metric keys present for a [deviceId]/source, sorted ascending. */
    suspend fun metricKeys(deviceId: String): List<String> = dao.metricKeys(deviceId)

    /** Workouts whose startTs falls in [from, to] (unix seconds), oldest first, row-limited. */
    suspend fun workouts(deviceId: String, from: Long, to: Long, limit: Int = DEFAULT_LIMIT): List<WorkoutRow> =
        dao.workouts(deviceId, from, to, limit)

    /** Scalar COUNT twin of [workouts] (exact total, no row limit) for count badges. */
    suspend fun workoutsCount(deviceId: String, from: Long, to: Long): Int =
        dao.workoutsCount(deviceId, from, to)

    /** Journal entries for the inclusive day range [from, to] (YYYY-MM-DD), oldest first. */
    suspend fun journal(deviceId: String, from: String, to: String): List<JournalEntry> =
        dao.journal(deviceId, from, to)

    /** Delete one native journal answer by natural key (only ever called with the "noop-journal"
     *  source id , imported rows are never touched). */
    suspend fun deleteJournalEntry(deviceId: String, day: String, question: String) =
        dao.deleteJournalEntry(deviceId, day, question)

    /** Atomically replace a device's imported journal within a day range — the WHOOP importer
     *  clears the span it re-writes and upserts in ONE transaction, so a crash mid-import can't drop
     *  the range's journal and the wake-day re-keying leaves no onset-keyed duplicates. */
    suspend fun replaceJournalRange(deviceId: String, from: String, to: String, rows: List<JournalEntry>) =
        dao.replaceJournalRange(deviceId, from, to, rows)

    /** Apple-Health daily aggregates for the inclusive day range [from, to] (YYYY-MM-DD), oldest first. */
    suspend fun appleDaily(deviceId: String, from: String, to: String): List<AppleDaily> =
        dao.appleDaily(deviceId, from, to)

    /** Scalar COUNT twin of [appleDaily] for count badges. */
    suspend fun appleDailyCount(deviceId: String, from: String, to: String): Int =
        dao.appleDailyCount(deviceId, from, to)

    /** All cached daily metrics for a device, oldest first. Feeds com.noop.analytics.IllnessWatch. */
    suspend fun days(deviceId: String): List<DailyMetric> = dao.days(deviceId)

    /** Scalar COUNT twin of [days] for count badges. */
    suspend fun daysCount(deviceId: String): Int = dao.daysCount(deviceId)

    /** Earliest/latest cached day-key for a source as (first, last), each null when it has no daily rows. */
    suspend fun dayBounds(deviceId: String): Pair<String?, String?> =
        dao.minDay(deviceId) to dao.maxDay(deviceId)

    /** Every distinct source id with at least one cached daily row. Feeds the Health Connect
     *  backfill's strap-coverage gate (see HealthConnectImporter.isStrapNativeSourceId). */
    suspend fun dailyMetricDeviceIds(): List<String> = dao.dailyMetricDeviceIds()

    /**
     * Delete the Health-Connect-shaped "my-whoop" sleep sessions an import wrote over strap-covered
     * nights: un-edited, signal-less windows (no efficiency/HR/HRV/motion) overlapping ANY computed
     * ("-noop") session. Never matches a WHOOP CSV / wearable-export import (those carry efficiency or
     * HR/HRV) or a user-edited row. Idempotent; returns the rows deleted.
     */
    suspend fun purgeHcShadowedSleepDays(): Int = dao.purgeHcShadowedSleepSessions()

    /**
     * One-shot repair for daily rows Health Connect wrote under "my-whoop" before it had its own source:
     * move them to "health-connect", where they gap-fill instead of outranking a strap-measured day. The
     * predicate is a provenance proof, not a heuristic — see [WhoopDao.moveHcDailyRowsToOwnSource]. A row
     * it cannot prove is left where it is, so a "my-whoop" a real strap adopted is never disturbed.
     * Idempotent; returns the rows moved.
     */
    suspend fun refileHcDailyRows(): Int = dao.moveHcDailyRowsToOwnSource()

    /**
     * One-time refile: move legacy Health Connect data out of the shared "apple-health" bucket into
     * its own "health-connect" source, so it stops being shown as Apple Health. HC workouts are tagged
     * `source = "health-connect"` so they move unconditionally; the daily aggregates only move when there
     * is no Apple Health EXPORT (no apple-health metricSeries), since only the export writes metricSeries.
     * Idempotent + safe (runs before this import writes any HC data, so no PK conflict).
     */
    suspend fun refileLegacyHealthConnect() {
        dao.reassignWorkoutsBySource(from = "apple-health", to = "health-connect", source = "health-connect")
        if (dao.metricSeriesCount("apple-health") == 0) {
            dao.reassignAppleDaily(from = "apple-health", to = "health-connect")
            upsertDevice("health-connect", name = "Health Connect")
        }
    }

    // MARK: - Merged reads (imported wins per day; computed "-noop" gap-fills; phone fills last)
    //
    // IntelligenceEngine persists on-device scores under "<deviceId>-noop"; the dashboard should see
    // BOTH sources so a strap-only user still gets a populated dashboard, while a real WHOOP import
    // always wins on the days it covers. [GAP_FILL_SOURCE_IDS] is a third bucket below both: a phone
    // aggregate fills a column nothing above it has and never replaces one that does. The screens point
    // their "my-whoop" reads at these merged variants (no DAO/schema change; precedence lives in one place).

    /** The computed-source id for a given imported [deviceId] (e.g. "my-whoop" → "my-whoop-noop"). */
    fun computedDeviceId(deviceId: String): String = "$deviceId-noop"

    /** Every registered source row, oldest first — the input every read scope is derived from. */
    suspend fun pairedDevices(): List<PairedDeviceRow> = dao.pairedDevices()

    /** The read-side union ids, resolved from the registry on each read (see [importedSourceIdsFor] /
     *  [computedSourceIdsFor]). No id parameter: a read scope has one derivation, not a caller's guess. */
    suspend fun importedSourceIds(): List<String> = importedSourceIdsFor(dao.pairedDevices())
    suspend fun computedSourceIds(): List<String> = computedSourceIdsFor(dao.pairedDevices())

    /** The reactive twin for the Flow reads: re-resolves when the registry changes, and only re-emits
     *  when the ID LIST moves (a `lastSeenAt` stamp must not resubscribe the dashboard). */
    private fun importedSourceIdsFlow(): Flow<List<String>> =
        dao.pairedDevicesFlow().map { importedSourceIdsFor(it) }.distinctUntilChanged()

    /**
     * The on-device DATA VOLUME read FRESH from the store (never the reactive dashboard caches), for
     * the Display & Performance test mode's `dataVolume` line:
     *   - dbRows = the raw decoded-stream footprint (HR + RR + events + the biometric streams), the dominant cost;
     *   - importedDays = imported daily-metric rows across the read scope;
     *   - workouts = recorded/detected workout-row count across the read scope;
     *   - lastRenderRows = the size of the merged DAILY set the dashboard renders: the union of distinct days
     *     across the three daily sources (imported straps + on-device computed + Apple).
     * The sources come from the registry read scope ([importedSourceIds]), so this measures what the
     * dashboard actually renders. Pure store reads: nothing reactive mutates, so calling it never
     * perturbs the screens it measures. Best-effort: a read failure contributes 0 rather than throwing.
     */
    suspend fun dataVolumeSnapshot(): com.noop.analytics.DataVolume {
        val dbRows = runCatching {
            dao.countHr() + dao.countRr() + dao.countEvents() + dao.countSpo2() +
                dao.countSkinTemp() + dao.countSteps() + dao.countResp() + dao.countGravity()
        }.getOrDefault(0)
        val ids = runCatching { importedSourceIds() }.getOrDefault(listOf(WHOOP_SOURCE))
        val imported = runCatching { unionByDay(ids.map { dao.days(it) }) }.getOrDefault(emptyList())
        val workouts = runCatching {
            dedupWorkoutsByKey(ids.flatMap { dao.workouts(it, 0L, 4_102_444_800L, 1_000_000) })
        }.getOrDefault(emptyList())
        // The merged daily read-set the dashboard renders over: union of distinct days across the three
        // daily sources.
        val computed = runCatching {
            unionByDay(ids.map { dao.days(computedDeviceId(it)) })
        }.getOrDefault(emptyList())
        val apple = runCatching { dao.days(APPLE_HEALTH_SOURCE) }.getOrDefault(emptyList())
        val renderDays = HashSet<String>()
        for (m in imported) renderDays.add(m.day)
        for (m in computed) renderDays.add(m.day)
        for (m in apple) renderDays.add(m.day)
        return com.noop.analytics.DataVolume(
            dbRows = dbRows,
            importedDays = imported.size,
            workouts = workouts.size,
            lastRenderRows = renderDays.size,
        )
    }

    /**
     * Per-table row counts for meta.json's storage block, read via the store (the same counts
     * [dataVolumeSnapshot] sums), so a Test Centre export shows the real on-device footprint.
     * Best-effort: a read failure returns empty (the caller's zeroed fallback stays an honest
     * "unreadable"), never a fabricated figure.
     */
    suspend fun storageRowCounts(): Map<String, Int> = runCatching {
        mapOf(
            "hr" to dao.countHr(), "rr" to dao.countRr(), "events" to dao.countEvents(),
            "battery" to dao.countBattery(), "spo2" to dao.countSpo2(),
            "skinTemp" to dao.countSkinTemp(), "steps" to dao.countSteps(),
            "resp" to dao.countResp(), "gravity" to dao.countGravity(),
        )
    }.getOrDefault(emptyMap())

    /**
     * All cached daily metrics, oldest first, merged with the on-device computed "-noop" scores.
     * Imported rows win per day; computed rows fill the days the import doesn't cover.
     *
     * Both buckets are read over the registry read scope ([importedSourceIds]): every non-archived
     * paired device plus the legacy import sink, so a second strap's days are visible instead of
     * excluded by construction. One id resolves to the same single read as before. The active id wins
     * per day inside each bucket ([unionByDay]); imports still win over computed across buckets
     * ([mergeDaily]).
     */
    suspend fun daysMerged(): List<DailyMetric> {
        val imported = unionByDay(importedSourceIds().map { dao.days(it) })
        val computed = unionByDay(computedSourceIds().map { dao.days(it) })
        val gapFill = unionByDay(GAP_FILL_SOURCE_IDS.map { dao.days(it) })
        // Days the user hand-edited the sleep of (the edit lives under the computed source): on those
        // days the computed sleep fields win over a re-imported night. Pool the edited sessions across
        // every computed source in the scope so a re-add doesn't lose an earlier-id edit's precedence.
        val editedSessions = computedSourceIds().flatMap { dao.editedSleepSessions(it) }
        return mergeDaily(
            imported = imported,
            computed = computed,
            userEditedDays = userEditedDays(editedSessions),
            gapFill = gapFill,
        )
    }

    /**
     * Union ([unionByDay]) of one daily flow per source id, emitting whenever any source changes. For the
     * common single-source (single-WHOOP) case it is the plain source flow (no extra operator). Read-side
     * helper for [daysMergedFlow] / [recentDaysMergedFlow].
     */
    private fun unionDaysFlow(flows: List<Flow<List<DailyMetric>>>): Flow<List<DailyMetric>> =
        if (flows.size == 1) flows[0]
        else combine(flows) { arrays -> unionByDay(arrays.toList()) }

    /**
     * Reactive merged daily metrics (oldest first): imported rows win per day, computed "-noop" rows
     * gap-fill. Emits whenever any contributing source changes.
     *
     * The source ids come from the registry ([importedSourceIdsFlow]) and are re-resolved when it
     * changes, so pairing or archiving a strap re-scopes the dashboard with no app restart.
     *
     * Also keys off the computed sources' user-edited sessions so a hand-edited night's sleep figures
     * keep precedence over a re-imported night (and the chart re-emits when an edit lands).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun daysMergedFlow(): Flow<List<DailyMetric>> =
        importedSourceIdsFlow().flatMapLatest { ids ->
            combine(
                unionDaysFlow(ids.map { dao.daysFlow(it) }),
                unionDaysFlow(ids.map { dao.daysFlow(computedDeviceId(it)) }),
                unionDaysFlow(GAP_FILL_SOURCE_IDS.map { dao.daysFlow(it) }),
                editedSleepSessionsFlow(ids.map { computedDeviceId(it) }),
            ) { imported, computed, gapFill, edited ->
                mergeDaily(
                    imported = imported,
                    computed = computed,
                    userEditedDays = userEditedDays(edited),
                    gapFill = gapFill,
                )
            }
        }

    /**
     * Bounded reactive merged daily metrics for the dashboard. Same per-day merge as [daysMergedFlow]
     * (imported wins, computed gap-fills, edited days keep the correction), but each source is capped to
     * the most-recent [RECENT_DAYS_CAP] rows before the merge, so a years-deep import stops re-merging the
     * whole history on every DB change. The cap comfortably covers every current dashboard surface. Rows
     * come back oldest-first, identical ordering to [daysMergedFlow]. Edited-day precedence still reads
     * the userEdited sessions (not day-capped: the set is already tiny), so a hand-edited recent night
     * keeps winning.
     *
     * Like [daysMergedFlow] the buckets are the registry read scope: the cap stays PER SOURCE, so the
     * union stays bounded (one capped page per source id).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recentDaysMergedFlow(): Flow<List<DailyMetric>> =
        importedSourceIdsFlow().flatMapLatest { ids ->
            combine(
                unionDaysFlow(ids.map { dao.recentDaysFlow(it, RECENT_DAYS_CAP) }),
                unionDaysFlow(ids.map { dao.recentDaysFlow(computedDeviceId(it), RECENT_DAYS_CAP) }),
                unionDaysFlow(GAP_FILL_SOURCE_IDS.map { dao.recentDaysFlow(it, RECENT_DAYS_CAP) }),
                editedSleepSessionsFlow(ids.map { computedDeviceId(it) }),
            ) { imported, computed, gapFill, edited ->
                // recentDaysFlow returns newest-first (DESC LIMIT); mergeDaily re-sorts ascending by day,
                // so the emitted order matches daysMergedFlow exactly.
                mergeDaily(
                    imported = imported,
                    computed = computed,
                    userEditedDays = userEditedDays(edited),
                    gapFill = gapFill,
                )
            }
        }

    /** Pooled user-edited sleep sessions across every computed source in the read scope, so a re-add
     *  doesn't drop an earlier-id night's edit precedence. Single-source ⇒ the plain flow. */
    private fun editedSleepSessionsFlow(computedIds: List<String>): Flow<List<SleepSession>> {
        val flows = computedIds.map { dao.editedSleepSessionsFlow(it) }
        return if (flows.size == 1) flows[0]
        else combine(flows) { arrays -> arrays.flatMap { it } }
    }

    /**
     * Sleep sessions in [from, to] (unix seconds) merged with their computed "-noop" twins. Imported
     * sessions win per night-end day; computed sessions gap-fill. Sorted by startTs ascending.
     *
     * Both buckets span the registry read scope, each already collapsed to one block per night
     * ([dedupSleepBlocks]) so a night two straps both staged is not listed twice. [mergeSleep] keys each
     * bucket by night-end day, LAST entry winning, so the ids are concatenated legacy-FIRST and
     * active-LAST: the active (live) night wins a day several ids cover, and a legacy (imported) night
     * fills a day only it has. One id per bucket ⇒ byte-identical behaviour.
     */
    suspend fun sleepSessionsMerged(
        from: Long,
        to: Long,
        limit: Int = DEFAULT_LIMIT,
    ): List<SleepSession> = mergeSleep(
        imported = dedupSleepBlocks(
            importedSourceIds().reversed().map { dao.sleepSessions(it, from, to, limit) },
        ),
        computed = dedupSleepBlocks(
            computedSourceIds().reversed().map { dao.sleepSessions(it, from, to, limit) },
        ),
    )

    /** ALL imported sleep BLOCKS across the registry read scope, keeping every session per source (a nap
     *  + a main night both survive) and collapsing one night recorded under several ids to the active
     *  strap's copy ([dedupSleepBlocks]). The Sleep tab's chevron walk reads this so a night recorded
     *  under any paired strap's id still surfaces. */
    suspend fun sleepSessionsUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        List<SleepSession> =
        dedupSleepBlocks(importedSourceIds().map { dao.sleepSessions(it, from, to, limit) })

    /** The COMPUTED ("-noop") twin of [sleepSessionsUnion], across the read scope's computed ids. */
    suspend fun computedSleepSessionsUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT):
        List<SleepSession> =
        dedupSleepBlocks(computedSourceIds().map { dao.sleepSessions(it, from, to, limit) })

    /** Workouts over the registry read scope (twin of [hrSamplesUnion] / [sleepSessionsUnion]): each
     *  strap owns its own id while imports live under the legacy sink, so a read pinned to a SINGLE id
     *  strands the others' workouts. Exact-duplicate rows are dropped on the (startTs, sport) natural
     *  key, active-strap-first. */
    suspend fun workoutsUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT): List<WorkoutRow> =
        dedupWorkoutsByKey(importedSourceIds().flatMap { dao.workouts(it, from, to, limit) })

    /** The COMPUTED ("-noop") twin of [workoutsUnion] for detected workouts (the engine writes detected
     *  sessions under "<deviceId>-noop"), across the read scope's computed ids. */
    suspend fun detectedWorkoutsUnion(from: Long, to: Long, limit: Int = DEFAULT_LIMIT): List<WorkoutRow> =
        dedupWorkoutsByKey(computedSourceIds().flatMap { dao.workouts(it, from, to, limit) })

    /** Cached daily metrics for the inclusive day range [from, to] (YYYY-MM-DD), oldest first. */
    suspend fun dailyMetrics(deviceId: String, from: String, to: String): List<DailyMetric> =
        dao.dailyMetricsRange(deviceId, from, to)

    // MARK: - Cross-source resolver (freshest-wins charts/metrics)
    //
    // Feeds Compare/Insights/Stress/Explore/Today. [resolvedSeries] resolves a metric over an explicit
    // precedence — imported WHOOP wins, NOOP-computed fills the days it doesn't cover, and Apple Health
    // only fills declared-compatible vitals on days neither strap source has.

    /** One day's resolved value plus the source that supplied it (so a caption can name it). */
    data class ResolvedMetricPoint(
        val day: String,
        val value: Double,
        val source: String,
        val sourceKey: String,
    )

    /** A candidate (source, key) pair the resolver tries, in precedence order. */
    data class MetricSourceCandidate(val source: String, val key: String)

    /** One candidate's per-day resolver value. [weakSleepTotal] marks a sleep-total that came off a
     *  BARE daily aggregate ([bareSleepAggregate]): kept only until a later candidate offers a
     *  REAL scored value for the day — see [resolveFirstWins]. Class-nested so tests address it as
     *  `WhoopRepository.CandidateRow`, like [ResolvedMetricPoint]. */
    internal data class CandidateRow(
        val day: String,
        val value: Double,
        val weakSleepTotal: Boolean = false,
    )

    /** The full result of resolving one metric: the sources tried + the merged per-day points. */
    data class MetricSeriesResolution(
        val requestedSource: String,
        val candidates: List<MetricSourceCandidate>,
        val points: List<ResolvedMetricPoint>,
    ) {
        /** Plain (day, value) rows , the shape the chart/correlation code already consumes. */
        val values: List<Pair<String, Double>> get() = points.map { it.day to it.value }

        /** Distinct sources that actually contributed a point, in first-seen order (for a caption). */
        val usedSources: List<String>
            get() {
                val seen = LinkedHashSet<String>()
                for (p in points) seen.add(p.source)
                return seen.toList()
            }
    }

    /**
     * Product-facing daily series for [key] across every COMPATIBLE source, freshest-wins. Use this
     * on surfaces where the user expects the best available signal; use [metricSeries] where one source
     * must be honoured verbatim. Precedence per [sourceCandidates]: imported WHOOP > NOOP-computed >
     * declared-compatible Apple Health. [from]/[to] are YYYY-MM-DD bounds.
     *
     * The strap candidates come from the registry read scope ([importedSourceIds]), so every paired
     * strap's history resolves rather than only the active one's.
     */
    suspend fun resolvedSeries(
        key: String,
        preferredSource: String,
        from: String,
        to: String,
    ): MetricSeriesResolution {
        val candidates = sourceCandidates(key, preferredSource, importedSourceIds())
        // First candidate wins per day; later candidates only fill days no earlier one covered. Exception
        // inside [resolveFirstWins]: a day held only by a WEAK sleep-total (a bare phone aggregate, e.g. a
        // constant bedtime-schedule span) yields to a later candidate's REAL scored night, so a resolver
        // read agrees with the mergeDaily dashboards.
        val perCandidate = candidates.map { it to resolvedRows(it, from, to) }
        return MetricSeriesResolution(preferredSource, candidates, resolveFirstWins(perCandidate))
    }

    /**
     * Read one candidate's rows for the window: its metricSeries, plus the matching DailyMetric column
     * for any day the metricSeries doesn't carry (a Bluetooth-only WHOOP 5 user has values in the daily
     * columns but not the long-format series). Ascending by day.
     *
     * The DailyMetric read uses a +1-day upper buffer ([bufferDayAfter]). A night is keyed on its LOCAL
     * wake day, so the row backing the SELECTED day's Rest can sort on the day AFTER the caller's `to`
     * (a just-after-midnight wake, or a UTC+ user whose wake-day rolls a calendar day ahead). Without the
     * buffer that banked row was excluded and Today fell back to the latest historical Rest. The buffer
     * only widens the daily read; `byDay`'s metricSeries-first precedence is unchanged.
     */
    private suspend fun resolvedRows(
        candidate: MetricSourceCandidate,
        from: String,
        to: String,
    ): List<CandidateRow> {
        val byDay = LinkedHashMap<String, CandidateRow>()
        for (row in dao.metricSeries(candidate.source, candidate.key, from, to)) {
            byDay[row.day] = CandidateRow(row.day, row.value)
        }
        // A sleep-total read off a BARE daily aggregate (no efficiency, no stage minutes — a phone shape,
        // where a stage-less bedtime-SCHEDULE record makes the total a target rather than measured sleep)
        // is flagged WEAK so a later candidate's real scored night can supersede it in [resolveFirstWins].
        // Every other daily column stays strong.
        val sleepTotalKey = candidate.key == "sleep_total_min" || candidate.key == "asleep_min"
        for (row in dao.dailyMetricsRange(candidate.source, from, bufferDayAfter(to))) {
            if (!byDay.containsKey(row.day)) {
                dailyColumn(candidate.key, row)?.let {
                    byDay[row.day] = CandidateRow(row.day, it, sleepTotalKey && bareSleepAggregate(row))
                }
            }
        }
        return byDay.values.sortedBy { it.day }
    }

    /** The "yyyy-MM-dd" day one calendar day AFTER [day], or [day] verbatim when it isn't a parseable
     *  ISO date (e.g. the wide-open "9999-99-99" sentinel Today passes, already past every real day, so
     *  no buffer is needed). Used as the +1-day read buffer in [resolvedRows] so a wake-day-keyed night
     *  that sorts just past the requested upper bound still resolves the selected day. */
    private fun bufferDayAfter(day: String): String =
        runCatching { java.time.LocalDate.parse(day).plusDays(1).toString() }.getOrDefault(day)

    /**
     * A compact snapshot of how much history each source holds, for the Data Sources "Freshness
     * Pipeline" card. Counts only — no per-day rows. Covers a wide window (4000 days).
     */
    suspend fun freshness(): DataFreshness {
        val to = freshnessDayKey(1)
        val from = freshnessDayKey(-4000)
        val ids = importedSourceIds()
        val imported = unionByDay(ids.map { dao.dailyMetricsRange(it, from, to) })
        val computed = unionByDay(ids.map { dao.dailyMetricsRange(computedDeviceId(it), from, to) })
        val apple = dao.dailyMetricsRange(APPLE_HEALTH_SOURCE, from, to)
        val now = System.currentTimeMillis() / 1000L
        val lo = now - 4000L * 86_400L
        val hi = now + 86_400L
        val importedSleeps = dedupSleepBlocks(ids.map { dao.sleepSessions(it, lo, hi, DEFAULT_LIMIT) })
        val computedSleeps =
            dedupSleepBlocks(ids.map { dao.sleepSessions(computedDeviceId(it), lo, hi, DEFAULT_LIMIT) })
        val days = (imported + computed + apple).map { it.day }
        return DataFreshness(
            importedDays = imported.size,
            computedDays = computed.size,
            appleDays = apple.size,
            importedSleeps = importedSleeps.size,
            computedSleeps = computedSleeps.size,
            earliestDay = days.minOrNull(),
            latestDay = days.maxOrNull(),
        )
    }

    /** "yyyy-MM-dd" for today offset by [deltaDays], fixed UTC (freshness window bounds). */
    private fun freshnessDayKey(deltaDays: Int): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.add(java.util.Calendar.DAY_OF_YEAR, deltaDays)
        return String.format(
            java.util.Locale.US, "%04d-%02d-%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }

    // MARK: - Flows

    /** Reactive daily metrics (oldest first) for a device. */
    fun daysFlow(deviceId: String): Flow<List<DailyMetric>> = dao.daysFlow(deviceId)

    // MARK: - Frontier / convenience

    /** Persist HR samples directly (e.g. a live-tracked workout's 1 Hz series). Dedup-safe:
     *  `insertHr` IGNOREs on the (deviceId, ts) primary key, so re-inserts / a later offload sync
     *  covering the same seconds are no-ops. */
    suspend fun insertHr(rows: List<HrSample>) = dao.insertHr(rows)

    suspend fun latestHrSampleTs(deviceId: String): Long? = dao.latestHrSampleTs(deviceId)
    suspend fun latestHr(deviceId: String): HrSample? = dao.latestHr(deviceId)
    suspend fun latestBattery(deviceId: String): BatterySample? = dao.latestBattery(deviceId)

    companion object {
        /** Default row cap on range reads. */
        const val DEFAULT_LIMIT = 100_000

        /** Dashboard merge window cap (days). The bounded [recentDaysMergedFlow] keeps at most this
         *  many most-recent days per source, so a years-deep import stops re-merging the whole history on
         *  every DB change. ~2 years comfortably covers the deepest Trends range and the rolling 7-day
         *  Fitness Age / Vitality windows. */
        const val RECENT_DAYS_CAP = 800

        /** Canonical source ids the resolver cross-references. The strap's real id is passed in. */
        const val WHOOP_SOURCE = "my-whoop"
        const val APPLE_HEALTH_SOURCE = "apple-health"
        const val HEALTH_CONNECT_SOURCE = "health-connect"

        /**
         * The IMPORTED daily-source ids to read, derived from the registry [devices] (never from one id):
         * the `active` device first, then every other non-`archived` device in registration order, then
         * the legacy [WHOOP_SOURCE] last. Archiving a strap is how a user retires it, so an archived id
         * is dropped; [WHOOP_SOURCE] is the import sink and the pre-registry bucket rather than a device,
         * so it is always read. Active-first ordering makes every per-day pick take the active row.
         * Companion form so [com.noop.ui.FusionDayAdapter] and the instance reads share ONE definition.
         */
        fun importedSourceIdsFor(devices: List<PairedDeviceRow>): List<String> {
            val ids = LinkedHashSet<String>()
            devices.firstOrNull { it.status == DeviceStatus.active.name }?.let { ids.add(it.id) }
            for (d in devices) if (d.status != DeviceStatus.archived.name) ids.add(d.id)
            ids.add(WHOOP_SOURCE)
            return ids.toList()
        }

        /** The COMPUTED ("-noop") source ids mirroring [importedSourceIdsFor] (the engine writes computed
         *  scores under "<importedDeviceId>-noop"). */
        fun computedSourceIdsFor(devices: List<PairedDeviceRow>): List<String> =
            importedSourceIdsFor(devices).map { "$it-noop" }

        /**
         * The LOWEST-precedence daily sources: phone aggregates that GAP-FILL only. Health Connect
         * measures nothing itself, it re-publishes what a phone or another app recorded, so its row may
         * supply a column no strap source carries and may never replace one that does ([mergeDaily]'s
         * gapFill bucket). Agrees with [com.noop.analytics.MetricArbitrationPolicy]'s tier 2 for
         * HEALTH_CONNECT on vitals and sleep; the phone-counts-steps tier 0 does not apply here because
         * a "health-connect" daily row never carries `steps` (steps land on appleDaily).
         */
        val GAP_FILL_SOURCE_IDS: List<String> = listOf(HEALTH_CONNECT_SOURCE)

        /** Pick the winner among per-source LATEST rows ([computedSourceIdsFor] order, active-strap
         *  first): the strictly newest day wins; a shared newest day keeps the FIRST seen (the active
         *  strap) — byte-identical to what `mergeComputedSeriesUnion(...).lastOrNull()` yields on the
         *  full series, computed from one LIMIT-1 row per source instead of materializing the whole
         *  history (perf: the Today/Health latest-value tiles). Pure companion for [ResolverUnionTest]. */
        internal fun latestFromPerSourceLatest(perSource: List<MetricSeriesRow?>): MetricSeriesRow? {
            var best: MetricSeriesRow? = null
            for (row in perSource) {
                if (row == null) continue
                if (best == null || row.day > best.day) best = row   // strictly newer wins; ties keep first (active)
            }
            return best
        }

        /** Merge per-source computed ("-noop") metricSeries rows into one series, DEDUPED per day: the
         *  ACTIVE strap's value wins over the canonical import's on a shared day. [perSource] is in
         *  [computedSourceIdsFor] order (active-strap first), so keeping the FIRST row seen per day
         *  preserves the active value — the same active-first idiom as [dedupSleepBlocks]. Result is
         *  day-sorted ascending. */
        internal fun mergeComputedSeriesUnion(perSource: List<List<MetricSeriesRow>>): List<MetricSeriesRow> {
            val byDay = LinkedHashMap<String, MetricSeriesRow>()
            for (rows in perSource) {
                for (row in rows) byDay.putIfAbsent(row.day, row)   // active-first: first seen per day wins
            }
            return byDay.values.sortedBy { it.day }
        }

        /**
         * Collapse the same physical night recorded under SEVERAL union ids down to one block, keeping the
         * earlier list's copy ([importedSourceIdsFor] orders active-first, so the active strap survives).
         * [perSource] is one list per source id; a block is dropped only when an EARLIER source already
         * holds an overlapping copy ([com.noop.analytics.SleepSessionDedup.isDuplicate]) — two straps stage
         * one night at bounds minutes apart, which no exact key can match. Blocks from the SAME source
         * never collapse each other, so a nap beside a main night is preserved and a single source is
         * returned verbatim. Pure companion form so the JVM tests exercise it without Room.
         */
        internal fun dedupSleepBlocks(perSource: List<List<SleepSession>>): List<SleepSession> {
            if (perSource.size <= 1) return perSource.firstOrNull() ?: emptyList()
            val kept = ArrayList<SleepSession>()
            for (list in perSource) {
                val prior = kept.size    // same-source blocks are compared only against earlier sources
                for (s in list) {
                    val shadowed = (0 until prior).any {
                        val earlier = kept[it]
                        (earlier.startTs == s.startTs && earlier.effectiveEndTs == s.effectiveEndTs) ||
                            com.noop.analytics.SleepSessionDedup.isDuplicate(earlier, s)
                    }
                    if (!shadowed) kept.add(s)
                }
            }
            return kept
        }

        /** Drop exact-duplicate workouts sharing an identical (startTs, sport) natural key — the same
         *  session read under two union ids — keeping the FIRST seen (callers pass active-strap-first
         *  lists). Twin of [dedupSleepBlocks]. */
        internal fun dedupWorkoutsByKey(rows: List<WorkoutRow>): List<WorkoutRow> {
            val seen = HashSet<Pair<Long, String>>()
            return rows.filter { seen.add(it.startTs to it.sport) }
        }

        /** Build a repository backed by the process-wide singleton database. */
        fun from(context: Context): WhoopRepository = WhoopRepository(WhoopDatabase.get(context))

        // MARK: - Compact per-epoch JSON (v18 motionJSON / sleepStateJSON): a bare `[..]` array, whole
        // doubles emitted WITHOUT a trailing `.0` (`3` not `3.0`, `1.5` unchanged). Hand-built rather than
        // org.json so the string round-trips identically; decode tolerates either form.

        /** A single double in compact form: an integral value as a bare integer (`3`, `0`),
         *  otherwise its shortest decimal (`1.5`, `12.25`). */
        internal fun encodeDouble(x: Double): String =
            if (x.isFinite() && x == kotlin.math.floor(x) && !x.isInfinite()) x.toLong().toString() else x.toString()

        internal fun encodeDoubleArray(xs: List<Double>): String =
            xs.joinToString(separator = ",", prefix = "[", postfix = "]") { encodeDouble(it) }

        internal fun encodeIntArray(xs: List<Int>): String =
            xs.joinToString(separator = ",", prefix = "[", postfix = "]") { it.toString() }

        /** Parse a bare JSON number array to doubles, or null when unparseable (absent stays absent). */
        internal fun decodeDoubleArray(json: String): List<Double>? = runCatching {
            val arr = org.json.JSONArray(json)
            List(arr.length()) { arr.getDouble(it) }
        }.getOrNull()

        /** Parse a bare JSON number array to ints, or null when unparseable. */
        internal fun decodeIntArray(json: String): List<Int>? = runCatching {
            val arr = org.json.JSONArray(json)
            List(arr.length()) { arr.getInt(it) }
        }.getOrNull()

        /**
         * Candidate (source, key) pairs to try for [key], in precedence order, given the user's
         * [preferredSource]. [strapIds] is the registry read scope ([importedSourceIdsFor]), active-first:
         *  • strap-preferred → [every imported strap, every computed sibling, compatible Apple] (Apple
         *    only for vitals with a declared 1:1 mapping);
         *  • Apple-preferred → [Apple] (+ computed straps ONLY for steps/active_kcal, which the strap
         *    estimates and Apple may not carry);
         *  • any other source → itself only (nutrition/mood are single-source by design).
         */
        internal fun sourceCandidates(
            key: String,
            preferredSource: String,
            strapIds: List<String>,
        ): List<MetricSourceCandidate> {
            fun uniqued(cs: List<MetricSourceCandidate>): List<MetricSourceCandidate> {
                val seen = LinkedHashSet<MetricSourceCandidate>()
                for (c in cs) seen.add(c)
                return seen.toList()
            }
            if (preferredSource in strapIds) {
                // Active strap first (live/measured wins per day), then the other paired straps, then the
                // CANONICAL "my-whoop" import (strapIds is already in that order), THEN every computed
                // sibling — so imports outrank computed estimates (the documented `imported WHOOP >
                // NOOP-computed` order); putting a computed sibling ahead of the canonical import would
                // let one strap's computed estimates shadow richer imported history. uniqued() collapses
                // these to one pair per source on a single-device install, so that path stays
                // byte-identical. Apple is the final cross-source fallback.
                val candidates = mutableListOf<MetricSourceCandidate>()
                for (id in strapIds) candidates.add(MetricSourceCandidate(id, key))
                for (id in strapIds) candidates.add(MetricSourceCandidate("$id-noop", key))
                appleCompatibleKey(key)?.let {
                    candidates.add(MetricSourceCandidate(APPLE_HEALTH_SOURCE, it))
                }
                // Health Connect LAST, mirroring [GAP_FILL_SOURCE_IDS] in the dashboard merge so a
                // resolver read and a mergeDaily read agree on precedence. Its vitals sit on a
                // "health-connect" DailyMetric row, read through [dailyColumn] under the WHOOP key, so a
                // phone-only day still resolves while every strap and import source outranks it.
                candidates.add(MetricSourceCandidate(HEALTH_CONNECT_SOURCE, key))
                return uniqued(candidates)
            }
            if (preferredSource == APPLE_HEALTH_SOURCE) {
                val candidates = mutableListOf(MetricSourceCandidate(APPLE_HEALTH_SOURCE, key))
                // Health Connect is an Apple-equivalent body-metric source on Android — a real Apple
                // EXPORT still wins per day (it's first), HC fills the rest. This makes a Health-Connect-
                // only weight history visible in Compare; HC emits a "weight" metricSeries under this
                // source from HealthConnectImporter.
                candidates.add(MetricSourceCandidate(HEALTH_CONNECT_SOURCE, key))
                if (noopComputedCanFillAppleMetric(key)) {
                    for (id in strapIds) candidates.add(MetricSourceCandidate("$id-noop", key))
                }
                return uniqued(candidates)
            }
            return listOf(MetricSourceCandidate(preferredSource, key))
        }

        /** The Apple-Health series key carrying the SAME quantity as a WHOOP key; null = no fallback. */
        internal fun appleCompatibleKey(key: String): String? = when (key) {
            "rhr" -> "resting_hr"
            "hrv", "spo2", "resp_rate", "avg_hr", "max_hr", "in_bed_min", "active_kcal" -> key
            "sleep_total_min" -> "asleep_min"
            "sleep_deep_min" -> "deep_min"
            "sleep_rem_min" -> "rem_min"
            "sleep_light_min" -> "core_min"
            else -> null
        }

        /** Whether the NOOP-computed strap source may fill an Apple-preferred metric. Only the two
         *  daily totals the strap genuinely estimates (steps, calories) , never a derived score. */
        private fun noopComputedCanFillAppleMetric(key: String): Boolean = when (key) {
            "steps", "active_kcal" -> true
            else -> false
        }

        /**
         * The DailyMetric column backing a resolver key, for days the metricSeries doesn't cover
         * (strap-only WHOOP 5 users). Also handles the Apple-compatible sleep aliases (asleep_min /
         * deep_min / rem_min / core_min) the resolver may request. Keys with no daily column return null.
         *
         * `sleep_performance` (the Rest composite, 0–100) is NOT a stored column: IntelligenceEngine
         * persists it as a metricSeries point. But a Bluetooth-only WHOOP 5 user — and, crucially, the
         * SELECTED (just-synced) day before the heavy daily pass has projected the series — has the
         * night's totals banked on the DailyMetric row while the metricSeries point is still missing.
         * Derive it on the fly via the single source of truth [com.noop.analytics.RestScorer.restFromDaily]
         * (the same composite the series carries), so the day resolves to its own Rest. Consistency is
         * left to the scorer's neutral default here (the daily row carries no regularity term).
         */
        internal fun dailyColumn(key: String, d: DailyMetric): Double? = when (key) {
            "recovery" -> d.recovery
            "hrv" -> d.avgHrv
            "rhr", "resting_hr" -> d.restingHr?.toDouble()
            "strain" -> d.strain
            "resp_rate" -> d.respRateBpm
            "spo2" -> d.spo2Pct
            "skin_temp" -> d.skinTempDevC
            "sleep_total_min", "asleep_min" -> d.totalSleepMin
            "sleep_efficiency" -> d.efficiency
            "sleep_deep_min", "deep_min" -> d.deepMin
            "sleep_rem_min", "rem_min" -> d.remMin
            "sleep_light_min", "core_min" -> d.lightMin
            "sleep_performance" -> com.noop.analytics.RestScorer.restFromDaily(d)
            "steps" -> d.steps?.toDouble()
            "active_kcal", "energy_kcal" -> d.activeKcalEst
            else -> null
        }

        /**
         * Whether [d]'s sleep block is a BARE aggregate — a totalSleepMin with NO efficiency and no
         * stage minutes beside it. That is the shape a phone aggregate carries, and on a phone whose OS
         * banks a stage-less bedtime-SCHEDULE record the total is the schedule length, a target rather
         * than measured sleep. Session-grade rows (WHOOP CSV / Xiaomi imports, every strap-computed
         * night) always carry efficiency and/or stages, so they never match. Shared by [mergeDaily] and
         * the cross-source resolver so both read paths apply ONE definition of "not real scored sleep".
         */
        internal fun bareSleepAggregate(d: DailyMetric): Boolean =
            d.totalSleepMin != null && d.efficiency == null &&
                d.deepMin == null && d.remMin == null && d.lightMin == null

        /**
         * The resolver's per-day merge, pure for JVM tests: first candidate wins per day; later
         * candidates only fill days no earlier one covered — EXCEPT a day held only by a WEAK
         * sleep-total (a bare imported aggregate, e.g. a Health Connect schedule span) is REPLACED by a
         * later candidate's real scored value (the strap-computed night). A weak value with no stronger
         * sibling still shows — never fabricate, never blank.
         */
        internal fun resolveFirstWins(
            perCandidate: List<Pair<MetricSourceCandidate, List<CandidateRow>>>,
        ): List<ResolvedMetricPoint> {
            val byDay = LinkedHashMap<String, ResolvedMetricPoint>()
            val weakDays = HashSet<String>()
            for ((candidate, rows) in perCandidate) {
                for (row in rows) {
                    val taken = byDay.containsKey(row.day)
                    if (!taken || (row.day in weakDays && !row.weakSleepTotal)) {
                        byDay[row.day] = ResolvedMetricPoint(row.day, row.value, candidate.source, candidate.key)
                        if (row.weakSleepTotal) weakDays.add(row.day) else weakDays.remove(row.day)
                    }
                }
            }
            return byDay.values.sortedBy { it.day }
        }

        /**
         * Collapse the per-day rows of one logical bucket that is physically split across MORE THAN ONE
         * source id into a single row per day, EARLIER list wins the day. Used to fold the active strap
         * id's rows together with the canonical "my-whoop" rows BEFORE [mergeDaily]: [lists] arrives in
         * precedence order (active id first, canonical second), so a day the re-added strap has
         * LIVE/measured data for wins over the same day in the canonical import, while a day only the
         * canonical import covers is still surfaced. Pure + order-stable: de-dupe is keyed on `day`, and
         * the result is re-sorted oldest-first downstream by [mergeDaily]. A single-source caller passes
         * one list and gets it back unchanged.
         */
        internal fun unionByDay(lists: List<List<DailyMetric>>): List<DailyMetric> {
            if (lists.size == 1) return lists[0]
            val byDay = LinkedHashMap<String, DailyMetric>()
            // First list wins: only fill a day a later (lower-precedence) list covers and an earlier one didn't.
            for (list in lists) for (d in list) byDay.putIfAbsent(d.day, d)
            return byDay.values.toList()
        }

        /**
         * Merge HR sample lists (the active-id ∪ canonical "my-whoop" union) into one time-ordered
         * stream, deduped by ts with the FIRST list (the active strap) winning on a tie. A single-id read
         * (single-WHOOP install) returns that list untouched, so the union is byte-identical there.
         */
        /** The single R-R list to use for a window: the one with the most beats, ties keeping the
         *  earliest (callers pass active-strap-first lists). A pick, never a merge — see
         *  [rrIntervalsUnion]. */
        internal fun pickRichestRr(lists: List<List<RrInterval>>): List<RrInterval> {
            var best: List<RrInterval> = emptyList()
            for (list in lists) if (list.size > best.size) best = list
            return best
        }

        internal fun mergeHrByTs(lists: List<List<HrSample>>): List<HrSample> = mergeByTs(lists) { it.ts }

        /** Merge per-source 1 Hz sample lists into one time-ordered stream, deduped by ts with the FIRST
         *  list (the active strap) winning. A single-id read returns that list untouched, so the union is
         *  byte-identical there. Shared by every raw-stream union read ([hrSamplesUnion] and its twins). */
        internal fun <T> mergeByTs(lists: List<List<T>>, ts: (T) -> Long): List<T> {
            if (lists.size == 1) return lists[0]
            val byTs = LinkedHashMap<Long, T>()
            for (list in lists) for (s in list) byTs.putIfAbsent(ts(s), s)
            return byTs.values.sortedBy { ts(it) }
        }

        /**
         * Merge HR bucket lists (the active-id ∪ canonical union) into one time-ordered stream, deduped
         * by bucket start with the FIRST list (the active strap) winning on a tie. Single-id ⇒
         * byte-identical.
         */
        internal fun mergeHrBucketsByStart(lists: List<List<HrBucket>>): List<HrBucket> {
            if (lists.size == 1) return lists[0]
            val byStart = LinkedHashMap<Long, HrBucket>()
            for (list in lists) for (b in list) byStart.putIfAbsent(b.bucket, b)
            return byStart.values.sortedBy { it.bucket }
        }

        /**
         * Pure pick of the latest classed @63 activity across the union's per-id step-sample lists: the
         * non-null [com.noop.data.StepSample.activityClass] on the greatest-ts sample, resolving a ts tie
         * in favour of the FIRST list (the active strap, mirroring the union's active-wins rule). A single
         * non-empty list reduces to "last non-null class in that list"; an empty union returns null (no icon).
         */
        internal fun latestActivityClass(lists: List<List<StepSample>>): Int? {
            var bestTs = Long.MIN_VALUE
            var bestClass: Int? = null
            for (list in lists) for (s in list) {
                // Strict > keeps the FIRST list's sample on an exact ts tie: earlier lists are scanned first,
                // so a later list's equal-ts sample never overwrites the active strap's.
                if (s.activityClass != null && s.ts > bestTs) {
                    bestTs = s.ts
                    bestClass = s.activityClass
                }
            }
            return bestClass
        }

        /**
         * Imported daily rows win per day; computed rows fill the days the import doesn't cover. Returns
         * oldest→newest by day string (lexicographic = chronological for YYYY-MM-DD).
         *
         * A day in [userEditedDays] is one the user hand-edited the sleep of. For those days the COMPUTED
         * row's SLEEP fields (the edit) take precedence over the import, otherwise a re-imported
         * WHOOP/Apple night would silently mask the correction. Non-sleep fields still follow the
         * imports-win merge, and every non-edited day is unchanged.
         *
         * An imported row whose sleep block is a BARE aggregate (totalSleepMin with no efficiency and no
         * stage minutes) never overrides a night the strap actually scored: the computed row's WHOLE
         * sleep block wins for that day. Keeps a bedtime-SCHEDULE span out of every sleep surface while a
         * session-grade import (WHOOP CSV / Xiaomi) still wins exactly as before.
         *
         * [gapFill] is the lowest-precedence bucket ([GAP_FILL_SOURCE_IDS]): a phone aggregate supplies a
         * column no row above it carries and can never replace one that does, so a strap-measured HRV,
         * resting HR or respiration always outranks the phone's figure for the same day. A day nothing
         * above covers is taken whole, so a phone-only history stays on the dashboard.
         */
        internal fun mergeDaily(
            imported: List<DailyMetric>,
            computed: List<DailyMetric>,
            userEditedDays: Set<String> = emptySet(),
            gapFill: List<DailyMetric> = emptyList(),
        ): List<DailyMetric> {
            val byDay = LinkedHashMap<String, DailyMetric>()
            for (d in computed) byDay[d.day] = d // computed first…
            // …import overwrites, so a real WHOOP import always wins, BUT coalesce the strap-only
            // on-device metrics (steps / calories / RSA resp) from the computed row, since importers
            // (esp. Health Connect) write a "my-whoop" daily row with those columns null and would
            // otherwise blank them on days the import also covers.
            for (d in imported) {
                val c = byDay[d.day]
                // Per-FIELD coalesce: the imported row wins for every column it actually has, but any
                // column it leaves null is gap-filled from the computed row. A real WHOOP import has its
                // scores/stages set, so "d.x ?: c.x" is a no-op there. A Health Connect import, though,
                // writes a "my-whoop" row with recovery/strain/sleep-stages NULL — without this it would
                // blank a strap-computed day. Coalescing every nullable field prevents that and heals
                // days already shadowed.
                val merged = if (c == null) d else d.copy(
                    totalSleepMin = d.totalSleepMin ?: c.totalSleepMin,
                    efficiency = d.efficiency ?: c.efficiency,
                    deepMin = d.deepMin ?: c.deepMin,
                    remMin = d.remMin ?: c.remMin,
                    lightMin = d.lightMin ?: c.lightMin,
                    disturbances = d.disturbances ?: c.disturbances,
                    restingHr = d.restingHr ?: c.restingHr,
                    avgHrv = d.avgHrv ?: c.avgHrv,
                    recovery = d.recovery ?: c.recovery,
                    strain = d.strain ?: c.strain,
                    exerciseCount = d.exerciseCount ?: c.exerciseCount,
                    spo2Pct = d.spo2Pct ?: c.spo2Pct,
                    skinTempDevC = d.skinTempDevC ?: c.skinTempDevC,
                    skinTempAbsC = d.skinTempAbsC ?: c.skinTempAbsC,
                    respRateBpm = d.respRateBpm ?: c.respRateBpm,
                    steps = d.steps ?: c.steps,
                    activeKcalEst = d.activeKcalEst ?: c.activeKcalEst,
                    // Raw SpO2 is on-device only (imports never carry it), so the imported row's null
                    // is backfilled from the computed row — otherwise the nightly means would be lost.
                    spo2Red = d.spo2Red ?: c.spo2Red,
                    spo2Ir = d.spo2Ir ?: c.spo2Ir,
                )
                // A BARE imported sleep total must never override a night the strap actually scored.
                // HealthConnectImporter can backfill a "my-whoop" daily row carrying ONLY totalSleepMin
                // (efficiency / deep / rem / light all null); on a phone whose OS banks a bedtime-SCHEDULE
                // record (stage-less, so the importer falls back to the raw session span) that total is a
                // target, not measured sleep, and the imports-win coalesce above would keep it forever
                // (an internally inconsistent row: import's total beside the computed row's stage
                // minutes). Rule: sleep DURATION figures must come from actually scored sleep — when the
                // import's sleep block is a bare aggregate (no efficiency and no stage minutes beside the
                // total) and the computed row scored a real night, the WHOLE sleep block comes from the
                // computed row. A session-grade import (WHOOP CSV / Xiaomi, which always carries efficiency
                // and stages) still wins unchanged, and an HC-only user (no computed night) keeps their
                // bare total. This is the read-side rollup, so days already shadowed come back right.
                // [bareSleepAggregate] is the ONE shared definition (the resolver applies it too).
                val bareImportedSleepTotal = bareSleepAggregate(d)
                // On an edited day, the computed (edit-derived) SLEEP fields win over the import. A bare
                // import is only demoted when the computed row is a REAL scored night (non-bare); a
                // bare-vs-bare day keeps imports-win (a real Apple total must still correct a stage-less
                // computed in-bed total).
                byDay[d.day] = if (c != null &&
                    (d.day in userEditedDays ||
                        (bareImportedSleepTotal && c.totalSleepMin != null && !bareSleepAggregate(c)))
                ) {
                    merged.copy(
                        totalSleepMin = c.totalSleepMin,
                        efficiency = c.efficiency,
                        deepMin = c.deepMin,
                        remMin = c.remMin,
                        lightMin = c.lightMin,
                        disturbances = c.disturbances,
                    )
                } else {
                    merged
                }
            }
            // Lowest precedence. A day nothing above covers is taken whole; otherwise the phone row only
            // supplies columns still absent. Named rather than looped over every nullable field because a
            // "health-connect" daily row carries exactly these six by construction — the same set
            // HealthConnectImporter writes and [WhoopDao.HC_DAILY_FOREIGN_COLUMNS_NULL] proves absent
            // elsewhere.
            for (g in gapFill) {
                val above = byDay[g.day]
                if (above == null) {
                    byDay[g.day] = g
                    continue
                }
                // A phone sleep total is a bare aggregate ([bareSleepAggregate]); it must never sit beside
                // measured stages, so it fills only a row carrying no sleep signal of its own.
                val noSleepAbove = above.totalSleepMin == null && above.efficiency == null &&
                    above.deepMin == null && above.remMin == null && above.lightMin == null
                byDay[g.day] = above.copy(
                    totalSleepMin = if (noSleepAbove) g.totalSleepMin else above.totalSleepMin,
                    restingHr = above.restingHr ?: g.restingHr,
                    avgHrv = above.avgHrv ?: g.avgHrv,
                    spo2Pct = above.spo2Pct ?: g.spo2Pct,
                    respRateBpm = above.respRateBpm ?: g.respRateBpm,
                    exerciseCount = above.exerciseCount ?: g.exerciseCount,
                )
            }
            return byDay.values.sortedBy { it.day }
        }

        /**
         * The set of LOCAL wake-days that carry a user-edited sleep session, keyed exactly as
         * `DailyMetric.day` (the engine's offset-local-day keyer, matching [mergeSleep]'s endDay). Drives
         * the edit-merge precedence in [mergeDaily].
         */
        internal fun userEditedDays(sessions: List<SleepSession>): Set<String> {
            val days = HashSet<String>()
            for (s in sessions) {
                if (!s.userEdited) continue
                val offsetSec = (java.util.TimeZone.getDefault().getOffset(s.effectiveEndTs * 1000) / 1000).toLong()
                days.add(com.noop.analytics.AnalyticsEngine.dayString(s.effectiveEndTs, offsetSec))
            }
            return days
        }

        /**
         * Sleep-session precedence, keyed by the LOCAL day the night ends on. A UTC key put a night
         * that ends after local-but-before-UTC midnight (a UTC+ user waking early) under yesterday's UTC
         * date, so the dashboard's local "today" read missed it and surfaced the previous night. The
         * local key matches how IntelligenceEngine buckets nights and how the resolver looks up "today".
         * REUSES the existing `AnalyticsEngine.dayString(ts, offsetSec)` overload — do NOT add a new
         * offset overload, it clashes on the JVM signature and breaks the build.
         */
        internal fun mergeSleep(
            imported: List<SleepSession>,
            computed: List<SleepSession>,
        ): List<SleepSession> {
            fun endDay(s: SleepSession): String {
                val offsetSec = (java.util.TimeZone.getDefault().getOffset(s.effectiveEndTs * 1000) / 1000).toLong()
                return com.noop.analytics.AnalyticsEngine.dayString(s.effectiveEndTs, offsetSec)
            }
            return mergeSleepRichness(imported, computed, ::endDay).sortedBy { it.startTs }
        }

        /** Imported-wins-per-day sleep merge WITH a richness exception, returned UNSORTED so callers can
         *  apply their own sort/keyer. [mergeSleep] is this keyed by local wake-day + sorted by startTs;
         *  the Sleep screen keys the same way but sorts by effectiveStartTs, so it calls this directly to
         *  get the SAME richness rule the browse/CSV path uses.
         *
         *  Preserves EVERY session (a day with a main night + a nap must keep both). Richness exception: a
         *  sparse import (no stage data on ANY of its sessions that day) must NOT clobber a computed day
         *  that HAS stage data — otherwise a stage-less WHOOP/Apple/HC re-import blanks the stage
         *  breakdown for a night the strap fully staged. Days where the import carries stages, or where
         *  neither side does, keep the imported-wins rule. */
        internal fun mergeSleepRichness(
            imported: List<SleepSession>,
            computed: List<SleepSession>,
            endDay: (SleepSession) -> String,
        ): List<SleepSession> {
            val importedByDay = imported.groupBy(endDay)
            val computedByDay = computed.groupBy(endDay)
            val out = ArrayList<SleepSession>(imported.size + computed.size)
            for ((day, imp) in importedByDay) {
                val comp = computedByDay[day]
                if (comp != null && imp.none { hasStages(it) } && comp.any { hasStages(it) }) {
                    out.addAll(comp)   // richer computed day survives a stage-less import
                } else {
                    out.addAll(imp)    // imported wins its day (unchanged rule)
                }
            }
            for ((day, comp) in computedByDay) if (day !in importedByDay) out.addAll(comp)
            return out
        }

        /** True when the session carries a non-empty stage payload; null, "", and "[]" carry none. */
        private fun hasStages(s: SleepSession): Boolean {
            val json = s.stagesJSON?.trim() ?: return false
            return json.isNotEmpty() && json != "[]"
        }
    }
}

/** OnConflictStrategy.IGNORE returns the new rowid, or -1 when the row was skipped. */
private fun List<Long>.countInserted(): Int = count { it != -1L }

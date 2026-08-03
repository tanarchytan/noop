package com.noop.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/*
 * Room entities for the on-device schema. Each table's PRIMARY KEY is the natural key that drives
 * insert dedupe (see the PK noted on each entity below; rrInterval also carries `seq` to tiebreak
 * two EQUAL same-second beats).
 *
 * `ts` columns are wall-clock unix SECONDS.
 */

/** Device row. Natural key = id. */
@Entity(tableName = "device")
data class DeviceRow(
    @androidx.room.PrimaryKey
    val id: String,
    val mac: String? = null,
    val name: String? = null,
    val firstSeen: Long? = null,
    val lastSeen: Long? = null,
)

/** Heart-rate sample. PK (deviceId, ts). */
@Entity(tableName = "hrSample", primaryKeys = ["deviceId", "ts"])
data class HrSample(
    val deviceId: String,
    val ts: Long,
    val bpm: Int,
    // Per-row upload flag; unused locally, kept for schema parity. Defaults to 0.
    val synced: Int = 0,
)

/**
 * HR derived from the WHOOP 5/MG v26 optical PPG waveform: the v26 record stores no per-second
 * bpm, so it is reconstructed on-device by autocorrelation. Kept in its own table (NOT merged into
 * `hrSample`) so a real sensor HR is never confused with a derived estimate; [conf] (0…1) records
 * the autocorrelation strength. PK (deviceId, ts) = one estimate per window-centre second;
 * [hrBuckets][WhoopDao.hrBuckets] COALESCE-unions it with `hrSample` so PPG HR only fills seconds
 * the strap never reported.
 */
@Entity(tableName = "ppgHrSample", primaryKeys = ["deviceId", "ts"])
data class PpgHrSample(
    val deviceId: String,
    val ts: Long,
    val bpm: Int,
    val conf: Double,
    val synced: Int = 0,
)

/** One downsampled HR point: bucket start (unix seconds) + mean bpm over it. Query result of
 *  [WhoopDao.hrBuckets], not a table. */
data class HrBucket(
    val bucket: Long,
    val avgBpm: Double,
)

/** Aggregate HR over a time window: sample count + avg/max bpm. Query result of
 *  [WhoopDao.hrWindowStats], not a table. Used to derive a workout's HR from strap samples when
 *  the imported session carries none. avg/max are null when n == 0. */
data class HrWindowStats(
    val n: Long,
    val avg: Double?,
    val max: Int?,
)

/**
 * R-R interval. `seq` tiebreaks two EQUAL R-R intervals in the same 1-second `ts` bucket: keying by
 * (deviceId, ts, rrMs) alone dropped the second equal beat and biased HRV high. Equal (ts, rrMs) beats
 * count 0, 1, …; distinct beats keep seq 0, so no distinct beat is ever dropped.
 */
@Entity(tableName = "rrInterval", primaryKeys = ["deviceId", "ts", "rrMs", "seq"])
data class RrInterval(
    val deviceId: String,
    val ts: Long,
    val rrMs: Int,
    val seq: Int = 0,
    val synced: Int = 0,
    /** Position among the beats sharing this [ts], stamped at decode from the wire order. Leads the
     *  read sort so RMSSD sees beats in the order the strap emitted them. NULL on rows written before
     *  the column existed: the order was never recorded, so it cannot be recovered. */
    val ord: Int? = null,
)

/**
 * Strap event. PK (deviceId, ts, kind). `payloadJSON` is the deterministic (sorted-keys) JSON of
 * the remaining parsed fields, with `event`/`event_timestamp` removed.
 */
@Entity(tableName = "event", primaryKeys = ["deviceId", "ts", "kind"])
data class EventRow(
    val deviceId: String,
    val ts: Long,
    val kind: String,
    val payloadJSON: String,
    val synced: Int = 0,
)

/**
 * Battery sample. PK (deviceId, ts). `soc` is state-of-charge percent (nullable), `mv` is
 * millivolts (nullable), `charging` is only set by BATTERY_LEVEL events (nullable otherwise).
 */
@Entity(tableName = "battery", primaryKeys = ["deviceId", "ts"])
data class BatterySample(
    val deviceId: String,
    val ts: Long,
    val soc: Double? = null,
    val mv: Int? = null,
    val charging: Boolean? = null,
    val synced: Int = 0,
)

/** SpO2 raw-ADC sample (type-47). PK (deviceId, ts). */
@Entity(tableName = "spo2Sample", primaryKeys = ["deviceId", "ts"])
data class Spo2Sample(
    val deviceId: String,
    val ts: Long,
    val red: Int,
    val ir: Int,
    val synced: Int = 0,
)

/**
 * WHOOP 5.0/MG sleep blood-oxygen percent. whoop-rs decodes it from the v18 sleep record (@frame-82,
 * tri-mode: only physiological readings surface, sentinels/diagnostics drop to null at decode), so a
 * stored row is always a real reading. A WELLNESS estimate, never medical. Distinct from [Spo2Sample]
 * (the WHOOP 4.0 raw red/IR ADC channels); a WHOOP 4.0 inserts nothing here. PK (deviceId, ts).
 */
@Entity(tableName = "spo2PctSample", primaryKeys = ["deviceId", "ts"])
data class Spo2PctSample(
    val deviceId: String,
    val ts: Long,
    val pct: Int,
)

/** Skin-temperature raw-ADC sample (type-47). PK (deviceId, ts). */
@Entity(tableName = "skinTempSample", primaryKeys = ["deviceId", "ts"])
data class SkinTempSample(
    val deviceId: String,
    val ts: Long,
    val raw: Int,
    // The record's two auxiliary thermal registers, in DECI-degrees C where [raw] is centi-degrees.
    // Nullable with no SQL DEFAULT: rows written before the columns existed read back null, and
    // WHOOP 4.0 never carries them.
    val auxRaw1: Int? = null,
    val auxRaw2: Int? = null,
    val synced: Int = 0,
)

/**
 * The 5/MG v18 per-second channels with no biometric stream of their own: the strap's dense record
 * counter (one step per second, independent of its clock) and the optical front-end telemetry.
 * Instrumentation — nothing scores or displays it. PK (deviceId, ts).
 */
@Entity(tableName = "v18Sample", primaryKeys = ["deviceId", "ts"])
data class V18Sample(
    val deviceId: String,
    val ts: Long,
    val recordIndex: Long? = null,
    /** The whole packed byte whose bits 4-5 [SleepStateSampleEntity.state] carries. */
    val sleepStateRaw: Int? = null,
    val opticalBaselineA: Int? = null,
    val opticalBaselineB: Int? = null,
    // Withheld by the decoder when the band flags the second's beat detection, which [opticalSignalPoor]
    // reports instead — so a null amplitude beside a true flag is a sentinel, not a missing read.
    val opticalAmpA: Int? = null,
    val opticalAmpB: Int? = null,
    val opticalSignalPoor: Boolean? = null,
    // Carried every second with no established meaning, named for their offset in the record so the
    // column claims nothing. Stored because the strap discards its history once an offload is acked.
    val rawU8At28: Int? = null,
    val rawU8At29: Int? = null,
    val rawU16At30: Int? = null,
    val rawF32At105: Double? = null,
    val rawU16At26: Int? = null,
    // The remaining per-second bytes that carry information, packed by the decoder. Stored whole and
    // never read here: unpacking is the decoder's job, so the layout has one owner.
    val unpinned: ByteArray? = null,
)

/**
 * Step / motion counter sample (WHOOP5 type-47 step_motion_counter@57). PK (deviceId, ts).
 * `counter` is the device's CUMULATIVE u16 step counter (0..65535, wraps), NOT a per-sample delta:
 * the daily step total is derived in AnalyticsEngine by summing positive consecutive deltas with
 * u16 wraparound handling. Same IGNORE-dedupe by natural key as SkinTempSample. APPROXIMATE: an
 * on-device estimate, unverified against the official WHOOP app.
 */
@Entity(tableName = "stepSample", primaryKeys = ["deviceId", "ts"])
data class StepSample(
    val deviceId: String,
    val ts: Long,
    val counter: Int,
    // Activity-class enum decoded from @63: 0=still, 1=walk, 2=run; null when the byte was
    // 0xFF/invalid or absent. Nullable INTEGER with no SQL DEFAULT (a Kotlin construction default
    // never reaches the schema), so old rows read back null: an absent class stays absent, never
    // a fabricated 0/"still".
    val activityClass: Int? = null,
    val synced: Int = 0,
)

/**
 * The strap's OWN per-record band sleep_state. The decoder reads the v18 @81 high nibble
 * (`(sb ushr 4) and 3`) as 0 wake / 1 still / 2 asleep / 3 up. The byte + offset are read directly
 * off captured frames like every other v18 field; ONLY the non-zero code meanings are inferred
 * (every real capture we hold reads 0, a worn daytime wake), so this is carried VERBATIM and
 * surfaced/persisted as the strap's reported state, NOT trusted to override the derived hypnogram.
 * PK (deviceId, ts).
 */
@Entity(tableName = "sleepStateSample", primaryKeys = ["deviceId", "ts"])
data class SleepStateSampleEntity(
    val deviceId: String,
    val ts: Long,
    val state: Int,   // 0 wake / 1 still / 2 asleep / 3 up (band's own high-nibble code)
)

/** Respiration raw-ADC sample (type-47). PK (deviceId, ts). */
@Entity(tableName = "respSample", primaryKeys = ["deviceId", "ts"])
data class RespSample(
    val deviceId: String,
    val ts: Long,
    val raw: Int,
    val synced: Int = 0,
)

/** Gravity vector sample (type-47, unit "g"). PK (deviceId, ts). */
@Entity(tableName = "gravitySample", primaryKeys = ["deviceId", "ts"])
data class GravitySample(
    val deviceId: String,
    val ts: Long,
    val x: Double,
    val y: Double,
    val z: Double,
    val synced: Int = 0,
    // 5.0/MG v18 on-chip gravity-removed motion magnitude (g), same (deviceId, ts) as the gravity vector.
    // The circadian / CosinorAge activity signal. Nullable: absent on 4.0 and on older banked rows.
    val dynAccelG: Double? = null,
)

/**
 * Cached server-computed daily metrics. Natural key (deviceId, day), day = "YYYY-MM-DD". All
 * metric columns nullable; com.noop.analytics.IllnessWatch reads restingHr / avgHrv / recovery /
 * strain / skinTempDevC / respRateBpm / totalSleepMin from this row.
 */
@Entity(tableName = "dailyMetric", primaryKeys = ["deviceId", "day"])
data class DailyMetric(
    val deviceId: String,
    val day: String,
    val totalSleepMin: Double? = null,
    val efficiency: Double? = null,
    val deepMin: Double? = null,
    val remMin: Double? = null,
    val lightMin: Double? = null,
    val disturbances: Int? = null,
    val restingHr: Int? = null,
    val avgHrv: Double? = null,
    val recovery: Double? = null,
    val strain: Double? = null,
    val exerciseCount: Int? = null,
    // In-sleep signal aggregates (nullable; computed server-side).
    val spo2Pct: Double? = null,        // mean SpO2 (%) during sleep
    val skinTempDevC: Double? = null,   // skin-temperature deviation (°C) from baseline
    val skinTempAbsC: Double? = null,   // absolute skin temperature (°C) during sleep
    val respRateBpm: Double? = null,    // mean respiration rate (breaths/min) during sleep
    // On-device derived daily step total from the WHOOP5 step_motion_counter@57: sum of positive
    // consecutive u16-counter deltas over the day. APPROXIMATE, not cloud/clinical parity.
    val steps: Int? = null,
    // On-device APPROXIMATE whole-day active+resting energy estimate (kcal), computed from HR
    // alone by AnalyticsEngine (Keytel active + Harris–Benedict BMR). Null when the day has no
    // scored HR window. NOT cloud/clinical parity, a heart-rate estimate.
    val activeKcalEst: Double? = null,
    // Minutes the day's heart rate spent in the age-derived %HRmax bands, binned from the strap's own
    // samples rather than a workout's imported percentages, so a day without a logged session still
    // carries a real split. Null when the day has no HR.
    val zone1to3Min: Double? = null,
    val zone4to5Min: Double? = null,
    // WHOOP 4.0 raw SpO2 PPG ADC means over detected sleep. The raw red/IR channels banked on the
    // v24 historical layout (spo2_red@68 / spo2_ir@70), NOT a calibrated blood-oxygen %: that
    // needs WHOOP's proprietary curve. Nullable and on-device only, so old rows + non-4.0 nights
    // stay null.
    val spo2Red: Int? = null,           // mean raw red PPG ADC during detected sleep
    val spo2Ir: Int? = null,            // mean raw IR PPG ADC during detected sleep
    // Persisted Rest inputs so the store-site sleep_performance and every restFromDaily recompute agree:
    // the personal sleep need (hours) and sleep-consistency [0,1] that fed this day's score.
    val sleepNeedHours: Double? = null,
    val sleepConsistency: Double? = null,
    // Persisted recovery inputs so the store-site Charge and every recompute (pass 2, drivers, trace)
    // agree: the overnight resting-HR decline slope (bpm/hr) and the previous day's Effort/strain that
    // fed this day's Charge. Null drops the matching term.
    val recoveryIndexSlope: Double? = null,
    val priorDayEffort: Double? = null,
)

/**
 * Cached server-computed sleep session. Natural key (deviceId, startTs). `stagesJSON` is the
 * verbatim stage-segments JSON array. Durable bed/wake editing:
 *   - [userEdited]: true when the user hand-corrects bed/wake time; the post-sync recompute then
 *     preserves those bounds instead of re-upserting the strap-detected session over them (the
 *     overlap guard in IntelligenceEngine). INTEGER NOT NULL DEFAULT 0, so old rows read un-edited.
 *   - [startTsAdjusted]: the hand-set onset time. [startTs] stays the IMMUTABLE detected primary
 *     key, so upsert REPLACEs the row in place instead of duplicating at a moved key. Nullable;
 *     null means unedited (use startTs). Display/sort/re-staging use [effectiveStartTs].
 *   - [endTsAdjusted]: the hand-set wake time, the same shape one bound over. [endTs] stays the
 *     DETECTED wake, which the post-sync heal refreshes while this is null, so a bed-only edit's
 *     end keeps improving as raw arrives. Display/sort/re-staging use [effectiveEndTs].
 */
@Entity(tableName = "sleepSession", primaryKeys = ["deviceId", "startTs"])
data class SleepSession(
    val deviceId: String,
    val startTs: Long,
    val endTs: Long,
    val efficiency: Double? = null,
    val restingHr: Int? = null,
    val avgHrv: Double? = null,
    val stagesJSON: String? = null,
    // Defaulted so every existing constructor call-site compiles unchanged, and old rows read
    // userEdited=false / startTsAdjusted=null.
    val userEdited: Boolean = false,
    val startTsAdjusted: Long? = null,
    // Per-epoch analytics the stager/interpreter compute then discard, banked beside [stagesJSON]:
    //   - [motionJSON]: compact JSON array of per-epoch motion magnitudes (restlessness signal),
    //     one entry per stage epoch on the same 30 s grid as stagesJSON.
    //   - [sleepStateJSON]: compact JSON array of the decoded v18 band sleep_state per epoch,
    //     `(sb shr 4) and 3`.
    // Both nullable TEXT with no SQL DEFAULT (a Kotlin construction default never reaches the
    // schema), so old rows read back null: an absent signal stays null, never a fabricated zero
    // series. Written/read through the targeted DAO methods, not @Upsert, which never names them
    // and so preserves them.
    val motionJSON: String? = null,
    val sleepStateJSON: String? = null,
    // Last, matching the ALTER TABLE that adds it: the hand-set wake, null until the user sets one.
    // Nullable INTEGER with no SQL DEFAULT, so old rows read back null and their end re-detects.
    val endTsAdjusted: Long? = null,
) {
    /** The bed (onset) time to DISPLAY / sort / re-stage by: the user's hand-set onset when edited,
     *  else the immutable detected [startTs]. */
    val effectiveStartTs: Long get() = startTsAdjusted ?: startTs

    /** The wake time to DISPLAY / sort / re-stage by: the user's hand-set wake when they set one,
     *  else the detected [endTs], which the heal may still refresh. */
    val effectiveEndTs: Long get() = endTsAdjusted ?: endTs

    /** Whole-block duration in hours (effective onset → wake). */
    val durationHours: Double get() = (effectiveEndTs - effectiveStartTs) / 3600.0
}

/**
 * Generic long-format metric store. Natural key (deviceId, day, key); `value` is always a REAL.
 * The secondary index (deviceId, key, day) is `idx_metricSeries_device_key_day`, for index-only
 * range reads.
 */
@Entity(
    tableName = "metricSeries",
    primaryKeys = ["deviceId", "day", "key"],
    indices = [Index(name = "idx_metricSeries_device_key_day", value = ["deviceId", "key", "day"])],
)
data class MetricSeriesRow(
    val deviceId: String,
    val day: String,
    @ColumnInfo(name = "key") val key: String,
    val value: Double,
)

/**
 * Lab Book marker reading (Health Records pillar). The richer source-of-truth behind the daily
 * `metricSeries` projection: one row per dated reading the user entered themselves, a day can
 * hold several readings, each carries a precise `takenAt` instant and `unit`, and notes /
 * qualitative (`valueText`) results don't fit a REAL-only `metricSeries` cell.
 *
 * `id` is the client-generated stable primary key (edit/delete by id, backup round-trips); the
 * natural key (deviceId, markerKey, takenAt, source) is a UNIQUE index so a re-import of the same
 * reading is idempotent (`OnConflictStrategy.REPLACE` on that index). `value` is nullable (a
 * qualitative entry stores only `valueText`); `day` is the pre-derived yyyy-MM-dd projection key.
 *
 * NON-CLINICAL: holds only user-entered values plus an optional `referenceText` (their own
 * report's range, verbatim). No reference-range tables, no normality judgement.
 */
@Entity(
    tableName = "labMarker",
    indices = [
        Index(name = "idx_labMarker_natural", value = ["deviceId", "markerKey", "takenAt", "source"], unique = true),
        Index(name = "idx_labMarker_device_marker_takenAt", value = ["deviceId", "markerKey", "takenAt"]),
        Index(name = "idx_labMarker_device_category", value = ["deviceId", "category"]),
    ],
)
data class LabMarkerRow(
    @androidx.room.PrimaryKey
    val id: String,
    val deviceId: String,
    val markerKey: String,
    val category: String,
    val day: String,          // yyyy-MM-dd (projection key)
    val takenAt: Long,        // epoch seconds (precise instant)
    val value: Double? = null, // nullable: qualitative entries store only valueText
    val valueText: String? = null,
    val unit: String,
    val source: String,
    val note: String? = null,
    val referenceText: String? = null, // user-entered range, shown verbatim; NOOP ships none
)

/**
 * Cached journal answer (logged behaviour). Natural key (deviceId, day, question), day =
 * "YYYY-MM-DD". `answeredYes` is stored as an INTEGER 0/1 in SQLite, exposed as Boolean here
 * (Room maps Boolean -> INTEGER).
 */
@Entity(tableName = "journal", primaryKeys = ["deviceId", "day", "question"])
data class JournalEntry(
    val deviceId: String,
    val day: String,
    val question: String,
    val answeredYes: Boolean,
    val notes: String? = null,
    /**
     * Optional numeric reading for a numeric journal item (e.g. caffeine mg, alcohol units). Null
     * for a plain yes/no answer and for every imported WHOOP row. A numeric log writes
     * answeredYes=true AND numericValue=v, so the EffectRanker with/without split is unchanged.
     * Room maps `Double?` -> nullable REAL.
     */
    val numericValue: Double? = null,
)

/**
 * Cached workout (Whoop + Apple Health). Natural key (deviceId, startTs, sport). All metric
 * columns nullable. `source` distinguishes origin ("my-whoop" / "apple-health"); `zonesJSON` is
 * verbatim HR-zone-percentages JSON. `startTs`/`endTs` are wall-clock unix SECONDS.
 */
@Entity(tableName = "workout", primaryKeys = ["deviceId", "startTs", "sport"])
data class WorkoutRow(
    val deviceId: String,
    val startTs: Long,
    val endTs: Long,
    val sport: String,
    val source: String,
    val durationS: Double? = null,
    val energyKcal: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val strain: Double? = null,
    val distanceM: Double? = null,
    val zonesJSON: String? = null,
    val notes: String? = null,
    val routePolyline: String? = null, // Encoded GPS route (RouteMath polyline); null = no GPS.
)

/**
 * Durable "this detected bout is not a workout" marker. The IntelligenceEngine wipes and
 * re-derives sport="detected" rows under "<deviceId>-noop" every run, so a plain delete only
 * hides a bout until the next re-detect recreates it. This table is independent of that churn: a
 * detected row is filtered out at read time whenever it OVERLAPS a marker's [startTs, endTs]
 * span, so dismissal is permanent, and span-overlap (not an exact-key match) survives the small
 * startTs drift a bout's boundary can take as more HR arrives.
 *
 * PK (deviceId, startTs), one marker per detected start; `endTs` is the span end.
 */
@Entity(tableName = "dismissedWorkout", primaryKeys = ["deviceId", "startTs"])
data class DismissedWorkout(
    val deviceId: String,
    val startTs: Long,
    val endTs: Long,
)

/**
 * Durable tombstone for a user-DELETED sleep session: keeps a deleted computed night from being
 * re-derived by the recompute, mirroring [DismissedWorkout]. PK (deviceId, startTs), keyed on the
 * deleted session's start; `endTs` is the span the recompute's overlap test uses (a re-detected
 * onset can drift second-to-second). Undo lifts a tombstone by (deviceId, startTs).
 */
@Entity(tableName = "dismissedSleep", primaryKeys = ["deviceId", "startTs"])
data class DismissedSleep(
    val deviceId: String,
    val startTs: Long,
    val endTs: Long,
)

/**
 * Cached Apple-Health daily aggregate. Natural key (deviceId, day), day = "YYYY-MM-DD". All
 * metric columns nullable.
 */
@Entity(tableName = "appleDaily", primaryKeys = ["deviceId", "day"])
data class AppleDaily(
    val deviceId: String,
    val day: String,
    val steps: Int? = null,
    val activeKcal: Double? = null,
    val basalKcal: Double? = null,
    val vo2max: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val walkingHr: Int? = null,
    val weightKg: Double? = null,
)

/**
 * The RAW WHOOP 5.0 v26 optical PPG waveform, one record per second. The strap's 24 Hz buffer
 * was fully decoded but only ever used to derive [PpgHrSample]; the samples themselves were
 * discarded right after. Persisted here so a future re-analysis (a better HR estimator,
 * HRV-from-PPG, a waveform viewer) can run over the original samples, not just the derived bpm.
 *
 * The 24 raw i16 ADC samples are packed into a compact BLOB (2 bytes/sample, little-endian, see
 * [StreamPersistence.packPpgSamples]/[StreamPersistence.unpackPpgSamples]) instead of 24 scalar
 * rows, keeping a v26-heavy night to roughly the same order of magnitude as one extra per-second
 * stream, and so a `.noopbak` round-trips byte-identically. PK (deviceId, ts) mirrors every other
 * per-second stream; a truncated frame can decode fewer than 24 samples. Fields are declared in
 * this order (deviceId, ts, samples) so the migration's CREATE TABLE column order matches Room's
 * generated shape.
 */
@Entity(tableName = "ppgWaveformSample", primaryKeys = ["deviceId", "ts"])
data class PpgWaveformSampleEntity(
    val deviceId: String,
    val ts: Long,
    val samples: ByteArray,
) {
    // ByteArray needs structural equals/hashCode (the generated identity ones break round-trip asserts).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PpgWaveformSampleEntity) return false
        return deviceId == other.deviceId && ts == other.ts && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + ts.hashCode()
        result = 31 * result + samples.contentHashCode()
        return result
    }
}

/**
 * One Live Session (silent guardian) record. Natural key (deviceId, startTs). `endTs` is null
 * while the session is still in progress. Fields are declared in this order so the migration SQL
 * matches Room's generated shape.
 */
@Entity(tableName = "liveSession", primaryKeys = ["deviceId", "startTs"])
data class LiveSessionRow(
    val deviceId: String,
    val startTs: Long,
    val endTs: Long?,
    val chargeAtStart: Double?,
    val floorBpm: Double,
    val ceilingBpm: Double,
    val inBandSec: Double,
    val belowSec: Double,
    val aboveSec: Double,
    val pushCount: Int,
    val easeCount: Int,
    val hrSource: String,
)

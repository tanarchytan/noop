package com.noop.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data-access for the local store.
 *
 * Stream inserts use OnConflictStrategy.IGNORE (idempotent by natural key — re-inserting an
 * existing row is a no-op).
 *
 * Server-derived caches (dailyMetric, sleepSession, metricSeries) use @Upsert so the latest
 * server value wins on conflict.
 *
 * Range reads are ORDER BY ts ASC (R-R and events add a secondary key), bound by [from, to]
 * inclusive with a row limit.
 */
@Dao
interface WhoopDao : DeviceRegistryDao {

    // MARK: - Device

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceRow)

    @Query("SELECT * FROM device WHERE id = :id")
    suspend fun device(id: String): DeviceRow?

    // The device-registry reads/writes (pairedDevice/dayOwnership, v8) live on the narrow
    // [DeviceRegistryDao] super-interface so [DeviceRegistry] is unit-testable with a fake DAO
    // (no Robolectric). Room flattens the inherited methods into this @Dao at compile time.

    // MARK: - Stream inserts (idempotent by natural key)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHr(rows: List<HrSample>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRr(rows: List<RrInterval>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(rows: List<EventRow>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBattery(rows: List<BatterySample>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSpo2(rows: List<Spo2Sample>): List<Long>

    /** WHOOP 5.0/MG sleep SpO2 percent (v18 @frame-82). Persist-only, idempotent by (deviceId, ts). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSpo2Pct(rows: List<Spo2PctSample>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkinTemp(rows: List<SkinTempSample>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertV18(rows: List<V18Sample>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSteps(rows: List<StepSample>): List<Long>

    /** The strap's OWN band sleep_state per record. Idempotent by (deviceId, ts). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSleepState(rows: List<SleepStateSampleEntity>): List<Long>

    /** Upsert one Live Session (v22). Natural key (deviceId, startTs) — start (endTs null) then end. */
    @Upsert
    suspend fun upsertLiveSession(row: LiveSessionRow)

    /** Most-recent Live Sessions first, for the look-back summary + streak. */
    @Query("SELECT * FROM liveSession WHERE deviceId = :deviceId ORDER BY startTs DESC LIMIT :limit")
    suspend fun recentLiveSessions(deviceId: String, limit: Int): List<LiveSessionRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResp(rows: List<RespSample>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGravity(rows: List<GravitySample>): List<Long>

    /** PPG-derived HR from the v26 optical waveform. Idempotent by (deviceId, ts). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPpgHr(rows: List<PpgHrSample>): List<Long>

    /** RAW v26 optical PPG waveform (packed i16 BLOB). Idempotent by (deviceId, ts). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPpgWaveform(rows: List<PpgWaveformSampleEntity>): List<Long>

    // MARK: - Server-derived caches (latest value wins)

    @Upsert
    suspend fun upsertDailyMetrics(rows: List<DailyMetric>)

    /**
     * Fill one day's blood oxygen ONLY where it is absent, leaving every other column untouched.
     * A plain upsert would rewrite the whole row, so a day the strap already scored would lose its
     * resting HR, HRV and sleep. Inserts the day when no row exists yet.
     */
    @Query(
        "INSERT INTO dailyMetric (deviceId, day, spo2Pct) VALUES (:deviceId, :day, :pct) " +
            "ON CONFLICT(deviceId, day) DO UPDATE SET spo2Pct = excluded.spo2Pct " +
            "WHERE dailyMetric.spo2Pct IS NULL",
    )
    suspend fun fillMissingSpo2(deviceId: String, day: String, pct: Double)

    @Upsert
    suspend fun upsertSleepSessions(rows: List<SleepSession>)

    /** Remove one sleep session by its full primary key (deviceId, startTs) — used by the
     *  bed/wake-time edit, which deletes then re-inserts because startTs is part of the PK. */
    @Query("DELETE FROM sleepSession WHERE deviceId = :deviceId AND startTs = :startTs")
    suspend fun deleteSleepSession(deviceId: String, startTs: Long)

    /** Manually ADD a sleep session the detector missed (e.g. a daytime nap). `onConflict = IGNORE`
     *  makes it purely ADDITIVE: never clobbers an existing session sharing the onset second (returns
     *  -1). userEdited = true (recompute guard) + startTsAdjusted = null + endTsAdjusted = the chosen
     *  wake (the user picked both bounds, so neither re-detects) protect/pin it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSleepSession(row: SleepSession): Long

    /**
     * Replace ONLY the stage breakdown of a user-edited night, leaving its bed/wake bounds
     * (startTsAdjusted/endTsAdjusted) and userEdited flag untouched — used by the post-sync heal that swaps
     * in real stages once raw arrives for a night edited before it landed (edit-time stages were a
     * fabricated placeholder). Scoped to `userEdited = 1` (Room stores Boolean true as INTEGER 1), so
     * it never rewrites an un-edited night. Keyed by the IMMUTABLE detected (deviceId, startTs), never
     * effectiveStartTs. Returns rows changed (0 when none match).
     */
    @Query(
        "UPDATE sleepSession SET stagesJSON = :stagesJSON " +
            "WHERE deviceId = :deviceId AND startTs = :detectedStartTs AND userEdited = 1"
    )
    suspend fun updateSleepStages(deviceId: String, detectedStartTs: Long, stagesJSON: String): Int

    /**
     * Refresh a hand-edited night's DETECTED wake from a fresh detection, leaving its onset, its stage
     * breakdown and the userEdited flag untouched — the other half of the heal above, for a night whose
     * end was computed before the raw finished offloading. Scoped to `userEdited = 1` AND
     * `endTsAdjusted IS NULL`, so a wake the user set is never moved. Keyed by the IMMUTABLE detected
     * (deviceId, startTs). Returns rows changed (0 when none match).
     */
    @Query(
        "UPDATE sleepSession SET endTs = :detectedEndTs " +
            "WHERE deviceId = :deviceId AND startTs = :detectedStartTs " +
            "AND userEdited = 1 AND endTsAdjusted IS NULL"
    )
    suspend fun refreshSleepEnd(deviceId: String, detectedStartTs: Long, detectedEndTs: Long): Int

    /** v18: write per-epoch motion magnitudes (compact JSON array) for one session, banked beside
     *  `stagesJSON` on the same row. Keyed by the IMMUTABLE (deviceId, startTs); `null` clears the
     *  column. Targeted UPDATE the @Upsert path never touches. Returns rows changed (0 if no session). */
    @Query(
        "UPDATE sleepSession SET motionJSON = :json WHERE deviceId = :deviceId AND startTs = :sessionStart"
    )
    suspend fun updateSessionMotion(deviceId: String, sessionStart: Long, json: String?): Int

    /** v18: read the per-epoch motion JSON for one session, or null when unset / no such session.
     *  The repository decodes it to `List<Double>?` (absent stays absent). */
    @Query("SELECT motionJSON FROM sleepSession WHERE deviceId = :deviceId AND startTs = :sessionStart")
    suspend fun sessionMotionJson(deviceId: String, sessionStart: Long): String?

    /**
     * v18: write the decoded band sleep_state per epoch (compact JSON int array) for one session.
     * Keyed by (deviceId, startTs); `null` clears the column. Targeted UPDATE, so the @Upsert path
     * leaves it untouched. Returns rows changed. */
    @Query(
        "UPDATE sleepSession SET sleepStateJSON = :json WHERE deviceId = :deviceId AND startTs = :sessionStart"
    )
    suspend fun updateSessionSleepState(deviceId: String, sessionStart: Long, json: String?): Int

    /** v18: read the decoded band sleep_state JSON for one session, or null when unset.
     *  The repository decodes it to `List<Int>?`. */
    @Query("SELECT sleepStateJSON FROM sleepSession WHERE deviceId = :deviceId AND startTs = :sessionStart")
    suspend fun sessionSleepStateJson(deviceId: String, sessionStart: Long): String?

    @Upsert
    suspend fun upsertMetricSeries(rows: List<MetricSeriesRow>)

    @Upsert
    suspend fun upsertJournal(rows: List<JournalEntry>)

    @Upsert
    suspend fun upsertWorkouts(rows: List<WorkoutRow>)

    @Upsert
    suspend fun upsertAppleDaily(rows: List<AppleDaily>)

    // MARK: - Range reads (ORDER BY ts ASC, inclusive [from, to], limited)

    /** COALESCE union: the measured `hrSample` is authoritative; the v26 PPG-derived `ppgHrSample`
     *  fills ONLY seconds the strap never reported a bpm for (anti-join), so a PPG-only WHOOP 5
     *  night still clears the scoring gate. PPG rows carry synced = 0. */
    @Query(
        "SELECT deviceId, ts, bpm, synced FROM (" +
            "SELECT deviceId, ts, bpm, synced FROM hrSample " +
            "WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "UNION ALL " +
            "SELECT p.deviceId AS deviceId, p.ts AS ts, p.bpm AS bpm, 0 AS synced FROM ppgHrSample p " +
            "WHERE p.deviceId = :deviceId AND p.ts >= :from AND p.ts <= :to " +
            "AND NOT EXISTS (SELECT 1 FROM hrSample h WHERE h.deviceId = p.deviceId AND h.ts = p.ts)" +
            ") ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun hrSamples(deviceId: String, from: Long, to: Long, limit: Int): List<HrSample>

    /** RAW measured HR only — the `hrSample` table with NO v26 PPG-derived union (cf. [hrSamples]).
     *  Backs the raw-sensor diagnostic export, which emits measured HR and PPG-derived HR as two
     *  distinct streams so they're never conflated. Range read, ts asc, row-limited. */
    @Query(
        "SELECT * FROM hrSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun rawHrSamples(deviceId: String, from: Long, to: Long, limit: Int): List<HrSample>

    /** Downsampled HR for charting: mean bpm per [bucketSeconds]-wide bucket over [from, to], keyed
     *  by the bucket start (floor(ts/bucket)*bucket), aggregated in SQL. COALESCE union: the real
     *  sensor `hrSample` is authoritative; the v26 PPG-derived `ppgHrSample` only contributes seconds
     *  the strap NEVER reported a bpm for, so derived HR fills gaps without ever double-counting. */
    @Query(
        "SELECT (ts / :bucketSeconds) * :bucketSeconds AS bucket, AVG(bpm) AS avgBpm FROM (" +
            "SELECT ts, bpm FROM hrSample " +
            "WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "UNION ALL " +
            "SELECT p.ts AS ts, p.bpm AS bpm FROM ppgHrSample p " +
            "WHERE p.deviceId = :deviceId AND p.ts >= :from AND p.ts <= :to " +
            "AND NOT EXISTS (SELECT 1 FROM hrSample h WHERE h.deviceId = p.deviceId AND h.ts = p.ts)" +
            ") GROUP BY ts / :bucketSeconds ORDER BY bucket ASC"
    )
    suspend fun hrBuckets(deviceId: String, from: Long, to: Long, bucketSeconds: Long): List<HrBucket>

    /** Raw v26 PPG-derived HR samples in [from, to] (ascending). */
    @Query(
        "SELECT * FROM ppgHrSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun ppgHrSamples(deviceId: String, from: Long, to: Long, limit: Int): List<PpgHrSample>

    /** RAW v26 optical PPG waveform rows in [from, to] (ascending), packed i16 BLOB. */
    @Query(
        "SELECT * FROM ppgWaveformSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun ppgWaveformSamples(deviceId: String, from: Long, to: Long, limit: Int):
        List<PpgWaveformSampleEntity>

    /** Aggregate HR over a window (one indexed (deviceId,ts) range scan — no row materialisation,
     *  no [hrSamples] LIMIT truncation). Backs the imported-workout HR fallback. */
    @Query(
        "SELECT COUNT(*) AS n, AVG(bpm) AS avg, MAX(bpm) AS max FROM hrSample " +
            "WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to"
    )
    suspend fun hrWindowStats(deviceId: String, from: Long, to: Long): HrWindowStats

    @Query(
        // ORDER BY ts, ord, rrMs, seq preserves emission order: RMSSD is built from successive-beat
        // differences, so order WITHIN a second is the whole input. `ord` carries true emission
        // order; `seq` can't (assignRrSeq keys on (ts, rrMs), so every beat in a second holds 0).
        // Legacy rows hold a NULL ord (SQLite sorts it first), falling through to (rrMs, seq).
        "SELECT * FROM rrInterval WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC, ord ASC, rrMs ASC, seq ASC LIMIT :limit"
    )
    suspend fun rrIntervals(deviceId: String, from: Long, to: Long, limit: Int): List<RrInterval>

    @Query(
        "SELECT * FROM event WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC, kind ASC LIMIT :limit"
    )
    suspend fun events(deviceId: String, from: Long, to: Long, limit: Int): List<EventRow>

    @Query(
        "SELECT * FROM battery WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun batterySamples(deviceId: String, from: Long, to: Long, limit: Int): List<BatterySample>

    @Query(
        "SELECT * FROM spo2Sample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun spo2Samples(deviceId: String, from: Long, to: Long, limit: Int): List<Spo2Sample>

    @Query(
        "SELECT * FROM spo2PctSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun spo2PctSamples(deviceId: String, from: Long, to: Long, limit: Int): List<Spo2PctSample>

    @Query(
        "SELECT * FROM skinTempSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun skinTempSamples(deviceId: String, from: Long, to: Long, limit: Int): List<SkinTempSample>

    @Query(
        "SELECT * FROM stepSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun stepSamples(deviceId: String, from: Long, to: Long, limit: Int): List<StepSample>

    /** The strap's OWN banked band sleep_state in [from, to], ascending. Feeds the Deep Timeline
     *  band-state track and the per-session grid the re-onset confirm guard reads. */
    @Query(
        "SELECT * FROM sleepStateSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun sleepStateSamples(deviceId: String, from: Long, to: Long, limit: Int): List<SleepStateSampleEntity>

    @Query(
        "SELECT * FROM respSample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun respSamples(deviceId: String, from: Long, to: Long, limit: Int): List<RespSample>

    @Query(
        "SELECT * FROM gravitySample WHERE deviceId = :deviceId AND ts >= :from AND ts <= :to " +
            "ORDER BY ts ASC LIMIT :limit"
    )
    suspend fun gravitySamples(deviceId: String, from: Long, to: Long, limit: Int): List<GravitySample>

    // MARK: - Daily metrics / sleep reads

    /** Cached daily metrics for days in [from, to] (lexicographic YYYY-MM-DD compare), oldest first. */
    @Query(
        "SELECT * FROM dailyMetric WHERE deviceId = :deviceId AND day >= :from AND day <= :to " +
            "ORDER BY day ASC"
    )
    suspend fun dailyMetricsRange(deviceId: String, from: String, to: String): List<DailyMetric>

    /**
     * Delete a source's cached daily rows whose day-key is in [from, to] (inclusive, yyyy-MM-dd
     * lexicographic = chronological). Used by the local-day re-bucketing migration to drop computed
     * ("-noop") UTC-keyed rows before re-upserting LOCAL-keyed ones, so a UTC/local duplicate day
     * can't linger. Source-scoped, so imported "my-whoop" rows are never touched.
     */
    @Query("DELETE FROM dailyMetric WHERE deviceId = :deviceId AND day >= :from AND day <= :to")
    suspend fun deleteDailyMetricsInRange(deviceId: String, from: String, to: String)

    /** All cached daily metrics for a device, oldest first. Convenience for analytics windows. */
    @Query("SELECT * FROM dailyMetric WHERE deviceId = :deviceId ORDER BY day ASC")
    suspend fun days(deviceId: String): List<DailyMetric>

    /** Scalar COUNT twin of [days], for count badges that were materializing every row for `.size`. */
    @Query("SELECT COUNT(*) FROM dailyMetric WHERE deviceId = :deviceId")
    suspend fun daysCount(deviceId: String): Int

    /** Earliest / latest cached day-key for a source (yyyy-MM-dd, lexicographic = chronological), null
     *  when the source has no daily rows. Feeds the Data Sources import-range line. */
    @Query("SELECT MIN(day) FROM dailyMetric WHERE deviceId = :deviceId")
    suspend fun minDay(deviceId: String): String?

    @Query("SELECT MAX(day) FROM dailyMetric WHERE deviceId = :deviceId")
    suspend fun maxDay(deviceId: String): String?

    /** Reactive stream of all daily metrics for a device, oldest first. */
    @Query("SELECT * FROM dailyMetric WHERE deviceId = :deviceId ORDER BY day ASC")
    fun daysFlow(deviceId: String): Flow<List<DailyMetric>>

    /**
     * Every distinct source id with at least one cached daily row. The Health Connect backfill's
     * covered-days gate filters these via (HealthConnectImporter.isStrapNativeSourceId), so the
     * skip-set also covers an actively paired strap's "whoop-<mac>" / "whoop-<mac>-noop" rows, not
     * just the canonical "my-whoop" / "my-whoop-noop" pair.
     */
    @Query("SELECT DISTINCT deviceId FROM dailyMetric")
    suspend fun dailyMetricDeviceIds(): List<String>

    /**
     * Heal: delete un-edited "my-whoop" sleep sessions with no signal beyond a window (no efficiency /
     * restingHr / avgHrv / motionJSON / sleepStateJSON — the Health Connect backfill's shape) when a
     * computed ("-noop") session overlaps the same window, so the richer night wins the merge.
     * CSV/wearable rows carry efficiency or HR/HRV and are never matched; userEdited rows are untouched.
     */
    @Query(
        "DELETE FROM sleepSession WHERE deviceId = 'my-whoop' AND userEdited = 0 " +
            "AND efficiency IS NULL AND restingHr IS NULL AND avgHrv IS NULL " +
            "AND motionJSON IS NULL AND sleepStateJSON IS NULL " +
            "AND EXISTS (SELECT 1 FROM sleepSession c WHERE c.deviceId LIKE '%-noop' " +
            "AND c.startTs < sleepSession.endTs " +
            "AND COALESCE(c.endTsAdjusted, c.endTs) > sleepSession.startTs)"
    )
    suspend fun purgeHcShadowedSleepSessions(): Int

    /**
     * Heal: delete "my-whoop" daily rows shaped like a Health Connect backfill (no efficiency / stage
     * minutes / disturbances / recovery / strain / steps — HC only writes totals + vitals) on a day a
     * computed ("-noop") source also covers, so the sparse row can't shadow the computed day in the
     * imported-wins merge. CSV-imported days carry stage minutes + efficiency and are never matched.
     */
    @Query(
        "DELETE FROM dailyMetric WHERE deviceId = 'my-whoop' " +
            "AND efficiency IS NULL AND deepMin IS NULL AND remMin IS NULL AND lightMin IS NULL " +
            "AND disturbances IS NULL AND recovery IS NULL AND strain IS NULL " +
            "AND steps IS NULL AND activeKcalEst IS NULL " +
            "AND day IN (SELECT day FROM dailyMetric d WHERE d.deviceId LIKE '%-noop')"
    )
    suspend fun purgeHcShadowedDailyMetrics(): Int

    /**
     * The most-recent [limit] daily metrics for a device, returned oldest-first. Backs the bounded
     * dashboard merge: SQL takes the newest rows (ORDER BY day DESC LIMIT), and the repository flips
     * them to ascending so every downstream consumer sees the SAME order as [daysFlow]. A generous
     * bound (RECENT_DAYS_CAP) avoids re-merging the WHOLE history on every DB change.
     */
    @Query("SELECT * FROM dailyMetric WHERE deviceId = :deviceId ORDER BY day DESC LIMIT :limit")
    fun recentDaysFlow(deviceId: String, limit: Int): Flow<List<DailyMetric>>

    @Query(
        "SELECT * FROM sleepSession WHERE deviceId = :deviceId AND startTs >= :from AND startTs <= :to " +
            "ORDER BY startTs ASC LIMIT :limit"
    )
    suspend fun sleepSessions(deviceId: String, from: Long, to: Long, limit: Int): List<SleepSession>

    /** Hand-edited sessions for a device (userEdited = 1), oldest first. The repository maps each to
     *  its LOCAL wake-day so [WhoopRepository.mergeDaily] lets the computed sleep fields win on those
     *  days over a re-imported night. */
    @Query("SELECT * FROM sleepSession WHERE deviceId = :deviceId AND userEdited = 1 ORDER BY startTs ASC")
    suspend fun editedSleepSessions(deviceId: String): List<SleepSession>

    /** Reactive variant of [editedSleepSessions] for the merged daily Flow. */
    @Query("SELECT * FROM sleepSession WHERE deviceId = :deviceId AND userEdited = 1 ORDER BY startTs ASC")
    fun editedSleepSessionsFlow(deviceId: String): Flow<List<SleepSession>>

    // MARK: - Generic metric series (v9)

    @Query(
        "SELECT * FROM metricSeries WHERE deviceId = :deviceId AND key = :key AND day >= :from AND day <= :to " +
            "ORDER BY day ASC"
    )
    suspend fun metricSeries(
        deviceId: String,
        key: String,
        from: String,
        to: String,
    ): List<MetricSeriesRow>

    /** Distinct metric keys present for a device, sorted ascending (v9). */
    @Query("SELECT DISTINCT key FROM metricSeries WHERE deviceId = :deviceId ORDER BY key ASC")
    suspend fun metricKeys(deviceId: String): List<String>

    /** Row count for one (deviceId, key) series — the scalar COUNT twin of [metricSeries], for count
     *  badges (Data Sources) that were materializing the full history just to call `.size`. */
    @Query("SELECT COUNT(*) FROM metricSeries WHERE deviceId = :deviceId AND key = :key")
    suspend fun metricSeriesKeyCount(deviceId: String, key: String): Int

    /** The NEWEST row of a (deviceId, key) series, or null — the ORDER BY day DESC LIMIT 1 twin of
     *  [metricSeries] for latest-value tiles (day is yyyy-MM-dd, so lexicographic MAX(day) = newest).
     *  Rides the same index, so the read stops at one row instead of materializing the whole series. */
    @Query("SELECT * FROM metricSeries WHERE deviceId = :deviceId AND key = :key ORDER BY day DESC LIMIT 1")
    suspend fun latestMetricSeriesRow(deviceId: String, key: String): MetricSeriesRow?

    /** Delete one projected day for a key (used when a Lab Book reading's last numeric value
     *  for a (markerKey, day) cell is removed). */
    @Query("DELETE FROM metricSeries WHERE deviceId = :deviceId AND day = :day AND key = :key")
    suspend fun deleteMetricSeriesPoint(deviceId: String, day: String, key: String)

    // MARK: - Lab Book markers (v17)
    //
    // The book is `labMarker` (one row per dated reading the user entered); the daily `metricSeries`
    // projection under source [LAB_BOOK_SOURCE_ID] is how the book talks to the rest of the app.
    // [upsertLabMarkers] / [deleteLabMarker] keep the two in lockstep in a single transaction.

    /** Raw upsert of marker rows by the natural key (UNIQUE index idx_labMarker_natural): a
     *  re-import of the same (deviceId, markerKey, takenAt, source) REPLACEs in place rather than
     *  duplicating. Prefer [upsertLabMarkers] (which also re-projects); this primitive backs it. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabMarkersRaw(rows: List<LabMarkerRow>)

    /** All readings in a category, oldest first (by takenAt). */
    @Query("SELECT * FROM labMarker WHERE deviceId = :deviceId AND category = :category ORDER BY takenAt ASC")
    suspend fun labMarkersByCategory(deviceId: String, category: String): List<LabMarkerRow>

    /** Full reading history for one marker, oldest first (by takenAt). */
    @Query("SELECT * FROM labMarker WHERE deviceId = :deviceId AND markerKey = :markerKey ORDER BY takenAt ASC")
    suspend fun labMarkersByKey(deviceId: String, markerKey: String): List<LabMarkerRow>

    /** Distinct marker keys present for a device, sorted ascending. */
    @Query("SELECT DISTINCT markerKey FROM labMarker WHERE deviceId = :deviceId ORDER BY markerKey ASC")
    suspend fun markerKeysPresent(deviceId: String): List<String>

    /** One reading by id, or null. Backs the delete-then-reproject flow. */
    @Query("SELECT * FROM labMarker WHERE id = :id")
    suspend fun labMarkerById(id: String): LabMarkerRow?

    /** Latest NUMERIC value for a (markerKey, day) cell (greatest takenAt with value not null),
     *  or null if the cell has no numeric reading. The latest-per-day projection rule. */
    @Query(
        "SELECT value FROM labMarker " +
            "WHERE deviceId = :deviceId AND markerKey = :markerKey AND day = :day AND value IS NOT NULL " +
            "ORDER BY takenAt DESC LIMIT 1"
    )
    suspend fun latestNumericForCell(deviceId: String, markerKey: String, day: String): Double?

    @Query("DELETE FROM labMarker WHERE id = :id")
    suspend fun deleteLabMarkerRaw(id: String)

    /**
     * Upsert marker rows, then re-project each affected (markerKey, day) cell into `metricSeries`
     * under [LAB_BOOK_SOURCE_ID]. Idempotent by natural key; LATEST-numeric-per-day wins (a
     * valueText-only reading never projects a cell). Atomic, so the write and projection can't diverge.
     */
    @Transaction
    suspend fun upsertLabMarkers(rows: List<LabMarkerRow>) {
        if (rows.isEmpty()) return
        insertLabMarkersRaw(rows)
        // Distinct touched cells, in a deterministic order.
        val cells = rows.map { Triple(it.deviceId, it.markerKey, it.day) }.toSet()
        for ((deviceId, markerKey, day) in cells) {
            reprojectCell(deviceId, markerKey, day)
        }
    }

    /**
     * Delete one reading by id; if it was the last numeric reading for its (markerKey, day) cell the
     * projected day is removed, otherwise the projection is recomputed from the remainder. Returns
     * true if a row was deleted.
     */
    @Transaction
    suspend fun deleteLabMarker(id: String): Boolean {
        val row = labMarkerById(id) ?: return false
        deleteLabMarkerRaw(id)
        reprojectCell(row.deviceId, row.markerKey, row.day)
        return true
    }

    /** Recompute the `metricSeries` projection (under [LAB_BOOK_SOURCE_ID]) for one cell from the
     *  CURRENT labMarker rows: latest-numeric-per-day wins; no numeric reading → drop the day. */
    @Transaction
    suspend fun reprojectCell(deviceId: String, markerKey: String, day: String) {
        val latest = latestNumericForCell(deviceId, markerKey, day)
        if (latest != null) {
            upsertMetricSeries(listOf(MetricSeriesRow(LAB_BOOK_SOURCE_ID, day, markerKey, latest)))
        } else {
            deleteMetricSeriesPoint(LAB_BOOK_SOURCE_ID, day, markerKey)
        }
    }

    companion object {
        /** The constant device-id the daily marker projection is written under, so Compare/Explore/
         *  Coach see markers as a single-source series. */
        const val LAB_BOOK_SOURCE_ID = "lab-book"
    }

    // MARK: - One-time refile: separate legacy Health Connect data from the Apple Health bucket.
    // Only an Apple Health EXPORT writes metricSeries, so metricSeries-count == 0 means the row is
    // Health-Connect-origin and safe to move (HC workouts are tagged source, so they always move).
    // Idempotent: nothing writes HC data to apple-health again, so re-runs match 0 rows.
    @Query("SELECT COUNT(*) FROM metricSeries WHERE deviceId = :deviceId")
    suspend fun metricSeriesCount(deviceId: String): Int

    @Query("UPDATE appleDaily SET deviceId = :to WHERE deviceId = :from")
    suspend fun reassignAppleDaily(from: String, to: String)

    @Query("UPDATE workout SET deviceId = :to WHERE deviceId = :from AND source = :source")
    suspend fun reassignWorkoutsBySource(from: String, to: String, source: String)

    // MARK: - Journal / workouts / Apple-Health reads (v8)

    /**
     * Journal entries for days in [from, to] (lexicographic YYYY-MM-DD compare), oldest day first
     * then by question.
     */
    @Query(
        "SELECT * FROM journal WHERE deviceId = :deviceId AND day >= :from AND day <= :to " +
            "ORDER BY day ASC, question ASC"
    )
    suspend fun journal(deviceId: String, from: String, to: String): List<JournalEntry>

    /**
     * Delete one journal answer by natural key (the native logging card's "clear"). Source-scoped
     * by deviceId, so clearing a native ("noop-journal") answer never removes an identical imported row.
     */
    @Query("DELETE FROM journal WHERE deviceId = :deviceId AND day = :day AND question = :question")
    suspend fun deleteJournalEntry(deviceId: String, day: String, question: String)

    /**
     * Delete a device's journal within a day range. The importer clears exactly the span it
     * re-writes before upserting, so a wake-day re-keying can't leave stale duplicate rows behind.
     * Bounded to [from, to] and source-scoped by deviceId — the native ("noop-journal") log is
     * never touched.
     */
    @Query("DELETE FROM journal WHERE deviceId = :deviceId AND day >= :from AND day <= :to")
    suspend fun deleteJournalRange(deviceId: String, from: String, to: String)

    /**
     * Atomically replace a device's journal within a day range: clear [from, to] then upsert [rows]
     * in ONE transaction, so a crash mid-import can't leave the range deleted-but-not-repopulated.
     */
    @Transaction
    suspend fun replaceJournalRange(deviceId: String, from: String, to: String, rows: List<JournalEntry>) {
        deleteJournalRange(deviceId, from, to)
        upsertJournal(rows)
    }

    /**
     * Workouts whose startTs falls in [from, to] (unix seconds), oldest first, row-limited.
     */
    @Query(
        "SELECT * FROM workout WHERE deviceId = :deviceId AND startTs >= :from AND startTs <= :to " +
            "ORDER BY startTs ASC LIMIT :limit"
    )
    suspend fun workouts(deviceId: String, from: Long, to: Long, limit: Int): List<WorkoutRow>

    /** Scalar COUNT twin of [workouts] (no row limit — a count badge wants the exact total), for
     *  badges that were materializing the row list for `.size`. */
    @Query("SELECT COUNT(*) FROM workout WHERE deviceId = :deviceId AND startTs >= :from AND startTs <= :to")
    suspend fun workoutsCount(deviceId: String, from: Long, to: Long): Int

    /**
     * Apple-Health daily aggregates for days in [from, to] (lexicographic compare), oldest first.
     */
    @Query(
        "SELECT * FROM appleDaily WHERE deviceId = :deviceId AND day >= :from AND day <= :to " +
            "ORDER BY day ASC"
    )
    suspend fun appleDaily(deviceId: String, from: String, to: String): List<AppleDaily>

    /** Scalar COUNT twin of [appleDaily], for badges that were materializing the rows for `.size`. */
    @Query("SELECT COUNT(*) FROM appleDaily WHERE deviceId = :deviceId AND day >= :from AND day <= :to")
    suspend fun appleDailyCount(deviceId: String, from: String, to: String): Int

    /** Delete a computed source's workouts of a given [sport] whose startTs is in [from, to]
     *  (makes detected-workout re-derivation idempotent). */
    @Query("DELETE FROM workout WHERE deviceId = :deviceId AND sport = :sport AND startTs >= :from AND startTs <= :to")
    suspend fun deleteWorkoutsBySport(deviceId: String, sport: String, from: Long, to: Long)

    /** Delete ONE workout by its full natural key (deviceId, startTs, sport). Used by the Workouts
     *  screen to remove a single manual / re-labelled session. */
    @Query("DELETE FROM workout WHERE deviceId = :deviceId AND startTs = :startTs AND sport = :sport")
    suspend fun deleteWorkoutByKey(deviceId: String, startTs: Long, sport: String)

    // MARK: - Dismissed detected bouts (durable marker; survives engine re-detection)

    /** Record a dismissed detected bout. IGNORE so re-dismissing the same bout is a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDismissed(rows: List<DismissedWorkout>)

    /** All dismissed markers for a [deviceId] (the computed "<id>-noop" source the detector writes). */
    @Query("SELECT * FROM dismissedWorkout WHERE deviceId = :deviceId")
    suspend fun dismissedWorkouts(deviceId: String): List<DismissedWorkout>

    /** Record a deleted sleep night. IGNORE so re-deleting the same night is a no-op. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDismissedSleep(rows: List<DismissedSleep>)

    /** All deleted-sleep markers for a [deviceId]. The engine reads the UNION of the imported id and its
     *  computed "<id>-noop" id (see [WhoopRepository.dismissedSleeps]), since a tombstone is written
     *  under whichever namespace owned the deleted row. */
    @Query("SELECT * FROM dismissedSleep WHERE deviceId = :deviceId")
    suspend fun dismissedSleeps(deviceId: String): List<DismissedSleep>

    /** Lift ONE deleted-sleep tombstone ("allow re-detection"): removes the marker so the night is
     *  re-detected from raw on the next analyze pass. Keyed by (deviceId, startTs), the same natural
     *  key [insertDismissedSleep] uses, so it removes exactly the tombstone that insert wrote. */
    @Query("DELETE FROM dismissedSleep WHERE deviceId = :deviceId AND startTs = :startTs")
    suspend fun deleteDismissedSleep(deviceId: String, startTs: Long)

    // MARK: - Frontier / stats

    /** Max HR sample ts for a device, or null if none — the biometric data frontier. COALESCEs
     *  measured `hrSample` with the v26 PPG-derived `ppgHrSample`, so a PPG-only offload (no
     *  measured HR) still advances the frontier. Both persist on the same per-second ts grid. */
    @Query(
        "SELECT MAX(ts) FROM (" +
            "SELECT ts FROM hrSample WHERE deviceId = :deviceId " +
            "UNION ALL " +
            "SELECT ts FROM ppgHrSample WHERE deviceId = :deviceId)",
    )
    suspend fun latestHrSampleTs(deviceId: String): Long?

    @Query("SELECT COUNT(*) FROM hrSample") suspend fun countHr(): Int
    // Max raw-HR timestamp across all devices. Paired with countHr() as a cheap whole-history change
    // fingerprint, so the 15-min idle rescore can skip when nothing new has landed (COALESCE → 0 when empty).
    @Query("SELECT COALESCE(MAX(ts), 0) FROM hrSample") suspend fun maxHrTs(): Long
    @Query("SELECT COUNT(*) FROM rrInterval") suspend fun countRr(): Int
    @Query("SELECT COUNT(*) FROM event") suspend fun countEvents(): Int
    @Query("SELECT COUNT(*) FROM battery") suspend fun countBattery(): Int
    @Query("SELECT COUNT(*) FROM spo2Sample") suspend fun countSpo2(): Int
    @Query("SELECT COUNT(*) FROM skinTempSample") suspend fun countSkinTemp(): Int
    @Query("SELECT COUNT(*) FROM stepSample") suspend fun countSteps(): Int
    @Query("SELECT COUNT(*) FROM respSample") suspend fun countResp(): Int
    @Query("SELECT COUNT(*) FROM gravitySample") suspend fun countGravity(): Int

    // MARK: - Live convenience reads

    /** Latest HR sample for a device (most recent ts), or null. */
    @Query("SELECT * FROM hrSample WHERE deviceId = :deviceId ORDER BY ts DESC LIMIT 1")
    suspend fun latestHr(deviceId: String): HrSample?

    /** Latest battery sample for a device (most recent ts), or null. */
    @Query("SELECT * FROM battery WHERE deviceId = :deviceId ORDER BY ts DESC LIMIT 1")
    suspend fun latestBattery(deviceId: String): BatterySample?

    // MARK: - One-time heal: purge rows polluted by a bad-strap-clock timestamp
    //
    // A bad strap clock decoded `unix` to garbage (far-past or a future date) before the ingest gate
    // existed. These deletes purge the pollution across EVERY device id (raw "my-whoop" streams AND
    // "-noop" computed rows), using bounds from [MIN_PLAUSIBLE_UNIX]/[FUTURE_MARGIN] (future-day string
    // = local "today"). Each returns the row count deleted; idempotent (nothing left to match on a re-run).

    /** Raw stream rows whose unix-second `ts` is implausible (before [minTs] or after [maxTs]). One per
     *  raw table (all keyed by `ts`); summed by the repository. */
    @Query("DELETE FROM hrSample WHERE ts < :minTs OR ts > :maxTs")
    suspend fun pruneHrByTs(minTs: Long, maxTs: Long): Int

    @Query("DELETE FROM ppgHrSample WHERE ts < :minTs OR ts > :maxTs")
    suspend fun prunePpgHrByTs(minTs: Long, maxTs: Long): Int

    @Query("DELETE FROM rrInterval WHERE ts < :minTs OR ts > :maxTs")
    suspend fun pruneRrByTs(minTs: Long, maxTs: Long): Int

    @Query("DELETE FROM skinTempSample WHERE ts < :minTs OR ts > :maxTs")
    suspend fun pruneSkinTempByTs(minTs: Long, maxTs: Long): Int

    @Query("DELETE FROM stepSample WHERE ts < :minTs OR ts > :maxTs")
    suspend fun pruneStepByTs(minTs: Long, maxTs: Long): Int

    @Query("DELETE FROM respSample WHERE ts < :minTs OR ts > :maxTs")
    suspend fun pruneRespByTs(minTs: Long, maxTs: Long): Int

    @Query("DELETE FROM gravitySample WHERE ts < :minTs OR ts > :maxTs")
    suspend fun pruneGravityByTs(minTs: Long, maxTs: Long): Int

    @Query("DELETE FROM spo2Sample WHERE ts < :minTs OR ts > :maxTs")
    suspend fun pruneSpo2ByTs(minTs: Long, maxTs: Long): Int

    @Query("DELETE FROM event WHERE ts < :minTs OR ts > :maxTs")
    suspend fun pruneEventByTs(minTs: Long, maxTs: Long): Int

    @Query("DELETE FROM battery WHERE ts < :minTs OR ts > :maxTs")
    suspend fun pruneBatteryByTs(minTs: Long, maxTs: Long): Int

    /** Daily-metric rows whose `day` is FUTURE (after [today], any source) or implausibly old (before
     *  [minDay]) AND computed (`-noop`). The far-past floor is `-noop`-scoped, so a WHOOP CSV import
     *  ("my-whoop") carrying REAL multi-year history is never purged. String compare is correct for ISO dates. */
    @Query("DELETE FROM dailyMetric WHERE day > :today OR (day < :minDay AND deviceId LIKE '%-noop')")
    suspend fun pruneDailyMetricByDay(today: String, minDay: String): Int

    /** Sleep-session rows whose onset `startTs` is future (after [maxTs], any source) or implausibly old
     *  (before [minTs]) AND computed (`-noop`), so an imported multi-year sleep history survives. */
    @Query("DELETE FROM sleepSession WHERE startTs > :maxTs OR (startTs < :minTs AND deviceId LIKE '%-noop')")
    suspend fun pruneSleepSessionByTs(minTs: Long, maxTs: Long): Int
}

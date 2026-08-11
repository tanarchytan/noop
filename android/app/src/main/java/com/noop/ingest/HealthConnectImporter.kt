package com.noop.ingest

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.noop.NoopApplication
import com.noop.R
import com.noop.analytics.FitnessAgeEngine
import com.noop.analytics.HydrationStore
import com.noop.data.AppleDaily
import com.noop.data.DailyMetric
import com.noop.data.ImportSummary
import com.noop.data.MetricSeriesRow
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.round
import kotlin.reflect.KClass

/**
 * Native Android Health Connect importer. Reads a fixed set of record types via
 * `androidx.health.connect:connect-client`, aggregates them per local calendar day, and upserts
 * into the same Room store [WhoopRepository] uses. Timestamps are wall-clock unix seconds.
 *
 * Device-id mapping: daily aggregates (steps/calories/VO2max/weight/avg-HR) -> [AppleDaily] under
 * "health-connect"; autonomic markers (resting-HR/HRV/sleep/SpO2/respiration) -> [DailyMetric] under
 * "health-connect" too, where WhoopRepository's gap-fill bucket ranks them below every strap source,
 * and still only for days the strap doesn't already cover; sleep SESSIONS -> [SleepSession] under
 * "my-whoop", where mergeSleepRichness yields them to a night the strap staged; exercise sessions ->
 * [WorkoutRow] under "health-connect".
 *
 * Permissions are assumed granted before [import] is called; otherwise it returns
 * [ImportSummary.failure].
 */
object HealthConnectImporter {

    const val SOURCE = "Health Connect"

    /** metricSeries key marking a day a menstrual period began; the cycle classifier reads these. */
    const val KEY_PERIOD_START = "period_start"

    private const val WHOOP = "my-whoop"
    // The computed/derived source IntelligenceEngine writes recovery/strain/sleep+stages under,
    // namely "<importedDeviceId>-noop" with importedDeviceId == WHOOP. A strap-only WHOOP user has
    // NO raw "my-whoop" daily rows — their nights live only here — so the backfill guard below must
    // treat a day the strap computed as already-owned too, or the sparse HC row shadows it.
    private const val WHOOP_COMPUTED = "$WHOOP-noop"
    // Health Connect data is stored under its own source ("health-connect"), not the shared
    // "apple-health" bucket, so it isn't mis-attributed to Apple Health in the UI and so the read side
    // can rank it below every strap source. The sleep SESSIONS still land under "my-whoop", where
    // WhoopRepository.mergeSleepRichness already yields them to a night the strap staged.
    const val HC_DEVICE = "health-connect"
    private const val HC_WORKOUT_SOURCE = "health-connect"

    /** Read window: a wide ~10-year span ending now. Health Connect itself caps retention. */
    private const val WINDOW_YEARS = 10L

    /** Page size for paginated readRecords() calls. */
    private const val PAGE_SIZE = 5000

    /** Slack (seconds) when matching a DistanceRecord to a workout session — a relay app can write the
     *  distance record offset by up to a few minutes from the session it belongs to. Overlap clipping
     *  keeps a neighbouring activity inside this window from over-counting. */
    private const val DISTANCE_MATCH_BUFFER_S = 300L

    /** The record types this importer reads, in one place so PERMISSIONS stays in sync. */
    private val READ_RECORDS: List<KClass<out Record>> = listOf(
        StepsRecord::class,
        TotalCaloriesBurnedRecord::class,
        ActiveCaloriesBurnedRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        SleepSessionRecord::class,
        OxygenSaturationRecord::class,
        RespiratoryRateRecord::class,
        Vo2MaxRecord::class,
        WeightRecord::class,
        BodyFatRecord::class,
        LeanBodyMassRecord::class,
        HeightRecord::class,
        BloodPressureRecord::class,
        HydrationRecord::class,
        NutritionRecord::class,
        MenstruationPeriodRecord::class,
        ExerciseSessionRecord::class,
        DistanceRecord::class,
    )

    /**
     * The set of Health Connect read-permission strings the UI must request before calling
     * [import]. One `READ_*` permission per record type in [READ_RECORDS].
     */
    val PERMISSIONS: Set<String> =
        READ_RECORDS.map { HealthPermission.getReadPermission(it) }.toSet()

    /**
     * Whether Health Connect is installed/available on this device — one of
     * [HealthConnectClient.SDK_AVAILABLE], [HealthConnectClient.SDK_UNAVAILABLE], or
     * [HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED].
     */
    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    /** The Health Connect client. Caller should gate on [sdkStatus] == SDK_AVAILABLE first. */
    fun client(context: Context): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    /**
     * Reads all configured record types, aggregates per local day, and upserts into [repo]. Assumes
     * [PERMISSIONS] are already granted; returns [ImportSummary.failure] otherwise. [heightCm] (the
     * user's profile height) derives BMI on days with a weight; pass 0.0 to skip BMI derivation.
     */
    suspend fun import(
        context: Context,
        repo: WhoopRepository,
        heightCm: Double = 0.0,
        onBodyMeasurements: (weightKg: Double?, heightCm: Double?) -> Unit = { _, _ -> },
    ): ImportSummary {
        if (sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
            return ImportSummary.failure(SOURCE, context.getString(R.string.import_hc_unavailable))
        }

        // Refile any legacy Health Connect data that landed in the shared "apple-health" bucket, before
        // importing, so a re-import refiles cleanly instead of duplicating across both sources.
        try { repo.refileLegacyHealthConnect() } catch (_: Exception) { /* best-effort */ }
        // Purge sparse HC-shaped "my-whoop" sleep sessions sitting over nights the strap's computed
        // ("-noop") source already covers, before this import's own coverage gate is read so the
        // healed state is what gets consulted. Idempotent, best-effort like the refile above.
        try { repo.purgeHcShadowedSleepDays() } catch (_: Exception) { /* best-effort */ }
        // Move daily rows this importer wrote under "my-whoop" before it had its own source, so they
        // gap-fill rather than outrank a strap-measured vital. Runs before any write below, so
        // "health-connect" holds no row this run could collide with. Best-effort and idempotent.
        try { repo.refileHcDailyRows() } catch (_: Exception) { /* best-effort */ }

        val client = client(context)

        // Verify the permissions really are granted (the UI may have been dismissed).
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            return ImportSummary.failure(SOURCE, "Could not read Health Connect permissions: ${e.message}")
        }
        // Partial permissions are fine: import the record types the user granted and skip the rest,
        // instead of refusing the whole import when a single type is missing. A revoked type throws and
        // is caught/skipped in [readAll], so we only bail when nothing at all is granted.
        if (granted.none { it in PERMISSIONS }) {
            return ImportSummary.failure(
                SOURCE,
                "No Health Connect data types are granted. Allow at least one type for NOOP in Health Connect, then import.",
            )
        }

        val zone = ZoneId.systemDefault()
        val end = Instant.now()
        val start = LocalDate.now(zone).minusYears(WINDOW_YEARS).atStartOfDay(zone).toInstant()
        val filter = TimeRangeFilter.between(start, end)
        // Skip our own writes on import (see readAll / isSelfWritten).
        val selfPackage = context.packageName
        // Height and weight are profile facts, not daily series: the newest reading wins and goes
        // back to the caller for the profile, where BMI, BMR and the VO2 max estimate read them.
        var newestWeightKg: Double? = null
        var newestWeightTs = Long.MIN_VALUE
        var newestHeightCm: Double? = null
        var newestHeightTs = Long.MIN_VALUE

        // Per-day accumulators. Keyed by "YYYY-MM-DD" (local).
        val acc = HashMap<String, DayAcc>()
        fun dayOf(instant: Instant, offset: ZoneOffset? = null): String = localDay(instant, offset, zone)
        fun bucket(day: String): DayAcc = acc.getOrPut(day) { DayAcc() }

        val workouts = ArrayList<WorkoutRow>()
        // A workout's local day, keyed by its start second. WorkoutRow stores epoch seconds only, so the
        // day computed from the record's own offset is kept here rather than recomputed from the phone's.
        val workoutDayByStartTs = HashMap<Long, String>()
        // Every energy record with its source, so a session can be credited with the calories burned
        // inside its window: ExerciseSessionRecord carries no energy of its own. Source is kept because
        // a phone and a watch both logging one ride must not be summed twice.
        val activeKcalRecords = ArrayList<KcalRecord>()
        val totalKcalRecords = ArrayList<KcalRecord>()
        // The Sleep screen reads repo.sleepSessions, so a per-day minute total alone leaves it empty.
        // Keep each night's bounds + per-stage minutes here, paired with its wake day for the
        // coveredDays gate at write-out.
        val hcSleepSessions = ArrayList<Pair<String, SleepSession>>()

        try {
            // --- Steps ---
            // Per-source sums: a phone and a watch both writing Health Connect steps count the same walk,
            // so summing across sources double-counts. Sum within a source (dataOrigin package), then
            // take the max source per day at write-out, matching the Android XML importer's de-overlap.
            readAll(client, StepsRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.startTime, r.startZoneOffset))
                val src = r.metadata.dataOrigin.packageName
                b.stepsBySource[src] = (b.stepsBySource[src] ?: 0L) + r.count
            }
            // --- Total calories burned (basal + active) ---
            // Per-source sums, max across sources at write-out (same overlap reasoning as steps).
            readAll(client, TotalCaloriesBurnedRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.startTime, r.startZoneOffset))
                val src = r.metadata.dataOrigin.packageName
                b.totalKcalBySource[src] = (b.totalKcalBySource[src] ?: 0.0) + r.energy.inKilocalories
                totalKcalRecords.add(KcalRecord(src, r.startTime.epochSecond, r.endTime.epochSecond, r.energy.inKilocalories))
            }
            // --- Active calories burned ---
            // Per-source sums, max across sources at write-out (same reasoning as steps). The per-record
            // window list below still gets every record, since it credits workouts by time overlap
            // separately; the per-source map only governs the day total.
            readAll(client, ActiveCaloriesBurnedRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.startTime, r.startZoneOffset))
                val src = r.metadata.dataOrigin.packageName
                b.activeKcalBySource[src] = (b.activeKcalBySource[src] ?: 0.0) + r.energy.inKilocalories
                activeKcalRecords.add(KcalRecord(src, r.startTime.epochSecond, r.endTime.epochSecond, r.energy.inKilocalories))
            }
            // --- Heart rate (instantaneous samples) -> per-day average ---
            readAll(client, HeartRateRecord::class, filter, selfPackage) { r ->
                for (s in r.samples) {
                    val b = bucket(dayOf(s.time, r.startZoneOffset))
                    b.hrSum += s.beatsPerMinute
                    b.hrCount += 1
                }
            }
            // --- Resting heart rate -> per-day average (rounded to Int) ---
            readAll(client, RestingHeartRateRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.time, r.zoneOffset))
                b.rhrSum += r.beatsPerMinute
                b.rhrCount += 1
            }
            // --- HRV (RMSSD, ms) -> per-day average ---
            readAll(client, HeartRateVariabilityRmssdRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.time, r.zoneOffset))
                b.hrvSum += r.heartRateVariabilityMillis
                b.hrvCount += 1
            }
            // --- Sleep sessions -> per-day total sleep minutes, assigned to the WAKE day ---
            readAll(client, SleepSessionRecord::class, filter, selfPackage) { r ->
                val day = dayOf(r.endTime, r.endZoneOffset)
                val b = bucket(day)
                // Prefer summed asleep-stage minutes; fall back to session span when no stages.
                val asleepMin = asleepMinutes(r)
                val totalMin = if (asleepMin > 0.0) asleepMin
                else (r.endTime.epochSecond - r.startTime.epochSecond) / 60.0
                b.sleepMin += totalMin
                b.hasSleep = true
                // Also keep the session itself so the Sleep screen has a night to show. startTs/endTs are
                // epoch seconds (what repo.sleepSessions queries). Per-stage minutes become stagesJSON in
                // the same shape the WHOOP CSV / Xiaomi importers use; null (no sub-stage) rides on totalSleepMin.
                hcSleepSessions.add(day to SleepSession(
                    deviceId = WHOOP,
                    startTs = r.startTime.epochSecond,
                    endTs = r.endTime.epochSecond,
                    stagesJSON = hcStagesJson(r),
                ))
            }
            // --- SpO2 (%) -> per-day average ---
            readAll(client, OxygenSaturationRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.time, r.zoneOffset))
                b.spo2Sum += r.percentage.value
                b.spo2Count += 1
            }
            // --- Respiratory rate (breaths/min) -> per-day average ---
            readAll(client, RespiratoryRateRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.time, r.zoneOffset))
                b.respSum += r.rate
                b.respCount += 1
            }
            // --- VO2 max (ml/kg/min) -> latest value of the day wins ---
            readAll(client, Vo2MaxRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.time, r.zoneOffset))
                if (r.time.epochSecond >= b.vo2maxTs) {
                    b.vo2max = r.vo2MillilitersPerMinuteKilogram
                    b.vo2maxTs = r.time.epochSecond
                }
            }
            // --- Weight (kg) -> latest value of the day wins ---
            readAll(client, WeightRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.time, r.zoneOffset))
                if (r.time.epochSecond >= b.weightTs) {
                    b.weightKg = r.weight.inKilograms
                    b.weightTs = r.time.epochSecond
                }
                if (r.time.epochSecond >= newestWeightTs) {
                    newestWeightTs = r.time.epochSecond
                    newestWeightKg = r.weight.inKilograms
                }
            }
            // --- Period starts -> the day a menstrual period began. The cycle classifier anchors
            // cycle-day 1 on the most recent one and cross-validates it against the detected shift. ---
            readAll(client, MenstruationPeriodRecord::class, filter, selfPackage) { r ->
                bucket(dayOf(r.startTime, r.startZoneOffset)).periodStart = true
            }
            // --- Blood pressure -> the day's last reading wins. A paired cuff is the only source of
            // a real BP number here; NOOP never estimates one. ---
            readAll(client, BloodPressureRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.time, r.zoneOffset))
                if (r.time.epochSecond >= b.bpTs) {
                    b.bpTs = r.time.epochSecond
                    b.systolic = r.systolic.inMillimetersOfMercury
                    b.diastolic = r.diastolic.inMillimetersOfMercury
                }
            }
            // --- Hydration + nutrition -> day totals, summed over every entry. Same keys the CSV
            // importer writes, so the Explore + Hydration screens read them unchanged. ---
            readAll(client, HydrationRecord::class, filter, selfPackage) { r ->
                bucket(dayOf(r.startTime, r.startZoneOffset)).hydrationMl += r.volume.inMilliliters
            }
            readAll(client, NutritionRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.startTime, r.startZoneOffset))
                r.energy?.let { b.kcalIn += it.inKilocalories }
                r.protein?.let { b.proteinG += it.inGrams }
                r.totalCarbohydrate?.let { b.carbsG += it.inGrams }
                r.totalFat?.let { b.fatG += it.inGrams }
            }
            // --- Height (cm) -> newest wins; not bucketed per day. ---
            readAll(client, HeightRecord::class, filter, selfPackage) { r ->
                if (r.time.epochSecond >= newestHeightTs) {
                    newestHeightTs = r.time.epochSecond
                    newestHeightCm = r.height.inMeters * 100.0
                }
            }
            // --- Body fat (%) -> latest value of the day wins. Health Connect's Percentage.value is
            // already 0-100 (the Apple Health import uses a 0..1 fraction), so it stores as-is and
            // matches AppleHealthImporter's "body_fat" key. ---
            readAll(client, BodyFatRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.time, r.zoneOffset))
                if (r.time.epochSecond >= b.bodyFatTs) {
                    b.bodyFatPct = r.percentage.value
                    b.bodyFatTs = r.time.epochSecond
                }
            }
            // --- Lean body mass (kg) -> latest value of the day wins (AppleHealthImporter's "lean_mass" key). ---
            readAll(client, LeanBodyMassRecord::class, filter, selfPackage) { r ->
                val b = bucket(dayOf(r.time, r.zoneOffset))
                if (r.time.epochSecond >= b.leanMassTs) {
                    b.leanMassKg = r.mass.inKilograms
                    b.leanMassTs = r.time.epochSecond
                }
            }
            // --- Exercise sessions -> WorkoutRow(source="health-connect") ---
            readAll(client, ExerciseSessionRecord::class, filter, selfPackage) { r ->
                val startS = r.startTime.epochSecond
                val endS = r.endTime.epochSecond
                workouts.add(
                    WorkoutRow(
                        deviceId = HC_DEVICE,
                        startTs = startS,
                        endTs = endS,
                        sport = exerciseName(r),
                        source = HC_WORKOUT_SOURCE,
                        durationS = (endS - startS).toDouble().coerceAtLeast(0.0),
                        energyKcal = null, // filled below, once every record is read
                        avgHr = null,
                        maxHr = null,
                        strain = null,
                        distanceM = null,
                        zonesJSON = null,
                        notes = r.title,
                    )
                )
                // Count exercises per local day on the start day for the WHOOP daily backfill.
                val exerciseDay = dayOf(r.startTime, r.startZoneOffset)
                workoutDayByStartTs[startS] = exerciseDay
                bucket(exerciseDay).exerciseCount += 1
            }

            // Fill per-workout HR: ExerciseSessionRecord carries no summary HR, so intersect each
            // session's window with its HeartRateRecord samples (a per-session read; the day-aggregate
            // pass above streams the full range and must not be buffered). readAll swallows a
            // per-session failure; needs >=60 samples (~1 min) so a few strays can't fabricate an average.
            for (i in workouts.indices) {
                val w = workouts[i]
                if (w.endTs <= w.startTs) continue
                var sum = 0L
                var n = 0L
                var max = 0L
                readAll(
                    client, HeartRateRecord::class,
                    TimeRangeFilter.between(
                        Instant.ofEpochSecond(w.startTs), Instant.ofEpochSecond(w.endTs)
                    ),
                    selfPackage,
                ) { hr ->
                    for (s in hr.samples) {
                        sum += s.beatsPerMinute
                        n += 1
                        if (s.beatsPerMinute > max) max = s.beatsPerMinute
                    }
                }
                if (n >= 60) {
                    workouts[i] = w.copy(
                        avgHr = round(sum.toDouble() / n).toInt(),
                        maxHr = max.toInt(),
                    )
                }
            }

            // Fill per-workout distance: ExerciseSessionRecord carries no distance, so sum the
            // DistanceRecord metres inside each session window (a per-session read, mirroring the HR
            // fill above). readAll swallows a per-session failure or revoked permission, so one bad
            // session can't fail the import.
            for (i in workouts.indices) {
                val w = workouts[i]
                if (w.endTs <= w.startTs) continue
                var meters = 0.0
                // A relay app (e.g. Suunto -> Health Sync) can write the cumulative DistanceRecord offset
                // by seconds to minutes from the session it belongs to, so read a buffered window to find
                // it, then count only the portion of each record that overlaps the actual session — so a
                // neighbouring activity's record inside the buffer can't over-count.
                val ws = w.startTs
                val we = w.endTs
                readAll(
                    client, DistanceRecord::class,
                    TimeRangeFilter.between(
                        Instant.ofEpochSecond(ws - DISTANCE_MATCH_BUFFER_S),
                        Instant.ofEpochSecond(we + DISTANCE_MATCH_BUFFER_S),
                    ),
                    selfPackage,
                ) { d ->
                    val rs = d.startTime.epochSecond
                    val re = d.endTime.epochSecond
                    val overlap = (minOf(re, we) - maxOf(rs, ws)).coerceAtLeast(0)
                    meters += when {
                        re > rs && overlap > 0 -> d.distance.inMeters * (overlap.toDouble() / (re - rs))
                        re <= rs && rs in (ws - DISTANCE_MATCH_BUFFER_S)..(we + DISTANCE_MATCH_BUFFER_S) ->
                            d.distance.inMeters // degenerate zero-length record near the session
                        else -> 0.0
                    }
                }
                if (meters > 0.0) {
                    workouts[i] = w.copy(distanceM = round1(meters))
                }
            }

            // Session energy, last: the total-derived estimate needs the day's basal burn, which is
            // only known once every energy record has been read.
            for (i in workouts.indices) {
                val w = workouts[i]
                val day = workoutDayByStartTs[w.startTs]?.let { acc[it] }
                val basal = day?.let {
                    basalKcal(maxSourceDouble(it.totalKcalBySource), maxSourceDouble(it.activeKcalBySource))
                }
                val kcal = sessionKcal(activeKcalRecords, totalKcalRecords, w.startTs, w.endTs, basal)
                if (kcal != null) workouts[i] = w.copy(energyKcal = kcal)
            }
        } catch (e: Exception) {
            return ImportSummary.failure(SOURCE, "Health Connect read failed: ${e.message}")
        }

        if (acc.isEmpty() && workouts.isEmpty()) {
            return ImportSummary(
                source = SOURCE,
                counts = emptyMap(),
                message = "No Health Connect data found to import.",
            )
        }

        // Days the strap already covers: read once, unioned across every strap-native source id — raw
        // "my-whoop" + computed "my-whoop-noop", plus any paired strap's "whoop-<mac>" / "-noop" rows.
        // A strap-only user has only the computed rows; a missing source degrades to empty, not fatal, and HC still gap-fills any uncovered day.
        val coveredDays: Set<String> = buildSet {
            for (id in strapSourceIds(repo)) addAll(strapDays(repo, id))
        }
        // A covered day drops the whole daily row below, blood oxygen with it. On 5.0/MG that one
        // value is still worth taking when the strap recorded none, so collect it here and fill the
        // gap after the write. Held to 5.0/MG by [Spo2Policy], the same gate the export side uses.
        val spo2Fills = ArrayList<Pair<String, Double>>()
        val spo2FillAllowed = spo2FillAllowed(context)

        val appleRows = ArrayList<AppleDaily>(acc.size)
        val dailyRows = ArrayList<DailyMetric>(acc.size)
        // Flattened (day, "weight", kg) points so the cross-source resolver Compare reads can see a
        // Health-Connect-only weight history, matching AppleHealthImporter's metricSeries emission.
        // HC_DEVICE is treated as apple-equivalent in WhoopRepository.sourceCandidates.
        val metricSeriesRows = ArrayList<MetricSeriesRow>(acc.size)

        for ((day, a) in acc) {
            // De-overlap the per-source maps by max (sum within a source, max across sources) so a
            // phone+watch pair doesn't double-count the day's steps / calories, matching the Android
            // XML importer's de-overlap.
            val daySteps = maxSourceLong(a.stepsBySource)
            val dayTotalKcal = maxSourceDouble(a.totalKcalBySource)
            val dayActiveKcal = maxSourceDouble(a.activeKcalBySource)

            // AppleDaily: steps / calories / vo2max / weight / avg-HR.
            val hasApple = daySteps > 0L || dayTotalKcal > 0.0 || dayActiveKcal > 0.0 ||
                a.vo2max != null || a.weightKg != null || a.hrCount > 0
            if (hasApple) {
                appleRows.add(
                    AppleDaily(
                        deviceId = HC_DEVICE,
                        day = day,
                        steps = if (daySteps > 0L) daySteps.toInt() else null,
                        activeKcal = if (dayActiveKcal > 0.0) round1(dayActiveKcal) else null,
                        basalKcal = basalKcal(dayTotalKcal, dayActiveKcal),
                        vo2max = a.vo2max?.let { round1(it) },
                        avgHr = if (a.hrCount > 0) round(a.hrSum.toDouble() / a.hrCount).toInt() else null,
                        maxHr = null,
                        walkingHr = null,
                        weightKg = a.weightKg?.let { round2(it) },
                    )
                )
                a.weightKg?.let { metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, "weight", round2(it)) }
                // Health Connect has no BMI record, so derive it from the day's weight and the user's
                // profile height (defaults to 178 cm until set, so this reflects the profile, not a
                // measured BMI). Sits inside the weight gate since BMI needs a weight; heightCm <= 0 skips it.
                derivedBmi(a.weightKg, heightCm)?.let {
                    metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, "bmi", it)
                }
            }

            // Body composition from a smart scale (e.g. synced via Garmin Connect) is metricSeries-only,
            // so emit it outside the hasApple gate — a scale-only day with no steps/HR still records its
            // readings. Same keys/units as AppleHealthImporter: body_fat as 0-100 percent, lean_mass in kg.
            a.bodyFatPct?.let { metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, "body_fat", round2(it)) }
            if (a.periodStart) {
                metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, KEY_PERIOD_START, 1.0)
            }
            a.systolic?.let { metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, "bp_systolic", round1(it)) }
            a.diastolic?.let { metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, "bp_diastolic", round1(it)) }
            if (a.hydrationMl > 0) {
                metricSeriesRows += MetricSeriesRow(HydrationStore.SOURCE_ID, day, HydrationStore.KEY, round1(a.hydrationMl))
            }
            if (a.kcalIn > 0) {
                metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, NutritionCsvImporter.KEY_CALORIES_IN, round1(a.kcalIn))
            }
            if (a.proteinG > 0) {
                metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, NutritionCsvImporter.KEY_PROTEIN_G, round1(a.proteinG))
            }
            if (a.carbsG > 0) {
                metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, NutritionCsvImporter.KEY_CARBS_G, round1(a.carbsG))
            }
            if (a.fatG > 0) {
                metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, NutritionCsvImporter.KEY_FAT_G, round1(a.fatG))
            }
            a.leanMassKg?.let { metricSeriesRows += MetricSeriesRow(HC_DEVICE, day, "lean_mass", round2(it)) }

            // DailyMetric (health-connect): resting-HR / HRV / sleep-minutes / SpO2 / respiration. Its own
            // source, so WhoopRepository's gap-fill bucket ranks it BELOW every strap source instead of
            // letting a phone aggregate outrank a measured vital. Still skips days the strap already
            // covers, so nothing is written that only precedence would then have to discard.
            if (day !in coveredDays) {
                hcDailyRow(
                    day = day,
                    restingHr = if (a.rhrCount > 0) round(a.rhrSum.toDouble() / a.rhrCount).toInt() else null,
                    hrv = if (a.hrvCount > 0) round1(a.hrvSum / a.hrvCount) else null,
                    sleepMin = if (a.hasSleep) round1(a.sleepMin) else null,
                    spo2 = if (a.spo2Count > 0) round1(a.spo2Sum / a.spo2Count) else null,
                    respRate = if (a.respCount > 0) round1(a.respSum / a.respCount) else null,
                    exerciseCount = if (a.exerciseCount > 0) a.exerciseCount else null,
                )?.let { dailyRows.add(it) }
            } else if (spo2FillAllowed && a.spo2Count > 0) {
                spo2Fills.add(day to round1(a.spo2Sum / a.spo2Count))
            }
        }

        // Persist. Register the devices we write under so name() lookups resolve.
        try {
            if (appleRows.isNotEmpty()) {
                repo.upsertDevice(HC_DEVICE, name = "Health Connect")
                repo.upsertAppleDaily(appleRows)
            }
            if (metricSeriesRows.isNotEmpty()) repo.upsertMetricSeries(metricSeriesRows)
            if (dailyRows.isNotEmpty()) {
                repo.upsertDevice(HC_DEVICE, name = "Health Connect")
                repo.upsertDailyMetrics(dailyRows)
            }
            // Write the collected sleep sessions under WHOOP, but only for days the strap does not
            // already cover (same guard as the daily rows), so a real strap night is never shadowed.
            val sleepRows = hcSleepSessions.filter { it.first !in coveredDays }.map { it.second }
            if (sleepRows.isNotEmpty()) {
                repo.upsertDevice(WHOOP, name = "WHOOP")
                repo.upsertSleepSessions(sleepRows)
            }
            if (workouts.isNotEmpty()) {
                repo.upsertWorkouts(workouts)
            }
            // Banked under HC's own source: the row it inserts is a gap-fill candidate, so it reaches a
            // strap day only where nothing above it carries blood oxygen. Writing it under "my-whoop"
            // inserted an imported-bucket row that OUTRANKED a strap SpO2 held under "<strapId>-noop".
            for ((day, pct) in spo2Fills) repo.fillMissingSpo2(HC_DEVICE, day, pct)
        } catch (e: Exception) {
            return ImportSummary.failure(SOURCE, "Saving Health Connect data failed: ${e.message}")
        }

        // After the save, so a failed import never moves the profile's weight or height.
        if (newestWeightKg != null || newestHeightCm != null) {
            onBodyMeasurements(newestWeightKg, newestHeightCm)
        }

        val counts = buildMap {
            if (appleRows.isNotEmpty()) put("appleDaily", appleRows.size)
            if (dailyRows.isNotEmpty()) put("dailyMetric", dailyRows.size)
            if (workouts.isNotEmpty()) put("workout", workouts.size)
        }

        // Day range across everything we touched (aggregates + workout start days).
        val touchedDays = sortedSetOf<String>().apply {
            addAll(appleRows.map { it.day })
            addAll(dailyRows.map { it.day })
            addAll(workouts.mapNotNull { workoutDayByStartTs[it.startTs] })
        }
        val firstDay = touchedDays.firstOrNull()
        val lastDay = touchedDays.lastOrNull()

        val total = counts.values.sum()
        return ImportSummary(
            source = SOURCE,
            counts = counts,
            firstDay = firstDay,
            lastDay = lastDay,
            message = if (total == 0) "Nothing new to import from Health Connect."
            else "Imported $total rows from Health Connect.",
        )
    }

    /**
     * Live top-up of today's Health Connect step total, since [import] freezes it until the next
     * manual run. Reads StepsRecord since today's start (bucketed like [import]) and updates the
     * "health-connect" [AppleDaily] row via copy — a fresh @Upsert row would null every other column.
     */
    suspend fun refreshTodaySteps(context: Context, repo: WhoopRepository): Int? {
        if (sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return null
        val client = client(context)
        val granted = try {
            client.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            return null
        }
        if (HealthPermission.getReadPermission(StepsRecord::class) !in granted) return null

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayKey = today.toString()
        // Per-source sums so the live top-up de-overlaps a phone+watch pair exactly like [import] (sum
        // within a source, max across sources), instead of a ~2x inflated cross-source count.
        val stepsBySource = HashMap<String, Long>()
        val selfPackage = context.packageName // Skip our own writes (see readAll / isSelfWritten)
        // readAll swallows a failed read (the map stays empty), so a flaky provider degrades to the stored
        // row below rather than clobbering it with zero.
        readAll(
            client, StepsRecord::class,
            TimeRangeFilter.between(today.atStartOfDay(zone).toInstant(), Instant.now()),
            selfPackage,
        ) { r ->
            // The filter matches by overlap — drop records that STARTED yesterday so the bucketing
            // agrees with [import]'s dayOf, which keys off the record's own offset.
            if (LocalDate.ofInstant(r.startTime, r.startZoneOffset ?: zone) == today) {
                val src = r.metadata.dataOrigin.packageName
                stepsBySource[src] = (stepsBySource[src] ?: 0L) + r.count
            }
        }
        val sum = maxSourceLong(stepsBySource) // De-overlap: max source, not cross-source sum

        val existing = try {
            repo.appleDaily(HC_DEVICE, dayKey, dayKey).firstOrNull()
        } catch (e: Exception) {
            null
        }
        // Zero is indistinguishable from "no data yet today" — never overwrite a stored count with it.
        if (sum <= 0L) return existing?.steps

        val updated = existing?.copy(steps = sum.toInt())
            ?: AppleDaily(deviceId = HC_DEVICE, day = dayKey, steps = sum.toInt())
        try {
            if (existing == null) repo.upsertDevice(HC_DEVICE, name = "Health Connect")
            repo.upsertAppleDaily(listOf(updated))
        } catch (e: Exception) {
            return existing?.steps
        }
        return sum.toInt()
    }

    // MARK: - paginated read helper

    /**
     * Read every page of [type] within [filter], invoking [onRecord] for each record.
     * Loops on the response page token so we never miss records past the first page.
     */
    private suspend fun <T : Record> readAll(
        client: HealthConnectClient,
        type: KClass<T>,
        filter: TimeRangeFilter,
        selfPackage: String = "",
        onRecord: (T) -> Unit,
    ) {
        var pageToken: String? = null
        try {
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(
                        recordType = type,
                        timeRangeFilter = filter,
                        pageSize = PAGE_SIZE,
                        pageToken = pageToken,
                    )
                )
                for (record in response.records) {
                    // Never re-ingest what NOOP itself wrote to Health Connect, or "share back" + import
                    // would double-count our own daily totals (steps / active energy / sleep).
                    if (isSelfWritten(record.metadata.dataOrigin.packageName, selfPackage)) continue
                    onRecord(record)
                }
                pageToken = response.pageToken
            } while (pageToken != null)
        } catch (e: Exception) {
            // One record type failing (e.g. a device/SDK validation quirk like "count must not be less
            // than 1" seen on some Health Connect builds) must not abort the whole import — log it and
            // keep whatever was read; a partial type is simply absent, never corrupt.
            android.util.Log.w("HealthConnect", "read of ${type.simpleName} failed; skipping: ${e.message}")
        }
    }

    /**
     * Whether the active strap's blood oxygen may be filled from Health Connect. An unreachable
     * registry or no active strap is treated as not allowed, so the fill needs a strap it recognises.
     */
    private suspend fun spo2FillAllowed(context: Context): Boolean {
        val app = context.applicationContext as? NoopApplication ?: return false
        val model = runCatching {
            val active = app.deviceRegistry.activeDeviceId()
            app.deviceRegistry.all().firstOrNull { it.id == active }?.model
        }.getOrNull()
        return Spo2Policy.trusted(model)
    }

    // MARK: - strap-coverage helpers

    /**
     * The set of "YYYY-MM-DD" days the strap already covers under [deviceId], read defensively:
     * a missing/empty source (the normal case for raw "my-whoop" on a strap-only user) yields an
     * empty set rather than throwing. The caller unions every strap-native source.
     */
    private suspend fun strapDays(repo: WhoopRepository, deviceId: String): Set<String> =
        try {
            coveredDaySet(repo.days(deviceId))
        } catch (e: Exception) {
            emptySet()
        }

    /**
     * Every source id whose daily rows count as strap coverage: discovered strap-native ids in the
     * daily cache, always unioned with the canonical [WHOOP] / [WHOOP_COMPUTED] pair so a failed
     * discovery still falls back to that pair. Read defensively — a DB error must not fail the import.
     */
    private suspend fun strapSourceIds(repo: WhoopRepository): Set<String> =
        try {
            repo.dailyMetricDeviceIds().filterTo(hashSetOf(WHOOP, WHOOP_COMPUTED)) {
                isStrapNativeSourceId(it)
            }
        } catch (e: Exception) {
            setOf(WHOOP, WHOOP_COMPUTED)
        }

    /**
     * True when [id] is a strap-native daily-metric source: the canonical raw "my-whoop", any
     * on-device computed "-noop" source, or an actively paired strap's raw "whoop-<mac>" id — the
     * sources the HC backfill must never shadow. Importer-owned sources are not strap coverage.
     */
    internal fun isStrapNativeSourceId(id: String): Boolean {
        val s = id.lowercase()
        return s == WHOOP || s.endsWith("-noop") || s.startsWith("whoop-")
    }

    /**
     * The local day a record belongs to, keyed from the record's OWN zone offset so a record banked
     * under a different offset (a DST change, travel) keeps the day it was recorded on. [fallback] is
     * the phone's current zone, used only when the record carries no offset.
     */
    internal fun localDay(instant: Instant, offset: ZoneOffset?, fallback: ZoneId): String =
        LocalDate.ofInstant(instant, offset ?: fallback).toString()

    /**
     * Pure mapper: the distinct local days carried by [rows]. Factored out so the skip-set semantics
     * — a strap-only user is covered by their computed rows — can be unit-tested without Room.
     * Unioning raw + computed is what stops the HC backfill from shadowing an already-covered day.
     */
    internal fun coveredDaySet(rows: List<DailyMetric>): Set<String> =
        rows.mapTo(HashSet()) { it.day }

    // MARK: - field mapping helpers

    /** Sum of asleep-stage durations (minutes). Excludes AWAKE / OUT_OF_BED / UNKNOWN. */
    private fun asleepMinutes(r: SleepSessionRecord): Double {
        if (r.stages.isEmpty()) return 0.0
        var min = 0.0
        for (stage in r.stages) {
            if (stage.stage in ASLEEP_STAGES) {
                min += (stage.endTime.epochSecond - stage.startTime.epochSecond) / 60.0
            }
        }
        return min
    }

    /** Build the `[{stage,min},...]` stagesJSON (same shape as the WHOOP CSV / Xiaomi importers) from
     *  per-stage segments. Returns null when the session has no sub-stage breakdown (e.g. a generic
     *  STAGE_TYPE_SLEEPING-only record), so the night rides on its total minutes alone. */
    private fun hcStagesJson(r: SleepSessionRecord): String? {
        if (r.stages.isEmpty()) return null
        var light = 0.0; var deep = 0.0; var rem = 0.0; var awake = 0.0
        for (s in r.stages) {
            val min = (s.endTime.epochSecond - s.startTime.epochSecond) / 60.0
            when (s.stage) {
                SleepSessionRecord.STAGE_TYPE_LIGHT -> light += min
                SleepSessionRecord.STAGE_TYPE_DEEP -> deep += min
                SleepSessionRecord.STAGE_TYPE_REM -> rem += min
                SleepSessionRecord.STAGE_TYPE_AWAKE -> awake += min
                else -> {}   // SLEEPING (generic) / UNKNOWN: counted in totalSleepMin, no sub-stage split
            }
        }
        if (light == 0.0 && deep == 0.0 && rem == 0.0 && awake == 0.0) return null
        val arr = org.json.JSONArray()
        fun seg(stage: String, min: Double) {
            if (min > 0.0) arr.put(org.json.JSONObject().put("stage", stage).put("min", min))
        }
        seg("light", light); seg("deep", deep); seg("rem", rem); seg("awake", awake)
        return if (arr.length() == 0) null else arr.toString()
    }

    /** SleepSessionRecord stage ints that count as "asleep". */
    private val ASLEEP_STAGES: Set<Int> = setOf(
        SleepSessionRecord.STAGE_TYPE_LIGHT,
        SleepSessionRecord.STAGE_TYPE_DEEP,
        SleepSessionRecord.STAGE_TYPE_REM,
        SleepSessionRecord.STAGE_TYPE_SLEEPING, // generic "asleep" with no sub-stage
    )

    /**
     * True when a record's origin is NOOP itself, so [readAll] skips it — otherwise "share back" would
     * re-read our own writes and double-count steps / active energy / sleep on the next import. HC's
     * origin filter is include-only, so this check is done in code. Empty [selfPackage] never skips.
     */
    internal fun isSelfWritten(originPackage: String, selfPackage: String): Boolean =
        selfPackage.isNotEmpty() && originPackage == selfPackage

    /**
     * De-overlap for a per-source step map: SUM is folded WITHIN each source by the read lambda, so the
     * day total is the MAX across sources (a phone and a watch reporting the same walk must not double-
     * count). Empty map -> 0. Matches the Android XML importer's de-overlap.
     */
    internal fun maxSourceLong(bySource: Map<String, Long>): Long = bySource.values.maxOrNull() ?: 0L

    /** De-overlap for a per-source calorie map (Double twin of [maxSourceLong]); empty -> 0.0. */
    internal fun maxSourceDouble(bySource: Map<String, Double>): Double = bySource.values.maxOrNull() ?: 0.0

    /**
     * BMI stored for a day: [FitnessAgeEngine.bmi] applied to the day's weight + profile height
     * (Health Connect carries no BMI record). Returns null — so no "bmi" point is written — when
     * there's no weight that day or heightCm <= 0, so a missing height never fabricates a value.
     */
    internal fun derivedBmi(weightKg: Double?, heightCm: Double): Double? {
        if (heightCm <= 0.0) return null
        val w = weightKg ?: return null
        return round2(FitnessAgeEngine.bmi(w, heightCm))
    }

    /**
     * Derive basal kcal = total - active when both are present and positive; else null.
     * Takes the already de-overlapped per-day totals, not the raw [DayAcc], so basal is computed
     * from the same source-deduplicated totals the row writes for active.
     */
    private fun basalKcal(totalKcal: Double, activeKcal: Double): Double? {
        if (totalKcal <= 0.0) return null
        val basal = totalKcal - activeKcal
        return if (basal > 0.0) round1(basal) else null
    }

    /** One energy record: its source, its window, and the kilocalories it carries. */
    internal data class KcalRecord(val source: String, val startS: Long, val endS: Long, val kcal: Double)

    /**
     * Kilocalories a source's records place inside `[startS, endS)`, prorated by overlap, summed WITHIN
     * each source and maxed ACROSS them. Summing across sources double-counts a phone and a watch that
     * both logged the same session; the day totals already de-overlap this way.
     */
    internal fun kcalInWindow(records: List<KcalRecord>, startS: Long, endS: Long): Double {
        if (endS <= startS) return 0.0
        val bySource = HashMap<String, Double>()
        for (r in records) {
            val overlap = minOf(endS, r.endS) - maxOf(startS, r.startS)
            if (overlap <= 0L) continue
            val recLen = (r.endS - r.startS).coerceAtLeast(1L)
            bySource[r.source] = (bySource[r.source] ?: 0.0) + r.kcal * (overlap.toDouble() / recLen)
        }
        return bySource.values.maxOrNull() ?: 0.0
    }

    /**
     * A session's energy, or null when neither stream covers it — never a fabricated number. Every
     * active record overlapping `[startS, endS]` is credited in proportion to its overlap, so neither
     * a per-minute nor a day-spanning record mis-credits.
     *
     * Prefers whichever estimate is LARGER. The active-record figure is right when a source writes one
     * record per session, and far too small when the only cover is a coarse daily record: prorating
     * assumes calories burn uniformly, so a hard hour inside a 24-hour record reads as 1/24 of the day.
     * The total-derived figure is the window's share of total burn minus its share of the day's basal,
     * and prorating BASAL is sound in a way prorating a workout's active burn is not — basal really is
     * near-uniform. Taking the larger lets real session cover win without a threshold.
     */
    internal fun sessionKcal(
        active: List<KcalRecord>,
        total: List<KcalRecord>,
        startS: Long,
        endS: Long,
        dayBasalKcal: Double?,
        daySeconds: Long = 86_400L,
    ): Double? {
        if (endS <= startS) return null
        val fromActive = kcalInWindow(active, startS, endS)
        val windowTotal = kcalInWindow(total, startS, endS)
        val fromTotal = if (windowTotal <= 0.0 || dayBasalKcal == null) 0.0 else {
            val share = (endS - startS).toDouble() / daySeconds.toDouble()
            windowTotal - dayBasalKcal * share
        }
        return maxOf(fromActive, fromTotal).takeIf { it > 0.0 }
    }

    /**
     * A short, human sport name for a Health Connect exercise session. Uses the user's title
     * if present, else maps the EXERCISE_TYPE_* int to a readable label, else "Workout".
     */
    private fun exerciseName(r: ExerciseSessionRecord): String {
        val title = r.title?.trim()
        if (!title.isNullOrEmpty()) return title
        return EXERCISE_TYPE_NAMES[r.exerciseType] ?: "Workout"
    }

    /**
     * Map of ExerciseSessionRecord.EXERCISE_TYPE_* constants to readable labels. References the library
     * constants directly (not hardcoded ints), so a renamed/removed constant is a compile error rather
     * than a silent int-mismatch. Unknown types fall back to "Workout".
     */
    private val EXERCISE_TYPE_NAMES: Map<Int, String> = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Running",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "Running",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Cycling",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "Cycling",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Swimming",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "Swimming",
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Strength",
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Walking",
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Hiking",
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING to "Rowing",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "Rowing",
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "Elliptical",
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "Pilates",
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING to "Boxing",
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "Badminton",
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to "Baseball",
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "Basketball",
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "Soccer",
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "Weightlifting",
        // Additional racket / team / misc sport types.
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to "Volleyball",
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "Tennis",
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to "Table Tennis",
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to "Squash",
        ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL to "Racquetball",
        ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to "Handball",
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY to "Ice Hockey",
        ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY to "Roller Hockey",
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN to "Football",
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to "Football",
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to "Rugby",
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to "Cricket",
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL to "Softball",
        ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO to "Water Polo",
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "Golf",
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "Dancing",
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to "Martial Arts",
        ExerciseSessionRecord.EXERCISE_TYPE_FENCING to "Fencing",
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to "Gymnastics",
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "Calisthenics",
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "Stretching",
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to "Exercise Class",
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to "Boot Camp",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "Stair Climbing",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "Stair Climbing",
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to "Climbing",
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING to "Skiing",
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to "Snowboarding",
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING to "Snowshoeing",
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING to "Skating",
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING to "Skating",
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING to "Surfing",
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to "Paddling",
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING to "Sailing",
        ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING to "Diving",
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC to "Frisbee",
    )

    private fun round1(x: Double) = round(x * 10.0) / 10.0
    private fun round2(x: Double) = round(x * 100.0) / 100.0

    /**
     * One day's Health Connect daily row, or null when the day carries none of the six values HC
     * supplies. Banked under [HC_DEVICE] rather than the strap's bucket, so WhoopRepository's gap-fill
     * merge ranks it below every strap source instead of letting a phone aggregate outrank a measured
     * vital. The six named here are the whole write set the repair's provenance test relies on.
     */
    internal fun hcDailyRow(
        day: String,
        restingHr: Int?,
        hrv: Double?,
        sleepMin: Double?,
        spo2: Double?,
        respRate: Double?,
        exerciseCount: Int?,
    ): DailyMetric? {
        if (restingHr == null && hrv == null && sleepMin == null &&
            spo2 == null && respRate == null && exerciseCount == null
        ) {
            return null
        }
        return DailyMetric(
            deviceId = HC_DEVICE,
            day = day,
            totalSleepMin = sleepMin,
            restingHr = restingHr,
            avgHrv = hrv,
            spo2Pct = spo2,
            respRateBpm = respRate,
            exerciseCount = exerciseCount,
        )
    }

    /** Per-local-day accumulator. */
    private class DayAcc {
        // Per-SOURCE sums (keyed by dataOrigin.packageName), reduced by MAX across sources at write-out
        // so a phone+watch pair reporting the same steps/calories doesn't double-count. Matches the
        // Android XML importer's de-overlap (sum within a source, max across sources).
        val stepsBySource = HashMap<String, Long>()
        val totalKcalBySource = HashMap<String, Double>()
        val activeKcalBySource = HashMap<String, Double>()

        var hrSum: Long = 0L
        var hrCount: Int = 0

        var rhrSum: Long = 0L
        var rhrCount: Int = 0

        var hrvSum: Double = 0.0
        var hrvCount: Int = 0

        var sleepMin: Double = 0.0
        var hasSleep: Boolean = false

        var spo2Sum: Double = 0.0
        var spo2Count: Int = 0

        var respSum: Double = 0.0
        var respCount: Int = 0

        var vo2max: Double? = null
        var vo2maxTs: Long = Long.MIN_VALUE

        var weightKg: Double? = null
        var weightTs: Long = Long.MIN_VALUE

        var bodyFatPct: Double? = null
        var bodyFatTs: Long = Long.MIN_VALUE
        var leanMassKg: Double? = null
        var leanMassTs: Long = Long.MIN_VALUE

        var exerciseCount: Int = 0

        // Blood pressure: the day's LAST reading wins, the way a cuff app shows the latest.
        var systolic: Double? = null
        var diastolic: Double? = null
        var bpTs: Long = Long.MIN_VALUE

        // Hydration and nutrition are day TOTALS: every entry adds up.
        var periodStart: Boolean = false

        var hydrationMl: Double = 0.0
        var kcalIn: Double = 0.0
        var proteinG: Double = 0.0
        var carbsG: Double = 0.0
        var fatG: Double = 0.0
    }
}

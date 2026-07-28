package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.MetricSeriesRow
import com.noop.data.SleepSession
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import com.noop.protocol.DeviceFamily
import com.noop.protocol.Whoop4SkinTemp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/*
 * Scores recovery, day strain and sleep from the raw strap streams, so a day the strap collected is
 * scored here rather than read from an import.
 *
 * Each recent day with at least MIN_HR_SAMPLES reads a window of raw streams from its owning source,
 * runs AnalyticsEngine.analyzeDay against baselines folded from that source's history, and persists a
 * DailyMetric plus sleep sessions under "<deviceId>-noop". WhoopRepository merges those UNDER any
 * imported row, so an import always wins and this only fills the days it does not cover.
 *
 * Stateless: it computes and persists, and the repository's day flow refreshes the UI. Every `ts` is
 * unix seconds.
 */
object IntelligenceEngine {

    /**
     * Serialises [analyzeRecent] against itself: four independent coroutines launch it, and two parallel
     * passes race the overlap heal, whose concurrent deletes can pick different survivors. Queues rather
     * than drops, because the callers pass different windows and dropping one would lose it silently.
     */
    private val analyzeGate = Mutex()

    /**
     * Per-day owner resolution source (invariant I2: a day's scores come from exactly ONE device).
     * A null source (the default) preserves the legacy single-source path byte-for-byte: every day
     * reads from [importedDeviceId]. A DeviceRegistry-backed implementation is passed in by the UI.
     */
    interface DayOwnerSource {
        /** Non-archived paired devices, each as a [DayOwnerResolver.Candidate] WITHOUT its hasData flag
         *  resolved yet (priority only: 0 = active strap, 1 = other live straps, 2 = imports). */
        suspend fun candidatePriorities(): List<Pair<String, Int>>

        /** A locked owner override for [day] from the dayOwnership table, or null. Wins outright. */
        suspend fun lockedOwner(day: String): String?

        /** The registry's currently-active strap id (CAPTURE-B universal `writeActiveId`). Defaults to
         *  null so legacy/test sources are unaffected; [RegistryDayOwnerSource] supplies the real active
         *  id so the universal dayOwner diagnostic can name where new data is being written vs read. */
        suspend fun activeWriteId(): String? = null

        /** The strap family that wrote [deviceId]'s rows, so the nightly skin-temp funnel converts the raw
         *  register on the right scale (5/MG centidegrees vs a WHOOP 4.0 v24 raw ADC). Defaults to WHOOP5;
         *  [RegistryDayOwnerSource] resolves a positively-identified 4.0. */
        suspend fun skinTempFamily(deviceId: String): DeviceFamily = DeviceFamily.WHOOP5
    }

    /** Minimum HR samples in a day's window before it is worth scoring. */
    const val MIN_HR_SAMPLES: Int = 200

    /** Read cap per stream read: 200_000 samples. */
    const val STREAM_LIMIT: Int = 200_000

    private const val SECONDS_PER_DAY: Long = 86_400L

    /** Imported wearable-export source ids whose daily aggregates can score a NOOP Charge/Rest for an
     *  import-only day. */
    private val WEARABLE_IMPORT_SOURCES = listOf("oura-import", "fitbit-import", "garmin-import")

    /** CAPTURE-B: a day's resolved read owner + the HR-row count read for it, captured in pass 1 and
     *  consumed by pass 2's universal dayOwner emit. */
    private data class OwnerRead(val owner: String, val hrRows: Int)

    /** Summary of one scored day (for logging / a future on-device intelligence screen). */
    data class Computed(
        val day: String,
        val recovery: Double?,
        val strain: Double?,
        val sleepMin: Double?,
        val hrv: Double?,
        val rhr: Int?,
    )

    /**
     * Score each of the last [maxDays] that carries raw HR and persist it under "<importedDeviceId>-noop".
     * Baselines fold from that source's nightly history, so even a first live night scores against the
     * user's own norm. Hops to [Dispatchers.Default]: 21 nights of 1 Hz data would ANR the main thread.
     *
     * @param maxHROverride explicit HRmax in bpm; null derives it from [profile].
     * @param nowSeconds wall-clock now, injectable so a test is deterministic.
     * @return the per-day summaries, newest first.
     */
    suspend fun analyzeRecent(
        repo: WhoopRepository,
        profile: UserProfile = UserProfile(),
        maxDays: Int = 21,
        importedDeviceId: String = "my-whoop",
        maxHROverride: Double? = null,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
        ownerSource: DayOwnerSource? = null,
        // The user's persisted step-coefficient override (null or 0 = auto-fit), and the sink the fitted
        // model is handed back through so the caller can mirror it into the profile store.
        manualStepCoefficient: Double? = null,
        persistStepsCalibration: (StepsEstimateEngine.Calibration) -> Unit = {},
        // Manual HRV recalibration anchor in epoch seconds, 0 = none. Read by the caller, since this
        // layer holds no Context; every night before it drops out of the fold.
        baselineEpoch: Double = 0.0,
        // The same anchor for the rest of Charge: resting HR, respiration and skin temperature.
        recoveryEpoch: Double = 0.0,
        // Per-day scoring diagnostic sink. Each scored day emits ONE privacy-safe line ("sleep day=…
        // totalSleepMin=… matched=… source=…"). Defaults to no-op; the AppViewModel wires it to the BLE
        // client's strap log, which PII-scrubs every line at the sink.
        diag: (String) -> Unit = {},
        // Test-mode trace sinks; null means no lines and no change to any score. See the public
        // overload for the contract.
        sleepTraceSink: ((String) -> Unit)? = null,
        recoveryTraceSink: ((String) -> Unit)? = null,
        stepsTraceSink: ((String) -> Unit)? = null,
        universalSink: ((String) -> Unit)? = null,
        workoutsTraceSink: ((String) -> Unit)? = null,
        hrvTraceSink: ((String) -> Unit)? = null,
    ): List<Computed> = withContext(Dispatchers.Default) {
        // Serialise the pass; see [analyzeGate]. Held only across this engine's own work.
        analyzeGate.withLock {
            val (out, healed) = analyzeRecentOnCpu(repo, profile, maxDays, importedDeviceId, maxHROverride,
                nowSeconds, ownerSource, manualStepCoefficient, persistStepsCalibration, baselineEpoch,
                recoveryEpoch, diag, sleepTraceSink, recoveryTraceSink,
                stepsTraceSink, universalSink, workoutsTraceSink, hrvTraceSink)
            if (healed == 0) out
            // The pass deleted duplicate sessions after scoring against them, so re-score once against the
            // cleaned store. The re-pass finds nothing left to heal, so it cannot loop.
            else analyzeRecentOnCpu(repo, profile, maxDays, importedDeviceId, maxHROverride,
                nowSeconds, ownerSource, manualStepCoefficient, persistStepsCalibration, baselineEpoch,
                recoveryEpoch, diag, sleepTraceSink, recoveryTraceSink,
                stepsTraceSink, universalSink, workoutsTraceSink, hrvTraceSink).first
        }
    }

    /** History span for the one-shot Effort rescore: large enough to cover any real wear history. */
    const val EFFORT_RESCORE_HISTORY_DAYS: Int = 4000

    /**
     * One-shot, on-upgrade full-history Effort rescore: recomputes strain FROM SOURCE for every day with
     * raw HR so pre-migration 0-21-axis rows land on NOOP's 0-100 axis (never a blind `strain*100/21`,
     * which would double-rescale rows already on 0-100). Gated via [flagGet]/[flagSet] to run once.
     */
    suspend fun runEffortRescoreIfNeeded(
        repo: WhoopRepository,
        profile: UserProfile = UserProfile(),
        importedDeviceId: String = "my-whoop",
        maxHROverride: Double? = null,
        flagGet: () -> Boolean,
        flagSet: () -> Unit,
        historyDays: Int = EFFORT_RESCORE_HISTORY_DAYS,
    ) {
        if (flagGet()) return
        analyzeRecent(
            repo = repo,
            profile = profile,
            maxDays = historyDays,
            importedDeviceId = importedDeviceId,
            maxHROverride = maxHROverride,
        )
        flagSet()
    }

    /**
     * Scores a day that arrived as a daily aggregate with no raw HR behind it (Health Connect, or an
     * Apple/Oura/Fitbit/Garmin export): appends to [dailies]/[restRows]/[out]. An export carrying its own
     * recovery wins and is never overwritten; returns null (calibrating) until the baseline is usable.
     */
    private suspend fun foldSourceOnlyDays(
        repo: WhoopRepository,
        importedDeviceId: String,
        computedId: String,
        oldestDay: String,
        newestDay: String,
        dailies: MutableList<DailyMetric>,
        restRows: MutableList<MetricSeriesRow>,
        out: MutableList<Computed>,
    ) {
        val importScoredDays = HashSet<String>().apply { addAll(dailies.map { it.day }) }
        val importSourceIds = buildList {
            add(importedDeviceId) // Health Connect imports its DailyMetric rows under the strap source.
            add(WhoopRepository.APPLE_HEALTH_SOURCE)
            add(WhoopRepository.HEALTH_CONNECT_SOURCE)
            addAll(WEARABLE_IMPORT_SOURCES)
        }.distinct()
        for (source in importSourceIds) {
            val rows = repo.dailyMetrics(source, oldestDay, newestDay)
            // A real export that already carries its OWN recovery WINS , never overwrite a verbatim imported
            // score; those days also pre-claim the slot so the fold doesn't re-score them.
            val byDay = rows.associateBy { it.day }
            for (r in rows) if (r.recovery != null) importScoredDays.add(r.day)
            for (w in watchRecoveries(rows, importScoredDays)) {
                val recovery = w.recovery ?: continue
                val row = byDay[w.day] ?: continue
                val scored = row.copy(deviceId = computedId, recovery = recovery)
                dailies.add(scored)
                importScoredDays.add(w.day)
                RestScorer.restFromDaily(scored)?.let { rest ->
                    restRows.add(MetricSeriesRow(deviceId = computedId, day = w.day, key = "sleep_performance", value = rest))
                }
                out.add(
                    Computed(
                        day = w.day,
                        recovery = recovery,
                        strain = scored.strain,
                        sleepMin = scored.totalSleepMin,
                        hrv = scored.avgHrv,
                        rhr = scored.restingHr,
                    ),
                )
            }
        }
    }

    /** The three baseline states a night is scored against, seeded before any pass-1 value folds in. */
    private data class SeededBaselines(val hrv: BaselineState, val rhr: BaselineState, val resp: BaselineState)

    /**
     * Seed the HRV, resting-HR and respiration baselines from history alone, so a night is never scored
     * against a baseline it contributed to. Pass-1 values fold in incrementally afterwards.
     */
    private fun seedBaselines(
        hist: List<DailyMetric>,
        nightlyHrvByDay: Map<String, Double?>,
        nightlyRhrByDay: Map<String, Double?>,
        nightlyRespByDay: Map<String, Double?>,
        hrvCfg: MetricCfg,
        rhrCfg: MetricCfg,
        respCfg: MetricCfg,
        baselineEpoch: Double,
        recoveryEpoch: Double,
    ): SeededBaselines {
        val histHrvByDay = LinkedHashMap<String, Double?>()
        val histRhrByDay = LinkedHashMap<String, Double?>()
        val histRespByDay = LinkedHashMap<String, Double?>()
        for (d in hist) {
            histHrvByDay[d.day] = d.avgHrv
            histRhrByDay[d.day] = d.restingHr?.toDouble()
            histRespByDay[d.day] = d.respRateBpm
        }
        // Imported (cloud) nightly values WIN per day: the on-device estimate only fills days the import
        // doesn't cover at all. Uses a key-absence check, NOT putIfAbsent, since Java's putIfAbsent treats
        // a NULL-mapped key as absent and would replace a blank imported HRV/RHR (~60%/~20% of recovery weight).
        mergeNightlyIntoHistory(histHrvByDay, nightlyHrvByDay)
        mergeNightlyIntoHistory(histRhrByDay, nightlyRhrByDay)
        mergeNightlyIntoHistory(histRespByDay, nightlyRespByDay)
        // Seed baseline state from IMPORTED history only (state BEFORE any pass-1 night). Pass-1 nightly
        // values fold in INCREMENTALLY in the pass-2 loop below: each night scores against strictly prior
        // state, then folds in for the next — so a day never contributes to its own baseline.
        val hrvImported = histHrvByDay.entries.sortedBy { it.key }.map { it.value }
        val hrvImportedKeys = histHrvByDay.entries.sortedBy { it.key }.map { it.key }
        val rhrImported = histRhrByDay.entries.sortedBy { it.key }.map { it.value }
        val rhrImportedKeys = histRhrByDay.entries.sortedBy { it.key }.map { it.key }
        val respImported = histRespByDay.entries.sortedBy { it.key }.map { it.value }
        val respImportedKeys = histRespByDay.entries.sortedBy { it.key }.map { it.key }
        var hrvState = Baselines.foldHistory(hrvImported, hrvImportedKeys, hrvCfg, baselineEpoch)
        var rhrState = Baselines.foldHistory(rhrImported, rhrImportedKeys, rhrCfg, recoveryEpoch)
        var respState = Baselines.foldHistory(respImported, respImportedKeys, respCfg, recoveryEpoch)
        return SeededBaselines(hrvState, rhrState, respState)
    }

    /**
     * The whole-window effort baseline the recovery Activity-Balance term scores against: imported
     * strain, with each scored night filling a day the import does not cover. Order-independent.
     */
    private fun effortBaselineOver(strainByDay: Map<String, Double?>): BaselineState? =
        Baselines.foldHistory(
            strainByDay.entries.sortedBy { it.key }.map { it.value }, Baselines.strainCfg,
        ).takeIf { it.usable }

    /**
     * Persist one per-session series for every KEPT session, taking the last value a scored night gave
     * for a start. A session the producer omitted has no key and stays NULL: an absent signal stays
     * absent rather than becoming a fabricated empty array.
     */
    private suspend fun <T> perKeptSession(
        scoredNights: List<DayResult>,
        keptStarts: Set<Long>,
        select: (DayResult) -> Map<Long, List<T>>,
        persist: suspend (Long, List<T>) -> Unit,
    ) {
        val byStart = LinkedHashMap<Long, List<T>>()
        for (res in scoredNights) {
            for ((start, series) in select(res)) if (start in keptStarts) byStart[start] = series
        }
        for ((start, series) in byStart) persist(start, series)
    }

    /**
     * Collapses re-banked duplicates of the same night (dedup key (deviceId, startTs)), deleting the
     * stale copies within the reconcile window and returning the count. Deletes the row ONLY — never a
     * dismissal tombstone, which would suppress re-detection for good — and never touches edited rows.
     */
    private suspend fun healOverlappingSessions(
        repo: WhoopRepository,
        computedId: String,
        windowStart: Long,
        nowSeconds: Long,
        tzOffsetSeconds: Long,
        oldestDay: String,
        newestDay: String,
        keptStarts: Set<Long>,
        diag: (String) -> Unit,
    ): Int {
        val healable = repo.sleepSessions(computedId, windowStart, nowSeconds, 4000).filter {
            AnalyticsEngine.dayString(it.endTs, tzOffsetSeconds) in oldestDay..newestDay
        }
        val dropped = SleepSessionDedup.dedupe(healable, freshStarts = keptStarts).dropped
        for (stale in dropped) repo.deleteSleepSessionRowOnly(stale)
        if (dropped.isNotEmpty()) {
            diag(
                "Dedup(#899): removed ${dropped.size} overlapping duplicate sleep " +
                    "session(s) re-banked under a shifted strap timebase; re-scoring the affected days.",
            )
        }
        return dropped.size
    }

    private suspend fun analyzeRecentOnCpu(
        repo: WhoopRepository,
        profile: UserProfile = UserProfile(),
        maxDays: Int = 21,
        importedDeviceId: String = "my-whoop",
        maxHROverride: Double? = null,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
        ownerSource: DayOwnerSource? = null,
        manualStepCoefficient: Double? = null,
        persistStepsCalibration: (StepsEstimateEngine.Calibration) -> Unit = {},
        baselineEpoch: Double = 0.0,
        recoveryEpoch: Double = 0.0,
        diag: (String) -> Unit = {},
        // Sleep & Rest test-mode trace sink. null = byte-identical default; when non-null each scored
        // day threads it into AnalyticsEngine.analyzeDay so detectSleep's gate trace + the Rest
        // sub-score line forward line-by-line to the .sleep-tagged strap log.
        sleepTraceSink: ((String) -> Unit)? = null,
        // Recovery (Charge) test-mode trace sink. null = byte-identical default; when non-null each
        // scored night emits its Charge term-breakdown to the .recovery-tagged strap log via
        // RecoveryScorerTrace.recoveryTrace, whose score is RecoveryScorer.recovery verbatim.
        recoveryTraceSink: ((String) -> Unit)? = null,
        // Steps test-mode trace sink. null = byte-identical default; when non-null each scored day
        // emits its 5/MG raw-counter trace and (after the fit) the WHOOP-4 calibration trace to the
        // .steps-tagged strap log. The trace reuses the SAME wrap-aware sum + calibrate verbatim.
        stepsTraceSink: ((String) -> Unit)? = null,
        // CAPTURE-B universal diagnostic sink. null = byte-identical default (no lines); when non-null
        // each scored day emits the verbatim `dayOwner …` line.
        universalSink: ((String) -> Unit)? = null,
        // Workouts & GPS test-mode trace sink. null = byte-identical default (no lines); when non-null
        // each detected bout emits a `detectedBout verdict=persisted|droppedOverlap …` line to the
        // .workouts-tagged strap log, so an "auto workout appeared then vanished" is explainable.
        workoutsTraceSink: ((String) -> Unit)? = null,
        // HRV & Autonomic test-mode sink. null = byte-identical default (no lines); when non-null,
        // analyzeDay forwards the nightly per-window RMSSD (by stage) + the whole-night/deep-only/
        // last-SWS summary to the .hrv-tagged strap log.
        hrvTraceSink: ((String) -> Unit)? = null,
        // The second component is how many duplicate sleep sessions the heal deleted, so the wrapper
        // knows whether to re-score once against the cleaned store.
    ): Pair<List<Computed>, Int> {
        val hrvCfg = Baselines.metricCfg["hrv"] ?: return emptyList<Computed>() to 0
        val rhrCfg = Baselines.metricCfg["resting_hr"] ?: return emptyList<Computed>() to 0
        val skinCfg = Baselines.metricCfg["skin_temp"] ?: return emptyList<Computed>() to 0
        val respCfg = Baselines.metricCfg["resp"] ?: return emptyList<Computed>() to 0

        val computedId = importedDeviceId + "-noop"

        // Device wall-clock offset (seconds east of UTC) for the sleep detector's daytime false-sleep
        // guard: the stager places each window's center on the LOCAL clock so only genuinely-daytime
        // windows face the stricter nap bar. getOffset(nowMillis) folds in current DST; computed once per run.
        val tzOffsetSeconds =
            java.util.TimeZone.getDefault().getOffset(nowSeconds * 1_000L) / 1_000L

        // Device-registry snapshot for per-day owner resolution (invariant I2: a day's scores come from
        // exactly ONE source), read ONCE before the loop since the paired-device list is stable for the
        // run. A single-WHOOP install resolves every day to [importedDeviceId]; a null [ownerSource] skips resolution.
        val candidatePriorities = ownerSource?.candidatePriorities().orEmpty()

        // CAPTURE-B: the registry's active strap id (the universal `writeActiveId`). Resolved ONCE; falls
        // back to [importedDeviceId] so a single-WHOOP install (or a null/legacy ownerSource) names the same
        // id the read path resolves to, and the universal line proves read == write rather than diverging.
        val activeWriteId = (universalSink?.let { ownerSource?.activeWriteId() }) ?: importedDeviceId

        // ── Pass 1: detect + aggregate each offloaded night against the imported-only baseline. For a
        // BLE-only user repo.days() is empty, so recovery is null here — but avgHrv/restingHr are
        // baseline-independent, harvested to seed pass 2. Collected oldest-first (foldHistory winsorizes).
        val hist = repo.days(importedDeviceId)
        // CAPTURE-B: per-day resolved read owner + HR-row count, captured in pass 1, consumed by pass 2's
        // universal dayOwner emit (reuses the SAME importedWhoopDays/appleHealthDays sets pass 2 builds
        // for daySourceToken, so no extra read). Only populated when the universal sink is on.
        val readOwnerByDay = LinkedHashMap<String, OwnerRead>()
        // HRV baseline honours the manual "Recalibrate baseline" epoch (noop.hrvBaselineEpoch): pass the
        // per-value day keys (parallel to the values) so foldHistory drops every night before the epoch
        // (0.0 = no recalibration). rhr/resp/skin stay on the 2-arg fold — recalibration is HRV-only.
        val hrvBase1 = Baselines.foldHistory(hist.map { it.avgHrv }, hist.map { it.day }, hrvCfg, baselineEpoch)
        val rhrBase1 = Baselines.foldHistory(hist.map { it.restingHr?.toDouble() }, hist.map { it.day }, rhrCfg, recoveryEpoch)
        val baselines1 = ProfileBaselines(hrv = hrvBase1, restingHR = rhrBase1)

        // Keep each night's small DayResult (daily metrics + detected sessions), NOT the raw streams:
        // every field except recovery is baseline-independent, so pass 2 only re-scores the cheap
        // recovery composite. Raw hr/rr/... lists are freed after each analyzeDay, keeping memory bounded.
        val scoredNights = ArrayList<DayResult>()

        // In-memory nightly values harvested in pass 1, used to seed the pass-2 baseline.
        // Keyed by day so the union with imported history de-dupes cleanly per UTC day.
        val nightlyHrvByDay = LinkedHashMap<String, Double?>()
        val nightlyRhrByDay = LinkedHashMap<String, Double?>()
        // Wear-gated nightly skin-temp means (on-device only — imported rows carry the deviation, not
        // the raw mean, so the skin-temp baseline is seeded purely from these).
        val nightlySkinByDay = LinkedHashMap<String, Double?>()
        // On-device RSA respiration estimates, unioned with imported respRateBpm below to seed the
        // resp baseline the recovery composite's wResp=0.05 term scores against.
        val nightlyRespByDay = LinkedHashMap<String, Double?>()

        // Floor `now` to LOCAL midnight so each `dayStart` lands on a local-day boundary and the day keys
        // are LOCAL calendar days, consistent with the dashboard's local "today" lookup. A west-of-UTC
        // user's evening crosses midnight UTC; bucketing by UTC would put it in the wrong day.
        val nowLocalMidnight = midnightLocal(nowSeconds, tzOffsetSeconds)

        // ── Learned habitual midsleep ──
        // Computed once per run from the trailing sleep history so the main-night pick aligns to the
        // user's real bedtime, not a fixed clock band. Keeps the longest sleep block per local calendar
        // day (naps drop out); null under HABITUAL_MIN_DAYS of history falls back to the overnight bonus.
        val habitualMidsleepSec = computeHabitualMidsleep(
            repo, importedDeviceId, computedId,
            nowLocalMidnight - maxDays * SECONDS_PER_DAY - 30 * 3_600L, nowSeconds, tzOffsetSeconds,
        )

        // Skin-temp family memoised per owner: [RegistryDayOwnerSource.skinTempFamily] runs a Room query,
        // and a 21-day scan would otherwise re-read it once per day for what is almost always ONE owner.
        // The registry is stable for the run, so every day sees the same value the per-day call would give.
        val skinFamilyByOwner = HashMap<String, DeviceFamily>()
        // The WHOOP 4.0 ADC offset is per-device, not per-night. Learn one anchor per owner from the
        // whole scan window and reuse it for every night so cross-night deviations survive.
        val skinAnchorScanFrom = nowLocalMidnight - (maxDays - 1).toLong() * SECONDS_PER_DAY - 30 * 3_600L
        val skinAnchorScanTo = nowLocalMidnight + 18 * 3_600L
        val skinAnchorByOwner = HashMap<String, Double>()
        val skinAnchorResolvedOwners = HashSet<String>()

        // Personal Rest inputs from the trailing persisted history (read before the loop overwrites it):
        // one need + consistency for the whole pass, slowly-varying; sparse history -> engine defaults.
        val priorSleepHrs = repo.dailyMetrics(
            computedId,
            AnalyticsEngine.dayString(nowLocalMidnight - (maxDays - 1) * SECONDS_PER_DAY, tzOffsetSeconds),
            AnalyticsEngine.dayString(nowLocalMidnight, tzOffsetSeconds),
        ).sortedBy { it.day }.mapNotNull { it.totalSleepMin }.map { it / 60.0 }.filter { it > 0 }
        val recentSleepNeed = if (priorSleepHrs.isEmpty()) null else RustScores.personalSleepNeedHours(priorSleepHrs.takeLast(14))
        val recentConsistency = VitalityEngine.sleepConsistency(priorSleepHrs.takeLast(7))

        for (offset in 0 until maxDays) {
            val dayStart = nowLocalMidnight - offset * SECONDS_PER_DAY
            val day = AnalyticsEngine.dayString(dayStart, tzOffsetSeconds)
            // Read a generous window around the night that ends on `day`; the stager finds the span.
            val from = dayStart - 30 * 3_600L
            // Sleep read-window END. A PAST day's night may end any time before the NEXT local midnight
            // (late sleepers wake well after noon), so it reads through to the next local midnight rather
            // than a hard `dayStart + 18h` cap; TODAY keeps the 18:00 cap (DAO clamps to now anyway).
            val nextMidnight = dayStart + SECONDS_PER_DAY
            val to = if (dayStart < nowLocalMidnight) nextMidnight else dayStart + 18 * 3_600L

            // I2: pick the single device that OWNS this day, and read ITS streams below. Single-device
            // installs resolve to [importedDeviceId] (priority 0); with multiple sources the day is
            // scored from exactly one (active strap > other live straps > imports, or a locked override).
            val owner = resolveDayOwner(repo, ownerSource, candidatePriorities, day, from, to, importedDeviceId)

            val hr = repo.hrSamples(owner, from, to, STREAM_LIMIT)
            // CAPTURE-B: capture this day's resolved read owner + HR-row count so pass 2 can emit the
            // verbatim universal `dayOwner …` line per SCORED day. Only when the universal sink is on;
            // a day skipped below for too few rows is never scored, so it emits no line.
            if (universalSink != null) readOwnerByDay[day] = OwnerRead(owner, hr.size)
            if (hr.size < MIN_HR_SAMPLES) {
                diag("sleep day=$day SKIPPED hrSamples=${hr.size} (need >=$MIN_HR_SAMPLES)")
                continue // need real raw data, not a stray sample
            }
            val rr = repo.rrIntervals(owner, from, to, STREAM_LIMIT)
            val grav = repo.gravitySamples(owner, from, to, STREAM_LIMIT)
            val steps = repo.stepSamples(owner, from, to, STREAM_LIMIT)
            val skin = repo.skinTempSamples(owner, from, to, STREAM_LIMIT)
            // WHOOP 4.0 raw SpO2 PPG samples for the night; analyzeDay banks the nightly red/IR ADC means
            // on the DailyMetric. Empty on a 5/MG (no v24 spo2 channels) → the raw means stay null.
            val spo2 = repo.spo2Samples(owner, from, to, STREAM_LIMIT)
            // WHOOP 5.0/MG sleep SpO2 percent (v18 @frame-82); analyzeDay banks the nightly MEDIAN on
            // DailyMetric.spo2Pct. Empty on a WHOOP 4.0 (which banks raw red/IR instead) → spo2Pct stays null.
            val spo2Pct = repo.spo2PctSamples(owner, from, to, STREAM_LIMIT)
            // The strap family that WROTE this owner's skin-temp rows, so analyzeDay converts the raw
            // register on the right scale (5/MG banks centidegrees, a WHOOP 4.0 v24 banks a raw ADC).
            // Unknown/non-WHOOP owners fall back to WHOOP5; resolved once per DISTINCT owner (see above).
            val skinFamily = skinFamilyByOwner.getOrPut(owner) {
                ownerSource?.skinTempFamily(owner) ?: DeviceFamily.WHOOP5
            }
            // Learn this device's worn skin-temp anchor once, window-wide: the @72 ADC register offset is
            // per-device (a real 4.0's ~1100-1600 band fails the global 826 anchor's 28-42°C wear gate).
            // Null for a non-4.0 owner or <100 in-band samples; a per-night re-centre would erase the signal.
            val skinAnchorRaw = if (skinFamily == DeviceFamily.WHOOP4) {
                if (!skinAnchorResolvedOwners.contains(owner)) {
                    val windowSkin = repo.skinTempSamples(owner, skinAnchorScanFrom, skinAnchorScanTo, STREAM_LIMIT)
                    Whoop4SkinTemp.deviceAnchorRaw(windowSkin.map { it.raw })?.let { skinAnchorByOwner[owner] = it }
                    skinAnchorResolvedOwners.add(owner)
                }
                skinAnchorByOwner[owner]
            } else {
                null
            }
            // Wrist-wear events paired into off-wrist [start,end) intervals for the off-wrist sleep
            // backstop: a session drops only once off-wrist coverage reaches maxOffWristSleepFraction, so
            // a short off-wrist tail survives. Unclosed spans close at `to`; empty when no wrist events.
            val wristOff = AnalyticsEngine.offWristIntervals(repo.events(owner, from, to, STREAM_LIMIT), to)

            // Calendar-day window for the additive daily totals (steps + calories): the night window
            // above ends at dayStart+12h and would undercount a past day's late hours, so this reads
            // [localMidnight(day), +86400) instead, feeding dayHr/daySteps; MIN_HR_SAMPLES stays gated on the night window.
            val dayMidnight = midnightLocal(dayStart, tzOffsetSeconds)
            val dayEnd = dayMidnight + SECONDS_PER_DAY - 1
            // Same [owner] as the night window above (I2): the additive day totals must come from the one
            // device that owns the day, never a mix.
            val dayHr = repo.hrSamples(owner, dayMidnight, dayEnd, STREAM_LIMIT)
            val daySteps = repo.stepSamples(owner, dayMidnight, dayEnd, STREAM_LIMIT)
            // Full calendar-day gravity for workout detection. The night window above ends at
            // dayStart+12h (~noon), so this reads [localMidnight, +24h) instead (clamped to now for
            // today) so the detector sees the whole day, including an afternoon/evening workout.
            val dayGrav = repo.gravitySamples(owner, dayMidnight, dayEnd, STREAM_LIMIT)

            // The strap's own band sleep_state for the night window as (ts, state) samples, read from
            // [owner] so the H7 morning-stillness guard confirms against the real offload and analyzeDay
            // grids it per session. Empty (no band stream, or a legacy DB) falls back to prior persisted state or the HR bar.
            var bandSleepState = repo.sleepStateSamples(owner, from, to).map { it.ts to it.state }
            if (bandSleepState.isEmpty()) {
                bandSleepState = bandSleepStateSamples(repo, computedId, from, to)
            }

            val res = AnalyticsEngine.analyzeDay(
                day = day,
                hr = hr,
                rr = rr,
                gravity = grav,
                steps = steps,
                dayHr = dayHr,
                daySteps = daySteps,
                dayGravity = dayGrav,
                skinTemp = skin,
                skinTempFamily = skinFamily,
                skinTempAnchorRaw = skinAnchorRaw,   // per-device worn anchor
                spo2 = spo2,
                spo2Pct = spo2Pct,             // 5/MG v18 sleep SpO2 %
                profile = profile,
                baselines = baselines1,
                maxHROverride = maxHROverride,
                tzOffsetSeconds = tzOffsetSeconds,
                wristOff = wristOff,
                habitualMidsleepSec = habitualMidsleepSec,
                bandSleepState = bandSleepState,
                // Personal Rest inputs, persisted on the day so restFromDaily recomputes the same score.
                sleepNeedHours = recentSleepNeed,
                sleepConsistency = recentConsistency,
                // Sleep & Rest test mode (Test Centre E5): thread the trace sink straight through. null (the
                // default) keeps analyzeDay's byte-identical untraced path; when the caller passed a non-null
                // sink (mode on), detectSleep's gate trace + the Rest sub-score line route to the .sleep-tagged
                // strap log. The sink is already the routing closure, so there is no per-day collect/replay.
                traceSink = sleepTraceSink,
                hrvTraceSink = hrvTraceSink,
                // Per-window HRV detail ONLY for the most-recent night (dayStart == today's local midnight),
                // so the 5000-line ring buffer isn't flooded; every night still emits the 1-line summary.
                hrvWindowDetail = dayStart == nowLocalMidnight,
            )

            // Whole-night HRV cleaning-pipeline summary to the strap log: RMSSD vs SDNN (rmssd >> sdnn
            // means beat-to-beat jitter, not real HRV), meanNN as an HR sanity check, and the R-R survival
            // count (nInput counts before the min-beats gate). A separate analyzeRaw pass; never touches avgHrv.
            val sleepRrRows = rr.filter { r -> res.sleepSessions.any { r.ts >= it.start && r.ts < it.end } }
            val sleepRr = sleepRrRows.map { it.rrMs.toDouble() }
            if (sleepRr.isNotEmpty()) {
                val h = RustScores.analyzeRaw(sleepRr)
                val ms = { v: Double? -> v?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "nil" }
                val rej = if (h.nInput > 0) String.format(java.util.Locale.US, "%.0f", 100.0 * (1.0 - h.nClean.toDouble() / h.nInput)) else "0"
                // Coverage is beat-time over elapsed time; above 1.0 is physically impossible. Two causes
                // are reported beside it because they are distinguishable: dupBeats counts byte-identical
                // (ts, rrMs) re-inserts, overlap counts reports re-covering seconds with different values.
                val ts = sleepRrRows.map { it.ts }
                val cov = String.format(java.util.Locale.US, "%.2f", RustScores.rrCoverage(ts, sleepRr))
                val dup = RustScores.duplicateBeatCount(ts, sleepRr)
                val (over, reports) = RustScores.overlappingReports(sleepRrRows)
                diag("hrv diag day=${res.daily.day} rmssd=${ms(h.rmssd)}ms sdnn=${ms(h.sdnn)}ms meanNN=${ms(h.meanNN)}ms " +
                    "rr=${h.nInput}/${h.nClean} rejected=$rej% coverage=$cov dupBeats=$dup overlap=$over/$reports")
            }

            // Steps test mode: emits the 5/MG raw-counter trace (cumulative @57 series + wrap-aware
            // deltas), tagged .steps, only when the sink is set; recomputes the same sum analyzeDay ran.
            // Guarded on daySteps non-empty: a WHOOP 4.0 has no raw step counter, so it uses the motion-estimate trace instead.
            if (stepsTraceSink != null && daySteps.isNotEmpty()) {
                for (line in StepsEstimateEngineTrace.rawCounterTrace(
                    daySteps = daySteps, dayKey = day, tzOffsetSeconds = tzOffsetSeconds,
                    ticksPerStep = profile.stepTicksPerStep,
                )) {
                    stepsTraceSink(line)
                }
            }

            // Harvest the baseline-independent nightly aggregates (a day with no detected
            // sleep yields null → recorded as a missing night, i.e. skip-and-hold). The raw
            // streams (hr/rr/...) go out of scope here and are freed before the next night.
            nightlyHrvByDay[day] = res.daily.avgHrv
            nightlyRhrByDay[day] = res.daily.restingHr?.toDouble()
            nightlySkinByDay[day] = res.nightlySkinTempC
            nightlyRespByDay[day] = res.daily.respRateBpm
            // ── RHR floor-vs-mean diagnostic ──
            // NOOP's restingHr is the WHOOP-style floor (min 5-min rolling-mean HR per session, day takes
            // the min across sessions); a "sleeping HR" app reports the night mean, which always sits
            // at-or-above the floor — logging both explains why NOOP reads lower. Counts/bpm only, no PII.
            val rhrFloor = res.daily.restingHr
            if (rhrFloor != null) {
                val inBedBpms = hr.filter { s -> res.sleepSessions.any { s.ts >= it.start && s.ts < it.end } }
                    .map { it.bpm }
                diag(rhrFloorMeanLogLine(day, rhrFloor, inBedBpms))
            }
            scoredNights.add(res)
        }

        // ── Seed the baseline: union of imported history + the nightly values just computed ──
        // Folds the in-memory pass-1 values (not a re-read of repo.days) to avoid a read-before-persist
        // hazard: a BLE-only user crosses Baselines.minNightsSeed (4 valid nights) so recovery lights up.
        // Chronological (oldest-first) replay; a day present in both takes the computed value.
        val seeded = seedBaselines(
            hist, nightlyHrvByDay, nightlyRhrByDay, nightlyRespByDay,
            hrvCfg, rhrCfg, respCfg, baselineEpoch, recoveryEpoch,
        )
        var hrvState = seeded.hrv
        var rhrState = seeded.rhr
        var respState = seeded.resp
        // Skin-temp baseline is on-device-only; seed from nothing (imported rows carry skinTempDevC, not raw mean).
        var skinState: BaselineState? = null
        // Daily-strain series for the recovery Activity-Balance term, keyed by day: imported strain, each
        // scored night's computed strain filling a day the import doesn't cover. Read twice below: the
        // whole-window effort baseline, and a prior-calendar-day lookup in the scoring loop.
        val strainByDay = LinkedHashMap<String, Double?>()
        for (d in hist) strainByDay[d.day] = d.strain
        for (res in scoredNights) if (res.daily.day !in strainByDay) strainByDay[res.daily.day] = res.daily.strain
        val effortBaseline = effortBaselineOver(strainByDay)
        fun currentBaselines() = ProfileBaselines(
            hrv = hrvState, restingHR = rhrState,
            resp = respState.takeIf { it.usable },
            skinTemp = skinState?.takeIf { it.usable },
            effort = effortBaseline,
        )

        // Real (non-detected) workouts in the scored window, used to de-duplicate detected bouts so a
        // user with both real sessions and a worn strap doesn't see the same session twice (mergeDaily's
        // per-day precedence doesn't cover the workout table). A detected bout overlapping any of these is skipped below.
        val windowStart = nowSeconds - maxDays.toLong() * SECONDS_PER_DAY - 30 * 3_600L
        val realWorkouts = repo.workouts(importedDeviceId, windowStart, nowSeconds) +
            repo.workouts("apple-health", windowStart, nowSeconds) +
            repo.workouts("health-connect", windowStart, nowSeconds)

        // ── Pass 2: re-score every night against the now-seeded baseline ──
        // Only the recovery composite is recomputed (cheap, baseline-dependent); every other field was
        // already computed in pass 1 (baseline-independent), so the heavy sleep/strain/workout/RSA
        // analysis runs once per night. Recovery stays null until the HRV baseline is usable (honest cold-start).
        val out = ArrayList<Computed>()
        val dailies = ArrayList<DailyMetric>()
        val sleepRows = ArrayList<SleepSession>()
        val workoutRows = ArrayList<WorkoutRow>()
        // Rest composite (0-100) per night, persisted as the sleep_performance metric series so the
        // dashboard Rest score reflects the composite, not raw efficiency.
        val restRows = ArrayList<MetricSeriesRow>()

        // User-corrected sleep windows for the COMPUTED source, overriding detected sleep so Rest and
        // recovery honor the edit, and gating the sleepRows upsert below against re-insertion. Scoped to
        // COMPUTED ("-noop") only: an edited IMPORTED night's recovery/performance still come from the export verbatim, keyed by the immutable detected `startTs`.
        //
        // Self-heals any night edited before its raw streams synced ([SleepStageHealer.selfHealEditedStages]):
        // re-derives stages from the now-available raw over the night's locked bounds, rewriting the stage
        // breakdown only (bounds untouched). Idempotent; must run before `editsByStart` so healed stages feed Rest/recovery this pass.
        val editedRows = SleepStageHealer.selfHealEditedStages(
            repo = repo,
            computedDeviceId = computedId,
            strapDeviceId = importedDeviceId,
            windowStart = windowStart,
            windowEnd = nowSeconds,
        )
        // [editsByStart] / [editOnsetByStart] are built per day inside the scoring loop, scoped to the day
        // each edit belongs to. sleepEditedDaily folds any edited row that isn't a twin of this day's
        // detected sessions in as a "manual" block, so a single edit can't pin totalSleepMin to a constant across every night.

        // Provenance sets for the per-day diagnostic source token: a day present in `hist` means a WHOOP
        // export covers it and wins the dashboard merge (mergeDaily: imports win field-by-field); Apple
        // Health rows are the same for that brand, with WHOOP winning ties. Key-presence sets only — no values leave.
        val importedWhoopDays = hist.map { it.day }.toHashSet()
        val appleHealthDays = repo
            .appleDaily(WhoopRepository.APPLE_HEALTH_SOURCE, "0000-01-01", "9999-12-31")
            .map { it.day }.toHashSet()

        for (res in scoredNights) {
            // Scope the edits to this day before folding: a userEdited row belongs to the day its night
            // ends on (endTs is stable under a bedtime edit, only the onset moves), so filtering here keeps
            // a single-night edit from overriding every night. editOnsetByStart still carries the corrected bedtime for this day's blocks.
            val dayEditedRows = editedRowsForDay(editedRows, res.daily.day, tzOffsetSeconds)
            val editsByStart: Map<Long, String?> = dayEditedRows.associate { it.startTs to it.stagesJSON }
            val editOnsetByStart: Map<Long, Long> = dayEditedRows.associate { it.startTs to it.effectiveStartTs }
            // Substitute an edited block's (reshaped) stages for its detected twin before the daily
            // sleep aggregate feeds Rest + recovery. No edit touching this night → `daily` is unchanged.
            val daily = sleepEditedDaily(
                res.daily, res.sleepSessions, editsByStart, editOnsetByStart,
                tzOffsetSeconds, habitualMidsleepSec,
            )
            // Skin-temp deviation must be attached before Charge scoring, or the 5%-weighted skin-temp
            // term never enters the formula.
            val bl = currentBaselines()
            val skinTempDevC = recomputeSkinTempDev(res.nightlySkinTempC, bl.skinTemp)
            val dailyWithSkin = if (skinTempDevC != null) daily.copy(skinTempDevC = skinTempDevC) else daily
            // The prior CALENDAR day's Effort for THIS night's Activity-Balance term, looked up directly
            // (order-independent), persisted on the row so the driver breakdown reads the same input. Absent
            // prior day (outside the window / unscored) -> null -> the term honestly drops.
            val prevDayKey = runCatching { java.time.LocalDate.parse(daily.day).minusDays(1).toString() }.getOrNull()
            val priorEffort = prevDayKey?.let { strainByDay[it] }
            val recovery = recomputeRecovery(dailyWithSkin, bl, priorEffort)
            if (recoveryTraceSink != null) {
                for (line in recoveryTraceLines(dailyWithSkin, bl, priorEffort)) recoveryTraceSink(line)
            }
            // Fold this night's values into the running baseline so the NEXT night scores against
            // strictly prior state (chronological, not shared).
            res.daily.avgHrv?.let { hrvState = Baselines.update(hrvState, it, hrvCfg) }
            res.daily.restingHr?.let { rhrState = Baselines.update(rhrState, it.toDouble(), rhrCfg) }
            res.daily.respRateBpm?.let { respState = Baselines.update(respState, it, respCfg) }
            res.nightlySkinTempC?.let { skinState = Baselines.update(skinState, it, skinCfg) }
            RestScorer.restFromDaily(daily)?.let { rest ->
                restRows.add(MetricSeriesRow(deviceId = computedId, day = daily.day, key = "sleep_performance", value = rest))
            }

            out.add(
                Computed(
                    day = daily.day,
                    recovery = recovery,
                    strain = daily.strain,
                    sleepMin = daily.totalSleepMin,
                    hrv = daily.avgHrv,
                    rhr = daily.restingHr,
                ),
            )
            // ── Per-day scoring diagnostic ──
            // One privacy-safe line per scored day to the strap log: day key, final total-sleep minutes
            // (after any edit), how many sleep blocks matched, and the dashboard headline's provenance.
            // Counts and a rounded minute only — no HR/HRV/timestamps.
            val tsmLog = daily.totalSleepMin?.let { Math.round(it).toString() } ?: "nil"
            // The banked stage split + efficiency ride beside the rollup so totalSleepMin vs the
            // deep+rem+light sum — the identity two screens must agree on — is verifiable per day from
            // the export alone. Rounded minutes only; stages=nil when the day has no banked stage split.
            val effLog = daily.efficiency?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "nil"
            diag(
                "sleep day=${daily.day} totalSleepMin=$tsmLog " +
                    "stages=${sleepStagesLogToken(daily.deepMin, daily.remMin, daily.lightMin)} " +
                    "eff=$effLog " +
                    "matched=${res.sleepSessions.size} " +
                    "source=${daySourceToken(daily.day, importedWhoopDays, appleHealthDays)}",
            )
            // One line per scored night with the computed HRV value. `avgHrv=nil` when the night has no
            // detected deep sleep, so the deep window forms no bucket. Rounded ms only, PII-free.
            val hrvLog = daily.avgHrv?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "nil"
            diag("hrv day=${daily.day} window=deep avgHrv=$hrvLog")
            // ── CAPTURE-B: universal dayOwner self-diagnostic ──
            // One line per scored day, tagged .universal, regardless of which test mode is on: readId is
            // the owner the day was read+scored from, writeActiveId is the registry's active id — a
            // divergence on a day with HR rows is the symptom. Only emitted when the universal sink is set.
            if (universalSink != null) {
                val read = readOwnerByDay[daily.day]
                universalSink(
                    dayOwnerLine(
                        day = daily.day,
                        readId = read?.owner ?: activeWriteId,
                        writeActiveId = activeWriteId,
                        hrRows = read?.hrRows ?: 0,
                        importedWhoopDays = importedWhoopDays,
                        appleHealthDays = appleHealthDays,
                    ),
                )
            }
            // Stamp the computed source id + the re-scored recovery & skin-temp deviation onto the row,
            // plus the prior-day Effort used to score it (recoveryIndexSlope already rides `daily`).
            dailies.add(daily.copy(deviceId = computedId, recovery = recovery, skinTempDevC = skinTempDevC, priorDayEffort = priorEffort))
            // Map the rich DetectedSleep sessions → Room SleepSession cache rows.
            for (s in res.sleepSessions) {
                sleepRows.add(
                    SleepSession(
                        deviceId = computedId,
                        startTs = s.start,
                        endTs = s.end,
                        efficiency = s.efficiency,
                        restingHr = s.restingHR,
                        avgHrv = s.avgHRV,
                        stagesJSON = AnalyticsEngine.encodeStages(s.stages),
                    ),
                )
            }
            // Persist the detected workouts the pipeline computes. Skip any bout overlapping a real
            // imported/manual workout so import+wear users don't double-count. sport="detected";
            // energyKcal is the approximate Keytel/BMR total.
            for (s in res.workouts) {
                val durMin = maxOf(0L, (s.end - s.start) / 60L).toInt()
                val avgBpm = s.avgHR.toInt()
                // Bare time overlap (any source), so a detected bout collapses against a manual session
                // even though their sports differ. Name the collider for the trace below.
                val collider = realWorkouts.firstOrNull { w -> s.start < w.endTs && w.startTs < s.end }
                if (collider != null) {
                    // The detected bout's avgHR/calories/maxHR/strain come from the same motion+HR trace
                    // used to find the activity's real boundaries, often tighter than a manual row's typed
                    // window. Backfills only the fields the colliding row lacks, keyed the same.
                    val backfilled = backfillWorkoutFromDetectedBout(
                        collider, avgBpm = avgBpm, peakHR = s.peakHR, caloriesKcal = s.caloriesKcal, strain = s.strain,
                    )
                    val didBackfill = backfilled != collider
                    if (didBackfill) workoutRows.add(backfilled)
                    workoutsTraceSink?.invoke(
                        WorkoutsTrace.detectedBoutLine(
                            verdict = if (didBackfill) "droppedOverlapBackfilled" else "droppedOverlap",
                            durMin = durMin, avgBpm = avgBpm,
                            overlapSource = colliderSourceLabel(collider.source),
                        ),
                    )
                    continue
                }
                workoutRows.add(
                    WorkoutRow(
                        deviceId = computedId,
                        startTs = s.start,
                        endTs = s.end,
                        sport = "detected",
                        source = computedId,
                        durationS = s.durationS,
                        energyKcal = s.caloriesKcal,
                        avgHr = avgBpm,
                        maxHr = s.peakHR,
                        strain = s.strain,
                    ),
                )
                workoutsTraceSink?.invoke(
                    WorkoutsTrace.detectedBoutLine(verdict = "persisted", durMin = durMin, avgBpm = avgBpm),
                )
            }
        }

        // The loop keys days by the LOCAL calendar day. Deletes the COMPUTED ("-noop") daily rows across
        // the recompute window before re-upserting the local-keyed rows, so no stale UTC-keyed row can
        // coexist as a duplicate day. Scoped to the computed source only; imported rows are never touched.
        val oldestDay = AnalyticsEngine.dayString(
            nowLocalMidnight - (maxDays - 1) * SECONDS_PER_DAY, tzOffsetSeconds,
        )
        val newestDay = AnalyticsEngine.dayString(nowLocalMidnight, tzOffsetSeconds)

        foldSourceOnlyDays(repo, importedDeviceId, computedId, oldestDay, newestDay, dailies, restRows, out)

        // Snapshot the persisted/merged daily history before the delete+re-upsert below rewrites the
        // computed window, so the Fitness Age gate (below) isn't undercut by this pass's own pruning: a
        // recompute only re-scores nights whose raw HR still lives in the store. Windowed to stay bounded.
        val faPriorDaily = repo.daysMerged(importedDeviceId).filter { it.day in oldestDay..newestDay }

        repo.deleteComputedDailyInRange(computedId, oldestDay, newestDay)

        // Persist the computed scores under the "-noop" source so the whole dashboard reads them. The
        // repository merges these under any imported "my-whoop" rows, so a real WHOOP import always
        // wins; this only fills the days the strap collected but no import covered.
        if (dailies.isNotEmpty()) repo.upsertDailyMetrics(dailies)
        if (restRows.isNotEmpty()) repo.upsertMetricSeries(restRows)

        // Trailing phases, both reading the scores just persisted above.
        writeWeeklyAges(repo, profile, dailies, faPriorDaily, computedId, importedDeviceId,
            ownerSource, candidatePriorities, newestDay, nowLocalMidnight, tzOffsetSeconds, diag)
        writeStepsEstimate(repo, dailies, computedId, importedDeviceId, ownerSource,
            candidatePriorities, nowLocalMidnight, tzOffsetSeconds, newestDay,
            manualStepCoefficient, persistStepsCalibration, stepsTraceSink)
        // Durability guard: drop any freshly-detected session that time-overlaps a night the user has
        // hand-corrected. A detected onset drifts as more raw data arrives, so without this a re-detected
        // night would upsert as a second row beside the edited one and double-count time-in-bed. Overlap uses the edit's effective window.
        val editedWindows = editedRows.map { it.effectiveStartTs to it.endTs }
        // Also drops any re-detected night the user has deleted: a dismissedSleep tombstone keeps it
        // from regenerating. Reads the union of imported + computed ids, so a tombstone under either
        // namespace is found; overlap (not exact startTs) since a re-detected onset drifts.
        val dismissedWindows = repo.dismissedSleeps(importedDeviceId).map { it.startTs to it.endTs }
        val skipWindows = editedWindows + dismissedWindows
        val sleepKept = DismissedSleepGuard.keeping(sleepRows, skipWindows) { it.startTs to it.endTs }
        if (sleepKept.isNotEmpty()) repo.upsertSleepSessions(sleepKept)
        // ── Persist per-epoch motion (H8) beside each kept session's stagesJSON ──
        // Only for sessions actually kept (not edited/dismissed), keyed by the detected start analyzeDay
        // returned. A session whose gravity wouldn't grid is left NULL — absent stays absent, never a
        // fabricated zero array.
        val keptStarts = sleepKept.map { it.startTs }.toHashSet()
        perKeptSession(scoredNights, keptStarts, { it.sessionMotionByStart }) { start, motion ->
            repo.persistSessionMotion(computedId, start, motion)
        }
        // ── Persist per-epoch band sleep_state beside each kept session's stagesJSON ──
        // analyzeDay grids the raw `sleepStateSample` stream per session; persisting it here lets the
        // next pass's H7 confirm see the strap's own scored band. Only for kept sessions; a session with
        // no band samples stays NULL — absent stays absent.
        perKeptSession(scoredNights, keptStarts, { it.sessionSleepStateByStart }) { start, states ->
            repo.persistSessionSleepState(computedId, start, states)
        }
        // ── Overlap-aware banked-sleep heal ──
        // An unstable strap clock re-banks the same night under a shifted timebase, producing a second
        // stale row beside the fresh one. Collapses the window's stored sessions with the overlap rule
        // (this pass's freshly-banked rows are the recency witness) and deletes the stale copies; edited rows are never dropped.
        val healDroppedCount = healOverlappingSessions(
            repo, computedId, windowStart, nowSeconds, tzOffsetSeconds, oldestDay, newestDay, keptStarts, diag,
        )
        // Make re-detection idempotent across runs: clear the prior computed detected workouts
        // in the scored window (a bout's startTs can drift as more HR arrives, which would
        // otherwise orphan stale rows under the (deviceId,startTs,sport) key), then re-insert.
        repo.deleteComputedWorkouts(computedId, "detected", windowStart, nowSeconds)
        if (workoutRows.isNotEmpty()) repo.upsertWorkouts(workoutRows)

        // A manually-started workout is scored from sparse live HR at save time, near-zero
        // calories/strain on a 5/MG. Once offloaded HR covers the window, re-score the
        // under-sampled ones from that denser data.
        rescoreManualWorkouts(repo, profile, importedDeviceId, maxHROverride, nowSeconds)

        return out to healDroppedCount
    }


    /** Fitness Age, Vitality / Body Age and Rhythm Age — weekly, keyed to the week's Saturday. */
    private suspend fun writeWeeklyAges(
        repo: WhoopRepository,
        profile: UserProfile,
        dailies: List<DailyMetric>,
        faPriorDaily: List<DailyMetric>,
        computedId: String,
        importedDeviceId: String,
        ownerSource: DayOwnerSource?,
        candidatePriorities: List<Pair<String, Int>>,
        newestDay: String,
        nowLocalMidnight: Long,
        tzOffsetSeconds: Long,
        diag: (String) -> Unit,
    ) {
        // ── Fitness Age (Phase 2) , weekly, keyed to the week's Saturday ──
        val fa7 = dailies.sortedBy { it.day }.takeLast(7)
        val faRHRs = fa7.mapNotNull { it.restingHr }.map { it.toDouble() }
        // Gates and computes Fitness Age on the union of the pre-rewrite persisted history and this
        // pass's fresh scores (fresh wins by day), so an RHR night counts whether it's in the store or
        // just scored. Kept separate from `fa7` (used by Vitality below, untouched); the gate lives in [fitnessAgeRows] so the manual refresh button applies the same rule.
        val faGateByDay = LinkedHashMap<String, DailyMetric>()
        for (d in faPriorDaily) faGateByDay[d.day] = d
        for (d in dailies) faGateByDay[d.day] = d
        val faGate7 = faGateByDay.values.sortedBy { it.day }.takeLast(7)
        val faPts = fitnessAgeRows(faGate7, profile, computedId, saturdayKeyOnOrBefore(newestDay))
        // Strap-log proof: the RHR-night count the engine sees for the gate , should equal the "N of last 7
        // nights" the readiness card shows; `computed` says whether the value was (re)written this pass.
        diag("fitnessAge gate day=$newestDay rhrNights=${faGate7.mapNotNull { it.restingHr }.size} activityDays=${faGate7.mapNotNull { it.strain }.size} computed=${faPts.isNotEmpty()}")
        if (faPts.isNotEmpty()) repo.upsertMetricSeries(faPts)

        // ── Vitality / Body Age (Phase 7) , weekly, keyed to the week's Saturday ──
        // Roll the last 7 days' wearable signals into the mortality-hazard model; VitalityEngine gates on
        // ≥3 inputs. VO₂max is omitted (fitness is Fitness Age's headline); Vitality leans on resting HR,
        // sleep duration + regularity, HRV-vs-age-norm, and steps.
        val vNights = fa7.mapNotNull { it.totalSleepMin }.map { it / 60.0 }.filter { it > 0 }
        val vHRVs = fa7.mapNotNull { it.avgHrv }
        val vSteps = fa7.mapNotNull { it.steps }.map { it.toDouble() }
        // Sleep regularity is the timing of sleep, not its length: scores the Sleep Regularity Index over
        // the trailing week's staged sleep, with HR-reporting windows as the wear mask so a removed strap
        // reads as unknown rather than wakefulness. Falls back to a duration proxy (1 - CV of nightly hours) when coverage is too thin.
        val sriWindowDays = 8
        val sriStart = midnightLocal(nowLocalMidnight - (sriWindowDays - 1) * SECONDS_PER_DAY, tzOffsetSeconds)
        val sriIndex: Double? = runCatching {
            val asleep = ArrayList<Pair<Long, Long>>()
            val covered = ArrayList<Pair<Long, Long>>()
            for (off in 0 until sriWindowDays) {
                val dayMid = midnightLocal(nowLocalMidnight - off * SECONDS_PER_DAY, tzOffsetSeconds)
                val dayEnd = dayMid + SECONDS_PER_DAY - 1
                val dayKey = AnalyticsEngine.dayString(dayMid, tzOffsetSeconds)
                val owner = resolveDayOwner(repo, ownerSource, candidatePriorities, dayKey, dayMid, dayEnd, importedDeviceId)
                repo.sleepSessions(owner, dayMid - SECONDS_PER_DAY, dayEnd, STREAM_LIMIT)
                    .forEach { asleep += it.effectiveStartTs to it.endTs }
                // Continuity, not the day's min..max: a min..max mask would count mid-day gaps as worn,
                // and for this index worn-but-unmeasured reads as awake.
                val hrTs = repo.hrSamples(owner, dayMid, dayEnd, STREAM_LIMIT).map { it.ts }
                covered += RustScores.coverageSpans(hrTs)
            }
            RustScores.sleepRegularityIndex(sriStart, sriWindowDays, asleep.distinct(), covered)
        }.getOrNull()

        val vInputs = VitalityEngine.Inputs(
            chronoAge = profile.age,
            restingHR = if (faRHRs.isEmpty()) null else medianOfDoubles(faRHRs),
            sleepHours = if (vNights.isEmpty()) null else vNights.average(),
            sleepRegularityIndex = sriIndex,
            sleepConsistency = if (sriIndex != null) null else VitalityEngine.sleepConsistency(vNights),
            rmssd = if (vHRVs.isEmpty()) null else medianOfDoubles(vHRVs),
            rmssdNorm = VitalityEngine.rmssdNorm(profile.age),
            steps = if (vSteps.isEmpty()) null else vSteps.average())
        VitalityEngine.compute(vInputs)?.let { vRes ->
            val satKey = saturdayKeyOnOrBefore(newestDay)
            repo.upsertMetricSeries(listOf(
                MetricSeriesRow(deviceId = computedId, day = satKey, key = "vitality", value = vRes.vitality),
                MetricSeriesRow(deviceId = computedId, day = satKey, key = "body_age", value = vRes.bodyAge)))
        }

        // Circadian Rhythm Age, weekly, keyed to the week's Saturday: pool the trailing 14 days' per-hour
        // on-chip motion (gravitySample.dynAccelG) into the rest-activity cosinor + biological-age transform.
        // Owner-resolved per day; needs >= 7 worn days of motion, else nothing persists.
        if (profile.age > 0) {
            val rhythmSamples = ArrayList<com.noop.data.GravitySample>()
            for (off in 0 until 14) {
                val dayMid = midnightLocal(nowLocalMidnight - off * SECONDS_PER_DAY, tzOffsetSeconds)
                val dayEnd = dayMid + SECONDS_PER_DAY - 1
                val dayKey = AnalyticsEngine.dayString(dayMid, tzOffsetSeconds)
                val owner = resolveDayOwner(repo, ownerSource, candidatePriorities, dayKey, dayMid, dayEnd, importedDeviceId)
                rhythmSamples += repo.gravitySamples(owner, dayMid, dayEnd, STREAM_LIMIT)
            }
            val activitySamples = rhythmSamples.mapNotNull { s ->
                s.dynAccelG?.let { uniffi.whoop_ffi.ActivitySample(s.ts, it) }
            }
            // A day or two of data fits a spurious rhythm, so require >= 7 distinct worn days first.
            val wornDays = activitySamples.map { (it.unix + tzOffsetSeconds) / SECONDS_PER_DAY }.distinct().size
            if (wornDays >= 7) {
                val sexInput = when (profile.sex.lowercase(java.util.Locale.US)) {
                    "male" -> uniffi.whoop_ffi.SexInput.MALE
                    "female" -> uniffi.whoop_ffi.SexInput.FEMALE
                    else -> uniffi.whoop_ffi.SexInput.UNKNOWN
                }
                RustScores.rhythmAge(activitySamples, tzOffsetSeconds, profile.age, sexInput)?.let { ra ->
                    repo.upsertMetricSeries(listOf(MetricSeriesRow(deviceId = computedId,
                        day = saturdayKeyOnOrBefore(newestDay), key = "rhythm_age", value = ra.cosinorAgeYears)))
                }
            }
        }
    }

    /** The WHOOP 4.0 daily step estimate: fit the motion-volume coefficient, then apply it. */
    private suspend fun writeStepsEstimate(
        repo: WhoopRepository,
        dailies: List<DailyMetric>,
        computedId: String,
        importedDeviceId: String,
        ownerSource: DayOwnerSource?,
        candidatePriorities: List<Pair<String, Int>>,
        nowLocalMidnight: Long,
        tzOffsetSeconds: Long,
        newestDay: String,
        manualStepCoefficient: Double?,
        persistStepsCalibration: (StepsEstimateEngine.Calibration) -> Unit,
        stepsTraceSink: ((String) -> Unit)?,
    ) {
        // ── Steps estimate (WHOOP 4.0), daily, keyed to each strap-only day ──
        // A WHOOP 4.0 sends no step count over BLE: calibrates the strap's daily motion volume against
        // the phone's real step count on days both exist (StepsEstimateEngine), then applies that
        // coefficient to strap-only days. Idempotent (re-upserts "steps_est"); inert until calibrated.
        val stepsCalDays = 60
        val calOldest = AnalyticsEngine.dayString(
            nowLocalMidnight - (stepsCalDays - 1) * SECONDS_PER_DAY, tzOffsetSeconds)
        // Phone reference steps per day, from the apple-health daily rows (steps > 0 only). The
        // Apple-Health importer banks `steps` in AppleDaily, not DailyMetric (sleep/HR/HRV only), so
        // this reads appleDaily here — reading dailyMetrics instead leaves the reference always empty.
        val appleRows = repo.appleDaily(WhoopRepository.APPLE_HEALTH_SOURCE, calOldest, newestDay)
        val refStepsByDay = HashMap<String, Double>()
        for (r in appleRows) { val s = r.steps; if (s != null && s > 0) refStepsByDay[r.day] = s.toDouble() }
        // Health Connect steps (imported under "health-connect", also in appleDaily) are a phone
        // reference too; unioned in so HC-only users get a step calibration. Apple-health wins on a
        // same-day overlap (fills only days Apple didn't already supply).
        val hcStepRows = repo.appleDaily(WhoopRepository.HEALTH_CONNECT_SOURCE, calOldest, newestDay)
        for (r in hcStepRows) {
            val s = r.steps
            if (s != null && s > 0 && !refStepsByDay.containsKey(r.day)) refStepsByDay[r.day] = s.toDouble()
        }
        // Per-day motion volume over the calibration window, read from the owner-resolved strap streams.
        // (Owner resolution mirrors the scoring loop; a single-device install resolves to importedDeviceId.)
        val motionByDay = HashMap<String, Double>()
        for (off in 0 until stepsCalDays) {
            val dayMid = midnightLocal(nowLocalMidnight - off * SECONDS_PER_DAY, tzOffsetSeconds)
            val dayEnd = dayMid + SECONDS_PER_DAY - 1
            val dayKey = AnalyticsEngine.dayString(dayMid, tzOffsetSeconds)
            val owner = resolveDayOwner(repo, ownerSource, candidatePriorities, dayKey, dayMid, dayEnd, importedDeviceId)
            val grav = repo.gravitySamples(owner, dayMid, dayEnd, STREAM_LIMIT)
            val m = StepsEstimateEngine.dayMotionIntensity(grav)
            if (m > 0) motionByDay[dayKey] = m
        }
        // Build calibration points only for days with BOTH a motion volume and a real phone step count.
        val calPoints = motionByDay.mapNotNull { (day, motion) ->
            refStepsByDay[day]?.let { StepsEstimateEngine.CalibrationPoint(motion = motion, steps = it) }
        }
        val stepsCal = StepsEstimateEngine.calibrate(calPoints, manualOverride = manualStepCoefficient)
        if (stepsCal != null) {
            // Estimate + upsert for each recent scored day that has motion but NO real phone step count.
            val estRows = ArrayList<MetricSeriesRow>()
            for (dm in dailies) {
                if (refStepsByDay.containsKey(dm.day)) continue
                val motion = motionByDay[dm.day] ?: continue
                val est = StepsEstimateEngine.estimate(motion, stepsCal) ?: continue
                estRows.add(MetricSeriesRow(deviceId = computedId, day = dm.day, key = "steps_est", value = est.toDouble()))
            }
            if (estRows.isNotEmpty()) repo.upsertMetricSeries(estRows)
            // Hand the fit back so the caller mirrors it into ProfileStore for the Settings/Steps screen.
            persistStepsCalibration(stepsCal)
        }
        // Steps test mode: emits the WHOOP-4 motion-volume calibration trace (per-day points + the
        // fitted/manual/withheld state) and a per-day estimate line, tagged .steps, only when the sink is
        // set. Reuses StepsEstimateEngine.calibrate/estimate verbatim, so it can't diverge from steps_est.
        if (stepsTraceSink != null) {
            for (line in StepsEstimateEngineTrace.calibrationTrace(calPoints, manualStepCoefficient)) {
                stepsTraceSink(line)
            }
            if (stepsCal != null) {
                for (dm in dailies) {
                    if (refStepsByDay.containsKey(dm.day)) continue
                    val motion = motionByDay[dm.day] ?: continue
                    val est = StepsEstimateEngine.estimate(motion, stepsCal) ?: continue
                    stepsTraceSink(
                        "stepsEst day=${dm.day} steps=$est " +
                            "motion=${Math.round(motion * 100.0) / 100.0} (motion-volume estimate)",
                    )
                }
            }
        }
    }

    /**
     * The source-only label for a detected-bout overlap collider in the workouts trace, computed
     * without reaching into the UI-layer WorkoutEditing (analytics must not depend on com.noop.ui).
     * No PII — a source class only.
     */
    private fun colliderSourceLabel(source: String): String {
        val s = source.lowercase()
        return when {
            s.endsWith("-noop") -> "detected"
            s == "manual" -> "manual"
            s == "lifting" -> "lifting"
            s == "activity-file" -> "activityFile"
            s == "apple-health" || s == "apple_health" || s == "health-connect" -> "apple"
            s.contains("whoop") -> "strap"
            else -> "apple"
        }
    }

    /**
     * Backfills only the avgHr/maxHr/energyKcal/strain fields [real] doesn't already have, from a
     * detected bout's own computed values; never touches a field already present. Returns [real]
     * unchanged (==) when it already had everything, so the caller can tell if a write is needed.
     */
    internal fun backfillWorkoutFromDetectedBout(
        real: WorkoutRow,
        avgBpm: Int,
        peakHR: Int,
        caloriesKcal: Double?,
        strain: Double?,
    ): WorkoutRow = real.copy(
        avgHr = real.avgHr ?: avgBpm,
        maxHr = real.maxHr ?: peakHR,
        energyKcal = real.energyKcal ?: caloriesKcal,
        strain = real.strain ?: strain,
    )

    /**
     * Re-scores under-sampled manual workouts. Conservative and idempotent: only `manual` rows that
     * look under-scored (negligible calories), and only when the recompute from denser HR is a genuine
     * improvement, so a well-scored workout is never touched. Manual + offloaded HR both live under [deviceId].
     */
    private suspend fun rescoreManualWorkouts(
        repo: WhoopRepository,
        profile: UserProfile,
        deviceId: String,
        maxHROverride: Double?,
        nowSeconds: Long,
    ) {
        val since = nowSeconds - 14L * 86_400L
        val rows = runCatching { repo.workouts(deviceId, since, nowSeconds) }.getOrNull() ?: return
        val hrMax = maxHROverride ?: (208.0 - 0.7 * profile.age)   // Tanaka, matching endWorkout
        val updated = ArrayList<WorkoutRow>()
        for (row in rows) {
            if (row.source != "manual") continue
            // Eligible when it looks under-scored (negligible kcal) or it's missing strain (the merged-
            // workout case, where kcal is the sum of inputs so it never looks under-scored yet strain
            // stays blank). improves() then accepts a strain-only gain for the latter.
            if (!ManualWorkoutRescore.looksUnderScored(row.energyKcal) && row.strain != null) continue
            val samples = runCatching { repo.hrSamples(deviceId, row.startTs, row.endTs, 20_000) }
                .getOrNull() ?: continue
            val s = ManualWorkoutRescore.scored(samples, profile, hrMax) ?: continue
            if (!ManualWorkoutRescore.improves(s, row.energyKcal, row.strain, allowStrainOnlyFill = true)) continue
            // Never lower a summed kcal: only take the recomputed kcal when it genuinely beats the stored
            // value; a strain-only fill (merged row) keeps the existing summed energyKcal.
            val kcalBeatsStored = (s.kcal ?: 0.0) > (row.energyKcal ?: 0.0) + ManualWorkoutRescore.IMPROVEMENT_MARGIN_KCAL
            val energyKcal = if (kcalBeatsStored) s.kcal else row.energyKcal
            updated.add(row.copy(energyKcal = energyKcal, avgHr = s.avgHr, maxHr = s.maxHr, strain = s.strain))
        }
        if (updated.isNotEmpty()) repo.upsertWorkouts(updated)
    }

    /**
     * Recomputes only the recovery composite for an already-analyzed day against a (possibly
     * freshly-seeded) baseline, using the baseline-independent values already on [daily] so pass 2
     * skips the expensive sleep/strain/workout pipeline. Null on missing HRV/RHR or an unusable baseline.
     */
    private fun recomputeRecovery(
        daily: DailyMetric,
        baselines: ProfileBaselines,
        priorDayEffort: Double? = null,
    ): Double? {
        val hrvVal = daily.avgHrv ?: return null
        val rhrVal = daily.restingHr ?: return null
        val hrvBase = baselines.hrv ?: return null
        // Feeds the Rest composite (÷100) as the sleep-quality term instead of raw efficiency, and folds
        // in the night's skin-temp deviation (both persisted daily fields). The two Oura terms read the
        // persisted slope + prior-day Effort against the effort baseline, matching the analyzeDay store site.
        val restQuality = RestScorer.restFromDaily(daily)?.let { it / 100.0 } ?: daily.efficiency
        return RustScores.recovery(
            hrv = hrvVal,
            rhr = rhrVal.toDouble(),
            resp = daily.respRateBpm, // term drops + renormalizes when null / no usable baseline
            hrvBaseline = hrvBase,
            rhrBaseline = baselines.restingHR,
            respBaseline = baselines.resp,
            sleepPerf = restQuality,
            skinTempDev = daily.skinTempDevC,
            recoveryIndexSlope = daily.recoveryIndexSlope,
            effortBaseline = baselines.effort,
            priorDayEffort = priorDayEffort,
        )
    }

    /** One day's source-only (daily-aggregate) recovery output, keyed by day. */
    data class WatchScoredDay(val day: String, val recovery: Double?, val confidence: ScoreConfidence)

    /**
     * Scores Charge for daily-aggregate (import-only) days the raw-HR loop never touched: folds each
     * row's trailing HRV + RHR history into [WatchRecovery], mirroring Charge's shape for daily values.
     * Stays null until enough usable nights exist; [strapRecoveryDays] are already-scored days, skipped.
     */
    fun watchRecoveries(
        rows: List<DailyMetric>,
        strapRecoveryDays: Set<String> = emptySet(),
    ): List<WatchScoredDay> {
        val sorted = rows.sortedBy { it.day }
        val out = ArrayList<WatchScoredDay>()
        for ((i, row) in sorted.withIndex()) {
            if (row.day in strapRecoveryDays) continue
            val prior = sorted.subList(0, i)
            val hrvHistory = prior.mapNotNull { it.avgHrv }
            val rhrHistory = prior.mapNotNull { it.restingHr?.toDouble() }
            val res = WatchRecovery.compute(
                todayHrv = row.avgHrv,
                todayRhr = row.restingHr,
                hrvHistory = hrvHistory,
                rhrHistory = rhrHistory,
            )
            out.add(WatchScoredDay(row.day, res.recovery, res.confidence))
        }
        return out
    }

    /**
     * The Charge term-breakdown trace lines for one day (Recovery test mode, Group G). Feeds the same
     * inputs [recomputeRecovery] does into the side-effect-free [RecoveryScorerTrace.recoveryTrace],
     * whose score is [RecoveryScorer.recovery] verbatim, so the trace can't diverge from the written
     * Charge number. Empty when a hard input (HRV/RHR/baseline) is missing.
     */
    private fun recoveryTraceLines(
        daily: DailyMetric,
        baselines: ProfileBaselines,
        priorDayEffort: Double? = null,
    ): List<String> {
        val hrvVal = daily.avgHrv
        val rhrVal = daily.restingHr
        val hrvBase = baselines.hrv
        if (hrvVal == null || rhrVal == null || hrvBase == null) {
            return listOf(
                "charge day=${daily.day} nilScore reason=missingInput (hrv/rhr/hrvBaseline required)",
            )
        }
        val restQuality = RestScorer.restFromDaily(daily)?.let { it / 100.0 } ?: daily.efficiency
        val (_, trace) = RecoveryScorerTrace.recoveryTrace(
            hrv = hrvVal,
            rhr = rhrVal.toDouble(),
            resp = daily.respRateBpm,
            hrvBaseline = hrvBase,
            rhrBaseline = baselines.restingHR,
            respBaseline = baselines.resp,
            sleepPerf = restQuality,
            skinTempDev = daily.skinTempDevC,
            recoveryIndexSlope = daily.recoveryIndexSlope,
            effortBaseline = baselines.effort,
            priorDayEffort = priorDayEffort,
        )
        // Prefix each line with the day key so a multi-night export stays parseable, matching the sleep
        // trace's per-day shape.
        return trace.map { "charge day=${daily.day} " + it.removePrefix("charge ") }
    }

    /**
     * The prior pass's persisted v18 band sleep_state (H8) for sessions overlapping [from, to], expanded
     * to timestamped (ts, state) samples on the 30 s epoch grid (`startTs + i*30`) for the H7 morning-
     * stillness guard. Empty when nothing is banded — falls back to the HR bar, never a fabricated reading.
     */
    private suspend fun bandSleepStateSamples(
        repo: WhoopRepository,
        computedId: String,
        from: Long,
        to: Long,
    ): List<Pair<Long, Int>> {
        val epochS = 30L
        // Collapses overlapping timebase-shifted duplicates before consuming band state: a stale
        // re-banked copy would otherwise feed "asleep" epochs at the old times into the H7 re-onset
        // guard, letting the stale block keep confirming itself. Read-side only; the store itself is
        // healed post-upsert in analyzeRecentOnCpu.
        val sessions = SleepSessionDedup.dedupe(repo.sleepSessions(computedId, from, to, 4000)).kept
        val samples = ArrayList<Pair<Long, Int>>()
        for (s in sessions) {
            val states = repo.sessionSleepState(computedId, s.startTs) ?: continue
            if (states.isEmpty()) continue
            for ((i, st) in states.withIndex()) {
                samples.add((s.startTs + i * epochS) to st)
            }
        }
        return samples
    }

    /**
     * The user's habitual midsleep (local time-of-day seconds), or null under HABITUAL_MIN_DAYS of
     * history (cold-start). Builds one HistoryBlock per stored session (effective bounds, local-day
     * midpoint key) and defers to [SleepStageTotals.habitualMidsleepSec], which keeps the longest block per day so naps drop out.
     */
    private suspend fun computeHabitualMidsleep(
        repo: WhoopRepository,
        importedId: String,
        computedId: String,
        windowStart: Long,
        windowEnd: Long,
        offsetSec: Long,
    ): Long? {
        val imported = repo.sleepSessions(importedId, windowStart, windowEnd, 4000)
        val computed = repo.sleepSessions(computedId, windowStart, windowEnd, 4000)
        // Collapses overlapping timebase-shifted duplicates before the learner sees the history: a stale
        // re-banked copy lands on a different day key, so the per-day longest-block de-dup wouldn't
        // otherwise catch it. Also covers an imported night and its computed twin (longest capture wins).
        val merged = SleepSessionDedup.dedupe(imported + computed).kept
        val blocks = merged.mapNotNull { s ->
            val start = s.effectiveStartTs
            val end = s.endTs
            if (end <= start) {
                null
            } else {
                val mid = start + (end - start) / 2
                SleepStageTotals.HistoryBlock(start, end, AnalyticsEngine.dayString(mid, offsetSec))
            }
        }
        return SleepStageTotals.habitualMidsleepSec(blocks, offsetSec)
    }

    /**
     * The edited/hand-logged sleep rows that belong to [day]: an edit belongs to the day its night ends
     * on (`dayString(endTs)`), matching AnalyticsEngine's end-day session bucket; `endTs` is stable under
     * a bedtime edit (only the onset moves), so scoping per day stops one edit leaking its total onto every night.
     */
    internal fun editedRowsForDay(
        editedRows: List<SleepSession>,
        day: String,
        tzOffsetSeconds: Long,
    ): List<SleepSession> = editedRows.filter { AnalyticsEngine.dayString(it.endTs, tzOffsetSeconds) == day }

    /**
     * Overrides a day's detected sleep aggregates with the user's hand-corrected window: substitutes
     * each edited block (matched by its stable detected startTs) and recomputes totalSleep/efficiency/
     * stage minutes from the reshaped stages. No edit touching the night leaves the daily unchanged.
     */
    private fun sleepEditedDaily(
        daily: DailyMetric,
        detected: List<DetectedSleep>,
        editsByStart: Map<Long, String?>,
        // Each edited block's effective onset (startTsAdjusted ?: startTs), keyed by its stable detected
        // startTs. A detected-but-unedited block isn't in here and falls back to its own detected start.
        editOnsetByStart: Map<Long, Long>,
        tzOffsetSeconds: Long,
        // The learned habitual midsleep (local time-of-day seconds) so the edited recompute picks the
        // same main night the Sleep tab shows; null = cold-start.
        habitualMidsleepSec: Long?,
    ): DailyMetric {
        if (editsByStart.isEmpty()) return daily
        // Detected blocks keyed by their stable startTs plus their re-encoded stages.
        val detectedTuples = detected.map { it.start to AnalyticsEngine.encodeStages(it.stages) }
        // A hand-logged nap is a userEdited row with no detected twin; pass those twinless rows through
        // the union channel so the seam knows about them (shown as their own session row; the main-night
        // pick below decides the headline total).
        val detectedStarts = detected.map { it.start }.toHashSet()
        val manual = editsByStart.filter { it.key !in detectedStarts }.map { it.key to it.value }
        // Supplies each block's effective onset keyed by its stable detected startTs, plus the tz offset
        // and learned habitual midsleep, so the edited recompute picks the same main night the Sleep tab
        // shows. The onset must be the user-corrected bedtime, not the immutable detected start.
        val editStarts = detectedTuples.map { it.first } + manual.map { it.first }
        val onsetByStart = editStarts.associateWith { start -> editOnsetByStart[start] ?: start }
        val r = SleepStageTotals.dailyAggregateHonoringEdits(
            detectedTuples, editsByStart, manual, onsetByStart, tzOffsetSeconds, habitualMidsleepSec,
        ) ?: return daily
        if (!r.editApplied) return daily
        val agg = r.sleep
        // Substitute ONLY the sleep-derived fields; every non-sleep field is left untouched.
        return daily.copy(
            totalSleepMin = agg.totalSleepMin,
            efficiency = agg.efficiency,
            deepMin = agg.deepMin,
            remMin = agg.remMin,
            lightMin = agg.lightMin,
        )
    }

    /** Assesses Fitness Age readiness from [gateDays] (the merged last-7 the readiness card counts) and,
     *  when ready, builds the fitness_age (+ optional vo2max) rows keyed to [satKey]; empty when not ready.
     *  The single source of the gate + compute, shared with the manual "refresh Fitness Age" button. */
    fun fitnessAgeRows(
        gateDays: List<DailyMetric>, profile: UserProfile, computedId: String, satKey: String,
    ): List<MetricSeriesRow> {
        val rhrs = gateDays.mapNotNull { it.restingHr }.map { it.toDouble() }
        val strains = gateDays.mapNotNull { it.strain }.filter { it >= 30.0 }
        val meanStrain = if (strains.isEmpty()) 0.0 else strains.average()
        val waist = if (profile.waistCm > 0) profile.waistCm else null
        val ready = FitnessAgeEngine.assessReadiness(
            hasAge = profile.age > 0, hasSex = profile.sex.isNotEmpty(),
            rhrDays = rhrs.size, activityDays = gateDays.mapNotNull { it.strain }.size,
            hasHeightWeight = profile.heightCm > 0 && profile.weightKg > 0, hasWaist = waist != null)
        if (!ready.canCompute) return emptyList()
        // VO2max + Fitness-Age math routes through the RustScores FFI; the readiness gate above and the
        // PA-index below stay in Kotlin.
        val res = RustScores.fitnessAgeCompute(
            age = profile.age, sex = profile.sex,
            restingHr = medianOfDoubles(rhrs),
            paIndex = FitnessAgeEngine.physicalActivityIndexFromStrain(strains.size, meanStrain),
            waistCm = waist) ?: return emptyList()
        val rows = mutableListOf(MetricSeriesRow(deviceId = computedId, day = satKey, key = "fitness_age", value = res.fitnessAge))
        res.vo2max?.let { rows.add(MetricSeriesRow(deviceId = computedId, day = satKey, key = "vo2max_est", value = it)) }
        return rows
    }

    /** Manual "refresh Fitness Age" (the not-ready-card button): recomputes the weekly Fitness Age now
     *  from the persisted merged daily history, no raw-HR rescoring, using the same gate ([fitnessAgeRows])
     *  and window logic as the recompute pass. Works offline (stored data only); returns true if written. */
    suspend fun recomputeFitnessAgeOnly(
        repo: WhoopRepository, profile: UserProfile, importedDeviceId: String, maxDays: Int = 21,
    ): Boolean {
        val computedId = importedDeviceId + "-noop"
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val tzOffsetSeconds = java.util.TimeZone.getDefault().getOffset(nowSeconds * 1_000L) / 1_000L
        val nowLocalMidnight = midnightLocal(nowSeconds, tzOffsetSeconds)
        val newestDay = AnalyticsEngine.dayString(nowLocalMidnight, tzOffsetSeconds)
        val oldestDay = AnalyticsEngine.dayString(nowLocalMidnight - (maxDays - 1) * SECONDS_PER_DAY, tzOffsetSeconds)
        val gate7 = repo.daysMerged(importedDeviceId)
            .filter { it.day in oldestDay..newestDay }.sortedBy { it.day }.takeLast(7)
        val rows = fitnessAgeRows(gate7, profile, computedId, saturdayKeyOnOrBefore(newestDay))
        if (rows.isNotEmpty()) repo.upsertMetricSeries(rows)
        return rows.isNotEmpty()
    }

    /**
     * Re-derives the skin-temperature deviation (°C) for a night against the freshly-seeded baseline,
     * mirroring the avgHrv→recovery re-score. Null when the night had no wear-gated mean or the baseline
     * isn't usable yet (< minNightsSeed). Rounded to 2 dp to match imported/demo precision. Approximate.
     */
    private fun recomputeSkinTempDev(nightly: Double?, base: BaselineState?): Double? {
        val v = nightly ?: return null
        val b = base?.takeIf { it.usable } ?: return null
        // Rounds half-away-from-zero to 2 dp: Math.round() is half-up and would diverge on negative
        // .5 ties (e.g. -2.5 rounds to -2 with Math.round(), -3 here).
        val scaled = Baselines.deviation(v, b).delta * 100.0
        val r = if (scaled >= 0) Math.floor(scaled + 0.5) else Math.ceil(scaled - 0.5)
        return r / 100.0
    }

    private fun medianOfDoubles(xs: List<Double>): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
    private fun saturdayKeyOnOrBefore(dayStr: String): String = try {
        val d = java.time.LocalDate.parse(dayStr)               // yyyy-MM-dd
        val back = (d.dayOfWeek.value + 1) % 7                  // SAT->0, SUN->1, MON->2 ... FRI->6
        d.minusDays(back.toLong()).toString()
    } catch (e: Exception) { dayStr }

    /**
     * Resolves the single device that owns [day] (invariant I2), so the day is scored from exactly one
     * source. A locked override wins outright; otherwise builds one [DayOwnerResolver.Candidate] per
     * device with a cheap `LIMIT 1` presence check and returns the lowest-priority candidate with data,
     * falling back to [importedDeviceId] when [ownerSource] is null or the resolver yields no owner.
     */
    private suspend fun resolveDayOwner(
        repo: WhoopRepository,
        ownerSource: DayOwnerSource?,
        candidatePriorities: List<Pair<String, Int>>,
        day: String,
        from: Long,
        to: Long,
        importedDeviceId: String,
    ): String {
        if (ownerSource == null) return importedDeviceId
        // A locked override wins outright and skips the presence checks entirely.
        ownerSource.lockedOwner(day)?.let { return it }
        if (candidatePriorities.isEmpty()) return importedDeviceId
        // On the default single-WHOOP install there's exactly one live candidate and it IS the fallback
        // id, so the per-candidate `LIMIT 1` HR probe is skipped. Gated on `== importedDeviceId`, not
        // just size == 1: a lone import candidate under a different id still needs the probe.
        if (candidatePriorities.size == 1 && candidatePriorities.first().first == importedDeviceId) {
            return importedDeviceId
        }
        val candidates = candidatePriorities.map { (id, priority) ->
            // Cheap presence check: a single HR row for this device in the night window marks it a
            // candidate. (LIMIT 1 , not the full pull the caller does once an owner is chosen.)
            val hasData = repo.hrSamples(id, from, to, 1).isNotEmpty()
            DayOwnerResolver.Candidate(deviceId = id, priority = priority, hasData = hasData)
        }
        return DayOwnerResolver.resolve(day, lockedOwner = null, candidates = candidates) ?: importedDeviceId
    }

    /**
     * Merges one metric's on-device pass-1 nightly values into the imported-history map. Imported
     * (cloud) values win per day; the computed estimate only fills days the import doesn't cover.
     */
    internal fun mergeNightlyIntoHistory(
        hist: LinkedHashMap<String, Double?>,
        nightly: Map<String, Double?>,
    ) {
        // `day !in hist` only checks key presence: an imported row with a null value would shadow the
        // real computed night forever, starving the baseline. `hist[day] == null` catches both absent
        // keys and null values, so imported non-null wins and an absent/null slot is backfilled.
        for ((day, v) in nightly) if (hist[day] == null) hist[day] = v
    }

    internal fun midnightUtc(ts: Long): Long = ts - Math.floorMod(ts, SECONDS_PER_DAY)

    /**
     * Floors a unix-seconds timestamp to 00:00:00 of its local calendar day. [offsetSec] is seconds
     * east of UTC: `ts - floorMod(ts + offsetSec, 86400)`. floorMod keeps the floor correct for negative
     * offsets and timestamps; [offsetSec] == 0 reduces exactly to [midnightUtc].
     */
    internal fun midnightLocal(ts: Long, offsetSec: Long): Long =
        ts - Math.floorMod(ts + offsetSec, SECONDS_PER_DAY)

    /**
     * The per-day diagnostic source token from the imported day-key sets. A WHOOP export covering [day]
     * wins the dashboard merge over the computed row (mergeDaily: imports win field-by-field), so it
     * takes precedence; Apple Health is next; otherwise the day is purely computed.
     */
    internal fun daySourceToken(
        day: String,
        importedWhoopDays: Set<String>,
        appleHealthDays: Set<String>,
    ): String = when {
        day in importedWhoopDays -> "imported:whoop"
        day in appleHealthDays -> "imported:apple"
        else -> "computed"
    }

    /**
     * The `stages=` token of the per-day sleep diagnostic line: `<deep>+<rem>+<light>=<sum>` in rounded
     * minutes when the day carries a full banked stage split, `nil` when any component is absent (an
     * unstaged night, or an imported day that only brought a total). The sum is printed so a rollup-vs-stages divergence is a one-line check against `totalSleepMin=`.
     */
    internal fun sleepStagesLogToken(deep: Double?, rem: Double?, light: Double?): String {
        if (deep == null || rem == null || light == null) return "nil"
        return "${Math.round(deep)}+${Math.round(rem)}+${Math.round(light)}=${Math.round(deep + rem + light)}"
    }

    /**
     * CAPTURE-B's universal provenance token for the dayOwner diagnostic, distinct from [daySourceToken]:
     * it reports what the day's data actually is — `none` (no HR, no import), an import's brand name, or
     * `measured` for real strap HR. An import is named even on a day that also has HR (imports win).
     */
    internal fun universalProvenanceToken(
        day: String,
        hrRows: Int,
        importedWhoopDays: Set<String>,
        appleHealthDays: Set<String>,
    ): String = when {
        day in importedWhoopDays -> "imported:whoop"
        day in appleHealthDays -> "imported:apple"
        hrRows > 0 -> "measured"
        else -> "none"
    }

    /**
     * The verbatim universal dayOwner diagnostic line (CAPTURE-B): [readId] is the device the day was
     * read from (the resolved owner); [writeActiveId] is the registry's active strap (where new data is
     * written); a mismatch between them is the symptom a Test Centre export self-diagnoses. No PII.
     */
    internal fun dayOwnerLine(
        day: String,
        readId: String,
        writeActiveId: String,
        hrRows: Int,
        importedWhoopDays: Set<String>,
        appleHealthDays: Set<String>,
    ): String {
        val provenance = universalProvenanceToken(day, hrRows, importedWhoopDays, appleHealthDays)
        return "dayOwner day=$day readId=$readId writeActiveId=$writeActiveId " +
            "hrRows=$hrRows provenance=$provenance"
    }

    /**
     * The per-day RHR floor-vs-mean diagnostic line. [floor] is the WHOOP-style resting HR (lowest
     * sustained 5-min in-bed level); a "sleeping HR" app's night mean always sits at-or-above it, so
     * logging both explains a lower reading. [inBedBpms] are the matched in-bed samples; empty gives nightMean="nil". No PII.
     */
    internal fun rhrFloorMeanLogLine(day: String, floor: Int, inBedBpms: List<Int>): String {
        val meanLog = if (inBedBpms.isEmpty()) "nil"
            else Math.round(inBedBpms.sum().toDouble() / inBedBpms.size).toString()
        return "rhr day=$day floor=$floor nightMean=$meanLog inBedSamples=${inBedBpms.size} " +
            "(floor = WHOOP-style lowest-sustained = NOOP RHR; mean = sleeping-HR-app number)"
    }
}

package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.EventRow
import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.SkinTempSample
import com.noop.data.Spo2PctSample
import com.noop.data.Spo2Sample
import com.noop.data.RrInterval
import com.noop.data.StepSample
import com.noop.protocol.DeviceFamily
import com.noop.protocol.Whoop4SkinTemp
import com.noop.protocol.skinTempCelsius
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * AnalyticsEngine.kt — orchestrator producing DailyMetric + sleep-session results.
 *
 * Given a day's raw streams + a user profile + personal baselines, runs the individual
 * analyzers (SleepStager / RecoveryScorer / StrainScorer / WorkoutDetector / Baselines)
 * and assembles a [com.noop.data.DailyMetric] (Room cache shape) plus the detected
 * [DetectedSleep] sessions.
 *
 * A PURE function over its inputs — does NOT touch the database (persistence is wired
 * by IntelligenceEngine). All derived values are APPROXIMATE. All `ts`/`start`/`end`
 * are wall-clock unix SECONDS (Long).
 */
object AnalyticsEngine {

    /**
     * Pair the strap's WRIST_OFF/WRIST_ON events into off-wrist [start, end) intervals for the
     * sleep detector's wear filter. Each OFF opens an interval that closes at the next ON, or at
     * [windowEnd] if still off-wrist at the end of the window. Repeated OFFs/ONs coalesce.
     */
    fun offWristIntervals(events: List<EventRow>, windowEnd: Long): List<Pair<Long, Long>> {
        val wear = events
            .filter { it.kind.startsWith("WRIST_OFF") || it.kind.startsWith("WRIST_ON") }
            .sortedBy { it.ts }
        val intervals = ArrayList<Pair<Long, Long>>()
        var offStart: Long? = null
        for (e in wear) {
            if (e.kind.startsWith("WRIST_OFF")) {
                if (offStart == null) offStart = e.ts            // ignore repeated OFFs
            } else {                                             // WRIST_ON closes an open off-wrist span
                val s = offStart
                if (s != null && e.ts > s) intervals.add(s to e.ts)
                offStart = null
            }
        }
        val s = offStart
        if (s != null && windowEnd > s) intervals.add(s to windowEnd)
        return intervals
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Day-string helper (UTC YYYY-MM-DD).
    // ─────────────────────────────────────────────────────────────────────────

    private val isoDay: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    /** Format a unix-seconds timestamp as a UTC YYYY-MM-DD day string. */
    fun dayString(ts: Long): String = isoDay.format(Instant.ofEpochSecond(ts))

    /**
     * Format a unix-seconds timestamp as the device's LOCAL YYYY-MM-DD day string. The day key is
     * the aggregation key for daily metrics, so it must match the device's LOCAL calendar day, not
     * UTC. [offsetSec] is seconds EAST of UTC; local date = UTC date of `(ts + offsetSec)`.
     */
    fun dayString(ts: Long, offsetSec: Long): String = dayString(ts + offsetSec)

    /**
     * JSON-encode stage segments to the array shape the sleepSession cache stores. Keys are
     * emitted in a FIXED alphabetical order (end, stage, start) built by hand, since JSONObject's
     * HashMap-backed store has no stable order and drift would break [SleepStageHealer]'s equality check.
     */
    fun encodeStages(stages: List<StageSegment>): String? {
        return try {
            val sb = StringBuilder()
            sb.append('[')
            for ((i, s) in stages.withIndex()) {
                if (i > 0) sb.append(',')
                // Keys alphabetical: end, stage, start.
                sb.append("{\"end\":").append(s.end)
                    .append(",\"stage\":").append(JSONObject.quote(s.stage))
                    .append(",\"start\":").append(s.start)
                    .append('}')
            }
            sb.append(']')
            sb.toString()
        } catch (e: Throwable) {
            android.util.Log.w("AnalyticsEngine", "sleepStageTotalsJSON: stages-to-JSON failed, dropping stage data", e)
            null
        }
    }

    /**
     * Analyze one day's streams into a [DayResult].
     *
     * @param day the caller's LOCAL calendar-day key; a sleep session is attributed to the day
     *   its `end` falls on. @param hr/rr/gravity the day's raw streams (a wider window than the
     *   night is fine). @param maxHROverride explicit HRmax; null falls back to Tanaka(profile.age).
     */
    fun analyzeDay(
        day: String,
        hr: List<HrSample> = emptyList(),
        rr: List<RrInterval> = emptyList(),
        gravity: List<GravitySample> = emptyList(),
        steps: List<StepSample> = emptyList(),
        // Calendar-day-scoped overrides for the ADDITIVE daily totals (steps + activeKcalEst),
        // workout detection, and strain; null falls back to the night window (~42h, ending ≈noon)
        // the rest of the analysis uses. Sleep/recovery always use hr/rr/gravity, not these.
        dayHr: List<HrSample>? = null,
        daySteps: List<StepSample>? = null,
        dayGravity: List<GravitySample>? = null,
        // Wear-gated nightly skin-temp mean, harvested baseline-independent; IntelligenceEngine seeds
        // a personal baseline from these across nights and re-derives skinTempDevC in a second pass.
        skinTemp: List<SkinTempSample> = emptyList(),
        // Device family that wrote [skinTemp], so the raw→°C conversion picks the right scale: 5/MG
        // banks CENTIDEGREES (raw/100), WHOOP 4.0's v24 field is a RAW ADC on a different scale.
        // Default WHOOP5 keeps existing callers unchanged.
        skinTempFamily: DeviceFamily = DeviceFamily.WHOOP5,
        // Per-device WHOOP 4.0 worn anchor raw: the raw value that maps to 33.0 °C for THIS device. The
        // @72 ADC's register offset is per-device (floor ~509, saturation 2047, worn band varies), so a
        // global anchor can map a worn band outside 28–42 °C and fail the worn gate. null → global anchor.
        skinTempAnchorRaw: Double? = null,
        // WHOOP 4.0 raw SpO2 PPG ADC samples (red/IR) for the night window. Nightly red/IR means over
        // detected sleep are banked as RAW ADC, NOT a calibrated blood-oxygen % (needs WHOOP's curve).
        // Default empty keeps non-4.0 nights null.
        spo2: List<Spo2Sample> = emptyList(),
        // WHOOP 5.0/MG sleep SpO2 % samples (v18 @frame-82); whoop-rs sleep-gates + drops sentinels first,
        // so each is a real reading. Nightly MEDIAN over detected sleep is banked as the day's SpO2 % —
        // a WELLNESS estimate, never medical. Default empty keeps WHOOP 4.0 nights null.
        spo2Pct: List<Spo2PctSample> = emptyList(),
        profile: UserProfile,
        baselines: ProfileBaselines = ProfileBaselines(),
        maxHROverride: Double? = null,
        // Wall-clock UTC offset (seconds) for the sleep detector's daytime false-sleep guard.
        // Default 0 keeps callers on UTC; IntelligenceEngine passes the device's real offset.
        tzOffsetSeconds: Long = 0L,
        // Off-wrist [start, end) intervals, paired from WRIST_OFF/WRIST_ON events by [offWristIntervals].
        // Sharpens the always-on HR-gap guard: a session drops only when its off-wrist coverage reaches
        // maxOffWristSleepFraction. Default empty keeps pure-function callers event-free.
        wristOff: List<Pair<Long, Long>> = emptyList(),
        // Personal sleep need (hours) for the Rest "duration vs need" component; null → 8 h default.
        // IntelligenceEngine refines it from the user's recent average asleep hours.
        sleepNeedHours: Double? = null,
        // How many recent nights informed [sleepNeedHours] (0 = still on the 8 h default). Drives the
        // Rest confidence tier only; does not affect the score.
        sleepNeedNights: Int = 0,
        // Sleep/wake regularity in [0,1] (1 = perfectly regular) for the Rest "consistency" component.
        // null (no history) drops the term and renormalizes the remaining weights.
        sleepConsistency: Double? = null,
        // The previous day's Effort/strain for the recovery Activity-Balance term, scored against the
        // effort baseline in [baselines]. null drops the term.
        priorDayEffort: Double? = null,
        // Learned habitual midsleep (local time-of-day seconds in [0, 86400)) for the main-night pick,
        // so a late/shift sleeper's real night out-scores a daytime nap. null = cold-start: falls back
        // to the broad overnight-band bonus.
        habitualMidsleepSec: Long? = null,
        // The strap's own persisted v18 BAND sleep_state per timestamp (`(sb shr 4) and 3`: 0 wake /
        // 1 still / 2 asleep / 3 up). Consumed only to confirm a borderline morning re-onset: a daytime
        // block the strap itself scored "asleep" is kept even on a borderline HR dip.
        bandSleepState: List<Pair<Long, Int>> = emptyList(),
        // Sleep & Rest test-mode trace sink. null = default (no-op). When non-null the gate trace from
        // detectSleep and the Rest sub-score line are forwarded line-by-line.
        traceSink: ((String) -> Unit)? = null,
        // HRV & Autonomic test-mode sink. null = default (no-op). When non-null, the nightly
        // per-5-min-window RMSSDs (tagged by sleep stage) plus a whole-night vs deep-only vs
        // last-SWS summary are forwarded, showing which stages lift the reported HRV.
        hrvTraceSink: ((String) -> Unit)? = null,
        // Whether to emit the ~90 per-window `hrv window …` lines vs just the 1-line summary. True only
        // for the most-recent night so the 5000-line ring buffer isn't flooded; the 1-line
        // `hrv nightSummary` is kept for every night.
        hrvWindowDetail: Boolean = false,
    ): DayResult {

        // ── Sleep detection + staging ─────────────────────────────────────────
        // Detection + staging + the motion-aware wake refinement all run in whoop-rs (physio-algo)
        // behind one FFI call; `steps` feeds the refinement, which self-gates on the observed density.
        val allSessions = RustSleepStager.analyze(
            hr = hr, rr = rr, gravity = gravity, steps = steps,
            tzOffsetSeconds = tzOffsetSeconds, wristOff = wristOff, bandSleepState = bandSleepState,
        )
        // Sessions attributed to `day` = those whose end falls on `day` (LOCAL day). `day` is the
        // caller's local-day key; attribute by the same offset so the bucket and the key agree.
        val matched = allSessions.filter { dayString(it.end, tzOffsetSeconds) == day }

        // ── The day's MAIN night ──────────────────────────────────────────────
        // Sleep-duration figures (asleep/stage minutes, efficiency, disturbances, Rest, debt ledger)
        // describe only the MAIN night: fragments split by a short wake are bridged into one scored
        // group by [SleepStageTotals.mainNightGroupIndicesScored]; naps stay separate rows in
        // `sleepSessions`. Candidates are scored on the sleep their hypnogram DECODED, not on their clock
        // span, so a span the stager filled with wake cannot be named the day's night.
        val mainGroupIdx = SleepStageTotals.mainNightGroupIndicesScored(
            matched.map {
                val hm = SleepStager.hypnogramMetrics(it)
                SleepStageTotals.ScoredNightBlock(it.start, hm.tstS, hm.tibS)
            },
            tzOffsetSeconds, habitualMidsleepSec,
        ) ?: emptyList()
        val mainGroup: List<DetectedSleep> = mainGroupIdx.map { matched[it] }

        // ── Daily sleep aggregates (AASM) summed over the main-night group ─────
        var deepS = 0.0
        var remS = 0.0
        var lightS = 0.0
        var tstS = 0.0
        var inBedS = 0.0
        var effWeighted = 0.0
        var disturbances = 0
        for (s in mainGroup) {
            val m = SleepStager.hypnogramMetrics(s)
            val inBed = (s.end - s.start).toDouble()
            inBedS += inBed                       // each fragment's own in-bed span (the gap is added below)
            effWeighted += s.efficiency * inBed   // in-bed-weighted efficiency across the group
            deepS += m.deepMin * 60.0
            remS += m.remMin * 60.0
            lightS += m.lightMin * 60.0
            tstS += m.tstS
            disturbances += m.disturbances
        }
        // OUT-OF-BED time between bridged fragments is AWAKE: it falls in no fragment's own span, so
        // it's folded in by extending the in-bed denominator (in-bed = asleep + awake; tstS unchanged).
        // Shared with [SleepStageTotals.interFragmentAwakeSeconds]; a bridged gap counts as one disturbance.
        val gapAwakeS = SleepStageTotals.interFragmentAwakeSeconds(mainGroup.map { it.start to it.end })
        if (gapAwakeS > 0.0) {
            inBedS += gapAwakeS                   // the gap is fully awake: extends in-bed, adds 0 to effWeighted
            disturbances += 1
        }
        val efficiency = if (inBedS > 0) effWeighted / inBedS else 0.0

        // The sleep-DURATION figures above are main-night-only, but the physiological aggregates below
        // (resting HR, HRV, respiration) intentionally use ALL matched sessions: recovery should reflect
        // the day's best resting physiology, and the main overnight dominates these anyway (in-bed-weighted).
        // Daily resting HR = lowest per-session resting HR across matched sessions (whoop-rs physio-algo).
        val restingHRDaily: Int? = RustScores.dailyRestingHr(matched.map { it.restingHR })
        // Daily avg HRV: pool RMSSD over DEEP-stage 5-min windows only (slow-wave sleep), computed in
        // whoop-rs, per session then meaned across sessions. null when no session has a deep bucket —
        // the caller shows calibrating, never a fabricated number.
        val avgHRVDaily: Double? = run {
            val deepVals = matched.mapNotNull { s ->
                val ffiSegments = s.stages.map { seg ->
                    val stage = when (seg.stage) {
                        "deep" -> uniffi.whoop_ffi.SleepStage.DEEP
                        "rem" -> uniffi.whoop_ffi.SleepStage.REM
                        "light" -> uniffi.whoop_ffi.SleepStage.LIGHT
                        else -> uniffi.whoop_ffi.SleepStage.WAKE
                    }
                    uniffi.whoop_ffi.SleepSegment(start = seg.start, end = seg.end, stage = stage)
                }
                RustScores.windowedAvgHrvDeep(s.start, s.end, rr, ffiSegments)
            }
            if (deepVals.isEmpty()) null else deepVals.sum() / deepVals.size
        }

        // ── HRV & Autonomic nightly trace ─────────────────────────────────────
        // Calls SleepStager.sessionHrvWindows for the stage-tagged diagnostic breakdown (per-5-min-window
        // RMSSD tagged by sleep stage), compared against the Rust-computed avgHRVDaily above.
        // Zero cost when the sink is null.
        if (hrvTraceSink != null) {
            // sessionHrvWindows requires ts-sorted rr (RMSSD = successive diffs); the value path passes the
            // stager's pre-sorted rrS, so sort our own copy of the day's raw rr once here for the re-window.
            val rrSorted = rr.sortedBy { it.ts }
            val allWin = ArrayList<SleepStager.HrvWindow>()
            for (s in matched) {
                val wins = SleepStager.sessionHrvWindows(s.start, s.end, rrSorted, s.stages)
                if (hrvWindowDetail) {
                    for (w in wins) {
                        hrvTraceSink(
                            "hrv window t=${(w.startTs - s.start) / 60}min stage=${w.stage} " +
                                "beats=${w.cleanBeats} rmssd=${w.rmssd?.let { "${round2(it)}ms" } ?: "nil"}",
                        )
                    }
                }
                allWin.addAll(wins)
            }
            fun meanMs(ws: List<SleepStager.HrvWindow>): String {
                val v = ws.mapNotNull { it.rmssd }
                return if (v.isEmpty()) "nil" else "${round2(v.sum() / v.size)}ms"
            }
            val withR = allWin.filter { it.rmssd != null }
            val deepW = withR.filter { it.stage == "deep" }
            val lastSws = SleepStager.lastDeepRun(allWin).filter { it.rmssd != null }
            // `reported` is the value NOOP actually displays (duration-weighted session-mean-of-means);
            // `wholeNight` is the pooled-window mean it equals on single-session nights and the apples-to-
            // apples baseline for the deepOnly/lastSWS comparison (all three are pooled window means).
            hrvTraceSink(
                "hrv nightSummary reported=${avgHRVDaily?.let { "${round2(it)}ms" } ?: "nil"} " +
                    "wholeNight=${meanMs(withR)} deepOnly=${meanMs(deepW)} " +
                    "lastSWS=${meanMs(lastSws)} nWin=${withR.size} nDeep=${deepW.size}",
            )
        }

        // Nightly APPROXIMATE respiratory rate (breaths/min) from the R-R stream via RSA (RustScores.
        // respRateFromRr): an on-device estimate, not a cloud/clinical value. Night value = median of
        // finite per-session estimates over each matched in-bed session; null when none are finite.
        val respRateDaily: Double? = run {
            val perSession = matched
                .mapNotNull { RustScores.respRateFromRr(rr, it.start, it.end) }
                .filter { it.isFinite() }
            if (perSession.isEmpty()) null else RustScores.median(perSession)
        }


        // ── Skin-temperature deviation (offline) ──────────────────────────────
        // Wear-gated in-bed mean, harvested baseline-independent every pass, plus the deviation against
        // the personal baseline (null in pass 1, when baselines.skinTemp is null; re-derived in pass 2,
        // mirroring avgHrv→recovery). Computed before Charge so its skin-temp penalty can read it. APPROXIMATE.
        val nightlySkinTempC = wornNightlySkinTempC(matched, hr, skinTemp, skinTempFamily, skinTempAnchorRaw)
        val skinTempDevC: Double? = nightlySkinTempC?.let { v ->
            baselines.skinTemp?.takeIf { it.usable }?.let { round2(Baselines.deviation(v, it).delta) }
        }

        // ── Raw SpO2 (WHOOP 4.0 v24 PPG ADC) ──────────────────────────────────
        // Nightly red/IR ADC means over detected in-bed spans, or null when none fell in a span.
        // A RAW device reading for the Health "Raw SpO2" tile — NOT a calibrated blood-oxygen %.
        // Scored in whoop-rs (RustScores.nightlySpo2RawMeans).
        val nightlySpo2Raw = RustScores.nightlySpo2RawMeans(matched, spo2)

        // ── Sleep SpO2 percent — one field, both generations ──────────────────
        // 5.0/MG: MEDIAN of the night's strap-computed readings over in-bed spans (already sleep-gated,
        // sentinels dropped). 4.0: no strap percent exists, so paired red/IR ADC runs through whoop-rs's
        // ratio-of-ratios instead. Either way it lands on DailyMetric.spo2Pct — a WELLNESS estimate, never medical.
        val nightlySpo2Pct = nightlySpo2PctMedian(matched, spo2Pct)
            ?: RustScores.spo2PercentFromPaired(matched, spo2)

        // ── Rest (sleep_performance composite, 0–100) ─────────────────────────
        // Weighted composite: duration-vs-personal-need 0.50 + efficiency 0.20 + restorative
        // (deep+REM)/asleep 0.20 + consistency 0.10. Stored under the sleep_performance key;
        // null when there's no in-bed session.
        val rest: Double? = if (matched.isEmpty()) null else RustScores.rest(
            asleepSeconds = tstS,
            efficiency = efficiency,
            deepSeconds = deepS,
            remSeconds = remS,
            sleepNeedHours = sleepNeedHours,
            consistency = sleepConsistency,
        )
        // Gravity-sparse computed ONCE, reused by the sleep-motion trace below and the Rest confidence
        // guard, so the two can never diverge and isGravitySparse runs only once per day.
        val gravitySparse = SleepStager.isGravitySparse(gravity, hr)
        // Emit the Rest sub-score breakdown for this night, reusing the IDENTICAL inputs `rest` consumed
        // above so the trace can never disagree with the score. Emitted only when a trace is requested
        // and this day scored a night.
        if (traceSink != null && matched.isNotEmpty()) {
            traceSink(RestScorer.subScoreLine(
                tstSeconds = tstS, inBedSeconds = inBedS, efficiency = efficiency,
                restorativeSeconds = deepS + remS,
                needHours = sleepNeedHours ?: RestScorer.defaultSleepNeedHours,
                consistency = sleepConsistency, deepSeconds = deepS,
                groupFragments = mainGroup.size, groupInBedSeconds = inBedS))
            // The motion-coverage + staging context behind the Rest number, so a high score on a poor night
            // can be explained from an export: WHOOP 4.0 banks motion coarsely (sparse=true), so most epochs
            // default to sleep, over-counting duration into a high Rest. `stager` is always V2.
            traceSink(RestScorer.sleepMotionLine(day, gravity.size, hr.size,
                gravitySparse, skinTempFamily))
            // The ONSET decision: did HR dip when the window opened, or open on a still-but-awake stretch
            // (HR ~baseline)? Both the baseline and at-onset window read from the SAME HR detection ran over
            // (`dayHr ?: hr`), so a real onset reads clearly below the day median. Silent when HR is absent.
            val onsetHr = dayHr ?: hr
            val onsetTs = (mainGroup.minOfOrNull { it.start } ?: matched.minOfOrNull { it.start }) ?: 0L
            val baselineHr = RestScorer.medianBpm(onsetHr.map { it.bpm })
            val hrAtOnset = RestScorer.medianBpm(
                onsetHr.filter { it.ts >= onsetTs && it.ts < onsetTs + RestScorer.onsetTraceWindowSec }.map { it.bpm })
            if (onsetTs > 0 && baselineHr != null && hrAtOnset != null) {
                traceSink(RestScorer.sleepOnsetLine(onsetTs, hrAtOnset, baselineHr))
            }
        }

        // Overnight resting-HR decline slope (bpm/hr) across the main-night in-bed window, for the
        // recovery "Recovery Index" term. Persisted so pass-2 recompute + the driver breakdown read the
        // same value scored here. null with no main night or too few bins.
        val recoveryIndexSlope: Double? = run {
            val s = mainGroup.minOfOrNull { it.start }
            val e = mainGroup.maxOfOrNull { it.end }
            if (s != null && e != null) RustScores.recoveryIndexSlope(hr, s, e) else null
        }

        // ── Recovery / Charge ─────────────────────────────────────────────────
        var recovery: Double? = null
        val hrvVal = avgHRVDaily
        val rhrVal = restingHRDaily
        val hrvBase = baselines.hrv
        if (hrvVal != null && rhrVal != null && hrvBase != null) {
            // Charge "Rest quality" term reads the Rest composite ÷100 (0..1), not raw efficiency.
            val sleepPerf = rest?.let { it / 100.0 }
            recovery = RustScores.recovery(
                hrv = hrvVal,
                rhr = rhrVal.toDouble(),
                resp = respRateDaily, // term drops + renormalizes when null / no baseline
                hrvBaseline = hrvBase,
                rhrBaseline = baselines.restingHR,
                respBaseline = baselines.resp,
                sleepPerf = sleepPerf,
                skinTempDev = skinTempDevC, // symmetric penalty; term drops + renormalizes when null
                recoveryIndexSlope = recoveryIndexSlope,
                effortBaseline = baselines.effort, // prior-day Effort baseline; term drops when absent
                priorDayEffort = priorDayEffort,
            )
        }

        // ── Strain ("Effort") — cardiovascular load over the full CALENDAR day ──
        // Integrates dayHr ([localMidnight, +24h), clamped to now for today) when supplied so Effort
        // covers the whole day instead of cutting off at the night window's ≈ noon bound. Falls back
        // to the night hr for pure-function callers.
        val effMaxHR: Double? = maxHROverride
            ?: if (profile.age > 0) StrainScorer.tanakaHRmax(profile.age) else null
        val restForStrain = restingHRDaily?.toDouble() ?: StrainScorer.defaultRestingHR
        // Day Effort (0–100 log cardiovascular load) scores via [RustScores.strain], proven bit-for-bit
        // == StrainScorer.strain (RustStrainParityTest). StrainScorer.strain itself stays live for the
        // per-bout workout path and the frontend live-preview; only this daily store site routes to Rust.
        val strain = RustScores.strain(
            hr = dayHr ?: hr,
            maxHR = effMaxHR,
            restingHR = restForStrain,
            method = StrainScorer.Method.EDWARDS,
            sex = profile.sex,
            denominator = RustScores.strainDenominator(),
        )

        // ── Workouts ──────────────────────────────────────────────────────────
        // Detects over the full calendar day (dayHr/dayGravity) when supplied, so a same-day
        // afternoon/evening workout isn't delayed to the next night-window pass (≈ noon cutoff).
        // Falls back to the night window for pure-function callers.
        val workouts = WorkoutDetector.detect(
            hr = dayHr ?: hr,
            gravity = dayGravity ?: gravity,
            restingHR = restingHRDaily?.toDouble(),
            maxHR = maxHROverride,
            age = if (profile.age > 0) profile.age else null,
            profile = profile,
        )

        // ── Steps (APPROXIMATE) ───────────────────────────────────────────────
        // step_motion_counter@57 is a CUMULATIVE u16 that wraps at 65536; the daily total sums
        // WRAP-AWARE deltas ((cur - prev) and 0xFFFF) across ts-ASC records, filtered to the LOCAL-day
        // key first. An ESTIMATE only — not cloud/clinical parity.
        val stepsTotal: Int? = run {
            // Prefer the full-calendar-day stream for the additive total, falling back to the night
            // window for pure-function callers. Filter to the LOCAL-day key first; wrap-aware tick
            // math lives in whoop-rs (RustScores.steps) so the daily and per-workout totals can never disagree.
            val inDay = (daySteps ?: steps).filter { dayString(it.ts, tzOffsetSeconds) == day }
            val ticks = RustScores.steps(inDay) ?: return@run null
            // @57 counts motion ticks, not validated steps — the 5/MG counter overcounts. Divide by the
            // user-calibrated ticks-per-step (default 1.0 = pass-through; floor 0.5 so a bad pref can at
            // most double, never explode, the total).
            val scaled = (ticks.toDouble() / max(profile.stepTicksPerStep, 0.5)).roundToLong()
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (scaled > 0) scaled else null
        }

        // ── Daily calories (APPROXIMATE, HR-only whole-day estimate) ──────────
        // Whole-day active+resting energy from the full HR window: resting BMR below activeThreshold,
        // Keytel active above (same model the per-workout estimate uses). Summed over the LOCAL
        // calendar day, not the ~42h night window, so late hours aren't dropped. Null with no HR.
        val dayHrFiltered = (dayHr ?: hr).filter { dayString(it.ts, tzOffsetSeconds) == day }
        val activeKcalEst: Double? = if (dayHrFiltered.isEmpty()) {
            null
        } else {
            RustScores.caloriesDay(
                hr = dayHrFiltered,
                profile = profile,
                hrmax = effMaxHR,
                restingHR = restingHRDaily?.toDouble(),
            )
        }

        // ── Time in the heart-rate zone bands ─────────────────────────────────
        // Binned from the day's own HR samples against the age-derived %HRmax zones, so a day with no
        // logged session still splits honestly. Grouped low (1-3) and high (4-5) the way the dashboard
        // reads them. Null when the day carries no HR, never a fabricated zero.
        val zoneMinutes: List<Double>? = if (dayHrFiltered.isEmpty()) null else {
            val secs = RustScores.hrTimeInZone(dayHrFiltered, age = profile.age, maxHrOverride = effMaxHR).seconds
            secs.map { it / 60.0 }.takeIf { m -> m.any { it > 0.0 } }
        }

        // ── Assemble DailyMetric ──────────────────────────────────────────────
        // deviceId is stamped by the caller (IntelligenceEngine persists under
        // "<deviceId>-noop"); use the imported source id as a placeholder here so
        // the value type is complete. The caller copies with its computed id.
        val daily = DailyMetric(
            deviceId = "",
            day = day,
            totalSleepMin = if (matched.isEmpty()) null else tstS / 60.0,
            efficiency = if (matched.isEmpty()) null else efficiency,
            deepMin = if (matched.isEmpty()) null else deepS / 60.0,
            remMin = if (matched.isEmpty()) null else remS / 60.0,
            lightMin = if (matched.isEmpty()) null else lightS / 60.0,
            disturbances = if (matched.isEmpty()) null else disturbances,
            restingHr = restingHRDaily,
            avgHrv = avgHRVDaily,
            recovery = recovery,
            strain = strain,
            exerciseCount = workouts.size,
            spo2Pct = nightlySpo2Pct,
            skinTempDevC = skinTempDevC,
            skinTempAbsC = nightlySkinTempC,
            respRateBpm = respRateDaily,
            steps = stepsTotal,
            activeKcalEst = activeKcalEst,
            zone1to3Min = zoneMinutes?.take(3)?.sum(),
            zone4to5Min = zoneMinutes?.drop(3)?.sum(),
            spo2Red = nightlySpo2Raw?.first,
            spo2Ir = nightlySpo2Raw?.second,
            // Persist the Rest inputs so restFromDaily recomputes the same score off the stored row.
            sleepNeedHours = sleepNeedHours,
            sleepConsistency = sleepConsistency,
            // Persist the recovery Oura-term inputs so pass-2 recompute + the driver breakdown score
            // against the same slope + prior-day Effort this store site used.
            recoveryIndexSlope = recoveryIndexSlope,
            priorDayEffort = priorDayEffort,
        )

        // ── Per-score confidence tiers ─────────────────────────────────────────
        val chargeConfidence = ScoreConfidence.forCharge(recovery, baselines.hrv)
        val effortConfidence = ScoreConfidence.forEffort(strain, hr.size)
        // Rest confidence with the H9 + sparse-motion guard: downgrades to low-confidence a night whose
        // deep+REM share is implausibly low on a high-efficiency night, or one staged on sparse gravity
        // (WHOOP 4.0's coarse motion can't reliably stage sleep). Confidence-only, no faked stages.
        val restConfidence = ScoreConfidence.forRest(
            hasSession = matched.isNotEmpty(),
            hasStagedSleep = (deepS + remS) > 0,
            asleepSeconds = tstS,
            restorativeSeconds = deepS + remS,
            efficiency = efficiency,
            gravitySparse = gravitySparse,
        )

        // ── Per-session per-epoch motion (H8) ─────────────────────────────────
        // The strap's per-epoch movement on the SAME 30 s grid as each session's stages, persisted
        // beside `stagesJSON`. A session that can't grid (too little gravity) is omitted, so the
        // caller persists NULL rather than a fabricated zero series.
        val sessionMotionByStart = HashMap<Long, List<Double>>()
        for (s in matched) {
            if (s.motionGrid.isNotEmpty()) sessionMotionByStart[s.start] = s.motionGrid
        }

        // ── Per-session per-epoch BAND sleep_state ────────────────────────────
        // Grids the strap's own band sleep_state (same samples the H7 guard consumes) onto each
        // session's 30 s epochs, for the caller to persist beside `stagesJSON`. Empty on WHOOP 4.0;
        // never overrides the derived hypnogram, only confirms a borderline morning re-onset.
        val sessionSleepStateByStart = HashMap<Long, List<Int>>()
        for (s in matched) {
            if (s.sleepStateGrid.isNotEmpty()) sessionSleepStateByStart[s.start] = s.sleepStateGrid
        }

        return DayResult(
            daily = daily,
            sleepSessions = matched,
            workouts = workouts,
            recovery = recovery,
            strain = strain,
            rest = rest,
            nightlySkinTempC = nightlySkinTempC,
            chargeConfidence = chargeConfidence,
            effortConfidence = effortConfidence,
            restConfidence = restConfidence,
            sessionMotionByStart = sessionMotionByStart,
            sessionSleepStateByStart = sessionSleepStateByStart,
        )
    }

    /** Round to 2 decimal places (matches the imported/demo skin-temp deviation precision). */
    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    /** Min worn, in-bed skin-temp samples (1 Hz ⇒ seconds) before a nightly mean is trusted. ~5 min
     *  guards against a few stray samples fabricating a baseline value. */
    private const val MIN_SKIN_TEMP_SAMPLES_INLINE = 300

    /**
     * Wear-gated mean in-bed skin temperature (°C) for the night, or null when too few worn samples.
     * A sample counts only inside a detected in-bed span, with a concurrent worn/alive HR, and in the
     * plausible worn range (raw→°C is DEVICE-FAMILY-AWARE: 5/MG = raw/100, WHOOP4 a different ADC scale). APPROXIMATE.
     */
    internal fun wornNightlySkinTempC(
        sessions: List<DetectedSleep>,
        hr: List<HrSample>,
        skinTemp: List<SkinTempSample>,
        family: DeviceFamily = DeviceFamily.WHOOP5,
        // Per-device WHOOP 4.0 worn anchor raw; null → the global Whoop4SkinTemp.ANCHOR_RAW. Threaded
        // straight to the funnel's conversion.
        anchorRaw: Double? = null,
        minSamples: Int = MIN_SKIN_TEMP_SAMPLES_INLINE,
    ): Double? = skinTempFunnel(sessions, hr, skinTemp, family, anchorRaw, minSamples).mean

    /** Physiological SpO2 band (%) the nightly median counts. whoop-rs already drops sentinels/diagnostics
     *  at decode, so this is a defensive floor/ceiling — never a clinical range. (v18 @frame-82) */
    private const val SPO2_PCT_MIN: Int = 70
    private const val SPO2_PCT_MAX: Int = 100

    /**
     * Nightly MEDIAN of the WHOOP 5.0/MG sleep SpO2 percent over the detected in-bed [sessions], or
     * null when none fell in a span and passed the physiological band. whoop-rs already sleep-gates
     * and drops sentinels, so every sample is real. A WELLNESS estimate, never medical.
     */
    internal fun nightlySpo2PctMedian(
        sessions: List<DetectedSleep>,
        spo2Pct: List<Spo2PctSample>,
    ): Double? {
        if (sessions.isEmpty() || spo2Pct.isEmpty()) return null
        val vals = ArrayList<Int>(spo2Pct.size)
        for (s in spo2Pct) {
            if (s.pct < SPO2_PCT_MIN || s.pct > SPO2_PCT_MAX) continue
            if (sessions.none { s.ts in it.start..it.end }) continue
            vals.add(s.pct)
        }
        if (vals.isEmpty()) return null
        vals.sort()
        val n = vals.size
        return if (n % 2 == 1) vals[n / 2].toDouble() else (vals[n / 2 - 1] + vals[n / 2]) / 2.0
    }

    /** Plausible worn skin-temperature range (°C). Off-wrist/charging samples drift to ambient and are
     *  excluded; the strap's own decode gate is the looser 20–45. */
    private const val SKIN_TEMP_MIN_C: Double = 28.0
    private const val SKIN_TEMP_MAX_C: Double = 42.0

    // ── Skin-temp funnel diagnostic ─────────────────────────────────────────────────────────────

    /**
     * Why nightly skin temp funneled toward absent for one night. Each sample is attributed to the
     * FIRST gate that drops it, in the SAME order [wornNightlySkinTempC] applies (not-worn →
     * out-of-window → out-of-range → kept); the four drop counts plus [kept] sum to [totalSamples].
     */
    data class SkinTempFunnelDiagnostic(
        val totalSamples: Int,
        val droppedNotWorn: Int,
        val droppedOutOfWindow: Int,
        val droppedOutOfRange: Int,
        val kept: Int,
        val minSamples: Int,
        val mean: Double?,
        // Raw-ADC visibility so an absent WHOOP 4.0 skin temp explains WHY (anchor mis-map vs
        // genuinely no worn data). Pure observations of the input — do NOT affect mean/gates.
        /** Min / median / max of the night's RAW skin-temp ADC values (null when no samples). */
        val rawMin: Int? = null,
        val rawMedian: Int? = null,
        val rawMax: Int? = null,
        /** Raw samples inside the worn ADC band (WORN_MIN..WORN_MAX_RAW); ≥100 lets the per-device anchor learn. */
        val inBandCount: Int = 0,
        /** The anchor raw actually used for the °C map (caller's per-device anchor, else the global 826). null on 5/MG. */
        val resolvedAnchorRaw: Double? = null,
        /** What °C the median raw maps to under [resolvedAnchorRaw]; outside 28–42 °C ⇒ every worn sample gated out. */
        val medianMappedC: Double? = null,
    ) {
        /** True when the night produced no usable mean - the case this diagnostic exists to triage. */
        val isAbsent: Boolean get() = mean == null

        /** Human-readable line(s) for the caller to LOG. No I/O here - the engine stays pure. When raw
         *  samples exist, a second `skin-temp-raw:` line surfaces the ADC band + resolved anchor mapping. */
        val summary: String
            get() {
                var s = "skin-temp-funnel: $totalSamples samples → kept $kept/$minSamples " +
                    "(mean=${mean?.let { String.format(java.util.Locale.US, "%.2f°C", it) } ?: "absent"}); " +
                    "dropped[notWorn=$droppedNotWorn, outOfWindow=$droppedOutOfWindow, " +
                    "outOfRange=$droppedOutOfRange]"
                if (rawMin != null && rawMedian != null && rawMax != null) {
                    s += "\nskin-temp-raw: raw[min=$rawMin p50=$rawMedian max=$rawMax] inBand=$inBandCount/$totalSamples"
                    if (resolvedAnchorRaw != null && medianMappedC != null) {
                        s += String.format(
                            java.util.Locale.US,
                            "; anchor=%.0f → p50 maps %.1f°C (worn gate 28–42°C, ADC band 550–2040)",
                            resolvedAnchorRaw, medianMappedC,
                        )
                    }
                }
                return s
            }
    }

    /**
     * Read-only skin-temp funnel for one night. Re-runs the SAME wear/window/range gates
     * [wornNightlySkinTempC] uses (producing the IDENTICAL mean), additionally counting where each
     * sample dropped. [wornNightlySkinTempC] is a thin wrapper over this, so the two can never disagree.
     */
    fun skinTempFunnel(
        sessions: List<DetectedSleep>,
        hr: List<HrSample>,
        skinTemp: List<SkinTempSample>,
        family: DeviceFamily = DeviceFamily.WHOOP5,
        // Per-device WHOOP 4.0 worn anchor raw; null → the global Whoop4SkinTemp.ANCHOR_RAW.
        anchorRaw: Double? = null,
        minSamples: Int = MIN_SKIN_TEMP_SAMPLES_INLINE,
    ): SkinTempFunnelDiagnostic {
        val total = skinTemp.size
        // Raw-ADC band + resolved anchor: PURE observation of the input, computed once and reported on
        // both return paths. Never touches the mean/gate logic below.
        val sortedRaws = skinTemp.map { it.raw }.sorted()
        val rawMin = sortedRaws.firstOrNull()
        val rawMax = sortedRaws.lastOrNull()
        val rawMedian = if (sortedRaws.isEmpty()) null else sortedRaws[sortedRaws.size / 2]
        val inBandCount = if (family == DeviceFamily.WHOOP4) {
            sortedRaws.count { it in Whoop4SkinTemp.WORN_MIN_RAW..Whoop4SkinTemp.WORN_MAX_RAW }
        } else {
            total
        }
        val usedAnchor: Double? = if (family == DeviceFamily.WHOOP4) (anchorRaw ?: Whoop4SkinTemp.ANCHOR_RAW) else null
        val medianMappedC: Double? = if (usedAnchor != null && rawMedian != null) {
            skinTempCelsius(rawMedian, family, usedAnchor)
        } else {
            null
        }
        // No sessions ⇒ every sample is out of window; no samples ⇒ an empty funnel. Either way the mean
        // is null, matching [wornNightlySkinTempC]'s early return.
        if (sessions.isEmpty() || skinTemp.isEmpty()) {
            return SkinTempFunnelDiagnostic(
                totalSamples = total, droppedNotWorn = 0,
                droppedOutOfWindow = if (sessions.isEmpty()) total else 0,
                droppedOutOfRange = 0, kept = 0, minSamples = minSamples, mean = null,
                rawMin = rawMin, rawMedian = rawMedian, rawMax = rawMax,
                inBandCount = inBandCount, resolvedAnchorRaw = usedAnchor, medianMappedC = medianMappedC,
            )
        }
        val wornSeconds = HashSet<Long>(hr.size)
        for (h in hr) if (h.bpm in 30..220) wornSeconds.add(h.ts)
        var sum = 0.0
        var kept = 0
        var notWorn = 0
        var outOfWindow = 0
        var outOfRange = 0
        for (t in skinTemp) {
            if (t.ts !in wornSeconds) { notWorn++; continue }
            if (sessions.none { t.ts in it.start..it.end }) { outOfWindow++; continue }
            // WHOOP 4.0 ONLY: drop raws outside the plausible worn ADC band before the anchor map. The
            // no-contact floor (~509) and 11-bit saturation ceiling (2047) are off-wrist/charging transients
            // that a per-device anchor could otherwise map into 28–42 °C. Attributed to the same `outOfRange` bucket.
            if (family == DeviceFamily.WHOOP4 &&
                t.raw !in Whoop4SkinTemp.WORN_MIN_RAW..Whoop4SkinTemp.WORN_MAX_RAW
            ) { outOfRange++; continue }
            // Per-device anchor: null anchorRaw → the global Whoop4SkinTemp.ANCHOR_RAW (826); WHOOP5 ignores it.
            val c = skinTempCelsius(t.raw, family, anchorRaw ?: Whoop4SkinTemp.ANCHOR_RAW)
            if (c < SKIN_TEMP_MIN_C || c > SKIN_TEMP_MAX_C) { outOfRange++; continue }
            sum += c
            kept++
        }
        val mean = if (kept >= minSamples) sum / kept else null
        return SkinTempFunnelDiagnostic(
            totalSamples = total, droppedNotWorn = notWorn, droppedOutOfWindow = outOfWindow,
            droppedOutOfRange = outOfRange, kept = kept, minSamples = minSamples, mean = mean,
            rawMin = rawMin, rawMedian = rawMedian, rawMax = rawMax,
            inBandCount = inBandCount, resolvedAnchorRaw = usedAnchor, medianMappedC = medianMappedC,
        )
    }
}

/*
 * RestScorer — NOOP "Rest" (sleep_performance) composite, 0–100.
 *
 *   Rest = 0.50·duration + 0.20·efficiency + 0.20·restorative + 0.10·consistency
 *
 * Each sub-component is itself on 0–100:
 *   duration     — asleep hours / personal need, clamped at 100 (8 h default, refined by recent avg).
 *   efficiency   — asleep / in-bed (0..1) × 100.
 *   restorative  — (deep + REM) / asleep share, normalized by a healthy target share, clamped 100.
 *   consistency  — sleep/wake regularity (0..1) × 100; null (no history) drops the term and
 *                  renormalizes the remaining weights.
 *
 * Outputs APPROXIMATE — not WHOOP's proprietary Sleep Performance.
 */
object RestScorer {

    // Every value below is READ from whoop-rs (physio-algo rest), never declared here: one displayed
    // Rest score reads them, so they have exactly one owner.

    /** Component weights (sum 1.0 when all present). */
    val wDuration: Double = RustScores.restCfg.wDuration
    val wEfficiency: Double = RustScores.restCfg.wEfficiency
    val wRestorative: Double = RustScores.restCfg.wRestorative
    val wConsistency: Double = RustScores.restCfg.wConsistency

    /** Default personal sleep need (hours) before any recent-average refinement. */
    val defaultSleepNeedHours: Double = RustScores.restCfg.defaultSleepNeedHours

    /**
     * Healthy restorative (deep + REM) share of asleep time. A share at/above this earns full
     * restorative credit; below it scales linearly.
     */
    val restorativeTargetShare: Double = RustScores.restCfg.restorativeTargetShare

    /**
     * Deep-sleep share of asleep time that earns FULL restorative credit; below it the restorative
     * term scales down toward [deepFloorFactor]. Prevents a night with normal REM but almost no deep
     * from earning near-full restorative credit.
     */
    val deepShareTarget: Double = RustScores.restCfg.deepShareTarget

    /** Most the restorative term is scaled down when deep is ~absent — never zeroed, so a low-deep
     *  night reads honestly without the whole night tanking. */
    val deepFloorFactor: Double = RustScores.restCfg.deepFloorFactor

    /** Neutral consistency (fraction) used when the caller supplies no regularity signal. */
    val NEUTRAL_CONSISTENCY: Double = RustScores.restCfg.neutralConsistency

    /**
     * Diagnostic (Sleep & Rest test mode): the motion-coverage + staging context behind the Rest
     * number. `grav`/`hr` are night-window sample counts; `sparse` is the gravity-sparse gate
     * (WHOOP 4.0's coarse motion over-counts sleep duration); `stager` is always V2.
     */
    fun sleepMotionLine(
        day: String, grav: Int, hr: Int, sparse: Boolean, family: DeviceFamily,
    ): String = "sleep-motion day=$day grav=$grav hr=$hr sparse=$sparse " +
        "stager=V2 family=${family.name.lowercase()}"

    /**
     * How long after the detected onset to sample HR for the onset trace (seconds). If onset opened
     * on a still-but-awake stretch, HR here stays near baseline; a real onset has already dipped.
     * 10 min is long enough to average out beat noise.
     */
    const val onsetTraceWindowSec: Long = 600L

    /**
     * Median of a bpm list: sorted, element at size/2 (upper-middle on an even count), deterministic.
     * null on an empty list.
     */
    fun medianBpm(bpms: List<Int>): Int? {
        if (bpms.isEmpty()) return null
        val s = bpms.sorted()
        return s[s.size / 2]
    }

    /**
     * Diagnostic (Sleep & Rest test mode): the ONSET decision behind an over-early bedtime. `hrRatio`
     * = HR-at-onset / day-median baseline; near 1.0 means HR had NOT dipped when the window opened
     * (sparse WHOOP 4.0 motion classifies "lying still, awake" as sleep). A real onset dips well below.
     */
    fun sleepOnsetLine(onsetTs: Long, hrAtOnsetBpm: Int, baselineHrBpm: Int): String {
        val ratio = if (baselineHrBpm > 0) hrAtOnsetBpm.toDouble() / baselineHrBpm.toDouble() else 0.0
        val r2 = Math.round(ratio * 100.0) / 100.0
        return "sleep-onset onsetTs=$onsetTs hrAtOnset=$hrAtOnsetBpm baselineHr=$baselineHrBpm hrRatio=$r2"
    }

    /**
     * Diagnostic line for the Rest composite: recomputes the four weighted sub-scores from the
     * SAME inputs the scorer reads, then pulls the final `composite=` value from whoop-rs so the
     * trace can never disagree with the stored score. Pure, side-effect-free.
     */
    fun subScoreLine(
        tstSeconds: Double, inBedSeconds: Double, efficiency: Double, restorativeSeconds: Double,
        needHours: Double, consistency: Double?, deepSeconds: Double?,
        groupFragments: Int, groupInBedSeconds: Double,
    ): String {
        fun clamp01(x: Double) = maxOf(0.0, minOf(1.0, x))
        fun r2(x: Double) = Math.round(x * 100.0) / 100.0
        val needSeconds = maxOf(needHours, 0.1) * 3600.0
        val durationScore = clamp01(tstSeconds / needSeconds)
        val efficiencyScore = clamp01(efficiency)
        val deepFactor = if (deepSeconds != null && tstSeconds > 0 && deepShareTarget > 0) {
            val adequacy = clamp01((deepSeconds / tstSeconds) / deepShareTarget)
            deepFloorFactor + (1.0 - deepFloorFactor) * adequacy
        } else 1.0
        val restorativeScore = if (tstSeconds > 0)
            clamp01((restorativeSeconds / tstSeconds) / restorativeTargetShare) * deepFactor else 0.0
        val consistencyScore = clamp01(consistency ?: NEUTRAL_CONSISTENCY)
        // Pull the composite from the real scorer (whoop-rs) so the trace can't diverge from the stored
        // score. It takes deep + REM separately; restorative = deep + REM, so REM = restorative - deep.
        val composite = RustScores.rest(
            asleepSeconds = tstSeconds, efficiency = efficiency,
            deepSeconds = deepSeconds ?: 0.0,
            remSeconds = restorativeSeconds - (deepSeconds ?: 0.0),
            sleepNeedHours = needHours, consistency = consistency,
        ) ?: 0.0
        return "rest composite=${r2(composite)} " +
            "dur=${r2(durationScore)}*wDur=$wDuration " +
            "eff=${r2(efficiencyScore)}*wEff=$wEfficiency " +
            "restor=${r2(restorativeScore)}*wRestor=$wRestorative deepFactor=${r2(deepFactor)} " +
            "consist=${r2(consistencyScore)}*wConsist=$wConsistency " +
            "group=$groupFragments groupInBedMin=${(groupInBedSeconds / 60).toInt()}"
    }

    /**
     * Rest composite [0,100] derived from a persisted [DailyMetric] (the pass-2/display path — raw
     * streams are gone but the night's totals remain). null when there's no sleep. Single source of
     * truth so the persisted sleep_performance series and the Charge "Rest quality" term agree.
     */
    fun restFromDaily(daily: DailyMetric, consistency: Double? = null): Double? {
        val tstMin = daily.totalSleepMin ?: return null
        val eff = daily.efficiency ?: return null
        if (tstMin <= 0.0) return null
        return RustScores.rest(
            asleepSeconds = tstMin * 60.0,
            efficiency = eff,
            deepSeconds = (daily.deepMin ?: 0.0) * 60.0,
            remSeconds = (daily.remMin ?: 0.0) * 60.0,
            sleepNeedHours = daily.sleepNeedHours,
            consistency = daily.sleepConsistency ?: consistency,
        )
    }
}

package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrRow
import com.noop.data.HrSample
import com.noop.data.RrInterval
import com.noop.data.Spo2Sample
import com.noop.data.StepSample
import uniffi.whoop_ffi.WindowedStressInfo
import uniffi.whoop_ffi.NapConfigInfo
import uniffi.whoop_ffi.NapVerdictInfo
import com.noop.protocol.RawImuSample
import uniffi.whoop_ffi.WorkoutGravitySample
import uniffi.whoop_ffi.DebtNightInput
import uniffi.whoop_ffi.DriverBaselineInfo
import uniffi.whoop_ffi.DriverRow
import uniffi.whoop_ffi.CorrelationStrength
import uniffi.whoop_ffi.DebtSeverity
import uniffi.whoop_ffi.RecoveryState
import uniffi.whoop_ffi.FitnessAgeInfo
import uniffi.whoop_ffi.HourPointInfo
import uniffi.whoop_ffi.HrRecoveryInfo
import uniffi.whoop_ffi.HrTick
import uniffi.whoop_ffi.HrZoneSetInfo
import uniffi.whoop_ffi.RecoveryDrivers
import uniffi.whoop_ffi.RrBeat
import uniffi.whoop_ffi.RrRun
import uniffi.whoop_ffi.SleepSegment
import uniffi.whoop_ffi.SleepSpanMsInfo
import uniffi.whoop_ffi.SleepStepSample
import uniffi.whoop_ffi.Spo2RawSample
import uniffi.whoop_ffi.Spo2Span
import uniffi.whoop_ffi.StrainMethod
import uniffi.whoop_ffi.StressComponentsInfo
import uniffi.whoop_ffi.StressDayInfo
import uniffi.whoop_ffi.TimeInZoneInfo

/**
 * Bridge from the app's Room/stream types to the whoop-rs Tier-1 derived-score FFI (physio-algo, via
 * uniffi). Sibling of [RustSleepStager]: it maps [HrSample]/[RrInterval]/baseline drivers into the FFI
 * record types, calls the exported score fn (fully qualified so a member never shadows the FFI symbol),
 * and returns the value the Kotlin scorer would have stored.
 */
internal object RustScores {

    // ── mappers ──────────────────────────────────────────────────────────────

    private fun hrTicks(hr: List<HrSample>): List<HrTick> =
        hr.map { HrTick(it.ts, it.bpm) }

    private fun baseline(b: RecoveryScorer.DriverBaseline?): DriverBaselineInfo? =
        b?.let { DriverBaselineInfo(it.mean, it.spread) }

    /** Folds consecutive same-`ts` R-R into one run, preserving intra-second beat order. Clamps each rrMs
     *  into UShort range rather than dropping it: an out-of-range beat still reaches the whoop-rs clean,
     *  which drops it WITH a contiguity break, instead of splicing its neighbours into a spurious pair. */
    private fun groupRuns(rr: List<RrInterval>): List<RrRun> {
        val out = ArrayList<RrRun>()
        for (r in rr) {
            val ms = r.rrMs.coerceIn(0, UShort.MAX_VALUE.toInt()).toUShort()
            val last = out.lastOrNull()
            if (last != null && last.unix == r.ts.toUInt()) last.rr = last.rr + ms
            else out.add(RrRun(r.ts.toUInt(), listOf(ms)))
        }
        return out
    }

    private fun method(m: StrainScorer.Method): StrainMethod = when (m) {
        StrainScorer.Method.EDWARDS -> StrainMethod.EDWARDS
        StrainScorer.Method.BANISTER -> StrainMethod.BANISTER
    }

    // ── Recovery / Charge ────────────────────────────────────────────────────

    fun recovery(
        hrv: Double,
        rhr: Double,
        resp: Double?,
        hrvBaseline: RecoveryScorer.DriverBaseline?,
        rhrBaseline: RecoveryScorer.DriverBaseline?,
        respBaseline: RecoveryScorer.DriverBaseline?,
        sleepPerf: Double?,
        skinTempDev: Double? = null,
        hrvBaselineUsable: Boolean = true,
        recoveryIndexSlope: Double? = null,
        effortBaseline: RecoveryScorer.DriverBaseline? = null,
        priorDayEffort: Double? = null,
    ): Double? = uniffi.whoop_ffi.recoveryScore(
        RecoveryDrivers(
            hrv = hrv,
            rhr = rhr,
            resp = resp,
            hrvBaseline = baseline(hrvBaseline),
            rhrBaseline = baseline(rhrBaseline),
            respBaseline = baseline(respBaseline),
            sleepPerf = sleepPerf,
            skinTempDev = skinTempDev,
            hrvBaselineUsable = hrvBaselineUsable,
            recoveryIndexSlope = recoveryIndexSlope,
            effortBaseline = baseline(effortBaseline),
            priorDayEffort = priorDayEffort,
        ),
    )

    /**
     * [BaselineState] convenience overload: converts each [BaselineState] to a [RecoveryScorer.DriverBaseline],
     * derives the cold-start gate from `hrvBaseline.usable`, then delegates to the FFI path above. Called by
     * [AnalyticsEngine], [IntelligenceEngine.recomputeRecovery], [WatchRecovery] and [RecoveryScorerTrace].
     */
    fun recovery(
        hrv: Double,
        rhr: Double,
        resp: Double?,
        hrvBaseline: BaselineState,
        rhrBaseline: BaselineState?,
        respBaseline: BaselineState?,
        sleepPerf: Double?,
        skinTempDev: Double? = null,
        recoveryIndexSlope: Double? = null,
        effortBaseline: BaselineState? = null,
        priorDayEffort: Double? = null,
    ): Double? = recovery(
        hrv = hrv,
        rhr = rhr,
        resp = resp,
        hrvBaseline = RecoveryScorer.DriverBaseline(hrvBaseline),
        rhrBaseline = rhrBaseline?.let { RecoveryScorer.DriverBaseline(it) },
        respBaseline = respBaseline?.let { RecoveryScorer.DriverBaseline(it) },
        sleepPerf = sleepPerf,
        skinTempDev = skinTempDev,
        hrvBaselineUsable = hrvBaseline.usable,
        recoveryIndexSlope = recoveryIndexSlope,
        effortBaseline = effortBaseline?.let { RecoveryScorer.DriverBaseline(it) },
        priorDayEffort = priorDayEffort,
    )

    fun band(score: Double): String = uniffi.whoop_ffi.recoveryBand(score)

    /** The five-way Charge state a score falls in. Only the cut points are read here; the word and
     *  the colour belong to the UI. Finer than [band], which carries the three colour bands. */
    fun state(score: Double): RecoveryState = uniffi.whoop_ffi.recoveryState(score)

    /**
     * The per-driver breakdown behind [recovery], scored from the SAME record: one row per term the
     * score actually used, each carrying its marginal whole-point swing and how it read against the
     * personal baseline, biggest mover first. Empty exactly where [recovery] returns null. The label,
     * value text and wording for each row are the caller's.
     */
    fun chargeDriverRows(
        hrv: Double,
        rhr: Double,
        resp: Double?,
        hrvBaseline: BaselineState,
        rhrBaseline: BaselineState?,
        respBaseline: BaselineState?,
        sleepPerf: Double?,
        skinTempDev: Double? = null,
        recoveryIndexSlope: Double? = null,
        effortBaseline: BaselineState? = null,
        priorDayEffort: Double? = null,
    ): List<DriverRow> = uniffi.whoop_ffi.recoveryDriverRows(
        RecoveryDrivers(
            hrv = hrv,
            rhr = rhr,
            resp = resp,
            hrvBaseline = baseline(RecoveryScorer.DriverBaseline(hrvBaseline)),
            rhrBaseline = rhrBaseline?.let { baseline(RecoveryScorer.DriverBaseline(it)) },
            respBaseline = respBaseline?.let { baseline(RecoveryScorer.DriverBaseline(it)) },
            sleepPerf = sleepPerf,
            skinTempDev = skinTempDev,
            hrvBaselineUsable = hrvBaseline.usable,
            recoveryIndexSlope = recoveryIndexSlope,
            effortBaseline = effortBaseline?.let { baseline(RecoveryScorer.DriverBaseline(it)) },
            priorDayEffort = priorDayEffort,
        ),
    )

    fun recoveryIndexSlope(hr: List<HrSample>, start: Long, end: Long): Double? =
        uniffi.whoop_ffi.recoveryIndexSlope(hrTicks(hr), start, end)

    fun bankedNights(nightlyHrv: List<Double?>): Int =
        uniffi.whoop_ffi.recoveryBankedNights(nightlyHrv).toInt()

    // ── Day Strain / Effort ──────────────────────────────────────────────────

    fun strain(
        hr: List<HrSample>,
        maxHR: Double?,
        restingHR: Double,
        method: StrainScorer.Method,
        sex: String,
        denominator: Double,
    ): Double? = uniffi.whoop_ffi.strainScore(hrTicks(hr), maxHR, restingHR, method(method), sex, denominator)

    fun strainDenominator(): Double = uniffi.whoop_ffi.strainDefaultDenominator()

    // ── HRV / RMSSD ──────────────────────────────────────────────────────────

    fun rmssdGapAware(rr: List<RrInterval>): Double? = uniffi.whoop_ffi.hrvRmssdGapAware(groupRuns(rr))

    fun rmssd(rrMs: List<Int>): Double? = uniffi.whoop_ffi.hrvRmssd(rrMs.map { it.toUShort() })

    fun rangeFilterRR(rrMs: List<Int>): List<Int> =
        uniffi.whoop_ffi.hrvRangeFilter(rrMs.map { it.toUShort() }).map { it.toInt() }

    fun rmssdRaw(nn: List<Double>): Double? = uniffi.whoop_ffi.hrvRmssdPlain(nn.map { it.toInt().toUShort() })

    /** Range + Malik-ectopic cleaned NN series (ms), in input order. Ungated: no clean-beat floor, so a
     *  caller that needs the survivors themselves gets them even where [analyzeRaw] would refuse. */
    fun cleanRR(rrMs: List<Double>): List<Double> =
        uniffi.whoop_ffi.hrvCleanRr(rrMs.map { it.toInt().toUShort() }).map { it.toDouble() }

    /** Survivor counts for one cleaning pass (input / after range / after ectopic) — the numbers a
     *  cleaning trace narrates, available where [analyzeRaw] reports zero because a gate refused. */
    fun cleanCounts(rrMs: List<Double>): uniffi.whoop_ffi.HrvCleanCountsInfo =
        uniffi.whoop_ffi.hrvCleanCounts(rrMs.map { it.toInt().toUShort() })

    /** The whoop-rs cleaning tuning: R-R bounds, clean-beat floor, Malik threshold and window, spot
     *  rejected-fraction ceiling, rolling-trace window. Read once; whoop-rs owns the values. */
    val hrvCleanCfg: uniffi.whoop_ffi.HrvCleanCfgInfo by lazy { uniffi.whoop_ffi.hrvCleanCfg() }

    /** Per-5-min-bucket clean-beat count + gap-aware RMSSD over [start, end] — the breakdown behind
     *  [windowedAvgHrv], for a caller that tags each bucket with the sleep stage at its centre. */
    fun windowedBuckets(start: Long, end: Long, rr: List<RrInterval>): List<uniffi.whoop_ffi.HrvBucketInfo> =
        uniffi.whoop_ffi.hrvWindowedBuckets(start.toUInt(), end.toUInt(), groupRuns(rr))

    /** Rolling trailing-window RMSSD over a timestamped R-R series, as `(windowEndTs, rmssd)`. A
     *  [stepSec] above 0 thins emission to one point per that many seconds of advance. */
    fun rollingRmssd(
        rr: List<RrInterval>,
        windowSec: Int = hrvCleanCfg.rollingWindowSecs.toInt(),
        stepSec: Int = 0,
        minBeatsPerWindow: Int = 8,
    ): List<Pair<Long, Double>> =
        uniffi.whoop_ffi.hrvRollingRmssd(
            rr.map { RrBeat(it.ts, it.rrMs.coerceIn(0, UShort.MAX_VALUE.toInt()).toUShort()) },
            windowSec.toLong(), stepSec.toLong(), minBeatsPerWindow.toUInt(),
        ).map { it.ts to it.rmssd }

    /** Total beat-time over the wall-clock span of the same beats. Over ~1.0 is physically impossible, so
     *  it flags double-counted or overlapping R-R. 0.0 for under two timestamps or a non-positive span. */
    fun rrCoverage(tsSec: List<Long>, rrMs: List<Double>): Double =
        uniffi.whoop_ffi.hrvRrCoverage(tsSec, rrMs)

    /** Rows repeating an earlier (ts, rrMs) exactly. Byte-identical re-inserts only; the value-differing
     *  report overlap is counted by [overlappingReports] instead. */
    fun duplicateBeatCount(tsSec: List<Long>, rrMs: List<Double>): Int =
        uniffi.whoop_ffi.hrvDuplicateBeatCount(tsSec, rrMs).toInt()

    /** `(overlapping, total)` per-second reports, an overlapping one re-reporting time already covered.
     *  The mechanism behind an [rrCoverage] above 1.0, which [duplicateBeatCount] cannot see. */
    fun overlappingReports(rr: List<RrInterval>): Pair<Int, Int> =
        uniffi.whoop_ffi.hrvOverlappingReports(groupRuns(rr)).let { it.overlapping.toInt() to it.total.toInt() }

    /** Full clean-and-analyze (RMSSD/SDNN/pNN50/meanNN + input/clean counts) into [HrvResult]. R-R are
     *  integer ms (u16 on the wire); range filter, Malik ectopic, the clean-beat floor and the spot
     *  rejected-fraction gate all live in whoop-rs. `nInput` = the raw count. */
    fun analyzeRaw(rawRR: List<Double>, maxRejectedFraction: Double? = null): HrvResult {
        val info = uniffi.whoop_ffi.hrvAnalyzeRaw(rawRR.map { it.toInt().toUShort() }, maxRejectedFraction)
        return HrvResult(
            rmssd = info.rmssd,
            sdnn = info.sdnn,
            meanNN = info.meanNn,
            pnn50 = info.pnn50,
            nInput = info.nInput.toInt(),
            nClean = info.nClean.toInt(),
        )
    }

    /** Windowed session avgHrv (ms): mean of per-5-min-bucket gap-aware RMSSD over [start, end] — the stored
     *  DailyMetric.avgHrv/SleepSession.avgHrv (twin of SleepStager.sessionAvgHRV). `rr` must be ts-sorted. */
    fun windowedAvgHrv(start: Long, end: Long, rr: List<RrInterval>): Double? =
        uniffi.whoop_ffi.hrvWindowedAvg(start.toUInt(), end.toUInt(), groupRuns(rr))

    // ── Resting HR ───────────────────────────────────────────────────────────

    fun sessionRestingHr(hr: List<HrSample>, start: Long, end: Long): Int? =
        uniffi.whoop_ffi.sessionRestingHr(start, end, hrTicks(hr))

    fun dailyRestingHr(sessionFloors: List<Int?>): Int? = uniffi.whoop_ffi.dailyRestingHr(sessionFloors)

    // ── HR recovery ──────────────────────────────────────────────────────────

    /** HR recovery for a workout window: the bpm drop 1/2/5 min after a sustained high-intensity bout,
     *  computed in whoop-rs. Null when ineligible or lacking post-workout coverage. */
    fun hrRecovery(hr: List<HrSample>, workoutStart: Long, workoutEnd: Long, maxHr: Double): HrRecoveryInfo? =
        uniffi.whoop_ffi.hrRecoveryCalculate(hrTicks(hr), workoutStart, workoutEnd, maxHr)

    // ── Respiratory rate ─────────────────────────────────────────────────────

    fun respRateFromRr(rr: List<RrInterval>, start: Long, end: Long): Double? =
        uniffi.whoop_ffi.respRateFromRr(
            // Drop physiologically out-of-range beats up front (the same bounds the range filter applies)
            // so an rrMs that would wrap on the UShort cast is dropped, not aliased into range.
            rr.asSequence()
                .filter { it.rrMs in hrvCleanCfg.rrMinMs.toInt()..hrvCleanCfg.rrMaxMs.toInt() }
                .map { RrBeat(it.ts, it.rrMs.toUShort()) }
                .toList(),
            start,
            end,
        )

    // ── Baevsky Stress Index ─────────────────────────────────────────────────

    fun stressIndex(rr: List<RrInterval>): Double? = uniffi.whoop_ffi.stressIndex(rr.map { it.rrMs.toDouble() })

    fun stressComponents(rr: List<RrInterval>): StressComponentsInfo? =
        uniffi.whoop_ffi.stressComponents(rr.map { it.rrMs.toDouble() })

    // ── SpO2 (4.0 paired red/IR) ─────────────────────────────────────────────

    /** Blood oxygen % from a 4.0 night's paired red/IR ADC via ratio-of-ratios, over in-bed samples only.
     *  The 4.0 counterpart to the 5.0/MG strap-computed percent; both bank DailyMetric.spo2Pct. Null when
     *  the channel isn't pulsatile enough to carry a ratio — the usual case on this hardware. */
    /** The multi-night 4.0 blood-oxygen readout: anchors the 30-night median and reports the 7-night
     *  median at that offset, so the night-to-night movement survives a per-device DC offset the
     *  absolute value cannot. [recentNightly] is oldest to newest. */
    fun spo2RollingReading(recentNightly: List<Double>): uniffi.whoop_ffi.Spo2Rolling =
        uniffi.whoop_ffi.spo2RollingReading(recentNightly)

    fun spo2PercentFromPaired(sessions: List<DetectedSleep>, spo2: List<Spo2Sample>): Double? {
        if (sessions.isEmpty() || spo2.isEmpty()) return null
        val inBed = spo2.filter { s -> sessions.any { s.ts in it.start..it.end } }
        if (inBed.isEmpty()) return null
        return uniffi.whoop_ffi.spo2FromPaired(
            inBed.map { it.red.toDouble() },
            inBed.map { it.ir.toDouble() },
        )
    }

    /** Nightly integer-truncated raw red/IR ADC means over the detected in-bed [sessions] — the stored
     *  DailyMetric.spo2Red/spo2Ir (twin of AnalyticsEngine.nightlySpo2RawMeans). Never a calibrated percent. */
    fun nightlySpo2RawMeans(sessions: List<DetectedSleep>, spo2: List<Spo2Sample>): Pair<Int, Int>? =
        uniffi.whoop_ffi.nightlySpo2RawMeans(
            sessions.map { Spo2Span(it.start, it.end) },
            spo2.map { Spo2RawSample(it.ts, it.red, it.ir) },
        )?.let { it.red to it.ir }

    // ── HR zones + time-in-zone ──────────────────────────────────────────────

    fun hrZonesForAge(age: Double, maxHrOverride: Double? = null): HrZoneSetInfo =
        uniffi.whoop_ffi.hrZonesForAge(age, maxHrOverride)

    fun hrTimeInZone(hr: List<HrSample>, age: Double, maxHrOverride: Double? = null): TimeInZoneInfo =
        uniffi.whoop_ffi.hrTimeInZone(hrTicks(hr), age, maxHrOverride)

    // ── VO2max / Fitness Age ─────────────────────────────────────────────────

    fun vo2maxEstimate(
        age: Double,
        sex: String,
        waistCm: Double,
        restingHr: Double,
        paIndex: Double,
    ): Double = uniffi.whoop_ffi.vo2maxEstimate(age, sex, waistCm, restingHr, paIndex)

    fun fitnessAgeCompute(
        age: Double,
        sex: String,
        restingHr: Double,
        paIndex: Double,
        waistCm: Double? = null,
        lowerConfidence: Boolean = false,
    ): FitnessAgeInfo? = uniffi.whoop_ffi.fitnessAgeCompute(age, sex, restingHr, paIndex, waistCm, lowerConfidence)

    // ── Stress onset detector ──────────────────────────────────────────────────

    fun stressOnsetEvaluate(
        rrBuffer: List<Int>,
        currentHr: Double?,
        recentMotionG: Double?,
        sessionActive: Boolean,
        state: uniffi.whoop_ffi.OnsetStateInfo,
        enabled: Boolean,
        autoNudge: Boolean,
        quietHoursEnabled: Boolean,
        quietStartMin: Int,
        quietEndMin: Int,
        nowSec: Long,
        tzOffsetSec: Long,
    ): uniffi.whoop_ffi.OnsetDecisionInfo = uniffi.whoop_ffi.stressOnsetEvaluate(
        rrBuffer.map { it.toUShort() }, currentHr, recentMotionG, sessionActive,
        state, enabled, autoNudge, quietHoursEnabled, quietStartMin, quietEndMin, nowSec, tzOffsetSec,
    )

    // ── Deep-sleep HRV window ────────────────────────────────────────────────

    /** Session avgHrv (ms) from 5-min buckets whose centre falls in deep-sleep spans. */
    fun windowedAvgHrvDeep(start: Long, end: Long, rr: List<RrInterval>, segments: List<SleepSegment>): Double? =
        uniffi.whoop_ffi.hrvWindowedAvgDeep(start.toUInt(), end.toUInt(), groupRuns(rr.sortedBy { it.ts }), segments)

    // ── Steps (5/MG cumulative motion counter) ───────────────────────────────

    /** Raw wrap-aware motion-tick total over [samples] (5/MG `step_motion_counter@57`). `null` for
     *  <2 samples or no forward movement (so "no data" stays distinct from a real zero). The caller
     *  applies its `stepTicksPerStep` calibration. */
    fun steps(samples: List<StepSample>): Int? =
        uniffi.whoop_ffi.stepsCounter(
            samples.map { SleepStepSample(it.ts, it.counter.toUShort(), it.activityClass?.toUByte()) },
        )?.toInt()

    // ── Calories (whole-day HR estimate) ─────────────────────────────────────

    /** APPROXIMATE whole-day energy (kcal) from the day's HR. Applies the profile / hrmax / resting
     *  fallbacks, then delegates the Keytel active + Harris-Benedict BMR model to whoop-rs. */
    fun caloriesDay(hr: List<HrSample>, profile: UserProfile, hrmax: Double?, restingHR: Double?): Double =
        uniffi.whoop_ffi.caloriesEstimateDay(
            hrTicks(hr),
            if (profile.weightKg > 0) profile.weightKg else 70.0,
            if (profile.heightCm > 0) profile.heightCm else 170.0,
            if (profile.age > 0) profile.age else 30.0,
            profile.sex,
            hrmax ?: 220.0,
            restingHR ?: 60.0,
        )

    // ── Rest (sleep performance composite) ───────────────────────────────────

    /** Rest (sleep performance) composite [0,100]. `null` when no asleep time. Absent `sleepNeedHours`
     *  -> 8 h, absent `consistency` -> neutral 0.5 (both resolved in whoop-rs). */
    fun rest(
        asleepSeconds: Double,
        efficiency: Double,
        deepSeconds: Double,
        remSeconds: Double,
        sleepNeedHours: Double? = null,
        consistency: Double? = null,
    ): Double? = uniffi.whoop_ffi.restScore(asleepSeconds, efficiency, deepSeconds, remSeconds, sleepNeedHours, consistency)

    // ── Sleep debt ledger ────────────────────────────────────────────────────

    /** Rolling sleep-debt ledger. Maps the whoop-rs `DebtLedgerInfo` back to the Kotlin [SleepDebtLedger]
     *  the UI consumes; no-data nights are skipped, never zero-filled. */
    fun sleepDebtLedger(
        series: List<Pair<String, Double?>>,
        needHours: Double = RestScorer.defaultSleepNeedHours,
        window: Int = SleepDebt.DEFAULT_WINDOW_NIGHTS,
    ): SleepDebtLedger {
        val info = uniffi.whoop_ffi.sleepDebtLedger(
            series.map { DebtNightInput(it.first, it.second) },
            needHours,
            window.coerceAtLeast(1).toUInt(),
        )
        return SleepDebtLedger(
            balanceMin = info.balanceMin,
            nights = info.nights.map { SleepDebtNight(day = it.day, sleptMin = it.sleptMin, deltaMin = it.deltaMin) },
            needMin = info.needMin,
        )
    }

    // ── Daily autonomic stress (RHR + HRV vs baseline) ───────────────────────

    /** Daily autonomic stress (0-3) for `today` against a `baseline` of prior `(rhr, hrv)` days. `null`
     *  under the 14-day baseline floor or with no usable signal. whoop-rs owns the mean/SD + z + logistic. */
    fun dailyStress(rhr: Double?, hrv: Double?, baseline: List<Pair<Double?, Double?>>): Double? =
        uniffi.whoop_ffi.dailyStress(
            StressDayInfo(rhr, hrv),
            baseline.map { StressDayInfo(it.first, it.second) },
        )

    // ── Windowed autonomic stress (per-hour activation, day + night) ─────────

    /** Score waking-hour aggregates for autonomic activation. Returns the whoop-rs `WindowedStressInfo`
     *  (scored hours + mean + peak hour + trailing high run + band minutes + the buckets held out and
     *  why); the caller reassembles its timeline. Buckets overlapping `sleepSpans` (wall-clock `[start,
     *  end)` ms) and buckets over the motion gate are dropped BEFORE the day's calm reference is built,
     *  so the caller says which window to ask for and whoop-rs owns the gate. */
    fun daytimeStress(hours: List<HourPointInfo>, sleepSpans: List<SleepSpanMsInfo>): WindowedStressInfo =
        uniffi.whoop_ffi.daytimeStress(hours, sleepSpans)

    /** Score one night's hourly aggregates on the same formula and bands — twin of [daytimeStress] with
     *  no hour-of-day filter, so the caller passes ONLY the buckets inside the sleep span. Feeds the
     *  sleep-stress card and the fourth sleep-performance driver. */
    fun sleepStress(hours: List<HourPointInfo>): WindowedStressInfo =
        uniffi.whoop_ffi.sleepStress(hours)

    // ── Frequency-domain HRV (Lomb-Scargle LF/HF) ────────────────────────────

    /** Frequency-domain HRV bands (LF/HF/LF-HF/total power, ms²) over a time-ordered R-R series (ms) —
     *  twin of [HrvFreqDomain.freqDomainRaw]. Range + Malik-ectopic clean, tachogram build, and the 60 s
     *  HF / 250 s LF span gates + 20-beat floor live in whoop-rs; `null` below either gate. */
    fun freqDomain(rawRR: List<Double>): HrvFreqDomain.Bands? =
        uniffi.whoop_ffi.hrvFreqDomain(rawRR.map { it.toInt().toUShort() })?.let {
            HrvFreqDomain.Bands(lf = it.lf, hf = it.hf, lfhf = it.lfhf, totalPower = it.totalPower)
        }

    // ── Short-nap detection ──────────────────────────────────────────────────

    /** Tri-state nap verdict over a candidate window — twin of [NapDetector.evaluate]. The dense-gravity
     *  eligibility, the longest-quiet-run and the length + HR-settled gates all live in whoop-rs. */
    fun napEvaluate(gravity: List<GravitySample>, hr: List<HrRow>, restingHr: Int?, config: NapConfig): NapDecision {
        val info = uniffi.whoop_ffi.napEvaluate(
            gravity.map { WorkoutGravitySample(it.ts, it.x, it.y, it.z) },
            hr.map { HrTick(it.ts, it.bpm) },
            restingHr,
            NapConfigInfo(
                enabled = config.enabled,
                minNapMinutes = config.minNapMinutes,
                maxNapMinutes = config.maxNapMinutes,
                stillThresholdG = config.stillThresholdG,
                hrSettleMarginBpm = config.hrSettleMarginBpm,
                smoothWindowSeconds = config.smoothWindowSeconds,
            ),
        )
        val verdict = when (info.verdict) {
            NapVerdictInfo.NAP -> NapVerdict.NAP
            NapVerdictInfo.NONE -> NapVerdict.NONE
            NapVerdictInfo.INCONCLUSIVE -> NapVerdict.INCONCLUSIVE
        }
        return NapDecision(
            verdict = verdict,
            candidate = info.candidate?.let {
                NapCandidate(start = it.start, end = it.end, meanHr = it.meanHr, confidence = it.confidence)
            },
        )
    }

    /** Body-clock phase from raw (unix, motion) samples + tz offset; whoop-rs bins them per local hour.
     *  Null when the fit is degenerate. */
    fun circadianPhase(
        samples: List<uniffi.whoop_ffi.ActivitySample>,
        tzOffsetSeconds: Long,
        daysObserved: Int,
        habitualWakeHour: Double,
        observedTempMinHour: Double?,
    ): uniffi.whoop_ffi.PhaseEstimateInfo? =
        uniffi.whoop_ffi.circadianPhaseFromSamples(
            samples, tzOffsetSeconds, daysObserved.toUInt(), habitualWakeHour, observedTempMinHour)

    /** Circadian Rhythm Age (relative) from raw (unix, motion) samples + tz offset + age + sex; whoop-rs
     *  bins per local hour, fits the cosinor, and applies the Gompertz transform. */
    fun rhythmAge(
        samples: List<uniffi.whoop_ffi.ActivitySample>,
        tzOffsetSeconds: Long,
        chronologicalAge: Double,
        sex: uniffi.whoop_ffi.SexInput,
    ): uniffi.whoop_ffi.RhythmAgeInfo? =
        uniffi.whoop_ffi.rhythmAgeFromSamples(samples, tzOffsetSeconds, chronologicalAge, sex)

    /** Personal sleep need (hours) = mean of recent nightly asleep hours, floored at 7.5. For the Rest score. */
    fun personalSleepNeedHours(recentAsleepHours: List<Double>): Double =
        uniffi.whoop_ffi.personalSleepNeedHours(recentAsleepHours)

    /** [personalSleepNeedHours] in MINUTES, the unit the sleep tiles, debt ledger and trends carry. */
    fun personalSleepNeedMinutes(recentAsleepMinutes: List<Double>): Double =
        personalSleepNeedHours(recentAsleepMinutes.map { it / 60.0 }) * 60.0

    /** HRV readiness over a nightly RMSSD series (oldest first): the log-domain 7-night baseline against
     *  the personal normal band. Null while calibrating (under 3 valid nights). */
    fun hrvReadiness(nightlyRmssd: List<Double?>): uniffi.whoop_ffi.HrvReadinessInfo? =
        uniffi.whoop_ffi.hrvReadiness(nightlyRmssd)

    /** Sample SD (ddof=1) of an NN series (ms); null under 2 beats. No filtering — the raw spread. */
    fun sdnnRaw(nn: List<Double>): Double? =
        uniffi.whoop_ffi.hrvSdnn(nn.map { it.toInt().toUShort() })

    /** pNN50 (%) over an already-clean NN series, every successive pair counted. The formula alone — no
     *  cleaning, no contiguity mask. Null under 2 beats. */
    fun pnn50Raw(nn: List<Double>): Double? =
        uniffi.whoop_ffi.hrvPnn50Plain(nn.map { it.toInt().toUShort() })

    // ── Raw 6-axis IMU activity features ─────────────────────────────────────

    /** Energy / jerk / gait-band cadence over a window of 100 Hz IMU samples. */
    fun imuFeatures(samples: List<RawImuSample>, sampleRateHz: Int): ImuActivityFeatures {
        val f = uniffi.whoop_ffi.imuFeatures(
            samples.map { uniffi.whoop_ffi.ImuSampleInfo(it.ax, it.ay, it.az, it.gx, it.gy, it.gz) },
            sampleRateHz,
        )
        return ImuActivityFeatures(
            accelEnergyG = f.accelEnergyG,
            gyroEnergyDps = f.gyroEnergyDps,
            jerkRms = f.jerkRms,
            cadenceHz = f.cadenceHz,
            cadenceStrength = f.cadenceStrength,
            sampleCount = f.sampleCount.toInt(),
        )
    }

    // ── Personal baselines ───────────────────────────────────────────────────

    /** Replay an ordered nightly series (oldest first) into a baseline state; nulls skip-and-hold. */
    fun baselineFoldHistory(values: List<Double?>, cfg: MetricCfg): BaselineState {
        val r = uniffi.whoop_ffi.baselineFoldHistory(values, metricCfgInfo(cfg))
        return BaselineState(
            baseline = r.baseline, spread = r.spread, nValid = r.nValid,
            nightsSinceUpdate = r.nightsSinceUpdate,
            status = BaselineStatus.entries.first { it.raw == r.status },
        )
    }

    // ── Workout detection ────────────────────────────────────────────────────

    /** Detect workout bouts from the HR + gravity streams. Bout strain, zone breakdown and the
     *  Keytel/BMR calorie estimate are all computed in whoop-rs alongside the detection. */
    fun workoutDetect(
        hr: List<HrSample>,
        gravity: List<GravitySample>,
        restingHR: Double?,
        maxHR: Double?,
        age: Double?,
        profile: UserProfile?,
    ): List<ExerciseSession> =
        uniffi.whoop_ffi.workoutDetect(
            hrTicks(hr),
            gravity.map { WorkoutGravitySample(it.ts, it.x, it.y, it.z) },
            restingHR,
            maxHR,
            age,
            profile?.weightKg ?: 0.0,
            profile?.heightCm ?: 0.0,
            profile?.sex ?: "",
        ).map { w ->
            ExerciseSession(
                start = w.start,
                end = w.end,
                avgHR = w.avgHr,
                peakHR = w.peakHr,
                strain = w.strain,
                durationS = w.durationS,
                zoneTimePct = w.zoneTime.associate { it.zone to it.pct },
                avgHRRPct = w.avgHrrPct,
                hrmax = w.hrmax,
                hrmaxSource = w.hrmaxSource,
                caloriesKcal = w.caloriesKcal,
                caloriesKJ = w.caloriesKj,
            )
        }

    /** Bout energy `(kcal, kJ)`. Profile gaps fall back to the neutral 70 kg / 170 cm / 30 y figures. */
    fun caloriesBout(
        hr: List<HrSample>,
        profile: UserProfile,
        hrmax: Double?,
        restingHR: Double?,
    ): Pair<Double, Double> {
        val out = uniffi.whoop_ffi.caloriesEstimateBout(
            hrTicks(hr),
            if (profile.weightKg > 0) profile.weightKg else 70.0,
            if (profile.heightCm > 0) profile.heightCm else 170.0,
            if (profile.age > 0) profile.age else 30.0,
            profile.sex,
            hrmax ?: 190.0,
            restingHR ?: 60.0,
        )
        return out[0] to out[1]
    }

    /** Trailing rolling mean of the motion intensities over `windowS` seconds. */
    fun smoothedIntensity(motion: List<ActivityPoint>, windowS: Double): List<Double> =
        uniffi.whoop_ffi.smoothedIntensity(
            motion.map { uniffi.whoop_ffi.ActivityPointInfo(it.ts, it.intensity) }, windowS,
        )

    /** Per-record motion intensity from a gravity stream — the shared spine for sedentary + nap reads. */
    fun activitySeries(gravity: List<GravitySample>): List<ActivityPoint> =
        uniffi.whoop_ffi.activitySeries(
            gravity.map { WorkoutGravitySample(it.ts, it.x, it.y, it.z) },
        ).map { ActivityPoint(ts = it.ts, intensity = it.intensity) }

    // ── Vitality / Body Age ──────────────────────────────────────────────────

    /** Vitality (0-100) + Body Age from the wearable drivers; null below three present drivers. */
    fun vitality(inputs: VitalityEngine.Inputs): VitalityEngine.Result? =
        uniffi.whoop_ffi.vitalityCompute(
            inputs.chronoAge, inputs.restingHR, inputs.vo2max, inputs.expectedVO2max,
            inputs.sleepHours, inputs.sleepRegularityIndex, inputs.sleepConsistency,
            inputs.rmssd, inputs.rmssdNorm, inputs.steps,
        )?.let { r ->
            VitalityEngine.Result(
                vitality = r.vitality,
                bodyAge = r.bodyAge,
                chronoAge = r.chronoAge,
                advanceYears = r.advanceYears,
                bandYears = r.bandYears,
                contributions = r.contributions.map {
                    VitalityEngine.Contribution(it.key, it.label, it.lnHazard)
                },
                factorsUsed = r.factorsUsed.toInt(),
            )
        }

    /** Each present driver's signed log-hazard, ungated — the "why" behind a reading. */
    fun vitalityContributions(inputs: VitalityEngine.Inputs): List<VitalityEngine.Contribution> =
        uniffi.whoop_ffi.vitalityContributions(
            inputs.chronoAge, inputs.restingHR, inputs.vo2max, inputs.expectedVO2max,
            inputs.sleepHours, inputs.sleepRegularityIndex, inputs.sleepConsistency,
            inputs.rmssd, inputs.rmssdNorm, inputs.steps,
        ).map { VitalityEngine.Contribution(it.key, it.label, it.lnHazard) }

    /** Nocturnal RMSSD age norm (ms) — the reference the HRV driver scores against. */
    fun vitalityRmssdNorm(forAge: Double): Double = uniffi.whoop_ffi.vitalityRmssdNorm(forAge)

    /** Coverage windows from a sample series: consecutive timestamps within `maxGapSeconds` form one
     *  span. Use this rather than a day's min..max, or every mid-day gap counts as worn time. */
    fun coverageSpans(timestamps: List<Long>, maxGapSeconds: Long = 300L): List<Pair<Long, Long>> =
        uniffi.whoop_ffi.coverageSpans(timestamps, maxGapSeconds).map { it.start to it.end }

    /** Sleep Regularity Index (-100..100) over `days` days from local midnight. `asleep` are the staged
     *  sleep spans, `covered` the windows the strap was reporting; anything outside `covered` is UNKNOWN,
     *  never awake, so taking the strap off cannot read as irregularity. Null below the coverage gates. */
    fun sleepRegularityIndex(
        firstLocalMidnight: Long,
        days: Int,
        asleep: List<Pair<Long, Long>>,
        covered: List<Pair<Long, Long>>,
    ): Double? = uniffi.whoop_ffi.sleepRegularityIndex(
        firstLocalMidnight,
        days.toUInt(),
        asleep.map { uniffi.whoop_ffi.TimeSpan(it.first, it.second) },
        covered.map { uniffi.whoop_ffi.TimeSpan(it.first, it.second) },
    )

    /** Sleep regularity in [0,1] from nightly durations (hours); null below three nights. */
    fun vitalitySleepConsistency(nightlyHours: List<Double>): Double? =
        uniffi.whoop_ffi.vitalitySleepConsistency(nightlyHours)

    /** Median of a series; 0.0 when empty. The same median the algorithms use. */
    fun median(values: List<Double>): Double = uniffi.whoop_ffi.seriesMedian(values)

    /** OLS slope of a series over x = 0, 1, 2, …; 0.0 under two points or a degenerate spread. */
    fun slope(values: List<Double>): Double = uniffi.whoop_ffi.seriesSlope(values)

    /** Arithmetic mean; 0.0 when empty, so a caller that must show "no data" checks the input. */
    fun mean(values: List<Double>): Double = uniffi.whoop_ffi.seriesMean(values)

    /** Sample SD (n−1) of a series; 0.0 under two points. */
    fun sampleSD(values: List<Double>): Double = uniffi.whoop_ffi.seriesSampleSd(values)

    /** Population SD (÷n) of a series; 0.0 when empty. The per-window spread, not the baselines' n−1. */
    fun populationSD(values: List<Double>): Double = uniffi.whoop_ffi.seriesPopulationSd(values)

    /** Pearson r over two equal-length series; null under two pairs or on a flat series. */
    fun pearson(xs: List<Double>, ys: List<Double>): Double? = uniffi.whoop_ffi.seriesPearson(xs, ys)

    /** Robust z against a baseline mean + EWMA-abs-dev spread — the z the Charge drivers use. */
    fun zScore(value: Double, mean: Double, spread: Double): Double =
        uniffi.whoop_ffi.zScore(value, mean, spread)

    /**
     * Weighted trendline of [values] over [days] (day offsets, not sample index) across a
     * [windowDays]-wide request. Carries its own 80% interval, so nothing here picks a slope
     * threshold. null under three points, under the window's minimum span, or with no x-spread.
     */
    fun trendline(
        days: List<Double>,
        values: List<Double>,
        weights: List<Double> = emptyList(),
        windowDays: Double,
    ): uniffi.whoop_ffi.TrendlineInfo? =
        uniffi.whoop_ffi.seriesTrendline(days, values, weights, windowDays)

    /** Second-half mean minus first-half mean of a series; null under four points. */
    fun halfChange(values: List<Double>): Double? = uniffi.whoop_ffi.seriesHalfChange(values)

    // ── Bands and tiers (the cut points behind a word, a swatch or a ramp position) ───────────

    /** How many equal tiers a sleep-performance driver's 0-100 is read in. */
    val sleepDriverTiers: Int by lazy { uniffi.whoop_ffi.sleepDriverTiers().toInt() }

    /** Which tier a driver's 0-100 falls in, counting from 0. Off-scale values clamp into range. */
    fun sleepDriverTier(percent: Double): Int = uniffi.whoop_ffi.sleepDriverTier(percent).toInt()

    /** The tier a driver LIGHTS: mirrored when its 0 is the good end. Only the lit tier moves. */
    fun sleepDriverTierLit(percent: Double, higherIsBetter: Boolean): Int =
        uniffi.whoop_ffi.sleepDriverTierLit(percent, higherIsBetter).toInt()

    /** Where a tier sits on a 0..1 ramp, so its swatch samples the scale the value does. */
    fun sleepDriverTierPosition(tier: Int): Double =
        uniffi.whoop_ffi.sleepDriverTierPosition(tier.toUInt())

    /** How far behind a SIGNED sleep-debt balance reads; the colour for each band is the UI's. */
    fun sleepDebtSeverity(balanceMin: Double): DebtSeverity =
        uniffi.whoop_ffi.sleepDebtSeverity(balanceMin)

    /** The strength band of a Pearson r, by |r|. Each surface words the band its own way. */
    fun correlationStrength(r: Double): CorrelationStrength = uniffi.whoop_ffi.correlationStrength(r)

    /** Fewest overlapping day pairs a correlation may be shown from. */
    val correlationMinPairs: Int by lazy { uniffi.whoop_ffi.correlationMinPairs().toInt() }

    /** Anchor positions of the Charge and Effort reading ramps; the colours at them are the palette's. */
    val rampStops: uniffi.whoop_ffi.RampStopsInfo by lazy { uniffi.whoop_ffi.rampStops() }

    /** Where a 0-100 score sits on its ramp, clamped to the ends. */
    fun rampPositionScore(score: Double): Double = uniffi.whoop_ffi.rampPositionScore(score)

    /** Where an already-normalised 0..1 fraction sits on its ramp. */
    fun rampPositionFraction(fraction: Double): Double = uniffi.whoop_ffi.rampPositionFraction(fraction)

    /** Where a Pearson r sits on a ramp: −1 at the bottom, 0 at the middle, +1 at the top. */
    fun rampPositionCorrelation(r: Double): Double = uniffi.whoop_ffi.rampPositionCorrelation(r)

    // ── Tuning tables (whoop-rs owns every value; read, never copied) ─────────

    /** Charge weights, logistic shape, band cuts and window gates. */
    val recoveryCfg: uniffi.whoop_ffi.RecoveryCfgInfo by lazy { uniffi.whoop_ffi.recoveryCfg() }

    /** Rest (sleep performance) weights and the duration / restorative shape. */
    val restCfg: uniffi.whoop_ffi.RestCfgInfo by lazy { uniffi.whoop_ffi.restCfg() }

    /** Effort scale, log-map denominator and the two coverage gates. */
    val strainCfg: uniffi.whoop_ffi.StrainCfgInfo by lazy { uniffi.whoop_ffi.strainCfg() }

    /** Baseline cold-start gates: seed, full trust, fast-adapt window. */
    val baselinesCfg: uniffi.whoop_ffi.BaselinesCfgInfo by lazy { uniffi.whoop_ffi.baselinesCfg() }

    /** Body-Age clamp and the reading's ± band. */
    val vitalityCfg: uniffi.whoop_ffi.VitalityCfgInfo by lazy { uniffi.whoop_ffi.vitalityCfg() }

    /** Sleep-debt ledger window and on-target band width. */
    val sleepDebtCfg: uniffi.whoop_ffi.SleepDebtCfgInfo by lazy { uniffi.whoop_ffi.sleepDebtCfg() }

    /** Nap detector defaults, before the user tunes them. */
    val napDefaults: uniffi.whoop_ffi.NapDefaultsInfo by lazy { uniffi.whoop_ffi.napDefaults() }

    /** Sleep detection + main-night window edges. */
    val sleepWindowCfg: uniffi.whoop_ffi.SleepWindowCfgInfo by lazy { uniffi.whoop_ffi.sleepWindowCfg() }

    /** One metric's baseline configuration, read from whoop-rs so the tuning table has a single owner. */
    fun baselineMetricCfg(metric: String): MetricCfg? =
        uniffi.whoop_ffi.baselineMetricCfg(metric)?.let {
            MetricCfg(
                minVal = it.minVal, maxVal = it.maxVal, floorSpread = it.floorSpread,
                halfLifeB = it.halfLifeB, halfLifeS = it.halfLifeS,
            )
        }

    /** The FFI shape for one metric's baseline configuration. */
    fun metricCfgInfo(cfg: MetricCfg) = uniffi.whoop_ffi.MetricCfgInfo(
        minVal = cfg.minVal, maxVal = cfg.maxVal, floorSpread = cfg.floorSpread,
        halfLifeB = cfg.halfLifeB, halfLifeS = cfg.halfLifeS,
    )

    /** Illness baseline policy: the recent-night gap, the window and the trust gate, all owned by whoop-rs. */
    val illnessBaselineCfg: uniffi.whoop_ffi.IllnessBaselineCfgInfo by lazy { uniffi.whoop_ffi.illnessBaselineCfg() }

    /** Per-night z of a chronological (oldest first) daily signal against its own trailing baseline. */
    fun illnessBaselineZ(values: List<Double?>): List<Double?> =
        uniffi.whoop_ffi.illnessBaselineZSeries(values)
}

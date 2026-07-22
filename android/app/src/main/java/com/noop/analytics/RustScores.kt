package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import com.noop.data.Spo2Sample
import com.noop.data.StepSample
import uniffi.whoop_ffi.DaytimeStressInfo
import uniffi.whoop_ffi.DebtNightInput
import uniffi.whoop_ffi.DriverBaselineInfo
import uniffi.whoop_ffi.FitnessAgeInfo
import uniffi.whoop_ffi.HourPointInfo
import uniffi.whoop_ffi.HrTick
import uniffi.whoop_ffi.HrZoneSetInfo
import uniffi.whoop_ffi.RecoveryDrivers
import uniffi.whoop_ffi.RrBeat
import uniffi.whoop_ffi.RrRun
import uniffi.whoop_ffi.SleepSegment
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

    /** Fold consecutive same-`ts` R-R into one run (preserving intra-second beat order), like the stager.
     *  Clamp each rrMs into the UShort range before the cast so a value above u16 can't WRAP into a bogus
     *  in-[300,2000] beat (the aliasing gap `respRateFromRr` guards against by pre-filtering the cast). Unlike
     *  that wrapper we CLAMP rather than drop: the gap-aware RMSSD needs an out-of-physio-range beat to still
     *  reach the whoop-rs clean, which drops it there WITH a contiguity break — dropping (or omitting) it here
     *  would splice its neighbours into a spurious successive pair and diverge from the Kotlin reference. A
     *  clamped value stays > RR_MAX_MS, so the Rust clean drops it with the same break; every real R-R (u16 on
     *  the wire) is in range and passes through unchanged. */
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
     * [BaselineState] convenience overload (mirrors the deleted `RecoveryScorer.recovery` twin): converts
     * each [BaselineState] to a [RecoveryScorer.DriverBaseline] and derives the cold-start gate from
     * `hrvBaseline.usable`, exactly as the Kotlin scorer did, then delegates to the FFI path above. This is
     * what the store sites ([AnalyticsEngine]/[IntelligenceEngine.recomputeRecovery]/[WatchRecovery]) and
     * the [RecoveryScorerTrace] call.
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

    fun sdnn(rrMs: List<Int>): Double? = uniffi.whoop_ffi.hrvSdnn(rrMs.map { it.toUShort() })

    fun rmssdRaw(nn: List<Double>): Double? = uniffi.whoop_ffi.hrvRmssdPlain(nn.map { it.toInt().toUShort() })

    /** Full clean-and-analyze (RMSSD/SDNN/pNN50/meanNN + input/clean counts) — twin of
     *  [HrvAnalyzer.analyzeRaw]. R-R are integer ms (u16 on the wire); range filter, Malik ectopic, the
     *  20-beat floor and the spot rejected-fraction gate all live in whoop-rs. `nInput` = the raw count. */
    fun analyzeRaw(rawRR: List<Double>, maxRejectedFraction: Double?): HrvAnalyzer.HrvResult {
        val info = uniffi.whoop_ffi.hrvAnalyzeRaw(rawRR.map { it.toInt().toUShort() }, maxRejectedFraction)
        return HrvAnalyzer.HrvResult(
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

    // ── Respiratory rate ─────────────────────────────────────────────────────

    fun respRateFromRr(rr: List<RrInterval>, start: Long, end: Long): Double? =
        uniffi.whoop_ffi.respRateFromRr(
            // Drop physiologically out-of-range beats up front (same bounds as HrvAnalyzer.rangeFilter, which
            // the Kotlin reference applies) so an rrMs that would wrap on the UShort cast is dropped, not
            // aliased into range — keeping the Rust leg bit-for-bit with the full-width Int reference.
            rr.asSequence()
                .filter { it.rrMs.toDouble() in HrvAnalyzer.RR_MIN_MS..HrvAnalyzer.RR_MAX_MS }
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

    // ── Deep-sleep HRV window (#141) ────────────────────────────────────────────

    /** Session avgHrv (ms) from 5-min buckets whose centre falls in deep-sleep spans. */
    fun windowedAvgHrvDeep(start: Long, end: Long, rr: List<RrInterval>, segments: List<SleepSegment>): Double? =
        uniffi.whoop_ffi.hrvWindowedAvgDeep(start.toUInt(), end.toUInt(), groupRuns(rr.sortedBy { it.ts }), segments)

    // ── Steps (5/MG cumulative motion counter) ───────────────────────────────

    /** Raw wrap-aware motion-tick total over [samples] (5/MG `step_motion_counter@57`) — twin of
     *  [StepsCounter.stepsInWindow]. `null` for <2 samples or no forward movement (so "no data" stays
     *  distinct from a real zero). The caller applies its `stepTicksPerStep` calibration. */
    fun steps(samples: List<StepSample>): Int? =
        uniffi.whoop_ffi.stepsCounter(
            samples.map { SleepStepSample(it.ts, it.counter.toUShort(), it.activityClass?.toUByte()) },
        )?.toInt()

    // ── Calories (whole-day HR estimate) ─────────────────────────────────────

    /** APPROXIMATE whole-day energy (kcal) from the day's HR — twin of [Calories.estimateDayCalories].
     *  Applies the SAME profile / hrmax / resting fallbacks the Kotlin path used, then delegates. */
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

    /** Rest (sleep performance) composite [0,100] — twin of [RestScorer.rest]. `null` when no asleep time.
     *  Absent `sleepNeedHours` -> 8 h, absent `consistency` -> neutral 0.5 (both resolved in whoop-rs). */
    fun rest(
        asleepSeconds: Double,
        efficiency: Double,
        deepSeconds: Double,
        remSeconds: Double,
        sleepNeedHours: Double? = null,
        consistency: Double? = null,
    ): Double? = uniffi.whoop_ffi.restScore(asleepSeconds, efficiency, deepSeconds, remSeconds, sleepNeedHours, consistency)

    // ── Sleep debt ledger ────────────────────────────────────────────────────

    /** Rolling sleep-debt ledger — twin of [SleepDebt.ledger]. Maps the whoop-rs `DebtLedgerInfo` back to
     *  the Kotlin [SleepDebtLedger] the UI consumes; no-data nights are skipped, never zero-filled. */
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

    // ── Daytime autonomic stress (per-hour activation) ───────────────────────

    /** Score waking-hour aggregates for autonomic activation. Returns the whoop-rs `DaytimeStressInfo`
     *  (scored hours + day mean + peak hour + trailing high run); the caller reassembles its timeline. */
    fun daytimeStress(hours: List<HourPointInfo>): DaytimeStressInfo =
        uniffi.whoop_ffi.daytimeStress(hours)
}

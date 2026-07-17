package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import com.noop.data.Spo2Sample
import uniffi.whoop_ffi.DriverBaselineInfo
import uniffi.whoop_ffi.FitnessAgeInfo
import uniffi.whoop_ffi.HrTick
import uniffi.whoop_ffi.HrZoneSetInfo
import uniffi.whoop_ffi.RecoveryDrivers
import uniffi.whoop_ffi.RrBeat
import uniffi.whoop_ffi.RrRun
import uniffi.whoop_ffi.Spo2RawSample
import uniffi.whoop_ffi.Spo2Span
import uniffi.whoop_ffi.StrainMethod
import uniffi.whoop_ffi.StressComponentsInfo
import uniffi.whoop_ffi.TimeInZoneInfo

/**
 * Bridge from the app's Room/stream types to the whoop-rs Tier-1 derived-score FFI (physio-algo, via
 * uniffi). Sibling of [RustSleepStager]: it maps [HrSample]/[RrInterval]/baseline drivers into the FFI
 * record types, calls the exported score fn (fully qualified so a member never shadows the FFI symbol),
 * and returns the value the Kotlin scorer would have stored.
 *
 * This is the parity-harness scaffolding — each fn is proven bit-for-bit == its Kotlin twin
 * ([RecoveryScorer]/[StrainScorer]/[HrvAnalyzer]/[StressIndex]/[HrZones]/[FitnessAgeEngine] …) on real
 * fixture nights before the Kotlin math is deleted and this becomes the only path. Nothing is wired into
 * [IntelligenceEngine] until the matching parity test passes.
 */
internal object RustScores {

    // ── mappers ──────────────────────────────────────────────────────────────

    private fun hrTicks(hr: List<HrSample>): List<HrTick> =
        hr.map { HrTick(it.ts, it.bpm) }

    private fun baseline(b: RecoveryScorer.DriverBaseline?): DriverBaselineInfo? =
        b?.let { DriverBaselineInfo(it.mean, it.spread) }

    /** Fold consecutive same-`ts` R-R into one run (preserving intra-second beat order), like the stager. */
    private fun groupRuns(rr: List<RrInterval>): List<RrRun> {
        val out = ArrayList<RrRun>()
        for (r in rr) {
            val ms = r.rrMs.toUShort()
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

    fun spo2FromPaired(red: List<Double>, ir: List<Double>): Double? =
        uniffi.whoop_ffi.spo2FromPaired(red, ir)

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
}

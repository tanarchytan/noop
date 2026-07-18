package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RespSample
import com.noop.data.RrInterval
import com.noop.data.StepSample
import uniffi.whoop_ffi.BandStateSample
import uniffi.whoop_ffi.SleepAccelSample
import uniffi.whoop_ffi.SleepHrSample
import uniffi.whoop_ffi.SleepInput
import uniffi.whoop_ffi.SleepRespSample
import uniffi.whoop_ffi.SleepRrRun
import uniffi.whoop_ffi.SleepSegment
import uniffi.whoop_ffi.SleepStage
import uniffi.whoop_ffi.SleepStepSample
import uniffi.whoop_ffi.SleepStreams
import uniffi.whoop_ffi.WristOffInterval
import uniffi.whoop_ffi.analyzeSleep
import uniffi.whoop_ffi.stageSleepRefined

/**
 * Bridge from the app's per-night streams to the whoop-rs sleep pipeline (physio-algo, via uniffi). ALL
 * detection + staging + motion-aware wake refinement lives in Rust now: [analyze] maps the app's sample
 * rows into a [SleepStreams], calls the single `analyzeSleep` FFI, and maps each returned session back to
 * a [DetectedSleep] (carrying the per-epoch motion + band sleep-state grids). [stage] re-stages a single
 * `[start, end]` span with the V2 recipe for the edit self-heal path.
 */
internal object RustSleepStager {

    /** Detect + stage a night's streams: one FFI call returns one [DetectedSleep] per accepted in-bed span. */
    fun analyze(
        hr: List<HrSample>,
        rr: List<RrInterval>,
        resp: List<RespSample>,
        gravity: List<GravitySample>,
        steps: List<StepSample>,
        tzOffsetSeconds: Long,
        wristOff: List<Pair<Long, Long>>,
        bandSleepState: List<Pair<Long, Int>>,
    ): List<DetectedSleep> {
        val streams = SleepStreams(
            hr = hr.sortedBy { it.ts }.map { SleepHrSample(it.ts, it.bpm.toUShort()) },
            rr = groupRuns(rr.sortedBy { it.ts }),
            accel = gravity.sortedBy { it.ts }.map { SleepAccelSample(it.ts, it.x, it.y, it.z) },
            resp = resp.sortedBy { it.ts }.map { SleepRespSample(it.ts, it.raw) },
            steps = steps.sortedBy { it.ts }
                .map { SleepStepSample(it.ts, it.counter.toUShort(), it.activityClass?.toUByte()) },
            tzOffsetS = tzOffsetSeconds,
            wristOff = wristOff.map { WristOffInterval(it.first, it.second) },
            bandSleepState = bandSleepState.map { BandStateSample(it.first, it.second) },
        )
        return analyzeSleep(streams).map { s ->
            DetectedSleep(
                start = s.start, end = s.end, efficiency = s.efficiency,
                stages = s.segments.map { StageSegment(start = it.start, end = it.end, stage = stageName(it.stage)) },
                restingHR = s.restingHr, avgHRV = s.avgHrv,
                motionGrid = s.motionGrid, sleepStateGrid = s.sleepStateGrid,
            )
        }
    }

    /** Re-stage one already-detected `[start, end]` span with the V2 recipe + motion-aware wake refinement
     *  (the app's edit self-heal path). */
    fun stage(
        start: Long, end: Long,
        grav: List<GravitySample>, hr: List<HrSample>, rr: List<RrInterval>, resp: List<RespSample>,
        steps: List<StepSample>,
    ): List<StageSegment> {
        val input = SleepInput(
            start = start, end = end,
            hr = hr.sortedBy { it.ts }.map { SleepHrSample(it.ts, it.bpm.toUShort()) },
            rr = groupRuns(rr.sortedBy { it.ts }),
            accel = grav.sortedBy { it.ts }.map { SleepAccelSample(it.ts, it.x, it.y, it.z) },
            resp = resp.sortedBy { it.ts }.map { SleepRespSample(it.ts, it.raw) },
        )
        val ffiSteps = steps.sortedBy { it.ts }
            .map { SleepStepSample(it.ts, it.counter.toUShort(), it.activityClass?.toUByte()) }
        return stageSleepRefined(input, ffiSteps).map { StageSegment(start = it.start, end = it.end, stage = stageName(it.stage)) }
    }

    /** Fold consecutive same-`ts` intervals into one run, preserving intra-second beat order. */
    private fun groupRuns(rr: List<RrInterval>): List<SleepRrRun> {
        val out = ArrayList<SleepRrRun>()
        for (r in rr) {
            val ms = r.rrMs.toUShort()
            val last = out.lastOrNull()
            if (last != null && last.ts == r.ts) last.intervals = last.intervals + ms
            else out.add(SleepRrRun(r.ts, listOf(ms)))
        }
        return out
    }

    private fun stageName(s: SleepStage): String = when (s) {
        SleepStage.WAKE -> "wake"
        SleepStage.LIGHT -> "light"
        SleepStage.DEEP -> "deep"
        SleepStage.REM -> "rem"
    }
}

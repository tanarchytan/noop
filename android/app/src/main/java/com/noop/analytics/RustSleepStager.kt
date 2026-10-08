package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval
import com.noop.data.StepSample
import uniffi.whoop_ffi.BandStateSample
import uniffi.whoop_ffi.SleepAccelSample
import uniffi.whoop_ffi.SleepEngine
import uniffi.whoop_ffi.SleepHrSample
import uniffi.whoop_ffi.SleepInput
import uniffi.whoop_ffi.SleepRrRun
import uniffi.whoop_ffi.SleepStage
import uniffi.whoop_ffi.SleepStepSample
import uniffi.whoop_ffi.SleepStreams
import uniffi.whoop_ffi.WristOffInterval
import uniffi.whoop_ffi.analyzeSleepWith
import uniffi.whoop_ffi.stageSleepRefinedWith

/**
 * Bridge from the app's per-night streams to the whoop-rs sleep pipeline (physio-algo, via uniffi). ALL
 * detection + staging + motion-aware wake refinement lives in Rust now: [analyze] maps the app's sample
 * rows into a [SleepStreams], calls the single `analyzeSleep` FFI, and maps each returned session back to
 * a [DetectedSleep] (carrying the per-epoch motion + band sleep-state grids). [stage] re-stages a single
 * `[start, end]` span with the V2 recipe for the edit self-heal path.
 */
internal object RustSleepStager {

    /** The engine each call runs; the app installs the stored setting at startup. Original until then. */
    @Volatile
    var engineProvider: () -> SleepEngine = { SleepEngine.ORIGINAL }

    /** Detect + stage a night's streams: one FFI call returns one [DetectedSleep] per accepted in-bed span. */
    fun analyze(
        hr: List<HrSample>,
        rr: List<RrInterval>,
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
            steps = steps.sortedBy { it.ts }
                .map { SleepStepSample(it.ts, it.counter.toUShort(), it.activityClass?.toUByte()) },
            tzOffsetS = tzOffsetSeconds,
            wristOff = wristOff.map { WristOffInterval(it.first, it.second) },
            bandSleepState = bandSleepState.map { BandStateSample(it.first, it.second) },
        )
        return analyzeSleepWith(streams, engineProvider()).map { s ->
            DetectedSleep(
                start = s.start, end = s.end, efficiency = s.efficiency,
                stages = s.segments.map { StageSegment(start = it.start, end = it.end, stage = stageName(it.stage)) },
                restingHR = s.restingHr, avgHRV = s.avgHrv,
                motionGrid = s.motionGrid, sleepStateGrid = s.sleepStateGrid,
                unscored = s.unscored.map { it.start to it.end },
            )
        }
    }

    /** A re-staged span: its stages plus the stretches the engine leaves unscored. */
    data class Staged(val stages: List<StageSegment>, val unscored: List<Pair<Long, Long>>)

    /** Re-stage one already-detected `[start, end]` span with the chosen engine + motion-aware wake
     *  refinement (the app's edit self-heal path). */
    fun stage(
        start: Long, end: Long,
        grav: List<GravitySample>, hr: List<HrSample>, rr: List<RrInterval>,
        steps: List<StepSample>,
    ): Staged {
        val input = SleepInput(
            start = start, end = end,
            hr = hr.sortedBy { it.ts }.map { SleepHrSample(it.ts, it.bpm.toUShort()) },
            rr = groupRuns(rr.sortedBy { it.ts }),
            accel = grav.sortedBy { it.ts }.map { SleepAccelSample(it.ts, it.x, it.y, it.z) },
        )
        val ffiSteps = steps.sortedBy { it.ts }
            .map { SleepStepSample(it.ts, it.counter.toUShort(), it.activityClass?.toUByte()) }
        val refined = stageSleepRefinedWith(input, ffiSteps, engineProvider())
        return Staged(
            stages = refined.segments.map { StageSegment(start = it.start, end = it.end, stage = stageName(it.stage)) },
            unscored = refined.unscored.map { it.start to it.end },
        )
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

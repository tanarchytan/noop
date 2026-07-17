package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RespSample
import com.noop.data.RrInterval
import uniffi.whoop_ffi.SleepAccelSample
import uniffi.whoop_ffi.SleepHrSample
import uniffi.whoop_ffi.SleepInput
import uniffi.whoop_ffi.SleepRespSample
import uniffi.whoop_ffi.SleepRrRun
import uniffi.whoop_ffi.SleepSegment
import uniffi.whoop_ffi.SleepStage
import uniffi.whoop_ffi.stageSleepV1
import uniffi.whoop_ffi.stageSleepV2

/**
 * Bridge from the app's per-night streams to the whoop-rs sleep stager (physio-algo, via uniffi). The
 * 4-class hypnogram recipe lives ONLY in Rust now; this maps [GravitySample]/[HrSample]/[RrInterval]/
 * [RespSample] into a [SleepInput], calls [stageSleepV2] (5.0/MG default) or [stageSleepV1] (4.0), and
 * maps the returned [SleepSegment]s back to the app's [StageSegment]. Byte-identical to the deleted
 * Kotlin engines (proven by RustSleepStagerParityTest over the DREAMT fixtures).
 */
internal object RustSleepStager {

    fun stage(
        v2: Boolean, start: Long, end: Long,
        grav: List<GravitySample>, hr: List<HrSample>, rr: List<RrInterval>, resp: List<RespSample>,
    ): List<StageSegment> {
        val input = SleepInput(
            start = start, end = end,
            hr = hr.sortedBy { it.ts }.map { SleepHrSample(it.ts, it.bpm.toUShort()) },
            rr = groupRuns(rr.sortedBy { it.ts }),
            accel = grav.sortedBy { it.ts }.map { SleepAccelSample(it.ts, it.x, it.y, it.z) },
            resp = resp.sortedBy { it.ts }.map { SleepRespSample(it.ts, it.raw) },
        )
        val segs = if (v2) stageSleepV2(input) else stageSleepV1(input)
        return segs.map { StageSegment(start = it.start, end = it.end, stage = stageName(it.stage)) }
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

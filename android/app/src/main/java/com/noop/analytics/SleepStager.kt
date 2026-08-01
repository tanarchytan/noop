package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RrInterval

/*
 * SleepStager.kt — app-side seam over the whoop-rs sleep engine.
 *
 * Detection, staging, wake refinement, and main-night selection live in whoop-rs
 * (physio-algo::sleep) via the single `analyzeSleep` FFI. This file keeps the HRV-window /
 * deep-pool layer (sessionHrvWindows / lastDeepRun / HrvWindow — stage-tagged 5-min RMSSD
 * windows for the deep-stage pool and HRV trace; session avgHrv itself is scored in whoop-rs
 * RustScores.windowedAvgHrv), hypnogramMetrics (AASM aggregation over Rust-produced stages),
 * and small pure predicates reused by callers and the resp-rate parity test.
 *
 * Types live in AnalyticsModels.kt: [DetectedSleep], [StageSegment], [HypnogramMetrics].
 * `ts` / `start` / `end` are wall-clock unix SECONDS (Long).
 */
object SleepStager {

    // ── Constants ─────────────────────────────────────────────────────────────
    //
    // Detection thresholds, the daytime guard, the off-wrist backstop and the sparse-gravity bridge
    // are whoop-rs `sleep::detect`, and its constants are the definitions. What is left here is only
    // what the two predicates below read.

    /** Data gap (minutes) that always breaks a run. */
    val maxGapMin: Int = RustScores.sleepWindowCfg.maxGapMin.toInt()

    /** Local hour (inclusive) at which the daytime band begins. */
    val daytimeBandStartHour: Int = RustScores.sleepWindowCfg.daytimeBandStartHour.toInt()

    /** Local hour (exclusive) at which the daytime band ends: an onset in [start, end) is daytime. */
    val daytimeBandEndHour: Int = RustScores.sleepWindowCfg.daytimeBandEndHour.toInt()

    /**
     * Gravity is "sparse" when its timespan covers less than this fraction of the HR-sample
     * timespan. A dense 4.0 night has gravity spanning the whole HR window (≈1.0) and never trips
     * this; a 5.0 backfill clumps gravity into a fraction of the night.
     */
    val sparseGravitySpanFrac: Double = RustScores.sleepWindowCfg.sparseGravitySpanFrac

    // ── Sparse-gravity gate ────────────────────────────────────────────────────

    /**
     * Largest spacing between consecutive timestamps (seconds), NO upper cap; 0.0 for <2 samples. Detects
     * clumped/sparse gravity where a few long dropouts keep the MEDIAN gap small but still break runs, so
     * the largest gap — not the median — is the right signal.
     */
    internal fun largestGapS(times: List<Long>): Double {
        if (times.size < 2) return 0.0
        var mx = 0.0
        for (i in 0 until times.size - 1) {
            val g = (times[i + 1] - times[i]).toDouble()
            if (g > mx) mx = g
        }
        return mx
    }

    /**
     * True when gravity is too sparse for the gravity-only spine to be trusted: its timespan covers <
     * sparseGravitySpanFrac of the HR-sample timespan, OR its LARGEST inter-sample gap exceeds maxGapMin
     * (catches clumped motion a median gap would miss). With no/degenerate HR the dense path is kept.
     */
    internal fun isGravitySparse(grav: List<GravitySample>, hr: List<HrSample>): Boolean {
        if (grav.size < 2 || hr.size < 2) return false
        val hrSpan = (hr[hr.size - 1].ts - hr[0].ts).toDouble()
        if (hrSpan <= 0) return false
        val gravSpan = (grav[grav.size - 1].ts - grav[0].ts).toDouble()
        if (gravSpan < sparseGravitySpanFrac * hrSpan) return true
        // Clumped 4.0 motion keeps a SMALL median gap yet still contains >maxGapMin dropouts the
        // gravity-only spine shreds the night on; the largest gap catches what a median would miss.
        // Flagging sparse only ENABLES buildRuns' HR-vouched bridge — a real wake still breaks it.
        return largestGapS(grav.map { it.ts }) > (maxGapMin * 60).toDouble()
    }

    /**
     * True when a run's ONSET (start), in LOCAL time, falls OUTSIDE the daytime band — i.e. sleep began
     * at night, not during the day. Anchors a continuous-sleep chain: only a chain that began overnight
     * may carry its tail past the daytime-band start (a late wake).
     */
    internal fun isOvernightOnset(start: Long, tzOffsetSeconds: Long): Boolean {
        val secOfDay = Math.floorMod(start + tzOffsetSeconds, CalendarDay.SECONDS_PER_DAY)
        val hour = (secOfDay / 3_600L).toInt()
        return !(hour >= daytimeBandStartHour && hour < daytimeBandEndHour)
    }

    // ── Respiration rate from R-R (RSA) — WHOOP5 on-wire path ────────────────

    /**
     * THE canonical plausible sleeping-respiratory-rate band (bpm): the RSA peak-pick can yield 6-8 bpm at
     * its noise floor, but every consumer only acts on 8-25. The resp-rate estimator (whoop-rs physio-algo,
     * via RustScores.respRateFromRr) clamps to this band, so the stored value never disagrees with what's acted on.
     */
    val respPlausibleRangeBpm: ClosedFloatingPointRange<Double> = 8.0..25.0


    // ── Per-session HR / HRV ─────────────────────────────────────────────────

    /** One 5-min HRV window: its start ts, the sleep stage at its center, the clean-beat count, and the
     *  window RMSSD (null when fewer than 2 clean beats, or when every successive pair straddles a dropped
     *  beat). Drives the deep-stage HRV pool and the HRV test-mode trace. */
    data class HrvWindow(val startTs: Long, val stage: String, val cleanBeats: Int, val rmssd: Double?)

    /**
     * The whoop-rs per-5-min HRV buckets for a session ([RustScores.windowedBuckets]), each tagged with the
     * sleep stage at its CENTRE from [stages]. Feeds the deep-stage HRV pool ([AnalyticsEngine]) and the
     * HRV test-mode trace; same buckets and same RMSSD as the stored session avgHrv, so the trace and the
     * displayed value agree. Passing `emptyList()` for [stages] tags every window "?".
     */
    internal fun sessionHrvWindows(
        start: Long, end: Long, rr: List<RrInterval>, stages: List<StageSegment>,
    ): List<HrvWindow> {
        val windowS = 5 * 60L
        return RustScores.windowedBuckets(start, end, rr).map { b ->
            val centre = b.start.toLong() + windowS / 2
            HrvWindow(
                startTs = b.start.toLong(),
                stage = stages.firstOrNull { centre >= it.start && centre < it.end }?.stage ?: "?",
                cleanBeats = b.cleanBeats.toInt(),
                rmssd = b.rmssd,
            )
        }
    }

    /** The LAST contiguous run of deep-stage windows in [windows] — the WHOOP-style "last slow-wave-sleep"
     *  comparator for the HRV nightly trace. Empty when no deep window is present. */
    internal fun lastDeepRun(windows: List<HrvWindow>): List<HrvWindow> {
        var lastRun: List<HrvWindow> = emptyList()
        val cur = ArrayList<HrvWindow>()
        for (w in windows) {
            if (w.stage == "deep") {
                cur.add(w)
            } else if (cur.isNotEmpty()) {
                lastRun = ArrayList(cur); cur.clear()
            }
        }
        if (cur.isNotEmpty()) lastRun = cur
        return lastRun
    }

    // ── AASM hypnogram metrics ───────────────────────────────────────────────

    /** AASM-style metrics from a session's stage segments. */
    fun hypnogramMetrics(session: DetectedSleep): HypnogramMetrics {
        val segs = session.stages.sortedBy { it.start }
        val tib = maxOf(0.0, (session.end - session.start).toDouble())

        fun dur(s: StageSegment): Double = (s.end - s.start).toDouble()
        val sleepSegs = segs.filter { it.stage == "light" || it.stage == "deep" || it.stage == "rem" }
        val tst = sleepSegs.sumOf { dur(it) }
        val deepS = segs.filter { it.stage == "deep" }.sumOf { dur(it) }
        val remS = segs.filter { it.stage == "rem" }.sumOf { dur(it) }
        val lightS = segs.filter { it.stage == "light" }.sumOf { dur(it) }

        val onset: Double
        val sptEnd: Double
        val sol: Double
        val first = sleepSegs.firstOrNull()
        val last = sleepSegs.lastOrNull()
        if (first != null && last != null) {
            onset = first.start.toDouble()
            sptEnd = last.end.toDouble()
            sol = maxOf(0.0, onset - session.start.toDouble())
        } else {
            onset = session.end.toDouble()
            sptEnd = session.end.toDouble()
            sol = tib
        }

        val remSegs = segs.filter { it.stage == "rem" }
        val remLatency = remSegs.firstOrNull()?.let { it.start.toDouble() - onset } ?: Double.NaN

        var waso = 0.0
        var disturbances = 0
        for (s in segs) {
            if (s.stage != "wake") continue
            val w0 = maxOf(s.start.toDouble(), onset)
            val w1 = minOf(s.end.toDouble(), sptEnd)
            if (w1 > w0) {
                waso += (w1 - w0)
                disturbances += 1
            }
        }

        val se = if (tib > 0) tst / tib else 0.0
        fun pct(x: Double): Double = if (tst > 0) x / tst * 100.0 else 0.0

        return HypnogramMetrics(
            tibS = tib, tstS = tst, sptS = maxOf(0.0, sptEnd - onset), solS = sol,
            remLatencyS = remLatency, wasoS = waso, efficiency = minOf(1.0, se),
            disturbances = disturbances, deepMin = deepS / 60.0, remMin = remS / 60.0,
            lightMin = lightS / 60.0, deepPct = pct(deepS), remPct = pct(remS), lightPct = pct(lightS),
        )
    }

}

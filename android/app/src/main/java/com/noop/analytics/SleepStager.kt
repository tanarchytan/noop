package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RespSample
import com.noop.data.RrInterval
import com.noop.data.StepSample
import kotlin.math.abs
import kotlin.math.sqrt

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

    // ── Stage 0 constants ─────────────────────────────────────────────────────

    /** Per-sample gravity change (g) at/below which a sample is "still". */
    const val gravityStillThresholdG: Double = 0.01

    /** Rolling stillness window (minutes). */
    const val stillWindowMin: Int = 15

    /** Fraction of still samples to call the window-center "sleep". */
    const val stillFraction: Double = 0.70

    /** Data gap (minutes) that always breaks a run. */
    const val maxGapMin: Int = 20

    /** Runs shorter than this (minutes) are absorbed into neighbours. */
    const val mergeMin: Int = 15

    /** A sleep run must exceed this (minutes) to count as a session. */
    const val minSleepMin: Int = 60

    /** Assumed sample interval (seconds) when not inferable. */
    const val defaultIntervalS: Double = 60.0

    // ── Daytime false-sleep guard ─────────────────────────────────────────────
    //
    // A long, still, sedentary daytime stretch is gravity-indistinguishable from a real nap. A
    // window whose CENTER falls in the daytime band must be long enough for a nap AND show
    // a genuine cardiac dip; overnight windows are unchanged.

    /** Local hour (inclusive) at which the stricter daytime bar begins. */
    const val daytimeBandStartHour: Int = 11

    /**
     * Local hour (exclusive) at which the stricter daytime bar ends. A window whose center
     * is in [start, end) local hours is "daytime"; everything else is "overnight".
     */
    const val daytimeBandEndHour: Int = 20

    /** A still run resuming within this gap of an overnight chain is the night's TAIL — a late
     *  wake or brief morning stir — not an isolated daytime nap, so it skips the daytime guard. */
    const val nightContinuationGapMin: Int = 90

    /**
     * A daytime window must run at least this long (minutes) to count — short still daytime
     * stretches are the dominant false-positive and are rejected outright.
     */
    const val daytimeMinSleepMin: Int = 90

    /**
     * A daytime window's resting HR (lowest 5-min rolling mean) must be at or below
     * baseline × this to confirm a real cardiac dip. Stricter than the overnight 1.05:
     * a true nap dips BELOW the waking-day median, sedentary stillness does not.
     */
    const val daytimeRestingHRMult: Double = 0.95

    // ── Physiological in-bed span cap ─────────────────────────────────────────
    //
    // Maximum plausible in-bed span (seconds) for one assembled main-sleep run. A 12h+ "sleep"
    // is a bad-clock artefact reading as one still block, which poisons Rest / the debt ledger.
    // A run whose span exceeds this is DROPPED, not truncated (truncating would fabricate a wake time).
    const val maxMainSleepSpanS: Long = 16L * 60L * 60L

    // ── Morning-stillness nap suppression ─────────────────────────────────────
    //
    // After a real wake the wrist is often still (coffee, back in bed) long enough to clear the
    // ordinary daytime guard and read as a fresh nap. A daytime block beginning within
    // morningStillnessWindowMin of a wake must show a genuine re-onset — a clear HR dip, not merely near it.

    /** A daytime block whose onset falls within this many minutes after an overnight wake is treated as
     *  suspected morning residual stillness and held to the stronger re-onset bar. ~3h covers the post-wake
     *  window; a genuine afternoon nap (hours later) faces only the ordinary daytime guard. */
    const val morningStillnessWindowMin: Int = 180

    /** Stronger resting-HR bar (× day baseline) a suspected-morning-stillness block must clear to be kept
     *  as a real re-onset. Stricter than [daytimeRestingHRMult] (0.95): residual waking stillness keeps a
     *  near-waking HR, so only a genuine dip (a true second sleep) survives. */
    const val morningReonsetRestingHRMult: Double = 0.90

    /** Persisted v18 BAND sleep_state value meaning "asleep" (`(sb>>4)&3`: 0 wake / 1 still / 2 asleep /
     *  3 up). The strap's own scored band state — an independent anchor confirming a borderline morning
     *  re-onset. */
    const val bandStateAsleep: Int = 2

    /** Fraction of a suspected-morning-stillness block's epochs whose band sleep_state must read "asleep"
     *  ([bandStateAsleep]) to confirm a genuine re-onset even when the HR dip is borderline. A residual-
     *  stillness false nap reads "still"/"up", not "asleep"; ≥0.6 keeps this conservative. */
    const val morningReonsetBandAsleepFrac: Double = 0.6

    /** Seconds in a calendar day (for local-hour-of-day arithmetic). */
    const val secondsPerDay: Long = 86_400L

    /** Floor on the rolling-window size in samples. */
    const val minWindowSamples: Int = 3

    /** A run is HR-confirmed only if mean HR ≤ baseline × this. */
    const val hrSleepBaselineMult: Double = 1.05

    // ── Motion-corroborated wake (elevated-but-flat-HR nights) ────────────────
    //
    // HR-led confirmation ([confirmSleepWithHR]) rejects a still run whose HR sits above the sleep band,
    // misreading an elevated-but-motionless night as wake. A run/epoch at the night's quiescent motion
    // floor with unchanged posture cannot be called wake on HR alone (the per-epoch half lives in whoop-rs).

    /** Relaxed sleep-band multiplier applied to [confirmSleepWithHR] when the run is DEEPLY motion-quiescent.
     *  Wider than [hrSleepBaselineMult] (1.05) so an elevated-but-motionless overnight run is not rejected,
     *  yet bounded so a genuinely awake still run above this band is still dropped. */
    const val quiescentHRSleepMult: Double = 1.30

    /** Per-minute gravity posture variance (g²) at/below which a minute counts as posture-STABLE. A minute
     *  with too few gravity samples to compute a variance is conservatively NOT counted as stable (silence
     *  ≠ stillness). */
    const val quiescentPostureVarG2: Double = 0.05

    /** Fraction of a run's minutes that must be posture-STABLE for the run to be DEEPLY motion-quiescent.
     *  Set well above the Stage-0 stillness bar ([stillFraction] 0.70) so only a genuinely motionless run —
     *  not an ordinary restless-but-in-bed night — earns the widened HR band. */
    const val quiescentStableFrac: Double = 0.90

    /** Minimum posture-stable minutes with COMPUTABLE variance a run needs before the deeply-quiescent
     *  verdict is trusted at all — a run with almost no dense-gravity minutes can't prove stillness, so it
     *  defers to the strict HR band rather than being waved through. */
    const val quiescentMinStableMinutes: Int = 20

    /** Floor (bpm) under [adaptiveOvernightHRBaseline] so a genuinely wakeful era cannot collapse the sleep
     *  band and score wakefulness asleep. */
    const val adaptiveBaselineFloor: Double = 40.0

    /** Skip HR refinement (trust gravity) when fewer than this many HR samples. */
    const val hrRefineMinSamples: Int = 30

    /** Consecutive sleep epochs required to declare onset. */
    const val onsetPersistEpochs: Int = 3

    // ── Off-wrist backstop ─────────────────────────────────────────────────────
    //
    // A wrist-off stretch reads as still gravity with missing HR, so the daytime guard lets it through as
    // sleep. The backstop measures OFF-WRIST COVERAGE from long HR gaps (a worn strap emits ~1 Hz HR) plus
    // explicit WRIST_OFF intervals; a run is dropped only when coverage reaches maxOffWristSleepFraction.

    /**
     * A contiguous HR-sample gap of at least this many minutes contributes to a candidate run's
     * off-wrist coverage. Sized at maxGapMin so a real worn night contributes ~no gap, but a wrist-off
     * stretch (HR flatlines to no samples) contributes its whole span; edges count too.
     */
    const val offWristHRGapMin: Int = 20

    /**
     * FRACTIONAL off-wrist rejection: a candidate run is dropped only when its off-wrist coverage — the
     * union of long HR-gap spans and WRIST_OFF→WRIST_ON intervals — is at least this fraction of its
     * duration. 0.5 keeps a night with a short off-wrist tail while dropping an all-day desk strap.
     */
    const val maxOffWristSleepFraction: Double = 0.5

    /**
     * Minimum average HR-stream density for the off-wrist HR-gap proxy to be trusted. A >[offWristHRGapMin]
     * -minute HR hole reads as "off the wrist" only when HR is otherwise dense; a sparse-HR night would
     * otherwise wrongly DROP. Below this density we don't assert off-wrist from gaps (WRIST_OFF still applies).
     */
    const val hrDenseSpacingS: Int = 600   // one HR sample per 10 minutes, averaged over the stream

    // ── Sparse-gravity robustness ─────────────────────────────────────────────
    //
    // A WHOOP 5.0 backfill has sparse/clumped gravity (~25% coverage), so the gravity-only spine
    // fragments the night and drops every <minSleepMin fragment. The fix derives the in-bed spine from a
    // sustained low-HR stretch, gated entirely behind "gravity is sparse" so dense 4.0 nights are unaffected.

    /**
     * Gravity is "sparse" when its timespan covers less than this fraction of the HR-sample
     * timespan. A dense 4.0 night has gravity spanning the whole HR window (≈1.0) and never trips
     * this; a 5.0 backfill clumps gravity into a fraction of the night.
     */
    const val sparseGravitySpanFrac: Double = 0.5

    /**
     * When sparse, HR drives the in-bed spine: an HR sample is "sleep-band" when its bpm ≤
     * baseline × this. Reuses the overnight HR-confirmation multiplier so the band is the same one
     * detectSleep already trusts to confirm a run.
     */
    const val hrSleepBandMult: Double = hrSleepBaselineMult

    /**
     * When sparse, two adjacent runs separated ONLY by a gravity gap up to this many minutes are merged
     * if the intervening HR stays in the sleep band, so a real night is not shredded into sub-minSleepMin
     * fragments. Sized at the daytime-nap floor (a real night never has a true >90 min wake bridge).
     */
    const val sparseBridgeGapMin: Int = 90

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
        val secOfDay = Math.floorMod(start + tzOffsetSeconds, secondsPerDay)
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

    /**
     * Local-maxima peak finder mirroring scipy.find_peaks(distance, height):
     * a sample is a peak if strictly greater than both neighbours and ≥ height;
     * peaks closer than `distance` are resolved by keeping the taller.
     */
    internal fun findPeaks(x: List<Double>, distance: Int, height: Double): List<Int> {
        val n = x.size
        if (n < 3) return emptyList()
        val candidates = ArrayList<Int>()
        var i = 1
        while (i < n - 1) {
            if (x[i] > x[i - 1] && x[i] >= height) {
                // handle flat plateaus: find right edge of the plateau
                var j = i
                while (j + 1 < n && x[j + 1] == x[i]) j += 1
                if (j + 1 < n && x[j + 1] < x[i]) {
                    candidates.add((i + j) / 2) // plateau midpoint
                }
                i = j + 1
            } else {
                i += 1
            }
        }
        if (distance <= 1 || candidates.isEmpty()) return candidates
        // Enforce minimum distance: greedily keep tallest, scipy-style.
        val byHeight = candidates.sortedByDescending { x[it] }
        val keep = BooleanArray(candidates.size) { true }
        val indexOf = HashMap<Int, Int>(candidates.size)
        for ((off, c) in candidates.withIndex()) indexOf[c] = off
        for (p in byHeight) {
            val pi = indexOf[p] ?: continue
            if (!keep[pi]) continue
            for ((qi, q) in candidates.withIndex()) {
                if (qi != pi && keep[qi]) {
                    if (abs(q - p) < distance) keep[qi] = false
                }
            }
        }
        return candidates.filterIndexed { off, _ -> keep[off] }.sorted()
    }

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

    // ── Small stats helpers ───────────────────────────────────────────────────

    /** Population standard deviation (numpy default, ddof=0). */
    internal fun standardDeviation(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.sum() / values.size.toDouble()
        var ss = 0.0
        for (v in values) {
            val d = v - mean
            ss += d * d
        }
        return sqrt(ss / values.size.toDouble())
    }
}

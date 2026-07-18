package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RespSample
import com.noop.data.RrInterval
import com.noop.data.StepSample
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * SleepStager.kt — the app-side seam over the whoop-rs sleep engine.
 *
 * Sleep DETECTION + STAGING + wake refinement + main-night selection all live in
 * whoop-rs (physio-algo::sleep), reached through the single `analyzeSleep` FFI — see
 * android/SLEEP-BORDER.md and whoop-rs/docs/sleep.md. This object is no longer a stager;
 * what remains is frontend glue, not algorithm:
 *   - detectSleep: a bounded memo (detectCache) in front of RustSleepStager.analyze — it
 *     only ever skips a recompute for an identical input, never changes the result.
 *   - the HRV-window / deep-pool layer (sessionHrvWindows / lastDeepRun / HrvWindow):
 *     stage-tagged 5-min RMSSD windows for the deep-stage HRV pool and the HRV trace.
 *     The scored session avgHrv itself is computed in whoop-rs (RustScores.windowedAvgHrv).
 *   - hypnogramMetrics: AASM minute/percent aggregation over the Rust-produced stages.
 *   - small pure predicates/primitives (isGravitySparse, isOvernightOnset, largestGapS,
 *     findPeaks, standardDeviation, respPlausibleRangeBpm) reused by callers and by the
 *     resp-rate parity test that pins the Rust scorer byte-identical.
 *
 * Types:
 *   - The detected-sleep type is [DetectedSleep] (AnalyticsModels.kt), NOT a Room entity.
 *     Stage segments are [StageSegment] (AnalyticsModels.kt, fields var).
 *   - [HypnogramMetrics] (AnalyticsModels.kt) is returned by [hypnogramMetrics].
 *
 * All `ts` / `start` / `end` are wall-clock unix SECONDS (Long).
 */
object SleepStager {

    // ── Stage 0 constants (sleep.py) ─────────────────────────────────────────

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

    // ── Daytime false-sleep guard (#90) ──────────────────────────────────────
    //
    // A long, still, sedentary daytime stretch (reading, a desk, a sofa) is gravity-
    // indistinguishable from a real nap, so the gravity spine alone misclassifies it as
    // sleep. The fix is NOT to drop daytime sleep — real naps are legitimate sessions —
    // but to hold a window whose CENTER falls in the local daytime band to a stricter bar:
    // it must be long enough to be a real nap AND show a genuine cardiac dip (a sedentary
    // stretch keeps a near-baseline HR). Overnight windows are UNCHANGED. Mirrors Swift.

    /** Local hour (inclusive) at which the stricter daytime bar begins. */
    const val daytimeBandStartHour: Int = 11

    /**
     * Local hour (exclusive) at which the stricter daytime bar ends. A window whose center
     * is in [start, end) local hours is "daytime"; everything else is "overnight".
     */
    const val daytimeBandEndHour: Int = 20

    /**
     * A still sleep run that resumes within this gap of an overnight sleep chain is the
     * night's TAIL — a late wake past the daytime-band start, or a brief morning stir then
     * back to sleep — not an isolated daytime nap, so it skips the daytime guard. Without
     * this, a real sleep that ran past ~11:00 local had its tail rejected as a "nap" and the
     * displayed wake time was truncated to late morning (late sleepers / shift workers).
     * Reimplemented from @vulnix0x4's PR #353.
     */
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

    // ── H4 physiological in-bed span cap (#547 / #531 / #509 / tail) ───────────
    //
    // Maximum plausible in-bed span (seconds) for a SINGLE assembled main-sleep run. No real single night
    // runs longer than this: a 12 h+ "sleep" is a bad-clock artefact (a stale/duplicated timestamp range,
    // or a strap that banked one frozen still stretch under a wrong clock) reading as one enormous still
    // block — which then reports a 12 h sleep and poisons Rest / the debt ledger / the headline. 16 h is
    // well above any genuine night yet below the clock-artefact range. A run whose span exceeds this is
    // DROPPED (not silently truncated to 16 h, which would fabricate a wake time): an over-long block is
    // not trustworthy enough to assert a span for at all. Mirrors Swift `maxMainSleepSpanS`.
    const val maxMainSleepSpanS: Long = 16L * 60L * 60L

    // ── H7 morning-stillness nap suppression (#531) ───────────────────────────
    //
    // After a real overnight wake the wrist is often still (sitting with coffee, back in bed scrolling, a
    // sofa) for a stretch that the gravity spine reads as a fresh "nap" — #531's 9 am phantom nap right after
    // the night ended. It is NOT a night-tail continuation (handled by nightContinuationGapMin and exempted),
    // and it can clear the ordinary daytime guard (long + the post-wake HR is still low), so it slipped
    // through. H7 holds a daytime block that BEGINS within morningStillnessWindowMin of the just-detected
    // overnight wake to a STRONGER bar: it must show a genuine SUSTAINED re-onset — a real second sleep dips
    // clearly below the day median, not merely near it. Mirrors Swift.

    /** A daytime block whose onset falls within this many minutes AFTER an overnight chain's wake is treated
     *  as suspected morning residual stillness and held to the stronger re-onset bar below. ~3 h covers the
     *  post-wake window where residual stillness masquerades as a nap; a genuine afternoon nap (hours later)
     *  is past it and faces only the ordinary daytime guard. Mirrors Swift `morningStillnessWindowMin`. (#531) */
    const val morningStillnessWindowMin: Int = 180

    /** The stronger resting-HR bar (× day baseline) a suspected-morning-stillness block must clear to be kept
     *  as a real re-onset. Stricter than the ordinary daytime [daytimeRestingHRMult] (0.95): residual waking
     *  stillness keeps a near-waking HR, so only a block that dips clearly (a true second sleep) survives.
     *  Mirrors Swift `morningReonsetRestingHRMult`. (#531) */
    const val morningReonsetRestingHRMult: Double = 0.90

    /** The persisted v18 BAND sleep_state value that means "asleep" (Interpreter's `(sb>>4)&3`: 0 wake /
     *  1 still / 2 asleep / 3 up). The strap's OWN scored band state — an independent anchor we CONSUME to
     *  confirm a borderline morning re-onset (H7). Mirrors Swift `bandStateAsleep`. (#531 / H8 consume) */
    const val bandStateAsleep: Int = 2

    /** Fraction of a suspected-morning-stillness block's epochs whose persisted band sleep_state must read
     *  "asleep" ([bandStateAsleep]) for the strap's OWN signal to CONFIRM a genuine re-onset and KEEP the
     *  block even when its HR dip is borderline. A real second sleep the strap itself scored asleep is a
     *  strong, honest anchor; a residual-stillness false nap reads "still"/"up", not "asleep". ≥0.6 keeps
     *  this conservative. Mirrors Swift `morningReonsetBandAsleepFrac`. (H8 consume) */
    const val morningReonsetBandAsleepFrac: Double = 0.6

    /** Seconds in a calendar day (for local-hour-of-day arithmetic). */
    const val secondsPerDay: Long = 86_400L

    /** Floor on the rolling-window size in samples. */
    const val minWindowSamples: Int = 3

    /** A run is HR-confirmed only if mean HR ≤ baseline × this. */
    const val hrSleepBaselineMult: Double = 1.05

    // ── Motion-corroborated wake (elevated-but-flat-HR nights, #462) ───────────
    //
    // Both stagers call "wake" primarily off HR / HR-variability, and the HR-led session confirmation
    // ([confirmSleepWithHR]) rejects a still run whose median HR sits above the sleep band. On a night with
    // pharmacologically- or metabolically-elevated resting HR that keeps HR up WITHOUT the wearer getting up
    // (a supplement protocol, a fever, a hot room, alcohol), that HR-led logic misreads hot-but-motionless
    // sleep as wake. The corroboration rule is: elevated-HR ALONE is insufficient — a run/epoch at the night's
    // quiescent MOTION floor with UNCHANGED posture cannot be called wake on cardiac evidence alone. The
    // per-epoch half lives in the whoop-rs V2 stager; this is the session-detection half.

    /** The relaxed sleep-band multiplier applied to [confirmSleepWithHR] when the run is DEEPLY motion-quiescent.
     *  Wider than [hrSleepBaselineMult] (1.05) so a supplement-elevated but motionless overnight run is not
     *  rejected, yet bounded (the floor) so a genuinely awake still run above this band is still dropped.
     *  Mirrors Swift `quiescentHRSleepMult`. */
    const val quiescentHRSleepMult: Double = 1.30

    /** Per-minute gravity posture variance (g²) at/below which a minute counts as posture-STABLE — the wrist
     *  orientation barely moved within the minute. A minute with too few gravity samples to compute a variance
     *  is conservatively NOT counted as stable (silence ≠ stillness). Mirrors Swift `quiescentPostureVarG2`. */
    const val quiescentPostureVarG2: Double = 0.05

    /** Fraction of a run's minutes that must be posture-STABLE for the run to be DEEPLY motion-quiescent. Set
     *  well above the Stage-0 stillness bar ([stillFraction] 0.70) so only a genuinely motionless run — not an
     *  ordinary restless-but-in-bed night — earns the widened HR band. Mirrors Swift `quiescentStableFrac`. */
    const val quiescentStableFrac: Double = 0.90

    /** Minimum posture-stable minutes with COMPUTABLE variance a run needs before the deeply-quiescent verdict
     *  is trusted at all — a run with almost no dense-gravity minutes can't prove stillness, so it defers to
     *  the strict HR band rather than being waved through. Mirrors Swift `quiescentMinStableMinutes`. */
    const val quiescentMinStableMinutes: Int = 20

    /** Floor (bpm) under [adaptiveOvernightHRBaseline] so a genuinely wakeful era cannot collapse the sleep
     *  band and score wakefulness asleep. Mirrors Swift `adaptiveBaselineFloor`. */
    const val adaptiveBaselineFloor: Double = 40.0

    /** Skip HR refinement (trust gravity) when fewer than this many HR samples. */
    const val hrRefineMinSamples: Int = 30

    /** Consecutive sleep epochs required to declare onset. */
    const val onsetPersistEpochs: Int = 3

    // ── Off-wrist backstop (#500) ─────────────────────────────────────────────
    //
    // A wrist-OFF stretch reads as perfectly still gravity with no contrary motion, so the
    // gravity spine classifies it as sleep — and because the off-wrist epochs carry zero/missing
    // HR the daytime guard treats them as "missing data" and lets them through (a daytime desk-off
    // strap logged a phantom sleep). The backstop measures OFF-WRIST COVERAGE: while worn the strap
    // emits ~1 Hz HR, so a long CONTIGUOUS gap in the HR samples spanning part of a candidate sleep
    // run is a strong off-wrist proxy that works even when explicit WRIST_OFF events are absent;
    // explicit WRIST_OFF→WRIST_ON intervals (when the store surfaces them) sharpen it. A run is dropped
    // only when that coverage reaches maxOffWristSleepFraction of its duration (the FRACTIONAL rule from
    // j0b-dev's #504), so a real night that over-extends into a SHORT off-wrist tail survives. Independent
    // of the daytime band — off-wrist is off-wrist day OR night, and a night-tail continuation does NOT
    // exempt it. Mirrors Swift.

    /**
     * A contiguous HR-sample gap of at least this many minutes contributes to a candidate run's
     * off-wrist coverage. Sized at maxGapMin so a real worn night (dense ~1 Hz HR, or PPG-derived HR
     * on a 5/MG) contributes ~no gap, but a wrist-off stretch (HR flatlines to no samples) contributes
     * its whole span. The edges of the run count too: a run that begins/ends far from its nearest HR
     * sample is partially uncovered.
     */
    const val offWristHRGapMin: Int = 20

    /**
     * FRACTIONAL off-wrist rejection (#500), design credited to j0b-dev's #504 analysis. A candidate
     * sleep run is dropped ONLY when its off-wrist coverage — the UNION of its long HR-gap spans and
     * any WRIST_OFF→WRIST_ON intervals overlapping it — is at least this fraction of its duration. The
     * earlier guard dropped the WHOLE run on ANY contiguous HR gap or ANY single WRIST_OFF blip, which
     * nuked a real night that over-extended into a SHORT off-wrist morning tail (strap removed shortly
     * after waking) or that contained one stray WRIST_OFF event. 0.5 keeps such a night (<50% off-wrist)
     * while still dropping an all-day desk strap (≈100% gap) or a session genuinely spent off-wrist.
     * Mirrors Swift.
     */
    const val maxOffWristSleepFraction: Double = 0.5

    /**
     * Minimum average HR-stream density for the off-wrist HR-gap proxy to be trusted (#507). The proxy
     * reads a >[offWristHRGapMin]-minute hole in HR as "off the wrist" — valid only when HR is otherwise
     * dense. A WHOOP 4.0's SYNCED night is reconstructed mostly from MOTION with sparse, derived HR, whose
     * natural gaps would otherwise read as off-wrist and wrongly DROP a real night. So if the HR stream
     * averages fewer than one sample per this many seconds, we don't assert off-wrist from gaps at all
     * (WRIST_OFF events still apply). Self-consistent: a night sparse enough to be >50% gap-covered is, by
     * definition, below this density, so it is spared. Measured over the whole stream, so an off-wrist HOLE
     * inside an otherwise dense, worn day (#500) is still caught. Mirrors Swift.
     */
    const val hrDenseSpacingS: Int = 600   // one HR sample per 10 minutes, averaged over the stream

    // ── Sparse-gravity robustness (#308) ──────────────────────────────────────
    //
    // On an un-unlocked WHOOP 5.0 the strap backfills mostly v18/v26 records where gravity is
    // sparse/clumped (~25% coverage), so the gravity-only Stage-0 spine fragments the night at
    // every >maxGapMin gravity gap and detectSleep drops every <minSleepMin fragment — collapsing
    // a ~6 h night to ~1 h. The fix derives the in-bed spine from a sustained low-HR stretch and
    // uses gravity stillness only to REFINE it, but is GATED ENTIRELY behind a "gravity is sparse"
    // condition so dense WHOOP-4.0 nights stay BYTE-IDENTICAL (a 4.0 regression is unacceptable).
    // Mirrors Swift.

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
     * When sparse, two adjacent sleep runs separated ONLY by a gravity gap up to this many minutes
     * are merged if the intervening HR stays in the sleep band — so a real night is not shredded
     * into sub-minSleepMin fragments by gravity dropouts. Sized at the daytime-nap floor (a real
     * continuous night never has a true >90 min wake bridge mid-sleep).
     */
    const val sparseBridgeGapMin: Int = 90

    // ── Sparse-gravity gate (#308) ─────────────────────────────────────────────

    /**
     * Largest spacing between consecutive timestamps (seconds), NO upper cap; 0.0 for <2 samples.
     * Used to detect clumped/sparse gravity where the dropouts themselves are the signal: a few
     * long dropouts in otherwise-dense (clumped) motion keep the MEDIAN gap small but still break
     * runs, so the largest gap — not the median — is the right signal (#28).
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
     * True when gravity is too sparse for the gravity-only spine to be trusted across gaps: the
     * gravity timespan covers < sparseGravitySpanFrac of the HR-sample timespan, OR the LARGEST
     * gravity inter-sample gap exceeds maxGapMin. The largest-gap test (not just the median) catches
     * CLUMPED motion — dense bursts split by a few long dropouts, the typical WHOOP 4.0 backfill
     * (#28) — whose median gap stays small yet which still hides run-breaking gaps. Requires a real
     * HR span to compare against — with no/degenerate HR the dense path is kept (false), so a 4.0
     * with absent HR is never reclassified as sparse.
     */
    internal fun isGravitySparse(grav: List<GravitySample>, hr: List<HrSample>): Boolean {
        if (grav.size < 2 || hr.size < 2) return false
        val hrSpan = (hr[hr.size - 1].ts - hr[0].ts).toDouble()
        if (hrSpan <= 0) return false
        val gravSpan = (grav[grav.size - 1].ts - grav[0].ts).toDouble()
        if (gravSpan < sparseGravitySpanFrac * hrSpan) return true
        // #28: clumped 4.0 motion keeps a SMALL median gap yet still contains >maxGapMin dropouts
        // the gravity-only spine shreds the night on. The largest gap catches what a median would
        // miss (largest >= median, so this subsumes the old median check). Flagging sparse only
        // ENABLES buildRuns' HR-vouched bridge — a real wake (HR above the sleep band) still breaks.
        return largestGapS(grav.map { it.ts }) > (maxGapMin * 60).toDouble()
    }

    /**
     * True when a run's ONSET (start), in LOCAL time, falls OUTSIDE the daytime band — i.e. the
     * sleep began at night, not during the day. Anchors a continuous-sleep chain: only a chain
     * that began overnight may carry its tail past the daytime-band start (a late wake).
     * Reimplemented from @vulnix0x4's PR #353.
     */
    internal fun isOvernightOnset(start: Long, tzOffsetSeconds: Long): Boolean {
        val secOfDay = Math.floorMod(start + tzOffsetSeconds, secondsPerDay)
        val hour = (secOfDay / 3_600L).toInt()
        return !(hour >= daytimeBandStartHour && hour < daytimeBandEndHour)
    }

    // ── detectSleep (public) ──────────────────────────────────────────────────

    /**
     * One folded Long per sample stream for the [detectSleep] memo key: folds the COUNT plus every
     * (ts, quantised value) with large odd multipliers — the same every-element discipline as
     * [StagerCache.fingerprint] — so an in-place interior edit (a re-import correcting a value), a
     * truncation, or an append all re-key to a fresh compute and a stale hit is never served.
     * Internal so the fingerprint-completeness test pins those properties directly.
     */
    internal fun <T> streamFingerprint(samples: List<T>, ts: (T) -> Long, quant: (T) -> Long): Long {
        var h = samples.size.toLong() * 2_654_435_761L
        for (e in samples) h = (h * 1_000_003L + ts(e)) * 1_000_003L + quant(e)
        return h
    }

    /** Full memo key for [detectSleep] — every input that steers detection or staging, nothing else. */
    private data class DetectKey(
        val grav: Long, val hr: Long, val rr: Long, val resp: Long, val steps: Long,
        val tz: Long, val wristOff: Long, val band: Long,
    )

    /** Bound ≈ the distinct days of a scoring window (matches the Swift detectSleepCache capacity, 40).
     *  Access-order LRU so the hottest nights survive a long session; an entry is a handful of small
     *  sessions — the multi-hour raw streams are never retained (#707 rule: the cache must not be the
     *  next leak). */
    private const val DETECT_CACHE_MAX_DAYS = 40
    private val detectCache = object : LinkedHashMap<DetectKey, List<DetectedSleep>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DetectKey, List<DetectedSleep>>): Boolean =
            size > DETECT_CACHE_MAX_DAYS
    }
    private val detectCacheLock = Any()

    /** Deep copy across the memo boundary. [DetectedSleep] itself is immutable, but its `stages` are
     *  mutable [StageSegment]s — the exact reason [StagerCache] deep-copies hypnograms — so the memo
     *  holds a private copy and hands every hit a fresh one; a caller reshaping a segment in place can
     *  never poison the cache. Swift needs no copy (value-type structs); Kotlin's mutable StageSegment
     *  does. O(stages), a rounding error next to the detection spine the memo skips. */
    private fun copyDetected(sessions: List<DetectedSleep>): List<DetectedSleep> =
        sessions.map { it.copy(stages = StagerCache.copyOf(it.stages)) }

    /**
     * Detect sleep sessions from biometric streams. Empty/absent gravity → [].
     * Gravity-only input degrades gracefully (HR/RR/resp refinements skipped).
     *
     * [tzOffsetSeconds] is the wall-clock UTC offset (TimeZone.getDefault().getOffset)
     * used ONLY to place each window's center on a LOCAL clock for the daytime false-sleep
     * guard (#90). It defaults to 0 so the pure function and its tests stay UTC; the live
     * call site (IntelligenceEngine) passes the device's real offset.
     *
     * [wristOff] is an optional list of off-wrist [start, end) intervals (unix seconds), paired from
     * the strap's WRIST_OFF/WRIST_ON events by `AnalyticsEngine.offWristIntervals`. When the call site
     * has them (IntelligenceEngine reads `repo.events`), they sharpen the always-on HR-gap off-wrist
     * backstop: a candidate run is dropped when its off-wrist coverage (HR-gap spans UNION these
     * intervals) reaches [maxOffWristSleepFraction] of its duration — the FRACTIONAL rule from #504, so
     * a real night with a short off-wrist tail survives (#500). Defaults to empty (HR-gap proxy only),
     * so the pure function and its tests stay event-free.
     *
     * PERF (#707 parity, mirrors the Swift detectSleepCache): this is the single heaviest analytics
     * call — it sorts the dense full-day gravity stream, builds the delta/still spine, and stages every
     * accepted run — and [IntelligenceEngine.analyzeRecent] re-runs it per day across the window every
     * 15-min tick, so an idempotent re-pass (or a sync that never touched a given day) redid all of it
     * for an identical result; the exact path that ANR'd before #125. Android had only the staging-layer
     * [StagerCache], so the detection spine still re-ran uncached ~21× per pass. The public entry now
     * memoizes the RESULT on a FULL input key (every argument that steers detection or staging: the four
     * streams, tz offset, #500 off-wrist intervals, #531 band state, the V2 toggle) in a bounded LRU. A
     * hit is byte-identical to recomputing — the memo only ever skips work, never changes it.
     */
    fun detectSleep(
        hr: List<HrSample> = emptyList(),
        rr: List<RrInterval> = emptyList(),
        resp: List<RespSample> = emptyList(),
        gravity: List<GravitySample>,
        steps: List<StepSample> = emptyList(),
        tzOffsetSeconds: Long = 0L,
        wristOff: List<Pair<Long, Long>> = emptyList(),
        // The strap's OWN persisted v18 BAND sleep_state per timestamp (0 wake/1 still/2 asleep/3 up),
        // consumed by the whoop-rs morning-stillness re-onset guard. IntelligenceEngine passes the night
        // window's persisted band state; default empty keeps pure-function callers free of it.
        bandSleepState: List<Pair<Long, Int>> = emptyList(),
    ): List<DetectedSleep> {
        // Detection + staging + motion-aware refinement all run in whoop-rs (physio-algo) via one FFI call;
        // this bounded LRU only ever skips a recompute for an identical input, never changes the result.
        val key = DetectKey(
            // Fold the three gravity axes SEPARATELY (raw IEEE-754 bits) so two postures sharing a component
            // sum can't alias; a fingerprint collision only ever costs an extra recompute, never a value.
            grav = streamFingerprint(gravity, { it.ts }) { s ->
                var q = java.lang.Double.doubleToRawLongBits(s.x)
                q = q * 1_000_003L + java.lang.Double.doubleToRawLongBits(s.y)
                q = q * 1_000_003L + java.lang.Double.doubleToRawLongBits(s.z)
                q
            },
            hr = streamFingerprint(hr, { it.ts }) { it.bpm.toLong() },
            rr = streamFingerprint(rr, { it.ts }) { it.rrMs.toLong() },
            resp = streamFingerprint(resp, { it.ts }) { it.raw.toLong() },
            steps = streamFingerprint(steps, { it.ts }) { it.counter.toLong() },
            tz = tzOffsetSeconds,
            wristOff = streamFingerprint(wristOff, { it.first }) { it.second },
            band = streamFingerprint(bandSleepState, { it.first }) { it.second.toLong() },
        )
        synchronized(detectCacheLock) { detectCache[key] }?.let { return copyDetected(it) }
        val sessions = RustSleepStager.analyze(
            hr, rr, resp, gravity, steps, tzOffsetSeconds, wristOff, bandSleepState,
        )
        synchronized(detectCacheLock) { detectCache[key] = copyDetected(sessions) }
        return sessions
    }

    // ── Respiration rate from R-R (RSA) — WHOOP5 on-wire path ────────────────

    /**
     * THE canonical plausible sleeping-respiratory-rate band (bpm). The RSA peak-pick can yield
     * 6–8 bpm at its noise floor, but every consumer (ReadinessEngine illness/readiness) only acts on
     * 8–25 — so a sub-8 estimate used to be persisted-then-silently-ignored. The resp-rate estimator
     * (now in whoop-rs physio-algo, reached via RustScores.respRateFromRr) clamps its output to this
     * band, and ReadinessEngine references this same range, so the stored value can never disagree with
     * what's acted on. (#78) */
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
     * Per-5-min-window RMSSD across a session, each window tagged with the sleep stage at its CENTER (from
     * [stages]) — the source the deep-stage HRV pool ([AnalyticsEngine]) and the HRV test-mode nightly trace
     * read. The stored session avgHrv (the plain mean of these window RMSSDs) is scored in whoop-rs
     * ([RustScores.windowedAvgHrv]); this Kotlin path stays only for the stage-tagged trace/deep-pool.
     * Passing `emptyList()` for [stages] tags every window "?" (the plain-average path doesn't need stages).
     */
    internal fun sessionHrvWindows(
        start: Long, end: Long, rr: List<RrInterval>, stages: List<StageSegment>,
    ): List<HrvWindow> {
        // CONTRACT: `rr` MUST already be ts-sorted (RMSSD is built from SUCCESSIVE differences, so a bucket
        // has to be chronological). The value path passes the loop's pre-sorted `rrS`; the trace caller sorts
        // its own copy. Not sorted here on purpose — re-sorting the value path could reorder same-second RR
        // under an unstable sort and shift the shipped avgHrv. Same contract the original sessionAvgHRV had.
        val seg = rr.filter { it.ts in start..end }
        if (seg.isEmpty()) return emptyList()
        val windowS = 5 * 60L
        val out = ArrayList<HrvWindow>()
        var t = start
        while (t < end) {
            val bucket = seg.filter { it.ts >= t && it.ts < t + windowS }.map { it.rrMs.toDouble() }
            // Full clean (range + Malik ectopic rejection), not just range — matches the
            // analyze() pipeline. The 0x2A37 RR on a WHOOP 5/MG is PPG-derived and noisier
            // than a 4.0's; rMSSD is built from SUCCESSIVE differences, so an un-rejected
            // jitter spike inflates the session HRV. Ectopic rejection drops those (#262/#235).
            // Gap-aware: a dropped ectopic/out-of-range beat must not splice its two neighbours into a
            // spurious successive difference, which is the exact spike the rejection above is meant to
            // remove. See HrvAnalyzer.rmssdGapAware.
            val cleaned = HrvAnalyzer.cleanRRGapAware(bucket)
            val rmssd = if (cleaned.nn.size >= 2) HrvAnalyzer.rmssdGapAware(cleaned.nn, cleaned.contiguous) else null
            val center = t + windowS / 2
            val stage = stages.firstOrNull { center >= it.start && center < it.end }?.stage ?: "?"
            out.add(HrvWindow(startTs = t, stage = stage, cleanBeats = cleaned.nn.size, rmssd = rmssd))
            t += windowS
        }
        return out
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

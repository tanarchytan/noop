package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RespSample
import com.noop.data.RrInterval
import com.noop.data.StepSample
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * SleepStager.kt — sleep/wake detection + APPROXIMATE 4-class staging.
 *
 * Faithful Kotlin port of StrandAnalytics/SleepStager.swift (verified on macOS),
 * itself ported from server/ingest/app/analysis/sleep.py and sleep_features.py.
 *
 * HONEST HEDGING: these stages are APPROXIMATIONS, not PSG-validated, not medical
 * advice. The EEG-free 4-class ceiling is ~65–73% epoch agreement (Walch 2019).
 * Light/deep separation is the weakest link — deep-minute estimates are the least
 * reliable output.
 *
 * Pipeline (30 s epochs):
 *   Stage 0  gravity-stillness sleep/wake spine → in-bed sessions. Cole–Kripke
 *            (te Lindert 30 s) computed as a citable cross-check; HR confirms runs.
 *   Stage 1  per-epoch cardiorespiratory features over a rolling 5-min window
 *            (mean HR, DoG-HR variability, RMSSD/SDNN from RR, resp rate + RRV).
 *   Stage 2  transparent percentile-band classifier → {wake, light, deep, rem}.
 *   Stage 3  median smoothing + physiology re-imposition (no early REM, deep in
 *            the first third of the night).
 *
 * NOTE: frequency-domain HRV features (HF, LF/HF) are omitted (no neurokit2/scipy
 * on-device); the parasympathetic-tone signal is RMSSD only. Respiration rate +
 * RRV are derived from the raw 1 Hz resp channel with a simple peak detector. The
 * classifier seam, percentile bands, smoothing, and physiology rules are
 * reproduced exactly.
 *
 * Types:
 *   - The detected-sleep type is [DetectedSleep] (AnalyticsModels.kt), NOT a Room
 *     entity. Stage segments are [StageSegment] (AnalyticsModels.kt, fields var).
 *   - [HypnogramMetrics] (AnalyticsModels.kt) is returned by [hypnogramMetrics].
 *
 * All `ts` / `start` / `end` are wall-clock unix SECONDS (Long); the Swift source
 * uses Int seconds. Math is done in Double throughout, matching the Swift port.
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

    // ── Stage 1–3 constants (sleep_features.py) ──────────────────────────────

    const val epochS: Double = 30.0
    const val featureWindowS: Double = 5 * 60.0
    const val ckCountDivisor: Double = 100.0
    const val ckCountClip: Double = 300.0
    const val moveDeltaThresholdG: Double = 0.01
    const val hrDogSigma1S: Double = 120.0
    const val hrDogSigma2S: Double = 600.0

    const val stageHRLowPct: Double = 25.0
    const val stageHRHighPct: Double = 70.0
    const val stageHRVHighPct: Double = 70.0
    const val stageHRVarHighPct: Double = 65.0
    const val stageRRVHighPct: Double = 65.0
    const val stageRRVLowPct: Double = 50.0
    const val stageWakeMoveFrac: Double = 0.15
    const val stageStillMoveFrac: Double = 0.10

    /**
     * Fraction of sleep-period epochs that must carry a MISSING per-epoch RMSSD (sparse R-R) for the
     * session's cardiac signal to count as PPG-DERIVED / sparse-cardiac. On a WHOOP 5/MG the PPG-derived
     * HR feeds a noisier per-epoch HR-variance, which inflates `hrVar` on otherwise still, low-HR sleep
     * epochs and was tripping the Stage-2 WAKE rule (which keys on the `hrvarHigh` percentile), so a
     * whole night over-reported WAKE. We already trust `!rmssd.isFinite()` as a PPG/sparse tell for the
     * pro-deep RMSSD handling (#127/#129); at this share across the night it also down-weights the
     * HR-variance half of the WAKE rule. ~50% keeps a real worn 4.0 night (dense R-R) on the strict
     * path and only relaxes nights whose cardiac signal is genuinely sparse/derived. (#705)
     */
    const val cardiacSparseEpochFrac: Double = 0.5

    const val smoothEpochs: Int = 5
    const val noREMAfterOnsetMin: Double = 15.0
    const val deepFirstFraction: Double = 1.0 / 3.0

    /** te Lindert 30 s Cole–Kripke weights [A₋₄..A₊₂]. SI = 0.001·Σ wᵢ·Aᵢ; sleep iff SI<1. */
    val ckWeights: List<Double> = listOf(106.0, 54.0, 58.0, 76.0, 230.0, 74.0, 67.0)
    const val ckScale: Double = 0.001
    const val ckBack: Int = 4
    const val ckFwd: Int = 2

    // ── Gravity deltas ───────────────────────────────────────────────────────

    /**
     * Per-record movement proxy = L2 magnitude of the gravity change vs the
     * previous record. First record → 0. (No dropout sentinel needed: GravitySample
     * always carries finite x/y/z.)
     */
    internal fun gravityDeltas(grav: List<GravitySample>): List<Double> {
        val deltas = ArrayList<Double>(grav.size)
        var prev: GravitySample? = null
        for ((i, r) in grav.withIndex()) {
            if (i == 0) {
                deltas.add(0.0)
            } else {
                val p = prev
                if (p != null) {
                    val dx = p.x - r.x
                    val dy = p.y - r.y
                    val dz = p.z - r.z
                    deltas.add(sqrt(dx * dx + dy * dy + dz * dz))
                } else {
                    deltas.add(0.0)
                }
            }
            prev = r
        }
        return deltas
    }

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

    // ── HR refinement ────────────────────────────────────────────────────────

    private inline fun <T> rowsBetween(rows: List<T>, start: Long, end: Long, ts: (T) -> Long): List<T> =
        rows.filter { ts(it) in start..end }

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

    // ── Stage 1–3: staging over a 30 s epoch grid ────────────────────────────

    /** First persistent-sleep epoch (onset) and last sleep epoch (final wake). */
    internal fun onsetAndFinalWake(ckFlags: List<Boolean>): Pair<Int, Int> {
        val n = ckFlags.size
        if (n == 0) return Pair(0, 0)
        var onset: Int? = null
        var run = 0
        for ((i, s) in ckFlags.withIndex()) {
            run = if (s) run + 1 else 0
            if (run >= onsetPersistEpochs) {
                onset = i - onsetPersistEpochs + 1
                break
            }
        }
        var final: Int? = null
        for (i in n - 1 downTo 0) {
            if (ckFlags[i]) {
                final = i
                break
            }
        }
        val o = onset ?: 0
        var f = final ?: (n - 1)
        if (f < o) f = n - 1
        return Pair(o, f)
    }

    // ── Per-epoch motion (H8 — persisted beside stagesJSON) ───────────────────

    // ── Epoch grid ────────────────────────────────────────────────────────────

    internal class EpochGrid(
        val start: Double,
        val end: Double,
        val edges: List<Double>,
        /** per-epoch summed |Δgravity| (raw, pre-rescale). */
        val counts: List<Double>,
        /** scale-robust per-epoch moving-sample fraction. */
        val moveFrac: List<Double>,
        /** per-epoch mean HR (bpm) or NaN. */
        val hr: List<Double>,
        /** per-epoch RR intervals (ms). */
        val rr: List<List<Double>>,
        /** per-epoch raw respiration samples. */
        val resp: List<List<Double>>,
    ) {
        val nEpochs: Int get() = counts.size
        fun epochMid(i: Int): Double = edges[i] + epochS / 2.0
    }

    internal fun buildEpochGrid(
        start: Double, end: Double,
        gravTimes: List<Long>, gravDeltas: List<Double>,
        hr: List<HrSample>, rr: List<RrInterval>, resp: List<RespSample>,
    ): EpochGrid {
        if (end <= start) {
            return EpochGrid(
                start = start, end = end, edges = listOf(start), counts = emptyList(),
                moveFrac = emptyList(), hr = emptyList(), rr = emptyList(), resp = emptyList(),
            )
        }
        val nEpochs = maxOf(1, ceil((end - start) / epochS).toInt())
        val edges = DoubleArray(nEpochs + 1) { start + it.toDouble() * epochS }
        edges[nEpochs] = maxOf(edges[nEpochs], end)

        val counts = DoubleArray(nEpochs)
        val moveN = IntArray(nEpochs)
        val gravN = IntArray(nEpochs)
        val hrSum = DoubleArray(nEpochs)
        val hrCnt = IntArray(nEpochs)
        val rrBuckets = Array(nEpochs) { ArrayList<Double>() }
        val respBuckets = Array(nEpochs) { ArrayList<Double>() }

        fun idx(ts: Double): Int? {
            if (ts < start || ts >= end) {
                if (ts == end) return nEpochs - 1
                return null
            }
            val i = ((ts - start) / epochS).toInt()
            return minOf(i, nEpochs - 1)
        }

        for (k in gravTimes.indices) {
            val i = idx(gravTimes[k].toDouble()) ?: continue
            counts[i] += gravDeltas[k]
            gravN[i] += 1
            if (gravDeltas[k] >= moveDeltaThresholdG) moveN[i] += 1
        }
        for (r in hr) {
            val i = idx(r.ts.toDouble()) ?: continue
            hrSum[i] += r.bpm.toDouble()
            hrCnt[i] += 1
        }
        for (r in rr) {
            val i = idx(r.ts.toDouble()) ?: continue
            rrBuckets[i].add(r.rrMs.toDouble())
        }
        for (r in resp) {
            val i = idx(r.ts.toDouble()) ?: continue
            respBuckets[i].add(r.raw.toDouble())
        }

        val hrMean = List(nEpochs) { if (hrCnt[it] > 0) hrSum[it] / hrCnt[it].toDouble() else Double.NaN }
        // No gravity coverage → 1.0 (treat as moving; conservative).
        val moveFrac = List(nEpochs) { if (gravN[it] > 0) moveN[it].toDouble() / gravN[it].toDouble() else 1.0 }

        return EpochGrid(
            start = start, end = end, edges = edges.toList(), counts = counts.toList(),
            moveFrac = moveFrac, hr = hrMean,
            rr = rrBuckets.map { it.toList() }, resp = respBuckets.map { it.toList() },
        )
    }

    // ── Cole–Kripke ────────────────────────────────────────────────────────────

    internal fun rescaleCounts(counts: List<Double>): List<Double> =
        counts.map { minOf(it / ckCountDivisor, ckCountClip) }

    internal fun coleKripke(rescaled: List<Double>): List<Boolean> {
        val n = rescaled.size
        val flags = ArrayList<Boolean>(n)
        for (i in 0 until n) {
            var si = 0.0
            for ((k, w) in ckWeights.withIndex()) {
                val j = i - ckBack + k
                val a = if (j in 0 until n) rescaled[j] else 0.0
                si += w * a
            }
            si *= ckScale
            flags.add(si < 1.0)
        }
        return flags
    }

    // ── Walch difference-of-Gaussians HR variability ─────────────────────────

    internal fun gaussianKernel(sigmaS: Double, dtS: Double = epochS): List<Double> {
        val sigma = maxOf(sigmaS / dtS, 1e-6) // σ in epochs
        val radius = maxOf(1, ceil(3 * sigma).toInt())
        val k = ArrayList<Double>(2 * radius + 1)
        for (x in -radius..radius) {
            k.add(exp(-0.5 * (x.toDouble() / sigma).pow(2)))
        }
        val sum = k.sum()
        return k.map { it / sum }
    }

    /** Same-length convolution with reflect padding (edge-stable). */
    internal fun convolveReflect(x: List<Double>, kernel: List<Double>): List<Double> {
        val r = kernel.size / 2
        // A signal shorter than the kernel radius can't be reflect-padded (the mirror reads x[r]
        // and x[x.size-2-i]) — return it unchanged rather than indexing out of bounds. In practice
        // the only caller is gated by the 60-min session floor, so this is defensive.
        if (r == 0 || x.size <= r) return x
        // Reflect padding: numpy 'reflect' mirrors WITHOUT repeating the edge sample.
        val padded = ArrayList<Double>(x.size + 2 * r)
        for (i in 0 until r) padded.add(x[r - i]) // x[r], x[r-1], ... x[1]
        padded.addAll(x)
        for (i in 0 until r) padded.add(x[x.size - 2 - i]) // x[n-2], x[n-3], ...
        // Valid convolution, then take the first x.count outputs.
        val out = ArrayList<Double>(x.size)
        val m = kernel.size
        // np.convolve(padded, kernel, 'valid') has length padded.count - m + 1.
        for (i in 0..(padded.size - m)) {
            var acc = 0.0
            for (j in 0 until m) acc += padded[i + j] * kernel[m - 1 - j]
            out.add(acc)
            if (out.size == x.size) break
        }
        return out
    }

    /**
     * DoG-filtered HR (σ1=120 s minus σ2=600 s). NaNs linearly interpolated first;
     * all-NaN → zeros.
     */
    internal fun dogHRVariability(hrPerEpoch: List<Double>): List<Double> {
        val n = hrPerEpoch.size
        if (n == 0) return emptyList()
        val maskIdx = (0 until n).filter { !hrPerEpoch[it].isNaN() }
        if (maskIdx.isEmpty()) return List(n) { 0.0 }

        // Linear interpolation over the grid (numpy.interp semantics: clamp at edges).
        val filled = DoubleArray(n)
        val first = maskIdx.first()
        val last = maskIdx.last()
        for (i in 0 until n) {
            if (!hrPerEpoch[i].isNaN()) {
                filled[i] = hrPerEpoch[i]
                continue
            }
            // find surrounding known points
            if (i <= first) {
                filled[i] = hrPerEpoch[first]
                continue
            }
            if (i >= last) {
                filled[i] = hrPerEpoch[last]
                continue
            }
            var lo = first
            var hi = last
            for (m in maskIdx) {
                if (m <= i) lo = m
                if (m >= i) {
                    hi = m
                    break
                }
            }
            if (hi == lo) {
                filled[i] = hrPerEpoch[lo]
            } else {
                val frac = (i - lo).toDouble() / (hi - lo).toDouble()
                filled[i] = hrPerEpoch[lo] + frac * (hrPerEpoch[hi] - hrPerEpoch[lo])
            }
        }

        val k1 = gaussianKernel(sigmaS = hrDogSigma1S)
        val k2 = gaussianKernel(sigmaS = hrDogSigma2S)
        val g1 = convolveReflect(filled.toList(), k1)
        val g2 = convolveReflect(filled.toList(), k2)
        return List(n) { g1[it] - g2[it] }
    }

    // ── Respiration rate + RRV (raw 1 Hz) ────────────────────────────────────

    /**
     * Estimate respiratory rate (breaths/min) and RRV (s) from a raw resp window.
     * Detrend → peak-pick (≥2 s apart) → breath intervals (1.5–12 s) → rate =
     * 60/median interval, RRV = std of intervals. (NaN, NaN) when too few samples.
     *
     * Faithful port of sleep_features.resp_rate_and_rrv using a simple local-maxima
     * peak finder. Returned as a Pair(rate, rrv).
     */
    internal fun respRateAndRRV(respRaw: List<Double>, dtS: Double = 1.0): Pair<Double, Double> {
        val nan = Double.NaN
        if (respRaw.size < 8) return Pair(nan, nan)
        val mean = respRaw.sum() / respRaw.size.toDouble()
        val x = respRaw.map { it - mean }
        if (x.all { abs(it) < 1e-12 }) return Pair(nan, nan)

        val std = standardDeviation(x)
        if (std <= 0) return Pair(nan, nan)

        val minDistance = maxOf(2, (2.0 / dtS).roundToInt())
        val peaks = findPeaks(x, distance = minDistance, height = 0.0)
        if (peaks.size < 3) return Pair(nan, nan)

        val intervals = ArrayList<Double>()
        for (i in 1 until peaks.size) {
            val iv = (peaks[i] - peaks[i - 1]).toDouble() * dtS
            if (iv in 1.5..12.0) intervals.add(iv)
        }
        if (intervals.size < 2) return Pair(nan, nan)
        val rate = 60.0 / HrvAnalyzer.median(intervals)
        val rrv = standardDeviation(intervals) // population std (numpy default)
        return Pair(rate, rrv)
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

    // ── Per-epoch features ──────────────────────────────────────────────────

    internal class EpochFeatures(
        val index: Int,
        val midTs: Double,
        /** rescaled Cole–Kripke activity count. */
        val count: Double,
        val moveFrac: Double,
        val ckSleep: Boolean,
        /** mean HR over the feature window. */
        val hr: Double,
        /** Walch DoG-HR windowed std. */
        val hrVar: Double,
        /** ms. */
        val rmssd: Double,
        /** ms. */
        val sdnn: Double,
        /** breaths/min. */
        val respRate: Double,
        /** respiratory-rate variability (s). */
        val rrv: Double,
        /** normalized time since onset, 0..1. */
        val clock: Double,
    )

    internal fun extractFeatures(
        grid: EpochGrid, ckFlags: List<Boolean>, dogHR: List<Double>,
        onsetIdx: Int, finalWakeIdx: Int,
    ): List<EpochFeatures> {
        val n = grid.nEpochs
        val rescaled = rescaleCounts(grid.counts)
        val halfW = (featureWindowS / epochS / 2).roundToInt()
        val span = maxOf(1, finalWakeIdx - onsetIdx).toDouble()

        val feats = ArrayList<EpochFeatures>(n)
        for (i in 0 until n) {
            val lo = maxOf(0, i - halfW)
            val hi = minOf(n, i + halfW + 1)

            val winHR = (lo until hi).map { grid.hr[it] }.filter { !it.isNaN() }
            val hrMean = if (winHR.isEmpty()) Double.NaN else winHR.sum() / winHR.size.toDouble()

            val winDog = (lo until hi).map { if (dogHR.isEmpty()) 0.0 else dogHR[it] }
            val hrVar = if (winDog.size >= 2) standardDeviation(winDog) else Double.NaN

            // RMSSD/SDNN over the pooled RR window (range-filtered, like the
            // Python per-epoch hrv_from_rr which uses RAW range-filtered RR).
            val winRR = ArrayList<Double>()
            for (j in lo until hi) winRR.addAll(grid.rr[j])
            val filteredRR = HrvAnalyzer.rangeFilter(winRR)
            val rmssd = if (filteredRR.size >= 5) (HrvAnalyzer.rmssdRaw(filteredRR) ?: Double.NaN) else Double.NaN
            val sdnn = if (filteredRR.size >= 5) (HrvAnalyzer.sdnnRaw(filteredRR) ?: Double.NaN) else Double.NaN

            val winResp = ArrayList<Double>()
            for (j in lo until hi) winResp.addAll(grid.resp[j])
            val (respRate, rrv) = respRateAndRRV(winResp)

            val clock = minOf(1.0, maxOf(0.0, (i - onsetIdx).toDouble() / span))

            feats.add(
                EpochFeatures(
                    index = i, midTs = grid.epochMid(i), count = rescaled[i],
                    moveFrac = grid.moveFrac[i],
                    ckSleep = if (i < ckFlags.size) ckFlags[i] else true,
                    hr = hrMean, hrVar = hrVar, rmssd = rmssd, sdnn = sdnn,
                    respRate = respRate, rrv = rrv, clock = clock,
                )
            )
        }
        return feats
    }

    // ── Percentile helper ─────────────────────────────────────────────────────

    /** numpy-style linear-interpolated percentile over finite values; null if none. */
    internal fun percentile(values: List<Double>, pct: Double): Double? {
        val vals = values.filter { it.isFinite() }.sorted()
        if (vals.isEmpty()) return null
        return percentileSorted(vals, pct)
    }

    /**
     * Linear-interpolated percentile of an already-sorted sequence (numpy-style).
     * Inlined from Swift `StrainScorer.percentile` (not yet ported to Kotlin); same
     * algorithm so a later StrainScorer port stays consistent.
     */
    private fun percentileSorted(sortedValues: List<Double>, pct: Double): Double {
        val n = sortedValues.size
        if (n == 0) return 0.0
        if (n == 1) return sortedValues[0]
        val position = (pct / 100.0) * (n - 1).toDouble()
        val lower = position.toInt()
        val upper = minOf(lower + 1, n - 1)
        val frac = position - lower.toDouble()
        return sortedValues[lower] + frac * (sortedValues[upper] - sortedValues[lower])
    }

    // ── Classifier seam (Stage 2) ─────────────────────────────────────────────

    internal fun classifyEpochs(features: List<EpochFeatures>): List<String> {
        val n = features.size
        if (n == 0) return emptyList()

        // Session-relative reference distributions over SLEEP-PERIOD epochs.
        val sleepFeats = if (features.any { it.ckSleep }) features.filter { it.ckSleep } else features
        val hrLo = percentile(sleepFeats.map { it.hr }, stageHRLowPct)
        val hrHi = percentile(sleepFeats.map { it.hr }, stageHRHighPct)
        val rmssdHi = percentile(sleepFeats.map { it.rmssd }, stageHRVHighPct)
        val hrvarHi = percentile(sleepFeats.map { it.hrVar }, stageHRVarHighPct)
        val rrvHi = percentile(sleepFeats.map { it.rrv }, stageRRVHighPct)
        val rrvLo = percentile(sleepFeats.map { it.rrv }, stageRRVLowPct)
        val cardiacSparse = isCardiacSparse(sleepFeats)

        return features.map {
            classifyOne(it, hrLo = hrLo, hrHi = hrHi, rmssdHi = rmssdHi,
                hrvarHi = hrvarHi, rrvHi = rrvHi, rrvLo = rrvLo,
                cardiacSparse = cardiacSparse)
        }
    }

    /**
     * Session-level PPG-derived / sparse-cardiac tell: most sleep-period epochs carry NO finite
     * per-epoch RMSSD (sparse R-R). On those nights the HR is PPG-derived and its windowed variance
     * (`hrVar`) is noisier, so the percentile `hrvarHigh` bar fires on genuinely still, low-HR sleep —
     * which the WAKE rule must NOT treat as cardiac activation. Same `!rmssd.isFinite()` signal already
     * trusted for the pro-deep RMSSD handling (#127/#129), aggregated across the night. (#705)
     */
    internal fun isCardiacSparse(sleepFeats: List<EpochFeatures>): Boolean {
        if (sleepFeats.isEmpty()) return false
        val sparse = sleepFeats.count { !it.rmssd.isFinite() }
        return sparse.toDouble() >= cardiacSparseEpochFrac * sleepFeats.size.toDouble()
    }

    internal fun classifyOne(
        f: EpochFeatures, hrLo: Double?, hrHi: Double?,
        rmssdHi: Double?, hrvarHi: Double?, rrvHi: Double?, rrvLo: Double?,
        cardiacSparse: Boolean = false,
    ): String {
        val hasHR = f.hr.isFinite()
        val hrLow = hasHR && hrLo != null && f.hr <= hrLo
        val hrHigh = hasHR && hrHi != null && f.hr >= hrHi

        // NOTE: HF omitted (no neurokit2). Parasympathetic tone = RMSSD only. A MISSING per-epoch
        // RMSSD (sparse R-R, common on BLE-offloaded nights and especially 5/MG) is treated as
        // pro-deep rather than deep-blocking — mirroring how a missing respiration value is handled
        // below — so those nights stop decoding 0 m of deep sleep despite a real depth signature
        // (still + low HR + regular breathing). An epoch WITH a finite RMSSD must still clear the
        // high-tone bar. (#127, #129)
        val parasympOK = (!f.rmssd.isFinite()) || (rmssdHi != null && f.rmssd >= rmssdHi)

        val hrvarHigh = f.hrVar.isFinite() && hrvarHi != null && f.hrVar >= hrvarHi
        val cardiacActivated = hrHigh || hrvarHigh

        // WAKE-specific cardiac vetting. On a PPG-derived / sparse-cardiac night the per-epoch HR-variance
        // is noisy, so `hrvarHigh` fires on still, low-HR sleep and used to flip those epochs to WAKE. When
        // the session is sparse we DOWN-WEIGHT hrVar for the wake promotion and require a real elevated HR
        // (`hrHigh`) — the down-weighting mirrors how sparse R-R is trusted for the pro-deep RMSSD handling.
        // Dense 4.0 nights keep the full `hrHigh || hrvarHigh` signal, so their behaviour is unchanged. (#705)
        val cardiacActivatedForWake = if (cardiacSparse) hrHigh else cardiacActivated

        val rrvIrregular = f.rrv.isFinite() && rrvHi != null && f.rrv >= rrvHi
        // Missing respiration (NaN RRV) treated as "regular" (pro-deep bias).
        val rrvRegular = (!f.rrv.isFinite()) || (rrvLo != null && f.rrv <= rrvLo)

        val still = f.moveFrac <= stageStillMoveFrac
        val moving = f.moveFrac >= stageWakeMoveFrac

        // WAKE: sustained motion + activated cardiac (or no HR to vet motion). On a sparse/PPG night the
        // cardiac half is vetted by HR only (see `cardiacActivatedForWake`), so noisy hrVar no longer
        // over-promotes still sleep to wake. (#705)
        if (moving && (cardiacActivatedForWake || !hasHR)) return "wake"
        // DEEP: still + low HR + regular respiration, with high parasympathetic tone when measurable.
        if (still && parasympOK && hrLow && rrvRegular) return "deep"
        // REM: still body + activated cardiac + irregular respiration.
        if (still && cardiacActivated && rrvIrregular) return "rem"
        // REM fallback when respiration unavailable: require BOTH cardiac signals.
        if (still && hrHigh && hrvarHigh && !f.rrv.isFinite()) return "rem"
        return "light"
    }

    // ── Post-processing (Stage 3) ─────────────────────────────────────────────

    internal fun smoothLabels(labels: List<String>, window: Int = smoothEpochs): List<String> {
        val n = labels.size
        if (n == 0 || window <= 1) return labels
        var w = window
        if (w % 2 == 0) w += 1
        val half = w / 2
        val out = ArrayList<String>(n)
        for (i in 0 until n) {
            val lo = maxOf(0, i - half)
            val hi = minOf(n, i + half + 1)
            val counts = HashMap<String, Int>()
            val order = ArrayList<String>()
            for (idx in lo until hi) {
                val s = labels[idx]
                if (counts[s] == null) order.add(s)
                counts[s] = (counts[s] ?: 0) + 1
            }
            val best = counts.values.maxOrNull()
            if (best == null) { out.add(labels[i]); continue }
            val winners = order.filter { counts[it] == best } // insertion order preserved
            out.add(if (winners.contains(labels[i])) labels[i] else winners[0])
        }
        return out
    }

    internal fun reimposePhysiology(
        labels: List<String>, features: List<EpochFeatures>,
        onsetIdx: Int, finalWakeIdx: Int,
    ): List<String> {
        val out = labels.toMutableList()
        val noREMEpochs = (noREMAfterOnsetMin * 60.0 / epochS).roundToInt()
        // "Deep is front-loaded" re-imposes scattered late "deep" back to light — BUT only when there's
        // deep in the first third to anchor that prior. If the whole detected deep block lands later
        // (individual variation, or HR/HRV-only staging without respiration placing the deepest, lowest-HR
        // window later), zeroing it out gives a wrong "0 m deep"; keeping the best estimate is better. (#127)
        val hasEarlyDeep = labels.indices.any { labels[it] == "deep" && features[it].clock <= deepFirstFraction }
        for ((i, f) in features.withIndex()) {
            if (i < onsetIdx || i > finalWakeIdx) continue
            if (out[i] == "rem" && (i - onsetIdx) < noREMEpochs) out[i] = "light"
            if (out[i] == "deep" && f.clock > deepFirstFraction && hasEarlyDeep) out[i] = "light"
        }
        return out
    }

    // ── REM-funnel diagnostic (#688) ──────────────────────────────────────────

    // 0% REM over a whole night is physiologically implausible (healthy adults cycle ~20–25% REM),
    // so a 0%-REM hypnogram — common on WHOOP 4.0 nights staged WITHOUT a respiration channel —
    // points at the STAGER, not the sleeper. The REM path in [classifyOne] is gated by three
    // predicates (still body + activated cardiac + irregular respiration), with a no-resp fallback
    // (still + high HR + high HR-variability), and any surviving early-REM is then stripped by the
    // no-REM-after-onset re-imposition. This pure, READ-ONLY diagnostic re-runs that exact funnel and
    // counts where REM was lost — WITHOUT changing a single label or score. It is a triage surface,
    // logged by the caller, never a scoring change. Mirrors Swift `remFunnelDiagnostic`. (#688)

    /**
     * Why REM funneled toward zero for one staged session window. Counts are over the SLEEP-PERIOD
     * epochs (onset…finalWake) the classifier actually ranges; pure + deterministic; shares the exact
     * classifier seam with [stageSession]. Mirrors Swift `SleepStager.REMFunnelDiagnostic`. (#688)
     */
    data class REMFunnelDiagnostic(
        /** Sleep-period epochs considered (onset…finalWake inclusive). */
        val sleepEpochs: Int,
        /** Epochs the classifier labelled "rem" BEFORE smoothing / re-imposition. */
        val remAtClassify: Int,
        /** "rem" epochs surviving the no-REM-after-onset re-imposition (the final hypnogram's REM). */
        val remAfterReimpose: Int,
        /** Classified-REM epochs stripped specifically by the 15-min onset guard. */
        val remStrippedByOnsetGuard: Int,
        /**
         * Whether ANY epoch carried a finite respiration-variability feature (the resp channel was
         * usable). False ⇒ the whole night ran the no-resp REM fallback — the dominant 4.0 cause.
         */
        val respChannelPresent: Boolean,
        /** Body not still enough (moveFrac above the still bar). */
        val blockedNotStill: Int,
        /** Neither HR-high nor HR-variability-high. */
        val blockedNoCardiacActivation: Int,
        /** Resp present but NOT irregular (regular breathing). */
        val blockedRespRegular: Int,
        /** Resp absent and the stricter no-resp REM bar unmet. */
        val blockedNoRespFallbackBar: Int,
        /** Won a non-REM stage outright (wake/deep/light) before any REM gate — not a REM rejection. */
        val wonOtherStage: Int,
    ) {
        /** True when the final hypnogram carries no REM at all — the case this diagnostic triages. */
        val isZeroREM: Boolean get() = remAfterReimpose == 0

        /** One human-readable line for the caller to LOG. No I/O here — the engine stays pure. */
        val summary: String
            get() = "REM-funnel: $sleepEpochs sleep-epochs, classify=$remAtClassify rem, " +
                "final=$remAfterReimpose rem (onset-guard stripped $remStrippedByOnsetGuard); " +
                "resp=${if (respChannelPresent) "present" else "ABSENT"}; " +
                "blocked[notStill=$blockedNotStill, noCardiac=$blockedNoCardiacActivation, " +
                "respRegular=$blockedRespRegular, noRespBar=$blockedNoRespFallbackBar], " +
                "otherStage=$wonOtherStage"
    }

    /**
     * Per-epoch reason REM was rejected, evaluated in classifier precedence order. `REM_ELIGIBLE`
     * means the epoch WOULD be labelled REM. Internal — drives [remFunnelDiagnostic].
     */
    internal enum class REMRejectReason {
        REM_ELIGIBLE, WON_OTHER_STAGE, NOT_STILL, NO_CARDIAC_ACTIVATION, RESP_REGULAR, NO_RESP_FALLBACK_BAR
    }

    /**
     * Classify a single epoch's REM-eligibility AND, when not eligible, the FIRST reason it failed —
     * using the exact predicates and precedence of [classifyOne] so the diagnostic can never diverge
     * from the real classifier. Read-only. Mirrors Swift `remRejectReason`. (#688)
     */
    internal fun remRejectReason(
        f: EpochFeatures, hrLo: Double?, hrHi: Double?,
        rmssdHi: Double?, hrvarHi: Double?, rrvHi: Double?, rrvLo: Double?,
        cardiacSparse: Boolean = false,
    ): REMRejectReason {
        // Mirror classifyOne's derived predicates exactly.
        val hasHR = f.hr.isFinite()
        val hrLow = hasHR && hrLo != null && f.hr <= hrLo
        val hrHigh = hasHR && hrHi != null && f.hr >= hrHi
        val parasympOK = (!f.rmssd.isFinite()) || (rmssdHi != null && f.rmssd >= rmssdHi)
        val hrvarHigh = f.hrVar.isFinite() && hrvarHi != null && f.hrVar >= hrvarHi
        val cardiacActivated = hrHigh || hrvarHigh
        val cardiacActivatedForWake = if (cardiacSparse) hrHigh else cardiacActivated
        val rrvIrregular = f.rrv.isFinite() && rrvHi != null && f.rrv >= rrvHi
        val rrvRegular = (!f.rrv.isFinite()) || (rrvLo != null && f.rrv <= rrvLo)
        val still = f.moveFrac <= stageStillMoveFrac
        val moving = f.moveFrac >= stageWakeMoveFrac

        // classifyOne precedence: WAKE, then DEEP, then REM (then REM fallback), else LIGHT.
        // An epoch that wins WAKE or DEEP was never a REM candidate.
        if (moving && (cardiacActivatedForWake || !hasHR)) return REMRejectReason.WON_OTHER_STAGE  // → wake
        if (still && parasympOK && hrLow && rrvRegular) return REMRejectReason.WON_OTHER_STAGE // → deep
        // From here the epoch did NOT win wake/deep; it is either REM or falls through to LIGHT.
        if (still && cardiacActivated && rrvIrregular) return REMRejectReason.REM_ELIGIBLE
        if (still && hrHigh && hrvarHigh && !f.rrv.isFinite()) return REMRejectReason.REM_ELIGIBLE
        // Not REM → attribute to the FIRST unmet REM precondition (in REM-rule order).
        if (!still) return REMRejectReason.NOT_STILL
        if (!cardiacActivated) return REMRejectReason.NO_CARDIAC_ACTIVATION
        if (f.rrv.isFinite()) return REMRejectReason.RESP_REGULAR  // resp present but not irregular
        return REMRejectReason.NO_RESP_FALLBACK_BAR                 // resp absent and no-resp bar unmet
    }

    /**
     * Read-only REM-funnel triage for ONE in-bed window [start, end] (#688). Re-runs the SAME
     * Stage-0→3 staging seam [stageSession] uses (epoch grid → Cole–Kripke → features → classify →
     * smooth → re-impose), but instead of emitting a hypnogram it COUNTS where REM was lost. Changes
     * NOTHING: no label, no score, no session. Returns null only when the window has too little gravity
     * to grid (mirroring [stageSession]'s degenerate fallback, which carries no REM to explain). The
     * caller logs `.summary`; tests assert the counts. Pure + deterministic. Mirrors Swift. (#688)
     */
    fun remFunnelDiagnostic(
        start: Long, end: Long, grav: List<GravitySample>,
        hr: List<HrSample>, rr: List<RrInterval>, resp: List<RespSample>,
    ): REMFunnelDiagnostic? {
        val gSeg = rowsBetween(grav, start, end) { it.ts }
        if (gSeg.size < 2) return null
        val gDeltas = gravityDeltas(gSeg)
        val gTimes = gSeg.map { it.ts }
        val hrSeg = rowsBetween(hr, start, end) { it.ts }
        val rrSeg = rowsBetween(rr, start, end) { it.ts }
        val respSeg = rowsBetween(resp, start, end) { it.ts }

        val grid = buildEpochGrid(
            start = start.toDouble(), end = end.toDouble(),
            gravTimes = gTimes, gravDeltas = gDeltas,
            hr = hrSeg, rr = rrSeg, resp = respSeg,
        )
        if (grid.nEpochs == 0) return null

        val rescaled = rescaleCounts(grid.counts)
        val ckFlags = coleKripke(rescaled)
        val (onsetIdx, finalWakeIdx) = onsetAndFinalWake(ckFlags)
        val dogHR = dogHRVariability(grid.hr)
        val feats = extractFeatures(grid = grid, ckFlags = ckFlags, dogHR = dogHR,
            onsetIdx = onsetIdx, finalWakeIdx = finalWakeIdx)

        // The SAME session-relative reference percentiles classifyEpochs derives.
        val sleepFeats = if (feats.any { it.ckSleep }) feats.filter { it.ckSleep } else feats
        val hrLo = percentile(sleepFeats.map { it.hr }, stageHRLowPct)
        val hrHi = percentile(sleepFeats.map { it.hr }, stageHRHighPct)
        val rmssdHi = percentile(sleepFeats.map { it.rmssd }, stageHRVHighPct)
        val hrvarHi = percentile(sleepFeats.map { it.hrVar }, stageHRVarHighPct)
        val rrvHi = percentile(sleepFeats.map { it.rrv }, stageRRVHighPct)
        val rrvLo = percentile(sleepFeats.map { it.rrv }, stageRRVLowPct)
        val cardiacSparse = isCardiacSparse(sleepFeats)

        // Classify + post-process exactly as stageSession does, so we explain the SAME hypnogram.
        val labels = classifyEpochs(feats)
        val smoothed = smoothLabels(labels)
        val reimposed = reimposePhysiology(smoothed, features = feats,
            onsetIdx = onsetIdx, finalWakeIdx = finalWakeIdx)

        val noREMEpochs = (noREMAfterOnsetMin * 60.0 / epochS).roundToInt()
        var sleepEpochs = 0; var remAtClassify = 0; var remAfterReimpose = 0; var remStrippedByOnsetGuard = 0
        var blockedNotStill = 0; var blockedNoCardiacActivation = 0; var blockedRespRegular = 0
        var blockedNoRespFallbackBar = 0; var wonOtherStage = 0
        var respChannelPresent = false

        for (i in onsetIdx..maxOf(onsetIdx, finalWakeIdx)) {
            if (i >= feats.size) break
            val f = feats[i]
            sleepEpochs += 1
            if (f.rrv.isFinite()) respChannelPresent = true
            // Per-epoch REM reason at the raw classifier seam (pre-smoothing) — the funnel's mouth.
            when (remRejectReason(f, hrLo = hrLo, hrHi = hrHi, rmssdHi = rmssdHi,
                hrvarHi = hrvarHi, rrvHi = rrvHi, rrvLo = rrvLo,
                cardiacSparse = cardiacSparse)) {
                REMRejectReason.REM_ELIGIBLE -> remAtClassify += 1
                REMRejectReason.WON_OTHER_STAGE -> wonOtherStage += 1
                REMRejectReason.NOT_STILL -> blockedNotStill += 1
                REMRejectReason.NO_CARDIAC_ACTIVATION -> blockedNoCardiacActivation += 1
                REMRejectReason.RESP_REGULAR -> blockedRespRegular += 1
                REMRejectReason.NO_RESP_FALLBACK_BAR -> blockedNoRespFallbackBar += 1
            }
            // Final-hypnogram REM (post smooth + re-impose) and the onset-guard strip.
            if (reimposed[i] == "rem") remAfterReimpose += 1
            // The re-imposition strips a SMOOTHED "rem" epoch inside the onset guard → light; count
            // the strip off the smoothed labels reimpose actually sees (exact, not the raw seam).
            if (smoothed[i] == "rem" && (i - onsetIdx) < noREMEpochs) remStrippedByOnsetGuard += 1
        }

        return REMFunnelDiagnostic(
            sleepEpochs = sleepEpochs, remAtClassify = remAtClassify, remAfterReimpose = remAfterReimpose,
            remStrippedByOnsetGuard = remStrippedByOnsetGuard, respChannelPresent = respChannelPresent,
            blockedNotStill = blockedNotStill, blockedNoCardiacActivation = blockedNoCardiacActivation,
            blockedRespRegular = blockedRespRegular, blockedNoRespFallbackBar = blockedNoRespFallbackBar,
            wonOtherStage = wonOtherStage,
        )
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

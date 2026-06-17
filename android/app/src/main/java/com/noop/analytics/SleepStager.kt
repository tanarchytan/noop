package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.data.RespSample
import com.noop.data.RrInterval
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
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

    /** Seconds in a calendar day (for local-hour-of-day arithmetic). */
    const val secondsPerDay: Long = 86_400L

    /** Floor on the rolling-window size in samples. */
    const val minWindowSamples: Int = 3

    /** A run is HR-confirmed only if mean HR ≤ baseline × this. */
    const val hrSleepBaselineMult: Double = 1.05

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

    const val smoothEpochs: Int = 5
    const val noREMAfterOnsetMin: Double = 15.0
    const val deepFirstFraction: Double = 1.0 / 3.0

    /**
     * Fragment-merge threshold (#274). A staged run shorter than this is "noise": the
     * WHOOP 5/MG banks sparse motion, so the stager emits lots of sub-minute stage flecks
     * and the hypnogram reads choppier than WHOOP's. mergeFragments (a DISPLAY/scoring
     * smoothing applied AFTER staging, never to the underlying detection) absorbs runs
     * below this into their neighbours. 3 min is conservative — long enough to clear the
     * fleck noise, short enough to leave a genuine stage transition (a real deep or REM
     * block runs many minutes) untouched. Mirrors Swift.
     */
    const val fragmentMergeMin: Double = 3.0

    /** fragmentMergeMin expressed in 30 s epochs (6). A run with < this many epochs merges. */
    val fragmentMergeEpochs: Int = (fragmentMergeMin * 60.0 / epochS).roundToInt()

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

    /** Median spacing between consecutive timestamps, restricted to (0, 300 s). */
    internal fun medianIntervalS(times: List<Long>): Double {
        if (times.size < 2) return defaultIntervalS
        val gaps = ArrayList<Double>(times.size)
        for (i in 0 until times.size - 1) {
            val g = (times[i + 1] - times[i]).toDouble()
            if (g > 0 && g < 300) gaps.add(g)
        }
        if (gaps.isEmpty()) return defaultIntervalS
        gaps.sort()
        return maxOf(gaps[gaps.size / 2], 1.0)
    }

    internal fun windowSize(times: List<Long>): Int {
        val interval = medianIntervalS(times)
        return maxOf(minWindowSamples, (stillWindowMin * 60 / interval).toInt())
    }

    // ── Sparse-gravity gate (#308) ─────────────────────────────────────────────

    /**
     * Median spacing between consecutive timestamps with NO upper cap (unlike medianIntervalS,
     * which restricts to <300 s to infer a sample rate). Used to detect clumped/sparse gravity
     * where the dropouts themselves are the signal. 0 for <2 samples.
     */
    internal fun medianGapS(times: List<Long>): Double {
        if (times.size < 2) return 0.0
        val gaps = ArrayList<Double>(times.size)
        for (i in 0 until times.size - 1) {
            val g = (times[i + 1] - times[i]).toDouble()
            if (g > 0) gaps.add(g)
        }
        if (gaps.isEmpty()) return 0.0
        gaps.sort()
        return gaps[gaps.size / 2]
    }

    /**
     * True when gravity is too sparse for the gravity-only spine to be trusted across gaps: the
     * gravity timespan covers < sparseGravitySpanFrac of the HR-sample timespan, OR the median
     * gravity inter-sample gap exceeds maxGapMin. Requires a real HR span to compare against — with
     * no/degenerate HR the dense path is kept (false), so a 4.0 with absent HR is never reclassified
     * as sparse.
     */
    internal fun isGravitySparse(grav: List<GravitySample>, hr: List<HrSample>): Boolean {
        if (grav.size < 2 || hr.size < 2) return false
        val hrSpan = (hr[hr.size - 1].ts - hr[0].ts).toDouble()
        if (hrSpan <= 0) return false
        val gravSpan = (grav[grav.size - 1].ts - grav[0].ts).toDouble()
        if (gravSpan < sparseGravitySpanFrac * hrSpan) return true
        return medianGapS(grav.map { it.ts }) > (maxGapMin * 60).toDouble()
    }

    /**
     * True when HR stays in the sleep band (≤ baseline × hrSleepBandMult) across (a, b], used to
     * decide whether a pure gravity gap is a real wake or just a dropout. With no baseline or no HR
     * in the interval, the answer is false (cannot vouch for the gap → treat as a real break).
     */
    internal fun hrSleepBandAcross(a: Long, b: Long, hr: List<HrSample>, baseline: Double?): Boolean {
        if (baseline == null) return false
        val seg = hr.filter { it.ts > a && it.ts <= b }
        if (seg.isEmpty()) return false
        val meanHR = seg.sumOf { it.bpm }.toDouble() / seg.size.toDouble()
        return meanHR <= baseline * hrSleepBandMult
    }

    /** Per-record sleep flags from a rolling fraction of "still" samples. */
    internal fun classifyStill(grav: List<GravitySample>, deltas: List<Double>): List<Boolean> {
        val n = grav.size
        if (n < 2) return List(n) { false }
        val half = windowSize(grav.map { it.ts }) / 2
        // stillPrefix[i] = number of still samples among deltas[0 until i]. Turns each per-sample
        // window count from O(window) into O(1), so the whole scan is O(n) not O(n×window). The old
        // nested loop ran ~n×window times per night and — ×21 nights, on the MAIN THREAD — froze the
        // app into ANRs after a few nights of 1 Hz history. Output is byte-identical. (#125)
        val stillPrefix = IntArray(n + 1)
        for (i in 0 until n) {
            stillPrefix[i + 1] = stillPrefix[i] + if (deltas[i] < gravityStillThresholdG) 1 else 0
        }
        val flags = ArrayList<Boolean>(n)
        for (i in 0 until n) {
            val lo = maxOf(0, i - half)
            val hi = minOf(n, i + half + 1)
            val stillCount = stillPrefix[hi] - stillPrefix[lo]
            flags.add(stillCount.toDouble() / (hi - lo).toDouble() >= stillFraction)
        }
        return flags
    }

    /** A contiguous sleep/active run. `stage` ∈ {"sleep", "active"}. */
    internal data class Period(val stage: String, val start: Long, val end: Long)

    /**
     * Collapse per-record flags into contiguous runs, breaking on class change
     * or a gap > maxGapMin minutes.
     *
     * When [sparse] (gravity is too clumped to bridge gaps — #308), a PURE gravity data-gap (no
     * contrary motion) does NOT close a SLEEP run while HR stays in the sleep band across the gap:
     * the strap simply banked no motion there, not a wake. A class change always still closes the
     * run, and the dense path ([sparse] == false) is byte-identical to the original. Mirrors Swift.
     */
    internal fun buildRuns(
        grav: List<GravitySample>, flags: List<Boolean>,
        sparse: Boolean = false, hr: List<HrSample> = emptyList(), baseline: Double? = null,
    ): List<Period> {
        val n = grav.size
        if (n == 0) return emptyList()
        val times = grav.map { it.ts }
        val maxGapS = (maxGapMin * 60).toLong()
        val periods = ArrayList<Period>()
        var runStart = 0
        for (i in 1..n) {
            val atEnd = (i == n)
            val close: Boolean
            if (atEnd) {
                close = true
            } else {
                val classChanged = flags[i] != flags[runStart]
                var gapExceeded = (times[i] - times[i - 1]) > maxGapS
                // Sparse override: a pure gravity gap (no class change) does not break a sleep run
                // when HR stays in the sleep band across it — the gap is a dropout, not a wake.
                if (sparse && gapExceeded && !classChanged && flags[runStart] &&
                    hrSleepBandAcross(times[i - 1], times[i], hr, baseline)
                ) {
                    gapExceeded = false
                }
                close = classChanged || gapExceeded
            }
            if (close) {
                periods.add(
                    Period(
                        stage = if (flags[runStart]) "sleep" else "active",
                        start = times[runStart],
                        end = times[i - 1],
                    )
                )
                runStart = i
            }
        }
        return periods
    }

    /** Absorb runs shorter than mergeMin minutes into their neighbours. */
    internal fun mergePeriods(periods: List<Period>, mergeMinutes: Int = mergeMin): List<Period> {
        if (periods.isEmpty()) return emptyList()
        val pending = periods.toMutableList()
        val thresholdS = (mergeMinutes * 60).toLong()
        val merged = ArrayList<Period>()
        var i = 0
        while (i < pending.size) {
            val current = pending[i]
            val tooShort = (current.end - current.start) < thresholdS
            if (!tooShort) {
                merged.add(current)
                i += 1
                continue
            }

            val hasPrev = i > 0 && merged.isNotEmpty()
            val hasNext = i + 1 < pending.size
            val bridgesSame = hasPrev && hasNext && pending[i - 1].stage == pending[i + 1].stage

            if (bridgesSame) {
                val prev = merged.removeAt(merged.size - 1)
                merged.add(Period(stage = prev.stage, start = prev.start, end = pending[i + 1].end))
                i += 2
            } else if (hasNext) {
                pending[i + 1] = Period(
                    stage = pending[i + 1].stage,
                    start = current.start,
                    end = pending[i + 1].end,
                )
                i += 1
            } else if (hasPrev) {
                val prev = merged.removeAt(merged.size - 1)
                merged.add(Period(stage = prev.stage, start = prev.start, end = current.end))
                i += 1
            } else {
                i += 1
            }
        }
        return merged
    }

    /**
     * Sparse-gravity bridge (#308): merge two adjacent SLEEP runs separated ONLY by a gap up to
     * sparseBridgeGapMin minutes when the intervening HR stays in the sleep band — so a real night
     * fragmented by gravity dropouts is re-stitched into one continuous in-bed span BEFORE the
     * minSleepMin gate drops the pieces. Active runs and over-threshold gaps are left untouched; the
     * span between two bridged sleep runs (an "active"/gap run, if present) is absorbed. A no-op when
     * [sparse] == false, so the dense 4.0 path is unchanged. Mirrors Swift.
     */
    internal fun bridgeSparseSleep(
        periods: List<Period>, sparse: Boolean, hr: List<HrSample>, baseline: Double?,
    ): List<Period> {
        if (!sparse || periods.isEmpty()) return periods
        val bridgeGapS = (sparseBridgeGapMin * 60).toLong()
        val out = ArrayList<Period>()
        for (p in periods) {
            val last = out.lastOrNull()
            if (last != null && last.stage == "sleep" && p.stage == "sleep") {
                val gap = p.start - last.end
                if (gap in 0..bridgeGapS && hrSleepBandAcross(last.end, p.start, hr, baseline)) {
                    out[out.size - 1] = Period(stage = "sleep", start = last.start, end = p.end)
                    continue
                }
            }
            out.add(p)
        }
        return out
    }

    // ── HR refinement ────────────────────────────────────────────────────────

    private inline fun <T> rowsBetween(rows: List<T>, start: Long, end: Long, ts: (T) -> Long): List<T> =
        rows.filter { ts(it) in start..end }

    /** Day HR baseline = median bpm over all HR samples; null if none. */
    internal fun hrBaseline(hr: List<HrSample>): Double? {
        val vals = hr.map { it.bpm.toDouble() }
        if (vals.isEmpty()) return null
        return HrvAnalyzer.median(vals)
    }

    internal fun confirmSleepWithHR(p: Period, hr: List<HrSample>, baseline: Double?): Boolean {
        if (baseline == null) return true
        val seg = rowsBetween(hr, p.start, p.end) { it.ts }
        if (seg.size < hrRefineMinSamples) return true
        val meanHR = seg.sumOf { it.bpm }.toDouble() / seg.size.toDouble()
        return meanHR <= baseline * hrSleepBaselineMult
    }

    /**
     * True when the run's CENTER, shifted to LOCAL time by [tzOffsetSeconds], lands in the
     * daytime band [daytimeBandStartHour, daytimeBandEndHour). The center (not the edges) is
     * used so a window straddling a band edge is classified once, by where it mostly is.
     * Math.floorMod keeps the local-shifted time in [0, secondsPerDay) for any sign.
     */
    internal fun isDaytimeCenter(p: Period, tzOffsetSeconds: Long): Boolean {
        val center = p.start + (p.end - p.start) / 2
        val secOfDay = Math.floorMod(center + tzOffsetSeconds, secondsPerDay)
        val hour = (secOfDay / 3_600L).toInt()
        return hour >= daytimeBandStartHour && hour < daytimeBandEndHour
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

    /**
     * Stricter bar for a daytime-centered window (#90). A real daytime nap clears it; a long
     * sedentary still stretch (the false-positive this guards) does not, because it is either
     * too short or never shows a genuine cardiac dip below the day median. Overnight windows
     * never reach here. Returns true = keep, false = reject.
     *
     * [restingHR] is the window's own lowest 5-min rolling-mean HR (the sleep-depth proxy
     * detectSleep already computes); [baseline] is the day's median HR. With no usable HR
     * evidence (null baseline OR null restingHR) a daytime stretch cannot be confirmed as a
     * real nap, so it is rejected — sedentary daytime stillness without a measured HR dip is
     * far more likely than an unmonitored nap, and this path can never touch the night.
     */
    internal fun passesDaytimeGuard(p: Period, restingHR: Int?, baseline: Double?): Boolean {
        val daytimeMinSleepS = (daytimeMinSleepMin * 60).toLong()
        if ((p.end - p.start) < daytimeMinSleepS) return false
        if (baseline == null || restingHR == null) return false
        return restingHR.toDouble() <= baseline * daytimeRestingHRMult
    }

    /**
     * Off-wrist HR-gap spans (#500). The contiguous HR-coverage gaps of at least [offWristHRGapMin]
     * minutes WITHIN [p.start, p.end], as concrete [start, end) sub-intervals — a strong wrist-OFF
     * proxy. Worn, the strap streams ~1 Hz HR (or PPG-derived HR on a 5/MG), so a real night yields no
     * long gap; an off-wrist stretch flatlines to no HR samples and yields a span. The leading edge
     * ([p.start] → first in-run sample) and trailing edge (last in-run sample → [p.end]) count too,
     * and a run with NO in-run HR at all is one full-period gap. With NO HR data at all this returns
     * [] (the gravity-only path is left to the existing guards — we can't assert off-wrist without HR).
     * These spans are UNIONed with the WRIST_OFF intervals by [offWristFraction]. Mirrors Swift.
     */
    internal fun offWristHRGapSpans(p: Period, hr: List<HrSample>): List<Pair<Long, Long>> {
        if (hr.isEmpty() || p.end <= p.start) return emptyList()
        val gapS = (offWristHRGapMin * 60).toLong()
        val seg = hr.filter { it.ts in p.start..p.end }.sortedBy { it.ts }
        // No HR anywhere inside a run long enough to matter → the whole period is one gap.
        if (seg.isEmpty()) return if ((p.end - p.start) >= gapS) listOf(p.start to p.end) else emptyList()
        val spans = ArrayList<Pair<Long, Long>>()
        // Leading edge: run start to first sample.
        if (seg[0].ts - p.start >= gapS) spans.add(p.start to seg[0].ts)
        // Interior: any gap between consecutive in-run samples.
        for (i in 1 until seg.size) if (seg[i].ts - seg[i - 1].ts >= gapS) spans.add(seg[i - 1].ts to seg[i].ts)
        // Trailing edge: last sample to run end.
        if (p.end - seg[seg.size - 1].ts >= gapS) spans.add(seg[seg.size - 1].ts to p.end)
        return spans
    }

    /**
     * Fractional off-wrist coverage of a candidate run [p.start, p.end] in [0, 1] (#500).
     * Design credited to j0b-dev's #504 analysis: instead of a binary drop on ANY HR gap or ANY single
     * WRIST_OFF blip, we measure how much of the run is off-wrist and let the caller drop it only past
     * [maxOffWristSleepFraction]. Coverage = (length of the UNION of) the HR-gap spans ([offWristHRGapSpans])
     * AND the supplied WRIST_OFF→WRIST_ON [wristOff] intervals, clipped to the run, divided by duration.
     * Unioning avoids double-counting overlapping gap+event time. A real night with a small (<50%)
     * off-wrist tail scores low and is kept; an all-day desk strap (HR-gap ≈100%, no events needed) or a
     * session genuinely spent off the wrist scores high and is dropped. Mirrors Swift.
     */
    internal fun offWristFraction(p: Period, hr: List<HrSample>, wristOff: List<Pair<Long, Long>>): Double {
        val dur = p.end - p.start
        if (dur <= 0) return 0.0
        // Collect every off-wrist span, clipped to the run: HR-gap proxy spans + explicit wrist-off events.
        val spans = ArrayList(offWristHRGapSpans(p, hr))
        for (w in wristOff) {
            val s = maxOf(w.first, p.start); val e = minOf(w.second, p.end)
            if (e > s) spans.add(s to e)
        }
        if (spans.isEmpty()) return 0.0
        // Union the spans so overlapping gap+event time is counted once, then sum the covered length.
        spans.sortBy { it.first }
        var covered = 0L; var curStart = spans[0].first; var curEnd = spans[0].second
        for (i in 1 until spans.size) {
            val sp = spans[i]
            if (sp.first <= curEnd) {
                curEnd = maxOf(curEnd, sp.second)        // overlapping/adjacent → extend
            } else {
                covered += curEnd - curStart             // disjoint → bank the run
                curStart = sp.first; curEnd = sp.second
            }
        }
        covered += curEnd - curStart
        return covered.toDouble() / dur.toDouble()
    }

    // ── detectSleep (public) ──────────────────────────────────────────────────

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
     */
    fun detectSleep(
        hr: List<HrSample> = emptyList(),
        rr: List<RrInterval> = emptyList(),
        resp: List<RespSample> = emptyList(),
        gravity: List<GravitySample>,
        tzOffsetSeconds: Long = 0L,
        wristOff: List<Pair<Long, Long>> = emptyList(),
    ): List<DetectedSleep> {
        val grav = gravity.sortedBy { it.ts }
        if (grav.size < 2) return emptyList()

        val hrS = hr.sortedBy { it.ts }
        val rrS = rr.sortedBy { it.ts }
        val respS = resp.sortedBy { it.ts }

        val baseline = hrBaseline(hrS)
        // Sparse-gravity gate (#308): an un-unlocked WHOOP 5.0 backfills mostly v18/v26 records
        // where gravity is clumped (~25% coverage), so the gravity-only spine fragments the night.
        // ONLY when sparse do the three robustness branches engage; a dense 4.0 night is `false`
        // here and follows the exact original path (byte-identical).
        val sparse = isGravitySparse(grav, hrS)

        val deltas = gravityDeltas(grav)
        val flags = classifyStill(grav, deltas)
        var runs = buildRuns(grav, flags, sparse = sparse, hr = hrS, baseline = baseline)
        runs = mergePeriods(runs)
        // Re-stitch sleep runs fragmented by pure gravity dropouts (sparse only) before minSleepMin.
        runs = bridgeSparseSleep(runs, sparse = sparse, hr = hrS, baseline = baseline)

        val minSleepS = (minSleepMin * 60).toLong()

        val sessions = ArrayList<DetectedSleep>()
        // Continuous-sleep chain tracking so a real overnight sleep that runs PAST the daytime-band
        // start (a late wake, or a brief morning stir then back to sleep that leaves the tail as its
        // own daytime-centered run) is NOT mistaken for an isolated daytime nap and rejected — which
        // truncated the displayed wake time to ~late morning. A daytime run skips the nap guard ONLY
        // when it directly continues (≤ nightContinuationGap) a chain that BEGAN overnight; isolated
        // daytime stillness (hours after waking) still faces the full guard.
        // Reimplemented from @vulnix0x4's PR #353.
        val continuationGapS = (nightContinuationGapMin * 60).toLong()
        var chainPrevEnd: Long? = null       // end of the last accepted sleep run
        var chainFromOvernight = false       // did the current contiguous chain begin overnight?
        for (p in runs) {
            if (p.stage != "sleep") continue
            if ((p.end - p.start) <= minSleepS) continue
            if (!confirmSleepWithHR(p, hrS, baseline)) continue
            // Off-wrist backstop (#500), FRACTIONAL rule (design credited to j0b-dev's #504 analysis):
            // a wrist-OFF stretch is still gravity with no HR, so it slips past both the gravity spine
            // and the daytime guard's "missing data" path. Measure off-wrist COVERAGE — the union of the
            // run's long HR-coverage gaps (the must-have proxy) and any WRIST_OFF→WRIST_ON intervals
            // overlapping it — and drop the run only when that reaches maxOffWristSleepFraction of its
            // duration. This no longer nukes a real night that over-extends into a SHORT (<50%) off-wrist
            // morning tail, or that holds a single stray WRIST_OFF blip, while an all-day desk strap
            // (≈100% gap) is still dropped. Checked BEFORE the night-tail exemption: off-wrist time is
            // off-wrist day or night and must NOT ride a continuation chain. It does NOT re-anchor the
            // chain (the run is simply skipped).
            if (offWristFraction(p, hrS, wristOff) >= maxOffWristSleepFraction) continue
            // Daytime false-sleep guard (#90): a window centered in the local daytime band
            // must clear a stricter bar (≥daytimeMinSleepMin AND a real resting-HR dip).
            // Overnight windows skip this entirely. restingHR is computed here (reused below).
            val resting = sessionRestingHR(start = p.start, end = p.end, hr = hrS)
            val continuesChain = chainPrevEnd?.let { p.start - it <= continuationGapS } ?: false
            val isNightTail = continuesChain && chainFromOvernight   // the night's tail, not a nap
            if (isDaytimeCenter(p, tzOffsetSeconds) && !passesDaytimeGuard(p, resting, baseline) && !isNightTail) continue
            val stages = stageSession(start = p.start, end = p.end, grav = grav,
                hr = hrS, rr = rrS, resp = respS)
            val eff = efficiency(start = p.start, end = p.end, stages = stages)
            val avgHrv = sessionAvgHRV(start = p.start, end = p.end, rr = rrS)
            sessions.add(
                DetectedSleep(
                    start = p.start, end = p.end, efficiency = eff,
                    stages = stages, restingHR = resting, avgHRV = avgHrv,
                )
            )
            // A run that does NOT continue the chain re-anchors it on this run's onset.
            if (!continuesChain) chainFromOvernight = isOvernightOnset(p.start, tzOffsetSeconds)
            chainPrevEnd = p.end
        }
        sessions.sortBy { it.start }
        return sessions
    }

    /** asleep / in-bed in [0, 1]; asleep = in-bed − wake. */
    internal fun efficiency(start: Long, end: Long, stages: List<StageSegment>): Double {
        val inBed = (end - start).toDouble()
        if (inBed <= 0) return 0.0
        val wake = stages.filter { it.stage == "wake" }.sumOf { (it.end - it.start).toDouble() }
        val asleep = maxOf(0.0, inBed - wake)
        return minOf(1.0, asleep / inBed)
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

    /** Build a 30 s hypnogram for [start, end] and return StageSegments. */
    internal fun stageSession(
        start: Long, end: Long, grav: List<GravitySample>,
        hr: List<HrSample>, rr: List<RrInterval>, resp: List<RespSample>,
    ): List<StageSegment> {
        val gSeg = rowsBetween(grav, start, end) { it.ts }
        if (gSeg.size < 2) return listOf(StageSegment(start = start, end = end, stage = "light"))

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
        if (grid.nEpochs == 0) return listOf(StageSegment(start = start, end = end, stage = "light"))

        val rescaled = rescaleCounts(grid.counts)
        val ckFlags = coleKripke(rescaled)
        val (onsetIdx, finalWakeIdx) = onsetAndFinalWake(ckFlags)

        val dogHR = dogHRVariability(grid.hr)
        val feats = extractFeatures(grid = grid, ckFlags = ckFlags, dogHR = dogHR,
            onsetIdx = onsetIdx, finalWakeIdx = finalWakeIdx)

        var labels = classifyEpochs(feats)
        labels = smoothLabels(labels)
        labels = reimposePhysiology(labels, features = feats,
            onsetIdx = onsetIdx, finalWakeIdx = finalWakeIdx)
        // Conservative fragment merge (#274): absorb sub-3-min stage flecks (the WHOOP 5/MG
        // sparse-motion artefact) so the hypnogram stops reading choppier than WHOOP's,
        // without erasing genuine multi-minute transitions. Display/scoring only — the
        // per-epoch detection above is unchanged.
        labels = mergeFragments(labels)

        // Pre-onset and post-final-wake epochs are not sleep → force wake.
        val mutLabels = labels.toMutableList()
        for (i in mutLabels.indices) {
            if (i < onsetIdx || i > finalWakeIdx) mutLabels[i] = "wake"
        }

        // Merge consecutive same-stage epochs into segments tiling [start, end].
        val segments = ArrayList<StageSegment>()
        for ((i, stage) in mutLabels.withIndex()) {
            val segStart = grid.edges[i].roundToLong()
            val segEnd = grid.edges[i + 1].roundToLong()
            val last = segments.lastOrNull()
            if (last != null && last.stage == stage) {
                segments[segments.size - 1].end = segEnd
            } else {
                segments.add(StageSegment(start = segStart, end = segEnd, stage = stage))
            }
        }
        if (segments.isNotEmpty()) segments[segments.size - 1].end = end
        return segments
    }

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

    /** RSA tachogram resample rate (Hz). 4 Hz is the standard HRV resample grid. */
    private const val rsaResampleHz: Double = 4.0

    /** Moving-mean detrend window for the RSA tachogram (seconds). */
    private const val rsaDetrendWindowS: Double = 8.0

    /** Minimum spacing between breath peaks on the tachogram (seconds) → ≤24 bpm. */
    private const val rsaMinPeakDistanceS: Double = 2.5

    /** Per-window length for the per-window rate estimate (seconds). */
    private const val rsaWindowS: Double = 300.0

    /** Physiologic breath-interval band (seconds): 0.1–0.4 Hz = 6–24 breaths/min. */
    private const val rsaMinBreathIntervalS: Double = 2.5  // 24 bpm
    private const val rsaMaxBreathIntervalS: Double = 10.0 // 6 bpm

    /**
     * THE canonical plausible sleeping-respiratory-rate band (bpm). The RSA peak-pick above can yield
     * 6–8 bpm at its noise floor, but every consumer (ReadinessEngine illness/readiness) only acts on
     * 8–25 — so a sub-8 estimate used to be persisted-then-silently-ignored. respRateFromRR now clamps
     * its output to this band (NaN outside it), and ReadinessEngine references this same range, so the
     * stored value can never disagree with what's acted on. (#78) */
    val respPlausibleRangeBpm: ClosedFloatingPointRange<Double> = 8.0..25.0

    /**
     * APPROXIMATE respiratory rate (breaths/min) from the R-R interval stream via
     * respiratory sinus arrhythmia (RSA), for use when no raw resp ADC channel is
     * available (WHOOP5 v18 wire is RR-only; resp ADC is WHOOP4 / cloud-only).
     *
     * This is an ON-DEVICE ESTIMATE, NOT a cloud/clinical respiration measurement.
     * It recovers the breathing-modulation of beat-to-beat timing, which tracks but
     * does not equal a chest-band / capnography rate.
     *
     * Pipeline (per matched in-bed session [start, end], unix SECONDS):
     *   1. Restrict RR rows to ts in [start, end]; range-filter the RR values
     *      (HrvAnalyzer.rangeFilter) to drop dropouts/ectopics.
     *   2. Reconstruct beat times by cumulatively summing the kept RR intervals
     *      from the first in-bed beat, yielding an (irregular) tachogram
     *      t_k = Σ rr, value_k = rr_k (ms).
     *   3. Resample the tachogram onto a uniform ~4 Hz grid by linear interpolation.
     *   4. Detrend: subtract a centered moving mean (rsaDetrendWindowS).
     *   5. Per ~5-min window: findPeaks (min distance rsaMinPeakDistanceS) on the
     *      detrended grid, keep peak-to-peak intervals in the 6–24 bpm band, rate =
     *      60 / median(intervals). Take the median across windows.
     * Returns NaN when too few intervals survive (honest no-data).
     */
    internal fun respRateFromRR(rr: List<RrInterval>, start: Long, end: Long): Double {
        val nan = Double.NaN
        if (end <= start) return nan

        // 1. In-bed RR rows in chronological order, range-filtered.
        val inBed = rr.asSequence()
            .filter { it.ts in start..end }
            .sortedBy { it.ts }
            .map { it.rrMs.toDouble() }
            .toList()
        val filtered = HrvAnalyzer.rangeFilter(inBed)
        if (filtered.size < 30) return nan // need enough beats for any RSA estimate

        // 2. Reconstruct beat times (seconds from session start) by cumulative sum.
        val beatTimes = DoubleArray(filtered.size)
        var acc = 0.0
        for (i in filtered.indices) {
            acc += filtered[i] / 1000.0
            beatTimes[i] = acc
        }
        val totalSpanS = beatTimes[beatTimes.size - 1]
        if (totalSpanS < rsaWindowS / 2.0) return nan // < ~2.5 min of beats

        // 3. Resample onto a uniform grid by linear interpolation.
        val dt = 1.0 / rsaResampleHz
        val nGrid = (totalSpanS / dt).toInt() + 1
        if (nGrid < 8) return nan
        val grid = DoubleArray(nGrid)
        var seg = 0
        for (g in 0 until nGrid) {
            val t = g * dt
            // advance segment so beatTimes[seg] <= t <= beatTimes[seg+1]
            while (seg < beatTimes.size - 2 && beatTimes[seg + 1] < t) seg += 1
            val t0 = beatTimes[seg]
            val t1 = beatTimes[seg + 1]
            val v0 = filtered[seg]
            val v1 = filtered[seg + 1]
            grid[g] = if (t1 <= t0) v0 else {
                val frac = ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
                v0 + frac * (v1 - v0)
            }
        }

        // 4. Detrend: subtract a centered moving mean (removes slow LF/baseline drift).
        val halfW = maxOf(1, (rsaDetrendWindowS * rsaResampleHz / 2.0).roundToInt())
        val detrended = DoubleArray(nGrid)
        for (i in 0 until nGrid) {
            val lo = maxOf(0, i - halfW)
            val hi = minOf(nGrid - 1, i + halfW)
            var sum = 0.0
            for (j in lo..hi) sum += grid[j]
            val mean = sum / (hi - lo + 1).toDouble()
            detrended[i] = grid[i] - mean
        }
        if (standardDeviation(detrended.toList()) <= 1e-9) return nan // flat → no RSA

        // 5. Per ~5-min window peak-pick → 60/median(breath interval); median across.
        val minDistSamples = maxOf(2, (rsaMinPeakDistanceS * rsaResampleHz).roundToInt())
        val windowSamples = maxOf(minDistSamples * 3, (rsaWindowS * rsaResampleHz).roundToInt())
        val perWindowRates = ArrayList<Double>()
        var w = 0
        while (w < nGrid) {
            val wEnd = minOf(nGrid, w + windowSamples)
            if (wEnd - w >= minDistSamples * 3) {
                val winSeg = ArrayList<Double>(wEnd - w)
                for (k in w until wEnd) winSeg.add(detrended[k])
                // findPeaks with height = 0.0 selects the positive RSA peaks (one per
                // breath) on the zero-mean detrended tachogram.
                val peaks = findPeaks(winSeg, distance = minDistSamples, height = 0.0)
                if (peaks.size >= 3) {
                    val intervals = ArrayList<Double>(peaks.size - 1)
                    for (i in 1 until peaks.size) {
                        val ivS = (peaks[i] - peaks[i - 1]).toDouble() * dt
                        if (ivS in rsaMinBreathIntervalS..rsaMaxBreathIntervalS) intervals.add(ivS)
                    }
                    if (intervals.size >= 2) {
                        val med = HrvAnalyzer.median(intervals)
                        if (med > 0.0) perWindowRates.add(60.0 / med)
                    }
                }
            }
            w += windowSamples
        }
        if (perWindowRates.isEmpty()) return nan
        // Reject estimates outside the canonical consumer band (NaN = "no usable estimate") so the
        // persisted value never silently disagrees with ReadinessEngine's plausibility gate. (#78)
        val median = HrvAnalyzer.median(perWindowRates)
        return if (median in respPlausibleRangeBpm) median else nan
    }

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

        return features.map {
            classifyOne(it, hrLo = hrLo, hrHi = hrHi, rmssdHi = rmssdHi,
                hrvarHi = hrvarHi, rrvHi = rrvHi, rrvLo = rrvLo)
        }
    }

    internal fun classifyOne(
        f: EpochFeatures, hrLo: Double?, hrHi: Double?,
        rmssdHi: Double?, hrvarHi: Double?, rrvHi: Double?, rrvLo: Double?,
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

        val rrvIrregular = f.rrv.isFinite() && rrvHi != null && f.rrv >= rrvHi
        // Missing respiration (NaN RRV) treated as "regular" (pro-deep bias).
        val rrvRegular = (!f.rrv.isFinite()) || (rrvLo != null && f.rrv <= rrvLo)

        val still = f.moveFrac <= stageStillMoveFrac
        val moving = f.moveFrac >= stageWakeMoveFrac

        // WAKE: sustained motion + activated cardiac (or no HR to vet motion).
        if (moving && (cardiacActivated || !hasHR)) return "wake"
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

    /**
     * Sleep-depth rank, lighter → deeper: wake 0, light 1, rem 2, deep 3. Used by
     * mergeFragments to bias an ambiguous merge toward the LIGHTER stage so smoothing
     * can never inflate deep/REM. Unknown labels rank lightest (0) — they never win deep.
     */
    internal fun stageDepthRank(stage: String): Int = when (stage) {
        "light" -> 1
        "rem" -> 2
        "deep" -> 3
        else -> 0 // "wake" and any unexpected label
    }

    /** A contiguous run of one stage spanning [len] epochs. */
    private data class StageRun(val stage: String, var len: Int)

    /**
     * Display/scoring smoothing of the staged label sequence (#274). Absorbs sub-threshold
     * "noise" runs WITHOUT erasing real transitions — applied AFTER staging, it never
     * touches the underlying per-epoch detection.
     *
     * Per run shorter than [thresholdEpochs]:
     *   • bridged by two SAME-stage neighbours → absorbed into them (the fleck was a blip
     *     inside one continuous stage);
     *   • between DIFFERENT stages → relabelled to the dominant (longer) neighbour. On a tie
     *     — or when the longer neighbour is the deeper one and the shorter is lighter and of
     *     comparable length — it biases toward the LIGHTER neighbour so a stray fleck can
     *     never inflate deep/REM (the least-reliable, most-overcountable classes).
     *
     * Single left-to-right pass over runs, mirroring mergePeriods' control flow so the
     * Swift and Kotlin ports stay byte-identical. A run already ≥ threshold is a real
     * transition and is always preserved.
     */
    internal fun mergeFragments(labels: List<String>, thresholdEpochs: Int = fragmentMergeEpochs): List<String> {
        val n = labels.size
        if (n == 0 || thresholdEpochs <= 1) return labels

        // Collapse the per-epoch labels into contiguous runs of (stage, length).
        val runs = ArrayList<StageRun>()
        for (s in labels) {
            val last = runs.lastOrNull()
            if (last != null && last.stage == s) last.len += 1
            else runs.add(StageRun(s, 1))
        }
        if (runs.size < 2) return labels

        val merged = ArrayList<StageRun>()
        var i = 0
        while (i < runs.size) {
            val current = runs[i]
            if (current.len >= thresholdEpochs) {
                merged.add(StageRun(current.stage, current.len))
                i += 1
                continue
            }

            val hasPrev = merged.isNotEmpty()
            val hasNext = i + 1 < runs.size

            if (hasPrev && hasNext && merged[merged.size - 1].stage == runs[i + 1].stage) {
                // Same-stage bridge: absorb the fleck and the next run into the previous one.
                merged[merged.size - 1].len += current.len + runs[i + 1].len
                i += 2
            } else if (hasPrev && hasNext) {
                // Between two DIFFERENT stages: relabel to the dominant neighbour, biasing
                // toward the lighter stage when the two neighbours are tied in length.
                val prev = merged[merged.size - 1]
                val next = runs[i + 1]
                val winner: String = when {
                    prev.len > next.len -> prev.stage
                    next.len > prev.len -> next.stage
                    // Tie → lighter (smaller depth rank) wins; never inflate deep/REM.
                    else -> if (stageDepthRank(prev.stage) <= stageDepthRank(next.stage)) prev.stage else next.stage
                }
                if (winner == prev.stage) {
                    merged[merged.size - 1].len += current.len
                    i += 1
                } else {
                    // Becomes part of the NEXT run: extend next, drop current.
                    runs[i + 1] = StageRun(next.stage, next.len + current.len)
                    i += 1
                }
            } else if (hasNext) {
                // No previous run (leading fleck): fold forward into the next run.
                runs[i + 1] = StageRun(runs[i + 1].stage, runs[i + 1].len + current.len)
                i += 1
            } else if (hasPrev) {
                // No next run (trailing fleck): fold back into the previous run.
                merged[merged.size - 1].len += current.len
                i += 1
            } else {
                // Single sub-threshold run with no neighbours — nothing to merge into.
                merged.add(StageRun(current.stage, current.len))
                i += 1
            }
        }

        // Re-expand the runs back into a per-epoch label sequence of the same length.
        val out = ArrayList<String>(n)
        for (r in merged) {
            for (k in 0 until r.len) out.add(r.stage)
        }
        return out
    }

    // ── Per-session HR / HRV ─────────────────────────────────────────────────

    /** Lowest 5-min rolling-mean HR during the session (bpm), or null. */
    internal fun sessionRestingHR(start: Long, end: Long, hr: List<HrSample>): Int? {
        val seg = hr.filter { it.ts in start..end }
        if (seg.isEmpty()) return null
        val windowS = 5 * 60L
        val means = ArrayList<Double>()
        var t = start
        while (t < end) {
            val win = seg.filter { it.ts >= t && it.ts < t + windowS }
            if (win.isNotEmpty()) means.add(win.sumOf { it.bpm }.toDouble() / win.size.toDouble())
            t += windowS
        }
        val m = means.minOrNull()
        if (m != null) return m.roundToInt()
        val all = seg.sumOf { it.bpm }.toDouble() / seg.size.toDouble()
        return all.roundToInt()
    }

    /**
     * Mean RMSSD over 5-min tumbling windows across the session (ms), or null.
     * Uses the same range-filter + ≥2-valid-interval rule as hrv.rmssd().
     */
    internal fun sessionAvgHRV(start: Long, end: Long, rr: List<RrInterval>): Double? {
        val seg = rr.filter { it.ts in start..end }
        if (seg.isEmpty()) return null
        val windowS = 5 * 60L
        val vals = ArrayList<Double>()
        var t = start
        while (t < end) {
            val bucket = seg.filter { it.ts >= t && it.ts < t + windowS }.map { it.rrMs.toDouble() }
            // Full clean (range + Malik ectopic rejection), not just range — matches the
            // analyze() pipeline. The 0x2A37 RR on a WHOOP 5/MG is PPG-derived and noisier
            // than a 4.0's; rMSSD is built from SUCCESSIVE differences, so an un-rejected
            // jitter spike inflates the session HRV. Ectopic rejection drops those (#262/#235).
            val cleaned = HrvAnalyzer.cleanRR(bucket)
            if (cleaned.size >= 2) {
                val r = HrvAnalyzer.rmssdRaw(cleaned)
                if (r != null) vals.add(r)
            }
            t += windowS
        }
        if (vals.isEmpty()) return null
        return vals.sum() / vals.size.toDouble()
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

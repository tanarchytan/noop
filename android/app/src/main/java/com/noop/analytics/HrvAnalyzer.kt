package com.noop.analytics

import com.noop.data.RrInterval
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * RMSSD + SDNN from RR intervals with cleaning.
 *
 *   RMSSD = sqrt( mean( (NN[i+1] − NN[i])^2 ) )      (Task Force 1996)
 *   SDNN  = sample standard deviation of NN, ddof=1  (Task Force 1996)
 *
 * Cleaning: range filter [300, 2000] ms, then Malik-style ectopic rejection (beats deviating
 * > ~20% from a local median are dropped), then require >= 20 clean intervals to trust the result.
 * Gap-aware cleaning remembers where beats were dropped, so a successive-difference metric (RMSSD,
 * pNN50) never spans a splice into a spurious delta. Distinct from Hrv in Analytics.kt, which
 * shares the RMSSD math but skips cleaning and the SDNN/meanNN/pNN50 outputs.
 */
object HrvAnalyzer {

    /** Minimum plausible RR interval (ms) — 300 ms ≈ 200 bpm. */
    const val RR_MIN_MS: Double = 300.0

    /** Maximum plausible RR interval (ms) — 2000 ms ≈ 30 bpm. */
    const val RR_MAX_MS: Double = 2000.0

    /** Minimum valid intervals required for a trustworthy RMSSD/SDNN. */
    const val MIN_BEATS: Int = 20

    /**
     * Malik-style ectopic threshold: a beat deviating more than this fraction
     * from the local median is rejected. 0.20 == 20%.
     */
    const val ECTOPIC_THRESHOLD: Double = 0.20

    /**
     * Half-width (in beats) of the local-median window used for ectopic rejection.
     * A window of 2*radius+1 beats (5 beats at radius 2) matches the common Malik
     * moving-window implementations.
     */
    const val ECTOPIC_WINDOW_RADIUS: Int = 2

    /**
     * Ceiling on the fraction of input beats the cleaning pipeline may reject before a SPOT reading
     * is refused as too noisy. Spot-only: on-demand callers pass this, the nightly windowed path
     * never does. 0.35 refuses once more than 35% of beats were dropped as out-of-range/ectopic,
     * even if [MIN_BEATS] clean intervals survive — an honesty gate on a short live capture.
     */
    const val DEFAULT_SPOT_MAX_REJECTED_FRACTION: Double = 0.35

    /** Result of an HRV computation over a window. */
    data class HrvResult(
        /** RMSSD in milliseconds, or null when too few valid beats. */
        val rmssd: Double?,
        /** SDNN (sample SD, ddof=1) in milliseconds, or null when too few valid beats. */
        val sdnn: Double?,
        /** Mean NN interval (ms) over the cleaned beats, or null. */
        val meanNN: Double?,
        /** pNN50: % of successive |ΔNN| > 50 ms, or null. */
        val pnn50: Double?,
        /** Count of RR intervals supplied to the analysis (before cleaning). */
        val nInput: Int,
        /** Count of clean NN intervals after range + ectopic filtering. */
        val nClean: Int,
    ) {
        companion object {
            /** An empty/insufficient-data result that preserves the input count. */
            fun empty(nInput: Int): HrvResult =
                HrvResult(rmssd = null, sdnn = null, meanNN = null, pnn50 = null,
                    nInput = nInput, nClean = 0)
        }
    }

    // ── Primitive Task Force statistics (no filtering) ───────────────────────

    /**
     * Task Force (1996) RMSSD over already-clean NN intervals (ms). Returns null
     * when fewer than 2 values (no successive differences). No filtering applied.
     */
    fun rmssdRaw(nn: List<Double>): Double? = RustScores.rmssdRaw(nn)

    fun sdnnRaw(nn: List<Double>): Double? = RustScores.sdnnRaw(nn)

    // ── Cleaning ─────────────────────────────────────────────────────────────

    /** Range filter: keep only intervals in [300, 2000] ms, preserving order. */
    fun rangeFilter(rr: List<Double>): List<Double> =
        RustScores.rangeFilterRR(rr.map { it.toInt() }).map { it.toDouble() }

    /**
     * Malik-style ectopic rejection: drop any beat that deviates from its local
     * median by more than [ECTOPIC_THRESHOLD] (20%). The local median is taken
     * over a centered window of `2*ECTOPIC_WINDOW_RADIUS+1` beats (excluding the
     * beat under test). Beats with too small a neighbourhood are kept.
     */
    fun rejectEctopic(nn: List<Double>): List<Double> {
        if (nn.size <= ECTOPIC_WINDOW_RADIUS) return nn
        val kept = ArrayList<Double>(nn.size)
        for (i in nn.indices) {
            val lo = maxOf(0, i - ECTOPIC_WINDOW_RADIUS)
            val hi = minOf(nn.size - 1, i + ECTOPIC_WINDOW_RADIUS)
            val neighbours = ArrayList<Double>(hi - lo)
            for (j in lo..hi) {
                if (j != i) neighbours.add(nn[j])
            }
            if (neighbours.size < 2) {
                kept.add(nn[i])
                continue
            }
            val med = median(neighbours)
            if (med <= 0) {
                kept.add(nn[i])
                continue
            }
            val deviation = abs(nn[i] - med) / med
            if (deviation <= ECTOPIC_THRESHOLD) {
                kept.add(nn[i])
            }
            // else: drop this beat as ectopic.
        }
        return kept
    }

    /** Full clean: range filter → ectopic rejection. Returns the clean NN series. */
    fun cleanRR(rr: List<Double>): List<Double> = rejectEctopic(rangeFilter(rr))

    // ── Gap-aware cleaning (successive-difference safety) ─────────────────────

    /**
     * A cleaned NN series that remembers where beats were dropped. [nn] is byte-identical to [cleanRR]
     * over the same input. [contiguous] has the same length: `contiguous[i]` is true when `nn[i]` and
     * `nn[i - 1]` were adjacent beats in the ORIGINAL series (no beat removed between them by the range
     * or ectopic filter) and false when a beat was dropped in between (a splice). Index 0 is always
     * false: the first beat has no predecessor.
     */
    data class CleanSeries(val nn: List<Double>, val contiguous: List<Boolean>)

    /**
     * Clean the RR series (range filter then Malik ectopic rejection, exactly like [cleanRR]) while
     * tracking which original beats were dropped, so a downstream successive-difference metric can skip
     * the difference across a removed beat. [CleanSeries.nn] equals [cleanRR] value for value.
     */
    fun cleanRRGapAware(rr: List<Double>): CleanSeries {
        // Pass 1: range filter, keeping each survivor's index in the ORIGINAL series.
        val rangedIdx = ArrayList<Int>(rr.size)
        val rangedVal = ArrayList<Double>(rr.size)
        for (i in rr.indices) {
            val v = rr[i]
            if (v >= RR_MIN_MS && v <= RR_MAX_MS) { rangedIdx.add(i); rangedVal.add(v) }
        }
        // Pass 2: Malik ectopic rejection over the range-filtered values, mirroring [rejectEctopic]
        // (same windows, same median, same threshold) so the kept values match cleanRR exactly, while
        // carrying each survivor's original index forward.
        val keptOrig = ArrayList<Int>(rangedVal.size)
        val keptVal = ArrayList<Double>(rangedVal.size)
        if (rangedVal.size <= ECTOPIC_WINDOW_RADIUS) {
            // rejectEctopic returns the input unchanged for a series this short.
            for (k in rangedVal.indices) { keptOrig.add(rangedIdx[k]); keptVal.add(rangedVal[k]) }
        } else {
            for (i in rangedVal.indices) {
                val lo = maxOf(0, i - ECTOPIC_WINDOW_RADIUS)
                val hi = minOf(rangedVal.size - 1, i + ECTOPIC_WINDOW_RADIUS)
                val neighbours = ArrayList<Double>(hi - lo)
                for (j in lo..hi) if (j != i) neighbours.add(rangedVal[j])
                val keep = when {
                    neighbours.size < 2 -> true
                    else -> {
                        val med = median(neighbours)
                        if (med <= 0) true else abs(rangedVal[i] - med) / med <= ECTOPIC_THRESHOLD
                    }
                }
                if (keep) { keptOrig.add(rangedIdx[i]); keptVal.add(rangedVal[i]) }
            }
        }
        // A survivor is contiguous with its predecessor only when their ORIGINAL indices are adjacent.
        val contiguous = ArrayList<Boolean>(keptVal.size)
        for (i in keptVal.indices) contiguous.add(i > 0 && keptOrig[i] == keptOrig[i - 1] + 1)
        return CleanSeries(keptVal, contiguous)
    }

    /**
     * Task Force RMSSD that counts a successive difference only when the two beats were adjacent in the
     * source ([CleanSeries.contiguous]). A difference spanning a dropped beat is skipped, so removing an
     * out-of-range or ectopic beat cannot splice its neighbours into a spurious delta. Identical to
     * [rmssdRaw] when there are no gaps. Returns null when no valid successive difference exists.
     */
    fun rmssdGapAware(nn: List<Double>, contiguous: List<Boolean>): Double? {
        require(nn.size == contiguous.size) { "nn and contiguous must be the same length" }
        var sumSq = 0.0
        var count = 0
        for (i in 1 until nn.size) {
            if (!contiguous[i]) continue
            val d = nn[i] - nn[i - 1]
            sumSq += d * d
            count += 1
        }
        return if (count < 1) null else sqrt(sumSq / count.toDouble())
    }

    /**
     * pNN50 (% of successive |ΔNN| > 50 ms) counting only gap-free pairs, mirroring [rmssdGapAware].
     * Identical to the plain pNN50 when there are no gaps. Returns null when no valid pair exists.
     */
    fun pnn50GapAware(nn: List<Double>, contiguous: List<Boolean>): Double? {
        require(nn.size == contiguous.size) { "nn and contiguous must be the same length" }
        var nn50 = 0
        var pairs = 0
        for (i in 1 until nn.size) {
            if (!contiguous[i]) continue
            if (abs(nn[i] - nn[i - 1]) > 50.0) nn50 += 1
            pairs += 1
        }
        return if (pairs < 1) null else nn50.toDouble() / pairs.toDouble() * 100.0
    }

    // ── Windowed analysis ────────────────────────────────────────────────────

    /**
     * Compute HRV from raw RR-interval values (ms), applying the full cleaning
     * pipeline. Returns an empty result when fewer than [MIN_BEATS] survive.
     *
     * @param maxRejectedFraction SPOT-ONLY honesty gate. When non-null, also refuses (empty result) if
     *   the fraction of input beats dropped by cleaning exceeds this value, even when [MIN_BEATS]
     *   clean intervals survive — a short live capture that threw away most of its beats is too noisy
     *   to trust. null (the default; what the nightly windowed path passes) skips the gate entirely.
     */
    fun analyzeRaw(rawRR: List<Double>, maxRejectedFraction: Double? = null): HrvResult =
        RustScores.analyzeRaw(rawRR, maxRejectedFraction)

    /** Total heartbeat-time (sum of NN intervals, ms) ÷ wall-clock span of the R-R window (ms). A
     *  value > ~1.0 is physically impossible — you can't record more beat-time than elapsed time —
     *  so it flags DOUBLE-COUNTED / overlapping R-R (e.g. a live + historical merge storing the same
     *  beat under two timestamps, which inflates HRV and drags resting HR down). Returns 0.0 for
     *  < 2 beats or a zero span. */
    fun rrCoverage(tsSec: List<Long>, rrMs: List<Double>): Double {
        if (tsSec.size < 2 || rrMs.isEmpty()) return 0.0
        val hi = tsSec.maxOrNull() ?: return 0.0
        val lo = tsSec.minOrNull() ?: return 0.0
        val spanMs = (hi - lo) * 1000.0
        if (spanMs <= 0.0) return 0.0
        return rrMs.sum() / spanMs
    }

    /** How many R-R rows are EXACT duplicates of another — same (ts, rrMs). Extra copies of one beat;
     *  `total − distinct(ts, rrMs)`. Points at the double-insert mechanism when [rrCoverage] > 1. */
    fun duplicateBeatCount(tsSec: List<Long>, rrMs: List<Double>): Int {
        val n = minOf(tsSec.size, rrMs.size)
        val seen = HashSet<Pair<Long, Double>>()
        var dups = 0
        for (i in 0 until n) if (!seen.add(tsSec[i] to rrMs[i])) dups++
        return dups
    }

    // ── Rolling / windowed rMSSD ─────────────────────────────────────────────
    //
    // Slides a [windowSec] window across the cleaned RR series and emits the Task-Force rMSSD over
    // the beats in each trailing window. Applies the same Malik/range filter as the nightly path
    // ([cleanRR]) first, so an ectopic or out-of-range beat can't inflate the curve. A raw RR
    // interval is beat-to-beat heart period, not variability — this is what the Deep Timeline plots
    // as its windowed rMSSD trace. Pure: no clock, no IO.

    /** Default rolling-window width (seconds) for the Deep Timeline windowed rMSSD trace. ~5 min, the
     *  shortest span the Task Force calls a short-term recording, so the curve has enough beats to mean
     *  something without smoothing away the within-night swings. */
    const val DEFAULT_ROLLING_WINDOW_SEC: Int = 300

    /**
     * Rolling/windowed rMSSD over an RR series. For each input sample (ascending by ts), computes the
     * Task-Force rMSSD over the cleaned beats whose ts falls in the trailing window `(ts - windowSec, ts]`,
     * emitting `(ts, rmssd)` only when at least [minBeatsPerWindow] clean beats survive in that window (a
     * 2-beat window is one successive difference — a noisy spike, not HRV). Range + Malik ectopic
     * filtering ([cleanRR]) is applied to the WHOLE series once, so artifacts never enter any window.
     * Empty when fewer than [minBeatsPerWindow] input rows. Pure, deterministic.
     *
     * @param rr the RR intervals; each carries a ts in unix SECONDS and rrMs.
     * @param windowSec the trailing window width in seconds (defaults to [DEFAULT_ROLLING_WINDOW_SEC]).
     * @param stepSec emit at most one point per this many seconds of advance — a thinning stride so a 1 Hz
     *   RR stream does not emit a point per beat and flood the chart. 0 (the default) emits at every
     *   qualifying window.
     * @param minBeatsPerWindow minimum clean beats required inside a window before it emits (default 8)
     *   — a smaller window is a noisy spike, not a trustworthy rMSSD.
     */
    fun rollingRmssd(
        rr: List<RrInterval>,
        windowSec: Int = DEFAULT_ROLLING_WINDOW_SEC,
        stepSec: Int = 0,
        minBeatsPerWindow: Int = 8,
    ): List<Pair<Long, Double>> {
        if (rr.size < minBeatsPerWindow || windowSec <= 0) return emptyList()
        // Ascending by ts so the trailing-window scan is monotone (the table read is already ordered, but
        // we don't assume it). Stable on equal ts.
        val sorted = rr.sortedBy { it.ts }
        // Clean the WHOLE series first (range + Malik ectopic), keeping each surviving beat's ts so a
        // window can be cut by timestamp. cleanRR works on the raw ms values; we re-pair to ts by walking
        // the same filters here so the kept ts/ms stay aligned (cleanRR drops items, losing the index map).
        val ranged = sorted.filter { it.rrMs.toDouble() in RR_MIN_MS..RR_MAX_MS }
        if (ranged.size < 2) return emptyList()
        val cleanMs = rejectEctopic(ranged.map { it.rrMs.toDouble() })
        // rejectEctopic preserves order and only drops items, so re-pair by walking both in lockstep: a
        // kept ms value corresponds to the next not-yet-consumed ranged beat with that value.
        val kept = ArrayList<RrInterval>(cleanMs.size)
        var ri = 0
        for (ms in cleanMs) {
            while (ri < ranged.size && ranged[ri].rrMs.toDouble() != ms) ri++
            if (ri < ranged.size) { kept.add(ranged[ri]); ri++ }
        }
        if (kept.size < 2) return emptyList()
        val window = windowSec.toLong()
        val out = ArrayList<Pair<Long, Double>>(kept.size)
        var lo = 0
        var lastEmitTs: Long? = null
        for (hi in kept.indices) {
            val tEnd = kept[hi].ts
            val tStart = tEnd - window
            // Advance the trailing edge so only beats with ts in (tStart, tEnd] remain.
            while (lo < hi && kept[lo].ts <= tStart) lo++
            // Thinning stride: skip emitting until at least [stepSec] has passed since the last
            // EMITTED point (measured against emits, not candidates).
            val last = lastEmitTs
            if (stepSec > 0 && last != null && tEnd - last < stepSec) continue
            val span = kept.subList(lo, hi + 1).map { it.rrMs.toDouble() }
            // A window with too few clean beats is a noisy spike, not a trustworthy rMSSD — require
            // [minBeatsPerWindow] survivors.
            if (span.size < minBeatsPerWindow) continue
            val r = rmssdRaw(span) ?: continue
            out.add(tEnd to r)
            lastEmitTs = tEnd
        }
        return out
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Median of a non-empty array. (Caller guarantees non-empty.) Returns 0.0 for
     * an empty input.
     *
     * Shared with SleepStager / AnalyticsEngine.
     */
    fun median(values: List<Double>): Double {
        val s = values.sorted()
        val n = s.size
        if (n == 0) return 0.0
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }
}

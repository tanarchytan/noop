package com.noop.analytics

import com.noop.data.GravitySample
import com.noop.data.HrSample
import kotlin.math.sqrt

/*
 * AutoWorkoutDetector.kt — MVP retroactive "did you just work out?" detector. Separate from
 * [WorkoutDetector] (computes calories/zones/strain, writes the durable "detected" rows): this
 * one only ever SUGGESTS a workout via a dismissible Today card, never writes on its own. The
 * user taps "Save" to turn a suggestion into a manual workout, or X to dismiss it forever.
 *
 * Thresholds are intentionally CONSERVATIVE (low sensitivity): a sustained ≥12-min elevation of
 * HR ≥ resting+30 bpm, brief (≤90 s) dips tolerated, near windows merged — avoids false positives
 * from stress / caffeine / a flight of stairs at the cost of missing gentle sessions. An OPTIONAL
 * continuous motion signal, when supplied, is required as confirmation; else it runs HR-only.
 *
 * Pure / headless: no Android, no I/O, no clock. Inputs are Room entities HrSample (ts:Long
 * seconds, bpm:Int) and GravitySample (ts:Long seconds, x/y/z:Double). All ts/start/end are unix
 * SECONDS as Long. NOT medical advice.
 */
object AutoWorkoutDetector {

    // ---- Constants ----

    /** Elevated gate: bpm must be at least restingHR + this margin to count as "working". */
    const val elevatedMarginBPM: Int = 30

    /** A candidate must hold the elevated gate for a contiguous span of at least this long. */
    const val minSustainedMin: Double = 12.0

    /** A dip below the gate no longer than this does NOT break the span (a red light, a sip of water). */
    const val maxDipS: Long = 90L

    /** Two detected windows whose gap is strictly less than this are merged into one. */
    const val mergeGapS: Long = 5L * 60L // 5 min

    /**
     * When an OPTIONAL continuous motion series is supplied, a window must ALSO show elevated motion
     * to qualify (confirmation). "Elevated motion" = the window's mean per-second motion intensity
     * (L2 gravity-delta) is at least this. Mirrors the motion gate scale used by [WorkoutDetector].
     * Ignored entirely when no motion series is passed (HR-only mode).
     */
    const val motionConfirmMean: Double = 0.05

    /** Resting-HR fallback when the caller has no nightly RHR for the day. */
    const val defaultRestingHR: Int = 60

    /**
     * A detected workout window. All fields are derived purely from the HR samples inside the window.
     * `startSec`/`endSec` are unix seconds; `avgBpm`/`peakBpm` are rounded; `durationMin` is whole minutes.
     */
    data class DetectedWorkout(
        val startSec: Long,
        val endSec: Long,
        val avgBpm: Int,
        val peakBpm: Int,
        val durationMin: Int,
    )

    /** Sorted (ts, bpm) HR pairs, ascending by ts. */
    private fun cleanHR(hr: List<HrSample>): List<HrSample> = hr.sortedBy { it.ts }

    /**
     * Per-second motion intensity = L2 magnitude of the gravity change vs the previous record.
     * First row → 0. Returns a ts→intensity map for O(1) window lookups. Empty input → empty map.
     */
    internal fun motionIntensityByTs(gravity: List<GravitySample>): Map<Long, Double> {
        if (gravity.isEmpty()) return emptyMap()
        val rows = gravity.sortedBy { it.ts }
        val out = LinkedHashMap<Long, Double>(rows.size)
        var prev: GravitySample? = null
        for ((i, row) in rows.withIndex()) {
            val p = prev
            val intensity = if (i == 0 || p == null) {
                0.0
            } else {
                val dx = row.x - p.x
                val dy = row.y - p.y
                val dz = row.z - p.z
                sqrt(dx * dx + dy * dy + dz * dz)
            }
            out[row.ts] = intensity
            prev = row
        }
        return out
    }

    /**
     * Detects sustained-elevated-HR workout windows: samples >= restingHR + [elevatedMarginBPM] are
     * "elevated"; a span grows across them, tolerating dips up to [maxDipS] before closing, and is
     * kept only once it lasts >= [minSustainedMin]; kept spans within [mergeGapS] of each other merge.
     * A supplied motion series must average >= [motionConfirmMean] over the window to confirm (else
     * HR-only); a window overlapping a [savedWorkouts] span is dropped so a logged workout is never
     * re-suggested. Each surviving window becomes a [DetectedWorkout] (avg/peak bpm, whole-minute duration).
     *
     * @param hr the day's (or last day or two's) HR samples; any order; empty → [].
     * @param restingHR the nightly resting HR for the day; null → [defaultRestingHR] (60).
     * @param gravity OPTIONAL continuous motion series for confirmation; empty/omitted → HR-only.
     * @param savedWorkouts already-saved workout windows as (startSec, endSec) pairs to exclude by overlap.
     */
    fun detect(
        hr: List<HrSample>,
        restingHR: Int? = null,
        gravity: List<GravitySample> = emptyList(),
        savedWorkouts: List<Pair<Long, Long>> = emptyList(),
    ): List<DetectedWorkout> {
        val seg = cleanHR(hr)
        if (seg.isEmpty()) return emptyList()

        val floor = (restingHR ?: defaultRestingHR) + elevatedMarginBPM

        // --- 1+2+3: grow sustained spans tolerating brief dips ---
        // A span is [spanStart, spanEnd] over ELEVATED-sample timestamps. `dipStart` marks where the
        // current sub-threshold run began (0 = not in a dip); a dip longer than maxDipS closes the span.
        val spans = ArrayList<Pair<Long, Long>>()
        var spanStart: Long? = null
        var spanEnd = 0L
        var dipStart: Long? = null

        fun closeSpan() {
            val s = spanStart
            if (s != null && (spanEnd - s) >= minSustainedMin * 60.0) {
                spans.add(s to spanEnd)
            }
            spanStart = null
            dipStart = null
        }

        for (sample in seg) {
            val elevated = sample.bpm >= floor
            if (elevated) {
                if (spanStart == null) spanStart = sample.ts
                spanEnd = sample.ts
                dipStart = null // the dip (if any) is bridged
            } else if (spanStart != null) {
                // In a span: tolerate the dip until it runs longer than maxDipS.
                val d = dipStart ?: sample.ts.also { dipStart = it }
                if ((sample.ts - d) > maxDipS) closeSpan()
            }
        }
        closeSpan()

        if (spans.isEmpty()) return emptyList()

        // --- 4: merge spans whose gap is strictly < mergeGapS (spans are start-ascending by build) ---
        val merged = ArrayList<Pair<Long, Long>>()
        var curStart = spans[0].first
        var curEnd = spans[0].second
        for (k in 1 until spans.size) {
            val next = spans[k]
            if ((next.first - curEnd) < mergeGapS) {
                curEnd = maxOf(curEnd, next.second)
            } else {
                merged.add(curStart to curEnd)
                curStart = next.first
                curEnd = next.second
            }
        }
        merged.add(curStart to curEnd)

        // --- 5+6+7 ---
        val motion = if (gravity.isEmpty()) emptyMap() else motionIntensityByTs(gravity)
        val results = ArrayList<DetectedWorkout>()
        for ((start, end) in merged) {
            // 6: never re-suggest a window overlapping an already-saved workout.
            if (savedWorkouts.any { overlaps(start, end, it.first, it.second) }) continue

            val window = seg.filter { it.ts in start..end }
            if (window.isEmpty()) continue

            // 5: motion confirmation, only when a continuous motion series was supplied.
            if (motion.isNotEmpty()) {
                val inWin = motion.entries.filter { it.key in start..end }.map { it.value }
                val meanMotion = if (inWin.isEmpty()) 0.0 else inWin.sum() / inWin.size.toDouble()
                if (meanMotion < motionConfirmMean) continue
            }

            val bpms = window.map { it.bpm }
            val avg = Math.round(bpms.sum().toDouble() / bpms.size.toDouble()).toInt()
            // window is non-empty so max() always exists; `?: avg` is just a non-null fallback.
            val peak = bpms.maxOrNull() ?: avg
            val durMin = ((end - start) / 60L).toInt()
            results.add(DetectedWorkout(startSec = start, endSec = end, avgBpm = avg, peakBpm = peak, durationMin = durMin))
        }
        return results
    }

    /** Two closed [aStart, aEnd] / [bStart, bEnd] intervals overlap (touching endpoints count). */
    internal fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
        aStart <= bEnd && bStart <= aEnd
}

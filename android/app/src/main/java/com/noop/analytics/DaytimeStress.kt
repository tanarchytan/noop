package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/*
 * DaytimeStress.kt — an intraday (hour-by-hour) read of the SAME autonomic stress proxy
 * the daily Stress monitor shows, computed from the day's banked HR + R-R.
 *
 * Faithful Kotlin port of StrandAnalytics/DaytimeStress.swift (verified on macOS).
 *
 * The daily Stress score (StressScreen / StressView) maps "resting HR up + HRV down vs a
 * personal baseline" onto a 0–3 logistic. This helper applies that SAME math at the
 * per-hour grain so the Stress screen can show *when* in the day stress ran high — not a
 * new score. For each waking hour it computes:
 *
 *   • mean HR over the hour                    (HR up   = stress, like daily RHR)
 *   • RMSSD over the hour's clean R-R          (HRV down = stress, like daily avgHRV)
 *
 * and z-scores each against the day's OWN quiet reference (the calm-hour quartile + the
 * spread across hours), then squashes the z-sum onto 0–3 with the identical logistic
 *   stress = 3 / (1 + e^(−raw)). 0 calm · 1.5 baseline · 3 high — same bands as the daily
 * score. The day is its own baseline: a desk day with one tense afternoon reads that
 * afternoon as elevated *relative to that person's own calm hours*, no cloud, no history
 * needed beyond the day itself.
 *
 * "Sustained high stress" is an honest, conservative flag: the most recent
 * [sustainedHours] covered hours must ALL sit in the HIGH band (≥ [highBandFloor]). It
 * drives a passive in-app suggestion to run a Breathe session — never a notification.
 *
 * APPROXIMATE and non-clinical: an hour with too little data (few HR samples / too few
 * clean beats) is reported with a null level and never invented.
 */
object DaytimeStress {

    // MARK: - Tunables

    /** Minimum HR samples in an hour before its mean HR is trusted (~5 min at 1 Hz). */
    const val minHourHrSamples: Int = 300
    /** Bucket width for the timeline, in seconds (one hour). */
    const val bucketSeconds: Long = 3_600L
    /** Band floor for "high" on the shared 0–3 scale (matches StressBand.High). */
    const val highBandFloor: Double = 2.0
    /** Consecutive most-recent covered hours that must all be HIGH to flag sustained stress. */
    const val sustainedHours: Int = 3
    /** First/last local hour-of-day treated as "waking" for the timeline (06:00–22:00). */
    const val wakingStartHour: Int = 6
    const val wakingEndHour: Int = 22

    // MARK: - Output

    /**
     * One hour of the daytime timeline. [level] is the shared 0–3 stress proxy, or null when
     * the hour had too little signal to score honestly.
     */
    data class HourPoint(
        /** Hour-of-day on the LOCAL clock (0–23), the bucket this point covers. */
        val hour: Int,
        /** Unix seconds at the start of the bucket (wall-clock). */
        val startTs: Long,
        /** Shared 0–3 stress proxy for the hour, or null when no data. */
        val level: Double?,
        /** Mean HR over the hour (bpm), or null. */
        val meanHr: Double?,
        /** RMSSD over the hour's clean R-R (ms), or null (too few clean beats). */
        val rmssd: Double?,
    ) {
        /** True when the hour was scored (had enough HR to place on the curve). */
        val hasData: Boolean get() = level != null
    }

    /** The full daytime read: the hourly timeline plus the sustained-high summary. */
    data class Result(
        /** Waking-hour timeline, earliest → latest. Hours with no signal carry level == null. */
        val hours: List<HourPoint>,
        /** True when the most recent [sustainedHours] SCORED hours all sit in the HIGH band. */
        val sustainedHigh: Boolean,
        /** Count of trailing high hours backing [sustainedHigh] (0 when not sustained). */
        val sustainedRun: Int,
        /** Mean stress across the SCORED hours, or null when none were scorable. */
        val dayMean: Double?,
        /** Peak scored hour (highest level), or null. */
        val peak: HourPoint?,
    ) {
        /** The scored hours only (level non-null), in time order. */
        val scored: List<HourPoint> get() = hours.filter { it.level != null }

        companion object {
            /** Empty read — used when the day had no usable intraday HR at all. */
            val EMPTY = Result(emptyList(), sustainedHigh = false, sustainedRun = 0,
                dayMean = null, peak = null)
        }
    }

    // MARK: - Shared stress math (identical formula to the daily StressModel)

    private fun mean(xs: List<Double>): Double? =
        if (xs.isEmpty()) null else xs.sum() / xs.size

    /** Population standard deviation; 0 when there's no spread. (Matches StressMath.std.) */
    private fun std(xs: List<Double>, m: Double?): Double {
        if (m == null || xs.size <= 1) return 0.0
        val v = xs.sumOf { (it - m) * (it - m) } / xs.size
        return sqrt(v)
    }

    /**
     * Combined autonomic z-score. HR-up and HRV-down both push it positive — the SAME
     * directionality as the daily score (RHR up = stress, HRV down = stress).
     */
    private fun rawScore(
        hr: Double?, meanHr: Double?, sdHr: Double,
        rmssd: Double?, meanRmssd: Double?, sdRmssd: Double,
    ): Double {
        var sum = 0.0
        if (hr != null && meanHr != null && sdHr > 0.0001) {
            sum += (hr - meanHr) / sdHr            // HR up = stress
        }
        if (rmssd != null && meanRmssd != null && sdRmssd > 0.0001) {
            sum += (meanRmssd - rmssd) / sdRmssd   // HRV (RMSSD) down = stress
        }
        return sum
    }

    /**
     * Logistic squash of the raw z-sum onto 0–3 (baseline 0 → 1.5). Identical to
     * StressMath.squash, so an hourly point shares the daily score's scale and bands.
     */
    private fun squash(raw: Double): Double =
        (3.0 / (1.0 + exp(-raw))).coerceIn(0.0, 3.0)

    // MARK: - Public API

    /**
     * Build the daytime stress timeline from a day's banked HR + R-R.
     *
     * @param hr the day's HR samples (any order; bucketed by ts here).
     * @param rr the day's R-R intervals.
     * @param tzOffsetSeconds seconds east of UTC, for placing each bucket on the LOCAL clock
     *   (so "waking hours" and the hour labels are local). Defaults to UTC.
     *
     * Returns [Result.EMPTY] when there isn't a single hour with enough HR to score.
     */
    fun analyze(hr: List<HrSample>, rr: List<RrInterval>, tzOffsetSeconds: Long = 0L): Result {
        if (hr.isEmpty()) return Result.EMPTY

        // 1) Bucket HR + R-R into LOCAL hour-of-day buckets, keyed by the bucket start
        //    (floored to the hour on the local clock).
        val hrByBucket = HashMap<Long, MutableList<Double>>()
        for (s in hr) {
            val localTs = s.ts + tzOffsetSeconds
            val bucket = floorDiv(localTs, bucketSeconds) * bucketSeconds
            hrByBucket.getOrPut(bucket) { ArrayList() }.add(s.bpm.toDouble())
        }
        val rrByBucket = HashMap<Long, MutableList<Double>>()
        for (s in rr) {
            val localTs = s.ts + tzOffsetSeconds
            val bucket = floorDiv(localTs, bucketSeconds) * bucketSeconds
            rrByBucket.getOrPut(bucket) { ArrayList() }.add(s.rrMs.toDouble())
        }

        // 2) Per-hour mean HR + RMSSD (RMSSD via the shared HRV cleaner, so ectopic beats
        //    can't fabricate variability). An hour with < minHourHrSamples HR is left
        //    unscored (null level) — never invented.
        data class HourAgg(val bucket: Long, val meanHr: Double?, val rmssd: Double?)
        val orderedBuckets = hrByBucket.keys.sorted()
        val aggs = ArrayList<HourAgg>(orderedBuckets.size)
        for (b in orderedBuckets) {
            val hrs = hrByBucket[b] ?: emptyList<Double>()
            val mHr = if (hrs.size >= minHourHrSamples) mean(hrs) else null
            val rrRes = HrvAnalyzer.analyzeRaw(rrByBucket[b] ?: emptyList())
            aggs.add(HourAgg(b, mHr, rrRes.rmssd))
        }

        // 3) The day's OWN quiet reference: centre on the CALM end (the lower quartile of
        //    hourly mean HR, the upper quartile of hourly RMSSD), and spread from the
        //    across-hour SD. This makes a flat day read ~baseline and a spiky day surface its
        //    tense hours — without any cross-day history. Falls back to the plain mean when
        //    there are too few scored hours for a quartile.
        //
        //    Built from the WAKING hours only — the same hours scored in step 4. Sleep is the
        //    calmest, lowest-HR / highest-HRV stretch of the day, and the analysis window
        //    always begins at local midnight, so the current day routinely carries several
        //    hours of it. Letting those night hours into the reference drags the "calm" anchor
        //    far beneath every waking hour, inflating an ordinary calm day toward HIGH and
        //    falsely tripping the sustained-high Breathe nudge.
        val referenceAggs = aggs.filter { isWakingHour(it.bucket) }
        val hrMeans = referenceAggs.mapNotNull { it.meanHr }
        val rmssdVals = referenceAggs.mapNotNull { it.rmssd }
        val refHr = calmReference(hrMeans, calmIsLow = true)         // calm HR is LOW
        val refRmssd = calmReference(rmssdVals, calmIsLow = false)   // calm HRV is HIGH
        val sdHr = std(hrMeans, mean(hrMeans))
        val sdRmssd = std(rmssdVals, mean(rmssdVals))

        // 4) Score each waking-hour bucket on the shared 0–3 curve.
        val points = ArrayList<HourPoint>(aggs.size)
        for (a in aggs) {
            if (!isWakingHour(a.bucket)) continue
            val hourOfDay = (floorDiv(a.bucket, bucketSeconds) % 24).toInt()
            // The wall-clock bucket start (undo the local shift applied above).
            val wallStart = a.bucket - tzOffsetSeconds
            // Score only when HR cleared the count gate (HR is the always-available anchor;
            // RMSSD enriches it when beats allow).
            val level: Double? = if (a.meanHr != null) {
                squash(rawScore(a.meanHr, refHr, sdHr, a.rmssd, refRmssd, sdRmssd))
            } else {
                null
            }
            points.add(HourPoint(hourOfDay, wallStart, level, a.meanHr, a.rmssd))
        }

        val scored = points.mapNotNull { p -> p.level?.let { p to it } }
        if (scored.isEmpty()) {
            // No scorable waking hour — still return the (unscored) timeline so the UI can
            // show "not enough data" rather than nothing.
            return if (points.isEmpty()) Result.EMPTY
            else Result(points, sustainedHigh = false, sustainedRun = 0, dayMean = null, peak = null)
        }

        // 5) Sustained-high flag: walk back from the latest SCORED hour while each is HIGH.
        var run = 0
        for ((_, lvl) in scored.asReversed()) {
            if (lvl >= highBandFloor) run += 1 else break
        }
        val sustained = run >= sustainedHours

        val dayMean = mean(scored.map { it.second })
        val peak = scored.maxByOrNull { it.second }?.first

        return Result(points, sustained, run, dayMean, peak)
    }

    // MARK: - Helpers

    /**
     * Floor-division that is correct for negative numerators (so a local time just before
     * the UTC epoch still buckets to the hour below, not toward zero).
     */
    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        val r = a % b
        return if (r != 0L && (r < 0L) != (b < 0L)) q - 1 else q
    }

    /**
     * Whether a local hour-bucket start falls inside the waking window the timeline scores
     * (06:00–22:00). The single source of truth for "waking" — used both to build the calm
     * reference and to pick the hours to score, so the two can never drift apart.
     */
    private fun isWakingHour(bucket: Long): Boolean {
        val hourOfDay = (floorDiv(bucket, bucketSeconds) % 24).toInt()
        return hourOfDay >= wakingStartHour && hourOfDay < wakingEndHour
    }

    /**
     * The day's "calm" reference for a signal: the quartile toward the calm end (lower
     * quartile when calm is LOW, e.g. HR; upper quartile when calm is HIGH, e.g. RMSSD).
     * Falls back to the plain mean below 4 values, and to null when empty.
     */
    private fun calmReference(xs: List<Double>, calmIsLow: Boolean): Double? {
        if (xs.isEmpty()) return null
        if (xs.size < 4) return mean(xs)
        val s = xs.sorted()
        return if (calmIsLow) quantile(s, 0.25) else quantile(s, 0.75)
    }

    /** Linear-interpolated quantile of an already-sorted, non-empty list. */
    private fun quantile(sorted: List<Double>, q: Double): Double {
        val n = sorted.size
        if (n == 0) return 0.0   // defensive: callers guard emptiness; never index []
        if (n == 1) return sorted[0]
        val pos = q * (n - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, n - 1)
        val frac = pos - lo
        return sorted[lo] + frac * (sorted[hi] - sorted[lo])
    }
}

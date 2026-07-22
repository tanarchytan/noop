package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import uniffi.whoop_ffi.HourPointInfo

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

    // MARK: - Mean over an hour's samples (mean HR for a bucket)

    private fun mean(xs: List<Double>): Double? =
        if (xs.isEmpty()) null else xs.sum() / xs.size

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
        return scoreRust(bucketize(hr, rr, tzOffsetSeconds), tzOffsetSeconds)
    }

    /** One hour's aggregates: mean HR (null below the [minHourHrSamples] gate) + cleaned RMSSD. */
    internal data class HourAgg(val bucket: Long, val meanHr: Double?, val rmssd: Double?)

    /** Bucket the day's HR + R-R into LOCAL hour-of-day buckets and reduce each to (meanHr, rmssd). RMSSD
     *  runs through the shared HRV cleaner so ectopic beats can't fabricate variability. */
    internal fun bucketize(hr: List<HrSample>, rr: List<RrInterval>, tzOffsetSeconds: Long): List<HourAgg> {
        val hrByBucket = HashMap<Long, MutableList<Double>>()
        for (s in hr) {
            val bucket = floorDiv(s.ts + tzOffsetSeconds, bucketSeconds) * bucketSeconds
            hrByBucket.getOrPut(bucket) { ArrayList() }.add(s.bpm.toDouble())
        }
        val rrByBucket = HashMap<Long, MutableList<Double>>()
        for (s in rr) {
            val bucket = floorDiv(s.ts + tzOffsetSeconds, bucketSeconds) * bucketSeconds
            rrByBucket.getOrPut(bucket) { ArrayList() }.add(s.rrMs.toDouble())
        }
        val orderedBuckets = hrByBucket.keys.sorted()
        val aggs = ArrayList<HourAgg>(orderedBuckets.size)
        for (b in orderedBuckets) {
            val hrs = hrByBucket[b] ?: emptyList<Double>()
            val mHr = if (hrs.size >= minHourHrSamples) mean(hrs) else null
            val rrRes = HrvAnalyzer.analyzeRaw(rrByBucket[b] ?: emptyList())
            aggs.add(HourAgg(b, mHr, rrRes.rmssd))
        }
        return aggs
    }

    /** Score the hourly aggregates in whoop-rs (daytime_stress), then reassemble the full
     *  timeline (unscored hours kept for the UI). Adopts the whoop-rs peak on a tie (last hour). */
    internal fun scoreRust(aggs: List<HourAgg>, tzOffsetSeconds: Long): Result {
        val hourPoints = aggs.map { a ->
            HourPointInfo((floorDiv(a.bucket, bucketSeconds) % 24).toInt(), a.meanHr, a.rmssd)
        }
        val info = RustScores.daytimeStress(hourPoints)
        val scoredByHour = info.hours.associateBy { it.hour }

        val points = ArrayList<HourPoint>(aggs.size)
        for (a in aggs) {
            if (!isWakingHour(a.bucket)) continue
            val hourOfDay = (floorDiv(a.bucket, bucketSeconds) % 24).toInt()
            val wallStart = a.bucket - tzOffsetSeconds
            val level = if (a.meanHr != null) scoredByHour[hourOfDay]?.stress else null
            points.add(HourPoint(hourOfDay, wallStart, level, a.meanHr, a.rmssd))
        }
        if (points.isEmpty()) return Result.EMPTY
        val peak = info.peakHour?.let { ph -> points.firstOrNull { it.hour == ph } }
        return Result(points, info.sustainedHigh, info.sustainedRun.toInt(), info.dayMean, peak)
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
     * (06:00–22:00). Picks which hours [scoreRust] keeps when it reassembles the timeline.
     */
    private fun isWakingHour(bucket: Long): Boolean {
        val hourOfDay = (floorDiv(bucket, bucketSeconds) % 24).toInt()
        return hourOfDay >= wakingStartHour && hourOfDay < wakingEndHour
    }
}

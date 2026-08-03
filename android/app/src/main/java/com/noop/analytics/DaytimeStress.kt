package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import uniffi.whoop_ffi.HourPointInfo
import uniffi.whoop_ffi.SleepSpanMsInfo
import uniffi.whoop_ffi.WindowedStressInfo

/*
 * DaytimeStress.kt — an intraday (hour-by-hour) read of the SAME autonomic stress proxy
 * the daily Stress monitor shows, computed from the day's banked HR + R-R.
 *
 * The daily Stress score maps "resting HR up + HRV down vs a personal baseline" onto a
 * 0–3 logistic. This applies that SAME math per waking hour — mean HR (up = stress) and
 * RMSSD over the hour's clean R-R (down = stress) — z-scored against the day's OWN quiet
 * reference (calm-hour quartile + spread across hours), then squashed onto 0–3 via
 * stress = 3 / (1 + e^(−raw)). 0 calm · 1.5 baseline · 3 high, same bands as the daily
 * score. The day is its own baseline, no history needed beyond the day itself.
 *
 * "Sustained high stress" fires only when the most recent [sustainedHours] covered hours
 * ALL sit in the HIGH band; drives a passive Breathe suggestion, never
 * a notification.
 *
 * APPROXIMATE and non-clinical: an hour with too little data is reported with a null level
 * and never invented.
 */
object DaytimeStress {

    // MARK: - Tunables

    /** Minimum HR samples in an hour before its mean HR is trusted (~5 min at 1 Hz). */
    const val minHourHrSamples: Int = 300
    /** Bucket width for the timeline, in seconds (one hour). */
    const val bucketSeconds: Long = 3_600L
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
        /** Minutes in each band exactly as whoop-rs tallied them; never re-counted here. */
        val lowMinutes: Long,
        val mediumMinutes: Long,
        val highMinutes: Long,
    ) {
        /** The scored hours only (level non-null), in time order. */
        val scored: List<HourPoint> get() = hours.filter { it.level != null }

        companion object {
            /** Empty read — used when the day had no usable intraday HR at all. */
            val EMPTY = Result(emptyList(), sustainedHigh = false, sustainedRun = 0,
                dayMean = null, peak = null, lowMinutes = 0L, mediumMinutes = 0L, highMinutes = 0L)
        }
    }

    // MARK: - Mean over an hour's samples (mean HR for a bucket)

    private fun mean(xs: List<Double>): Double? =
        if (xs.isEmpty()) null else RustScores.mean(xs)

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
            val rrRes = RustScores.analyzeRaw(rrByBucket[b] ?: emptyList())
            aggs.add(HourAgg(b, mHr, rrRes.rmssd))
        }
        return aggs
    }

    /**
     * Score ONE SLEEP WINDOW's HR + R-R on the same formula and the same bands. whoop-rs applies no
     * hour-of-day filter here, so the caller passes only the night's samples; [SleepStressCard] and the
     * fourth sleep-performance driver read the band minutes and share it returns.
     */
    fun analyzeNight(hr: List<HrSample>, rr: List<RrInterval>, tzOffsetSeconds: Long = 0L): WindowedStressInfo =
        RustScores.sleepStress(toHourPoints(bucketize(hr, rr, tzOffsetSeconds), tzOffsetSeconds))

    /** One hour bucket's aggregates as the border's own record: the LOCAL hour-of-day for labelling and
     *  the bucket's WALL-CLOCK start in ms, which is the space whoop-rs tests the spans in. [motionG] is
     *  null until a per-bucket dynamic-accel channel is bucketed here, so the motion gate suppresses
     *  nothing today. */
    private fun toHourPoints(aggs: List<HourAgg>, tzOffsetSeconds: Long): List<HourPointInfo> =
        aggs.map {
            HourPointInfo(
                hour = hourOfDay(it.bucket),
                meanHr = it.meanHr,
                rmssd = it.rmssd,
                startMs = (it.bucket - tzOffsetSeconds) * 1_000L,
                motionG = null,
            )
        }

    /**
     * The spans of each covered LOCAL day that the timeline does not score — midnight to
     * [wakingStartHour] and [wakingEndHour] to midnight — as wall-clock `[start, end)` ms. whoop-rs no
     * longer carries an hour-of-day filter, so the caller says which window to ask for; this reproduces
     * the shipped 06:00-22:00 selection exactly. INTERIM: real sleep + nap spans belong here, and until
     * they are wired a late night still anchors its own calm reference.
     */
    private fun nonWakingSpans(aggs: List<HourAgg>, tzOffsetSeconds: Long): List<SleepSpanMsInfo> =
        aggs.map { floorDiv(it.bucket, CalendarDay.SECONDS_PER_DAY) }.distinct().sorted().flatMap { day ->
            val midnight = (day * CalendarDay.SECONDS_PER_DAY - tzOffsetSeconds) * 1_000L
            listOf(
                SleepSpanMsInfo(midnight, midnight + wakingStartHour * bucketSeconds * 1_000L),
                SleepSpanMsInfo(
                    midnight + wakingEndHour * bucketSeconds * 1_000L,
                    midnight + CalendarDay.SECONDS_PER_DAY * 1_000L,
                ),
            )
        }

    /** Score the hourly aggregates in whoop-rs (daytime_stress), then reassemble the full
     *  timeline (unscored hours kept for the UI). Adopts the whoop-rs peak on a tie (last hour). */
    internal fun scoreRust(aggs: List<HourAgg>, tzOffsetSeconds: Long): Result {
        val info = RustScores.daytimeStress(
            toHourPoints(aggs, tzOffsetSeconds), nonWakingSpans(aggs, tzOffsetSeconds),
        )
        val scoredByHour = info.hours.associateBy { it.hour }

        val points = ArrayList<HourPoint>(aggs.size)
        for (a in aggs) {
            if (!isWakingHour(a.bucket)) continue
            val hour = hourOfDay(a.bucket)
            val wallStart = a.bucket - tzOffsetSeconds
            val level = if (a.meanHr != null) scoredByHour[hour]?.stress else null
            points.add(HourPoint(hour, wallStart, level, a.meanHr, a.rmssd))
        }
        if (points.isEmpty()) return Result.EMPTY
        val peak = info.peakHour?.let { ph -> points.firstOrNull { it.hour == ph } }
        return Result(points, info.sustainedHigh, info.sustainedRun.toInt(), info.dayMean, peak,
            info.lowMinutes, info.mediumMinutes, info.highMinutes)
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

    /** The LOCAL hour-of-day (0–23) a bucket start falls in — the key the border's records carry. */
    private fun hourOfDay(bucket: Long): Int = (floorDiv(bucket, bucketSeconds) % 24).toInt()

    /**
     * Whether a local hour-bucket start falls inside the waking window the timeline scores
     * (06:00–22:00). Picks which hours [scoreRust] keeps when it reassembles the timeline.
     */
    private fun isWakingHour(bucket: Long): Boolean =
        hourOfDay(bucket) >= wakingStartHour && hourOfDay(bucket) < wakingEndHour
}

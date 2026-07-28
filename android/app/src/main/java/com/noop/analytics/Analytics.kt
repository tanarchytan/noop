package com.noop.analytics

import com.noop.data.DailyMetric
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Heart-rate variability.
 *
 * RMSSD = root-mean-square of successive R-R interval differences (milliseconds in,
 * milliseconds out).
 */
object Hrv {
    /**
     * Root mean square of successive differences over a list of R-R intervals (ms).
     *
     * Returns 0.0 when fewer than two intervals are available.
     */
    fun rmssd(rr: List<Int>): Double {
        if (rr.size < 2) return 0.0
        var sum = 0.0
        var n = 0
        for (i in 1 until rr.size) {
            val d = (rr[i] - rr[i - 1]).toDouble()
            sum += d * d
            n += 1
        }
        return if (n > 0) sqrt(sum / n.toDouble()) else 0.0
    }
}

/**
 * Heart-rate training zones.
 *
 * Zone ladder on pct = hr / maxHR: pct >= 0.9 → 5, >= 0.8 → 4, >= 0.7 → 3, >= 0.6 → 2, else 1.
 */
object Zones {
    /**
     * Zone (1..5) for a heart rate given an estimated maximum heart rate.
     *
     * If [hrMax] is non-positive the percentage is undefined, so this falls back to the
     * lowest zone.
     */
    fun zone(hr: Int, hrMax: Int): Int {
        if (hrMax <= 0) return 1
        val pct = hr.toDouble() / hrMax.toDouble()
        return when {
            pct >= 0.9 -> 5
            pct >= 0.8 -> 4
            pct >= 0.7 -> 3
            pct >= 0.6 -> 2
            else -> 1
        }
    }

    /**
     * Tanaka maximum-heart-rate estimate: round(208 - 0.7 * age).
     */
    fun hrMaxTanaka(age: Int): Int = (208.0 - 0.7 * age).roundToInt()
}

/**
 * Illness / strain early-warning.
 *
 * Compares the last ~2 days against a ~28-day baseline ending 3 days ago across resting
 * HR, HRV, skin-temperature deviation and respiration. Two or more anomalies surface a
 * banner; the classic early-illness signature is RHR up + HRV down + skin-temp up.
 *
 * Gating on a user toggle is a UI concern this pure function omits; callers decide
 * whether to run it.
 */
object IllnessWatch {
    /**
     * Evaluate the [days] history (oldest -> newest). Returns a human-readable banner
     * message when 2+ anomaly flags fire, otherwise null.
     *
     * Requires at least 14 days of history.
     */
    fun evaluate(days: List<DailyMetric>): String? {
        if (days.size < 14) return null

        val recent = days.takeLast(2)
        // ~28 days ending 3 days ago: take the last 31, drop the most recent 3.
        val base = days.takeLast(31).dropLast(3)

        fun mean(vals: List<Double>): Double? =
            if (vals.isEmpty()) null else vals.sum() / vals.size.toDouble()

        fun rm(selector: (DailyMetric) -> Double?): Double? =
            mean(recent.mapNotNull(selector))

        fun bm(selector: (DailyMetric) -> Double?): Double? =
            mean(base.mapNotNull(selector))

        val flags = mutableListOf<String>()

        run {
            val r = rm { it.restingHr?.toDouble() }
            val b = bm { it.restingHr?.toDouble() }
            if (r != null && b != null && r >= b + 5) {
                flags.add("resting HR +${(r - b).roundToInt()} bpm")
            }
        }

        run {
            val r = rm { it.avgHrv }
            val b = bm { it.avgHrv }
            if (r != null && b != null && b > 0 && r <= b * 0.80) {
                flags.add("HRV −${((1 - r / b) * 100).roundToInt()}%")
            }
        }

        run {
            val r = rm { it.skinTempDevC }
            if (r != null && r >= 0.6) {
                flags.add("skin temp +${formatOneDp(r)}°C")
            }
        }

        run {
            // respRateBpm may be a clean cloud value or a noisier on-device RSA estimate, with no
            // source flag to tell them apart, so this gates conservatively: needs >=10 baseline
            // nights, restricts to physiologically plausible 8-25 bpm, and uses a wide +2.5 bpm margin.
            val respBase = base.mapNotNull { it.respRateBpm }
            val r = rm { it.respRateBpm }
            val b = bm { it.respRateBpm }
            val plausible = { v: Double -> v in 8.0..25.0 }
            if (r != null && b != null && respBase.size >= 10 &&
                plausible(r) && plausible(b) && r >= b + 2.5
            ) {
                flags.add("respiration up")
            }
        }

        return if (flags.size >= 2) {
            "Your body looks strained - " + flags.joinToString(", ") +
                ". Consider taking it easy."
        } else {
            null
        }
    }

    /** Format a double to one decimal place (locale-independent), matching "%.1f". */
    private fun formatOneDp(value: Double): String {
        val scaled = (value * 10.0).roundToInt()
        val whole = scaled / 10
        val frac = kotlin.math.abs(scaled % 10)
        return "$whole.$frac"
    }
}

package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * App-side parity gate for routing the whole-day calorie estimate through whoop-rs. The APPROXIMATE
 * HR-only day energy (Keytel 2005 active + revised Harris-Benedict BMR) is moving from the Kotlin
 * [Calories.estimateDayCalories] to physio-algo (via the [RustScores.caloriesDay] bridge -> uniffi ->
 * whoop-rs `calories_estimate_day`); this pins the swap by replaying the SAME HR day + profile through
 * BOTH paths and asserting the returned kcal is bit-for-bit identical (the exact Double
 * [AnalyticsEngine] stores as `DailyMetric.activeKcal`). Loads the host libwhoop_ffi via JNA.
 *
 * One divergence point, OUTSIDE the store domain: whoop-rs clamps `hr_reserve = (hrmax - resting).max(1.0)`
 * where the Kotlin path uses it raw. They agree for every real input (hrmax is Tanaka ~184, resting ~50),
 * so the store site is bit-identical; a degenerate `hrmax <= resting + 1` is the only place they part, and
 * that is never a real stored value. The tests below stay in the real domain on purpose.
 */
class RustCaloriesParityTest {

    private val dev = "t"
    private val start = 1_783_651_476L

    private fun assertBitEq(msg: String, kotlin: Double, rust: Double) {
        assertEquals(
            "$msg (kotlin=$kotlin rust=$rust)",
            java.lang.Double.doubleToLongBits(kotlin),
            java.lang.Double.doubleToLongBits(rust),
        )
    }

    private fun assertCaloriesParity(
        label: String,
        hr: List<HrSample>,
        profile: UserProfile,
        hrmax: Double?,
        restingHR: Double?,
    ) {
        val kotlin = Calories.estimateDayCalories(hr, profile, hrmax, restingHR)
        val rust = RustScores.caloriesDay(hr, profile, hrmax, restingHR)
        assertBitEq("$label [sex=${profile.sex} hrmax=$hrmax rhr=$restingHR n=${hr.size}]", kotlin, rust)
    }

    /** A dense 1 Hz day crossing rest, moderate and hard HR so both the below-gate resting rate and the
     *  above-gate Keytel rate (with its resting floor) are exercised. */
    private fun mixedDay(n: Int = 4800): List<HrSample> = (0 until n).map { i ->
        val bpm = when {
            i < 1200 -> 58
            i < 2400 -> 95 + (i / 9) % 20 // low-moderate, straddles the day gate
            i < 3600 -> 150 + (i / 7) % 30 // hard block
            else -> 62 + i % 9
        }
        HrSample(dev, start + i, bpm)
    }

    @Test
    fun `store-site config parity on a real-shaped day`() {
        val hr = mixedDay()
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 33.0, sex = "male")
        assertCaloriesParity("store-site", hr, profile, 184.9, 52.0)
    }

    @Test
    fun `parity holds for every sex coefficient branch`() {
        val hr = mixedDay()
        for (sex in listOf("male", "female", "nonbinary")) {
            val profile = UserProfile(weightKg = 75.0, heightCm = 175.0, age = 40.0, sex = sex)
            assertCaloriesParity("sex-$sex", hr, profile, 180.0, 55.0)
        }
    }

    @Test
    fun `null hrmax and resting fall back identically`() {
        val hr = mixedDay()
        val profile = UserProfile(weightKg = 70.0, heightCm = 170.0, age = 30.0, sex = "female")
        assertCaloriesParity("null-fallbacks", hr, profile, null, null)
    }

    @Test
    fun `all-resting light day matches on both paths`() {
        val light = (0 until 2000).map { HrSample(dev, start + it, 60) }
        val profile = UserProfile(weightKg = 68.0, heightCm = 172.0, age = 28.0, sex = "male")
        assertCaloriesParity("light-day", light, profile, 187.4, 58.0)
    }

    @Test
    fun `empty day is zero on both paths`() {
        val profile = UserProfile(sex = "male")
        assertCaloriesParity("empty", emptyList(), profile, 184.0, 52.0)
    }
}

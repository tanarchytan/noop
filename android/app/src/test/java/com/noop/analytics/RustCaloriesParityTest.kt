package com.noop.analytics

import com.noop.data.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FFI smoke test for the whole-day calorie estimate ([RustScores.caloriesDay] -> uniffi -> whoop-rs
 * `calories_estimate_day`). The Keytel 2005 active + revised Harris-Benedict BMR model, the day HRR gate
 * and the resting floor all live in physio-algo; this pins the marshalled kcal behaviour (empty day is
 * zero, an active day burns more than a resting one, every sex branch and the null fallbacks return a
 * finite positive). Exact kcal is frozen by the Rust golden tests. Loads the host libwhoop_ffi via JNA.
 */
class RustCaloriesParityTest {

    private val dev = "t"
    private val start = 1_783_651_476L

    /** A dense 1 Hz day crossing rest, moderate and hard HR. */
    private fun mixedDay(n: Int = 4800): List<HrSample> = (0 until n).map { i ->
        val bpm = when {
            i < 1200 -> 58
            i < 2400 -> 95 + (i / 9) % 20
            i < 3600 -> 150 + (i / 7) % 30
            else -> 62 + i % 9
        }
        HrSample(dev, start + i, bpm)
    }

    @Test
    fun `empty day is zero`() {
        assertEquals(0.0, RustScores.caloriesDay(emptyList(), UserProfile(sex = "male"), 184.0, 52.0), 0.0)
    }

    @Test
    fun `an active day burns more than an all-resting day`() {
        val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 33.0, sex = "male")
        val resting = (0 until 4800).map { HrSample(dev, start + it, 58) }
        val active = RustScores.caloriesDay(mixedDay(), profile, 184.9, 52.0)
        val rest = RustScores.caloriesDay(resting, profile, 184.9, 52.0)
        assertTrue("resting day is positive (BMR)", rest > 0.0)
        assertTrue("active day exceeds resting", active > rest)
    }

    @Test
    fun `every sex coefficient branch returns a finite positive`() {
        for (sex in listOf("male", "female", "nonbinary")) {
            val profile = UserProfile(weightKg = 75.0, heightCm = 175.0, age = 40.0, sex = sex)
            val kcal = RustScores.caloriesDay(mixedDay(), profile, 180.0, 55.0)
            assertTrue("sex=$sex finite positive", kcal.isFinite() && kcal > 0.0)
        }
    }

    @Test
    fun `null hrmax and resting fall back to a finite positive`() {
        val profile = UserProfile(weightKg = 70.0, heightCm = 170.0, age = 30.0, sex = "female")
        val kcal = RustScores.caloriesDay(mixedDay(), profile, null, null)
        assertTrue(kcal.isFinite() && kcal > 0.0)
    }
}

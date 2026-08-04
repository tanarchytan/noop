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
 * finite positive), the resting/active split, and that a confirmed bout changes the billing. Exact kcal
 * is frozen by the Rust golden tests. Loads the host libwhoop_ffi via JNA.
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

    /** A detected bout over [from, to]; only its span reaches the calorie model. */
    private fun bout(from: Long, to: Long) = ExerciseSession(
        start = from, end = to, avgHR = 150.0, peakHR = 170, strain = null,
        durationS = (to - from).toDouble(), zoneTimePct = emptyMap(), avgHRRPct = null,
        hrmax = null, hrmaxSource = "unknown", caloriesKcal = null, caloriesKJ = null,
    )

    private val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 33.0, sex = "male")

    @Test
    fun `empty day is zero in every part`() {
        val d = RustScores.caloriesDay(emptyList(), emptyList(), UserProfile(sex = "male"), 184.0, 52.0)
        assertEquals(0.0, d.totalKcal, 0.0)
        assertEquals(0.0, d.restingKcal, 0.0)
        assertEquals(0.0, d.activeKcal, 0.0)
    }

    @Test
    fun `an active day burns more than an all-resting day`() {
        val resting = (0 until 4800).map { HrSample(dev, start + it, 58) }
        val active = RustScores.caloriesDay(mixedDay(), emptyList(), profile, 184.9, 52.0)
        val rest = RustScores.caloriesDay(resting, emptyList(), profile, 184.9, 52.0)
        assertTrue("resting day is positive (BMR)", rest.totalKcal > 0.0)
        assertTrue("active day exceeds resting", active.totalKcal > rest.totalKcal)
    }

    /** The split is what the daily column stores, so it is asserted rather than assumed: a day spent at
     *  rest has a real BMR total and almost no excess over lying still. */
    @Test
    fun `the split adds up and a resting day carries almost no active energy`() {
        val resting = (0 until 4800).map { HrSample(dev, start + it, 58) }
        val d = RustScores.caloriesDay(resting, emptyList(), profile, 184.9, 52.0)
        assertEquals(d.totalKcal, d.restingKcal + d.activeKcal, 1e-9)
        assertTrue("BMR dominates a resting day", d.restingKcal > 0.9 * d.totalKcal)

        val mixed = RustScores.caloriesDay(mixedDay(), emptyList(), profile, 184.9, 52.0)
        assertEquals(mixed.totalKcal, mixed.restingKcal + mixed.activeKcal, 1e-9)
        assertTrue("an active day carries real excess", mixed.activeKcal > 0.0)
    }

    /** The bouts argument exists so a confirmed second is billed on the bout's 0.30 HRR gate instead of
     *  the stricter 0.50 one. If passing it changed nothing, the argument would be decoration and the day
     *  could still double-bill against the per-workout estimate.
     *
     *  The span must be the MODERATE block: at 80 kg / 184.9 hrmax / 52 resting the two gates sit at 92
     *  and 118 bpm, so only 95-114 bpm can tell them apart. Over the hard block both gates already pass
     *  and the test would compare a number against itself. */
    @Test
    fun `a confirmed bout changes what the day bills`() {
        val hr = mixedDay()
        val without = RustScores.caloriesDay(hr, emptyList(), profile, 184.9, 52.0)
        val with = RustScores.caloriesDay(hr, listOf(bout(start + 1200, start + 2400)), profile, 184.9, 52.0)
        assertTrue("a bout must raise the day's active energy", with.activeKcal > without.activeKcal)
        assertEquals(with.totalKcal, with.restingKcal + with.activeKcal, 1e-9)
    }

    /** A bout over seconds that already clear the stricter gate bills the same either way — the guard
     *  that keeps the test above honest about WHY it passes. */
    @Test
    fun `a bout over already-hard seconds changes nothing`() {
        val hr = mixedDay()
        val without = RustScores.caloriesDay(hr, emptyList(), profile, 184.9, 52.0)
        val with = RustScores.caloriesDay(hr, listOf(bout(start + 2400, start + 3600)), profile, 184.9, 52.0)
        assertEquals(without.activeKcal, with.activeKcal, 1e-9)
    }

    @Test
    fun `every sex coefficient branch returns a finite positive`() {
        for (sex in listOf("male", "female", "nonbinary")) {
            val p = UserProfile(weightKg = 75.0, heightCm = 175.0, age = 40.0, sex = sex)
            val d = RustScores.caloriesDay(mixedDay(), emptyList(), p, 180.0, 55.0)
            assertTrue("sex=$sex finite positive", d.totalKcal.isFinite() && d.totalKcal > 0.0)
        }
    }

    /** An absent hrmax is resolved by whoop-rs from the day's own peak, then age — the app passes the
     *  null through rather than substituting a constant of its own. */
    @Test
    fun `null hrmax and resting fall back to a finite positive`() {
        val p = UserProfile(weightKg = 70.0, heightCm = 170.0, age = 30.0, sex = "female")
        val d = RustScores.caloriesDay(mixedDay(), emptyList(), p, null, null)
        assertTrue(d.totalKcal.isFinite() && d.totalKcal > 0.0)
    }
}

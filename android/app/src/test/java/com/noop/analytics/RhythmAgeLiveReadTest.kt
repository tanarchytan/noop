package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/**
 * The Health hub reads Rhythm Age LIVE off the same rest-activity samples the body clock is fitted on,
 * rather than the stored `rhythm_age` rows (a day can hold one per strap with no precedence rule). These
 * pin the gate that read applies and that it is the SAME computation the store-side pass persists.
 */
class RhythmAgeLiveReadTest {

    /** A 24 h rest-activity profile in the strap's own units (g), one sample per hour over [days] days. */
    private fun samples(mesorG: Double, ampG: Double, acrophase: Double, days: Int)
        : List<uniffi.whoop_ffi.ActivitySample> {
        val out = ArrayList<uniffi.whoop_ffi.ActivitySample>()
        for (d in 0 until days) {
            for (h in 0 until 24) {
                val v = mesorG + ampG * cos(2.0 * PI * (h - acrophase) / 24.0)
                out.add(uniffi.whoop_ffi.ActivitySample((d * 24L + h) * 3600L, v))
            }
        }
        return out
    }

    private fun evaluate(
        samples: List<uniffi.whoop_ffi.ActivitySample>,
        age: Double = 47.0,
        sex: String = "male",
    ) = V5HealthSignals.evaluate(
        days = emptyList(),
        cycleOptedIn = false,
        activitySamples = samples,
        tzOffsetSeconds = 0L,
        chronologicalAge = age,
        sex = sex,
    )

    /** One day short of the floor offers nothing: a few days fit a spurious rhythm, so the card counts
     *  up instead of naming a year. */
    @Test
    fun belowTheWornDayFloorNoAgeIsOffered() {
        val days = CircadianEngine.MIN_WORN_DAYS - 1
        val snap = evaluate(samples(0.030, 0.025, 15.0, days))
        assertNull("under the floor the engine must not name an age", snap.rhythmAge)
        assertEquals("the card still has a day count to show", days, snap.restActivityWornDays)
    }

    /** At the floor the age appears, and it is EXACTLY what whoop-rs returns for those samples — the live
     *  read is the store-side computation, not a second one. */
    @Test
    fun atTheWornDayFloorTheAgeMatchesTheEngine() {
        val s = samples(0.030, 0.025, 15.0, CircadianEngine.MIN_WORN_DAYS)
        val snap = evaluate(s)
        val direct = RustScores.rhythmAge(s, 0L, 47.0, RustScores.sexInput("male"))
        assertNotNull("at the floor an age is offered", snap.rhythmAge)
        assertNotNull(direct)
        assertEquals(direct!!.cosinorAgeYears, snap.rhythmAge!!.cosinorAgeYears, 0.0)
        assertEquals(direct.advanceYears, snap.rhythmAge!!.advanceYears, 0.0)
        assertEquals(CircadianEngine.MIN_WORN_DAYS, snap.restActivityWornDays)
    }

    /** Worn days are DISTINCT local days, not sample count: a fortnight's worth of samples crammed into
     *  three days must not unlock the gate. */
    @Test
    fun theFloorCountsDistinctDaysNotSamples() {
        val dense = ArrayList<uniffi.whoop_ffi.ActivitySample>()
        for (d in 0 until 3) {
            for (m in 0 until 24 * 12) {
                val h = m / 12.0
                dense.add(
                    uniffi.whoop_ffi.ActivitySample(
                        (d * 86_400L) + (m * 300L),
                        0.030 + 0.025 * cos(2.0 * PI * (h - 15.0) / 24.0),
                    ),
                )
            }
        }
        val snap = evaluate(dense)
        assertEquals(3, snap.restActivityWornDays)
        assertNull("864 samples over 3 days is still 3 days", snap.rhythmAge)
    }

    /** Enough wear but a flat rhythm: whoop-rs refuses the fit, so the card says so instead of
     *  fabricating a year from noise. */
    @Test
    fun aFlatRhythmYieldsNoAgeEvenWithEnoughWear() {
        val snap = evaluate(samples(0.030, 0.0005, 15.0, 21))
        assertEquals(21, snap.restActivityWornDays)
        assertNull("a flat profile must not produce an age", snap.rhythmAge)
    }

    /** Without a real age there is nothing to transform against, so nothing is offered. */
    @Test
    fun noAgeIsOfferedWithoutAProfileAge() {
        val snap = evaluate(samples(0.030, 0.025, 15.0, 21), age = 0.0)
        assertNull(snap.rhythmAge)
    }

    /** The body clock and Rhythm Age come off ONE fit, so a snapshot can never hold an age without the
     *  phase estimate that produced it. */
    @Test
    fun theAgeAndTheBodyClockShareOneFit() {
        val snap = evaluate(samples(0.030, 0.025, 15.0, 21))
        assertNotNull(snap.rhythmAge)
        assertNotNull("the same samples must also yield the body clock", snap.bodyClock)
    }

    /** No samples at all leaves both readings absent and the day count at zero, which is what keeps the
     *  card off a strap that banks no motion. */
    @Test
    fun noMotionLeavesTheCardWithNothingToCountUp() {
        val snap = evaluate(emptyList())
        assertNull(snap.rhythmAge)
        assertNull(snap.bodyClock)
        assertEquals(0, snap.restActivityWornDays)
    }

    /** The one profile-tag mapping both the store pass and the live read hand whoop-rs. */
    @Test
    fun sexInputMapsTheProfileTag() {
        assertEquals(uniffi.whoop_ffi.SexInput.MALE, RustScores.sexInput("male"))
        assertEquals(uniffi.whoop_ffi.SexInput.FEMALE, RustScores.sexInput("Female"))
        assertEquals(uniffi.whoop_ffi.SexInput.UNKNOWN, RustScores.sexInput("nonbinary"))
        assertEquals(uniffi.whoop_ffi.SexInput.UNKNOWN, RustScores.sexInput(""))
    }
}

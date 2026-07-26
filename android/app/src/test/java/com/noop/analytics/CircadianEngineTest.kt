package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/** The cosinor + phase estimate run in whoop-rs; these pin the FFI result and the Kotlin wording/planner. */
class CircadianEngineTest {

    /** A 24 h rest-activity profile as raw (unix, motion) samples — one per hour, over [days] days. */
    private fun samples(mesor: Double, amp: Double, acrophase: Double, days: Int)
        : List<uniffi.whoop_ffi.ActivitySample> {
        val out = ArrayList<uniffi.whoop_ffi.ActivitySample>()
        for (d in 0 until days) {
            for (h in 0 until 24) {
                val v = mesor + amp * cos(2.0 * PI * (h - acrophase) / 24.0)
                out.add(uniffi.whoop_ffi.ActivitySample((d * 24L + h) * 3600L, v))
            }
        }
        return out
    }

    private fun phase(mesor: Double, amp: Double, acrophase: Double, days: Int, wake: Double = 7.0,
                      tempMin: Double? = null) =
        RustScores.circadianPhase(samples(mesor, amp, acrophase, days), 0L, days, wake, tempMin)

    @Test fun rustCosinorRecoversTheInjectedAcrophase() {
        val est = phase(50.0, 30.0, 15.0, 20)!!
        assertEquals(15.0, est.acrophaseHours, 1e-6)
    }

    @Test fun rustAcrophaseWrapsIntoDay() {
        val est = phase(10.0, 5.0, 23.0, 20)!!
        assertEquals(23.0, est.acrophaseHours, 1e-6)
        assertTrue(est.acrophaseHours in 0.0..24.0)
    }

    @Test fun strongRhythmEnoughDaysIsSolid() {
        val est = CircadianEngine.fromRust(phase(50.0, 30.0, 15.0, 20)!!)
        assertEquals(CircadianEngine.PhaseConfidence.SOLID, est.confidence)
        assertEquals(3.0, est.tempMinHour, 1e-6)
    }

    @Test fun thinDataIsUnreadable() {
        val est = CircadianEngine.fromRust(phase(50.0, 30.0, 15.0, 4)!!)
        assertEquals(CircadianEngine.PhaseConfidence.UNREADABLE, est.confidence)
        assertTrue(est.note.lowercase().contains("hard to read"))
    }

    @Test fun arrhythmicProfileIsUnreadable() {
        val est = CircadianEngine.fromRust(phase(50.0, 0.5, 15.0, 30)!!)
        assertEquals(CircadianEngine.PhaseConfidence.UNREADABLE, est.confidence)
    }

    @Test fun observedTempMinOverridesDerived() {
        val est = CircadianEngine.fromRust(phase(50.0, 30.0, 15.0, 20, tempMin = 4.5)!!)
        assertEquals(4.5, est.tempMinHour, 1e-9)
    }

    @Test fun fromRustMapsLeanOntoTheCardWording() {
        // A late body clock against an early schedule reads "later"; the mapper turns that into the
        // night-owl sentence the BodyClockCard shows.
        val late = CircadianEngine.fromRust(phase(50.0, 30.0, 18.0, 20, wake = 6.0)!!)
        assertEquals(CircadianEngine.PhaseConfidence.SOLID, late.confidence)
        assertTrue(late.offsetVsScheduleMinutes > 20.0)
        assertTrue(late.note.contains("night-owl"))
    }

    @Test fun eastwardAdvancePlanUsesMorningLight() {
        val plan = CircadianEngine.planShift(3.0, 23.0, 7.0)
        assertEquals(CircadianEngine.ShiftDirection.ADVANCE, plan.direction)
        assertEquals(3, plan.estimatedDays)
        assertEquals(3, plan.days.size)
        val last = plan.days.last()
        assertEquals(20.0, last.targetSleepHour, 1e-9)
        assertEquals(4.0, last.targetWakeHour, 1e-9)
        assertEquals(4.0, last.brightLightStartHour, 1e-9)
        assertTrue(last.guidance.contains("bright light early"))
    }

    @Test fun westwardDelayPlanUsesEveningLight() {
        val plan = CircadianEngine.planShift(-2.0, 23.0, 7.0)
        assertEquals(CircadianEngine.ShiftDirection.DELAY, plan.direction)
        assertEquals(2, plan.estimatedDays)
        val last = plan.days.last()
        assertEquals(1.0, last.targetSleepHour, 1e-9)
        assertEquals(9.0, last.targetWakeHour, 1e-9)
        assertTrue(last.guidance.contains("bright light in the evening"))
    }

    @Test fun noShiftNeededReturnsNonePlan() {
        val plan = CircadianEngine.planShift(0.2, 23.0, 7.0)
        assertEquals(CircadianEngine.ShiftDirection.NONE, plan.direction)
        assertTrue(plan.days.isEmpty())
    }

    @Test fun planNeverMentionsSupplements() {
        val banned = listOf("melatonin", "supplement", "pill", "drug", "caffeine pill", "medication")
        for (shift in listOf(3.0, -3.0, 6.0, -1.0)) {
            val plan = CircadianEngine.planShift(shift, 23.0, 7.0)
            var text = plan.note.lowercase()
            for (d in plan.days) text += " " + d.guidance.lowercase()
            for (b in banned) assertFalse("plan mentioned $b", text.contains(b))
        }
    }

    @Test fun steppedAtOneHourPerDay() {
        val plan = CircadianEngine.planShift(6.0, 23.0, 7.0)
        assertEquals(6, plan.estimatedDays)
        assertEquals(6, plan.days.size)
    }

    @Test fun clockFormatting() {
        assertEquals("20:00", CircadianEngine.clock(20.0))
        assertEquals("23:30", CircadianEngine.clock(23.5))
        assertEquals("23:00", CircadianEngine.clock(-1.0))
        assertEquals("07:15", CircadianEngine.clock(7.25))
    }
}
